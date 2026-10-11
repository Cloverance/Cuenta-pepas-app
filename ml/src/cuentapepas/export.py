"""Export a trained checkpoint to ONNX and TFLite (fp32 + fp16), verify parity and write the model.json contract.

Pipeline (CPU only; never touches CUDA): checkpoint -> ONNX (opset 13, static [1,3,384,640]) -> onnxsim ->
onnx2tf -> TFLite NHWC. Every artifact is built in a scratch directory; only after all parity checks pass are the
.tflite files, model.json and parity.json copied to the output directory, so a failed export leaves nothing behind.
"""
from __future__ import annotations

import gc
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np

from cuentapepas.postprocess import (DEFAULT_MAX_DETECTIONS, DEFAULT_NMS_IOU, DEFAULT_SCORE_THRESHOLD, Detection,
                                     postprocess)
from cuentapepas.taxonomy import REPO_ROOT, load_taxonomy
from cuentapepas.yolox_bridge import ensure_yolox

MODEL_ID = "cuentapepas-nano-v1"
EXP_FILE = REPO_ROOT / "ml" / "exps" / "cuentapepas_nano.py"
DEFAULT_VAL_DIR = REPO_ROOT / "data" / "local" / "processed" / "croppie_v1" / "val"
DEFAULT_OUT = REPO_ROOT / "android" / "app" / "src" / "main" / "assets" / "model"
INPUT_H, INPUT_W = 384, 640
PAD_VALUE = 114
OPSET = 13
N_PARITY_IMAGES = 16
LICENSE_ATTRIBUTION = ("Trained on Croppie coffee Uganda (CC BY-SA 4.0) by Producers Direct & Alliance Bioversity-CIAT. "
                       "YOLOX Apache-2.0.")
FP32_NAME = "cuentapepas_nano_fp32.tflite"
FP16_NAME = "cuentapepas_nano_fp16.tflite"

# Parity tolerances (max |delta|) on the rows that matter (see _max_diffs): boxes are in input pixels, scores are
# obj/cls probabilities in 0..1.
TOL_ONNX = {"boxes": 1e-3, "scores": 1e-4}
TOL_TFLITE_FP32 = {"boxes": 1e-2, "scores": 1e-3}
BOX_RTOL = 1e-4  # extra relative slack on box coordinates, see _max_diffs
FP16_MAX_COUNT_DIFF = 2
FP16_MIN_MEAN_IOU = 0.9
# Detection agreement is gated at the default threshold; the lower one is informational (a barely trained model
# may have no detection at all at 0.30).
GATE_THRESHOLD = DEFAULT_SCORE_THRESHOLD
INFO_THRESHOLD = 0.05


class ExportError(RuntimeError):
    pass


# ---------------------------------------------------------------- inputs

def find_checkpoint(run_dir: Path) -> Path:
    """`best_ckpt.pth` under a training run directory (YOLOX writes it to <run>/<exp_name>/)."""
    found = sorted(run_dir.rglob("best_ckpt.pth"))
    if not found:
        raise ExportError(f"no best_ckpt.pth under {run_dir}")
    return found[-1]


def letterbox(image_bgr: np.ndarray) -> np.ndarray:
    """Preprocessing exactly as the app does it: top-left letterbox into 384x640, pad 114, BGR float32 0..255, HWC."""
    h, w = image_bgr.shape[:2]
    r = min(INPUT_H / h, INPUT_W / w)
    nh, nw = int(round(h * r)), int(round(w * r))
    canvas = np.full((INPUT_H, INPUT_W, 3), PAD_VALUE, dtype=np.uint8)
    canvas[:nh, :nw] = cv2.resize(image_bgr, (nw, nh), interpolation=cv2.INTER_LINEAR)
    return canvas.astype(np.float32)


