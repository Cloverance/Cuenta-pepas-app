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


@pytest.mark.parametrize("content", ["", "# only a comment\n", "- a\n- b\n", "just a string\n"])
def test_rejects_empty_or_non_mapping_yaml(tmp_path, content):
    (tmp_path / "weird.yaml").write_text(content, encoding="utf-8")
    with pytest.raises(CatalogError, match="mapping"):
        load_source("weird", sources_dir=tmp_path)


def test_rejects_name_that_differs_from_file_stem(tmp_path):
    (tmp_path / "file_name.yaml").write_text(
        "name: other_name\nlicense: MIT\nstatus: usable\nclass_map: {}\nsource_classes: {}\n", encoding="utf-8")
    with pytest.raises(CatalogError, match="other_name"):
        load_source("file_name", sources_dir=tmp_path)
