import json

from PIL import Image

from cuentapepas import cli
from cuentapepas.taxonomy import REPO_ROOT


def _make(root, split, stem, rows):
    (root / split / split / "images").mkdir(parents=True, exist_ok=True)
    (root / split / split / "labels").mkdir(parents=True, exist_ok=True)
    Image.new("RGB", (480, 640)).save(root / split / split / "images" / f"{stem}.jpg")
    (root / split / split / "labels" / f"{stem}.txt").write_text("\n".join(rows), encoding="utf-8")


def test_build_dataset_command_writes_manifest_and_summary(tmp_path, capsys):
    raw = tmp_path / "raw"
    # two photos 2 h apart (ids are epoch milliseconds): two capture sessions, so both splits get one
    _make(raw, "train", "1687438155343c0", ["3 0.5 0.5 0.1 0.1"])
    _make(raw, "val", "1687445355343c0", ["1 0.5 0.5 0.1 0.1"])
    manifest = tmp_path / "manifests" / "demo.jsonl"
    code = cli.main(["build-dataset", "--source", "croppie_coffee_ug", "--raw", str(raw),
                     "--out", str(tmp_path / "out"), "--manifest", str(manifest)])
    assert code == 0
    summary = json.loads((tmp_path / "manifests" / "demo.summary.json").read_text(encoding="utf-8"))
    assert sum(summary["n_images"].values()) == 2
    assert len(manifest.read_text().splitlines()) == 2
    assert "n_images" in capsys.readouterr().out


def test_relative_paths_resolve_against_repo_root():
    assert cli._repo_path("data/local") == REPO_ROOT / "data" / "local"
    assert cli._repo_path(str(REPO_ROOT / "x")) == REPO_ROOT / "x"