def load_parity_images(val_dir: Path, n: int = N_PARITY_IMAGES) -> list[tuple[str, np.ndarray]]:
    """`n` evenly spaced val images (sorted by name, so the selection is deterministic), letterboxed HWC float32."""
    files = sorted(val_dir.glob("*.jpg"))
    if len(files) < n:
        raise ExportError(f"need {n} val images for the parity check, found {len(files)} in {val_dir}")
    step = len(files) // n
    out = []
    for path in files[::step][:n]:
        img = cv2.imread(str(path), cv2.IMREAD_COLOR)
        if img is None:
            raise ExportError(f"cannot read parity image {path}")
        out.append((path.name, letterbox(img)))
    return out


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_torch_model(ckpt_path: Path):
    """YOLOX-Nano from the repo Exp with the checkpoint's weights, eval mode, on CPU, decoding in the graph."""
    import torch
    from torch import nn

    ensure_yolox()
    from yolox.exp import get_exp
    from yolox.models.network_blocks import SiLU
    from yolox.utils import replace_module

    exp = get_exp(str(EXP_FILE), None)
    model = exp.get_model()
    ckpt = torch.load(str(ckpt_path), map_location="cpu", weights_only=True)
    model.load_state_dict(ckpt["model"] if "model" in ckpt else ckpt)
    model = replace_module(model, nn.SiLU, SiLU)
    model.eval().to(torch.device("cpu"))
    model.head.decode_in_inference = True
    return model, ckpt


# ---------------------------------------------------------------- conversion

def _import_simplify():
    """`onnxsim.simplify`, working around an environment mismatch.

    The installed onnxsim imports `ml_dtypes.float6_*` at module level (only for its FP6 quantization helper, unused
    here), but TensorFlow 2.18 pins ml_dtypes 0.4.x, which lacks them. Placeholders let the import succeed; nothing
    in the simplify path touches them.
    """
    import ml_dtypes

    for name in ("float6_e3m2fn", "float6_e2m3fn"):
        if not hasattr(ml_dtypes, name):
            setattr(ml_dtypes, name, None)
    from onnxsim import simplify

    return simplify


def export_onnx(model, onnx_path: Path) -> None:
    import onnx
    import torch

    simplify = _import_simplify()

    dummy = torch.zeros(1, 3, INPUT_H, INPUT_W)
    with torch.no_grad():
        torch.onnx.export(model, dummy, str(onnx_path), input_names=["images"], output_names=["output"],
                          opset_version=OPSET, dynamic_axes=None, dynamo=False)
    simplified, ok = simplify(onnx.load(str(onnx_path)))
    if not ok:
        raise ExportError("onnxsim could not validate the simplified ONNX model")
    onnx.save(simplified, str(onnx_path))


