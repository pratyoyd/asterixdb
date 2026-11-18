# PLAQUE DISTINCT filter: verification of the per-node seen-set build (2026-11-18)

Scope: DISTINCT with the observer and filter on the probe side only.

## What was verified

The build with these changes (made 2026-11-17, loaded 2026-11-18 10:38):

- default seen-set `roaring`, default level 3, default early sending `fj`;
- one seen-set per node (before, the 4 NCs of the test server shared one);
- propagation (NC to CC to NC) as the only way a node learns another node's values;
- the CC does not send a delta back to its sender;
- CC entries removed when the job ends (`PlaqueJobCleanupListener`).

## Result in short

All 168 measured runs and the 4 warm-up runs return the same result as Baleen off (md5 of the sorted rows).

Median of 6 runs, seconds, speedup over Baleen off in brackets:

| Query | Baleen off | hash L2 | hash L3 | flat L2 | flat L3 | roaring L2 | roaring L3 (default) |
|---|---|---|---|---|---|---|---|
| Q1 `l_shipmode`, join | 203.8 | 36.2 (5.63x) | 12.8 (15.97x) | 35.5 (5.74x) | 12.7 (16.07x) | 35.7 (5.72x) | 12.3 (16.56x) |
| Q2 `l_suppkey`, join | 164.6 | 44.3 (3.72x) | 44.2 (3.73x) | 16.0 (10.27x) | 16.3 (10.09x) | 16.4 (10.03x) | 16.0 (10.31x) |
| Q3 `l_partkey`, join | 143.1 | 126.2 (1.13x) | 124.8 (1.15x) | 44.4 (3.22x) | 44.1 (3.24x) | 46.4 (3.09x) | 47.6 (3.01x) |
| Q4 `l_shipmode`, no join | 402.5 | 19.6 (20.56x) | 2.9 (137.28x) | 18.9 (21.26x) | 3.0 (135.92x) | 19.8 (20.28x) | 3.0 (134.04x) |

Q2, files skipped out of 30, per run:

| Variant | Run 1 | Run 2 | Run 3 | Run 4 | Run 5 | Run 6 |
|---|---|---|---|---|---|---|
| hash, level 2 | 0 | 0 | 0 | 0 | 0 | 0 |
| hash, level 3 | 0 | 0 | 0 | 0 | 0 | 0 |
| flat, level 2 | 25 | 25 | 25 | 22 | 25 | 23 |
| flat, level 3 | 23 | 22 | 25 | 23 | 23 | 23 |
| roaring, level 2 | 25 | 23 | 26 | 25 | 25 | 25 |
| roaring, level 3 | 25 | 22 | 25 | 21 | 25 | 25 |

No file is skipped on Q1, Q3 or Q4 in any run.

## Findings

- With the defaults (roaring, level 3) the four queries run 16.6x, 10.3x, 3.0x and 134x faster than Baleen off.
- Level 3 acts on `l_shipmode` (Q1 and Q4): 26 of 30 files are read with the row-group filter and the rows leaving the scan fall from 59,986,052 to 8,000,000. Q1 goes from about 36 s (level 2) to about 12.5 s, Q4 from about 19 s to about 3 s. This holds for all three seen-sets.
- Level 3 does nothing on Q2 and Q3: 0 files read with the row-group filter, times equal to level 2, results correct. This run does not separate the two possible reasons (column without dictionary, or more distinct values than the `notinmax` limit of 10,000).
- Q2 with per-node seen-sets skips 21 to 26 of 30 files (flat and roaring). On the shared seen-set build of 2026-11-17 it was 26 of 30 in all 6 runs, median 12.2 s (roaring, level 2, `fj`); now the medians are 16.0 to 16.4 s. The skip now depends on values arriving from the other nodes through the CC.
- Propagation carries values on every query that has values to share. Counters of the median run, summed over the 4 observers: Q2 30 to 31 deltas sent, 78 to 81 merged, about 300,000 values merged; Q3 (flat, roaring) 411 to 412 sent, 1,221 to 1,224 merged, about 6,000,000 values merged; Q4 4 sent, 3 merged, 21 values merged; Q1 4 sent, 0 merged.
- Hash never skips a file and is the slowest seen-set on Q2 and Q3 (Q3: 1.13x to 1.15x).
- Q4 has no tuple filter (the observer sits directly above the scan), so its "Rows out of scan" and "Dropped by tuple filter" columns are 0; the scan output is the "Rows into observer" column.

## Setup

- Server: AsterixHyracksIntegrationUtil, 4 NCs, on sensoria; log `build_logs/asterix_distinct_pernode.log`.
- Dataverse `baleen_ice`, table `lineitem` (Iceberg, 30 files), `orders`.
- Script `build_logs/bench_distinct_pernode.py`; outputs `build_logs/bench_result_distinct_pernode.md`, `build_logs/bench_result_distinct_pernode_per_run.md`.
- Configurations per query: Baleen off, and {hash, flat, roaring} x level {2, 3}; flush left at its default `fj`, propagation at its default (on).
- 6 runs per configuration, taken round-robin within a query; one discarded warm-up run per query (roaring, level 3). 4 queries x 7 configurations x 6 runs = 168 runs. Run from 10:39 to about 13:45.
- Counters in the summary table are those of the upper-median run of the configuration.

