import pytest
import numpy as np

from cuentapepas.evaluate import evaluate_detections, match_and_count


def _gt():
    cats = [{"id": 0, "name": "a"}, {"id": 1, "name": "b"}]
    imgs = [{"id": 1, "file_name": "1.jpg", "width": 200, "height": 200}]
    anns = [
        {"id": 1, "image_id": 1, "category_id": 0, "bbox": [10, 10, 20, 20], "area": 400.0, "iscrowd": 0},     # small
        {"id": 2, "image_id": 1, "category_id": 1, "bbox": [60, 60, 50, 50], "area": 2500.0, "iscrowd": 0},    # medium
        {"id": 3, "image_id": 1, "category_id": 1, "bbox": [95, 95, 100, 100], "area": 10000.0, "iscrowd": 0},  # large
    ]
    return {"images": imgs, "annotations": anns, "categories": cats}


def _det(cat, bbox, score):
    return {"image_id": 1, "category_id": cat, "bbox": bbox, "score": score}


def test_confusion_recall_and_sizes():
    dets = [
        _det(0, [10, 10, 20, 20], 0.9),      # correct on gt1
        _det(0, [60, 60, 50, 50], 0.8),      # wrong class on gt2 (true b, predicted a)
        _det(1, [0, 150, 20, 20], 0.7),      # false positive -> background row
        _det(1, [95, 95, 100, 100], 0.1),    # below 0.25, ignored: gt3 missed
    ]
    r = match_and_count(_gt(), dets, 2)
    assert r["confusion_matrix"] == [[1, 0, 0], [1, 0, 1], [0, 1, 0]]
    assert r["n_gt"] == 3
    assert r["recall_iou50"] == 2 / 3
    assert r["recall_iou50_class_correct"] == 1 / 3
    assert r["recall_by_size"]["small"]["recall"] == 1.0
    assert r["recall_by_size"]["medium"]["recall"] == 1.0
    assert r["recall_by_size"]["medium"]["recall_class_correct"] == 0.0
    assert r["recall_by_size"]["large"]["recall"] == 0.0


def test_one_detection_matches_one_gt_only():
    r = match_and_count(_gt(), [_det(0, [10, 10, 20, 20], 0.9), _det(0, [10, 10, 20, 20], 0.8)], 2)
    assert r["confusion_matrix"][2][0] == 1  # the duplicate is a false positive


def test_perfect_detections_give_map_one():
    gt = _gt()
    dets = [_det(a["category_id"], a["bbox"], 0.9) for a in gt["annotations"]]
    m = evaluate_detections(gt, dets)
    assert m["map50"] == pytest.approx(1.0) and m["map50_95"] == pytest.approx(1.0)
    assert m["ap50_per_class"] == {"a": pytest.approx(1.0), "b": pytest.approx(1.0)}
    assert m["recall_iou50"] == 1.0
    assert np.trace(np.array(m["confusion_matrix"])) == 3


def test_no_detections():
    m = evaluate_detections(_gt(), [])
    assert m["map50"] == 0.0 and m["recall_iou50"] == 0.0
    assert m["confusion_matrix"][0][2] == 1


def test_bench_images_letterboxed_with_attribution(tmp_path):
    from PIL import Image

    from cuentapepas.fixtures import make_bench_images

    val = tmp_path / "val"
    val.mkdir()
    for i in range(5):
        Image.new("RGB", (480, 640), (200, 50, 50)).save(val / f"{i}.jpg")
    names = make_bench_images(tmp_path / "bench", n=3, val_dir=val)
    assert len(names) == 3
    img = Image.open(tmp_path / "bench" / "bench_00.jpg")
    assert img.size == (1280, 720)
    assert img.getpixel((5, 360)) == (0, 0, 0)  # black side band
    assert "CC BY-SA 4.0" in (tmp_path / "bench" / "ATTRIBUTION.txt").read_text(encoding="utf-8")