def onnx_to_tflite(onnx_path: Path, work: Path) -> tuple[Path, Path, list[str]]:
    """Run onnx2tf and return (fp32, fp16) .tflite paths plus the flags used.

    Only `-i -o -nuo` are used. `-nuo` skips onnx2tf's own onnxsim pass: the graph is already simplified here, and
    onnx2tf shells out to the `onnxsim` CLI, which fails to import in this environment (see `_import_simplify`).
    `-osd` is not needed. `-cotof` (onnx2tf's own output comparison) is not used: with 4-D inputs it fetches a
    calibration .npy from GitHub, and this pipeline verifies parity itself on real val images, offline.
    """
    out_dir = work / "tf"
    flags = ["-i", str(onnx_path), "-o", str(out_dir), "-nuo"]
    recorded_flags = ["-i", "<model.onnx>", "-o", "<out_dir>", "-nuo"]  # paths are scratch directories
    onnx2tf = Path(sys.executable).parent / ("onnx2tf.exe" if os.name == "nt" else "onnx2tf")
    cmd = [str(onnx2tf), *flags] if onnx2tf.exists() else [sys.executable, "-m", "onnx2tf", *flags]
    env = dict(os.environ, CUDA_VISIBLE_DEVICES="", TF_CPP_MIN_LOG_LEVEL="2", PYTHONUTF8="1")
    # onnx2tf's pre-conversion sanity pass wants a 20x128x128x3 sample array and downloads it from GitHub unless a file
    # with this exact name exists in its working directory. Provide a local one so the export never needs the network.
    np.save(work / "calibration_image_sample_data_20x128x128x3_float32.npy",
            np.random.default_rng(0).random((20, 128, 128, 3), dtype=np.float32))
    proc = subprocess.run(cmd, cwd=work, env=env, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        tail = "\n".join((proc.stdout + proc.stderr).splitlines()[-30:])
        raise ExportError(f"onnx2tf failed (exit {proc.returncode}):\n{tail}")
    fp32 = next(iter(sorted(out_dir.glob("*_float32.tflite"))), None)
    fp16 = next(iter(sorted(out_dir.glob("*_float16.tflite"))), None)
    if fp32 is None or fp16 is None:
        raise ExportError(f"onnx2tf did not produce float32/float16 tflite files in {out_dir}: "
                          f"{sorted(p.name for p in out_dir.iterdir())}")
    return fp32, fp16, recorded_flags


# ---------------------------------------------------------------- runners

class TorchRunner:
    def __init__(self, model):
        self.model = model

    def __call__(self, hwc: np.ndarray) -> np.ndarray:
        import torch

        with torch.no_grad():
            x = torch.from_numpy(np.ascontiguousarray(hwc.transpose(2, 0, 1)[None]))
            return self.model(x).numpy()[0]


class OnnxRunner:
    def __init__(self, path: Path):
        import onnxruntime as ort

        self.session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])

    def close(self) -> None:
        self.session = None

    def __call__(self, hwc: np.ndarray) -> np.ndarray:
        return self.session.run(["output"], {"images": np.ascontiguousarray(hwc.transpose(2, 0, 1)[None])})[0][0]


class TfliteRunner:
    """TFLite model with an NHWC float32 input. `transposed` is True when the output is [1, 5+C, N]."""

    def __init__(self, path: Path):
        try:
            from ai_edge_litert.interpreter import Interpreter
        except ImportError:
            from tensorflow.lite import Interpreter
        self.interpreter = Interpreter(model_path=str(path), num_threads=2)
        self.interpreter.allocate_tensors()
        self.input = self.interpreter.get_input_details()[0]
        outputs = self.interpreter.get_output_details()
        if len(outputs) != 1:
            raise ExportError(f"{path.name}: expected 1 output, got {[o['name'] for o in outputs]}")
        self.output = outputs[0]
        shape = [int(s) for s in self.input["shape"]]
        if shape != [1, INPUT_H, INPUT_W, 3] or self.input["dtype"] != np.float32:
            raise ExportError(f"{path.name}: unexpected input {shape} {self.input['dtype']}, want NHWC float32")
        self.output_shape = [int(s) for s in self.output["shape"]]
        self.transposed = self.output_shape[1] < self.output_shape[2]

    def close(self) -> None:
        """Release the model file (Windows cannot delete a file an interpreter still maps)."""
        self.interpreter = None

    def __call__(self, hwc: np.ndarray) -> np.ndarray:
        self.interpreter.set_tensor(self.input["index"], np.ascontiguousarray(hwc[None]))
        self.interpreter.invoke()
        out = self.interpreter.get_tensor(self.output["index"])[0]
        return out.T if self.transposed else out


# ---------------------------------------------------------------- parity

