#!/usr/bin/env bash
# Movitop — cross-compile a Linux ARM64 MOTIS binary for on-device execution.
#
# Uses the official MOTIS docker-cpp-build image (musl ARM64 toolchain) and the
# upstream linux-arm64-release CMake preset. Output lands as a *native library*
# at app/src/main/jniLibs/arm64-v8a/libmotis.so — NOT app/src/main/assets.
#
# Why jniLibs and not assets: since Android 10 (API 29) the OS refuses to
# exec() a file the app copied into its own writable data directory (W^X on
# app-private storage). Packaging the binary as jniLibs/<abi>/libmotis.so
# makes the installer extract it straight into the app's read-only,
# exec-permitted nativeLibraryDir instead — see MotisBinaryManager.kt.
#
# Usage:
#   ./build_motis_arm64.sh
#
# Expect 30–90 minutes on first run (dependency fetch + compile).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOTIS_DIR="${SCRIPT_DIR}/movitop_data/motis"
JNILIBS_DIR="${SCRIPT_DIR}/app/src/main/jniLibs/arm64-v8a"
OUTPUT="${JNILIBS_DIR}/libmotis.so"
BUILD_IMAGE="${MOTIS_BUILD_IMAGE:-ghcr.io/motis-project/docker-cpp-build:latest}"
BUILD_DIR="build/arm64-release"
BINARY="${BUILD_DIR}/motis"

log() { echo "[build_motis_arm64] $*"; }

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

log "Prefetching commonly flaky dependencies on the host (more reliable than in-container git)..."
prefetch_host_dep() {
  local name="$1" url="$2"
  local dir="${MOTIS_DIR}/deps/${name}"
  if [[ -d "${dir}/.git" ]] && git -C "${dir}" rev-parse HEAD >/dev/null 2>&1; then
    return 0
  fi
  rm -rf "${dir}"
  for attempt in 1 2 3; do
    log "  cloning ${name} (attempt ${attempt})..."
    if git clone --depth 1 "${url}" "${dir}"; then
      return 0
    fi
    rm -rf "${dir}"
    sleep 5
  done
  log "  WARNING: could not prefetch ${name}; pkg may retry inside Docker"
  return 0
}

prefetch_host_dep json       https://github.com/motis-project/json.git
prefetch_host_dep utf8proc   https://github.com/triptix-tech/utf8proc.git
prefetch_host_dep osr        https://github.com/motis-project/osr.git
prefetch_host_dep adr        https://github.com/triptix-tech/adr.git
prefetch_host_dep tiles      https://github.com/motis-project/tiles.git
prefetch_host_dep reflect-cpp https://github.com/motis-project/reflect-cpp.git
prefetch_host_dep prometheus-cpp https://github.com/motis-project/prometheus-cpp.git
prefetch_host_dep opentelemetry-cpp https://github.com/motis-project/opentelemetry-cpp.git
prefetch_host_dep unordered_dense https://github.com/motis-project/unordered_dense.git
prefetch_host_dep PROJ       https://github.com/motis-project/PROJ.git

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

log "Building MOTIS for linux-arm64 inside Docker..."
docker run --rm \
  -v "${MOTIS_DIR}:/motis" \
  -v "${JNILIBS_DIR}:/output" \
  -w /motis \
  "${BUILD_IMAGE}" \
  bash -c "
    set -euo pipefail

    echo '[container] configuring linux-arm64-release preset...'
    cmake --preset linux-arm64-release -DCMAKE_CXX_FLAGS='-DINSTALL=.'

    JOBS=\$(nproc 2>/dev/null || echo 4)
    echo \"[container] compiling (jobs=\${JOBS})...\"
    cmake --build --preset linux-arm64-release -j \"\${JOBS}\"

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
