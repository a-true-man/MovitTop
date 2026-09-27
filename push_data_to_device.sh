#!/usr/bin/env bash
# Movitop — push the compiled MOTIS graph to a connected Android device/emulator.
#
# DEV/TEST CONVENIENCE ONLY. Copies movitop_data/{data,tzdata}/ into the
# app's external files dir:
#   /storage/emulated/0/Android/data/<package>/files/motis_data/{data,tzdata}
# MotisForegroundService.motisDataDir() returns the parent motis_data/ root;
# MotisBinaryManager runs `libmotis.so server -d data` from there (tzdata is
# read as ./tzdata relative to that same cwd — see android_seccomp_shim.h /
# the -DINSTALL=. build flag).
#
# `adb push` writes these files as the `shell` user, which on some
# devices/emulators leaves them unreadable by the app's own uid (a real,
# repeatedly-hit issue during development) — this script best-effort `adb
# root`+chowns them to fix that. On a real, non-rooted device `adb root`
# will fail; if the app then reports "Missing config.yml" even though the
# push succeeded, that's this permission issue — use the in-app "Import
# updated transit data" screen instead (DataImportActivity), which writes
# the files as the app itself and never hits it.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PACKAGE="iam699030.gmail.movitop"
LOCAL_DATA="${SCRIPT_DIR}/movitop_data/data"
LOCAL_TZDATA="${SCRIPT_DIR}/movitop_data/tzdata"
LOCAL_MAP="${SCRIPT_DIR}/movitop_data/israel.map"
REMOTE_BASE="/storage/emulated/0/Android/data/${PACKAGE}/files/motis_data"
REMOTE_DATA="${REMOTE_BASE}/data"
REMOTE_TZDATA="${REMOTE_BASE}/tzdata"
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

# 2) Graph (~900MB-1GB) — the on-device engine's actual routing data. Optional
#    per-run since it's large and slow to push repeatedly during UI-only dev.
if [[ "${PUSH_GRAPH:-0}" == "1" ]]; then
  GRAPH_SIZE="$(du -sh "${LOCAL_DATA}" | cut -f1)"
  echo "[push_data] pushing graph ${GRAPH_SIZE} (PUSH_GRAPH=1) -> ${REMOTE_DATA}"
  if "${ADB}" push "${LOCAL_DATA}" "${REMOTE_BASE}/"; then
    echo "[push_data] graph pushed."
  else
    echo "[push_data] WARNING: graph push failed (likely no space)."
    "${ADB}" shell "rm -rf '${REMOTE_DATA}'" || true
  fi

  if [[ -d "${LOCAL_TZDATA}" ]]; then
    echo "[push_data] pushing tzdata -> ${REMOTE_TZDATA}"
    "${ADB}" shell "rm -rf '${REMOTE_TZDATA}'"
    "${ADB}" push "${LOCAL_TZDATA}" "${REMOTE_TZDATA}"
  else
    echo "[push_data] WARNING: ${LOCAL_TZDATA} not found — engine will fail to load the timetable without it."
  fi
else
  echo "[push_data] skipping ${LOCAL_DATA}/${LOCAL_TZDATA} (set PUSH_GRAPH=1 to push them for the on-device engine)."
fi

echo "[push_data] fixing ownership (best-effort; needs adb root — see header comment if it fails) ..."
if "${ADB}" root >/dev/null 2>&1; then
  APP_UID="$("${ADB}" shell pm list packages -U 2>/dev/null | grep "${PACKAGE}" | sed -n 's/.*uid://p' | tr -d '\r')"
  if [[ -n "${APP_UID}" ]]; then
    "${ADB}" shell "chown -R ${APP_UID}:${APP_UID} '${REMOTE_BASE}'" || true
  fi
fi

echo "[push_data] verifying map on device..."
"${ADB}" shell "ls -l '${REMOTE_MAP}' 2>/dev/null" | tr -d '\r' || true

echo "[push_data] done."