def _max_diffs(ref: np.ndarray, other: np.ndarray, tol: dict[str, float]) -> dict:
    """Max |delta| of boxes (cols 0-3) and scores (cols 4-9) on the rows that matter, plus over every row.

    "Rows that matter" are those whose reference score (obj * max cls) is at least INFO_THRESHOLD: the app's score
    threshold slider never goes below it. The 5040 anchors also include confident-background rows whose box size is
    exp() of a raw logit; fp32 rounding differences between runtimes are amplified there (up to ~0.05 px on a
    ~450 px box) without any effect on a detection, so they are reported (`*_all_rows`) but not gated.

    `box_ok` / `score_ok` apply the tolerance on the rows that matter: boxes allow `tol + BOX_RTOL * |ref|` because
    fp32 resolution alone is ~3e-5 px at 450 px and conv rounding accumulates over ~100 layers; scores are absolute.
    """
    d = np.abs(ref - other)
    rows = ref[:, 4] * ref[:, 5:].max(axis=1) >= INFO_THRESHOLD
    box_allowed = tol["boxes"] + BOX_RTOL * np.abs(ref[rows, :4])
    return {"boxes": float(d[rows, :4].max(initial=0.0)), "scores": float(d[rows, 4:].max(initial=0.0)),
            "boxes_all_rows": float(d[:, :4].max()), "scores_all_rows": float(d[:, 4:].max()),
            "box_ok": bool(np.all(d[rows, :4] < box_allowed)), "score_ok": bool(np.all(d[rows, 4:] < tol["scores"]))}


def _box_iou(a: tuple, b: tuple) -> float:
    iw = max(min(a[2], b[2]) - max(a[0], b[0]), 0.0)
    ih = max(min(a[3], b[3]) - max(a[1], b[1]), 0.0)
    inter = iw * ih
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / union if union > 0 else 0.0


def detection_agreement(ref: list[list[Detection]], other: list[list[Detection]]) -> dict:
    """Per-image count differences and the mean best IoU of each reference detection against the other model."""
    count_diffs = [abs(len(r) - len(o)) for r, o in zip(ref, other)]
    best_ious = [max((_box_iou(d.box, e.box) for e in o), default=0.0) for r, o in zip(ref, other) for d in r]
    return {"n_ref": sum(len(r) for r in ref), "n_other": sum(len(o) for o in other),
            "max_count_diff": max(count_diffs), "mean_best_iou": float(np.mean(best_ious)) if best_ious else None}


def _merge(per_image: list[dict]) -> dict:
    return {k: (all(d[k] for d in per_image) if k.endswith("_ok") else max(d[k] for d in per_image))
            for k in per_image[0]}


def run_parity(torch_run, onnx_run, fp32_run, fp16_run, images) -> dict:
    """Compare raw outputs (torch vs onnx, torch vs tflite fp32) and postprocessed detections (torch vs tflite fp16)."""
    diffs: dict[str, list] = {"onnx": [], "tflite_fp32": []}
    thresholds = (GATE_THRESHOLD, INFO_THRESHOLD)
    ref_dets: dict[float, list] = {t: [] for t in thresholds}
    fp16_dets: dict[float, list] = {t: [] for t in thresholds}
    finite = True
    for _, hwc in images:
        ref = torch_run(hwc)
        finite = finite and bool(np.isfinite(ref).all())
        diffs["onnx"].append(_max_diffs(ref, onnx_run(hwc), TOL_ONNX))
        diffs["tflite_fp32"].append(_max_diffs(ref, fp32_run(hwc), TOL_TFLITE_FP32))
        out16 = fp16_run(hwc)
        for t in thresholds:
            ref_dets[t].append(postprocess(ref, score_threshold=t, nms_iou=DEFAULT_NMS_IOU))
            fp16_dets[t].append(postprocess(out16, score_threshold=t, nms_iou=DEFAULT_NMS_IOU))
    return {
        "n_images": len(images),
        "onnx_max_abs_diff": _merge(diffs["onnx"]),
        "tflite_fp32_max_abs_diff": _merge(diffs["tflite_fp32"]),
        "tflite_fp16_detections": {f"{t:.2f}": detection_agreement(ref_dets[t], fp16_dets[t]) for t in thresholds},
        "pytorch_output_finite": finite,
    }


