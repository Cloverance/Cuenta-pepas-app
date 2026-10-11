import datetime as dt
import json
import sys
from pathlib import Path

import pytest
from PIL import Image

from cuentapepas import train as train_mod
from cuentapepas.train import TrainConfig, build_command, build_env, gpu_info, make_subset, prepare_run, train


def _coco_dataset(root: Path, n_per_split: int) -> None:
    (root / "annotations").mkdir(parents=True)
    for split in ("train", "val"):
        (root / split).mkdir()
        images, annotations = [], []
        for i in range(1, n_per_split + 1):
            name = f"{split}{i}.jpg"
            Image.new("RGB", (8, 8)).save(root / split / name)
            images.append({"id": i, "file_name": name, "width": 8, "height": 8})
            annotations.append({"id": i, "image_id": i, "category_id": 0, "bbox": [1, 1, 2, 2], "area": 4,
                                "iscrowd": 0})
        coco = {"images": images, "annotations": annotations, "categories": [{"id": 0, "name": "green"}]}
        (root / "annotations" / f"{split}.json").write_text(json.dumps(coco), encoding="utf-8")


def test_make_subset_is_seeded_and_keeps_matching_annotations(tmp_path):
    src = tmp_path / "src"
    _coco_dataset(src, n_per_split=10)
    assert make_subset(src, tmp_path / "a", n=4, seed=1) == {"train": 4, "val": 4}
    make_subset(src, tmp_path / "b", n=4, seed=1)
    for split in ("train", "val"):
        a = json.loads((tmp_path / "a" / "annotations" / f"{split}.json").read_text(encoding="utf-8"))
        b = json.loads((tmp_path / "b" / "annotations" / f"{split}.json").read_text(encoding="utf-8"))
        assert a == b
        ids = {im["id"] for im in a["images"]}
        assert {ann["image_id"] for ann in a["annotations"]} == ids
        assert all((tmp_path / "a" / split / im["file_name"]).exists() for im in a["images"])


def test_command_runs_isolated_fp32_with_seed_and_run_dir_opts(tmp_path):
    cfg = TrainConfig(batch=8, pretrained=tmp_path / "w.pth")
    cmd = build_command(cfg, tmp_path / "run", python="py")
    assert cmd[:6] == ["py", "-I", "-X", "utf8", "-m", "cuentapepas.yolox_bridge"]
    assert "--fp16" not in cmd
    assert cmd[cmd.index("-b") + 1] == "8" and cmd[cmd.index("-d") + 1] == "1"
    assert cmd[cmd.index("-c") + 1] == str(tmp_path / "w.pth")
    assert cmd[-4:] == ["seed", str(cfg.seed), "output_dir", str(tmp_path / "run")]
    smoke = build_command(TrainConfig(smoke=True, pretrained=None), tmp_path / "run", python="py")
    assert "-c" not in smoke and smoke[-4:] == ["eval_interval", "1", "print_interval", "1"]


def test_env_sets_exp_overrides(tmp_path, monkeypatch):
    monkeypatch.setenv("CP_TOOLS", str(tmp_path / "tools"))
    monkeypatch.setenv("TERM", "xterm")
    assert build_env(TrainConfig(epochs=120, workers=0), tmp_path)["CP_MAX_EPOCH"] == "120"
    assert "TERM" not in build_env(TrainConfig(), tmp_path)  # loguru would colourise train.log
    env = build_env(TrainConfig(smoke=True, workers=0), tmp_path)
    assert (env["CP_MAX_EPOCH"], env["CP_WORKERS"], env["CP_DATASET_DIR"]) == ("1", "0", str(tmp_path))


def test_env_records_resolved_exp_defaults_and_inherited_overrides(tmp_path, monkeypatch):
    monkeypatch.setenv("CP_TOOLS", str(tmp_path / "tools"))
    monkeypatch.delenv("CP_MULTISCALE", raising=False)
    monkeypatch.delenv("CP_NO_AUG_EPOCHS", raising=False)
    env = build_env(TrainConfig(), tmp_path)
    assert (env["CP_MULTISCALE"], env["CP_NO_AUG_EPOCHS"]) == ("1", "15")
    monkeypatch.setenv("CP_MULTISCALE", "0")
    monkeypatch.setenv("CP_NO_AUG_EPOCHS", "4")
    env = build_env(TrainConfig(), tmp_path)
    assert (env["CP_MULTISCALE"], env["CP_NO_AUG_EPOCHS"]) == ("0", "4")


def test_env_points_temp_dirs_into_the_tools_dir(tmp_path, monkeypatch):
    tools = tmp_path / "tools"
    monkeypatch.setenv("CP_TOOLS", str(tools))
    env = build_env(TrainConfig(), tmp_path)
    assert env["TEMP"] == env["TMP"] == env["TMPDIR"] == str(tools / "tmp")
    assert (tools / "tmp").is_dir()


def test_gpu_info_warns_with_the_exception_and_returns_none(monkeypatch, capsys):
    def no_nvidia_smi(*args, **kwargs):
        raise FileNotFoundError(2, "No such file", "nvidia-smi")

    monkeypatch.setattr(train_mod.subprocess, "run", no_nvidia_smi)
    assert gpu_info() is None
    err = capsys.readouterr().err
    assert err.startswith("WARNING:") and "nvidia-smi" in err


