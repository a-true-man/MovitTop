#!/usr/bin/env bash
# Movitop — offline data bootstrap (macOS)
# Creates movitop_data/, clones MOTIS, downloads Israel GTFS and OSM PBF.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA_DIR="${SCRIPT_DIR}/movitop_data"

MOTIS_REPO="https://github.com/motis-project/motis.git"
# Must match MOTIS_REF in .github/workflows/build-motis-binary.yml — the
# jniLibs/*.so binaries shipped to devices are cross-compiled from that exact
# commit. A graph compiled against any other commit can silently drift in
# on-disk format (nigiri_bin_ver and friends), and the on-device MOTIS server
# then refuses to start with "no existing version found" / "binary version
# mismatch". Previously this cloned/pulled whatever was newest on MOTIS's
# default branch, which is exactly how that drift happened. Bump both places
# together (then rebuild+commit the binaries) if you ever change this.
MOTIS_REF="dd233976d5e2497babcb0ba9be15fe4b30a78f5f"
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
  log "MOTIS repo exists, fetching pinned commit ${MOTIS_REF} ..."
  git -C "${MOTIS_DIR}" fetch --depth 1 origin "${MOTIS_REF}"
  git -C "${MOTIS_DIR}" checkout --detach FETCH_HEAD
else
  log "Cloning MOTIS at pinned commit ${MOTIS_REF} ..."
  git init "${MOTIS_DIR}"
  git -C "${MOTIS_DIR}" remote add origin "${MOTIS_REPO}"
  git -C "${MOTIS_DIR}" fetch --depth 1 origin "${MOTIS_REF}"
  git -C "${MOTIS_DIR}" checkout --detach FETCH_HEAD
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
