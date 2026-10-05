#!/usr/bin/env python3
"""
Movitop — build a small offline SQLite DB of per-line departure times, for
the in-app "Line times" screen (search a line, see its schedule for a day,
switch direction, drill into one trip's full stop-by-stop arrival times).

Rather than querying MOTIS's routing API (not designed for "list every trip
of route X today") or parsing the ~1GB stop_times.txt on-device, this does a
one-time pass on the dev machine and ships a compact SQLite file
(movitop_data/data/line_schedules.sqlite) alongside the compiled graph.

Schema is normalized: route-level fields (short/long name, agency, terminal
stop names, direction pairing) live once per route_id in `routes`, not once
per trip in `departures` — route_short_name/route_long_name/agency_name were
previously duplicated across every one of a route's trips even though only a
few thousand distinct routes exist nationwide. Stop times are stored as
INTEGER seconds-since-midnight instead of "HH:MM:SS" TEXT (GTFS-style, so
still >24:00:00 for past-midnight trips — LineScheduleRepository.kt converts
back to that same text format before returning to callers, so nothing above
the DB layer changes). Together these cut the file from ~865MB to well under
200MB with the exact same query results.

Route short names are reused across completely unrelated lines in Israel's
national GTFS (e.g. "133" covers five distinct lines run by different
agencies in different cities), so this also pairs up each route's two
directions (same short name + agency, endpoints reversed) into a
line_group_id/paired_route_id, so the app can merge a line's two directions
into one card without accidentally merging unrelated same-numbered lines.

Usage:
  python3 build_line_schedules.py [--gtfs movitop_data/israel-optimized.zip]
                                   [--output movitop_data/data/line_schedules.sqlite]
"""

from __future__ import annotations

import argparse
import math
import sqlite3
import zipfile
from pathlib import Path

import pandas as pd

# A line's two directions rarely share an exact stop_id at their endpoints —
# this feed usually splits a terminal into a boarding-platform stop_id and a
# drop-off stop_id a block or two apart. Verified against several real pairs
# in the feed (e.g. Haifa's Hof HaCarmel/Hamifratz terminals, Modi'in
# Illit<->Beitar Illit): those are 50-350m apart, well under this.
SAME_TERMINAL_KM = 0.5


def haversine_km(a: tuple[float, float], b: tuple[float, float]) -> float:
    lat1, lon1, lat2, lon2 = map(math.radians, [a[0], a[1], b[0], b[1]])
    dlat, dlon = lat2 - lat1, lon2 - lon1
    h = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 2 * 6371 * math.asin(math.sqrt(h))


def terminals_distance_km(
    stop_id_a: int, stop_id_b: int, stop_coords: dict[int, tuple[float, float]]
) -> float | None:
    a, b = stop_coords.get(stop_id_a), stop_coords.get(stop_id_b)
    return haversine_km(a, b) if a is not None and b is not None else None


def gtfs_time_to_seconds(t: str | float | None) -> int | None:
    """"HH:MM:SS" (hour may exceed 23 for past-midnight trips) -> total seconds."""
    if t is None or (isinstance(t, float) and math.isnan(t)):
        return None
    parts = str(t).split(":")
    if len(parts) != 3:
        return None
    try:
        h, m, s = (int(p) for p in parts)
    except ValueError:
        return None
    return h * 3600 + m * 60 + s


def gtfs_time_series_to_seconds(series: pd.Series) -> pd.Series:
    """Vectorized version of [gtfs_time_to_seconds] for the ~18M-row stop_times pass."""
    parts = series.fillna("00:00:00").str.split(":", n=2, expand=True)
    h = pd.to_numeric(parts[0], errors="coerce").fillna(0).astype("int64")
    m = pd.to_numeric(parts[1], errors="coerce").fillna(0).astype("int64")
    s = pd.to_numeric(parts[2], errors="coerce").fillna(0).astype("int64")
    return h * 3600 + m * 60 + s