def check_parity(report: dict) -> list[str]:
    """Tolerance violations as readable messages (empty list = pass)."""
    problems = []
    for key, tol, label in (("onnx_max_abs_diff", TOL_ONNX, "ONNX"),
                            ("tflite_fp32_max_abs_diff", TOL_TFLITE_FP32, "TFLite fp32")):
        for what, ok in (("boxes", "box_ok"), ("scores", "score_ok")):
            if not report[key][ok]:
                problems.append(f"{label} {what} max|diff| {report[key][what]:.3e} exceeds tolerance {tol[what]:.0e}"
                                + (f" + {BOX_RTOL:.0e}*|ref|" if what == "boxes" else ""))
    gate = report["tflite_fp16_detections"][f"{GATE_THRESHOLD:.2f}"]
    if gate["max_count_diff"] > FP16_MAX_COUNT_DIFF:
        problems.append(f"TFLite fp16 detection count differs by {gate['max_count_diff']} on some image "
                        f"(max {FP16_MAX_COUNT_DIFF})")
    if gate["mean_best_iou"] is not None and gate["mean_best_iou"] <= FP16_MIN_MEAN_IOU:
        problems.append(f"TFLite fp16 mean best IoU {gate['mean_best_iou']:.3f} <= {FP16_MIN_MEAN_IOU}")
    if not report["pytorch_output_finite"]:
        problems.append("PyTorch output contains NaN/Inf")
    return problems


# ---------------------------------------------------------------- model.json

def _rel(path: Path) -> str | None:
    try:
        return path.resolve().relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return None


def read_metrics(run_dir: Path | None, ckpt: dict) -> tuple[dict, str]:
    """Real validation metrics only: <run>/metrics.json, else the AP the trainer stored in the checkpoint, else null.

    YOLOX records `best_ap` (COCO AP@[.5:.95] on val, a fraction) in best_ckpt.pth but not AP50, so `val_map50`
    stays null unless metrics.json provides it.
    """
    metrics = {"val_map50": None, "val_map50_95": None}
    if run_dir is not None and (run_dir / "metrics.json").exists():
        raw = json.loads((run_dir / "metrics.json").read_text(encoding="utf-8"))
        return {k: raw.get(k) for k in metrics}, "metrics.json"
    best_ap = ckpt.get("best_ap") if isinstance(ckpt, dict) else None
    if best_ap is not None:
        metrics["val_map50_95"] = float(best_ap)
        return metrics, "checkpoint best_ap"
    return metrics, "unavailable"


def build_model_json(*, training_run: str | None, metrics: dict, fp16: Path, fp32: Path, output_shape: list[int],
                     transposed: bool) -> dict:
    taxonomy = load_taxonomy()
    output = {"shape": output_shape,
              "layout": ["cx", "cy", "w", "h", "obj", *(f"cls{i}" for i in range(len(taxonomy.ids)))],
              "coords": "input_pixels", "activations": "sigmoid_applied"}
    if transposed:
        output["transposed"] = True
    return {
        "model_id": MODEL_ID,
        "taxonomy_schema_version": taxonomy.schema_version,
        "classes": taxonomy.ids,
        "input": {"name": "images", "width": INPUT_W, "height": INPUT_H, "layout": "NHWC", "dtype": "float32",
                  "channel_order": "BGR", "range": [0, 255], "letterbox": "top_left", "pad_value": PAD_VALUE},
        "output": output,
        "postprocess": {"score": "obj*max(cls)", "default_score_threshold": DEFAULT_SCORE_THRESHOLD,
                        "nms": "class_agnostic_greedy", "default_nms_iou": DEFAULT_NMS_IOU,
                        "max_detections": DEFAULT_MAX_DETECTIONS},
        "files": {"fp16": {"name": FP16_NAME, "sha256": _sha256(fp16)},
                  "fp32": {"name": FP32_NAME, "sha256": _sha256(fp32)}},
        "training_run": training_run,
        "metrics": metrics,
        "license_attribution": LICENSE_ATTRIBUTION,
    }


# ---------------------------------------------------------------- entry point

@dataclass(frozen=True)
class ExportConfig:
    out: Path = DEFAULT_OUT
    run: Path | None = None
    ckpt: Path | None = None
    val_dir: Path = DEFAULT_VAL_DIR


