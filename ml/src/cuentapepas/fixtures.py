"""Kotlin parity fixtures (`cuentapepas fixtures`) and benchmark images (`cuentapepas bench-images`)."""
from __future__ import annotations

import json
from pathlib import Path

import cv2
import numpy as np

from cuentapepas.export import ExportError, TfliteRunner, letterbox
from cuentapepas.postprocess import DEFAULT_NMS_IOU, DEFAULT_SCORE_THRESHOLD, postprocess
from cuentapepas.taxonomy import REPO_ROOT

DEFAULT_VAL_DIR = REPO_ROOT / "data" / "local" / "processed" / "croppie_v1" / "val"
FALLBACK_THRESHOLD = 0.05
MIN_DETECTIONS = 3
BENCH_W, BENCH_H = 1280, 720
ATTRIBUTION = (
    "Imágenes de referencia para pruebas de rendimiento.\n"
    "Fuente: Croppie coffee Uganda © 2024 Producers Direct y Alliance Bioversity & CIAT, "
    "licencia CC BY-SA 4.0: https://creativecommons.org/licenses/by-sa/4.0/\n"
    "Modificadas: redimensionadas (ajuste con bandas negras a 1280x720).\n"
)


def _val_images(val_dir: Path) -> list[Path]:
    files = sorted(val_dir.glob("*.jpg"))
    if not files:
        raise ExportError(f"no val images in {val_dir}")
    return files


def make_fixtures(model: Path, out: Path, val_dir: Path = DEFAULT_VAL_DIR, n: int = 3) -> list[dict]:
    """Pick `n` val images that give >= MIN_DETECTIONS detections, write raw_<k>.bin + expected_<k>.json.

    The threshold is 0.30 when `n` candidates qualify at it, otherwise 0.05 for all fixtures (recorded in each
    json; the Kotlin test reads it).
    """
    runner = TfliteRunner(model)
    files = _val_images(val_dir)
    cands = files[:: max(1, len(files) // 60)]  # ~60 evenly spaced candidates, deterministic
    outputs = []
    for path in cands:
        img = cv2.imread(str(path), cv2.IMREAD_COLOR)
        outputs.append((path.name, runner(letterbox(img)).astype(np.float32)))
    threshold = DEFAULT_SCORE_THRESHOLD
    chosen = [(nm, o) for nm, o in outputs if len(postprocess(o, threshold, DEFAULT_NMS_IOU)) >= MIN_DETECTIONS]
    if len(chosen) < n:
        threshold = FALLBACK_THRESHOLD
        chosen = [(nm, o) for nm, o in outputs if len(postprocess(o, threshold, DEFAULT_NMS_IOU)) >= MIN_DETECTIONS]
    if len(chosen) < n:
        raise ExportError(f"only {len(chosen)} candidate images give >= {MIN_DETECTIONS} detections at {threshold}")
    chosen = chosen[:: len(chosen) // n][:n]
    out.mkdir(parents=True, exist_ok=True)
    report = []
    for k, (name, raw) in enumerate(chosen):
        dets = postprocess(raw, threshold, DEFAULT_NMS_IOU)
        (out / f"raw_{k}.bin").write_bytes(np.ascontiguousarray(raw, dtype="<f4").tobytes())
        expected = {"score_threshold": threshold, "nms_iou": DEFAULT_NMS_IOU,
                    "detections": [{"box": list(d.box), "score": d.score, "cls": d.cls} for d in dets]}
        (out / f"expected_{k}.json").write_text(json.dumps(expected, indent=2) + "\n", encoding="utf-8", newline="\n")
        report.append({"k": k, "image": name, "n_detections": len(dets), "score_threshold": threshold})
    return report


def make_bench_images(out: Path, n: int = 20, val_dir: Path = DEFAULT_VAL_DIR) -> list[str]:
    """`n` evenly spaced val images letterboxed (black, centered) to 1280x720 JPEG, plus ATTRIBUTION.txt."""
    files = _val_images(val_dir)
    if len(files) < n:
        raise ExportError(f"need {n} val images, found {len(files)}")
    out.mkdir(parents=True, exist_ok=True)
    picked = [files[int(i)] for i in np.linspace(0, len(files) - 1, n).round()]
    names = []
    for k, path in enumerate(picked):
        img = cv2.imread(str(path), cv2.IMREAD_COLOR)
        h, w = img.shape[:2]
        r = min(BENCH_H / h, BENCH_W / w)
        nh, nw = int(round(h * r)), int(round(w * r))
        canvas = np.zeros((BENCH_H, BENCH_W, 3), np.uint8)
        y0, x0 = (BENCH_H - nh) // 2, (BENCH_W - nw) // 2
        canvas[y0:y0 + nh, x0:x0 + nw] = cv2.resize(img, (nw, nh), interpolation=cv2.INTER_AREA)
        name = f"bench_{k:02d}.jpg"
        cv2.imwrite(str(out / name), canvas, [cv2.IMWRITE_JPEG_QUALITY, 92])
        names.append(f"{name} <- {path.name}")
    (out / "ATTRIBUTION.txt").write_text(ATTRIBUTION, encoding="utf-8", newline="\n")
    return names
