import json
import os
import shutil
from pathlib import Path

import pytest
from loguru import logger
from PIL import Image

from cuentapepas.catalog import load_source
from cuentapepas.datasets.coco_build import build_coco, group_split, session_groups
from cuentapepas.datasets.croppie import original_id, read_croppie_split
from cuentapepas.taxonomy import load_taxonomy


def _make(root: Path, split: str, stem: str, rows: list[str]):
    for original_split in ("train", "val"):  # a real raw layout always has both (possibly empty) splits
        (root / original_split / original_split / "images").mkdir(parents=True, exist_ok=True)
        (root / original_split / original_split / "labels").mkdir(parents=True, exist_ok=True)
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
    _make(raw, "train", "222c0", [])                              # second original so neither split is empty
    out = tmp_path / "out"
    manifest = tmp_path / "m.jsonl"
    summary = build_coco(raw, out, manifest, load_source("croppie_coffee_ug"), load_taxonomy(),
                         val_fraction=0.5, seed=1, group_gap_seconds=None)
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
    assert len(lines) == 3 and all(len(json.loads(l)["sha256"]) == 64 for l in lines)


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
                         val_fraction=0.25, seed=3, group_gap_seconds=None)
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
                      val_fraction=0.25, seed=3, group_gap_seconds=None) == summary


# ---------------------------------------------------------------------------------------------------------
# read_croppie_split guards
# ---------------------------------------------------------------------------------------------------------

def test_read_croppie_split_fails_clearly_when_directories_are_missing(tmp_path):
    with pytest.raises(FileNotFoundError, match=r"images"):
        list(read_croppie_split(tmp_path, "train"))
    (tmp_path / "train" / "train" / "images").mkdir(parents=True)
    with pytest.raises(FileNotFoundError, match=r"labels"):
        list(read_croppie_split(tmp_path, "train"))
    (tmp_path / "train" / "train" / "labels").mkdir()
    assert list(read_croppie_split(tmp_path, "train")) == []


def test_read_croppie_split_rejects_image_without_label_file(tmp_path):
    _make(tmp_path, "train", "333c0", ["3 0.5 0.5 0.1 0.1"])
    (tmp_path / "train" / "train" / "labels" / "333c0.txt").unlink()
    with pytest.raises(FileNotFoundError, match=r"333c0"):
        list(read_croppie_split(tmp_path, "train"))


def test_read_croppie_split_rejects_label_file_without_image(tmp_path):
    _make(tmp_path, "train", "444c0", ["3 0.5 0.5 0.1 0.1"])
    (tmp_path / "train" / "train" / "labels" / "444c9.txt").write_text("3 0.5 0.5 0.1 0.1\n", encoding="utf-8")
    with pytest.raises(ValueError, match=r"444c9\.txt"):
        list(read_croppie_split(tmp_path, "train"))


@pytest.mark.parametrize("row", [
    "3 0.5 0.5 0 0.1",       # w = 0
    "3 0.5 0.5 0.1 0",       # h = 0
    "3 0.5 0.5 -0.1 0.1",    # negative w
    "3 0.5 0.5 1.2 0.1",     # w > 1
    "3 0.5 0.5 0.1 1.0001",  # h > 1
    "3 -0.01 0.5 0.1 0.1",   # cx < 0
    "3 1.5 0.5 0.1 0.1",     # cx > 1
    "3 0.5 -0.2 0.1 0.1",    # cy < 0
    "3 0.5 1.01 0.1 0.1",    # cy > 1
    "3 0.5 0.5 nan 0.1",     # not a finite number
])
def test_read_croppie_split_rejects_out_of_range_boxes(tmp_path, row):
    _make(tmp_path, "train", "555c0", ["3 0.5 0.5 0.1 0.1", row])
    with pytest.raises(ValueError, match=r"555c0\.txt:2"):
        list(read_croppie_split(tmp_path, "train"))


def test_read_croppie_split_accepts_boxes_on_the_unit_boundary(tmp_path):
    _make(tmp_path, "train", "666c0", ["3 0 1 1 1", "3 1 0 0.001 0.001"])
    (image,) = read_croppie_split(tmp_path, "train")
    assert image.boxes == ((3, 0.0, 1.0, 1.0, 1.0), (3, 1.0, 0.0, 0.001, 0.001))


# ---------------------------------------------------------------------------------------------------------
# session_groups
# ---------------------------------------------------------------------------------------------------------

def test_session_groups_split_on_gaps_and_use_first_id_as_group():
    a, b, c, d = "1687438155343", "1687438155400", "1687438400000", "1687439000000"
    # a->b 0.06 s, b->c 244.6 s (same session, below 300 s), c->d 600 s (new session)
    assert session_groups([d, b, a, c], gap_seconds=300.0) == {a: a, b: a, c: a, d: d}


def test_session_groups_gap_must_strictly_exceed_the_threshold():
    base = 1_687_438_155_343
    ids = [str(base), str(base + 300_000), str(base + 600_001)]
    groups = session_groups(ids, gap_seconds=300.0)
    assert groups[ids[0]] == groups[ids[1]] == ids[0]      # exactly 300 s apart: same session
    assert groups[ids[2]] == ids[2]                        # 300.001 s after the previous: new session


