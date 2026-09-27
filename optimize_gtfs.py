#!/usr/bin/env python3
"""
Movitop — selective GTFS shape stripping for Israel MOT feed.

Urban trips: clear shape_id so MOTIS can derive geometry from OSM (saves space).
Intercity / long-distance: keep shape_id and matching shapes.txt rows.
"""

from __future__ import annotations

import argparse
import math
import shutil
import sys
import zipfile
from pathlib import Path

import pandas as pd

# Israel MOT LineTypeDesc values (ClusterToLine.zip).
# Urban routes are stripped; intercity + regional routes keep their shapes.
LINE_TYPE_KEEP = frozenset(
    {"בינעירוני", "אזורי", "intercity", "regional"}
)
LINE_TYPE_URBAN = frozenset(
    {"עירוני", "urban"}
)

# route_type: 2 = rail (Israel Railways and similar long corridors).
RAIL_ROUTE_TYPES = frozenset({2})

# Agency name substrings that are primarily long-distance (still use distance fallback).
INTERCITY_AGENCY_KEYWORDS = (
    "רכבת",
    "railways",
    "rail",
    "נתיב",
    "nateev",
    "אפיקים",
    "afikim",
    "סופרבוס",  # many intercity coaches in periphery
    "superbus",
)

DEFAULT_INTERCITY_KM = 35.0


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6371.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = math.sin(dlat / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlon / 2) ** 2
    return 2 * r * math.asin(math.sqrt(min(1.0, a)))


def load_cluster_line_types(data_dir: Path) -> pd.Series | None:
    """OfficeLineId -> LineTypeDesc from optional MOT ClusterToLine.zip.

    The MOT cluster file links by ``OfficeLineId`` (the official line number),
    which matches the first segment of ``routes.route_desc`` (e.g. "67001-1-#").
    """
    cluster_zip = data_dir / "ClusterToLine.zip"
    if not cluster_zip.is_file():
        return None

    with zipfile.ZipFile(cluster_zip) as zf:
        names = [n for n in zf.namelist() if n.lower().endswith(".txt")]
        if not names:
            return None
        with zf.open(names[0]) as f:
            # index_col=False: MOT rows have a trailing comma; without this pandas
            # would treat the first column as an index and shift every field.
            df = pd.read_csv(f, dtype=str, index_col=False)

    line_col = next(
        (c for c in df.columns if c.lower() in ("officelineid", "office_line_id")),
        None,
    )
    type_col = next(
        (c for c in df.columns if c.lower() in ("linetypedesc", "line_type_desc")),
        None,
    )
    if line_col is None or type_col is None:
        return None

    out = df[[line_col, type_col]].dropna()
    out[line_col] = out[line_col].astype(str).str.strip()
    out[type_col] = out[type_col].astype(str).str.strip()
    out = out.drop_duplicates(subset=[line_col])
    return out.set_index(line_col)[type_col]


def office_line_ids(routes: pd.DataFrame) -> pd.Series:
    """route_id -> OfficeLineId parsed from routes.route_desc (first '-' segment)."""
    if "route_desc" not in routes.columns:
        return pd.Series(dtype=str)
    desc = routes["route_desc"].astype(str).str.strip()
    office = desc.str.split("-", n=1).str[0].str.strip()
    office = office.where(office.str.len() > 0)
    return pd.Series(office.values, index=routes["route_id"].astype(str))


def shape_lengths_km(shapes: pd.DataFrame) -> pd.Series:
    """Max path length per shape_id (km), using shape_dist_traveled or haversine."""
    if shapes.empty:
        return pd.Series(dtype=float)

    if "shape_dist_traveled" in shapes.columns:
        dist = pd.to_numeric(shapes["shape_dist_traveled"], errors="coerce")
        by_shape = shapes.assign(_d=dist).groupby("shape_id")["_d"].max()
        if by_shape.notna().any():
            return (by_shape / 1000.0).rename("length_km")

    shapes = shapes.sort_values(["shape_id", "shape_pt_sequence"], na_position="last")
    lengths: dict[str, float] = {}
    for shape_id, grp in shapes.groupby("shape_id", sort=False):
        total = 0.0
        prev = None
        for row in grp.itertuples(index=False):
            lat = float(row.shape_pt_lat)
            lon = float(row.shape_pt_lon)
            if prev is not None:
                total += haversine_km(prev[0], prev[1], lat, lon)
            prev = (lat, lon)
        lengths[str(shape_id)] = total
    return pd.Series(lengths, name="length_km")


