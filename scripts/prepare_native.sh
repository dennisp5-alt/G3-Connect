#!/usr/bin/env bash
# Fetch upstream source separation C ABI and Android arm64 shared libs for the pinned release.
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION='1.13.8'
BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
curl -fL --retry 3 "${BASE}/sherpa-onnx-v${VERSION}-android.tar.bz2" -o "$TMP/sherpa.tar.bz2"
mkdir -p "$TMP/out"
tar -xjf "$TMP/sherpa.tar.bz2" -C "$TMP/out"
LIBS="$(find "$TMP/out" -type f -path '*/arm64-v8a/libsherpa-onnx-c-api.so' | head -1 | xargs -r dirname)"
if [[ -z "$LIBS" || ! -s "$LIBS/libsherpa-onnx-c-api.so" || ! -s "$LIBS/libonnxruntime.so" ]]; then
  echo 'Required prebuilt arm64 C API/ONNX libraries missing from upstream archive' >&2; exit 1
fi
mkdir -p app/src/main/jniLibs/arm64-v8a
cp "$LIBS"/*.so app/src/main/jniLibs/arm64-v8a/
# Copy C++ sibling .so too, where present: C API libraries may depend on it.
mkdir -p app/src/main/cpp/sherpa-onnx/c-api
curl -fL --retry 3 "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v${VERSION}/sherpa-onnx/c-api/c-api.h" \
  -o app/src/main/cpp/sherpa-onnx/c-api/c-api.h
printf 'Native libraries and header ready for v%s\n' "$VERSION"

# Experimental four-stem backend requires ONNX Runtime C API header.
curl -fL --retry 3 "https://raw.githubusercontent.com/microsoft/onnxruntime/v1.18.1/include/onnxruntime/core/session/onnxruntime_c_api.h" \
  -o app/src/main/cpp/onnxruntime_c_api.h