def test_session_groups_sorts_numerically_not_lexicographically():
    assert session_groups(["1000", "999"], gap_seconds=1.0) == {"999": "999", "1000": "999"}


def test_session_groups_dedupes_and_handles_empty_input():
    assert session_groups(["5", "5", "5"]) == {"5": "5"}
    assert session_groups([]) == {}


@pytest.mark.parametrize("bad", ["abc", "12x", "", "1.5", "-7"])
def test_session_groups_rejects_non_numeric_ids(bad):
    with pytest.raises(ValueError, match="numeric"):
        session_groups(["1687438155343", bad])


def test_session_groups_rejects_negative_gap():
    with pytest.raises(ValueError, match="gap_seconds"):
        session_groups(["1"], gap_seconds=-1.0)


def _make_sessions(raw: Path, n_sessions: int = 10, per_session: int = 3) -> dict[str, str]:
    """Sessions 1 h apart; originals inside a session 20 s apart; each original cropped into both splits."""
    base = 1_687_438_155_343
    session_of: dict[str, str] = {}
    for s in range(n_sessions):
        for k in range(per_session):
            oid = str(base + s * 3_600_000 + k * 20_000)
            session_of[oid] = str(base + s * 3_600_000)
            _make(raw, "train", f"{oid}c0", ["1 0.5 0.5 0.1 0.1"])
            _make(raw, "val", f"{oid}c1", ["1 0.5 0.5 0.1 0.1"])
    return session_of


def test_build_coco_keeps_every_member_of_a_session_in_one_split(tmp_path):
    raw, out, manifest = tmp_path / "raw", tmp_path / "out", tmp_path / "m.jsonl"
    session_of = _make_sessions(raw)
    summary = build_coco(raw, out, manifest, load_source("croppie_coffee_ug"), load_taxonomy(),
                         val_fraction=0.3, seed=5)
    rows = [json.loads(line) for line in manifest.read_text().splitlines()]
    splits_of_session: dict[str, set[str]] = {}
    for r in rows:
        splits_of_session.setdefault(session_of[r["original_id"]], set()).add(r["split"])
        assert r["group_id"] == session_of[r["original_id"]]
    assert all(len(v) == 1 for v in splits_of_session.values())
    assert summary["group_gap_seconds"] == 300.0
    assert summary["n_groups"] == {"train": 7, "val": 3}
    assert summary["n_originals"] == {"train": 21, "val": 9}


def test_build_coco_without_session_gap_groups_per_original(tmp_path):
    raw, out, manifest = tmp_path / "raw", tmp_path / "out", tmp_path / "m.jsonl"
    _make_sessions(raw)
    summary = build_coco(raw, out, manifest, load_source("croppie_coffee_ug"), load_taxonomy(),
                         val_fraction=0.3, seed=5, group_gap_seconds=None)
    rows = [json.loads(line) for line in manifest.read_text().splitlines()]
    originals = sorted({r["original_id"] for r in rows})
    expected = group_split(originals, 0.3, 5)
    assert {r["original_id"]: r["split"] for r in rows} == expected
    assert all(r["group_id"] == r["original_id"] for r in rows)
    assert summary["group_gap_seconds"] is None
    assert summary["n_groups"] == summary["n_originals"] == {"train": 21, "val": 9}


# ---------------------------------------------------------------------------------------------------------
# build_coco guards
# ---------------------------------------------------------------------------------------------------------

def _build(raw: Path, tmp_path: Path, **kwargs):
    kwargs.setdefault("val_fraction", 0.5)
    kwargs.setdefault("seed", 1)
    kwargs.setdefault("group_gap_seconds", None)
    return build_coco(raw, tmp_path / "out", tmp_path / "m.jsonl", load_source("croppie_coffee_ug"),
                      load_taxonomy(), **kwargs)


@pytest.mark.parametrize("bad", [0.0, 1.0, -0.1, 1.5])
def test_val_fraction_must_be_inside_open_unit_interval(tmp_path, bad):
    with pytest.raises(ValueError, match="val_fraction"):
        group_split(["a", "b"], bad, 1)
    raw = tmp_path / "raw"
    _make(raw, "train", "101c0", [])
    _make(raw, "train", "102c0", [])
    with pytest.raises(ValueError, match="val_fraction"):
        _build(raw, tmp_path, val_fraction=bad)


def test_build_coco_fails_clearly_when_raw_directories_are_missing(tmp_path):
    with pytest.raises(FileNotFoundError, match=r"images"):
        _build(tmp_path / "does-not-exist", tmp_path)
    raw = tmp_path / "raw"
    _make(raw, "train", "101c0", [])
    shutil.rmtree(raw / "val")                      # the 'val' half of the download is missing
    with pytest.raises(FileNotFoundError, match=r"val"):
        _build(raw, tmp_path)


def test_build_coco_rejects_an_empty_dataset(tmp_path):
    raw = tmp_path / "raw"
    for split in ("train", "val"):
        (raw / split / split / "images").mkdir(parents=True)
        (raw / split / split / "labels").mkdir(parents=True)
    with pytest.raises(ValueError, match="no images"):
        _build(raw, tmp_path)


