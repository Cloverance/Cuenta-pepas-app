# Plan A — Modelo de detección v1 (datos → entrenamiento → TFLite)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Entrenar un detector YOLOX-Nano de 5 clases de madurez con Croppie UG, exportarlo a TFLite con su contrato documentado y generar fixtures de paridad para la app.

**Architecture:** Paquete Python `cuentapepas` en `ml/` (uv, Python 3.12, entorno en `.tools/ml-venv`). YOLOX (Apache-2.0) se usa desde su código fuente clonado en `.tools/third_party/YOLOX`, agregado a `sys.path` (no se instala: su `setup.py` compila extensiones C++). Los datos crudos y procesados viven en `data/local/` (excluido de git). En git solo van catálogo, taxonomía, manifiestos y código.

**Tech Stack:** Python 3.12, PyTorch 2.14 cu126 (GTX 1050, sm_61, **fp32**: Pascal tiene fp16 lento), YOLOX, pycocotools, onnx, onnx2tf, tensorflow-cpu 2.18, pytest.

**Reglas para todas las tareas:**
- Antes de cualquier comando: `cd "/d/Programas Data/Cuenta Pepas" && source tools/env.sh`. Nada se instala ni se escribe en C:.
- Python se corre con `cd ml && uv run --no-sync python -I ...` y pytest con `cd ml && uv run --no-sync pytest -q`.
- Código, identificadores y logs en inglés; documentación en español.
- Mensajes de commit con la línea final `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

**Hechos verificados del dataset (2026-10-11):**
- Ruta: `data/local/raw/croppie_coffee_ug/data/{train,val}/{train,val}/{images,labels}`. Imágenes de 480×640 (ancho×alto), en formato YOLO.
- Clases de la fuente: `{0: dark_brown_cherry, 1: green_cherry, 2: low_visibility_unsure, 3: red_cherry, 4: yellow_cherry}`.
- Nombres de archivo: `<id_original>c<n>.jpg` para los recortes y `<id_original>.jpg` para la imagen original reducida. Hay 631 originales.
- **Fuga de datos:** 622 de los 631 originales aparecen tanto en train como en val. Hay que volver a dividir por `id_original`.
- `_pictures_to_exclude.txt` lista los ids `1688117174257` y `1688718305705`, que se excluyen.
- La clase `low_visibility_unsure` tiene 740 cajas (menos del 0,5 %) y se descarta.

---

## Estructura de archivos

| Archivo | Responsabilidad |
|---|---|
| `data/taxonomy.yaml` | Taxonomía v1 (5 clases, índices inmutables) |
| `data/sources/croppie_coffee_ug.yaml` | Catálogo de la fuente: licencia, mapeo de clases y forma de descarga |
| `docs/datos/fichas/croppie_coffee_ug.md` | Ficha del dataset (español) |
| `NOTICE` | Atribuciones (YOLOX Apache-2.0, Croppie CC BY-SA 4.0, pesos COCO) |
| `ml/src/cuentapepas/taxonomy.py` | Carga y valida la taxonomía; mapea las clases de cada fuente |
| `ml/src/cuentapepas/catalog.py` | Carga y valida `data/sources/*.yaml` |
| `ml/src/cuentapepas/datasets/croppie.py` | Lee Croppie (YOLO): conversión de clases y agrupado por `id_original` |
| `ml/src/cuentapepas/datasets/coco_build.py` | Construye el dataset COCO con división por grupo, hardlinks y manifiesto JSONL |
| `ml/src/cuentapepas/yolox_bridge.py` | Agrega YOLOX a `sys.path` y verifica la versión fijada |
| `ml/exps/cuentapepas_nano.py` | Exp de YOLOX: 5 clases, entrada 384×640, Nano depthwise |
| `ml/src/cuentapepas/train.py` | Lanzador de entrenamiento (envuelve `tools/train.py` de YOLOX) que registra la ejecución en `runs/` |
| `ml/src/cuentapepas/postprocess.py` | Referencia de posprocesado: umbral obj×cls y NMS sin distinguir clase (igual al de Kotlin) |
| `ml/src/cuentapepas/export.py` | PyTorch→ONNX (con decodificación)→TFLite, más prueba de paridad y `model.json` |
| `ml/src/cuentapepas/fixtures.py` | Fixtures de paridad para Kotlin: salida cruda del modelo y detecciones esperadas |
| `ml/src/cuentapepas/cli.py` | CLI `cuentapepas` con los subcomandos `build-dataset`, `train`, `export`, `fixtures` y `eval` |
| `ml/tests/...` | Pruebas pytest, sin GPU y sin datos reales |
| `docs/modelo/model-card.md` | Ficha del modelo: métricas, datos, limitaciones y licencia |

---

### Task A1: Taxonomía v1

**Files:**
- Create: `data/taxonomy.yaml`, `ml/src/cuentapepas/taxonomy.py`
- Test: `ml/tests/test_taxonomy.py`

- [ ] **Step 1: Crear `data/taxonomy.yaml`**

```yaml
# Taxonomía de madurez de pepas de café. Índices INMUTABLES: solo se agregan clases al final
# y cualquier cambio sube schema_version (ver docs/adr/0003).
schema_version: 1
classes:
  - index: 0
    id: green
    name_es: verde
    description_es: Fruto verde, sin viraje de color.
  - index: 1
    id: semi_ripe
    name_es: pintón
    description_es: Viraje parcial amarillo/naranja/rojo en menos de ~75 % de la superficie.
  - index: 2
    id: ripe
    name_es: maduro (cereza)
    description_es: Rojo (o amarillo en variedades amarillas) uniforme, turgente.
  - index: 3
    id: overripe
    name_es: sobremaduro
    description_es: Rojo oscuro/vinotinto, puede verse arrugado.
  - index: 4
    id: dry
    name_es: seco
    description_es: Café oscuro o negro, seco en la rama.
```

- [ ] **Step 2: Escribir la prueba que falla** (`ml/tests/test_taxonomy.py`)

```python
import pytest
from cuentapepas.taxonomy import Taxonomy, load_taxonomy, TaxonomyError

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
```

- [ ] **Step 3: Correr** `uv run --no-sync pytest -q tests/test_taxonomy.py` → FAIL (`ModuleNotFoundError`).

- [ ] **Step 4: Implementar `ml/src/cuentapepas/taxonomy.py`**

```python
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
```

- [ ] **Step 5: Correr las pruebas** → PASS.
- [ ] **Step 6: Commit** `git add data/taxonomy.yaml ml/src/cuentapepas/taxonomy.py ml/tests/test_taxonomy.py && git commit -m "feat(ml): maturity taxonomy v1 with immutable indices"`

### Task A2: Catálogo de fuentes, ficha y NOTICE

**Files:**
- Create: `data/sources/croppie_coffee_ug.yaml`, `ml/src/cuentapepas/catalog.py`, `docs/datos/fichas/croppie_coffee_ug.md`, `NOTICE`
- Test: `ml/tests/test_catalog.py`

- [ ] **Step 1: `data/sources/croppie_coffee_ug.yaml`**

```yaml
name: croppie_coffee_ug
title: Croppie coffee Uganda
url: https://huggingface.co/datasets/rgautroncgiar/croppie_coffee_ug
provider: Producers Direct & Alliance Bioversity International and CIAT
license: CC-BY-SA-4.0
license_note: "La ficha de HF enlaza además a una guía GPLv3 (license_link); el texto del README declara CC BY-SA 4.0. Se trata como CC BY-SA 4.0."
commercial_use: true
share_alike: true
status: usable
species: [arabica, robusta]
capture: "Celulares de pequeños productores en finca (Uganda), partes del árbol, a veces con tabla de fondo"
approx_images: 8333
format: yolo
annotation: bbox_amodal_partial   # cajas sobre frutos >10 mm; guía de anotación incluida
download:
  method: http
  base_url: https://huggingface.co/datasets/rgautroncgiar/croppie_coffee_ug/resolve/main
  files: [data/train.zip, data/val.zip, _classes.txt, _pictures_to_exclude.txt, README.md, annotation_guide.html]
  retrieved: 2026-10-11
source_classes: {0: dark_brown_cherry, 1: green_cherry, 2: low_visibility_unsure, 3: red_cherry, 4: yellow_cherry}
class_map:
  green_cherry: green
  yellow_cherry: semi_ripe     # en variedades de fruto amarillo, amarillo = maduro (limitación conocida)
  red_cherry: ripe
  dark_brown_cherry: overripe  # la fuente fusiona sobremaduro y seco; 'dry' queda sin datos de esta fuente
  low_visibility_unsure: null  # <0.5 % de cajas; se descarta
exclude_original_ids: ["1688117174257", "1688718305705"]
notes: >
  Las particiones train/val originales comparten 622 de 631 imágenes originales (recortes de la misma foto
  en ambas). Se re-particiona por id original. Nunca se redistribuye el dataset.
```

- [ ] **Step 2: Prueba que falla** (`ml/tests/test_catalog.py`)

```python
import pytest
from cuentapepas.catalog import load_source, load_all_sources, CatalogError
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
```

- [ ] **Step 3: Implementar `ml/src/cuentapepas/catalog.py`**

```python
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
```

- [ ] **Step 4: Escribir `docs/datos/fichas/croppie_coffee_ug.md`.** En español, con tabla resumen (licencia, uso comercial, ShareAlike, captura, conteos por clase verificados: train 104.591 cajas, val 44.873; rojo 75 k, verde 50 k, amarillo 17 k, café oscuro 7 k, dudoso 740), la fuga train/val y su corrección, los sesgos (Uganda, robusta incluida, tabla de fondo, sin sombrío colombiano) y "cómo citar".
- [ ] **Step 5: Escribir `NOTICE`.** Proyecto MIT. Este producto usa:
  - YOLOX © Megvii, Apache-2.0, https://github.com/Megvii-BaseDetection/YOLOX
  - Croppie coffee Uganda © 2024 Producers Direct y Alliance Bioversity & CIAT, CC BY-SA 4.0. Los pesos del modelo entrenados con estos datos se distribuyen con esta atribución.
  - Pesos preentrenados COCO de YOLOX: el dataset COCO es CC BY 4.0, con imágenes bajo los términos de Flickr.
  - TensorFlow Lite, Apache-2.0.
- [ ] **Step 6:** `uv run --no-sync pytest -q` → PASS. **Commit** `feat(ml): data source catalog with Croppie entry, datasheet and NOTICE`.

### Task A3: Construcción del dataset COCO con división por foto original

**Files:**
- Create: `ml/src/cuentapepas/datasets/__init__.py` (vacío), `ml/src/cuentapepas/datasets/croppie.py`, `ml/src/cuentapepas/datasets/coco_build.py`, `ml/src/cuentapepas/cli.py`
- Test: `ml/tests/test_coco_build.py`

Salida en `data/local/processed/croppie_v1/` con esta estructura, que es la que espera `COCODataset` de YOLOX (`data_dir/<name>/<file>` y `data_dir/annotations/<json>`):
```
annotations/train.json   annotations/val.json
train/<file>.jpg (hardlinks)   val/<file>.jpg (hardlinks)
```
El manifiesto se escribe en `data/manifests/croppie_v1.jsonl`, que sí va a git. Cada línea tiene `{file, split, original_id, sha256, n_boxes}`. Además se escribe `data/manifests/croppie_v1.summary.json` con los conteos por split y clase y la semilla.

- [ ] **Step 1: Prueba que falla** (`ml/tests/test_coco_build.py`). Usa un mini dataset sintético en `tmp_path`:

```python
import json
from pathlib import Path
from PIL import Image
from cuentapepas.datasets.croppie import read_croppie_split, original_id
from cuentapepas.datasets.coco_build import group_split, build_coco
from cuentapepas.catalog import load_source
from cuentapepas.taxonomy import load_taxonomy

def _make(root: Path, split: str, stem: str, rows: list[str]):
    (root / split / split / "images").mkdir(parents=True, exist_ok=True)
    (root / split / split / "labels").mkdir(parents=True, exist_ok=True)
    Image.new("RGB", (480, 640), (10, 120, 10)).save(root / split / split / "images" / f"{stem}.jpg")
    (root / split / split / "labels" / f"{stem}.txt").write_text("\n".join(rows), encoding="utf-8")

def test_original_id():
    assert original_id("1687438155343c12") == "1687438155343"
    assert original_id("1687438155343") == "1687438155343"

def test_group_split_keeps_groups_together_and_is_deterministic():
    groups = [f"g{i}" for i in range(100)]
    a = group_split(groups, val_fraction=0.15, seed=7)
    b = group_split(groups, val_fraction=0.15, seed=7)
    assert a == b
    assert 10 <= sum(1 for g in groups if a[g] == "val") <= 20
    assert set(a.values()) == {"train", "val"}

def test_build_coco_maps_classes_drops_unsure_and_excluded(tmp_path):
    raw = tmp_path / "raw"
    # class 3 = red_cherry -> ripe(2); class 2 = unsure -> dropped
    _make(raw, "train", "111c0", ["3 0.5 0.5 0.1 0.05", "2 0.2 0.2 0.05 0.05"])
    _make(raw, "val", "111c1", ["1 0.5 0.5 0.1 0.05"])           # same original 111 in both splits
    _make(raw, "train", "1688117174257c0", ["3 0.5 0.5 0.1 0.1"])  # excluded id
    out = tmp_path / "out"
    manifest = tmp_path / "m.jsonl"
    summary = build_coco(raw, out, manifest, load_source("croppie_coffee_ug"), load_taxonomy(),
                         val_fraction=0.5, seed=1)
    train = json.loads((out / "annotations" / "train.json").read_text())
    val = json.loads((out / "annotations" / "val.json").read_text())
    files = [i["file_name"] for i in train["images"] + val["images"]]
    assert "1688117174257c0.jpg" not in files
    splits_of_111 = {s for s, d in (("train", train), ("val", val))
                     for i in d["images"] if i["file_name"].startswith("111")}
    assert len(splits_of_111) == 1                      # group kept together
    anns = train["annotations"] + val["annotations"]
    assert sorted(a["category_id"] for a in anns) == [0, 2]  # green(0), ripe(2); unsure dropped
    a = next(a for a in anns if a["category_id"] == 2)
    assert a["bbox"] == [pytest_approx(216.0), pytest_approx(304.0), pytest_approx(48.0), pytest_approx(32.0)]
    assert [c["name"] for c in train["categories"]] == ["green", "semi_ripe", "ripe", "overripe", "dry"]
    assert summary["dropped_boxes"] == 1 and summary["excluded_images"] == 1
    lines = manifest.read_text().splitlines()
    assert len(lines) == 2 and all(len(json.loads(l)["sha256"]) == 64 for l in lines)

def pytest_approx(v):
    import pytest
    return pytest.approx(v, abs=0.01)
```

- [ ] **Step 2: Implementar `datasets/croppie.py`**
  - `original_id(stem: str) -> str`: con la expresión `re.match(r"(\d+)", stem)`.
  - `read_croppie_split(raw_root, split) -> Iterator[CroppieImage]`: produce `CroppieImage(path, original_id, width, height, boxes=[(source_class_idx, cx, cy, w, h)])` (normalizado).
  - Un archivo de etiquetas vacío es válido (imagen sin cajas). Una línea mal formada lanza `ValueError` con el archivo y el número de línea; no se ignora en silencio.
- [ ] **Step 3: Implementar `datasets/coco_build.py`**
  - `group_split(groups, val_fraction, seed) -> dict[group, "train"|"val"]`: ordena los grupos únicos, los mezcla con `random.Random(seed)` y toma `round(n*val_fraction)` para val (al menos 1).
  - `build_coco(raw_root, out_dir, manifest_path, source, taxonomy, val_fraction=0.15, seed=20261011) -> dict`. Pasos:
    1. Junta las imágenes de los dos splits originales (`train`, `val`).
    2. Excluye `source.exclude_original_ids`.
    3. Asigna split por `original_id` con `group_split`.
    4. Mapea cada caja con `taxonomy.map_class(source.class_map, source.source_classes[idx])`. Si da `None`, descarta la caja y cuenta `dropped_boxes`.
    5. Convierte a COCO en píxeles absolutos: `x=(cx-w/2)*W`, `y=(cy-h/2)*H`, `w*W`, `h*H`, `area=w*h`, `iscrowd=0`. Los ids de imagen y anotación empiezan en 1 y son estables, porque se ordenan por nombre de archivo.
    6. Las categorías son `[{id: i, name: tax.ids[i]}]`, con ids basados en 0. YOLOX hace el mapeo con `coco.getCatIds()`, así que 0 es válido.
    7. Crea hardlinks con `os.link`; si falla (por ejemplo, otro volumen), copia con `shutil.copy2`.
    8. Escribe el manifiesto JSONL ordenado y el resumen. Devuelve el resumen: `{seed, val_fraction, n_images:{train,val}, n_originals:{train,val}, boxes_per_class:{train:{...}, val:{...}}, dropped_boxes, excluded_images, taxonomy_schema_version}`.
- [ ] **Step 4: `cli.py`** con argparse y el subcomando `build-dataset --source croppie_coffee_ug --raw data/local/raw/croppie_coffee_ug/data --out data/local/processed/croppie_v1 --manifest data/manifests/croppie_v1.jsonl`. El resumen se escribe junto al manifiesto como `.summary.json`. `main()` devuelve un código de salida.
- [ ] **Step 5:** `uv run --no-sync pytest -q` → PASS.
- [ ] **Step 6: Correr con los datos reales.** `uv run --no-sync cuentapepas build-dataset ...` (rutas relativas a la raíz del repo; el CLI resuelve contra `REPO_ROOT`). Esperado:
  - unas 8.300 imágenes, unos 535 originales en train y unos 94 en val;
  - `dropped_boxes` ≈ 740 y `excluded_images` > 0;
  - **ningún `original_id` en ambos splits**: se verifica con un script corto sobre el manifiesto.
- [ ] **Step 7: Commit** con el código, las pruebas, `data/manifests/croppie_v1.jsonl` y `.summary.json`: `feat(ml): build group-split COCO dataset from Croppie (fixes train/val leakage)`.

### Task A4: Puente YOLOX, Exp y prueba de humo del entrenamiento

**Files:**
- Create: `ml/src/cuentapepas/yolox_bridge.py`, `ml/exps/cuentapepas_nano.py`, `ml/src/cuentapepas/train.py`, `tools/get-pretrained.sh`
- Modify: `ml/src/cuentapepas/cli.py` (subcomando `train`)
- Test: `ml/tests/test_yolox_smoke.py`

- [ ] **Step 1: `yolox_bridge.py`**

```python
"""Make the vendored YOLOX checkout importable without installing it (its setup.py builds C++ ops)."""
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

from cuentapepas.taxonomy import REPO_ROOT

YOLOX_COMMIT = "6ddff4824372906469a7fae2dc3206c7aa4bbaee"
YOLOX_DIR = Path(os.environ.get("CP_TOOLS", REPO_ROOT / ".tools")) / "third_party" / "YOLOX"


def ensure_yolox() -> Path:
    if not (YOLOX_DIR / "yolox" / "__init__.py").exists():
        raise RuntimeError(f"YOLOX not found at {YOLOX_DIR}; run: git clone https://github.com/Megvii-BaseDetection/YOLOX {YOLOX_DIR}")
    head = subprocess.run(["git", "-C", str(YOLOX_DIR), "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    if head != YOLOX_COMMIT:
        raise RuntimeError(f"YOLOX checkout at {head}, expected pinned {YOLOX_COMMIT}")
    if str(YOLOX_DIR) not in sys.path:
        sys.path.insert(0, str(YOLOX_DIR))
    return YOLOX_DIR
```

- [ ] **Step 2: `ml/exps/cuentapepas_nano.py`.** Adapta `exps/default/yolox_nano.py`. Clase `Exp(yolox.exp.Exp)` con:
  - `num_classes=5`, `depth=0.33`, `width=0.25`;
  - `input_size=test_size=(384, 640)`, `random_size=(10, 20)`;
  - `mosaic_scale=(0.5, 1.5)`, `mosaic_prob=1.0`, `enable_mixup=False`;
  - `data_dir` = valor de la variable de entorno `CP_DATASET_DIR` o, por defecto, `<repo>/data/local/processed/croppie_v1`;
  - `train_ann="train.json"`, `val_ann="val.json"`;
  - `max_epoch=int(os.environ.get("CP_MAX_EPOCH", 120))`, `no_aug_epochs=15`;
  - `data_num_workers=int(os.environ.get("CP_WORKERS", 2))`, `eval_interval=10`, `print_interval=50`;
  - `exp_name="cuentapepas_nano"`;
  - `output_dir=<repo>/runs/yolox`.

  Sobrescribe `get_model()` como en `yolox_nano.py` (YOLOPAFPN y YOLOXHead con `depthwise=True`, `act="silu"`, e `init_yolo` con BN eps 1e-3 y momentum 0.03). Sobrescribe `get_dataset` y `get_eval_dataset` para pasar `name="train"`/`"val"` (no `train2017`/`val2017`).
- [ ] **Step 3: `tools/get-pretrained.sh`.** Descarga `https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_nano.pth` a `data/local/weights/yolox_nano_coco.pth` si no existe, e imprime su sha256.
- [ ] **Step 4: `train.py` y `cli train`.**
  1. `ensure_yolox()`.
  2. Crea `runs/<UTC timestamp>-<exp>/` con `run.json`: commit de git, `git status --porcelain` (aviso si hay cambios sin commit), hash sha256 del manifiesto, resumen del dataset, argumentos, versiones de torch y CUDA, nombre de la GPU y semilla.
  3. Lanza un subproceso: `python -I <YOLOX>/tools/train.py -f ml/exps/cuentapepas_nano.py -d 1 -b <batch> -c <pretrained> --seed? ` (YOLOX acepta `exp.seed` por `-o`/opts; usar `opts=["seed", "20261011", "output_dir", run_dir]`). Lo hace con `env PYTHONPATH=<YOLOX>` y `cwd=<repo>`, y pasa el log del subproceso a `run_dir/train.log`.
  4. **Sin `--fp16`** (Pascal).
  5. Opciones del CLI: `--batch 8 --epochs 120 --workers 2 --smoke`. `--smoke` pone `CP_MAX_EPOCH=1`, usa como dataset un subconjunto de 32 imágenes que se genera con `make_subset()`, y fija `eval_interval=1`.
- [ ] **Step 5: Prueba de humo** (`ml/tests/test_yolox_smoke.py`). Se marca con `@pytest.mark.slow` y se salta si falta el checkout de YOLOX. Con `get_model()` y entrada `torch.zeros(1,3,384,640)` en modo eval y `decode_in_inference=True`, la salida debe tener forma `(1, 5040, 10)` (48·80 + 24·40 + 12·20 = 5040; 4 caja + 1 obj + 5 clases). Registra el marcador `slow` en `pyproject.toml` (`[tool.pytest.ini_options] markers = ["slow: needs YOLOX checkout"]`).
- [ ] **Step 6: Correr** `uv run --no-sync pytest -q -m slow` → PASS. Luego `uv run --no-sync cuentapepas train --smoke`. Esperado: termina una época sin error y escribe `runs/.../train.log` con una línea de AP.
  - Si YOLOX falla por la compatibilidad con Python 3.12 o PyTorch 2.14, se corrige con un **parche mínimo documentado** en `tools/patches/yolox-*.patch`, aplicado por `tools/setup-yolox.sh`. No se edita a mano sin dejar el parche.
  - Si `data_num_workers>0` falla en Windows, se usa `CP_WORKERS=0` y se deja documentado.
- [ ] **Step 7: Commit** `feat(ml): YOLOX bridge, nano exp (384x640, 5 classes) and traceable training launcher`.

### Task A5: Entrenamiento completo y evaluación

- [ ] **Step 1: Medir la memoria.** Con `--batch 8` y `--batch 16`, revisar `nvidia-smi` durante 2 minutos y elegir el mayor tamaño de lote que no supere los 2,8 GB.
- [ ] **Step 2: Lanzar el entrenamiento completo en segundo plano.** `uv run --no-sync cuentapepas train --batch <B> --epochs 120`. La duración se estima con el log de la primera época y se registra en `run.json`.
- [ ] **Step 3: Evaluar.** Al terminar, `cuentapepas eval --run runs/<id>` calcula sobre val, por grupo:
  - mAP50, mAP50-95 y AP50 por clase (COCOeval);
  - recall a IoU 0,5 con score de 0,25;
  - matriz de confusión de clase entre las detecciones emparejadas (IoU ≥ 0,5).

  Todo se guarda en `runs/<id>/metrics.json`.
- [ ] **Step 4: Ficha del modelo.** Escribir `docs/modelo/model-card.md` (español) con:
  - datos, división y métricas;
  - limitaciones: Uganda; `dry` sin ejemplos de entrenamiento, por lo que nunca debería predecirse con confianza; amarillo = maduro en variedades amarillas; sin sombrío colombiano;
  - licencia y atribución.

  Commit `docs: model card for cuentapepas-nano v1 with measured metrics`.

### Task A6: Exportación a TFLite y contrato `model.json`

**Files:**
- Create: `ml/src/cuentapepas/export.py`, `ml/src/cuentapepas/postprocess.py`
- Modify: `ml/src/cuentapepas/cli.py` (subcomando `export`)
- Test: `ml/tests/test_postprocess.py`

- [ ] **Step 1: Prueba de `postprocess.py`.** Usa una salida sintética `(N, 10)`:

```python
import numpy as np
from cuentapepas.postprocess import postprocess, nms

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
```

- [ ] **Step 2: Implementar `postprocess.py`.**
  - Usa `Detection(box: tuple[float,float,float,float] (x1,y1,x2,y2), score: float, cls: int, class_probs: np.ndarray)`.
  - `score = obj * max(cls)`.
  - Filtra con `score >= score_threshold`.
  - Ordena por score de forma descendente y **estable**: `np.argsort(-score, kind="stable")`.
  - NMS voraz sin distinguir clase, con IoU > `nms_iou` = suprimir. El IoU usa áreas `(x2-x1)*(y2-y1)` sin el +1.
  - `max_detections=300`.
  - **Este algoritmo es el contrato que Kotlin replica exactamente.**
- [ ] **Step 3: Implementar `export.py`.**
  1. Carga la Exp y el checkpoint (`best_ckpt.pth`).
  2. Pone `model.head.decode_in_inference = True` y exporta con `torch.onnx.export`: entrada `images` de forma `[1,3,384,640]`, salida `output`, opset 13, sin ejes dinámicos.
  3. Simplifica con onnxsim.
  4. Corre `onnx2tf -i model.onnx -o <tmp> -osd -cotof`. Si `-osd` o `-cotof` no existen en la versión instalada, se usa solo `-i -o`. Se renombran los resultados a `cuentapepas_nano_fp32.tflite` y `cuentapepas_nano_fp16.tflite`.
  5. **Paridad.** Toma 16 imágenes de val y las preprocesa igual que la app: letterbox arriba a la izquierda, `r = min(384/h, 640/w)`, `cv2.resize` con `INTER_LINEAR`, relleno 114, BGR float32 de 0 a 255.
     - Compara PyTorch con ONNX (máx. |Δ| < 1e-3 en cajas y < 1e-4 en scores) y con TFLite fp32 (máx. |Δ| < 1e-2 en cajas y < 1e-3 en scores; NHWC).
     - Compara también las detecciones tras `postprocess` con fp16: el mismo número ±2 por imagen y una media del mejor IoU emparejado > 0,9.
     - Si se superan las tolerancias, la exportación falla con mensaje y no deja artefactos a medias.
  6. Escribe `model.json`:

```json
{
  "model_id": "cuentapepas-nano-v1",
  "taxonomy_schema_version": 1,
  "classes": ["green", "semi_ripe", "ripe", "overripe", "dry"],
  "input": {"name": "images", "width": 640, "height": 384, "layout": "NHWC", "dtype": "float32",
            "channel_order": "BGR", "range": [0, 255], "letterbox": "top_left", "pad_value": 114},
  "output": {"shape": [1, 5040, 10], "layout": ["cx", "cy", "w", "h", "obj", "cls0", "cls1", "cls2", "cls3", "cls4"],
             "coords": "input_pixels", "activations": "sigmoid_applied"},
  "postprocess": {"score": "obj*max(cls)", "default_score_threshold": 0.30, "nms": "class_agnostic_greedy", "default_nms_iou": 0.45, "max_detections": 300},
  "files": {"fp16": {"name": "cuentapepas_nano_fp16.tflite", "sha256": "..."}, "fp32": {"name": "cuentapepas_nano_fp32.tflite", "sha256": "..."}},
  "training_run": "runs/<id>", "metrics": {"val_map50": 0.0, "val_map50_95": 0.0},
  "license_attribution": "Trained on Croppie coffee Uganda (CC BY-SA 4.0) by Producers Direct & Alliance Bioversity-CIAT. YOLOX Apache-2.0."
}
```

  Si en el TFLite la salida queda transpuesta, por ejemplo `[1,10,5040]`, la forma real se registra en `output.shape` y se agrega `"transposed": true`. Kotlin lee esa bandera.
- [ ] **Step 4: Copiar a la app.** `cuentapepas export --run runs/<id> --out android/app/src/main/assets/model/` copia el TFLite fp16 y fp32 y `model.json`. Los `.tflite` están en `.gitignore`; `model.json` sí va a git.
- [ ] **Step 5: Commit** `feat(ml): ONNX/TFLite export with parity checks and model.json contract`.

### Task A7: Fixtures de paridad para Kotlin e imágenes de benchmark

**Files:**
- Create: `ml/src/cuentapepas/fixtures.py`
- Modify: `ml/src/cuentapepas/cli.py` (subcomando `fixtures`)

- [ ] **Step 1: Fixtures del posprocesado.** `cuentapepas fixtures --model android/app/src/main/assets/model/cuentapepas_nano_fp32.tflite --out android/core/src/test/resources/fixtures/`.
  - Toma 3 imágenes de val, corre el TFLite y guarda `raw_<k>.bin`: la salida float32 little-endian de forma `[5040*10]`.
  - Guarda `expected_<k>.json` con `{score_threshold: 0.30, nms_iou: 0.45, detections: [{box:[x1,y1,x2,y2], score, cls}]}` calculado con `postprocess.py`.

  Los `.bin` pesan unos 200 KB cada uno y sí van a git, porque no contienen imagen.
- [ ] **Step 2: Imágenes de benchmark.** Copiar 20 imágenes de val, redimensionadas a 1280×720 con letterbox negro, a `android/app/src/main/assets/bench/`. Son CC BY-SA, así que se agrega `android/app/src/main/assets/bench/ATTRIBUTION.txt`. Las imágenes **no** van a git: se agrega `android/app/src/main/assets/bench/*.jpg` a `.gitignore`. Se documenta en el README cómo regenerarlas.
- [ ] **Step 3: Commit** `feat(ml): Kotlin parity fixtures and benchmark images`.
