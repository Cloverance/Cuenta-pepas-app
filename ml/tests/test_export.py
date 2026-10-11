import json

import numpy as np
import pytest

from cuentapepas import export
from cuentapepas.cli import main


def test_letterbox_is_top_left_with_pad_114_bgr_float32():
    img = np.zeros((640, 480, 3), dtype=np.uint8)  # portrait crop: r = min(384/640, 640/480) = 0.6 -> 288x384
    img[..., 0] = 10  # blue channel only
    out = export.letterbox(img)
    assert out.shape == (384, 640, 3) and out.dtype == np.float32
    assert np.all(out[:, 288:] == 114) and np.all(out[:, :288, 0] == 10) and np.all(out[:, :288, 1] == 0)


def test_letterbox_landscape_pads_bottom():
    out = export.letterbox(np.full((360, 640, 3), 50, dtype=np.uint8))  # r = 1.0
    assert np.all(out[:360] == 50) and np.all(out[360:] == 114)


def _report(**over):
    det = {"n_ref": 10, "n_other": 10, "max_count_diff": 0, "mean_best_iou": 0.99}
    ok = {"box_ok": True, "score_ok": True}
    report = {"onnx_max_abs_diff": {"boxes": 1e-5, "scores": 1e-6, **ok},
              "tflite_fp32_max_abs_diff": {"boxes": 1e-3, "scores": 1e-5, **ok},
              "tflite_fp16_detections": {"0.30": det, "0.05": det}, "pytorch_output_finite": True}
    report.update(over)
    return report


def test_check_parity_passes_within_tolerances_and_names_each_violation():
    assert export.check_parity(_report()) == []
    bad = _report(onnx_max_abs_diff={"boxes": 2e-3, "scores": 1e-6, "box_ok": False, "score_ok": True},
                  tflite_fp16_detections={"0.30": {"max_count_diff": 3, "mean_best_iou": 0.5}})
    problems = export.check_parity(bad)
    assert len(problems) == 3 and any("ONNX boxes" in p for p in problems) and any("IoU" in p for p in problems)


def test_check_parity_tolerates_no_detections():
    empty = {"n_ref": 0, "n_other": 0, "max_count_diff": 0, "mean_best_iou": None}
    assert export.check_parity(_report(tflite_fp16_detections={"0.30": empty, "0.05": empty})) == []


def test_model_json_records_transposed_output_only_when_true(tmp_path):
    for name in (export.FP16_NAME, export.FP32_NAME):
        (tmp_path / name).write_bytes(b"x")
    common = dict(training_run="runs/x", metrics={"val_map50_95": 0.2}, metrics_source="checkpoint.best_ap",
                  parity_report_sha256="ab" * 32, fp16=tmp_path / export.FP16_NAME, fp32=tmp_path / export.FP32_NAME)
    plain = export.build_model_json(output_shape=[1, 5040, 10], transposed=False, **common)
    assert "transposed" not in plain["output"] and plain["output"]["shape"] == [1, 5040, 10]
    assert plain["classes"] == ["green", "semi_ripe", "ripe", "overripe", "dry"]
    assert plain["input"]["layout"] == "NHWC" and plain["postprocess"]["max_detections"] == 300
    flipped = export.build_model_json(output_shape=[1, 10, 5040], transposed=True, **common)
    assert flipped["output"]["transposed"] is True
    assert plain["metrics_source"] == "checkpoint.best_ap" and plain["parity_report_sha256"] == "ab" * 32
    assert None not in plain["metrics"].values()


def test_metrics_are_never_fabricated(tmp_path):
    assert export.read_metrics(None, {}) == ({}, "unavailable")
    assert export.read_metrics(tmp_path, {"best_ap": 0.25}) == ({"val_map50_95": 0.25}, "checkpoint.best_ap")
    (tmp_path / "metrics.json").write_text(json.dumps({"val_map50": 0.7, "val_map50_95": 0.4}), encoding="utf-8")
    assert export.read_metrics(tmp_path, {"best_ap": 0.25}) == ({"val_map50": 0.7, "val_map50_95": 0.4}, "metrics.json")
    (tmp_path / "metrics.json").write_text(json.dumps({"val_map50": 0.7, "val_map50_95": None}), encoding="utf-8")
    assert export.read_metrics(tmp_path, {}) == ({"val_map50": 0.7}, "metrics.json")


def test_find_checkpoint_and_missing(tmp_path):
    with pytest.raises(export.ExportError):
        export.find_checkpoint(tmp_path)
    (tmp_path / "exp").mkdir()
    (tmp_path / "exp" / "best_ckpt.pth").write_bytes(b"x")
    assert export.find_checkpoint(tmp_path) == tmp_path / "exp" / "best_ckpt.pth"
    (tmp_path / "exp2").mkdir()
    (tmp_path / "exp2" / "best_ckpt.pth").write_bytes(b"x")
    with pytest.raises(export.ExportError, match="exp2"):
        export.find_checkpoint(tmp_path)


