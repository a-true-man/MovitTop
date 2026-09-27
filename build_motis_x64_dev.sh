#!/usr/bin/env bash
# Movitop — build a Linux x86_64 MOTIS binary for local emulator testing.
#
# DEV/TEST ONLY. Real phones are arm64-v8a — ship build_motis_arm64.sh's
# output for production. This script exists because most Android emulators
# (and Intel/AMD dev Macs) run x86_64 images, which can't execute an arm64
# binary at all; building linux-amd64-release natively inside the same
# docker-cpp-build image lets you validate the full on-device-engine flow
# (nativeLibraryDir exec, HTTP server, routing) on an emulator before
# spending 30-90 minutes on the arm64 cross-compile.
#
# Output: app/src/main/jniLibs/x86_64/libmotis.so
#
# Usage:
#   ./build_motis_x64_dev.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOTIS_DIR="${SCRIPT_DIR}/movitop_data/motis"
JNILIBS_DIR="${SCRIPT_DIR}/app/src/main/jniLibs/x86_64"
OUTPUT="${JNILIBS_DIR}/libmotis.so"
BUILD_IMAGE="${MOTIS_BUILD_IMAGE:-ghcr.io/motis-project/docker-cpp-build:latest}"
BUILD_DIR="build/amd64-release"
BINARY="${BUILD_DIR}/motis"

log() { echo "[build_motis_x64_dev] $*"; }

if [[ ! -d "${MOTIS_DIR}" ]]; then
  echo "ERROR: ${MOTIS_DIR} not found. Run setup_movitop_data.sh first." >&2
  exit 1
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: docker not found." >&2
  exit 1
fi

mkdir -p "${JNILIBS_DIR}"

log "Pulling ${BUILD_IMAGE} (if needed)..."
docker pull "${BUILD_IMAGE}"

log "Ensuring MOTIS dependencies (Docker pkg with retries)..."
docker run --rm \
  -v "${MOTIS_DIR}:/motis" \
  -w /motis \
  "${BUILD_IMAGE}" \
  bash -c "
    set -euo pipefail
    git config --global --add safe.directory /motis
    git config --global url.\"https://github.com/\".insteadOf git@github.com:
    git config --global url.\"https://github.com/\".insteadOf ssh://git@github.com/
    git config --global http.version HTTP/1.1
    git config --global http.postBuffer 524288000

    cleanup_broken() {
      find deps -maxdepth 1 -mindepth 1 -type d 2>/dev/null | while read -r dir; do
        if [[ -d \"\${dir}/.git\" ]] && ! git -C \"\${dir}\" rev-parse HEAD >/dev/null 2>&1; then
          echo \"[container] removing broken clone \${dir}\"
          rm -rf \"\${dir}\"
        fi
      done
    }

    for attempt in 1 2 3 4 5; do
      cleanup_broken
      rm -f .pkg.lock
      echo \"[container] pkg attempt \${attempt}/5...\"
      if /opt/pkg -l; then
        exit 0
      fi
      echo \"[container] pkg failed — waiting 15s before retry\"
      sleep 15
    done
    echo 'ERROR: pkg dependency fetch failed after 5 attempts' >&2
    exit 1
  "

log "Building MOTIS for linux-amd64 (native, no cross-compile) inside Docker..."
docker run --rm \
  -v "${MOTIS_DIR}:/motis" \
  -v "${JNILIBS_DIR}:/output" \
  -w /motis \
  "${BUILD_IMAGE}" \
  bash -c "
    set -euo pipefail

    echo '[container] configuring linux-amd64-release preset...'
    cmake --preset linux-amd64-release -DCMAKE_CXX_FLAGS='-DINSTALL=.'

    JOBS=\$(nproc 2>/dev/null || echo 4)
    echo \"[container] compiling (jobs=\${JOBS})...\"
    cmake --build --preset linux-amd64-release -j \"\${JOBS}\"

    if [[ ! -f '${BINARY}' ]]; then
      echo 'ERROR: expected binary not found at ${BINARY}' >&2
      exit 1
    fi

    cp '${BINARY}' /output/libmotis.so
    chmod +x /output/libmotis.so
    ls -lh /output/libmotis.so
  "

if [[ ! -f "${OUTPUT}" ]]; then
  echo "ERROR: build finished but ${OUTPUT} is missing." >&2
  exit 1
fi

log "Done: ${OUTPUT} ($(du -sh "${OUTPUT}" | cut -f1))"
