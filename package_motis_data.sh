#!/usr/bin/env bash
# Movitop — package a compiled MOTIS graph + map into one zip for offline
# distribution to installed devices via the in-app "Import updated transit
# data" screen (DataImportActivity). No server, no download — copy this zip
# onto the device over USB/SD card, then pick it in the app.
#
# Run this after compile_motis_graph.sh produces a fresh movitop_data/data/.
#
# Usage:
#   ./package_motis_data.sh [output.zip]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA_DIR="${SCRIPT_DIR}/movitop_data"
GRAPH_DIR="${DATA_DIR}/data"
TZDATA_DIR="${DATA_DIR}/tzdata"
MAP_FILE="${DATA_DIR}/israel.map"
OUTPUT="${1:-${SCRIPT_DIR}/movitop-data-$(date +%Y%m%d).zip}"

log() { echo "[package_motis_data] $*"; }

if [[ ! -f "${GRAPH_DIR}/config.yml" ]]; then
  echo "ERROR: ${GRAPH_DIR}/config.yml not found. Run compile_motis_graph.sh first." >&2
  exit 1
fi

rm -f "${OUTPUT}"
log "Zipping ${GRAPH_DIR} (+ israel.map if present) -> ${OUTPUT}"

( cd "${GRAPH_DIR}" && zip -r -q "${OUTPUT}" . )
if [[ -d "${TZDATA_DIR}" ]]; then
  ( cd "${DATA_DIR}" && zip -r -q "${OUTPUT}" tzdata )
fi
if [[ -f "${MAP_FILE}" ]]; then
  ( cd "${DATA_DIR}" && zip -q "${OUTPUT}" israel.map )
fi

log "Done: ${OUTPUT} ($(du -sh "${OUTPUT}" | cut -f1))"
log "Copy this file to the device (USB/SD card) and import it via the app's data-import screen."
