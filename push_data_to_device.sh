#!/usr/bin/env bash
# Movitop — push the compiled MOTIS graph to a connected Android device/emulator.
#
# Copies movitop_data/data/ into the app's external files dir:
#   /storage/emulated/0/Android/data/<package>/files/motis_data/data
# MotisForegroundService.motisDataDir() returns the parent motis_data/ root;
# MotisBinaryManager runs `./motis-server server -d data` from there.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PACKAGE="iam699030.gmail.movitop"
LOCAL_DATA="${SCRIPT_DIR}/movitop_data/data"
LOCAL_MAP="${SCRIPT_DIR}/movitop_data/israel.map"
REMOTE_BASE="/storage/emulated/0/Android/data/${PACKAGE}/files/motis_data"
REMOTE_DATA="${REMOTE_BASE}/data"
REMOTE_MAP="${REMOTE_BASE}/israel.map"

# Locate adb (PATH first, then the standard SDK location).
ADB="$(command -v adb || true)"
if [[ -z "${ADB}" ]]; then
  for candidate in \
    "${ANDROID_HOME:-}/platform-tools/adb" \
    "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
    "${HOME}/Library/Android/sdk/platform-tools/adb"; do
    if [[ -x "${candidate}" ]]; then ADB="${candidate}"; break; fi
  done
fi
if [[ -z "${ADB}" ]]; then
  echo "ERROR: adb not found. Install platform-tools or add adb to PATH." >&2
  exit 1
fi

if [[ ! -f "${LOCAL_MAP}" && ! -d "${LOCAL_DATA}" ]]; then
  echo "ERROR: neither ${LOCAL_MAP} nor ${LOCAL_DATA} found. Run setup_movitop_data.sh first." >&2
  exit 1
fi

echo "[push_data] adb: ${ADB}"
echo "[push_data] waiting for device..."
"${ADB}" wait-for-device

# A device count check gives a clearer error than a failed push.
DEVICES="$("${ADB}" devices | grep -cw "device" || true)"
if [[ "${DEVICES}" -eq 0 ]]; then
  echo "ERROR: no authorized device/emulator connected." >&2
  exit 1
fi

"${ADB}" shell "mkdir -p '${REMOTE_BASE}'"

# 1) Map first — it's essential for the offline MapView and small enough for
#    emulators. (Clear any stale/partial graph to reclaim space.)
"${ADB}" shell "rm -rf '${REMOTE_DATA}'"
if [[ -f "${LOCAL_MAP}" ]]; then
  MAP_SIZE="$(du -sh "${LOCAL_MAP}" | cut -f1)"
  echo "[push_data] pushing map ${MAP_SIZE} -> ${REMOTE_MAP}"
  "${ADB}" push "${LOCAL_MAP}" "${REMOTE_MAP}"
else
  echo "[push_data] WARNING: ${LOCAL_MAP} not found — skipping map (run setup_movitop_data.sh)."
fi

# 2) Graph is OPTIONAL while the app uses the remote MOTIS server (10.0.2.2).
#    It's only needed by the future on-device native engine and is ~670MB, which
#    does not fit many emulators — so push it best-effort and never fail the run.
if [[ "${PUSH_GRAPH:-0}" == "1" ]]; then
  GRAPH_SIZE="$(du -sh "${LOCAL_DATA}" | cut -f1)"
  echo "[push_data] pushing graph ${GRAPH_SIZE} (PUSH_GRAPH=1) -> ${REMOTE_DATA}"
  if "${ADB}" push "${LOCAL_DATA}" "${REMOTE_BASE}/"; then
    echo "[push_data] graph pushed."
  else
    echo "[push_data] WARNING: graph push failed (likely no space). Using remote server is fine."
    "${ADB}" shell "rm -rf '${REMOTE_DATA}'" || true
  fi
else
  echo "[push_data] skipping ${LOCAL_DATA} graph (set PUSH_GRAPH=1 to push it for the on-device engine)."
fi

echo "[push_data] verifying map on device..."
"${ADB}" shell "ls -l '${REMOTE_MAP}' 2>/dev/null" | tr -d '\r' || true

echo "[push_data] done."
