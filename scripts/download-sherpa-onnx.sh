#!/usr/bin/env bash
set -euo pipefail

VERSION="1.13.0"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}/sherpa-onnx-${VERSION}.aar"
DEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/app/libs"
DEST="${DEST_DIR}/sherpa-onnx.aar"

mkdir -p "${DEST_DIR}"

if [[ -f "${DEST}" ]]; then
  echo "Already present: ${DEST}"
  exit 0
fi

echo "Downloading sherpa-onnx ${VERSION}…"
curl -fL --progress-bar -o "${DEST}" "${URL}"
echo "Saved to ${DEST}"
