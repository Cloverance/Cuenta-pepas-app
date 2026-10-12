"""Build a COCO-format dataset from Croppie with a split grouped by capture session.

The original Croppie train/val splits share almost all original photos (crops of one photo land on both
sides), so they are merged and re-split by group to avoid train/val leakage. A group is a capture session:
original ids are epoch-millisecond timestamps and photos taken close together in time are near-duplicates
(same tree, same light), so they must not straddle the split either.
"""
from __future__ import annotations

import hashlib
import json
import os
import random
import re
import shutil
from pathlib import Path
from typing import Iterable

from loguru import logger

from cuentapepas.catalog import Source
from cuentapepas.datasets.croppie import CroppieImage, read_croppie_split
from cuentapepas.taxonomy import Taxonomy

ORIGINAL_SPLITS = ("train", "val")
SPLITS = ("train", "val")
DEFAULT_VAL_FRACTION = 0.15
DEFAULT_SEED = 20261011
DEFAULT_GROUP_GAP_SECONDS = 300.0


def _check_val_fraction(val_fraction: float) -> None:
    if not 0.0 < val_fraction < 1.0:
        raise ValueError(f"val_fraction must be in (0, 1), got {val_fraction}")


def session_groups(original_ids: Iterable[str], gap_seconds: float = DEFAULT_GROUP_GAP_SECONDS) -> dict[str, str]:
    """Group original ids (epoch-millisecond timestamps) into capture sessions.

    Ids are sorted numerically; a new session starts whenever the gap to the previous id is strictly greater
    than `gap_seconds`. Sessions chain: each photo only needs to be close to its predecessor. Returns
    {original_id: group_id} where group_id is the first (earliest) id of the session.
    """
    if not gap_seconds >= 0:  # also rejects NaN
        raise ValueError(f"gap_seconds must be >= 0, got {gap_seconds}")
    unique = set(original_ids)
    bad = sorted(i for i in unique if re.fullmatch(r"[0-9]+", i) is None)
    if bad:
        raise ValueError(f"original ids must be numeric epoch-millisecond timestamps, got {bad[:5]}")
    gap_ms = gap_seconds * 1000.0
    groups: dict[str, str] = {}
    group_id = ""
    previous_ms: int | None = None
    for oid in sorted(unique, key=lambda i: (int(i), i)):
        ms = int(oid)
        if previous_ms is None or ms - previous_ms > gap_ms:
            group_id = oid
        groups[oid] = group_id
        previous_ms = ms
    return groups


def group_split(groups: Iterable[str], val_fraction: float, seed: int) -> dict[str, str]:
    """Deterministically assign each unique group to 'train' or 'val' (at least one group goes to val)."""
    _check_val_fraction(val_fraction)
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


def _link_or_copy(src: Path, dst: Path) -> OSError | None:
    """Hard-link src to dst, falling back to a copy. Returns the link error if the fallback was used."""
    dst.parent.mkdir(parents=True, exist_ok=True)
    if dst.exists():
        dst.unlink()  # rebuild is idempotent: replace what a previous run left behind
    try:
        os.link(src, dst)
    except OSError as exc:  # e.g. different volume or filesystem without hardlinks
        shutil.copy2(src, dst)
        return exc
    return None


def _prune_split_dir(split_dir: Path, keep: set[str]) -> int:
    """Delete the files of a split folder that are not part of the new split (stale images of an old seed)."""
    if not split_dir.is_dir():
        return 0
    stale = [p for p in split_dir.iterdir() if not p.is_dir() and p.name not in keep]
    for path in stale:
        path.unlink()
    return len(stale)


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
    group_gap_seconds: float | None = DEFAULT_GROUP_GAP_SECONDS,
) -> dict:
    """Write <out_dir>/{annotations/<split>.json, <split>/<file>.jpg} plus the JSONL manifest; return the summary.

    The split is assigned per group: a capture session (see `session_groups`) when `group_gap_seconds` is set,
    or a single original photo when it is None. Raises ValueError if there are no images or if either split
    ends up empty. Nothing is written until the whole dataset has been validated; stale files of a previous
    build are then removed from <out_dir>/<split>.

    Manifest `n_boxes` counts the boxes kept in the COCO annotations (after dropping unmapped classes).
    """
    source.require_trainable()
    _check_val_fraction(val_fraction)
    images, excluded_images = _collect_images(raw_root, source)
    if not images:
        raise ValueError(f"no images found under {raw_root} (excluded as per the catalog: {excluded_images})")

    original_ids = {i.original_id for i in images}
    if group_gap_seconds is None:
        group_of = {oid: oid for oid in original_ids}
    else:
        group_of = session_groups(original_ids, group_gap_seconds)
    assignment = group_split(group_of.values(), val_fraction, seed)
    groups: dict[str, set[str]] = {s: set() for s in SPLITS}
    for group_id, split in assignment.items():
        groups[split].add(group_id)
    for split in SPLITS:
        if not groups[split]:
            raise ValueError(
                f"split '{split}' is empty: {len(assignment)} group(s) and val_fraction={val_fraction} leave it "
                f"without images; add more data, shrink group_gap_seconds ({group_gap_seconds}) or change val_fraction"
            )

    categories = [{"id": i, "name": name} for i, name in enumerate(taxonomy.ids)]
    coco = {s: {"images": [], "annotations": [], "categories": categories} for s in SPLITS}
    boxes_per_class = {s: {name: 0 for name in taxonomy.ids} for s in SPLITS}
    originals: dict[str, set[str]] = {s: set() for s in SPLITS}
    placements: list[tuple[Path, Path]] = []
    manifest_rows = []
    dropped_boxes = 0

    for img in images:
        group_id = group_of[img.original_id]
        split = assignment[group_id]
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
        placements.append((img.path, out_dir / split / img.path.name))
        manifest_rows.append({
            "file": img.path.name,
            "split": split,
            "original_id": img.original_id,
            "group_id": group_id,
            "sha256": _sha256(img.path),
            "n_boxes": n_boxes,
        })

    # Everything validated: from here on the output folder is modified.
    for split in SPLITS:
        _prune_split_dir(out_dir / split, {img["file_name"] for img in coco[split]["images"]})
    link_errors = [err for src, dst in placements if (err := _link_or_copy(src, dst)) is not None]
    if link_errors:
        logger.warning(
            "hard links unavailable ({}): copied {} of {} images instead; this uses extra disk space",
            link_errors[0], len(link_errors), len(placements),
        )

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
        "group_gap_seconds": group_gap_seconds,
        "n_images": {s: len(coco[s]["images"]) for s in SPLITS},
        "n_originals": {s: len(originals[s]) for s in SPLITS},
        "n_groups": {s: len(groups[s]) for s in SPLITS},
        "boxes_per_class": boxes_per_class,
        "dropped_boxes": dropped_boxes,
        "excluded_images": excluded_images,
        "taxonomy_schema_version": taxonomy.schema_version,
    }
