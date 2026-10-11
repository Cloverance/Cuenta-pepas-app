import json
from pathlib import Path

import pytest
from PIL import Image

from cuentapepas.catalog import load_source
from cuentapepas.datasets.coco_build import build_coco, group_split
from cuentapepas.datasets.croppie import original_id, read_croppie_split
from cuentapepas.taxonomy import load_taxonomy


def _make(root: Path, split: str, stem: str, rows: list[str]):
    (root / split / split / "images").mkdir(parents=True, exist_ok=True)
    (root / split / split / "labels").mkdir(parents=True, exist_ok=True)
    Image.new("RGB", (480, 640), (10, 120, 10)).save(root / split / split / "images" / f"{stem}.jpg")
    (root / split / split / "labels" / f"{stem}.txt").write_text("\n".join(rows), encoding="utf-8")


def test_original_id():
    assert original_id("1687438155343c12") == "1687438155343"
    assert original_id("1687438155343") == "1687438155343"


def test_group_split_keeps_groups_together_and_is_deterministic():
    groups = [f"g{i}" for i in range(100)]
    a = group_split(groups, val_fraction=0.15, seed=7)
    b = group_split(groups, val_fraction=0.15, seed=7)
    assert a == b
    assert 10 <= sum(1 for g in groups if a[g] == "val") <= 20
    assert set(a.values()) == {"train", "val"}


def test_build_coco_maps_classes_drops_unsure_and_excluded(tmp_path):
    raw = tmp_path / "raw"
    # class 3 = red_cherry -> ripe(2); class 2 = unsure -> dropped
    _make(raw, "train", "111c0", ["3 0.5 0.5 0.1 0.05", "2 0.2 0.2 0.05 0.05"])
    _make(raw, "val", "111c1", ["1 0.5 0.5 0.1 0.05"])           # same original 111 in both splits
    _make(raw, "train", "1688117174257c0", ["3 0.5 0.5 0.1 0.1"])  # excluded id
    out = tmp_path / "out"
    manifest = tmp_path / "m.jsonl"
    summary = build_coco(raw, out, manifest, load_source("croppie_coffee_ug"), load_taxonomy(),
                         val_fraction=0.5, seed=1)
    train = json.loads((out / "annotations" / "train.json").read_text())
    val = json.loads((out / "annotations" / "val.json").read_text())
    files = [i["file_name"] for i in train["images"] + val["images"]]
    assert "1688117174257c0.jpg" not in files
    splits_of_111 = {s for s, d in (("train", train), ("val", val))
                     for i in d["images"] if i["file_name"].startswith("111")}
    assert len(splits_of_111) == 1                      # group kept together
    anns = train["annotations"] + val["annotations"]
    assert sorted(a["category_id"] for a in anns) == [0, 2]  # green(0), ripe(2); unsure dropped
    a = next(a for a in anns if a["category_id"] == 2)
    assert a["bbox"] == [pytest.approx(216.0, abs=0.01), pytest.approx(304.0, abs=0.01),
                         pytest.approx(48.0, abs=0.01), pytest.approx(32.0, abs=0.01)]
    assert [c["name"] for c in train["categories"]] == ["green", "semi_ripe", "ripe", "overripe", "dry"]
    assert summary["dropped_boxes"] == 1 and summary["excluded_images"] == 1
    lines = manifest.read_text().splitlines()
    assert len(lines) == 2 and all(len(json.loads(l)["sha256"]) == 64 for l in lines)


def test_read_croppie_split_accepts_empty_labels_and_rejects_malformed_lines(tmp_path):
    _make(tmp_path, "train", "222c0", [])
    _make(tmp_path, "train", "222c1", ["3 0.5 0.5 0.1 0.1", "3 0.5 0.5 oops 0.1"])
    with pytest.raises(ValueError, match=r"222c1\.txt:2"):
        list(read_croppie_split(tmp_path, "train"))
    (tmp_path / "train" / "train" / "labels" / "222c1.txt").write_text("3 0.5 0.5 0.1\n", encoding="utf-8")
    with pytest.raises(ValueError, match=r"222c1\.txt:1"):
        list(read_croppie_split(tmp_path, "train"))
    (tmp_path / "train" / "train" / "labels" / "222c1.txt").write_text("3 0.5 0.5 0.1 0.1\n", encoding="utf-8")
    images = {i.path.stem: i for i in read_croppie_split(tmp_path, "train")}
    assert images["222c0"].boxes == () and images["222c0"].original_id == "222"
    assert images["222c1"].boxes == ((3, 0.5, 0.5, 0.1, 0.1),)
    assert (images["222c1"].width, images["222c1"].height) == (480, 640)


def test_build_coco_never_shares_an_original_id_between_splits(tmp_path):
    raw = tmp_path / "raw"
    for n in range(20):  # 20 originals, each cropped into both original splits
        _make(raw, "train", f"{n + 100}c0", ["1 0.5 0.5 0.1 0.1"])
        _make(raw, "train", f"{n + 100}", ["1 0.5 0.5 0.1 0.1"])
        _make(raw, "val", f"{n + 100}c1", ["0 0.5 0.5 0.1 0.1", "4 0.3 0.3 0.1 0.1"])
    out, manifest = tmp_path / "out", tmp_path / "m.jsonl"
    summary = build_coco(raw, out, manifest, load_source("croppie_coffee_ug"), load_taxonomy(),
                         val_fraction=0.25, seed=3)
    rows = [json.loads(line) for line in manifest.read_text().splitlines()]
    by_split = {s: {r["original_id"] for r in rows if r["split"] == s} for s in ("train", "val")}
    assert by_split["train"].isdisjoint(by_split["val"])
    assert len(by_split["val"]) == 5 and len(by_split["train"]) == 15
    assert summary["n_originals"] == {"train": 15, "val": 5}
    assert summary["n_images"] == {"train": 45, "val": 15}
    for r in rows:  # image files were placed next to the annotations YOLOX expects
        assert (out / r["split"] / r["file"]).is_file()
    # class 0 = dark_brown -> overripe(3), class 4 = yellow -> semi_ripe(1), class 1 = green -> green(0)
    assert summary["boxes_per_class"]["train"]["green"] + summary["boxes_per_class"]["val"]["green"] == 40
    assert summary["boxes_per_class"]["train"]["dry"] == 0
    total_boxes = sum(sum(per_class.values()) for per_class in summary["boxes_per_class"].values())
    assert total_boxes == sum(r["n_boxes"] for r in rows) == 80
    # rebuilding over an existing output is idempotent
    assert build_coco(raw, out, manifest, load_source("croppie_coffee_ug"), load_taxonomy(),
                      val_fraction=0.25, seed=3) == summary
