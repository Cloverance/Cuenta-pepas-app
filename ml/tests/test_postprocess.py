import numpy as np

from cuentapepas.postprocess import nms, postprocess


def row(cx, cy, w, h, obj, cls):
    return [cx, cy, w, h, obj, *cls]


def test_threshold_uses_obj_times_cls_and_argmax_class():
    out = np.array([row(100, 100, 20, 20, 0.9, [0.1, 0.1, 0.8, 0.0, 0.0]),
                    row(300, 100, 20, 20, 0.3, [0.9, 0, 0, 0, 0])], dtype=np.float32)
    dets = postprocess(out, score_threshold=0.5, nms_iou=0.45)
    assert len(dets) == 1
    assert dets[0].cls == 2 and abs(dets[0].score - 0.72) < 1e-6
    assert dets[0].box == (90.0, 90.0, 110.0, 110.0)


def test_nms_is_class_agnostic():
    out = np.array([row(100, 100, 20, 20, 0.9, [0.9, 0, 0, 0, 0]),
                    row(102, 100, 20, 20, 0.9, [0, 0, 0.8, 0, 0])], dtype=np.float32)
    dets = postprocess(out, score_threshold=0.1, nms_iou=0.45)
    assert len(dets) == 1 and dets[0].cls == 0


def test_class_probs_are_kept_for_maturity_vote():
    out = np.array([row(50, 50, 10, 10, 1.0, [0.2, 0.6, 0.2, 0, 0])], dtype=np.float32)
    d = postprocess(out, score_threshold=0.1, nms_iou=0.45)[0]
    assert np.allclose(d.class_probs, [0.2, 0.6, 0.2, 0, 0])


def test_threshold_is_inclusive_and_empty_input_gives_no_detections():
    out = np.array([row(50, 50, 10, 10, 0.5, [1.0, 0, 0, 0, 0])], dtype=np.float32)
    assert len(postprocess(out, score_threshold=0.5, nms_iou=0.45)) == 1
    assert postprocess(np.zeros((0, 10), dtype=np.float32), score_threshold=0.1, nms_iou=0.45) == []


def test_ties_keep_the_earlier_row_and_output_is_sorted_by_score():
    out = np.array([row(100, 100, 20, 20, 0.5, [1, 0, 0, 0, 0]),
                    row(100, 100, 20, 20, 0.5, [0, 1, 0, 0, 0]),
                    row(300, 100, 20, 20, 0.9, [0, 0, 1, 0, 0])], dtype=np.float32)
    dets = postprocess(out, score_threshold=0.1, nms_iou=0.45)
    assert [d.cls for d in dets] == [2, 0]


def test_iou_equal_to_threshold_is_kept_and_areas_have_no_plus_one():
    # boxes (0,0,10,10) and (5,0,15,10): intersection 50, union 150 -> IoU 1/3 exactly representable check via threshold
    boxes = np.array([[0, 0, 10, 10], [5, 0, 15, 10]], dtype=np.float32)
    scores = np.array([0.9, 0.8], dtype=np.float32)
    assert nms(boxes, scores, iou_threshold=1 / 3 + 1e-6) == [0, 1]
    assert nms(boxes, scores, iou_threshold=1 / 3 - 1e-6) == [0]


def test_max_detections_caps_the_result():
    n = 400
    out = np.zeros((n, 10), dtype=np.float32)
    out[:, 0] = np.arange(n) * 50.0  # disjoint boxes
    out[:, 1] = 10
    out[:, 2:4] = 10
    out[:, 4] = 1.0
    out[:, 5] = np.linspace(1.0, 0.5, n)
    assert len(postprocess(out, score_threshold=0.1, nms_iou=0.45)) == 300
    assert len(postprocess(out, score_threshold=0.1, nms_iou=0.45, max_detections=5)) == 5