Queries:

```sql
-- Q1
SELECT DISTINCT l.l_shipmode FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
-- Q2
SELECT DISTINCT l.l_suppkey FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
-- Q3
SELECT DISTINCT l.l_partkey FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
-- Q4
SELECT DISTINCT l.l_shipmode FROM lineitem l;
```

Settings sent before each query:

```sql
-- Baleen off
SET `compiler.plaque.enabled` "false";
-- Baleen on
SET `compiler.plaque.enabled` "true"; SET `compiler.plaque.distinct.set` "<hash|flat|roaring>"; SET `compiler.plaque.distinct.level` "<2|3>";
```

Result sizes and md5 (first 10 hex digits): Q1 7 rows 86ca9c0b89; Q2 100,000 rows fe689b6606; Q3 1,999,998 rows 1169248e70; Q4 7 rows 86ca9c0b89.

## Built-in limits

- Hash seen-set cap 1,000,000 values; file skip with hash only for single-value files.
- Flat seen-set range 16,000,000.
- File skip: BIGINT column, null count 0, every value between the file's bounds seen.
- Level 3: column needs a Parquet dictionary; value list at most `notinmax` = 10,000.
- Propagation interval 5,000 rows, plus one send at close.
- Levels 0 and 1, and flush settings other than `fj`, were not part of this run.
- The 4 NCs run in one process.

## Other events during the run

- `DROP DATASET lineitem_dict` and `DROP DATASET lineitem_dict_1rg` (dataverse `baleen_ice`) were issued at 10:40:20, during the first Baleen-off run of Q1. That run took 207.6 s; the other five took 200.3 to 207.8 s.
- Baleen off, Q2: runs 1 and 3 took 204.8 and 187.4 s against 160.5 to 165.3 s for the other four. Baleen off, Q3: run 5 took 177.6 s against 138.9 to 145.9 s. Cause not looked into. Medians are used.

## Bugs

None found in this run. The only exception in the server log is the readiness poll during startup (`cluster is UNUSABLE`).

## Summary table (as written by the script)

