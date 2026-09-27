#!/usr/bin/env python3
"""
Movitop — build a small offline SQLite DB of per-line departure times, for
the in-app "Line times" screen (search a line, see its schedule for a day).

Rather than querying MOTIS's routing API (not designed for "list every trip
of route X today") or parsing the ~1GB stop_times.txt on-device, this does a
one-time pass on the dev machine and ships a compact SQLite file
(movitop_data/data/line_schedules.sqlite) alongside the compiled graph.

Usage:
  python3 build_line_schedules.py [--gtfs movitop_data/israel-optimized.zip]
                                   [--output movitop_data/data/line_schedules.sqlite]
"""

from __future__ import annotations

import argparse
import sqlite3
import zipfile
from pathlib import Path

import pandas as pd


def build(gtfs_zip: Path, output_db: Path) -> None:
    if output_db.exists():
        output_db.unlink()
    output_db.parent.mkdir(parents=True, exist_ok=True)

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
        stops = pd.read_csv(z.open("stops.txt"), usecols=["stop_id", "stop_name"], dtype=str)
        stop_names = stops.set_index("stop_id")["stop_name"].to_dict()

        print("Reading trips.txt ...")
        trips = pd.read_csv(
            z.open("trips.txt"),
            usecols=["route_id", "service_id", "trip_id", "trip_headsign", "direction_id"],
            dtype=str,
        )

        print("Scanning stop_times.txt for each trip's first stop (chunked, ~1GB) ...")
        best: pd.DataFrame | None = None  # indexed by trip_id: stop_sequence, departure_time, stop_id
        chunk_iter = pd.read_csv(
            z.open("stop_times.txt"),
            usecols=["trip_id", "departure_time", "stop_id", "stop_sequence"],
            dtype={"trip_id": str, "departure_time": str, "stop_id": str, "stop_sequence": int},
            chunksize=3_000_000,
        )
        for i, chunk in enumerate(chunk_iter):
            chunk_best = chunk.loc[chunk.groupby("trip_id")["stop_sequence"].idxmin()]
            if best is None:
                combined = chunk_best
            else:
                combined = pd.concat([best, chunk_best], ignore_index=True)
            best = combined.loc[combined.groupby("trip_id")["stop_sequence"].idxmin()]
            print(f"  ...chunk {i + 1}, {len(best)} trips seen so far")
        best = best.set_index("trip_id")

        first_stop = {
            trip_id: (int(row.stop_sequence), row.departure_time, row.stop_id)
            for trip_id, row in best.iterrows()
        }

    print("Joining trips + routes + agency + first-stop departure times ...")
    trips = trips.merge(routes, on="route_id", how="left")
    trips = trips.merge(agency, on="agency_id", how="left")

    rows = []
    for t in trips.itertuples(index=False):
        fs = first_stop.get(t.trip_id)
        if fs is None:
            continue
        _, dep_time, stop_id = fs
        rows.append((
            t.route_id,
            t.route_short_name or "",
            t.route_long_name or "",
            t.agency_name or "",
            t.service_id,
            t.trip_id,
            t.trip_headsign or "",
            t.direction_id or "0",
            stop_names.get(stop_id, ""),
            dep_time,
        ))

    print(f"Writing {len(rows)} trip departures + {len(calendar)} calendar rows to {output_db} ...")
    conn = sqlite3.connect(output_db)
    conn.execute("""
        CREATE TABLE departures (
            route_id TEXT, route_short_name TEXT, route_long_name TEXT,
            agency_name TEXT, service_id TEXT, trip_id TEXT,
            headsign TEXT, direction_id TEXT,
            first_stop_name TEXT, departure_time TEXT
        )
    """)
    conn.executemany("INSERT INTO departures VALUES (?,?,?,?,?,?,?,?,?,?)", rows)
    conn.execute("CREATE INDEX idx_departures_route_short ON departures(route_short_name)")
    conn.execute("CREATE INDEX idx_departures_service ON departures(service_id)")

    conn.execute("""
        CREATE TABLE calendar (
            service_id TEXT, monday TEXT, tuesday TEXT, wednesday TEXT,
            thursday TEXT, friday TEXT, saturday TEXT, sunday TEXT,
            start_date TEXT, end_date TEXT
        )
    """)
    conn.executemany(
        "INSERT INTO calendar VALUES (?,?,?,?,?,?,?,?,?,?)",
        calendar[[
            "service_id", "monday", "tuesday", "wednesday", "thursday",
            "friday", "saturday", "sunday", "start_date", "end_date",
        ]].itertuples(index=False, name=None),
    )
    conn.execute("CREATE INDEX idx_calendar_service ON calendar(service_id)")
    conn.commit()
    conn.close()

    size_mb = output_db.stat().st_size / (1024 * 1024)
    print(f"Done: {output_db} ({size_mb:.1f} MB)")


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