def test_cli_export_requires_run_or_ckpt():
    with pytest.raises(SystemExit):
        main(["export"])
    with pytest.raises(SystemExit):
        main(["export", "--run", "a", "--ckpt", "b"])


def test_cli_export_reports_failure_with_exit_code_1(tmp_path, capsys):
    assert main(["export", "--ckpt", str(tmp_path / "missing.pth"), "--out", str(tmp_path / "out")]) == 1
    assert "checkpoint not found" in capsys.readouterr().err
    assert not (tmp_path / "out").exists()


def test_max_diffs_gates_only_rows_with_a_meaningful_score_and_allows_relative_box_slack():
    ref = np.zeros((3, 10), dtype=np.float32)
    ref[0, 4], ref[0, 5] = 1.0, 0.9            # score 0.9: matters
    ref[1, 4], ref[1, 5] = 0.01, 0.5           # score 0.005: background
    ref[2, 2], ref[2, 4], ref[2, 5] = 400.0, 1.0, 0.9  # matters, big box
    other = ref.copy()
    other[0, 0] += 0.0005
    other[1, 2] += 0.5                          # large error on a background row
    other[2, 2] += 0.02                         # 5e-5 relative: within 1e-3 + 1e-4*400 = 0.041
    d = export._max_diffs(ref, other, export.TOL_ONNX)
    assert abs(d["boxes"] - 0.02) < 1e-4 and d["scores"] == 0.0 and d["box_ok"] and d["score_ok"]
    assert abs(d["boxes_all_rows"] - 0.5) < 1e-6
    other[2, 2] += 0.05                         # now 0.07 > 0.041
    assert not export._max_diffs(ref, other, export.TOL_ONNX)["box_ok"]


def test_letterbox_rounds_half_up_like_kotlin_round_to_int():
    # h=768, w=1 -> scale = 0.5 (exact), width = 0.5: half-up gives 1 (Python's banker's round gives 0).
    out = export.letterbox(np.full((768, 1, 3), 50, dtype=np.uint8))
    assert np.all(out[:384, :1] == 50) and np.all(out[:, 1:] == 114)
    # h=1, w=3 -> scale 640/3 = 213.33 (width-bound); height floor(213.33 + .5) = 213
    out = export.letterbox(np.full((1, 3, 3), 50, dtype=np.uint8))
    assert np.all(out[:213] == 50) and np.all(out[213:] == 114)


def _install_files(tmp_path):
    src = tmp_path / "src"
    src.mkdir()
    files = []
    for name in ("a.tflite", "b.tflite", "model.json"):
        (src / name).write_text(name, encoding="utf-8")
        files.append((src / name, name))
    return files


def test_install_atomic_installs_all_files_and_leaves_no_part_files(tmp_path):
    out = tmp_path / "out"
    export.install_atomic(_install_files(tmp_path), out)
    assert sorted(p.name for p in out.iterdir()) == ["a.tflite", "b.tflite", "model.json"]


def test_install_atomic_failure_in_the_middle_cleans_up(tmp_path, monkeypatch):
    out = tmp_path / "out"
    real_copy = export.shutil.copyfile
    calls = []

    def flaky(src, dst):
        calls.append(dst)
        if len(calls) == 2:
            raise OSError("disk full")
        return real_copy(src, dst)

    monkeypatch.setattr(export.shutil, "copyfile", flaky)
    with pytest.raises(OSError, match="disk full"):
        export.install_atomic(_install_files(tmp_path), out)
    assert list(out.iterdir()) == []


def test_install_atomic_failure_during_replace_removes_parts_and_never_installs_model_json(tmp_path, monkeypatch):
    out = tmp_path / "out"
    real_replace = export.os.replace
    done = []

    def flaky(src, dst):
        if len(done) == 1:
            raise OSError("replace failed")
        done.append(dst)
        return real_replace(src, dst)

    monkeypatch.setattr(export.os, "replace", flaky)
    with pytest.raises(OSError, match="replace failed"):
        export.install_atomic(_install_files(tmp_path), out)
    assert not list(out.glob("*.part")) and not (out / "model.json").exists()


def test_check_parity_flags_nan_in_onnx_and_tflite_outputs():
    problems = export.check_parity(_report(onnx_output_finite=False, tflite_fp32_output_finite=False,
                                           tflite_fp16_output_finite=False))
    assert len(problems) == 3 and all("NaN/Inf" in p for p in problems)
