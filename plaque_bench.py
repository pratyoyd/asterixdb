#!/usr/bin/env python3
"""
plaque_bench.py — Benchmark helper for PLAQUE filter optimization on AsterixDB.

Runs SELECT MAX(col) (or MIN) with and without PLAQUE enabled, collects timings
and profiler breakdowns (scan, aggregate, plaque-filter), saves full profiler
JSON per run, and displays per-run results plus rolling median from run 2..N.

Usage:
    # Basic: 5 runs of MAX(l_shipdate) on SF=10
    python3 plaque_bench.py --sf 10 --runs 5

    # With a
     filter predicate
    python3 plaque_bench.py --sf 10 --runs 5 --where "l_orderkey < 10000"

    # MIN instead of MAX
    python3 plaque_bench.py --sf 10 --runs 5 --agg min

    # Custom column
    python3 plaque_bench.py --sf 10 --runs 5 --col l_extendedprice

    # Custom AsterixDB endpoint
    python3 plaque_bench.py --sf 10 --runs 5 --url http://myhost:19002/query/service

    # Custom output directory for profiler JSON files
    python3 plaque_bench.py --sf 10 --runs 5 --out-dir ./plaque_results

Output files:
    Profiler JSON files are saved to <out-dir>/ (default: ./plaque_profiles_<timestamp>/):
        plaque_normal_sf10_run1_<timestamp>.json
        plaque_plaque_sf10_run1_<timestamp>.json
        ...

Tuple-count placeholders:
    The script reserves columns for "tuples at local aggregate" in its output.
    In a future milestone, these counts will be read from signal files written
    by the PLAQUE runtime. For now, they display as "-" (not yet wired).

    When ready, the counts will be read from files matching:
        <tuple-count-dir>/plaque_local_agg_count_<mode>_<timestamp>_run<N>.txt
    where <timestamp> is the program-start timestamp (YYYYMMDD_HHMMSS),
    shared across all runs in one invocation. The file is expected to contain
    a single integer (the number of tuples that reached the local aggregate).

    To enable, uncomment the read_tuple_count() body and pass --tuple-count-dir.
"""

import argparse
import json
import statistics
import sys
import time
import os
from datetime import datetime

try:
    import requests
except ImportError:
    print("ERROR: 'requests' package not found. Install with: pip3 install requests", file=sys.stderr)
    sys.exit(1)


# ---------------------------------------------------------------------------
# AsterixDB query execution
# ---------------------------------------------------------------------------

def run_query(url, statement, timeout_s=300):
    """POST a SQL++ statement to AsterixDB and return (elapsed_seconds, response_json).

    Sends with profile=timings and optimized-logical-plan=true so the response
    contains operator-level profiling data.
    """
    payload = {
        "statement": statement,
        "optimized-logical-plan": "true",
        "profile": "timings",
    }
    t0 = time.monotonic()
    resp = requests.post(url, json=payload, timeout=timeout_s)
    elapsed = time.monotonic() - t0
    resp.raise_for_status()
    return elapsed, resp.json()


# ---------------------------------------------------------------------------
# Profiler parsing
# ---------------------------------------------------------------------------

# Operator name fragments used to identify interesting operators in the
# profiler counters.  These are matched case-insensitively against the
# counter "name" field.  Adjust if AsterixDB renames operators.
SCAN_KEYWORDS = ["data scan", "datasource scan", "index search"]
AGG_KEYWORDS = ["aggregate", "assign ["]  # local agg shows as MicroOp assign with agg
PLAQUE_KEYWORDS = ["plaque-filter", "plaque_filter", "plaque filter"]

# For a simple scalar MAX/MIN query, the profile has a pipeline like:
#   Empty Tuple Source -> Data Scan -> [stream-project] -> [plaque-filter] -> aggregate -> ...
# The "run-time" in counters is in milliseconds.


def extract_operator_times(response_json):
    """Extract scan, aggregate, and plaque-filter times from profiler output.

    Scans all tasks across all joblets and sums run-times per operator category
    across partitions.

    Returns dict with keys: scan_ms, agg_ms, plaque_ms (each float, 0.0 if absent).
    Also returns a list of all (name, run_time_ms) pairs for debugging.
    """
    profile = response_json.get("profile")
    if not profile:
        return {"scan_ms": 0.0, "agg_ms": 0.0, "plaque_ms": 0.0}, []

    scan_ms = 0.0
    agg_ms = 0.0
    plaque_ms = 0.0
    all_ops = []

    for joblet in profile.get("joblets", []):
        for task in joblet.get("tasks", []):
            for counter in task.get("counters", []):
                name = counter.get("name", "")
                rt = counter.get("run-time", 0.0)
                all_ops.append((name, rt))

                name_lower = name.lower()
                if any(kw in name_lower for kw in PLAQUE_KEYWORDS):
                    plaque_ms += rt
                elif any(kw in name_lower for kw in SCAN_KEYWORDS):
                    scan_ms += rt
                elif "aggregate" in name_lower:
                    agg_ms += rt

    return {"scan_ms": scan_ms, "agg_ms": agg_ms, "plaque_ms": plaque_ms}, all_ops


