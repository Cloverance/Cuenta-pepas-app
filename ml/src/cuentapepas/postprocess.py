"""Reference postprocess for the exported detector output.

This module is the CONTRACT the Kotlin app replicates exactly (see model.json "postprocess"):
  1. score = obj * max(cls probabilities); class = argmax(cls probabilities)
  2. keep rows with score >= score_threshold
  3. order by score descending, stable (ties keep the earlier row)
  4. greedy NMS, class-agnostic: a candidate is suppressed when IoU with any kept box is > nms_iou
     (IoU uses areas (x2-x1)*(y2-y1), no +1)
  5. keep at most max_detections
Input rows are `(cx, cy, w, h, obj, cls0..clsK-1)` in input pixels with sigmoid already applied.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np

DEFAULT_SCORE_THRESHOLD = 0.30
DEFAULT_NMS_IOU = 0.45
DEFAULT_MAX_DETECTIONS = 300


@dataclass(frozen=True, eq=False)
class Detection:
    box: tuple[float, float, float, float]  # x1, y1, x2, y2 in input pixels
    score: float
    cls: int
    class_probs: np.ndarray


def _iou(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """IoU of box `a` (4,) against boxes `b` (n, 4), float32 arithmetic like the app."""
    iw = np.maximum(np.minimum(a[2], b[:, 2]) - np.maximum(a[0], b[:, 0]), np.float32(0))
    ih = np.maximum(np.minimum(a[3], b[:, 3]) - np.maximum(a[1], b[:, 1]), np.float32(0))
    inter = iw * ih
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[:, 2] - b[:, 0]) * (b[:, 3] - b[:, 1]) - inter
    return np.where(union > 0, inter / np.maximum(union, np.float32(1e-12)), np.float32(0))


def nms(boxes: np.ndarray, scores: np.ndarray, iou_threshold: float) -> list[int]:
    """Greedy class-agnostic NMS over (n, 4) xyxy boxes; returns kept indices, highest score first."""
    boxes = np.asarray(boxes, dtype=np.float32)
    order = np.argsort(-np.asarray(scores, dtype=np.float32), kind="stable")
    keep: list[int] = []
    for i in order:
        if keep and np.any(_iou(boxes[i], boxes[keep]) > iou_threshold):
            continue
        keep.append(int(i))
    return keep


def postprocess(output: np.ndarray, score_threshold: float = DEFAULT_SCORE_THRESHOLD,
                nms_iou: float = DEFAULT_NMS_IOU, max_detections: int = DEFAULT_MAX_DETECTIONS) -> list[Detection]:
    """Decode one image's raw `(N, 5 + num_classes)` output into detections (see module docstring)."""
    out = np.asarray(output, dtype=np.float32)
    class_probs = out[:, 5:]
    score = out[:, 4] * class_probs.max(axis=1)
    idx = np.flatnonzero(score >= np.float32(score_threshold))
    if idx.size == 0:
        return []
    out, score, class_probs = out[idx], score[idx], class_probs[idx]
    half_w, half_h = out[:, 2] / np.float32(2), out[:, 3] / np.float32(2)
    boxes = np.stack([out[:, 0] - half_w, out[:, 1] - half_h, out[:, 0] + half_w, out[:, 1] + half_h], axis=1)
    keep = nms(boxes, score, nms_iou)[:max_detections]
    return [Detection(box=tuple(float(v) for v in boxes[i]), score=float(score[i]),
                      cls=int(class_probs[i].argmax()), class_probs=class_probs[i].copy())
            for i in keep]