def build(gtfs_zip: Path, output_db: Path) -> None:
    if output_db.exists():
        output_db.unlink()
    output_db.parent.mkdir(parents=True, exist_ok=True)

    conn = sqlite3.connect(output_db)
    # Throwaway file rebuilt from scratch every run — trade durability for
    # build speed across the ~18M-row stop_times insert below.
    conn.execute("PRAGMA synchronous=OFF")
    conn.execute("PRAGMA journal_mode=MEMORY")
    # Must be set before any table is created. Measured 1024/4096/8192/16384
    # on this same data: SQLite's own default (4096) was the *worst* of the
    # four (393.7MB) — 8192 measured 342.4MB, matching 16384/32768 almost
    # exactly, so it's the smallest page size that still gets the full
    # benefit (larger pages amortize the per-page b-tree header over more of
    # stop_times' 17.8M narrow rows) without going larger than needed for a
    # table that's mostly read as single-row point lookups on-device.
    conn.execute("PRAGMA page_size=8192")
    create_schema(conn)

    with zipfile.ZipFile(gtfs_zip) as z:
        print("Reading routes.txt ...")
        routes = pd.read_csv(
            z.open("routes.txt"),
            usecols=["route_id", "route_short_name", "route_long_name", "agency_id"],
            dtype=str,
        )

        print("Reading agency.txt ...")
        agency = pd.read_csv(z.open("agency.txt"), usecols=["agency_id", "agency_name"], dtype=str)

        print("Reading calendar.txt ...")
        calendar = pd.read_csv(z.open("calendar.txt"), dtype=str)

        print("Reading stops.txt ...")
        stops = pd.read_csv(
            z.open("stops.txt"), usecols=["stop_id", "stop_name", "stop_lat", "stop_lon"], dtype=str
        )
        stops["stop_id"] = stops["stop_id"].astype(int)
        stops["stop_lat"] = stops["stop_lat"].astype(float)
        stops["stop_lon"] = stops["stop_lon"].astype(float)
        stop_names = stops.set_index("stop_id")["stop_name"].to_dict()
        # Same physical terminal is often split across several stop_ids in
        # this feed (a boarding platform vs. a drop-off stop_id, ~50-100m
        # apart) — pairing a line's two directions below matches by distance
        # between endpoints, not stop_id equality, because of this.
        stop_coords = {
            row.stop_id: (row.stop_lat, row.stop_lon) for row in stops.itertuples(index=False)
        }

        print("Reading trips.txt ...")
        trips = pd.read_csv(
            z.open("trips.txt"),
            usecols=["route_id", "service_id", "trip_id", "trip_headsign", "direction_id"],
            dtype=str,
        )
        # Fixes each trip's departures.id up front, from trips.txt alone, so
        # the single stop_times.txt pass below can insert with the right FK
        # without waiting for the departures rows themselves to be built.
        trip_id_to_row_id = {tid: i for i, tid in enumerate(trips["trip_id"])}

        print("Scanning stop_times.txt: inserting every stop + finding each trip's first/last stop (chunked, ~1GB) ...")
        first_stop: dict[str, tuple[int, str, int]] = {}  # trip_id -> (seq, time, stop_id)
        last_stop: dict[str, tuple[int, str, int]] = {}
        chunk_iter = pd.read_csv(
            z.open("stop_times.txt"),
            usecols=["trip_id", "arrival_time", "departure_time", "stop_id", "stop_sequence"],
            dtype={
                "trip_id": str, "arrival_time": str, "departure_time": str,
                "stop_id": int, "stop_sequence": int,
            },
            chunksize=3_000_000,
        )
        insert_stop_times = """
            INSERT INTO stop_times (trip_row_id, stop_sequence, stop_id, arrival_time)
            VALUES (?, ?, ?, ?)
        """
        for i, chunk in enumerate(chunk_iter):
            chunk["arrival_time"] = chunk["arrival_time"].fillna(chunk["departure_time"])

            # first/last-stop dicts keep the raw "HH:MM:SS" text (only a
            # handful of scalar conversions happen later, in the departures
            # pass) — the seconds conversion below is only for the bulk
            # per-row stop_times insert, where it has to be vectorized to
            # keep up with ~18M rows.
            chunk_first = chunk.loc[chunk.groupby("trip_id")["stop_sequence"].idxmin()]
            chunk_last = chunk.loc[chunk.groupby("trip_id")["stop_sequence"].idxmax()]
            for row in chunk_first.itertuples(index=False):
                prev = first_stop.get(row.trip_id)
                if prev is None or row.stop_sequence < prev[0]:
                    first_stop[row.trip_id] = (row.stop_sequence, row.departure_time, row.stop_id)
            for row in chunk_last.itertuples(index=False):
                prev = last_stop.get(row.trip_id)
                if prev is None or row.stop_sequence > prev[0]:
                    last_stop[row.trip_id] = (row.stop_sequence, row.arrival_time, row.stop_id)

            chunk["trip_row_id"] = chunk["trip_id"].map(trip_id_to_row_id)
            chunk["arrival_seconds"] = gtfs_time_series_to_seconds(chunk["arrival_time"])
            mapped = chunk.dropna(subset=["trip_row_id"])
            conn.executemany(
                insert_stop_times,
                mapped[["trip_row_id", "stop_sequence", "stop_id", "arrival_seconds"]].itertuples(index=False, name=None),
            )
            conn.commit()
            print(f"  ...chunk {i + 1}, {len(first_stop)} trips seen so far")

    print("Joining trips + routes + agency ...")
    trips = trips.merge(routes, on="route_id", how="left")
    trips = trips.merge(agency, on="agency_id", how="left")
    trips["first_stop_id"] = trips["trip_id"].map(lambda t: first_stop[t][2] if t in first_stop else None)
    trips["last_stop_id"] = trips["trip_id"].map(lambda t: last_stop[t][2] if t in last_stop else None)

    print("Resolving each route's canonical terminals ...")
    with_terminals = trips.dropna(subset=["first_stop_id", "last_stop_id"])
    terminal_counts = (
        with_terminals.groupby(["route_id", "first_stop_id", "last_stop_id"])
        .size()
        .reset_index(name="n")
        # Tie-break on the terminal ids themselves (not just "n") and sort
        # with a stable algorithm: plain sort_values("n") is quicksort by
        # default, so a route_id with two terminal pairs tied on trip count
        # could keep either one across rebuilds — silently reassigning which
        # route_id represents a merged line/its terminal names run to run.
        .sort_values(["n", "first_stop_id", "last_stop_id"], ascending=[False, True, True], kind="mergesort")
        .drop_duplicates("route_id")
        .set_index("route_id")
    )
    route_meta = routes.drop_duplicates("route_id").merge(agency, on="agency_id", how="left").set_index("route_id")

    print("Pairing each line's two directions ...")
    line_group_id: dict[str, str] = {}
    paired_route_id: dict[str, str] = {}
    by_short_agency: dict[tuple[str, str], list[str]] = {}
    for route_id in terminal_counts.index:
        meta = route_meta.loc[route_id] if route_id in route_meta.index else None
        short_name = (meta["route_short_name"] if meta is not None else "") or ""
        agency_name = (meta["agency_name"] if meta is not None else "") or ""
        by_short_agency.setdefault((short_name, agency_name), []).append(route_id)

    for candidates in by_short_agency.values():
        unmatched = set(candidates)
        for route_id in candidates:
            if route_id not in unmatched:
                continue
            first_a, last_a = terminal_counts.loc[route_id, ["first_stop_id", "last_stop_id"]]
            best_match: str | None = None
            best_distance = None
            for other_id in candidates:
                if other_id == route_id or other_id not in unmatched:
                    continue
                first_b, last_b = terminal_counts.loc[other_id, ["first_stop_id", "last_stop_id"]]
                distance = terminals_distance_km(first_a, last_b, stop_coords) + \
                    terminals_distance_km(last_a, first_b, stop_coords)
                if distance is not None and distance <= 2 * SAME_TERMINAL_KM:
                    if best_distance is None or distance < best_distance:
                        best_match, best_distance = other_id, distance
            if best_match is not None:
                group = min(route_id, best_match)
                line_group_id[route_id] = group
                line_group_id[best_match] = group
                paired_route_id[route_id] = best_match
                paired_route_id[best_match] = route_id
                unmatched.discard(route_id)
                unmatched.discard(best_match)
    for route_id in terminal_counts.index:
        line_group_id.setdefault(route_id, route_id)

    print(f"Writing {len(terminal_counts)} routes ...")
    route_rows = []
    for route_id in terminal_counts.index:
        meta = route_meta.loc[route_id] if route_id in route_meta.index else None
        first_id, last_id = terminal_counts.loc[route_id, ["first_stop_id", "last_stop_id"]]
        route_rows.append((
            route_id,
            (meta["route_short_name"] if meta is not None else "") or "",
            (meta["route_long_name"] if meta is not None else "") or "",
            (meta["agency_name"] if meta is not None else "") or "",
            int(first_id),
            int(last_id),
            line_group_id[route_id],
            paired_route_id.get(route_id),
        ))
    conn.executemany("INSERT INTO routes VALUES (?,?,?,?,?,?,?,?)", route_rows)
    conn.execute("CREATE INDEX idx_routes_line_group ON routes(line_group_id)")

    print("Building departures rows ...")
    dep_rows = []
    for t in trips.itertuples(index=False):
        if t.trip_id not in first_stop or t.route_id not in terminal_counts.index:
            continue
        _, dep_time, _ = first_stop[t.trip_id]
        try:
            direction_id = int(t.direction_id) if t.direction_id not in (None, "") else 0
        except ValueError:
            direction_id = 0
        dep_rows.append((
            trip_id_to_row_id[t.trip_id],
            t.route_id,
            t.service_id,
            t.trip_id,
            t.trip_headsign or "",
            direction_id,
            gtfs_time_to_seconds(dep_time) or 0,
        ))

    print(f"Writing {len(dep_rows)} trip departures + {len(calendar)} calendar rows to {output_db} ...")
    conn.executemany(
        "INSERT INTO departures VALUES (?,?,?,?,?,?,?)", dep_rows
    )
    conn.execute("CREATE INDEX idx_departures_route_id ON departures(route_id)")
    conn.execute("CREATE INDEX idx_departures_trip_id ON departures(trip_id)")

    conn.executemany(
        "INSERT INTO stops VALUES (?, ?)", stop_names.items()
    )

    calendar_days = calendar[["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]].apply(
        lambda col: pd.to_numeric(col, errors="coerce").fillna(0).astype(int)
    )
    conn.executemany(
        "INSERT INTO calendar VALUES (?,?,?,?,?,?,?,?,?,?)",
        pd.concat(
            [calendar[["service_id"]], calendar_days, calendar[["start_date", "end_date"]]], axis=1
        ).itertuples(index=False, name=None),
    )
    conn.commit()

    print("Vacuuming (repacks pages left ~10% underfilled by the incremental build) ...")
    conn.execute("VACUUM")
    conn.close()

    size_mb = output_db.stat().st_size / (1024 * 1024)
    print(f"Done: {output_db} ({size_mb:.1f} MB)")


def create_schema(conn: sqlite3.Connection) -> None:
    # Route-level fields (name/agency/terminals/direction-pairing) live once
    # per route here instead of being repeated on every one of that route's
    # trip rows in `departures` — see module docstring.
    conn.execute("""
        CREATE TABLE routes (
            route_id TEXT PRIMARY KEY,
            route_short_name TEXT, route_long_name TEXT, agency_name TEXT,
            first_stop_id INTEGER, last_stop_id INTEGER,
            line_group_id TEXT, paired_route_id TEXT
        )
    """)
    conn.execute("CREATE TABLE stops (stop_id INTEGER PRIMARY KEY, stop_name TEXT)")
    conn.execute("""
        CREATE TABLE departures (
            id INTEGER PRIMARY KEY,
            route_id TEXT, service_id TEXT, trip_id TEXT,
            headsign TEXT, direction_id INTEGER, departure_time INTEGER
        )
    """)
    # WITHOUT ROWID + a composite key clusters the table itself in
    # (trip_row_id, stop_sequence) order instead of keeping a separate
    # ~260MB secondary index for that same lookup — this table alone was
    # ~630MB of the old ~865MB file (17.8M rows), so it's worth the less
    # common table form. Safe here because that pair is verified unique
    # per trip in the source feed (checked against the live GTFS export).
    conn.execute("""
        CREATE TABLE stop_times (
            trip_row_id INTEGER NOT NULL, stop_sequence INTEGER NOT NULL,
            stop_id INTEGER, arrival_time INTEGER,
            PRIMARY KEY (trip_row_id, stop_sequence)
        ) WITHOUT ROWID
    """)
    conn.execute("""
        CREATE TABLE calendar (
            service_id TEXT PRIMARY KEY, monday INTEGER, tuesday INTEGER, wednesday INTEGER,
            thursday INTEGER, friday INTEGER, saturday INTEGER, sunday INTEGER,
            start_date TEXT, end_date TEXT
        )
    """)
    conn.commit()


def main() -> int:
    script_dir = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description="Build the offline line-schedules SQLite DB")
    parser.add_argument("--gtfs", type=Path, default=script_dir / "movitop_data" / "israel-optimized.zip")
    parser.add_argument(
        "--output", type=Path, default=script_dir / "movitop_data" / "data" / "line_schedules.sqlite"
    )
    args = parser.parse_args()
    build(args.gtfs, args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
