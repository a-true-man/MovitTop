#!/usr/bin/env bash
# Movitop — run `motis import` to preprocess GTFS+OSM into movitop_data/data/.
#
# This is the one-time (per data update) "graph compile" step: MOTIS reads
# movitop_data/config.yml + the OSM pbf + the optimized GTFS zip and writes
# out a serialized, mmap-ready graph to movitop_data/data/. That data/
# directory is what push_data_to_device.sh later copies onto a device, and
# what `libmotis.so server -d data` (MotisBinaryManager) serves at runtime.
#
# Runs inside the same docker-cpp-build image using the x86_64 dev binary
# (build_motis_x64_dev.sh) — the import step is pure data preprocessing, so
# doing it on the dev machine's native architecture is fastest; the resulting
# data/ graph is architecture-portable and works unchanged with the arm64
# server binary shipped to real devices.
#
# Usage:
#   ./compile_motis_graph.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA_DIR="${SCRIPT_DIR}/movitop_data"
BINARY="${SCRIPT_DIR}/app/src/main/jniLibs/x86_64/libmotis.so"
BUILD_IMAGE="${MOTIS_BUILD_IMAGE:-ghcr.io/motis-project/docker-cpp-build:latest}"

log() { echo "[compile_motis_graph] $*"; }

if [[ ! -f "${BINARY}" ]]; then
  echo "ERROR: ${BINARY} not found. Run build_motis_x64_dev.sh first." >&2
  exit 1
fi
if [[ ! -f "${DATA_DIR}/config.yml" ]]; then
  echo "ERROR: ${DATA_DIR}/config.yml not found." >&2
  exit 1
fi
if [[ ! -f "${DATA_DIR}/israel-optimized.zip" ]]; then
  echo "ERROR: ${DATA_DIR}/israel-optimized.zip not found. Run optimize_gtfs.py first." >&2
  exit 1
fi
if ! ls "${DATA_DIR}"/israel-and-palestine-latest.osm.pbf >/dev/null 2>&1; then
  echo "ERROR: ${DATA_DIR}/israel-and-palestine-latest.osm.pbf not found. Run setup_movitop_data.sh first." >&2
  exit 1
fi

log "Importing (this reads the OSM pbf + GTFS zip and writes data/) ..."
docker run --rm \
  -v "${DATA_DIR}:/data" \
  -v "${BINARY}:/motis:ro" \
  -w /data \
  "${BUILD_IMAGE}" \
  /motis import

log "Done. Graph at ${DATA_DIR}/data:"
du -sh "${DATA_DIR}/data" 2>/dev/null || true
