"""Traceable training launcher: records each run in runs/<UTC>-<exp>/ and wraps YOLOX tools/train.py.

The YOLOX trainer runs in a subprocess (`python -I -X utf8 -m cuentapepas.yolox_bridge train ...`) whose combined
stdout/stderr is mirrored to the console and to `<run_dir>/train.log`. YOLOX checkpoints, its own log and
TensorBoard events land in `<run_dir>/<exp_name>/`.
"""
from __future__ import annotations

import datetime as dt
import hashlib
import importlib.metadata
import json
import os
import random
import runpy
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

from cuentapepas.taxonomy import REPO_ROOT
from cuentapepas.yolox_bridge import YOLOX_COMMIT, YOLOX_DIR, ensure_yolox

DEFAULT_SEED = 20261011
EXP_FILE = REPO_ROOT / "ml" / "exps" / "cuentapepas_nano.py"
DEFAULT_DATASET = REPO_ROOT / "data" / "local" / "processed" / "croppie_v1"
DEFAULT_MANIFEST = REPO_ROOT / "data" / "manifests" / "croppie_v1.jsonl"
DEFAULT_PRETRAINED = REPO_ROOT / "data" / "local" / "weights" / "yolox_nano_coco.pth"
RUNS_DIR = REPO_ROOT / "runs"
SMOKE_IMAGES = 32
SPLITS = ("train", "val")
# Same defaults as ml/exps/cuentapepas_nano.py reads; resolved here so run.json records the values actually used.
DEFAULT_MULTISCALE = "1"
DEFAULT_NO_AUG_EPOCHS = "15"


@dataclass(frozen=True)
class TrainConfig:
    batch: int = 8
    epochs: int = 120
    workers: int = 2
    smoke: bool = False
    seed: int = DEFAULT_SEED
    pretrained: Path | None = DEFAULT_PRETRAINED
    dataset: Path = DEFAULT_DATASET
    manifest: Path = DEFAULT_MANIFEST
    exp_file: Path = EXP_FILE
    runs_dir: Path = RUNS_DIR

    @property
    def exp_name(self) -> str:
        return self.exp_file.stem


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _link_or_copy(src: Path, dst: Path) -> None:
    try:
        os.link(src, dst)
    except OSError:  # different volume or no hardlink support
        shutil.copy2(src, dst)


def make_subset(src: Path, dst: Path, n: int = SMOKE_IMAGES, seed: int = DEFAULT_SEED) -> dict[str, int]:
    """Write a COCO dataset at `dst` with `n` seeded-random images per split of `src` (annotations kept as is).

    Returns the number of images written per split.
    """
    counts = {}
    for split in SPLITS:
        with open(src / "annotations" / f"{split}.json", encoding="utf-8") as fh:
            coco = json.load(fh)
        images = sorted(coco["images"], key=lambda im: im["id"])
        chosen = sorted(random.Random(seed).sample(images, min(n, len(images))), key=lambda im: im["id"])
        chosen_ids = {im["id"] for im in chosen}
        subset = {
            "images": chosen,
            "annotations": [a for a in coco["annotations"] if a["image_id"] in chosen_ids],
            "categories": coco["categories"],
        }
        (dst / "annotations").mkdir(parents=True, exist_ok=True)
        (dst / split).mkdir(parents=True, exist_ok=True)
        with open(dst / "annotations" / f"{split}.json", "w", encoding="utf-8", newline="\n") as fh:
            json.dump(subset, fh, separators=(",", ":"))
        for im in chosen:
            _link_or_copy(src / split / im["file_name"], dst / split / im["file_name"])
        counts[split] = len(chosen)
    return counts


def _git(*args: str, cwd: Path = REPO_ROOT) -> str:
    return subprocess.run(["git", "-C", str(cwd), *args], capture_output=True, text=True, check=True).stdout


def git_state(repo: Path = REPO_ROOT) -> dict:
    status = [line for line in _git("status", "--porcelain", cwd=repo).splitlines() if line.strip()]
    return {"commit": _git("rev-parse", "HEAD", cwd=repo).strip(), "dirty": bool(status), "status_porcelain": status}


def yolox_state() -> dict:
    """Pinned YOLOX commit plus any local modifications (e.g. applied tools/patches)."""
    return {"commit": YOLOX_COMMIT, "local_changes": git_state(YOLOX_DIR)["status_porcelain"]}


