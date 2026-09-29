#!/usr/bin/env python3
"""Refreshes app/src/main/assets/ravkav_stations.tsv from the Israeli MOT's
open GTFS portal (https://gtfs.mot.gov.il/gtfsfiles/ChargingRavKav.zip) — one
CSV per operator listing where a Rav-Kav card can be loaded/reloaded.

Re-run this whenever that dataset is refreshed upstream; it's otherwise a
one-off, offline asset (same "bundled, not fetched at runtime" approach as the
MOTIS graph data — see DataImportManager.kt).

Row shape (tab-separated, 11 fields):
name  city  address  agency  phone  hours  cash  credit  accessible  lat  lon

hours = 7 "|"-joined values for Sun..Sat (each "HH:MM-HH:MM", or empty).
cash/credit/accessible = "1" or "0".
"""
from __future__ import annotations

import csv
import io
import sys
import urllib.request
import zipfile
from pathlib import Path

SOURCE_URL = "https://gtfs.mot.gov.il/gtfsfiles/ChargingRavKav.zip"
OUT_PATH = Path(__file__).parent / "app/src/main/assets/ravkav_stations.tsv"

DAY_COLS = [
    "sundayhours", "mondayhours", "tuesdayhours", "wednesdayhours",
    "thursdayhours", "fridayhours", "saturdayhours",
]

# Loose sanity bounds around Israel (incl. Eilat/Golan), to drop bad rows.
LAT_RANGE = (29.0, 33.5)
LON_RANGE = (34.0, 36.0)


def clean(s: str | None) -> str:
    return " ".join((s or "").split()).replace("\t", " ").replace("|", "/")


def as_bool(s: str | None) -> str:
    return "1" if (s or "").strip().upper() == "TRUE" else "0"


def parse_csv(data: bytes) -> list[list[str]]:
    reader = csv.DictReader(io.StringIO(data.decode("utf-8-sig")))
    fieldmap = {(k or "").strip().lower(): k for k in (reader.fieldnames or [])}

    def get(row: dict, key: str) -> str:
        col = fieldmap.get(key)
        return row.get(col, "") if col else ""

    rows = []
    for row in reader:
        lat_s, lon_s = get(row, "latitude").strip(), get(row, "longitude").strip()
        if not lat_s or not lon_s:
            continue
        try:
            lat, lon = float(lat_s), float(lon_s)
        except ValueError:
            continue
        if not (LAT_RANGE[0] <= lat <= LAT_RANGE[1] and LON_RANGE[0] <= lon <= LON_RANGE[1]):
            continue

        name = clean(get(row, "nameofstationheb")) or clean(get(row, "nameofstationeng"))
        city = clean(get(row, "cityheb")) or clean(get(row, "cityeng"))
        address = clean(get(row, "addressheb")) or clean(get(row, "addresseng"))
        place = clean(get(row, "placeheb")) or clean(get(row, "placeeng"))
        if place and place not in address:
            address = f"{address}, {place}" if address else place
        agency = clean(get(row, "agencyheb")) or clean(get(row, "agencyeng"))
        phone = clean(get(row, "phonenumber"))
        hours = "|".join(clean(get(row, d)) for d in DAY_COLS)
        cash = as_bool(get(row, "acceptcash"))
        credit = as_bool(get(row, "acceptcreditcard"))
        accessible = as_bool(get(row, "accessible"))

        rows.append([
            name, city, address, agency, phone, hours,
            cash, credit, accessible, f"{lat:.6f}", f"{lon:.6f}",
        ])
    return rows


def main() -> None:
    print(f"Downloading {SOURCE_URL} ...", file=sys.stderr)
    with urllib.request.urlopen(SOURCE_URL, timeout=60) as resp:
        archive = zipfile.ZipFile(io.BytesIO(resp.read()))

    rows_out: list[list[str]] = []
    seen: set[tuple[str, str, str]] = set()
    for name in sorted(archive.namelist()):
        if not name.lower().endswith(".csv"):
            continue
        for row in parse_csv(archive.read(name)):
            key = (row[0], row[9], row[10])  # name, lat, lon
            if key in seen:
                continue
            seen.add(key)
            rows_out.append(row)

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with OUT_PATH.open("w", encoding="utf-8", newline="\n") as out:
        for r in rows_out:
            out.write("\t".join(r) + "\n")

    print(f"Wrote {len(rows_out)} stations to {OUT_PATH}")


if __name__ == "__main__":
    main()
