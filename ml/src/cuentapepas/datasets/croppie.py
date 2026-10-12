"""Reader for the Croppie coffee Uganda YOLO layout: <root>/<split>/<split>/{images,labels}."""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator

from PIL import Image

# (source_class_idx, cx, cy, w, h) with coordinates normalized to [0, 1]
Box = tuple[int, float, float, float, float]


@dataclass(frozen=True)
class CroppieImage:
    path: Path
    original_id: str
    width: int
    height: int
    boxes: tuple[Box, ...]


def original_id(stem: str) -> str:
    """'1687438155343c12' (a crop) and '1687438155343' (the full photo) share the same original id."""
    match = re.match(r"(\d+)", stem)
    if match is None:
        raise ValueError(f"file stem '{stem}' does not start with an original photo id")
    return match.group(1)


def _parse_label_file(path: Path) -> tuple[Box, ...]:
    boxes: list[Box] = []
    for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        parts = line.split()
        try:
            if len(parts) != 5:
                raise ValueError(f"expected 5 fields, got {len(parts)}")
            box: Box = (int(parts[0]), *(float(p) for p in parts[1:]))
        except ValueError as exc:
            raise ValueError(f"{path}:{line_no}: malformed label line {line!r} ({exc})") from exc
        _, cx, cy, w, h = box
        # written so that NaN fails every comparison and is rejected too
        if not (0.0 < w <= 1.0 and 0.0 < h <= 1.0 and 0.0 <= cx <= 1.0 and 0.0 <= cy <= 1.0):
            raise ValueError(
                f"{path}:{line_no}: box out of range in {line!r} (need 0 < w <= 1, 0 < h <= 1, 0 <= cx <= 1, 0 <= cy <= 1)"
            )
        boxes.append(box)
    return tuple(boxes)


def read_croppie_split(raw_root: Path, split: str) -> Iterator[CroppieImage]:
    """Yield every image of an original split in file-name order. An empty label file means no boxes.

    Raises FileNotFoundError if the split directories are missing or an image has no label file, and
    ValueError for label files without an image or boxes that fall outside the normalized range.
    """
    images_dir = raw_root / split / split / "images"
    labels_dir = raw_root / split / split / "labels"
    for directory in (images_dir, labels_dir):
        if not directory.is_dir():
            raise FileNotFoundError(f"Croppie '{split}' directory not found: {directory}")
    image_paths = sorted(images_dir.glob("*.jpg"))
    image_stems = {p.stem for p in image_paths}
    orphans = [p for p in sorted(labels_dir.glob("*.txt")) if p.stem not in image_stems]
    if orphans:
        raise ValueError(f"label file without a matching image in {images_dir}: {orphans[0]}"
                         + (f" (and {len(orphans) - 1} more)" if len(orphans) > 1 else ""))
    for image_path in image_paths:
        label_path = labels_dir / f"{image_path.stem}.txt"
        if not label_path.is_file():
            raise FileNotFoundError(f"missing label file for image {image_path}: expected {label_path}")
        with Image.open(image_path) as img:
            width, height = img.size
        yield CroppieImage(
            path=image_path,
            original_id=original_id(image_path.stem),
            width=width,
            height=height,
            boxes=_parse_label_file(label_path),
        )
