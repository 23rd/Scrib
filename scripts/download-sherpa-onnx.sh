#!/usr/bin/env bash
set -euo pipefail

VERSION="2.0.3"
SHA256="4c766e2e74c9d33d595338c21fe3b305c73ba45d47e96a235675c33142a2ec50"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}/sherpa-onnx-${VERSION}.aar"
DEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/app/libs"
DEST="${DEST_DIR}/sherpa-onnx.aar"

mkdir -p "${DEST_DIR}"

verify() {
  local sum
  if command -v sha256sum >/dev/null 2>&1; then
    sum="$(sha256sum "$1")" || return 1
  else
    sum="$(shasum -a 256 "$1")" || return 1
  fi
  [[ "${sum%% *}" == "${SHA256}" ]]
}

if [[ -f "${DEST}" ]] && verify "${DEST}"; then
  echo "Already present: ${DEST}"
  exit 0
fi

echo "Downloading sherpa-onnx ${VERSION}…"
TEMP="$(mktemp "${DEST}.XXXXXX")"
trap 'rm -f "${TEMP}"' EXIT
curl -fL --progress-bar -o "${TEMP}" "${URL}"
if ! verify "${TEMP}"; then
  echo "Checksum mismatch: sherpa-onnx ${VERSION}" >&2
  exit 1
fi
mv -f "${TEMP}" "${DEST}"
echo "Saved to ${DEST}"