def _scratch_dir() -> Path:
    base = Path(os.environ.get("CP_TOOLS") or REPO_ROOT / ".tools") / "tmp"
    base.mkdir(parents=True, exist_ok=True)
    return Path(tempfile.mkdtemp(prefix="cp_export_", dir=base))


def _run_of(ckpt_path: Path) -> Path | None:
    """`runs/<id>` when the checkpoint sits at runs/<id>/<exp>/best_ckpt.pth."""
    run = ckpt_path.resolve().parent.parent
    return run if run.parent.name == "runs" else None


def export(cfg: ExportConfig) -> dict:
    """Run the whole export. Returns the parity report; raises ExportError (nothing written to `cfg.out`) on failure."""
    if (cfg.run is None) == (cfg.ckpt is None):
        raise ExportError("give exactly one of --run or --ckpt")
    ckpt_path = cfg.ckpt if cfg.ckpt is not None else find_checkpoint(cfg.run)
    if not ckpt_path.exists():
        raise ExportError(f"checkpoint not found: {ckpt_path}")
    run_dir = cfg.run if cfg.run is not None else _run_of(ckpt_path)
    images = load_parity_images(cfg.val_dir)

    work = _scratch_dir()
    try:
        model, ckpt = load_torch_model(ckpt_path)
        onnx_path = work / "cuentapepas_nano.onnx"
        print(f"[export] ONNX opset {OPSET} from {ckpt_path}", flush=True)
        export_onnx(model, onnx_path)
        print("[export] onnx2tf ...", flush=True)
        fp32, fp16, flags = onnx_to_tflite(onnx_path, work)

        fp32_run, fp16_run, onnx_run = TfliteRunner(fp32), TfliteRunner(fp16), OnnxRunner(onnx_path)
        try:
            if fp32_run.output_shape != fp16_run.output_shape:
                raise ExportError(f"fp32/fp16 output shapes differ: {fp32_run.output_shape} vs {fp16_run.output_shape}")
            print(f"[export] parity on {len(images)} val images ...", flush=True)
            report = run_parity(TorchRunner(model), onnx_run, fp32_run, fp16_run, images)
        finally:
            fp32_run.close(), fp16_run.close(), onnx_run.close()
        problems = check_parity(report)
        if problems:
            raise ExportError("parity check failed:\n  " + "\n  ".join(problems) + "\n" + json.dumps(report, indent=2))

        metrics, metrics_source = read_metrics(run_dir, ckpt)
        training_run = _rel(run_dir) if run_dir is not None else None
        out_shape = fp32_run.output_shape
        model_json = build_model_json(training_run=training_run, metrics=metrics, fp16=fp16, fp32=fp32,
                                      output_shape=out_shape, transposed=fp32_run.transposed)
        report.update({
            "checkpoint": _rel(ckpt_path) or str(ckpt_path), "checkpoint_sha256": _sha256(ckpt_path),
            "metrics_source": metrics_source, "onnx2tf_flags": flags,
            "tflite_input": {"name": fp32_run.input["name"], "shape": [int(s) for s in fp32_run.input["shape"]]},
            "tflite_output": {"name": fp32_run.output["name"], "shape": out_shape, "transposed": fp32_run.transposed},
            "tolerances": {"onnx": TOL_ONNX, "tflite_fp32": TOL_TFLITE_FP32, "box_relative": BOX_RTOL,
                           "gated_rows": f"reference score >= {INFO_THRESHOLD}",
                           "fp16_max_count_diff": FP16_MAX_COUNT_DIFF, "fp16_min_mean_iou": FP16_MIN_MEAN_IOU},
            "parity_images": [name for name, _ in images],
        })

        cfg.out.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(fp32, cfg.out / FP32_NAME)
        shutil.copyfile(fp16, cfg.out / FP16_NAME)
        (cfg.out / "parity.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8", newline="\n")
        (cfg.out / "model.json").write_text(json.dumps(model_json, indent=2) + "\n", encoding="utf-8", newline="\n")
        return report
    finally:
        gc.collect()
        shutil.rmtree(work, ignore_errors=True)
