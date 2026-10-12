import pytest

from cuentapepas.taxonomy import Taxonomy, TaxonomyError, load_taxonomy


def test_load_repo_taxonomy():
    tax = load_taxonomy()
    assert tax.schema_version == 1
    assert tax.ids == ["green", "semi_ripe", "ripe", "overripe", "dry"]
    assert tax.index_of("ripe") == 2


def test_rejects_non_contiguous_indices():
    with pytest.raises(TaxonomyError):
        Taxonomy.from_dict({"schema_version": 1, "classes": [
            {"index": 0, "id": "a"}, {"index": 2, "id": "b"}]})


def test_rejects_duplicate_ids():
    with pytest.raises(TaxonomyError):
        Taxonomy.from_dict({"schema_version": 1, "classes": [
            {"index": 0, "id": "a"}, {"index": 1, "id": "a"}]})


def test_map_class_returns_none_for_dropped():
    tax = load_taxonomy()
    class_map = {"red_cherry": "ripe", "low_visibility_unsure": None}
    assert tax.map_class(class_map, "red_cherry") == 2
    assert tax.map_class(class_map, "low_visibility_unsure") is None
    with pytest.raises(TaxonomyError):
        tax.map_class(class_map, "unknown_label")
    with pytest.raises(TaxonomyError):
        tax.map_class({"x": "not_a_class"}, "x")
