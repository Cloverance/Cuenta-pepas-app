import datetime as dt
import json
from pathlib import Path

import pytest
from PIL import Image

from cuentapepas import train as train_mod
from cuentapepas.train import TrainConfig, build_command, build_env, make_subset, prepare_run


def _coco_dataset(root: Path, n_per_split: int) -> None:
    (root / "annotations").mkdir(parents=True)
    for split in ("train", "val"):
        (root / split).mkdir()
        images, annotations = [], []
        for i in range(1, n_per_split + 1):
            name = f"{split}{i}.jpg"
            Image.new("RGB", (8, 8)).save(root / split / name)
            images.append({"id": i, "file_name": name, "width": 8, "height": 8})
            annotations.append({"id": i, "image_id": i, "category_id": 0, "bbox": [1, 1, 2, 2], "area": 4,
                                "iscrowd": 0})
        coco = {"images": images, "annotations": annotations, "categories": [{"id": 0, "name": "green"}]}
        (root / "annotations" / f"{split}.json").write_text(json.dumps(coco), encoding="utf-8")


def test_make_subset_is_seeded_and_keeps_matching_annotations(tmp_path):
    src = tmp_path / "src"
    _coco_dataset(src, n_per_split=10)
    assert make_subset(src, tmp_path / "a", n=4, seed=1) == {"train": 4, "val": 4}
    make_subset(src, tmp_path / "b", n=4, seed=1)
    for split in ("train", "val"):
        a = json.loads((tmp_path / "a" / "annotations" / f"{split}.json").read_text(encoding="utf-8"))
        b = json.loads((tmp_path / "b" / "annotations" / f"{split}.json").read_text(encoding="utf-8"))
        assert a == b
        ids = {im["id"] for im in a["images"]}
        assert {ann["image_id"] for ann in a["annotations"]} == ids
        assert all((tmp_path / "a" / split / im["file_name"]).exists() for im in a["images"])


def test_command_runs_isolated_fp32_with_seed_and_run_dir_opts(tmp_path):
    cfg = TrainConfig(batch=8, pretrained=tmp_path / "w.pth")
    cmd = build_command(cfg, tmp_path / "run", python="py")
    assert cmd[:6] == ["py", "-I", "-X", "utf8", "-m", "cuentapepas.yolox_bridge"]
    assert "--fp16" not in cmd
    assert cmd[cmd.index("-b") + 1] == "8" and cmd[cmd.index("-d") + 1] == "1"
    assert cmd[cmd.index("-c") + 1] == str(tmp_path / "w.pth")
    assert cmd[-4:] == ["seed", str(cfg.seed), "output_dir", str(tmp_path / "run")]
    smoke = build_command(TrainConfig(smoke=True, pretrained=None), tmp_path / "run", python="py")
    assert "-c" not in smoke and smoke[-4:] == ["eval_interval", "1", "print_interval", "1"]


def test_env_sets_exp_overrides(tmp_path, monkeypatch):
    monkeypatch.setenv("TERM", "xterm")
    assert build_env(TrainConfig(epochs=120, workers=0), tmp_path)["CP_MAX_EPOCH"] == "120"
    assert "TERM" not in build_env(TrainConfig(), tmp_path)  # loguru would colourise train.log
    env = build_env(TrainConfig(smoke=True, workers=0), tmp_path)
    assert (env["CP_MAX_EPOCH"], env["CP_WORKERS"], env["CP_DATASET_DIR"]) == ("1", "0", str(tmp_path))


def test_prepare_run_fails_before_creating_the_run_dir_when_inputs_are_missing(tmp_path):
    manifest = tmp_path / "m.jsonl"
    manifest.write_text("{}\n", encoding="utf-8")
    cfg = TrainConfig(dataset=tmp_path / "missing", manifest=manifest, pretrained=None, runs_dir=tmp_path / "runs")
    with pytest.raises(FileNotFoundError, match="build-dataset"):
        prepare_run(cfg)
    with pytest.raises(FileNotFoundError, match="get-pretrained"):
        prepare_run(TrainConfig(pretrained=tmp_path / "w.pth", runs_dir=tmp_path / "runs"))
    assert not (tmp_path / "runs").exists()


def test_prepare_run_records_provenance(tmp_path, monkeypatch):
    monkeypatch.setattr(train_mod, "gpu_info", lambda: {"name": "fake"})
    monkeypatch.setattr(train_mod, "yolox_state", lambda: {"commit": "pinned"})
    dataset = tmp_path / "ds"
    _coco_dataset(dataset, n_per_split=40)
    manifest = tmp_path / "m.jsonl"
    manifest.write_text('{"file": "x"}\n', encoding="utf-8")
    manifest.with_suffix(".summary.json").write_text('{"n_images": {"train": 40}}', encoding="utf-8")
    weights = tmp_path / "w.pth"
    weights.write_bytes(b"w")
    cfg = TrainConfig(smoke=True, dataset=dataset, manifest=manifest, pretrained=weights, runs_dir=tmp_path / "runs")
    now = dt.datetime(2026, 10, 11, 12, 0, 0, tzinfo=dt.timezone.utc)

    run_dir, record, dataset_dir = prepare_run(cfg, now=now)

    assert run_dir.name == "20261011T120000Z-cuentapepas_nano-smoke"
    assert dataset_dir == run_dir / "smoke_dataset"
    on_disk = json.loads((run_dir / "run.json").read_text(encoding="utf-8"))
    assert on_disk == record
    assert len(record["git"]["commit"]) == 40 and isinstance(record["git"]["dirty"], bool)
    assert len(record["dataset"]["manifest_sha256"]) == 64
    assert record["dataset"]["summary"] == {"n_images": {"train": 40}}
    assert record["dataset"]["smoke_subset"]["n_images"] == {"train": 32, "val": 32}
    assert record["args"]["epochs"] == 1 and record["args"]["fp16"] is False
    assert len(record["args"]["pretrained_sha256"]) == 64
    assert record["seed"] == cfg.seed and record["gpu"] == {"name": "fake"}
    assert record["versions"]["torch"]