def intercity_agency_ids(agency: pd.DataFrame) -> set[str]:
    if agency.empty or "agency_id" not in agency.columns:
        return set()
    name_col = "agency_name" if "agency_name" in agency.columns else None
    if name_col is None:
        return set()
    ids: set[str] = set()
    for row in agency.itertuples(index=False):
        name = str(getattr(row, name_col, "")).lower()
        if any(kw in name for kw in INTERCITY_AGENCY_KEYWORDS):
            ids.add(str(row.agency_id))
    return ids


def classify_routes(
    routes: pd.DataFrame,
    trips: pd.DataFrame,
    shapes: pd.DataFrame | None,
    agency: pd.DataFrame,
    cluster_types: pd.Series | None,
    intercity_km: float,
) -> pd.Series:
    routes = routes.copy()
    routes["route_id"] = routes["route_id"].astype(str)
    route_ids = routes["route_id"].unique()
    keep = pd.Series(False, index=route_ids, dtype=bool)

    # Routes positively classified by the MOT cluster file (urban vs intercity/regional)
    # are decided there; only unclassified routes fall back to the distance heuristic.
    classified = pd.Series(False, index=route_ids, dtype=bool)
    if cluster_types is not None:
        office = office_line_ids(routes)
        line_type = office.map(cluster_types)
        line_type = line_type.reindex(route_ids)
        keep |= line_type.isin(LINE_TYPE_KEEP).fillna(False)
        classified |= line_type.notna()

    # Rail is always long-distance -> always keep shapes.
    if "route_type" in routes.columns:
        rt = pd.to_numeric(routes.set_index("route_id")["route_type"], errors="coerce")
        keep |= rt.reindex(route_ids).isin(RAIL_ROUTE_TYPES)

    # Known long-distance operators -> keep.
    ic_agencies = intercity_agency_ids(agency)
    if ic_agencies and "agency_id" in routes.columns:
        agency_by_route = routes.set_index("route_id")["agency_id"].astype(str)
        keep |= agency_by_route.reindex(route_ids).isin(ic_agencies).fillna(False)

    # Distance fallback: only for routes the cluster file did not classify.
    trip_shapes = trips[["route_id", "shape_id"]].dropna().copy()
    trip_shapes["route_id"] = trip_shapes["route_id"].astype(str)
    trip_shapes["shape_id"] = trip_shapes["shape_id"].astype(str)

    shape_len = shape_lengths_km(shapes) if shapes is not None and not shapes.empty else pd.Series(dtype=float)
    if not shape_len.empty:
        for route_id, grp in trip_shapes.groupby("route_id"):
            if classified.get(route_id, False):
                continue
            lengths = shape_len.reindex(grp["shape_id"].unique()).dropna()
            if not lengths.empty and float(lengths.max()) >= intercity_km:
                keep.loc[route_id] = True

    return keep


def read_gtfs_table(extract_dir: Path, name: str) -> pd.DataFrame:
    path = extract_dir / name
    if not path.is_file():
        return pd.DataFrame()
    return pd.read_csv(path, dtype=str, low_memory=False)


def write_gtfs_table(df: pd.DataFrame, path: Path) -> None:
    df.to_csv(path, index=False, lineterminator="\n")


