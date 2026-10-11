"""Command line entry point: `cuentapepas <command>`."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from cuentapepas.catalog import load_source
from cuentapepas.datasets.coco_build import DEFAULT_SEED, DEFAULT_VAL_FRACTION, build_coco
from cuentapepas.taxonomy import REPO_ROOT, load_taxonomy


def _repo_path(value: str) -> Path:
    """Relative paths are resolved against the repository root so the CLI works from any directory."""
    path = Path(value)
    return path if path.is_absolute() else REPO_ROOT / path


def _build_dataset(args: argparse.Namespace) -> int:
    source = load_source(args.source)
    manifest = _repo_path(args.manifest)
    summary = build_coco(
        _repo_path(args.raw), _repo_path(args.out), manifest, source, load_taxonomy(),
        val_fraction=args.val_fraction, seed=args.seed,
    )
    summary_path = manifest.with_suffix(".summary.json")
    summary_path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps(summary, indent=2))
    print(f"manifest: {manifest}")
    print(f"summary: {summary_path}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="cuentapepas")
    commands = parser.add_subparsers(dest="command", required=True)

    build = commands.add_parser("build-dataset", help="build a COCO dataset with a group split by original photo")
    build.add_argument("--source", required=True, help="catalog entry name (data/sources/<name>.yaml)")
    build.add_argument("--raw", required=True, help="root of the unzipped source data (contains train/ and val/)")
    build.add_argument("--out", required=True, help="output directory for the COCO dataset")
    build.add_argument("--manifest", required=True, help="output JSONL manifest; the summary goes next to it")
    build.add_argument("--val-fraction", type=float, default=DEFAULT_VAL_FRACTION)
    build.add_argument("--seed", type=int, default=DEFAULT_SEED)
    build.set_defaults(handler=_build_dataset)

    args = parser.parse_args(argv)
    return args.handler(args)


if __name__ == "__main__":
    raise SystemExit(main())
