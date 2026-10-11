import pytest

from cuentapepas.catalog import CatalogError, load_all_sources, load_source
from cuentapepas.taxonomy import load_taxonomy


def test_croppie_entry_is_valid():
    src = load_source("croppie_coffee_ug")
    assert src.status == "usable"
    assert src.license == "CC-BY-SA-4.0"
    assert src.source_classes[3] == "red_cherry"


def test_all_class_maps_target_taxonomy():
    tax = load_taxonomy()
    for src in load_all_sources():
        for label in src.class_map:
            tax.map_class(src.class_map, label)  # raises on invalid target


def test_rejects_missing_license(tmp_path):
    p = tmp_path / "bad.yaml"
    p.write_text("name: bad\nstatus: usable\nclass_map: {}\nsource_classes: {}\n", encoding="utf-8")
    with pytest.raises(CatalogError):
        load_source("bad", sources_dir=tmp_path)


def test_blocked_sources_cannot_be_used(tmp_path):
    p = tmp_path / "blk.yaml"
    p.write_text("name: blk\nlicense: unknown\nstatus: blocked\nclass_map: {}\nsource_classes: {}\n", encoding="utf-8")
    src = load_source("blk", sources_dir=tmp_path)
    with pytest.raises(CatalogError):
        src.require_trainable()
