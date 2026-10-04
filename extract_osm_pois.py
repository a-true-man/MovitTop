#!/usr/bin/env python3
"""Extracts named points of interest (restaurants, pharmacies, shops, banks,
etc.) from movitop_data/israel-and-palestine-latest.osm.pbf — the same OSM
extract setup_movitop_data.sh already downloads to build the Mapsforge
israel.map — into app/src/main/assets/osm_pois.tsv for the Android app's
"nearby" list and map markers.

No network access and no third-party dependencies (no pyosmium): PBF is a
sequence of length-prefixed Blob/BlobHeader protobuf messages, each holding a
zlib-compressed PrimitiveBlock. That's a small, fixed schema (osmformat.proto /
fileformat.proto), so this hand-rolls just enough of the protobuf wire format
to read it, the same "stdlib only, one-off dev script" convention as
build_line_schedules.py and fetch_ravkav_charging_stations.py.

Only tagged *nodes* are read (via DenseNodes, which is what every
osmium/osmconvert-produced extract — including Geofabrik's — uses). Areas
mapped only as ways/relations (e.g. a mall outline with no center node) are
missed; acceptable for a "nearby" list, which cares about point locations.

Row shape (tab-separated, 7 fields):
  name  category  subtype  address  hours  lat  lon

hours = 7 "|"-joined values for Sun..Sat (each "HH:MM-HH:MM", or empty when
the node's opening_hours tag is missing or doesn't match the small common
subset this parses — never guessed/fabricated).

Usage:
  python3 extract_osm_pois.py [--pbf movitop_data/israel-and-palestine-latest.osm.pbf]
                               [--output app/src/main/assets/osm_pois.tsv]
"""
from __future__ import annotations

import argparse
import struct
import sys
import zlib
from pathlib import Path

DEFAULT_PBF = Path(__file__).parent / "movitop_data/israel-and-palestine-latest.osm.pbf"
DEFAULT_OUT = Path(__file__).parent / "app/src/main/assets/osm_pois.tsv"

# Loose sanity bounds around Israel (incl. Eilat/Golan) — same as
# fetch_ravkav_charging_stations.py — to drop stray out-of-area nodes.
LAT_RANGE = (29.0, 33.5)
LON_RANGE = (34.0, 36.0)

DAY_CODES = ["Su", "Mo", "Tu", "We", "Th", "Fr", "Sa"]  # index 0..6, matches RavKav's hoursByDay order

# (osm_key, osm_value) -> (category, subtype label shown in the UI)
CATEGORY_MAP: dict[tuple[str, str], tuple[str, str]] = {}


def _register(category: str, key: str, values: list[str]) -> None:
    for v in values:
        CATEGORY_MAP[(key, v)] = (category, v)


_register("food_drink", "amenity", [
    "restaurant", "cafe", "fast_food", "bar", "pub", "ice_cream", "food_court",
])
_register("food_drink", "shop", ["bakery"])

_register("shopping", "shop", [
    "supermarket", "convenience", "mall", "department_store", "clothes",
    "shoes", "electronics", "books", "gift", "kiosk", "greengrocer",
    "butcher", "hardware", "furniture", "jewelry", "toys", "sports",
    "florist", "pet", "deli",
])

_register("health", "amenity", [
    "pharmacy", "hospital", "clinic", "dentist", "doctors", "veterinary",
])

_register("finance", "amenity", ["bank", "atm", "bureau_de_change"])

_register("leisure", "amenity", ["cinema", "theatre", "nightclub"])
_register("leisure", "leisure", [
    "park", "playground", "fitness_centre", "sports_centre", "swimming_pool",
])
_register("leisure", "tourism", ["attraction", "museum", "zoo", "viewpoint", "hotel", "guest_house"])

_register("other", "amenity", [
    "post_office", "police", "fuel", "library", "townhall", "marketplace",
    "fire_station", "toilets",
])


def classify(tags: dict[str, str]) -> tuple[str, str] | None:
    for key in ("amenity", "shop", "tourism", "leisure"):
        value = tags.get(key)
        if value and (key, value) in CATEGORY_MAP:
            return CATEGORY_MAP[(key, value)]
    return None