def test_prepare_run_requires_the_dataset_summary(tmp_path):
    dataset = tmp_path / "ds"
    _coco_dataset(dataset, n_per_split=2)
    manifest = tmp_path / "m.jsonl"
    manifest.write_text("{}\n", encoding="utf-8")
    cfg = TrainConfig(dataset=dataset, manifest=manifest, pretrained=None, runs_dir=tmp_path / "runs")
    with pytest.raises(FileNotFoundError, match="summary.json"):
        prepare_run(cfg)
    assert not (tmp_path / "runs").exists()


def test_train_returns_the_trainer_exit_code_and_records_it(tmp_path, monkeypatch):
    monkeypatch.setenv("CP_TOOLS", str(tmp_path / "tools"))
    monkeypatch.setattr(train_mod, "ensure_yolox", lambda: None)
    monkeypatch.setattr(train_mod, "git_state", lambda repo=None: {"commit": "c" * 40, "dirty": False,
                                                                    "status_porcelain": []})
    monkeypatch.setattr(train_mod, "yolox_state", lambda: {"commit": "pinned", "local_changes": []})
    monkeypatch.setattr(train_mod, "gpu_info", lambda: None)
    monkeypatch.setattr(train_mod, "torch_versions", lambda: {"torch": None, "cuda": None})
    failing = [sys.executable, "-c", "import sys; sys.exit(3)"]
    monkeypatch.setattr(train_mod, "build_command", lambda cfg, run_dir, python=sys.executable: failing)

    dataset = tmp_path / "ds"
    _coco_dataset(dataset, n_per_split=2)
    manifest = tmp_path / "m.jsonl"
    manifest.write_text('{"file": "x"}\n', encoding="utf-8")
    manifest.with_suffix(".summary.json").write_text("{}", encoding="utf-8")
    exp = tmp_path / "exp.py"
    exp.write_text("class Exp: pass\n", encoding="utf-8")
    runs = tmp_path / "runs"
    cfg = TrainConfig(dataset=dataset, manifest=manifest, exp_file=exp, pretrained=None, runs_dir=runs)

    assert train(cfg) == 3

    (run_dir,) = runs.iterdir()
    record = json.loads((run_dir / "run.json").read_text(encoding="utf-8"))
    assert record["exit_code"] == 3
    assert record["command"] == failing
    assert (run_dir / "train.log").exists()


def test_prepare_run_fails_before_creating_the_run_dir_when_inputs_are_missing(tmp_path):
    manifest = tmp_path / "m.jsonl"
    manifest.write_text("{}\n", encoding="utf-8")
    cfg = TrainConfig(dataset=tmp_path / "missing", manifest=manifest, pretrained=None, runs_dir=tmp_path / "runs")
    with pytest.raises(FileNotFoundError, match="build-dataset"):
        prepare_run(cfg)
    with pytest.raises(FileNotFoundError, match="get-pretrained"):
        prepare_run(TrainConfig(pretrained=tmp_path / "w.pth", runs_dir=tmp_path / "runs"))
    assert not (tmp_path / "runs").exists()


def test_prepare_run_records_provenance(tmp_path, monkeypatch):
    monkeypatch.setattr(train_mod, "gpu_info", lambda: {"name": "fake"})
    monkeypatch.setattr(train_mod, "yolox_state", lambda: {"commit": "pinned"})
    dataset = tmp_path / "ds"
    _coco_dataset(dataset, n_per_split=40)
    manifest = tmp_path / "m.jsonl"
    manifest.write_text('{"file": "x"}\n', encoding="utf-8")
    manifest.with_suffix(".summary.json").write_text('{"n_images": {"train": 40}}', encoding="utf-8")
    weights = tmp_path / "w.pth"
    weights.write_bytes(b"w")
    cfg = TrainConfig(smoke=True, dataset=dataset, manifest=manifest, pretrained=weights, runs_dir=tmp_path / "runs")
    now = dt.datetime(2026, 10, 11, 12, 0, 0, tzinfo=dt.timezone.utc)

    run_dir, record, dataset_dir = prepare_run(cfg, now=now)

    assert run_dir.name == "20261011T120000Z-cuentapepas_nano-smoke"
    assert dataset_dir == run_dir / "smoke_dataset"
    on_disk = json.loads((run_dir / "run.json").read_text(encoding="utf-8"))
    assert on_disk == record
    assert len(record["git"]["commit"]) == 40 and isinstance(record["git"]["dirty"], bool)
    assert len(record["dataset"]["manifest_sha256"]) == 64
    assert record["dataset"]["summary"] == {"n_images": {"train": 40}}
    assert set(record["dataset"]["annotations_sha256"]) == {"train", "val"}
    assert all(len(h) == 64 for h in record["dataset"]["annotations_sha256"].values())
    assert len(record["exp_file_sha256"]) == 64
    assert record["dataset"]["smoke_subset"]["n_images"] == {"train": 32, "val": 32}
    assert record["args"]["epochs"] == 1 and record["args"]["fp16"] is False
    assert len(record["args"]["pretrained_sha256"]) == 64
    assert record["seed"] == cfg.seed and record["gpu"] == {"name": "fake"}
    assert record["versions"]["torch"]