| Query | Variant | Runs (s) | Median (s) | Speedup | Result = off | Files skipped per run | Files skipped / total | Files read with row-group filter | Rows out of scan | Dropped by tuple filter | Rows into observer | Dropped by observer | Deltas sent | Deltas merged | Values merged |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Q1 l_shipmode, join | Baleen off | 207.6 203.4 203.5 200.3 207.8 204.2 | 203.8 | 1.00x | yes |  | |||||||||
| Q1 l_shipmode, join | hash, level 2 | 37.1 36.2 38.7 35.0 36.2 33.8 | 36.2 | 5.63x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 59982452 | 1694 | 1666 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 3 | 12.9 12.7 13.1 12.2 12.7 12.9 | 12.8 | 15.97x | yes | 0 0 0 0 0 0 | 0 / 30 | 26 | 8000000 | 7997601 | 1146 | 1118 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | 38.0 35.2 35.5 35.5 37.6 34.8 | 35.5 | 5.74x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 59983650 | 1120 | 1092 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 3 | 13.1 12.0 13.1 12.8 12.5 12.4 | 12.7 | 16.07x | yes | 0 0 0 0 0 0 | 0 / 30 | 26 | 8000000 | 7996400 | 1694 | 1666 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | 36.0 35.2 36.4 35.3 36.1 33.0 | 35.7 | 5.72x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 59982453 | 1684 | 1656 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 3 | 11.8 12.4 12.2 12.0 13.9 12.9 | 12.3 | 16.56x | yes | 0 0 0 0 0 0 | 0 / 30 | 26 | 8000000 | 7997597 | 1154 | 1126 | 4 | 0 | 0 |
| Q2 l_suppkey, join | Baleen off | 204.8 161.2 187.4 160.5 165.3 163.9 | 164.6 | 1.00x | yes |  | |||||||||
| Q2 l_suppkey, join | hash, level 2 | 45.0 46.8 43.4 44.6 43.9 43.4 | 44.3 | 3.72x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 59695412 | 140767 | 15990 | 30 | 78 | 299874 |
| Q2 l_suppkey, join | hash, level 3 | 42.2 45.0 44.9 45.8 43.1 43.4 | 44.2 | 3.73x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 59695067 | 140731 | 16085 | 30 | 78 | 299910 |
| Q2 l_suppkey, join | flat, level 2 | 16.1 16.1 15.8 22.7 15.9 15.6 | 16.0 | 10.27x | yes | 25 25 25 22 25 23 | 25 / 30 | 0 | 10000000 | 9709273 | 140684 | 17051 | 30 | 78 | 299997 |
| Q2 l_suppkey, join | flat, level 3 | 16.2 22.2 15.0 16.4 16.1 22.0 | 16.3 | 10.09x | yes | 23 22 25 23 23 23 | 23 / 30 | 0 | 14000000 | 13710145 | 140345 | 16664 | 30 | 78 | 299997 |
| Q2 l_suppkey, join | roaring, level 2 | 15.1 18.3 13.0 16.1 16.7 16.9 | 16.4 | 10.03x | yes | 25 23 26 25 25 25 | 25 / 30 | 0 | 10000000 | 9707183 | 141540 | 17197 | 31 | 81 | 299991 |
| Q2 l_suppkey, join | roaring, level 3 | 15.8 15.7 15.1 18.3 16.2 16.4 | 16.0 | 10.31x | yes | 25 22 25 21 25 25 | 25 / 30 | 0 | 10000000 | 9708078 | 141133 | 16764 | 31 | 81 | 299991 |
| Q3 l_partkey, join | Baleen off | 145.9 141.4 138.9 140.1 177.6 144.8 | 143.1 | 1.00x | yes |  | |||||||||
| Q3 l_partkey, join | hash, level 2 | 123.5 124.1 128.2 124.9 127.4 129.5 | 126.2 | 1.13x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 29319038 | 14852082 | 36834 | 204 | 612 | 3022908 |
| Q3 l_partkey, join | hash, level 3 | 125.9 131.7 122.3 124.6 123.6 125.0 | 124.8 | 1.15x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 29318921 | 14851982 | 31473 | 203 | 609 | 3021645 |
| Q3 l_partkey, join | flat, level 2 | 43.6 44.2 44.5 44.1 47.8 46.5 | 44.4 | 3.22x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 55739596 | 2054214 | 15795 | 411 | 1221 | 5998695 |
| Q3 l_partkey, join | flat, level 3 | 46.3 44.8 42.0 43.5 42.7 46.7 | 44.1 | 3.24x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 55739140 | 2054455 | 16055 | 411 | 1221 | 5999190 |
| Q3 l_partkey, join | roaring, level 2 | 46.0 47.1 46.7 48.2 44.5 45.2 | 46.4 | 3.09x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 55713580 | 2066731 | 25778 | 412 | 1224 | 5992749 |
| Q3 l_partkey, join | roaring, level 3 | 47.6 44.4 47.7 49.6 44.4 48.9 | 47.6 | 3.01x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 55714256 | 2066427 | 25475 | 412 | 1224 | 5993178 |
| Q4 l_shipmode, no join | Baleen off | 403.7 393.2 408.6 396.9 403.2 401.8 | 402.5 | 1.00x | yes |  | |||||||||
| Q4 l_shipmode, no join | hash, level 2 | 18.7 18.6 19.5 20.2 20.0 19.7 | 19.6 | 20.56x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 3 | 3.1 3.0 3.0 2.6 2.9 2.8 | 2.9 | 137.28x | yes | 0 0 0 0 0 0 | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 2 | 19.3 18.3 19.0 18.4 18.9 19.6 | 18.9 | 21.26x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 3 | 2.7 3.1 3.1 3.0 2.9 2.8 | 3.0 | 135.92x | yes | 0 0 0 0 0 0 | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 2 | 19.8 18.7 20.3 18.1 19.9 21.4 | 19.8 | 20.28x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 3 | 3.0 3.1 2.6 3.0 3.4 2.9 | 3.0 | 134.04x | yes | 0 0 0 0 0 0 | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |

## Every run