def parse_opening_hours(raw: str | None) -> list[str] | None:
    """Best-effort subset of the OSM opening_hours mini-language: semicolon-
    separated "<days> <HH:MM>-<HH:MM>" clauses (plus the "24/7" shortcut).
    Returns None (unknown — never guessed) for anything else: multiple time
    ranges in one clause, "off"/"closed", public-holiday rules, comments, etc.
    """
    if not raw:
        return None
    raw = raw.strip()
    if raw in ("24/7",):
        return ["00:00-24:00"] * 7

    hours = [""] * 7
    touched = False
    for clause in raw.split(";"):
        clause = clause.strip()
        if not clause:
            continue
        parts = clause.split()
        if len(parts) != 2:
            return None
        days_spec, time_spec = parts
        if "," in time_spec or time_spec.lower() in ("off", "closed"):
            return None
        if "-" not in time_spec:
            return None
        start, _, end = time_spec.partition("-")
        if not _is_hhmm(start) or not _is_hhmm(end):
            return None

        days = _expand_days(days_spec)
        if days is None:
            return None
        for d in days:
            hours[d] = f"{start}-{end}"
            touched = True

    return hours if touched else None


def _is_hhmm(s: str) -> bool:
    if len(s) != 5 or s[2] != ":":
        return False
    h, m = s[:2], s[3:]
    return h.isdigit() and m.isdigit() and 0 <= int(h) <= 24 and 0 <= int(m) < 60


def _expand_days(spec: str) -> list[int] | None:
    result: list[int] = []
    for token in spec.split(","):
        token = token.strip()
        if "-" in token:
            a, _, b = token.partition("-")
            if a not in DAY_CODES or b not in DAY_CODES:
                return None
            start, end = DAY_CODES.index(a), DAY_CODES.index(b)
            i = start
            while True:
                result.append(i)
                if i == end:
                    break
                i = (i + 1) % 7
        else:
            if token not in DAY_CODES:
                return None
            result.append(DAY_CODES.index(token))
    return result


# --------------------------------------------------------------------------
# Minimal protobuf wire-format reader (just enough for OSM PBF's fixed schema)
# --------------------------------------------------------------------------

def _read_varint(buf: bytes, pos: int) -> tuple[int, int]:
    result = 0
    shift = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, pos
        shift += 7


def _zigzag(n: int) -> int:
    return (n >> 1) ^ -(n & 1)


def _parse_fields(buf: bytes) -> dict[int, list]:
    """field_number -> list of raw values (int for varint/fixed32/fixed64, bytes for length-delimited)."""
    fields: dict[int, list] = {}
    pos, length = 0, len(buf)
    while pos < length:
        tag, pos = _read_varint(buf, pos)
        field_no, wire_type = tag >> 3, tag & 0x7
        if wire_type == 0:
            value, pos = _read_varint(buf, pos)
        elif wire_type == 2:
            size, pos = _read_varint(buf, pos)
            value = buf[pos:pos + size]
            pos += size
        elif wire_type == 1:
            value = buf[pos:pos + 8]
            pos += 8
        elif wire_type == 5:
            value = buf[pos:pos + 4]
            pos += 4
        else:
            raise ValueError(f"unsupported protobuf wire type {wire_type}")
        fields.setdefault(field_no, []).append(value)
    return fields


def _packed_varints(buf: bytes) -> list[int]:
    out = []
    pos, length = 0, len(buf)
    while pos < length:
        v, pos = _read_varint(buf, pos)
        out.append(v)
    return out


def _read_blobs(path: Path):
    with path.open("rb") as f:
        while True:
            header_len_raw = f.read(4)
            if len(header_len_raw) < 4:
                return
            header_len = struct.unpack(">I", header_len_raw)[0]
            header_fields = _parse_fields(f.read(header_len))
            blob_type = header_fields[1][0].decode("utf-8")
            data_size = header_fields[3][0]
            blob_bytes = f.read(data_size)
            yield blob_type, blob_bytes


def _decode_blob(blob_bytes: bytes) -> bytes:
    fields = _parse_fields(blob_bytes)
    if 1 in fields:  # raw, uncompressed
        return fields[1][0]
    if 3 in fields:  # zlib_data
        return zlib.decompress(fields[3][0])
    raise ValueError("unsupported Blob compression (only raw/zlib handled)")


