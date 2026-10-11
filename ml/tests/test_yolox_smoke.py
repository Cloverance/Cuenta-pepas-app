import importlib.util
import json

import pytest

from cuentapepas.taxonomy import REPO_ROOT

EXP_FILE = REPO_ROOT / "ml" / "exps" / "cuentapepas_nano.py"


def _load_exp():
    from cuentapepas.yolox_bridge import ensure_yolox

    try:
        ensure_yolox()
    except RuntimeError as exc:  # no pinned checkout on this machine
        pytest.skip(str(exc))
    spec = importlib.util.spec_from_file_location("cuentapepas_nano", EXP_FILE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.Exp()


@pytest.mark.slow
def test_nano_exp_outputs_5040_anchors_with_5_classes():
    torch = pytest.importorskip("torch")
    exp = _load_exp()
    assert exp.num_classes == 5
    assert exp.input_size == exp.test_size == (384, 640)
    model = exp.get_model()
    model.eval()
    model.head.decode_in_inference = True
    with torch.no_grad():
        out = model(torch.zeros(1, 3, 384, 640))
    # 48*80 + 24*40 + 12*20 = 5040 anchors; 4 box + 1 objectness + 5 classes
    assert tuple(out.shape) == (1, 5040, 10)


@pytest.mark.slow
def test_nano_exp_uses_depthwise_nano_backbone_and_custom_split_names(tmp_path, monkeypatch):
    monkeypatch.setenv("CP_DATASET_DIR", str(tmp_path))
    monkeypatch.setenv("CP_MAX_EPOCH", "3")
    monkeypatch.setenv("CP_WORKERS", "0")
    exp = _load_exp()
    assert (exp.depth, exp.width) == (0.33, 0.25)
    assert exp.data_dir == str(tmp_path)
    assert exp.max_epoch == 3 and exp.data_num_workers == 0
    assert (exp.train_ann, exp.val_ann) == ("train.json", "val.json")
    assert exp.enable_mixup is False and exp.mosaic_prob == 1.0
    exp.merge(["seed", "20261011"])
    assert exp.seed == 20261011  # YOLOX keeps opts for None-valued attrs as str; the exp coerces it


def _dense_coco(root, n_boxes):
    from PIL import Image

    for split in ("train", "val"):
        (root / split).mkdir(parents=True)
        Image.new("RGB", (480, 640), (40, 120, 40)).save(root / split / "dense.jpg")
    boxes = [[20.0 + 22 * (i % 20), 20.0 + 22 * (i // 20), 18.0, 18.0] for i in range(n_boxes)]
    coco = {
        "images": [{"id": 1, "file_name": "dense.jpg", "width": 480, "height": 640}],
        "annotations": [{"id": i + 1, "image_id": 1, "category_id": 2, "bbox": b, "area": 324.0, "iscrowd": 0}
                        for i, b in enumerate(boxes)],
        "categories": [{"id": i, "name": n} for i, n in enumerate(["green", "semi_ripe", "ripe", "overripe", "dry"])],
    }
    (root / "annotations").mkdir()
    for split in ("train", "val"):
        (root / "annotations" / f"{split}.json").write_text(json.dumps(coco), encoding="utf-8")


@pytest.mark.slow
def test_train_loader_keeps_more_than_120_labels_per_image(tmp_path, monkeypatch):
    pytest.importorskip("torch")
    _dense_coco(tmp_path, n_boxes=200)
    monkeypatch.setenv("CP_DATASET_DIR", str(tmp_path))
    monkeypatch.setenv("CP_WORKERS", "0")
    exp = _load_exp()
    exp.get_data_loader(batch_size=1, is_distributed=False, no_aug=True)
    _, labels, _, _ = exp.dataset[0]
    assert labels.shape == (exp.max_labels, 5)
    assert int((labels.sum(axis=1) > 0).sum()) == 200