| Query | Variant | Run | Time (s) | Result = off | Files skipped / total | Files read with row-group filter | Rows out of scan | Dropped by tuple filter | Rows into observer | Dropped by observer | Deltas sent | Deltas merged | Values merged |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Q1 l_shipmode, join | warm-up (roaring, level 3), discarded | 0 | 14.0 | | 0 / 30 | 26 | 8000000 | 7997603 | 1150 | 1122 | 4 | 0 | 0 |
| Q1 l_shipmode, join | Baleen off | 1 | 207.6 | yes | |||||||||
| Q1 l_shipmode, join | hash, level 2 | 1 | 37.1 | yes | 0 / 30 | 0 | 59986052 | 59983654 | 1112 | 1084 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 3 | 1 | 12.9 | yes | 0 / 30 | 26 | 8000000 | 7997602 | 1112 | 1084 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | 1 | 38.0 | yes | 0 / 30 | 0 | 59986052 | 59983653 | 1146 | 1118 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 3 | 1 | 13.1 | yes | 0 / 30 | 26 | 8000000 | 7998800 | 538 | 510 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | 1 | 36.0 | yes | 0 / 30 | 0 | 59986052 | 59982453 | 1684 | 1656 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 3 | 1 | 11.8 | yes | 0 / 30 | 26 | 8000000 | 7996400 | 1694 | 1666 | 4 | 0 | 0 |
| Q1 l_shipmode, join | Baleen off | 2 | 203.4 | yes | |||||||||
| Q1 l_shipmode, join | hash, level 2 | 2 | 36.2 | yes | 0 / 30 | 0 | 59986052 | 59982452 | 1694 | 1666 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 3 | 2 | 12.7 | yes | 0 / 30 | 26 | 8000000 | 7996400 | 1694 | 1666 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | 2 | 35.2 | yes | 0 / 30 | 0 | 59986052 | 59983650 | 1120 | 1092 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 3 | 2 | 12.0 | yes | 0 / 30 | 26 | 8000000 | 7998798 | 582 | 554 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | 2 | 35.2 | yes | 0 / 30 | 0 | 59986052 | 59984075 | 965 | 937 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 3 | 2 | 12.4 | yes | 0 / 30 | 26 | 8000000 | 7997597 | 1154 | 1126 | 4 | 0 | 0 |
| Q1 l_shipmode, join | Baleen off | 3 | 203.5 | yes | |||||||||
| Q1 l_shipmode, join | hash, level 2 | 3 | 38.7 | yes | 0 / 30 | 0 | 59986052 | 59981251 | 2266 | 2238 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 3 | 3 | 13.1 | yes | 0 / 30 | 26 | 8000000 | 7998799 | 572 | 544 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | 3 | 35.5 | yes | 0 / 30 | 0 | 59986052 | 59984854 | 574 | 546 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 3 | 3 | 13.1 | yes | 0 / 30 | 26 | 8000000 | 7998798 | 582 | 554 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | 3 | 36.4 | yes | 0 / 30 | 0 | 59986052 | 59984851 | 572 | 544 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 3 | 3 | 12.2 | yes | 0 / 30 | 26 | 8000000 | 7995773 | 1988 | 1960 | 4 | 0 | 0 |
| Q1 l_shipmode, join | Baleen off | 4 | 200.3 | yes | |||||||||
| Q1 l_shipmode, join | hash, level 2 | 4 | 35.0 | yes | 0 / 30 | 0 | 59986052 | 59983069 | 1459 | 1431 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 3 | 4 | 12.2 | yes | 0 / 30 | 26 | 8000000 | 7997599 | 1110 | 1082 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | 4 | 35.5 | yes | 0 / 30 | 0 | 59986052 | 59983650 | 1120 | 1092 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 3 | 4 | 12.8 | yes | 0 / 30 | 26 | 8000000 | 7996400 | 1694 | 1666 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | 4 | 35.3 | yes | 0 / 30 | 0 | 59986052 | 59983654 | 1112 | 1084 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 3 | 4 | 12.0 | yes | 0 / 30 | 26 | 8000000 | 7998799 | 572 | 544 | 4 | 0 | 0 |
| Q1 l_shipmode, join | Baleen off | 5 | 207.8 | yes | |||||||||
| Q1 l_shipmode, join | hash, level 2 | 5 | 36.2 | yes | 0 / 30 | 0 | 59986052 | 59984851 | 572 | 544 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 3 | 5 | 12.7 | yes | 0 / 30 | 26 | 8000000 | 7997599 | 1110 | 1082 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | 5 | 37.6 | yes | 0 / 30 | 0 | 59986052 | 59983219 | 1368 | 1340 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 3 | 5 | 12.5 | yes | 0 / 30 | 26 | 8000000 | 7998802 | 574 | 546 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | 5 | 36.1 | yes | 0 / 30 | 0 | 59986052 | 59984850 | 582 | 554 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 3 | 5 | 13.9 | yes | 0 / 30 | 26 | 8000000 | 7997600 | 1156 | 1128 | 4 | 0 | 0 |
| Q1 l_shipmode, join | Baleen off | 6 | 204.2 | yes | |||||||||
| Q1 l_shipmode, join | hash, level 2 | 6 | 33.8 | yes | 0 / 30 | 0 | 59986052 | 59981251 | 2266 | 2238 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 3 | 6 | 12.9 | yes | 0 / 30 | 26 | 8000000 | 7997601 | 1146 | 1118 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | 6 | 34.8 | yes | 0 / 30 | 0 | 59986052 | 59983653 | 1146 | 1118 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 3 | 6 | 12.4 | yes | 0 / 30 | 26 | 8000000 | 7998798 | 582 | 554 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | 6 | 33.0 | yes | 0 / 30 | 0 | 59986052 | 59984854 | 574 | 546 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 3 | 6 | 12.9 | yes | 0 / 30 | 26 | 8000000 | 7996970 | 1454 | 1426 | 4 | 0 | 0 |
| Q2 l_suppkey, join | warm-up (roaring, level 3), discarded | 0 | 19.9 | | 22 / 30 | 0 | 16000000 | 15707126 | 141732 | 16892 | 31 | 81 | 299751 |
| Q2 l_suppkey, join | Baleen off | 1 | 204.8 | yes | |||||||||
| Q2 l_suppkey, join | hash, level 2 | 1 | 45.0 | yes | 0 / 30 | 0 | 59986052 | 59694272 | 141188 | 15978 | 31 | 81 | 299964 |
| Q2 l_suppkey, join | hash, level 3 | 1 | 42.2 | yes | 0 / 30 | 0 | 59986052 | 59693574 | 141569 | 16588 | 31 | 81 | 299979 |
| Q2 l_suppkey, join | flat, level 2 | 1 | 16.1 | yes | 25 / 30 | 0 | 10000000 | 9708089 | 140951 | 16971 | 31 | 81 | 299991 |
| Q2 l_suppkey, join | flat, level 3 | 1 | 16.2 | yes | 23 / 30 | 0 | 14000000 | 13708790 | 140865 | 16913 | 30 | 78 | 299991 |
| Q2 l_suppkey, join | roaring, level 2 | 1 | 15.1 | yes | 25 / 30 | 0 | 10000000 | 9708252 | 141182 | 16783 | 31 | 81 | 299997 |
| Q2 l_suppkey, join | roaring, level 3 | 1 | 15.8 | yes | 25 / 30 | 0 | 10000000 | 9707355 | 141561 | 17372 | 31 | 81 | 299997 |
| Q2 l_suppkey, join | Baleen off | 2 | 161.2 | yes | |||||||||
| Q2 l_suppkey, join | hash, level 2 | 2 | 46.8 | yes | 0 / 30 | 0 | 59986052 | 59695527 | 140571 | 16336 | 30 | 78 | 299967 |
| Q2 l_suppkey, join | hash, level 3 | 2 | 45.0 | yes | 0 / 30 | 0 | 59986052 | 59694409 | 140948 | 16572 | 31 | 81 | 299997 |
| Q2 l_suppkey, join | flat, level 2 | 2 | 16.1 | yes | 25 / 30 | 0 | 10000000 | 9709273 | 140684 | 17051 | 30 | 78 | 299997 |
| Q2 l_suppkey, join | flat, level 3 | 2 | 22.2 | yes | 22 / 30 | 0 | 16000000 | 15709687 | 140516 | 17017 | 30 | 78 | 299979 |
| Q2 l_suppkey, join | roaring, level 2 | 2 | 18.3 | yes | 23 / 30 | 0 | 14000000 | 13709030 | 140701 | 16612 | 30 | 78 | 299922 |
| Q2 l_suppkey, join | roaring, level 3 | 2 | 15.7 | yes | 22 / 30 | 0 | 16000000 | 15707776 | 141367 | 17285 | 31 | 81 | 299994 |
| Q2 l_suppkey, join | Baleen off | 3 | 187.4 | yes | |||||||||
| Q2 l_suppkey, join | hash, level 2 | 3 | 43.4 | yes | 0 / 30 | 0 | 59986052 | 59695688 | 140487 | 15734 | 31 | 81 | 299997 |
| Q2 l_suppkey, join | hash, level 3 | 3 | 44.9 | yes | 0 / 30 | 0 | 59986052 | 59695067 | 140731 | 16085 | 30 | 78 | 299910 |
| Q2 l_suppkey, join | flat, level 2 | 3 | 15.8 | yes | 25 / 30 | 0 | 10000000 | 9709731 | 140353 | 16628 | 29 | 78 | 300000 |
| Q2 l_suppkey, join | flat, level 3 | 3 | 15.0 | yes | 25 / 30 | 0 | 10000000 | 9709725 | 140513 | 16905 | 29 | 78 | 300000 |
| Q2 l_suppkey, join | roaring, level 2 | 3 | 13.0 | yes | 26 / 30 | 0 | 8000000 | 7698636 | 145662 | 21505 | 30 | 81 | 300000 |
| Q2 l_suppkey, join | roaring, level 3 | 3 | 15.1 | yes | 25 / 30 | 0 | 10000000 | 9708856 | 140903 | 16375 | 31 | 81 | 299991 |
| Q2 l_suppkey, join | Baleen off | 4 | 160.5 | yes | |||||||||
| Q2 l_suppkey, join | hash, level 2 | 4 | 44.6 | yes | 0 / 30 | 0 | 59986052 | 59695412 | 140767 | 15990 | 30 | 78 | 299874 |
| Q2 l_suppkey, join | hash, level 3 | 4 | 45.8 | yes | 0 / 30 | 0 | 59986052 | 59695122 | 140667 | 15820 | 30 | 78 | 299772 |
| Q2 l_suppkey, join | flat, level 2 | 4 | 22.7 | yes | 22 / 30 | 0 | 16000000 | 15709774 | 140372 | 16917 | 29 | 75 | 299607 |
| Q2 l_suppkey, join | flat, level 3 | 4 | 16.4 | yes | 23 / 30 | 0 | 14000000 | 13710145 | 140345 | 16664 | 30 | 78 | 299997 |
| Q2 l_suppkey, join | roaring, level 2 | 4 | 16.1 | yes | 25 / 30 | 0 | 10000000 | 9706878 | 141759 | 17316 | 31 | 81 | 299994 |
| Q2 l_suppkey, join | roaring, level 3 | 4 | 18.3 | yes | 21 / 30 | 0 | 18000000 | 17709178 | 140705 | 16638 | 30 | 78 | 299853 |
| Q2 l_suppkey, join | Baleen off | 5 | 165.3 | yes | |||||||||
| Q2 l_suppkey, join | hash, level 2 | 5 | 43.9 | yes | 0 / 30 | 0 | 59986052 | 59694460 | 141121 | 16314 | 30 | 78 | 299802 |
| Q2 l_suppkey, join | hash, level 3 | 5 | 43.1 | yes | 0 / 30 | 0 | 59986052 | 59694800 | 140797 | 16001 | 30 | 78 | 299742 |
| Q2 l_suppkey, join | flat, level 2 | 5 | 15.9 | yes | 25 / 30 | 0 | 10000000 | 9710270 | 140228 | 16659 | 30 | 78 | 299997 |
| Q2 l_suppkey, join | flat, level 3 | 5 | 16.1 | yes | 23 / 30 | 0 | 14000000 | 13709446 | 140636 | 17218 | 30 | 78 | 299997 |
| Q2 l_suppkey, join | roaring, level 2 | 5 | 16.7 | yes | 25 / 30 | 0 | 10000000 | 9707183 | 141540 | 17197 | 31 | 81 | 299991 |
| Q2 l_suppkey, join | roaring, level 3 | 5 | 16.2 | yes | 25 / 30 | 0 | 10000000 | 9708078 | 141133 | 16764 | 31 | 81 | 299991 |
| Q2 l_suppkey, join | Baleen off | 6 | 163.9 | yes | |||||||||
| Q2 l_suppkey, join | hash, level 2 | 6 | 43.4 | yes | 0 / 30 | 0 | 59986052 | 59694349 | 141183 | 16405 | 31 | 81 | 299994 |
| Q2 l_suppkey, join | hash, level 3 | 6 | 43.4 | yes | 0 / 30 | 0 | 59986052 | 59696558 | 140204 | 15828 | 30 | 78 | 299964 |
| Q2 l_suppkey, join | flat, level 2 | 6 | 15.6 | yes | 23 / 30 | 0 | 14000000 | 13710284 | 140184 | 16594 | 30 | 78 | 299997 |
| Q2 l_suppkey, join | flat, level 3 | 6 | 22.0 | yes | 23 / 30 | 0 | 14000000 | 13710398 | 140204 | 16767 | 29 | 75 | 299658 |
| Q2 l_suppkey, join | roaring, level 2 | 6 | 16.9 | yes | 25 / 30 | 0 | 10000000 | 9707803 | 141209 | 16960 | 31 | 81 | 299991 |
| Q2 l_suppkey, join | roaring, level 3 | 6 | 16.4 | yes | 25 / 30 | 0 | 10000000 | 9708257 | 141231 | 17027 | 31 | 81 | 299994 |
| Q3 l_partkey, join | warm-up (roaring, level 3), discarded | 0 | 44.5 | | 0 / 30 | 0 | 59986052 | 55712204 | 2067194 | 26338 | 412 | 1224 | 5991936 |
| Q3 l_partkey, join | Baleen off | 1 | 145.9 | yes | |||||||||
| Q3 l_partkey, join | hash, level 2 | 1 | 123.5 | yes | 0 / 30 | 0 | 59986052 | 29319002 | 14852568 | 34380 | 204 | 612 | 3024873 |
| Q3 l_partkey, join | hash, level 3 | 1 | 125.9 | yes | 0 / 30 | 0 | 59986052 | 29318723 | 14851887 | 33131 | 203 | 609 | 3022827 |
| Q3 l_partkey, join | flat, level 2 | 1 | 43.6 | yes | 0 / 30 | 0 | 59986052 | 55740123 | 2054078 | 15861 | 411 | 1221 | 5999067 |
| Q3 l_partkey, join | flat, level 3 | 1 | 46.3 | yes | 0 / 30 | 0 | 59986052 | 55739084 | 2054652 | 16267 | 410 | 1218 | 5996037 |
| Q3 l_partkey, join | roaring, level 2 | 1 | 46.0 | yes | 0 / 30 | 0 | 59986052 | 55716705 | 2065258 | 24819 | 412 | 1224 | 5994093 |
| Q3 l_partkey, join | roaring, level 3 | 1 | 47.6 | yes | 0 / 30 | 0 | 59986052 | 55714864 | 2065861 | 25348 | 412 | 1224 | 5993211 |
| Q3 l_partkey, join | Baleen off | 2 | 141.4 | yes | |||||||||
| Q3 l_partkey, join | hash, level 2 | 2 | 124.1 | yes | 0 / 30 | 0 | 59986052 | 29318954 | 14851712 | 32620 | 204 | 612 | 3024375 |
| Q3 l_partkey, join | hash, level 3 | 2 | 131.7 | yes | 0 / 30 | 0 | 59986052 | 29320537 | 14850996 | 35010 | 204 | 612 | 3026205 |
| Q3 l_partkey, join | flat, level 2 | 2 | 44.2 | yes | 0 / 30 | 0 | 59986052 | 55739507 | 2054163 | 15997 | 411 | 1221 | 5998380 |
| Q3 l_partkey, join | flat, level 3 | 2 | 44.8 | yes | 0 / 30 | 0 | 59986052 | 55739140 | 2054455 | 16055 | 411 | 1221 | 5999190 |
| Q3 l_partkey, join | roaring, level 2 | 2 | 47.1 | yes | 0 / 30 | 0 | 59986052 | 55718452 | 2064136 | 23941 | 412 | 1224 | 5995119 |
| Q3 l_partkey, join | roaring, level 3 | 2 | 44.4 | yes | 0 / 30 | 0 | 59986052 | 55713908 | 2066498 | 25445 | 412 | 1224 | 5992389 |
| Q3 l_partkey, join | Baleen off | 3 | 138.9 | yes | |||||||||
| Q3 l_partkey, join | hash, level 2 | 3 | 128.2 | yes | 0 / 30 | 0 | 59986052 | 29318704 | 14852305 | 33035 | 204 | 612 | 3024552 |
| Q3 l_partkey, join | hash, level 3 | 3 | 122.3 | yes | 0 / 30 | 0 | 59986052 | 29319466 | 14851479 | 29122 | 204 | 612 | 3020754 |
| Q3 l_partkey, join | flat, level 2 | 3 | 44.5 | yes | 0 / 30 | 0 | 59986052 | 55739596 | 2054214 | 15795 | 411 | 1221 | 5998695 |
| Q3 l_partkey, join | flat, level 3 | 3 | 42.0 | yes | 0 / 30 | 0 | 59986052 | 55738099 | 2054821 | 16459 | 410 | 1218 | 5995311 |
| Q3 l_partkey, join | roaring, level 2 | 3 | 46.7 | yes | 0 / 30 | 0 | 59986052 | 55713580 | 2066731 | 25778 | 412 | 1224 | 5992749 |
| Q3 l_partkey, join | roaring, level 3 | 3 | 47.7 | yes | 0 / 30 | 0 | 59986052 | 55714256 | 2066427 | 25475 | 412 | 1224 | 5993178 |
| Q3 l_partkey, join | Baleen off | 4 | 140.1 | yes | |||||||||
| Q3 l_partkey, join | hash, level 2 | 4 | 124.9 | yes | 0 / 30 | 0 | 59986052 | 29318893 | 14852236 | 30373 | 204 | 612 | 3022833 |
| Q3 l_partkey, join | hash, level 3 | 4 | 124.6 | yes | 0 / 30 | 0 | 59986052 | 29319511 | 14852313 | 31554 | 204 | 612 | 3021915 |
| Q3 l_partkey, join | flat, level 2 | 4 | 44.1 | yes | 0 / 30 | 0 | 59986052 | 55738137 | 2054727 | 16235 | 411 | 1221 | 5999271 |
| Q3 l_partkey, join | flat, level 3 | 4 | 43.5 | yes | 0 / 30 | 0 | 59986052 | 55737852 | 2054892 | 16490 | 410 | 1218 | 5994939 |
| Q3 l_partkey, join | roaring, level 2 | 4 | 48.2 | yes | 0 / 30 | 0 | 59986052 | 55717418 | 2064677 | 24405 | 412 | 1224 | 5995701 |
| Q3 l_partkey, join | roaring, level 3 | 4 | 49.6 | yes | 0 / 30 | 0 | 59986052 | 55715197 | 2065725 | 25022 | 412 | 1224 | 5994675 |
| Q3 l_partkey, join | Baleen off | 5 | 177.6 | yes | |||||||||
| Q3 l_partkey, join | hash, level 2 | 5 | 127.4 | yes | 0 / 30 | 0 | 59986052 | 29319038 | 14852082 | 36834 | 204 | 612 | 3022908 |
| Q3 l_partkey, join | hash, level 3 | 5 | 123.6 | yes | 0 / 30 | 0 | 59986052 | 29319420 | 14851919 | 28854 | 204 | 612 | 3020661 |
| Q3 l_partkey, join | flat, level 2 | 5 | 47.8 | yes | 0 / 30 | 0 | 59986052 | 55739843 | 2054152 | 15781 | 411 | 1221 | 5998938 |
| Q3 l_partkey, join | flat, level 3 | 5 | 42.7 | yes | 0 / 30 | 0 | 59986052 | 55737958 | 2055055 | 16471 | 411 | 1221 | 5999145 |
| Q3 l_partkey, join | roaring, level 2 | 5 | 44.5 | yes | 0 / 30 | 0 | 59986052 | 55713006 | 2067007 | 25771 | 412 | 1224 | 5992554 |
| Q3 l_partkey, join | roaring, level 3 | 5 | 44.4 | yes | 0 / 30 | 0 | 59986052 | 55713387 | 2067010 | 26294 | 412 | 1224 | 5992413 |
| Q3 l_partkey, join | Baleen off | 6 | 144.8 | yes | |||||||||
| Q3 l_partkey, join | hash, level 2 | 6 | 129.5 | yes | 0 / 30 | 0 | 59986052 | 29319538 | 14851603 | 31038 | 204 | 612 | 3022578 |
| Q3 l_partkey, join | hash, level 3 | 6 | 125.0 | yes | 0 / 30 | 0 | 59986052 | 29318921 | 14851982 | 31473 | 203 | 609 | 3021645 |
| Q3 l_partkey, join | flat, level 2 | 6 | 46.5 | yes | 0 / 30 | 0 | 59986052 | 55737933 | 2054815 | 16221 | 411 | 1221 | 5998926 |
| Q3 l_partkey, join | flat, level 3 | 6 | 46.7 | yes | 0 / 30 | 0 | 59986052 | 55738622 | 2054329 | 16079 | 411 | 1221 | 5999367 |
| Q3 l_partkey, join | roaring, level 2 | 6 | 45.2 | yes | 0 / 30 | 0 | 59986052 | 55713100 | 2067071 | 26318 | 412 | 1224 | 5991606 |
| Q3 l_partkey, join | roaring, level 3 | 6 | 48.9 | yes | 0 / 30 | 0 | 59986052 | 55714810 | 2066155 | 25587 | 412 | 1224 | 5992344 |
| Q4 l_shipmode, no join | warm-up (roaring, level 3), discarded | 0 | 2.7 | | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | Baleen off | 1 | 403.7 | yes | |||||||||
| Q4 l_shipmode, no join | hash, level 2 | 1 | 18.7 | yes | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 3 | 1 | 3.1 | yes | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 2 | 1 | 19.3 | yes | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 3 | 1 | 2.7 | yes | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 2 | 1 | 19.8 | yes | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 3 | 1 | 3.0 | yes | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | Baleen off | 2 | 393.2 | yes | |||||||||
| Q4 l_shipmode, no join | hash, level 2 | 2 | 18.6 | yes | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 3 | 2 | 3.0 | yes | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 2 | 2 | 18.3 | yes | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 3 | 2 | 3.1 | yes | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 2 | 2 | 18.7 | yes | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 3 | 2 | 3.1 | yes | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | Baleen off | 3 | 408.6 | yes | |||||||||
| Q4 l_shipmode, no join | hash, level 2 | 3 | 19.5 | yes | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 3 | 3 | 3.0 | yes | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 2 | 3 | 19.0 | yes | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 3 | 3 | 3.1 | yes | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 2 | 3 | 20.3 | yes | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 3 | 3 | 2.6 | yes | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | Baleen off | 4 | 396.9 | yes | |||||||||
| Q4 l_shipmode, no join | hash, level 2 | 4 | 20.2 | yes | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 3 | 4 | 2.6 | yes | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 2 | 4 | 18.4 | yes | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 3 | 4 | 3.0 | yes | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 2 | 4 | 18.1 | yes | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 3 | 4 | 3.0 | yes | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | Baleen off | 5 | 403.2 | yes | |||||||||
| Q4 l_shipmode, no join | hash, level 2 | 5 | 20.0 | yes | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 3 | 5 | 2.9 | yes | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 2 | 5 | 18.9 | yes | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 3 | 5 | 2.9 | yes | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 2 | 5 | 19.9 | yes | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 3 | 5 | 3.4 | yes | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | Baleen off | 6 | 401.8 | yes | |||||||||
| Q4 l_shipmode, no join | hash, level 2 | 6 | 19.7 | yes | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 3 | 6 | 2.8 | yes | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 2 | 6 | 19.6 | yes | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 3 | 6 | 2.8 | yes | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 2 | 6 | 21.4 | yes | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 3 | 6 | 2.9 | yes | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |

## Decisions made without asking

- Matrix: seen-set x level {2, 3} only, flush and propagation at their defaults.
- Warm-up configuration: roaring, level 3.
- The two table drops were run while the benchmark was in progress.
- Summary counters are taken from the upper-median run, not averaged.

## Stated but not verified

- CC cleanup after a job ends: no counter exists for it, not checked.
- Why the Q2 file skip varies between 21 and 26: the timing of propagated values is the likely cause, not measured.
- Why level 3 does nothing on Q2 and Q3 (no dictionary, or value list over the limit): not separated.
- The slow Baleen-off runs on Q2 and Q3: cause unknown.
- The comparison with 12.2 s on the shared seen-set build is across two days and two server starts.
