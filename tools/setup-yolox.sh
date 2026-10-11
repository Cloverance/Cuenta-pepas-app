#!/usr/bin/env bash
# Clones YOLOX (Apache-2.0) at the pinned commit into $CP_TOOLS/third_party/YOLOX and applies the local
# compatibility patches in tools/patches/yolox-*.patch. Idempotent: safe to run again.
# YOLOX is not pip-installed (its setup.py builds C++ ops); cuentapepas.yolox_bridge adds it to sys.path.
# Usage: bash tools/setup-yolox.sh
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

REPO="https://github.com/Megvii-BaseDetection/YOLOX"
# Keep in sync with YOLOX_COMMIT in ml/src/cuentapepas/yolox_bridge.py.
COMMIT="6ddff4824372906469a7fae2dc3206c7aa4bbaee"
DEST="$CP_TOOLS/third_party/YOLOX"

if [ ! -d "$DEST/.git" ]; then
  echo "==> Cloning $REPO"
  mkdir -p "$(dirname "$DEST")"
  git clone "$REPO" "$DEST"
fi
if [ "$(git -C "$DEST" rev-parse HEAD)" != "$COMMIT" ]; then
  echo "==> Checking out pinned commit $COMMIT"
  git -C "$DEST" -c advice.detachedHead=false checkout "$COMMIT"
fi

shopt -s nullglob
for patch in "$CP_ROOT"/tools/patches/yolox-*.patch; do
  name="$(basename "$patch")"
  if git -C "$DEST" apply --check "$patch" 2>/dev/null; then
    git -C "$DEST" apply "$patch"
    echo "==> Applied $name"
  elif git -C "$DEST" apply --reverse --check "$patch" 2>/dev/null; then
    echo "==> Already applied: $name"
  else
    echo "ERROR: $name neither applies nor is already applied to $DEST" >&2
    exit 1
  fi
done
git -C "$DEST" status --short
