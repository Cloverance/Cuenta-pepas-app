"""Data source catalog (data/sources/*.yaml) with provenance and license."""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

import yaml

from cuentapepas.taxonomy import REPO_ROOT

SOURCES_DIR = REPO_ROOT / "data" / "sources"
REQUIRED = ("name", "license", "status", "class_map", "source_classes")
STATUSES = ("usable", "reference", "blocked")


class CatalogError(ValueError):
    pass


@dataclass(frozen=True)
class Source:
    name: str
    license: str
    status: str
    class_map: dict
    source_classes: dict
    exclude_original_ids: list = field(default_factory=list)
    raw: dict = field(default_factory=dict, compare=False, repr=False)

    def require_trainable(self) -> None:
        if self.status != "usable":
            raise CatalogError(f"source '{self.name}' has status '{self.status}' and cannot be used for training")


def load_source(name: str, sources_dir: Path = SOURCES_DIR) -> Source:
    path = sources_dir / f"{name}.yaml"
    raw = yaml.safe_load(path.read_text(encoding="utf-8"))
    missing = [k for k in REQUIRED if k not in raw]
    if missing:
        raise CatalogError(f"{path.name}: missing fields {missing}")
    if raw["status"] not in STATUSES:
        raise CatalogError(f"{path.name}: invalid status '{raw['status']}'")
    return Source(
        name=raw["name"], license=raw["license"], status=raw["status"],
        class_map=raw["class_map"] or {},
        source_classes={int(k): v for k, v in (raw["source_classes"] or {}).items()},
        exclude_original_ids=[str(x) for x in raw.get("exclude_original_ids", [])],
        raw=raw,
    )


def load_all_sources(sources_dir: Path = SOURCES_DIR) -> list[Source]:
    return [load_source(p.stem, sources_dir) for p in sorted(sources_dir.glob("*.yaml"))]
