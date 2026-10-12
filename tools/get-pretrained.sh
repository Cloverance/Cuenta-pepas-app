#!/usr/bin/env bash
# Downloads the official YOLOX-Nano COCO checkpoint (Apache-2.0, see NOTICE) to data/local/weights/
# and verifies it against the sha256 recorded on first download (2026-10-11).
# Usage: bash tools/get-pretrained.sh
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

URL="https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_nano.pth"
SHA256="cd28f55fbbc1829f99d9ac9b38a16d259a22889739c8728ea877610201feff7b"
DEST="$CP_ROOT/data/local/weights/yolox_nano_coco.pth"

if [ ! -f "$DEST" ]; then
  mkdir -p "$(dirname "$DEST")"
  echo "==> Downloading $URL"
  curl -fL --retry 3 -o "$DEST.part" "$URL"
  mv "$DEST.part" "$DEST"
fi
actual="$(sha256sum "$DEST" | cut -d' ' -f1)"
echo "$actual  $DEST"
if [ "$actual" != "$SHA256" ]; then
  echo "ERROR: sha256 mismatch for $DEST (expected $SHA256)" >&2
  exit 1
fi