def _iter_dense_node_tags(primitive_block: bytes):
    """Yields (lat, lon, tags) for every tagged node in one PrimitiveBlock."""
    fields = _parse_fields(primitive_block)
    stringtable_fields = _parse_fields(fields[1][0])
    strings = [b.decode("utf-8", "replace") for b in stringtable_fields.get(1, [])]

    granularity = fields.get(17, [100])[0]
    lat_offset = fields.get(19, [0])[0]
    lon_offset = fields.get(20, [0])[0]

    for group_bytes in fields.get(2, []):
        group_fields = _parse_fields(group_bytes)
        dense_list = group_fields.get(2)
        if not dense_list:
            continue
        dense_fields = _parse_fields(dense_list[0])
        ids = [_zigzag(v) for v in _packed_varints(dense_fields.get(1, [b""])[0])]
        lat_deltas = [_zigzag(v) for v in _packed_varints(dense_fields.get(8, [b""])[0])]
        lon_deltas = [_zigzag(v) for v in _packed_varints(dense_fields.get(9, [b""])[0])]
        kv_flat = _packed_varints(dense_fields.get(10, [b""])[0])

        kv_per_node: list[list[int]] = []
        current: list[int] = []
        for v in kv_flat:
            if v == 0:
                kv_per_node.append(current)
                current = []
            else:
                current.append(v)
        while len(kv_per_node) < len(ids):
            kv_per_node.append([])

        cur_lat = cur_lon = 0
        for i in range(len(ids)):
            cur_lat += lat_deltas[i]
            cur_lon += lon_deltas[i]
            kv = kv_per_node[i]
            if not kv:
                continue
            tags = {}
            for j in range(0, len(kv) - 1, 2):
                tags[strings[kv[j]]] = strings[kv[j + 1]]
            if not tags:
                continue
            lat = 1e-9 * (lat_offset + granularity * cur_lat)
            lon = 1e-9 * (lon_offset + granularity * cur_lon)
            yield lat, lon, tags


def _format_address(tags: dict[str, str]) -> str:
    parts = []
    street = tags.get("addr:street", "")
    house = tags.get("addr:housenumber", "")
    if street:
        parts.append(f"{street} {house}".strip())
    city = tags.get("addr:city", "")
    if city:
        parts.append(city)
    return ", ".join(parts)


def _clean(s: str) -> str:
    return " ".join(s.split()).replace("\t", " ")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pbf", type=Path, default=DEFAULT_PBF)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args()

    if not args.pbf.exists():
        print(f"ERROR: {args.pbf} not found — run setup_movitop_data.sh first.", file=sys.stderr)
        sys.exit(1)

    seen: set[tuple[str, str, str]] = set()
    rows_out: list[str] = []
    blocks_seen = 0

    for blob_type, blob_bytes in _read_blobs(args.pbf):
        if blob_type != "OSMData":
            continue
        primitive_block = _decode_blob(blob_bytes)
        blocks_seen += 1
        for lat, lon, tags in _iter_dense_node_tags(primitive_block):
            if not (LAT_RANGE[0] <= lat <= LAT_RANGE[1] and LON_RANGE[0] <= lon <= LON_RANGE[1]):
                continue
            name = tags.get("name:he") or tags.get("name")
            if not name:
                continue
            classified = classify(tags)
            if classified is None:
                continue
            category, subtype = classified

            key = (name, f"{lat:.6f}", f"{lon:.6f}")
            if key in seen:
                continue
            seen.add(key)

            address = _format_address(tags)
            hours = parse_opening_hours(tags.get("opening_hours")) or [""] * 7

            rows_out.append("\t".join([
                _clean(name), category, subtype, _clean(address),
                "|".join(hours), f"{lat:.6f}", f"{lon:.6f}",
            ]))

        if blocks_seen % 200 == 0:
            print(f"...{blocks_seen} blocks, {len(rows_out)} POIs so far", file=sys.stderr)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="\n") as out:
        for row in rows_out:
            out.write(row + "\n")

    print(f"Wrote {len(rows_out)} POIs to {args.output} (from {blocks_seen} data blocks)")


if __name__ == "__main__":
    main()