# ---------------------------------------------------------------------------
# Save profiler output
# ---------------------------------------------------------------------------

def save_profile_json(response_json, out_dir, mode, sf, run_index, timestamp):
    """Save the full response JSON to a file for later analysis."""
    os.makedirs(out_dir, exist_ok=True)
    filename = f"plaque_{mode}_sf{sf}_run{run_index + 1}_{timestamp}.json"
    filepath = os.path.join(out_dir, filename)
    with open(filepath, "w") as f:
        json.dump(response_json, f, indent=2)
    return filepath


# ---------------------------------------------------------------------------
# Query builders
# ---------------------------------------------------------------------------

def build_query(agg, col, sf, where_clause=None):
    """Build a plain (non-PLAQUE) aggregate query."""
    q = f"SELECT {agg.upper()}({col}) FROM Lineitem_{sf}"
    if where_clause:
        q += f" WHERE {where_clause}"
    q += ";"
    return q


def build_plaque_query(agg, col, sf, where_clause=None):
    """Build the same query with PLAQUE enabled via SET."""
    inner = build_query(agg, col, sf, where_clause)
    # Remove trailing semicolon from inner, wrap with SET prefix
    inner_no_semi = inner.rstrip(";")
    return f'SET `compiler.plaque.enabled` "true"; {inner_no_semi};'


# ---------------------------------------------------------------------------
# Tuple-count placeholder
# ---------------------------------------------------------------------------

# PLACEHOLDER: Read the number of tuples that reached the local aggregate.
# In PLAQUE mode, the runtime will write this count to a signal file.
# When wired, this function reads from:
#   <tuple_count_dir>/plaque_local_agg_count_<mode>_<timestamp>_run<N>.txt
# The file contains a single integer.
#
# To enable:
#   1. Uncomment the function body below.
#   2. Pass --tuple-count-dir /path/to/signal/dir on the command line.
#   3. Ensure the PLAQUE runtime writes the file before this script reads it.

def read_tuple_count(tuple_count_dir, timestamp, run_index, mode):
    """
    Read tuple count from a signal file.

    Args:
        tuple_count_dir: directory where count files are written
        timestamp: program-start timestamp (YYYYMMDD_HHMMSS), same for all runs
        run_index: 0-based run number
        mode: "normal" or "plaque"

    Returns:
        int or None (None means not available)
    """
    # --- PLACEHOLDER: uncomment when PLAQUE runtime writes count files ---
    # if tuple_count_dir is None:
    #     return None
    # filename = f"plaque_local_agg_count_{mode}_{timestamp}_run{run_index + 1}.txt"
    # filepath = os.path.join(tuple_count_dir, filename)
    # try:
    #     with open(filepath, "r") as f:
    #         return int(f.read().strip())
    # except (FileNotFoundError, ValueError):
    #     return None
    return None


# ---------------------------------------------------------------------------
# Display helpers
# ---------------------------------------------------------------------------

def fmt_time(seconds):
    """Format seconds to 4 decimal places."""
    return f"{seconds:.4f}s"


def fmt_ms(ms):
    """Format milliseconds to 1 decimal place."""
    return f"{ms:.1f}ms"


def fmt_count(count):
    """Format tuple count or placeholder."""
    if count is None:
        return "-"
    return f"{count:,}"


def print_header():
    print(f"\n{'Run':>4}  {'Normal Time':>13}  {'Normal Tuples':>15}  "
          f"{'Plaque Time':>13}  {'Plaque Tuples':>15}  {'Speedup':>8}")
    print("-" * 82)


def print_row(run_num, normal_time, normal_count, plaque_time, plaque_count):
    speedup = normal_time / plaque_time if plaque_time > 0 else float('inf')
    print(f"{run_num:>4}  {fmt_time(normal_time):>13}  {fmt_count(normal_count):>15}  "
          f"{fmt_time(plaque_time):>13}  {fmt_count(plaque_count):>15}  {speedup:>7.2f}x")


def print_operator_breakdown(label, op_times):
    """Print scan / agg / plaque-filter times for one run."""
    parts = []
    parts.append(f"scan={fmt_ms(op_times['scan_ms'])}")
    parts.append(f"agg={fmt_ms(op_times['agg_ms'])}")
    if op_times['plaque_ms'] > 0:
        parts.append(f"plaque={fmt_ms(op_times['plaque_ms'])}")
    print(f"       {label}: {', '.join(parts)}")