def torch_versions() -> dict:
    """torch/CUDA versions read without importing torch, so the launcher never creates a CUDA context."""
    info: dict = {"torch": None, "cuda": None}
    try:
        info["torch"] = importlib.metadata.version("torch")
        dist = importlib.metadata.distribution("torch")
        version_py = Path(dist.locate_file("torch/version.py"))
        info["cuda"] = runpy.run_path(str(version_py)).get("cuda")
    except (importlib.metadata.PackageNotFoundError, OSError) as exc:
        print(f"WARNING: torch version info unavailable, run.json versions incomplete: "
              f"{type(exc).__name__}: {exc}", file=sys.stderr)
    return info


def gpu_info() -> dict | None:
    try:
        out = subprocess.run(
            ["nvidia-smi", "--query-gpu=name,driver_version,memory.total", "--format=csv,noheader"],
            capture_output=True, text=True, check=True, timeout=30,
        ).stdout.strip().splitlines()
    except (OSError, subprocess.SubprocessError) as exc:
        print(f"WARNING: GPU info unavailable, run.json gpu will be null: "
              f"{type(exc).__name__}: {exc}", file=sys.stderr)
        return None
    if not out:
        print("WARNING: nvidia-smi reported no GPU, run.json gpu will be null", file=sys.stderr)
        return None
    name, driver, memory = (part.strip() for part in out[0].split(","))
    return {"name": name, "driver": driver, "memory_total": memory}


def _utc_now() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0)


def _rel(path: Path) -> str:
    try:
        return path.resolve().relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return str(path)


def build_command(cfg: TrainConfig, run_dir: Path, python: str = sys.executable) -> list[str]:
    """Subprocess argv. No --fp16: Pascal GPUs (GTX 1050) have slow fp16, train in fp32."""
    cmd = [python, "-I", "-X", "utf8", "-m", "cuentapepas.yolox_bridge", "train",
           "-f", str(cfg.exp_file), "-d", "1", "-b", str(cfg.batch)]
    if cfg.pretrained is not None:
        cmd += ["-c", str(cfg.pretrained)]
    # Trailing positional opts are merged into the Exp (YOLOX's `-o` is --occupy, not opts).
    opts = ["seed", str(cfg.seed), "output_dir", str(run_dir)]
    if cfg.smoke:
        opts += ["eval_interval", "1", "print_interval", "1"]
    return cmd + opts


def build_env(cfg: TrainConfig, dataset_dir: Path) -> dict[str, str]:
    """Exp overrides. PYTHONPATH is not used: the child runs with -I, and the bridge puts YOLOX on sys.path."""
    env = dict(os.environ)
    # YOLOX's COCO evaluation writes temp files via tempfile.mkstemp; keep them under the project tools dir, not C:.
    tmp = Path(os.environ.get("CP_TOOLS") or REPO_ROOT / ".tools") / "tmp"
    tmp.mkdir(parents=True, exist_ok=True)
    env.update({
        "CP_DATASET_DIR": str(dataset_dir),
        "CP_MAX_EPOCH": "1" if cfg.smoke else str(cfg.epochs),
        "CP_WORKERS": str(cfg.workers),
        "CP_MULTISCALE": os.environ.get("CP_MULTISCALE", DEFAULT_MULTISCALE),
        "CP_NO_AUG_EPOCHS": os.environ.get("CP_NO_AUG_EPOCHS", DEFAULT_NO_AUG_EPOCHS),
        "TEMP": str(tmp),
        "TMP": str(tmp),
        "TMPDIR": str(tmp),
    })
    # On Windows loguru colourises stderr whenever TERM is set (Git Bash), even into a pipe; keep train.log plain.
    env.pop("TERM", None)
    return env


def _write_json(path: Path, data: dict) -> None:
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8", newline="\n")


def _kill_tree(proc: subprocess.Popen) -> None:
    """Kill the trainer with its DataLoader workers (on Windows the venv python.exe is itself a redirector)."""
    if os.name == "nt":
        subprocess.run(["taskkill", "/T", "/F", "/PID", str(proc.pid)], capture_output=True)
    else:
        proc.kill()
    proc.wait()


def _stream(cmd: list[str], env: dict[str, str], log_path: Path) -> int:
    """Run `cmd`, mirroring its output to `log_path` (UTF-8) and the console; the child dies with the launcher."""
    if hasattr(sys.stdout, "reconfigure"):
        # A cp1252 console/redirect cannot encode YOLOX's box-drawing tables; never crash the launcher on that.
        sys.stdout.reconfigure(errors="backslashreplace")
    with open(log_path, "w", encoding="utf-8", newline="\n") as log:
        proc = subprocess.Popen(cmd, cwd=REPO_ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, encoding="utf-8", errors="replace", bufsize=1)
        try:
            for line in proc.stdout:
                log.write(line)
                log.flush()
                sys.stdout.write(line)
                sys.stdout.flush()
        except BaseException:
            _kill_tree(proc)
            raise
        return proc.wait()


