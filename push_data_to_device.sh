#!/usr/bin/env bash
# Movitop — push the compiled MOTIS graph to a connected Android device/emulator.
#
# DEV/TEST CONVENIENCE ONLY. Copies movitop_data/{data,tzdata}/ into the
# app's INTERNAL files dir (NOT external/sdcard storage):
#   /data/user/0/<package>/files/motis_data/{data,tzdata}
# MotisForegroundService.motisDataDir() returns the parent motis_data/ root;
# MotisBinaryManager runs `libmotis.so server -d data` from there (tzdata is
# read as ./tzdata relative to that same cwd — see android_seccomp_shim.h /
# the -DINSTALL=. build flag).
#
# Why internal and not external (getExternalFilesDir) storage: external
# storage is FUSE-backed, and under the real app sandbox that made the
# on-device MOTIS server (mmap-heavy custom binary format + LMDB), the
# mapsforge .map file, and line_schedules.sqlite take 20+ minutes of ~100%
# single-core CPU before answering a single request — reproduced end-to-end
# debugging this on a real device. From internal storage (or anywhere
# outside the real app sandbox, including `adb shell`) the exact same binary
# and data load and serve in under a second.
#
# Internal storage is private to the app's own UID — unlike external
# storage, `adb push` CANNOT write there at all without `adb root` (works on
# emulators and rooted/userdebug devices; fails outright on a real,
# non-rooted device). On a non-rooted real device, use the in-app "Import
# updated transit data" screen instead (DataImportActivity), which writes
# the files as the app itself and never needs root.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PACKAGE="iam699030.gmail.movitop"
LOCAL_DATA="${SCRIPT_DIR}/movitop_data/data"
LOCAL_TZDATA="${SCRIPT_DIR}/movitop_data/tzdata"
LOCAL_MAP="${SCRIPT_DIR}/movitop_data/israel.map"
REMOTE_BASE="/data/user/0/${PACKAGE}/files/motis_data"
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

# Internal storage needs root for every step below (mkdir/rm/push), not just
# the final chown — unlike the old external-storage path, there's no partial
# "wrong owner but files landed" outcome here, it just fails outright.
if ! "${ADB}" root >/dev/null 2>&1; then
  echo "ERROR: 'adb root' failed — this device/emulator can't be written to" >&2
  echo "  via adb at all on internal storage (expected on a real,"  >&2
  echo "  non-rooted device). Use the in-app 'Import updated transit data'"  >&2
  echo "  screen instead (DataImportActivity) — see package_motis_data.sh."  >&2
  exit 1
fi

APP_UID="$("${ADB}" shell pm list packages -U 2>/dev/null | grep "${PACKAGE}" | sed -n 's/.*uid://p' | tr -d '\r')"
if [[ -z "${APP_UID}" ]]; then
  echo "ERROR: couldn't resolve ${PACKAGE}'s uid — is it installed?" >&2
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

echo "[push_data] fixing ownership (pushed files land owned by root) ..."
"${ADB}" shell "chown -R ${APP_UID}:${APP_UID} '${REMOTE_BASE}'"

echo "[push_data] verifying map on device..."
"${ADB}" shell "ls -l '${REMOTE_MAP}' 2>/dev/null" | tr -d '\r' || true

echo "[push_data] done."