def optimize(
    input_zip: Path,
    output_zip: Path,
    data_dir: Path,
    intercity_km: float,
    work_dir: Path | None,
) -> None:
    if not input_zip.is_file():
        raise FileNotFoundError(f"GTFS zip not found: {input_zip}")

    tmp = work_dir or (data_dir / "_gtfs_extract_tmp")
    if tmp.exists():
        shutil.rmtree(tmp)
    tmp.mkdir(parents=True)

    print(f"Extracting {input_zip.name} ...")
    with zipfile.ZipFile(input_zip) as zf:
        zf.extractall(tmp)

    agency = read_gtfs_table(tmp, "agency.txt")
    routes = read_gtfs_table(tmp, "routes.txt")
    trips = read_gtfs_table(tmp, "trips.txt")
    shapes = read_gtfs_table(tmp, "shapes.txt")

    if trips.empty or routes.empty:
        raise RuntimeError("trips.txt or routes.txt missing from GTFS archive")

    cluster_types = load_cluster_line_types(data_dir)
    if cluster_types is not None:
        print(f"Using ClusterToLine metadata ({len(cluster_types)} routes).")
    else:
        print(
            "ClusterToLine.zip not found — using route_type, agency keywords, "
            f"and shape length (>={intercity_km} km) only."
        )

    keep_route = classify_routes(
        routes, trips, shapes if not shapes.empty else None, agency, cluster_types, intercity_km
    )

    trips = trips.copy()
    trips["route_id"] = trips["route_id"].astype(str)
    had_shape = trips["shape_id"].notna() & (trips["shape_id"].astype(str).str.len() > 0)

    def route_keeps_shape(route_id: str) -> bool:
        return bool(keep_route.get(str(route_id), False))

    strip_mask = had_shape & ~trips["route_id"].map(route_keeps_shape)
    stripped_trips = int(strip_mask.sum())
    trips.loc[strip_mask, "shape_id"] = pd.NA

    kept_shape_ids: set[str] = set()
    if "shape_id" in trips.columns:
        kept = trips["shape_id"].dropna().astype(str)
        kept = kept[kept.str.len() > 0]
        kept_shape_ids = set(kept.unique())

    if not shapes.empty:
        before_shapes = len(shapes)
        shapes = shapes[shapes["shape_id"].astype(str).isin(kept_shape_ids)].copy()
        print(f"shapes.txt rows: {before_shapes} -> {len(shapes)}")
        if shapes.empty:
            (tmp / "shapes.txt").unlink(missing_ok=True)
        else:
            write_gtfs_table(shapes, tmp / "shapes.txt")
    elif (tmp / "shapes.txt").is_file():
        (tmp / "shapes.txt").unlink(missing_ok=True)

    write_gtfs_table(trips, tmp / "trips.txt")

    intercity_routes = int(keep_route.sum())
    print(f"Routes keeping shapes: {intercity_routes} / {len(keep_route)}")
    print(f"Trips stripped of shape_id: {stripped_trips}")

    print(f"Writing {output_zip} ...")
    if output_zip.exists():
        output_zip.unlink()
    with zipfile.ZipFile(output_zip, "w", compression=zipfile.ZIP_DEFLATED) as zout:
        for file_path in sorted(tmp.rglob("*")):
            if file_path.is_file():
                zout.write(file_path, file_path.relative_to(tmp).as_posix())

    shutil.rmtree(tmp)
    in_mb = input_zip.stat().st_size / (1024 * 1024)
    out_mb = output_zip.stat().st_size / (1024 * 1024)
    print(f"Size: {in_mb:.1f} MB -> {out_mb:.1f} MB ({100 * out_mb / in_mb:.0f}% of original)")


def main() -> int:
    script_dir = Path(__file__).resolve().parent
    default_data = script_dir / "movitop_data"

    parser = argparse.ArgumentParser(description="Movitop selective GTFS shape optimizer")
    parser.add_argument(
        "--data-dir",
        type=Path,
        default=default_data,
        help="Directory containing israel-public-transportation.zip (default: ./movitop_data)",
    )
    parser.add_argument(
        "--input",
        type=Path,
        default=None,
        help="Input GTFS zip (default: <data-dir>/israel-public-transportation.zip)",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=None,
        help="Output GTFS zip (default: <data-dir>/israel-optimized.zip)",
    )
    parser.add_argument(
        "--intercity-km",
        type=float,
        default=DEFAULT_INTERCITY_KM,
        help=f"Minimum shape length (km) to keep shapes (default: {DEFAULT_INTERCITY_KM})",
    )
    args = parser.parse_args()

    data_dir = args.data_dir.resolve()
    input_zip = (args.input or data_dir / "israel-public-transportation.zip").resolve()
    output_zip = (args.output or data_dir / "israel-optimized.zip").resolve()

    try:
        optimize(input_zip, output_zip, data_dir, args.intercity_km, work_dir=None)
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