def prepare_run(cfg: TrainConfig, now: dt.datetime | None = None) -> tuple[Path, dict, Path]:
    """Create the run directory and its run.json. Returns (run_dir, record, dataset_dir)."""
    if cfg.pretrained is not None and not cfg.pretrained.exists():
        raise FileNotFoundError(f"pretrained weights not found: {cfg.pretrained}; run: bash tools/get-pretrained.sh")
    if not cfg.exp_file.exists():
        raise FileNotFoundError(f"experiment file not found: {cfg.exp_file}")
    summary_path = cfg.manifest.with_suffix(".summary.json")
    for required in (cfg.manifest, summary_path, *(cfg.dataset / "annotations" / f"{s}.json" for s in SPLITS)):
        if not required.exists():
            raise FileNotFoundError(f"{required} not found; run: cuentapepas build-dataset")
    now = now or _utc_now()
    suffix = "-smoke" if cfg.smoke else ""
    run_dir = cfg.runs_dir / f"{now.strftime('%Y%m%dT%H%M%SZ')}-{cfg.exp_name}{suffix}"
    run_dir.mkdir(parents=True, exist_ok=False)

    git = git_state()
    if git["dirty"]:
        print(f"WARNING: uncommitted changes; this run is not reproducible from commit {git['commit']}:",
              *git["status_porcelain"], sep="\n  ", file=sys.stderr)

    dataset: dict = {
        "dir": _rel(cfg.dataset),
        "manifest": _rel(cfg.manifest),
        "manifest_sha256": _sha256(cfg.manifest),
        "summary": json.loads(summary_path.read_text(encoding="utf-8")),
        "annotations_sha256": {s: _sha256(cfg.dataset / "annotations" / f"{s}.json") for s in SPLITS},
    }
    dataset_dir = cfg.dataset
    if cfg.smoke:
        dataset_dir = run_dir / "smoke_dataset"
        dataset["smoke_subset"] = {"dir": _rel(dataset_dir), "seed": cfg.seed,
                                   "n_images": make_subset(cfg.dataset, dataset_dir, SMOKE_IMAGES, cfg.seed)}

    record = {
        "run_id": run_dir.name,
        "created_utc": now.isoformat(),
        "exp": cfg.exp_name,
        "exp_file": _rel(cfg.exp_file),
        "exp_file_sha256": _sha256(cfg.exp_file),
        "git": git,
        "yolox": yolox_state(),
        "dataset": dataset,
        "args": {
            "batch": cfg.batch,
            "epochs": 1 if cfg.smoke else cfg.epochs,
            "workers": cfg.workers,
            "smoke": cfg.smoke,
            "fp16": False,
            "pretrained": _rel(cfg.pretrained) if cfg.pretrained else None,
            "pretrained_sha256": _sha256(cfg.pretrained) if cfg.pretrained else None,
        },
        "seed": cfg.seed,
        "versions": {**torch_versions(), "python": sys.version.split()[0]},
        "gpu": gpu_info(),
    }
    _write_json(run_dir / "run.json", record)
    return run_dir, record, dataset_dir


def train(cfg: TrainConfig) -> int:
    ensure_yolox()
    run_dir, record, dataset_dir = prepare_run(cfg)
    cmd = build_command(cfg, run_dir)
    env = build_env(cfg, dataset_dir)
    record["command"] = cmd
    # Resolved exp overrides (see ml/exps/cuentapepas_nano.py), always recorded even when left at their defaults.
    record["env"] = {k: env[k] for k in ("CP_DATASET_DIR", "CP_MAX_EPOCH", "CP_WORKERS",
                                         "CP_MULTISCALE", "CP_NO_AUG_EPOCHS")}
    _write_json(run_dir / "run.json", record)
    print(f"run dir: {run_dir}", flush=True)

    started = _utc_now()
    exit_code: int | None = None
    try:
        exit_code = _stream(cmd, env, run_dir / "train.log")
    finally:
        finished = _utc_now()
        record.update({
            "started_utc": started.isoformat(),
            "finished_utc": finished.isoformat(),
            "duration_s": (finished - started).total_seconds(),
            "exit_code": exit_code,  # None: interrupted
        })
        _write_json(run_dir / "run.json", record)
    print(f"run dir: {run_dir} (exit code {exit_code})", flush=True)
    return exit_code
