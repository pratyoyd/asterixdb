# DISTINCT filter, levels 0 and 1 on the current build (2026-11-19)

TPC-H SF10, Iceberg `baleen_ice.lineitem` (30 files) and `baleen_ice.orders`, 4 node controllers in one JVM.
One discarded warm-up per query, then 3 runs per configuration (3 by the user's choice, not the usual 6),
configurations taken in turn within each round. Speedup is median off divided by median on. Every Baleen-on
result equalled the Baleen-off result. Flush `fj`, per-node seen-sets with propagation (the defaults).

Level 0 = observer only. Level 1 = observer + pre-join filter ("tuple filter" in earlier notes).
Levels 2 and 3 on this build were measured on 11-18 (`2026_11_18_PLAQUE_DISTINCT_PERNODE_VERIFICATION.md`).

## Queries

```sql
USE baleen_ice; SELECT DISTINCT l.l_shipmode FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';  -- Q1
USE baleen_ice; SELECT DISTINCT l.l_suppkey  FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';  -- Q2
USE baleen_ice; SELECT DISTINCT l.l_partkey  FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';  -- Q3
USE baleen_ice; SELECT DISTINCT l.l_shipmode FROM lineitem l;                                                                        -- Q4
```

## All runs and counters

| Query | Variant | Runs (s) | Median (s) | Speedup | Result = off | Files skipped per run | Files skipped / total | Files read with row-group filter | Rows out of scan | Dropped by tuple filter | Rows into observer | Dropped by observer | Deltas sent | Deltas merged | Values merged |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Q1 l_shipmode, join | Baleen off | 205.0 214.5 205.8 | 205.8 | 1.00x | yes |  | |||||||||
| Q1 l_shipmode, join | hash, level 0 | 39.2 38.1 37.6 | 38.1 | 5.41x | yes | 0 0 0 | 0 / 30 | 0 | 0 | 0 | 29058120 | 29058092 | 4 | 3 | 21 |
| Q1 l_shipmode, join | hash, level 1 | 36.7 35.3 34.1 | 35.3 | 5.83x | yes | 0 0 0 | 0 / 30 | 0 | 59986052 | 59983654 | 1112 | 1084 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 0 | 38.5 36.5 38.1 | 38.1 | 5.41x | yes | 0 0 0 | 0 / 30 | 0 | 0 | 0 | 29058120 | 29058092 | 4 | 3 | 21 |
| Q1 l_shipmode, join | flat, level 1 | 39.4 38.1 34.5 | 38.1 | 5.40x | yes | 0 0 0 | 0 / 30 | 0 | 59986052 | 59984854 | 574 | 546 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 0 | 39.5 37.7 39.5 | 39.5 | 5.21x | yes | 0 0 0 | 0 / 30 | 0 | 0 | 0 | 29058120 | 29058092 | 4 | 3 | 21 |
| Q1 l_shipmode, join | roaring, level 1 | 36.6 36.5 37.6 | 36.6 | 5.62x | yes | 0 0 0 | 0 / 30 | 0 | 59986052 | 59983650 | 1120 | 1092 | 4 | 0 | 0 |
| Q2 l_suppkey, join | Baleen off | 166.6 163.5 164.4 | 164.4 | 1.00x | yes |  | |||||||||
| Q2 l_suppkey, join | hash, level 0 | 40.5 37.5 38.9 | 38.9 | 4.23x | yes | 0 0 0 | 0 / 30 | 0 | 0 | 0 | 29058120 | 28947467 | 157 | 471 | 300000 |
| Q2 l_suppkey, join | hash, level 1 | 46.2 45.2 43.4 | 45.2 | 3.64x | yes | 0 0 0 | 0 / 30 | 0 | 59986052 | 59695517 | 140549 | 16013 | 30 | 81 | 300000 |
| Q2 l_suppkey, join | flat, level 0 | 36.5 36.8 37.3 | 36.8 | 4.46x | yes | 0 0 0 | 0 / 30 | 0 | 0 | 0 | 29058120 | 28949734 | 161 | 477 | 300000 |
| Q2 l_suppkey, join | flat, level 1 | 34.0 34.2 36.0 | 34.2 | 4.80x | yes | 0 0 0 | 0 / 30 | 0 | 59986052 | 59695962 | 140418 | 16711 | 30 | 78 | 299973 |
| Q2 l_suppkey, join | roaring, level 0 | 36.4 36.5 38.8 | 36.5 | 4.51x | yes | 0 0 0 | 0 / 30 | 0 | 0 | 0 | 29058120 | 28949702 | 162 | 480 | 300000 |
| Q2 l_suppkey, join | roaring, level 1 | 34.9 34.8 36.0 | 34.9 | 4.71x | yes | 0 0 0 | 0 / 30 | 0 | 59986052 | 59687353 | 144454 | 20215 | 30 | 81 | 300000 |
| Q3 l_partkey, join | Baleen off | 133.0 147.3 138.5 | 138.5 | 1.00x | yes |  | |||||||||
| Q3 l_partkey, join | hash, level 0 | 94.0 99.7 94.7 | 94.7 | 1.46x | yes | 0 0 0 | 0 / 30 | 0 | 0 | 0 | 29058120 | 14208773 | 244 | 732 | 3022167 |
| Q3 l_partkey, join | hash, level 1 | 127.0 126.7 124.3 | 126.7 | 1.09x | yes | 0 0 0 | 0 / 30 | 0 | 59986052 | 29319480 | 14852554 | 35471 | 204 | 612 | 3025953 |
| Q3 l_partkey, join | flat, level 0 | 49.1 44.9 47.1 | 47.1 | 2.94x | yes | 0 0 0 | 0 / 30 | 0 | 0 | 0 | 29058120 | 27050359 | 3195 | 9579 | 5999994 |
| Q3 l_partkey, join | flat, level 1 | 45.6 44.0 47.4 | 45.6 | 3.04x | yes | 0 0 0 | 0 / 30 | 0 | 59986052 | 55739446 | 2054211 | 16072 | 411 | 1221 | 5998902 |
| Q3 l_partkey, join | roaring, level 0 | 45.8 46.3 48.8 | 46.3 | 2.99x | yes | 0 0 0 | 0 / 30 | 0 | 0 | 0 | 29058120 | 27049427 | 3195 | 9582 | 5999994 |
| Q3 l_partkey, join | roaring, level 1 | 45.6 45.5 46.1 | 45.6 | 3.04x | yes | 0 0 0 | 0 / 30 | 0 | 59986052 | 55718166 | 2064414 | 24377 | 412 | 1224 | 5995038 |
| Q4 l_shipmode, no join | Baleen off | 415.9 416.2 416.6 | 416.2 | 1.00x | yes |  | |||||||||
| Q4 l_shipmode, no join | hash, level 0 | 21.6 18.1 19.6 | 19.6 | 21.22x | yes | 0 0 0 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 1 | 19.4 18.7 21.7 | 19.4 | 21.41x | yes | 0 0 0 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 0 | 19.7 19.7 20.0 | 19.7 | 21.14x | yes | 0 0 0 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 1 | 19.0 19.2 20.2 | 19.2 | 21.66x | yes | 0 0 0 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 0 | 19.4 20.1 19.8 | 19.8 | 21.05x | yes | 0 0 0 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 1 | 18.7 22.1 19.9 | 19.9 | 20.94x | yes | 0 0 0 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |

DONE

## What the numbers say

- Against the 11-17 uniform benchmark, 23 of 24 cells are within 4.5 s (Q3 is 2 to 4 s slower today, but its
  Baleen-off time is also 15 s higher: 138.5 against 123.2 s). The exception is Q3 hash level 1: 126.7 s
  against 103.3 s on 11-17. It agrees with the 11-18 hash level 2 time (126.2 s), so the change came with the
  11-17/11-18 work (per-node seen-sets, propagation), not with today's build.
- The pre-join filter adds little over the observer: at most 2.6 s (Q2 flat, 36.8 to 34.2 s). The observer
  already removes the sort; the filter only saves the join probe for rows already seen.
- Hash seen-set, level 1 is slower than level 0 on Q2 (38.9 to 45.2 s) and Q3 (94.7 to 126.7 s). On Q3 the
  hash set passes its 1,000,000-value cap and stops filtering (29.3 M rows dropped against 55.7 M for
  flat and Roaring).
- Q4 has no join, so there is no pre-join filter position; level 1 equals level 0 (counters identical).
- No file is skipped at levels 0 and 1, as designed.

## Files

- `build_logs/bench_distinct_levels01.py` (copy of `bench_distinct_pernode.py` with 3 runs and levels 0, 1),
  `bench_result_distinct_levels01.md`, `bench_result_distinct_levels01_per_run.md`, `bench_distinct_levels01.out`.
- Slide CSV on the user's machine: `~/baleen_slide_tables/distinct_current_build_all_levels_2026_11_19.csv`.

## Decisions I made without asking

- Included a Baleen-off baseline (12 runs, about 45 minutes); the user said afterwards it was not needed.
- The CSV combines two runs with separate baselines: levels 0-1 against today's, levels 2-3 against 11-18's.
- The server is today's build, which also carries the MAX/MIN early-send change.

## Stated but not verified

- That the hash cap is the whole reason for the Q3 hash level 1 slowdown (inferred from the drop counters).
- Why hash level 1 is slower than level 0 on Q2, where the cap is not reached.
- 3 runs per cell; differences of 1 to 2 s between cells are within run-to-run spread.

## Profiled runs: what the observer removes (Q1 and Q4)

One run each with `profile=timings`, Baleen off and observer only (Roaring, level 0), after one discarded
warm-up. Files in `build_logs/profile_distinct_1119/` (`run.py`, `analyze.py`, `q{1,4}_{off,observer}.json`).
Operator time is summed over the 4 partitions; "slowest" is the largest single partition.

| Operator | Q1 off, sum (slowest) | Q1 observer | Q4 off, sum (slowest) | Q4 observer |
|---|---|---|---|---|
| Wall time | 206.1 | 39.5 | 418.2 | 19.4 |
| Scan lineitem | 81.9 (22.0) | 74.4 (20.3) | 65.3 (17.7) | 49.5 (13.5) |
| Field extraction above the scan | 28.0 | 26.0 | 47.5, incl. building the output record | 15.8 |
| Scan orders + select + build | 32.9 | 31.3 | n/a | n/a |
| Hash join probe | 53.4 (13.8) | 58.4 (15.4) | n/a | n/a |
| Project + build output record | 20.2 | 6.1 (observer included) | (in field extraction) | 5.3 (observer included) |
| Sort, run generation | 201.9 (91.6) | 0.0 | 421.2 (189.6) | 0.0 |
| Sort, run merge | 99.0 (46.2) | 0.0 | 230.5 (105.2) | 0.0 |
| Rows into the sort | 29,058,120 | 28 | 59,986,052 | 28 |

- The observer removes the sort: 28 rows reach it (7 values from each of 4 partitions) instead of 29 M / 60 M.
  On the slowest partition the sort took 137.8 s of Q1's 206 s and 294.8 s of Q4's 418 s.
- Scan, join build and join probe are unchanged at level 0; they are what is left (Q1 39.5 s, Q4 19.4 s).
- Not in the table: the hash exchange in front of the sort has no operator timer; it also shrinks to 28 rows.
- Decisions made without asking: one run per cell, Roaring only, no plan approval asked before running.
- Not verified: that operator run-times exclude time spent waiting on the next operator.
- Sort skew with Baleen off: the 7 ship modes hash onto the 4 partitions as 0 / 3 / 3 / 1 values. Sort time
  per partition (run generation + merge): Q1 0 / 135.3 / 136.7 / 28.8 s; Q4 0 / 294.8 / 290.2 / 66.7 s. Two
  partitions sort 43% of the rows each and set the wall time; one sorts nothing.

## Profiled runs: why the hash seen-set is slower at level 1 than level 0 (Q2 and Q3)

One run each with `profile=timings`, hash seen-set, level 0 and level 1, after one discarded warm-up
(`profile_distinct_1119/run_hash.py`, `q{2,3}_hash_l{0,1}.json`). Operator time summed over 4 partitions.

| Operator | Q2 level 0 | Q2 level 1 | Q3 level 0 | Q3 level 1 |
|---|---|---|---|---|
| Wall time | 35.9 | 50.4 | 92.6 | 127.9 |
| Scan lineitem + field extraction | 87.2 | 94.3 | 100.2 | 100.2 |
| Pre-join filter (rows in / out) | n/a | 51.3 (60.0 M / 0.29 M) | n/a | 206.2 (60.0 M / 30.7 M) |
| Hash join probe (rows out) | 51.6 (29.1 M) | 0.6 (0.14 M) | 56.8 (29.1 M) | 49.2 (14.9 M) |
| Observer (rows in / out) | 27.8 (29.1 M / 0.11 M) | 0.6 (0.14 M / 0.12 M) | 93.2 (29.1 M / 14.8 M) | 68.9 (14.9 M / 14.8 M) |
| Sort, generation + merge | 0.9 | 0.9 | 173.3 | 187.1 |
| Scan-side pipeline per partition (scan + extraction + filter) / 4 | 21.8 | 36.4 | 25.1 | 76.6 |

- Q2: the hash filter does its job (60.0 M rows in, 0.29 M out) and the join probe falls from 51.6 s to 0.6 s.
  But the hash membership test costs 51.3 s, as much as the probe it saves (0.86 microseconds per row for
  both). Total operator time is about the same; the work has moved from the join's thread into the scan's
  thread, which was already the longer of the two. Scan-side work per partition goes from 21.8 s to 36.4 s
  and the wall time rises by the same 14.5 s.
- Q3: the filter passes 30.7 M of 60.0 M rows: with 2 M values and a 1 M cap, half of the values can never
  enter the set (consistent with the cap; the cap itself was not instrumented). The test is also 4 times
  dearer per row on the larger set (3.4 microseconds). The filter costs 206 s and saves 7.6 s of join probe
  and 24 s of observer; the sort is unchanged because the same 14.8 M rows reach it.
- Inference, not measured here: flat and Roaring do not show this because their test is much cheaper per row
  (Q1 Roaring observer: 3.2 s for 29.1 M rows, 0.11 microseconds per row, though on a 7-value string column).
- Decisions made without asking: one run per cell; hash only, no flat/Roaring profile for comparison.
