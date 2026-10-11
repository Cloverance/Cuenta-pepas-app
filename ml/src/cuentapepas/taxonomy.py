"""Maturity taxonomy (data/taxonomy.yaml) with immutable class indices."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Mapping

import yaml

REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_TAXONOMY_PATH = REPO_ROOT / "data" / "taxonomy.yaml"


class TaxonomyError(ValueError):
    pass


@dataclass(frozen=True)
class Taxonomy:
    schema_version: int
    ids: list[str]

    @classmethod
    def from_dict(cls, raw: Mapping) -> "Taxonomy":
        classes = sorted(raw["classes"], key=lambda c: c["index"])
        indices = [c["index"] for c in classes]
        if indices != list(range(len(classes))):
            raise TaxonomyError(f"class indices must be 0..n-1 without gaps, got {indices}")
        ids = [c["id"] for c in classes]
        if len(set(ids)) != len(ids):
            raise TaxonomyError(f"duplicate class ids: {ids}")
        return cls(schema_version=int(raw["schema_version"]), ids=ids)

    def index_of(self, class_id: str) -> int:
        try:
            return self.ids.index(class_id)
        except ValueError as exc:
            raise TaxonomyError(f"unknown taxonomy class '{class_id}'") from exc

    def map_class(self, class_map: Mapping[str, str | None], source_label: str) -> int | None:
        """Map a source dataset label to a taxonomy index; None means 'drop this box'."""
        if source_label not in class_map:
            raise TaxonomyError(f"label '{source_label}' missing from class_map")
        target = class_map[source_label]
        return None if target is None else self.index_of(target)


def load_taxonomy(path: Path = DEFAULT_TAXONOMY_PATH) -> Taxonomy:
    with open(path, encoding="utf-8") as fh:
        return Taxonomy.from_dict(yaml.safe_load(fh))