def print_medians(normal_times, plaque_times):
    """Print rolling medians from run 2..N."""
    print(f"\n{'Window':>10}  {'Median Normal':>14}  {'Median Plaque':>14}  {'Median Speedup':>15}")
    print("-" * 60)
    for end in range(2, len(normal_times) + 1):
        # Median over runs 2..end (skip first run as warmup)
        n_slice = normal_times[1:end]
        p_slice = plaque_times[1:end]
        if not n_slice:
            continue
        med_n = statistics.median(n_slice)
        med_p = statistics.median(p_slice)
        speedup = med_n / med_p if med_p > 0 else float('inf')
        label = f"runs 2-{end}"
        print(f"{label:>10}  {fmt_time(med_n):>14}  {fmt_time(med_p):>14}  {speedup:>14.2f}x")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Benchmark PLAQUE filter optimization on AsterixDB",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__
    )
    parser.add_argument("--sf", type=int, required=True,
                        help="Scale factor (table suffix, e.g. 10 for Lineitem_10)")
    parser.add_argument("--runs", type=int, default=5,
                        help="Number of runs (default: 5)")
    parser.add_argument("--agg", choices=["max", "min"], default="max",
                        help="Aggregate function (default: max)")
    parser.add_argument("--col", default="l_shipdate",
                        help="Column to aggregate (default: l_shipdate)")
    parser.add_argument("--where", default=None, dest="where_clause",
                        help='Optional WHERE clause, e.g. "l_orderkey < 10000"')
    parser.add_argument("--url", default="http://localhost:19002/query/service",
                        help="AsterixDB query endpoint")
    parser.add_argument("--timeout", type=int, default=300,
                        help="Per-query timeout in seconds (default: 300)")
    parser.add_argument("--out-dir", default=None,
                        help="Directory for profiler JSON output files "
                             "(default: ./plaque_profiles_<timestamp>/)")
    # PLACEHOLDER: tuple count directory — uncomment when ready
    parser.add_argument("--tuple-count-dir", default=None,
                        help="Directory where PLAQUE runtime writes tuple-count files "
                             "(placeholder — not yet wired)")

    args = parser.parse_args()

    # Timestamp for this invocation — shared across all runs, used in filenames
    run_timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")

    # Output directory for profiler JSON files
    out_dir = args.out_dir or f"./plaque_profiles_{run_timestamp}"

    normal_query = build_query(args.agg, args.col, args.sf, args.where_clause)
    plaque_query = build_plaque_query(args.agg, args.col, args.sf, args.where_clause)

    print(f"PLAQUE Benchmark — {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"  SF={args.sf}  runs={args.runs}  agg={args.agg.upper()}({args.col})")
    if args.where_clause:
        print(f"  WHERE {args.where_clause}")
    print(f"  endpoint: {args.url}")
    print(f"  timestamp: {run_timestamp}")
    print(f"  profiles: {out_dir}")
    print(f"\nNormal query:  {normal_query}")
    print(f"Plaque query:  {plaque_query}")

    normal_times = []
    plaque_times = []
    normal_counts = []
    plaque_counts = []

    print_header()

    for i in range(args.runs):
        # --- Normal (no PLAQUE) ---
        resp_normal = None
        try:
            t_normal, resp_normal = run_query(args.url, normal_query, args.timeout)
        except Exception as e:
            print(f"  ERROR on normal run {i+1}: {e}", file=sys.stderr)
            t_normal = float('nan')

        # PLACEHOLDER: read tuple count for normal mode
        c_normal = read_tuple_count(args.tuple_count_dir, run_timestamp, i, "normal")

        # --- PLAQUE mode ---
        resp_plaque = None
        try:
            t_plaque, resp_plaque = run_query(args.url, plaque_query, args.timeout)
        except Exception as e:
            print(f"  ERROR on plaque run {i+1}: {e}", file=sys.stderr)
            t_plaque = float('nan')

        # PLACEHOLDER: read tuple count for plaque mode
        c_plaque = read_tuple_count(args.tuple_count_dir, run_timestamp, i, "plaque")

        normal_times.append(t_normal)
        plaque_times.append(t_plaque)
        normal_counts.append(c_normal)
        plaque_counts.append(c_plaque)

        # Print timing row
        print_row(i + 1, t_normal, c_normal, t_plaque, c_plaque)

        # Extract and print operator breakdowns from profiler
        if resp_normal:
            op_normal, _ = extract_operator_times(resp_normal)
            print_operator_breakdown("normal", op_normal)
            save_profile_json(resp_normal, out_dir, "normal", args.sf, i, run_timestamp)

        if resp_plaque:
            op_plaque, _ = extract_operator_times(resp_plaque)
            print_operator_breakdown("plaque", op_plaque)
            save_profile_json(resp_plaque, out_dir, "plaque", args.sf, i, run_timestamp)

    # --- Rolling medians (skip first run as warmup) ---
    if args.runs >= 2:
        print_medians(normal_times, plaque_times)

    # --- Summary ---
    if args.runs >= 2:
        valid_normal = [t for t in normal_times[1:] if t == t]  # filter NaN
        valid_plaque = [t for t in plaque_times[1:] if t == t]
        if valid_normal and valid_plaque:
            med_n = statistics.median(valid_normal)
            med_p = statistics.median(valid_plaque)
            print(f"\nFinal median (runs 2-{args.runs}): "
                  f"normal={fmt_time(med_n)}  plaque={fmt_time(med_p)}  "
                  f"speedup={med_n/med_p:.2f}x")

    print(f"\nProfiler JSON files saved to: {out_dir}")


if __name__ == "__main__":
    main()