def test_build_coco_rejects_a_dataset_where_every_image_is_excluded(tmp_path):
    raw = tmp_path / "raw"
    _make(raw, "train", "1688117174257c0", ["3 0.5 0.5 0.1 0.1"])
    _make(raw, "val", "1688718305705c0", ["3 0.5 0.5 0.1 0.1"])
    with pytest.raises(ValueError, match="no images"):
        _build(raw, tmp_path)


def test_build_coco_rejects_a_split_left_empty(tmp_path):
    raw = tmp_path / "raw"
    _make(raw, "train", "101c0", [])
    _make(raw, "train", "101c1", [])           # a single original: everything lands in val, train is empty
    with pytest.raises(ValueError, match="train"):
        _build(raw, tmp_path)
    _make(raw, "train", "102c0", [])           # two originals but val_fraction ~ 1: val takes both
    with pytest.raises(ValueError, match="train"):
        _build(raw, tmp_path, val_fraction=0.99)


def test_build_coco_does_not_touch_the_output_when_validation_fails(tmp_path):
    raw = tmp_path / "raw"
    _make(raw, "train", "101c0", [])
    _make(raw, "train", "102c0", [])
    _build(raw, tmp_path)
    before = sorted(p.relative_to(tmp_path) for p in (tmp_path / "out").rglob("*") if p.is_file())
    _make(raw, "val", "103c0", ["9 0.5 0.5 0.1 0.1"])   # unknown class index, found while building annotations
    with pytest.raises(ValueError):
        _build(raw, tmp_path, seed=2)
    assert sorted(p.relative_to(tmp_path) for p in (tmp_path / "out").rglob("*") if p.is_file()) == before


def test_build_coco_rejects_missing_label_file(tmp_path):
    raw = tmp_path / "raw"
    _make(raw, "train", "101c0", [])
    _make(raw, "train", "102c0", [])
    (raw / "train" / "train" / "labels" / "102c0.txt").unlink()
    with pytest.raises(FileNotFoundError, match=r"102c0"):
        _build(raw, tmp_path)


def test_build_coco_rejects_duplicate_image_name_across_source_splits(tmp_path):
    raw = tmp_path / "raw"
    _make(raw, "train", "101c0", [])
    _make(raw, "val", "101c0", [])
    with pytest.raises(ValueError, match=r"duplicate image name '101c0\.jpg'"):
        _build(raw, tmp_path)


def test_build_coco_rejects_unknown_class_index(tmp_path):
    raw = tmp_path / "raw"
    _make(raw, "train", "101c0", ["9 0.5 0.5 0.1 0.1"])
    _make(raw, "train", "102c0", [])
    with pytest.raises(ValueError, match=r"class index 9"):
        _build(raw, tmp_path)


def test_build_coco_rejects_out_of_range_box(tmp_path):
    raw = tmp_path / "raw"
    _make(raw, "train", "101c0", ["3 0.5 0.5 1.5 0.1"])
    _make(raw, "train", "102c0", [])
    with pytest.raises(ValueError, match=r"101c0\.txt:1"):
        _build(raw, tmp_path)


def test_build_coco_removes_stale_images_from_a_previous_build(tmp_path):
    raw, out = tmp_path / "raw", tmp_path / "out"
    for n in range(20):
        _make(raw, "train", f"{n + 100}c0", ["1 0.5 0.5 0.1 0.1"])
    _build(raw, tmp_path, val_fraction=0.5, seed=1)
    stray = out / "train" / "stray.txt"
    stray.write_text("left over", encoding="utf-8")
    sibling = out / "annotations" / "keep.txt"       # outside the split folders: must be left alone
    sibling.write_text("mine", encoding="utf-8")
    summary = _build(raw, tmp_path, val_fraction=0.5, seed=2)   # different seed: many images change split
    for split in ("train", "val"):
        listed = {i["file_name"] for i in json.loads((out / "annotations" / f"{split}.json").read_text())["images"]}
        on_disk = {p.name for p in (out / split).iterdir()}
        assert on_disk == listed and len(listed) == summary["n_images"][split]
    assert not stray.exists() and sibling.read_text(encoding="utf-8") == "mine"


def test_build_coco_warns_once_when_hardlinks_are_unavailable(tmp_path, monkeypatch):
    raw, out = tmp_path / "raw", tmp_path / "out"
    for n in range(6):
        _make(raw, "train", f"{n + 100}c0", ["1 0.5 0.5 0.1 0.1"])

    def no_link(src, dst, *args, **kwargs):
        raise OSError("cross-device link")

    monkeypatch.setattr(os, "link", no_link)
    messages: list[str] = []
    sink = logger.add(lambda m: messages.append(m.record["message"]), level="WARNING")
    try:
        _build(raw, tmp_path)
    finally:
        logger.remove(sink)
    assert len(messages) == 1 and "copied 6 of 6" in messages[0]
    assert len(list((out / "train").glob("*.jpg"))) + len(list((out / "val").glob("*.jpg"))) == 6
