#!/usr/bin/env bash
# Movitop — offline data bootstrap (macOS)
# Creates movitop_data/, clones MOTIS, downloads Israel GTFS and OSM PBF.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA_DIR="${SCRIPT_DIR}/movitop_data"

MOTIS_REPO="https://github.com/motis-project/motis.git"
GTFS_URL="https://gtfs.mot.gov.il/gtfsfiles/israel-public-transportation.zip"
OSM_URL="https://download.geofabrik.de/asia/israel-and-palestine-latest.osm.pbf"
# Pre-built Mapsforge vector map for the Android frontend (no osmosis needed).
# NOTE: the server uses hyphens in the filename (israel-and-palestine.map).
MAP_URL="https://download.mapsforge.org/maps/v5/asia/israel-and-palestine.map"

GTFS_ZIP="${DATA_DIR}/israel-public-transportation.zip"
OSM_PBF="${DATA_DIR}/israel-and-palestine-latest.osm.pbf"
MAP_FILE="${DATA_DIR}/israel.map"
MOTIS_DIR="${DATA_DIR}/motis"

log() { printf '[setup_movitop_data] %s\n' "$*"; }

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    log "ERROR: '$1' is required but not installed."
    exit 1
  fi
}

download() {
  local url="$1"
  local dest="$2"
  if [[ -f "${dest}" ]]; then
    log "Already present, skipping: $(basename "${dest}")"
    return 0
  fi
  log "Downloading $(basename "${dest}") ..."
  curl -fL --retry 5 --retry-delay 5 -C - -o "${dest}" "${url}"
}

need_cmd git
need_cmd curl

mkdir -p "${DATA_DIR}"
log "Working directory: ${DATA_DIR}"

if [[ -d "${MOTIS_DIR}/.git" ]]; then
  log "MOTIS repo exists, pulling latest ..."
  git -C "${MOTIS_DIR}" pull --ff-only
else
  log "Cloning MOTIS ..."
  git clone --depth 1 "${MOTIS_REPO}" "${MOTIS_DIR}"
fi

download "${GTFS_URL}" "${GTFS_ZIP}"
download "${OSM_URL}" "${OSM_PBF}"
# Pre-built Mapsforge map for the Android offline MapView.
download "${MAP_URL}" "${MAP_FILE}"

log "Done."
log "  MOTIS:  ${MOTIS_DIR}"
log "  GTFS:   ${GTFS_ZIP}"
log "  OSM:    ${OSM_PBF}"
log "  MAP:    ${MAP_FILE}"
log ""
log "Next: install Python deps and run optimize_gtfs.py (see project README or assistant notes)."
