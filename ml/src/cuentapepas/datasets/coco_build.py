"""Build a COCO-format dataset from Croppie with a split grouped by original photo.

The original Croppie train/val splits share almost all original photos (crops of one photo land on both
sides), so they are merged and re-split by original id to avoid train/val leakage.
"""
from __future__ import annotations

import hashlib
import json
import os
import random
import shutil
from pathlib import Path
from typing import Iterable

from cuentapepas.catalog import Source
from cuentapepas.datasets.croppie import CroppieImage, read_croppie_split
from cuentapepas.taxonomy import Taxonomy

ORIGINAL_SPLITS = ("train", "val")
SPLITS = ("train", "val")
DEFAULT_VAL_FRACTION = 0.15
DEFAULT_SEED = 20261011


def group_split(groups: Iterable[str], val_fraction: float, seed: int) -> dict[str, str]:
    """Deterministically assign each unique group to 'train' or 'val' (at least one group goes to val)."""
    if not 0.0 < val_fraction < 1.0:
        raise ValueError(f"val_fraction must be in (0, 1), got {val_fraction}")
    unique = sorted(set(groups))
    random.Random(seed).shuffle(unique)
    n_val = min(len(unique), max(1, round(len(unique) * val_fraction)))
    return {g: ("val" if i < n_val else "train") for i, g in enumerate(unique)}


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _link_or_copy(src: Path, dst: Path) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    if dst.exists():
        dst.unlink()  # rebuild is idempotent: replace what a previous run left behind
    try:
        os.link(src, dst)
    except OSError:  # e.g. different volume or filesystem without hardlinks
        shutil.copy2(src, dst)


def _collect_images(raw_root: Path, source: Source) -> tuple[list[CroppieImage], int]:
    """Read both original splits, dropping excluded originals. Returns (images sorted by name, n_excluded)."""
    excluded_ids = set(source.exclude_original_ids)
    images: list[CroppieImage] = []
    seen_names: dict[str, Path] = {}
    n_excluded = 0
    for original_split in ORIGINAL_SPLITS:
        for img in read_croppie_split(raw_root, original_split):
            if img.original_id in excluded_ids:
                n_excluded += 1
                continue
            if img.path.name in seen_names:
                raise ValueError(f"duplicate image name '{img.path.name}': {seen_names[img.path.name]} and {img.path}")
            seen_names[img.path.name] = img.path
            images.append(img)
    images.sort(key=lambda i: i.path.name)
    return images, n_excluded


def build_coco(
    raw_root: Path,
    out_dir: Path,
    manifest_path: Path,
    source: Source,
    taxonomy: Taxonomy,
    val_fraction: float = DEFAULT_VAL_FRACTION,
    seed: int = DEFAULT_SEED,
) -> dict:
    """Write <out_dir>/{annotations/<split>.json, <split>/<file>.jpg} plus the JSONL manifest; return the summary.

    Manifest `n_boxes` counts the boxes kept in the COCO annotations (after dropping unmapped classes).
    """
    source.require_trainable()
    images, excluded_images = _collect_images(raw_root, source)
    assignment = group_split((i.original_id for i in images), val_fraction, seed)

    categories = [{"id": i, "name": name} for i, name in enumerate(taxonomy.ids)]
    coco = {s: {"images": [], "annotations": [], "categories": categories} for s in SPLITS}
    boxes_per_class = {s: {name: 0 for name in taxonomy.ids} for s in SPLITS}
    originals: dict[str, set[str]] = {s: set() for s in SPLITS}
    manifest_rows = []
    dropped_boxes = 0

    for img in images:
        split = assignment[img.original_id]
        originals[split].add(img.original_id)
        dataset = coco[split]
        image_id = len(dataset["images"]) + 1
        dataset["images"].append(
            {"id": image_id, "file_name": img.path.name, "width": img.width, "height": img.height}
        )
        n_boxes = 0
        for class_idx, cx, cy, w, h in img.boxes:
            if class_idx not in source.source_classes:
                raise ValueError(f"{img.path}: class index {class_idx} not in source_classes of '{source.name}'")
            category_id = taxonomy.map_class(source.class_map, source.source_classes[class_idx])
            if category_id is None:
                dropped_boxes += 1
                continue
            abs_w, abs_h = w * img.width, h * img.height
            dataset["annotations"].append({
                "id": len(dataset["annotations"]) + 1,
                "image_id": image_id,
                "category_id": category_id,
                "bbox": [round((cx - w / 2) * img.width, 2), round((cy - h / 2) * img.height, 2),
                         round(abs_w, 2), round(abs_h, 2)],
                "area": round(abs_w * abs_h, 2),
                "iscrowd": 0,
            })
            boxes_per_class[split][taxonomy.ids[category_id]] += 1
            n_boxes += 1
        _link_or_copy(img.path, out_dir / split / img.path.name)
        manifest_rows.append({
            "file": img.path.name,
            "split": split,
            "original_id": img.original_id,
            "sha256": _sha256(img.path),
            "n_boxes": n_boxes,
        })

    (out_dir / "annotations").mkdir(parents=True, exist_ok=True)
    for split in SPLITS:
        (out_dir / split).mkdir(parents=True, exist_ok=True)
        with open(out_dir / "annotations" / f"{split}.json", "w", encoding="utf-8", newline="\n") as fh:
            json.dump(coco[split], fh, separators=(",", ":"))

    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    with open(manifest_path, "w", encoding="utf-8", newline="\n") as fh:
        for row in manifest_rows:
            fh.write(json.dumps(row) + "\n")

    return {
        "seed": seed,
        "val_fraction": val_fraction,
        "n_images": {s: len(coco[s]["images"]) for s in SPLITS},
        "n_originals": {s: len(originals[s]) for s in SPLITS},
        "boxes_per_class": boxes_per_class,
        "dropped_boxes": dropped_boxes,
        "excluded_images": excluded_images,
        "taxonomy_schema_version": taxonomy.schema_version,
    }
