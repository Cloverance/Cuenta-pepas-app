"""Make the vendored YOLOX checkout importable without installing it (its setup.py builds C++ ops).

Also the subprocess entry point used by `cuentapepas train`: `python -I -X utf8 -m cuentapepas.yolox_bridge train <args>`.
`-I` ignores PYTHONPATH, so YOLOX is put on `sys.path` here; spawned DataLoader workers inherit that `sys.path`
through multiprocessing's preparation data.
"""
from __future__ import annotations

import os
import runpy
import subprocess
import sys
import types
from pathlib import Path

from cuentapepas.taxonomy import REPO_ROOT

YOLOX_COMMIT = "6ddff4824372906469a7fae2dc3206c7aa4bbaee"
YOLOX_DIR = Path(os.environ.get("CP_TOOLS", REPO_ROOT / ".tools")) / "third_party" / "YOLOX"
PATCHES_DIR = REPO_ROOT / "tools" / "patches"


def ensure_yolox() -> Path:
    """Check the pinned checkout and its local patches (tools/setup-yolox.sh), then put it on sys.path."""
    if not (YOLOX_DIR / "yolox" / "__init__.py").exists():
        raise RuntimeError(f"YOLOX not found at {YOLOX_DIR}; run: bash tools/setup-yolox.sh")
    head = subprocess.run(
        ["git", "-C", str(YOLOX_DIR), "rev-parse", "HEAD"], capture_output=True, text=True, check=True
    ).stdout.strip()
    if head != YOLOX_COMMIT:
        raise RuntimeError(f"YOLOX checkout at {head}, expected pinned {YOLOX_COMMIT}")
    for patch in sorted(PATCHES_DIR.glob("yolox-*.patch")):
        applied = subprocess.run(["git", "-C", str(YOLOX_DIR), "apply", "--reverse", "--check", str(patch)],
                                 capture_output=True).returncode == 0
        if not applied:
            raise RuntimeError(f"YOLOX patch {patch.name} is not applied; run: bash tools/setup-yolox.sh")
    if str(YOLOX_DIR) not in sys.path:
        sys.path.insert(0, str(YOLOX_DIR))
    return YOLOX_DIR


def run_yolox_train(argv: list[str]) -> None:
    """Run YOLOX `tools/train.py` with `argv`, letting training errors propagate.

    The `__main__` block of tools/train.py is reproduced here because its `main` is wrapped in `@logger.catch`,
    which logs the exception and exits with status 0; calling the unwrapped function makes failures exit non-zero.
    """
    ensure_yolox()
    # TensorBoard event files do not need TensorFlow, but `torch.utils.tensorboard` imports the full TF package
    # (installed here for the TFLite export) unless `tensorboard.compat.notf` exists: ~1 GB RAM and minutes of
    # start-up on this machine. This is TensorBoard's own switch to force its TF stub.
    sys.modules.setdefault("tensorboard.compat.notf", types.ModuleType("tensorboard.compat.notf"))
    from yolox.core import launch
    from yolox.exp import check_exp_value, get_exp
    from yolox.utils import configure_module, get_num_devices

    tools_train = runpy.run_path(str(YOLOX_DIR / "tools" / "train.py"), run_name="yolox_tools_train")
    main = getattr(tools_train["main"], "__wrapped__", tools_train["main"])

    configure_module()
    args = tools_train["make_parser"]().parse_args(argv)
    exp = get_exp(args.exp_file, args.name)
    exp.merge(args.opts)
    check_exp_value(exp)
    if not args.experiment_name:
        args.experiment_name = exp.exp_name
    num_gpu = get_num_devices() if args.devices is None else args.devices
    if num_gpu != 1:
        raise RuntimeError(f"cuentapepas trains on a single GPU, got devices={num_gpu}")
    if args.cache is not None:
        exp.dataset = exp.get_dataset(cache=True, cache_type=args.cache)
    launch(main, num_gpu, args.num_machines, args.machine_rank, backend=args.dist_backend,
           dist_url="auto" if args.dist_url is None else args.dist_url, args=(exp, args))


if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] != "train":
        raise SystemExit("usage: python -I -m cuentapepas.yolox_bridge train <YOLOX tools/train.py args>")
    run_yolox_train(sys.argv[2:])
