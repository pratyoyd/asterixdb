# Baleen DISTINCT filter: uniform benchmark (2026-11-17)

Every query with every seen-set at every level, 6 runs each, and 6 runs with Baleen off. 312 runs in total, started 2026-11-16 20:30 and finished 2026-11-17 00:51. This is the state before the flush change described in `2026_11_16_PLAQUE_DISTINCT_FLUSH_PLAN.md`.

Every one of the 288 Baleen runs returned the same result as Baleen off.

## Setup

- sensoria-2, one JVM with 4 node controllers (AsterixHyracksIntegrationUtil), so the seen-set is shared by all partitions without any messages.
- Dataverse `baleen_ice`, Iceberg tables on S3Mock with a Nessie catalog. `lineitem` has 59,986,052 rows in 30 Parquet files, unsorted and unpartitioned.
- Server log: `/scratch/asterixdb_eightynode/build_logs/asterix_distinct_bench3.log`. Script: `/scratch/asterixdb_eightynode/build_logs/bench_distinct.py`. Per-run extraction: `/scratch/asterixdb_eightynode/build_logs/per_run_distinct.py`.
- Code: branch `baleen`, uncommitted, with the two changes made on 2026-11-16 after the first run (seen-set gets the column range at every level; no-join observer fix).

## Queries

Baleen off is the query alone. Each Baleen run puts these three statements in front of it, with `<set>` one of `hash`, `flat`, `roaring` and `<level>` one of `0`, `1`, `2`, `3`:

```sql
USE baleen_ice;
SET `compiler.plaque.enabled` "true";
SET `compiler.plaque.distinct.set` "<set>";
SET `compiler.plaque.distinct.level` "<level>";
```

Q1:
```sql
SELECT DISTINCT l.l_shipmode FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
```
Q2:
```sql
SELECT DISTINCT l.l_suppkey FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
```
Q3:
```sql
SELECT DISTINCT l.l_partkey FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
```
Q4:
```sql
SELECT DISTINCT l.l_shipmode FROM lineitem l;
```

## Limits built into the code that shape these numbers

| Limit | Value | Effect here |
|---|---|---|
| Hash seen-set size | 1,000,000 values | Q3 has 1,999,998 values; beyond the cap the hash set stops recording and later duplicates pass through to the sort. |
| Hash seen-set file test | passes only when a file's lower bound equals its upper bound | Hash never skips a file on any of these queries. |
| Flat bitmap range | 16,000,000 | Not reached. |
| File skip (level 2) | BIGINT columns only, null count 0, every integer in the file's bounds seen | Does not apply to `l_shipmode`. Can never pass on `l_partkey`: the bounds span 1 to 2,000,000 and the answer has 1,999,998 values. |
| Row-group skip (level 3) | BIGINT and string, NOT IN list of at most 10,000 values, needs a dictionary-encoded column | Only `l_shipmode` is dictionary-encoded in these files. On `l_suppkey` and `l_partkey` level 3 is level 2 again. |
| Tuple filter (level 1 and up) | inserted only when there is an operator between scan and observer | Q4 has no join, so no tuple filter is inserted. Levels 0, 1, 2 of Q4 are the same plan. |

## Summary table

Counters in this table are those of the run with the median time. Every run's counters are in the per-run table below.

| Query | Variant | Runs (s) | Median (s) | Speedup | Result = off | Files skipped / total | Files read with row-group filter | Rows out of scan | Dropped by tuple filter | Rows into observer | Dropped by observer |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Q1 l_shipmode, join | Baleen off | 212.8 213.5 209.1 212.5 237.5 204.3 | 212.7 | 1.00x | 7 rows | | | | | | |
| Q1 l_shipmode, join | hash, level 0 | 37.7 36.0 38.2 38.2 39.1 38.2 | 38.2 | 5.57x | yes | 0 / 30 | 0 | 0 | 0 | 29058120 | 29058113 |
| Q1 l_shipmode, join | hash, level 1 | 34.4 36.5 35.6 36.5 34.7 34.7 | 35.1 | 6.05x | yes | 0 / 30 | 0 | 59986052 | 59951246 | 16713 | 16706 |
| Q1 l_shipmode, join | hash, level 2 | 36.7 36.5 37.2 35.5 33.8 36.0 | 36.2 | 5.87x | yes | 0 / 30 | 0 | 59986052 | 59954841 | 14940 | 14933 |
| Q1 l_shipmode, join | hash, level 3 | 12.3 12.5 12.9 12.4 11.8 13.8 | 12.4 | 17.10x | yes | 0 / 30 | 26 | 8000000 | 7963992 | 17465 | 17458 |
| Q1 l_shipmode, join | flat, level 0 | 35.8 38.6 36.5 37.3 37.1 40.4 | 37.2 | 5.71x | yes | 0 / 30 | 0 | 0 | 0 | 29058120 | 29058113 |
| Q1 l_shipmode, join | flat, level 1 | 34.8 36.2 36.4 36.1 37.1 36.9 | 36.3 | 5.86x | yes | 0 / 30 | 0 | 59986052 | 59948785 | 17788 | 17781 |
| Q1 l_shipmode, join | flat, level 2 | 34.2 36.4 34.6 35.6 37.4 35.3 | 35.4 | 6.00x | yes | 0 / 30 | 0 | 59986052 | 59947647 | 18545 | 18538 |
| Q1 l_shipmode, join | flat, level 3 | 12.8 12.6 11.9 13.4 12.6 12.5 | 12.6 | 16.89x | yes | 0 / 30 | 26 | 8000000 | 7960393 | 19096 | 19089 |
| Q1 l_shipmode, join | roaring, level 0 | 35.5 36.4 38.1 37.8 36.0 36.8 | 36.6 | 5.81x | yes | 0 / 30 | 0 | 0 | 0 | 29058120 | 29058113 |
| Q1 l_shipmode, join | roaring, level 1 | 36.5 37.0 34.3 34.8 38.9 36.8 | 36.6 | 5.80x | yes | 0 / 30 | 0 | 59986052 | 59946446 | 19058 | 19051 |
| Q1 l_shipmode, join | roaring, level 2 | 35.9 33.2 33.7 32.8 34.0 34.7 | 33.9 | 6.28x | yes | 0 / 30 | 0 | 59986052 | 59954843 | 15199 | 15192 |
| Q1 l_shipmode, join | roaring, level 3 | 12.2 11.5 13.0 12.9 12.7 12.8 | 12.7 | 16.73x | yes | 0 / 30 | 26 | 8000000 | 7966392 | 16184 | 16177 |
| Q2 l_suppkey, join | Baleen off | 156.9 158.7 156.7 157.6 158.7 159.2 | 158.2 | 1.00x | 100000 rows | | | | | | |
| Q2 l_suppkey, join | hash, level 0 | 36.3 35.1 34.6 36.0 35.2 36.5 | 35.6 | 4.44x | yes | 0 / 30 | 0 | 0 | 0 | 29058120 | 28958120 |
| Q2 l_suppkey, join | hash, level 1 | 41.5 43.2 41.6 39.7 43.4 42.2 | 41.9 | 3.78x | yes | 0 / 30 | 0 | 59986052 | 59670707 | 152599 | 52599 |
| Q2 l_suppkey, join | hash, level 2 | 41.0 42.7 40.5 41.7 43.9 42.7 | 42.2 | 3.75x | yes | 0 / 30 | 0 | 59986052 | 59672118 | 151689 | 51689 |
| Q2 l_suppkey, join | hash, level 3 | 40.9 42.6 42.3 42.6 43.3 43.0 | 42.6 | 3.71x | yes | 0 / 30 | 0 | 59986052 | 59663094 | 156130 | 56130 |
| Q2 l_suppkey, join | flat, level 0 | 33.9 34.5 37.0 34.9 36.4 36.5 | 35.6 | 4.44x | yes | 0 / 30 | 0 | 0 | 0 | 29058120 | 28958120 |
| Q2 l_suppkey, join | flat, level 1 | 36.6 33.2 33.7 34.6 34.7 35.4 | 34.7 | 4.56x | yes | 0 / 30 | 0 | 59986052 | 59667401 | 154038 | 54038 |
| Q2 l_suppkey, join | flat, level 2 | 22.6 23.5 19.7 33.1 17.7 18.4 | 21.2 | 7.47x | yes | 14 / 30 | 0 | 32000000 | 31683746 | 153078 | 53078 |
| Q2 l_suppkey, join | flat, level 3 | 18.8 35.3 19.6 18.2 30.6 33.8 | 25.1 | 6.30x | yes | 2 / 30 | 0 | 56000000 | 55683970 | 152780 | 52780 |
| Q2 l_suppkey, join | roaring, level 0 | 36.4 36.5 36.3 35.0 35.9 37.0 | 36.4 | 4.35x | yes | 0 / 30 | 0 | 0 | 0 | 29058120 | 28958120 |
| Q2 l_suppkey, join | roaring, level 1 | 34.3 33.8 33.9 35.6 35.2 33.3 | 34.1 | 4.64x | yes | 0 / 30 | 0 | 59986052 | 59656637 | 159365 | 59365 |
| Q2 l_suppkey, join | roaring, level 2 | 20.3 17.6 20.6 32.8 18.2 21.0 | 20.4 | 7.75x | yes | 14 / 30 | 0 | 32000000 | 31653750 | 167549 | 67549 |
| Q2 l_suppkey, join | roaring, level 3 | 31.7 32.7 16.5 31.1 15.7 23.4 | 27.2 | 5.81x | yes | 2 / 30 | 0 | 56000000 | 55665434 | 161795 | 61795 |
| Q3 l_partkey, join | Baleen off | 121.7 123.6 125.6 122.0 125.1 122.7 | 123.2 | 1.00x | 1999998 rows | | | | | | |
| Q3 l_partkey, join | hash, level 0 | 90.8 91.3 96.2 113.0 94.4 92.0 | 93.2 | 1.32x | yes | 0 / 30 | 0 | 0 | 0 | 29058120 | 14215243 |
| Q3 l_partkey, join | hash, level 1 | 99.4 105.2 114.6 115.9 101.4 99.2 | 103.3 | 1.19x | yes | 0 / 30 | 0 | 59986052 | 29301883 | 14860239 | 17007 |
| Q3 l_partkey, join | hash, level 2 | 101.3 101.1 101.0 99.3 98.1 96.5 | 100.2 | 1.23x | yes | 0 / 30 | 0 | 59986052 | 29306046 | 14858470 | 15128 |
| Q3 l_partkey, join | hash, level 3 | 100.2 102.1 96.5 109.4 107.6 99.7 | 101.1 | 1.22x | yes | 0 / 30 | 0 | 59986052 | 29305593 | 14858580 | 15336 |
| Q3 l_partkey, join | flat, level 0 | 44.0 43.2 46.5 44.7 45.7 45.5 | 45.1 | 2.73x | yes | 0 / 30 | 0 | 0 | 0 | 29058120 | 27058122 |
| Q3 l_partkey, join | flat, level 1 | 41.3 44.8 40.9 43.5 41.8 42.5 | 42.2 | 2.92x | yes | 0 / 30 | 0 | 59986052 | 55648921 | 2098077 | 98079 |
| Q3 l_partkey, join | flat, level 2 | 42.0 41.1 41.1 43.0 40.1 41.2 | 41.1 | 2.99x | yes | 0 / 30 | 0 | 59986052 | 55663606 | 2091236 | 91238 |
| Q3 l_partkey, join | flat, level 3 | 43.3 44.0 42.5 40.8 39.1 41.9 | 42.2 | 2.92x | yes | 0 / 30 | 0 | 59986052 | 55665444 | 2090301 | 90303 |
| Q3 l_partkey, join | roaring, level 0 | 44.0 43.2 43.6 42.2 42.5 42.9 | 43.0 | 2.86x | yes | 0 / 30 | 0 | 0 | 0 | 29058120 | 27058122 |
| Q3 l_partkey, join | roaring, level 1 | 41.3 41.9 41.6 40.8 40.9 42.7 | 41.4 | 2.97x | yes | 0 / 30 | 0 | 59986052 | 55609184 | 2117039 | 117041 |
| Q3 l_partkey, join | roaring, level 2 | 41.3 42.0 40.9 41.8 41.1 44.4 | 41.5 | 2.97x | yes | 0 / 30 | 0 | 59986052 | 55616989 | 2113720 | 113722 |
| Q3 l_partkey, join | roaring, level 3 | 41.8 40.6 42.1 42.4 41.9 40.9 | 41.8 | 2.94x | yes | 0 / 30 | 0 | 59986052 | 55610612 | 2116679 | 116681 |
| Q4 l_shipmode, no join | Baleen off | 403.9 399.3 396.2 403.4 392.8 404.8 | 401.4 | 1.00x | 7 rows | | | | | | |
| Q4 l_shipmode, no join | hash, level 0 | 18.5 18.8 19.1 19.9 20.3 18.8 | 19.0 | 21.15x | yes | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | hash, level 1 | 18.6 18.3 19.9 19.6 19.7 19.3 | 19.5 | 20.62x | yes | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | hash, level 2 | 18.7 18.9 19.5 19.4 18.9 19.0 | 19.0 | 21.16x | yes | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | hash, level 3 | 2.7 3.0 3.0 2.9 2.8 2.7 | 2.9 | 140.65x | yes | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999993 |
| Q4 l_shipmode, no join | flat, level 0 | 19.5 18.0 19.4 18.9 17.9 18.9 | 18.9 | 21.25x | yes | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | flat, level 1 | 19.0 19.8 18.9 20.0 18.9 18.9 | 19.0 | 21.14x | yes | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | flat, level 2 | 19.8 18.4 19.4 17.9 18.4 19.5 | 18.9 | 21.27x | yes | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | flat, level 3 | 2.6 2.9 3.0 2.9 2.8 2.5 | 2.8 | 142.68x | yes | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999993 |
| Q4 l_shipmode, no join | roaring, level 0 | 20.9 22.1 20.5 21.4 21.2 21.4 | 21.3 | 18.86x | yes | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | roaring, level 1 | 20.9 21.4 20.1 21.1 20.2 22.0 | 21.0 | 19.13x | yes | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | roaring, level 2 | 21.2 21.0 20.1 21.2 22.2 22.1 | 21.2 | 18.95x | yes | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | roaring, level 3 | 3.1 3.3 3.4 3.0 2.8 3.1 | 3.1 | 129.80x | yes | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999993 |

## Every run

Rows out of scan and the tuple-filter column are zero where no tuple filter is in the plan (level 0, and Q4).

| Query | Variant | Run | Time (s) | Files skipped / total | Files read with row-group filter | Rows out of scan | Dropped by tuple filter | Rows into observer | Dropped by observer |
|---|---|---|---|---|---|---|---|---|---|
| Q1 l_shipmode, join | Baleen off | 1 | 212.8 | | | | | | |
| Q1 l_shipmode, join | Baleen off | 2 | 213.5 | | | | | | |
| Q1 l_shipmode, join | Baleen off | 3 | 209.1 | | | | | | |
| Q1 l_shipmode, join | Baleen off | 4 | 212.5 | | | | | | |
| Q1 l_shipmode, join | Baleen off | 5 | 237.5 | | | | | | |
| Q1 l_shipmode, join | Baleen off | 6 | 204.3 | | | | | | |
| Q1 l_shipmode, join | hash, level 0 | 1 | 37.7 | 0 / 30 | 0 | 0 | 0 | 29058120 | 29058113 |
| Q1 l_shipmode, join | hash, level 0 | 2 | 36.0 | 0 / 30 | 0 | 0 | 0 | 29058120 | 29058113 |
| Q1 l_shipmode, join | hash, level 0 | 3 | 38.2 | 0 / 30 | 0 | 0 | 0 | 29058120 | 29058113 |
| Q1 l_shipmode, join | hash, level 0 | 4 | 38.2 | 0 / 30 | 0 | 0 | 0 | 29058120 | 29058113 |
| Q1 l_shipmode, join | hash, level 0 | 5 | 39.1 | 0 / 30 | 0 | 0 | 0 | 29058120 | 29058113 |
| Q1 l_shipmode, join | hash, level 0 | 6 | 38.2 | 0 / 30 | 0 | 0 | 0 | 29058120 | 29058113 |
| Q1 l_shipmode, join | hash, level 1 | 1 | 34.4 | 0 / 30 | 0 | 59986052 | 59948135 | 18315 | 18308 |
| Q1 l_shipmode, join | hash, level 1 | 2 | 36.5 | 0 / 30 | 0 | 59986052 | 59950047 | 17399 | 17392 |
| Q1 l_shipmode, join | hash, level 1 | 3 | 35.6 | 0 / 30 | 0 | 59986052 | 59951246 | 16713 | 16706 |
| Q1 l_shipmode, join | hash, level 1 | 4 | 36.5 | 0 / 30 | 0 | 59986052 | 59947641 | 18512 | 18505 |
| Q1 l_shipmode, join | hash, level 1 | 5 | 34.7 | 0 / 30 | 0 | 59986052 | 59950042 | 17212 | 17205 |
| Q1 l_shipmode, join | hash, level 1 | 6 | 34.7 | 0 / 30 | 0 | 59986052 | 59951244 | 16621 | 16614 |
| Q1 l_shipmode, join | hash, level 2 | 1 | 36.7 | 0 / 30 | 0 | 59986052 | 59947647 | 18581 | 18574 |
| Q1 l_shipmode, join | hash, level 2 | 2 | 36.5 | 0 / 30 | 0 | 59986052 | 59954841 | 14940 | 14933 |
| Q1 l_shipmode, join | hash, level 2 | 3 | 37.2 | 0 / 30 | 0 | 59986052 | 59954846 | 15146 | 15139 |
| Q1 l_shipmode, join | hash, level 2 | 4 | 35.5 | 0 / 30 | 0 | 59986052 | 59950042 | 17295 | 17288 |
| Q1 l_shipmode, join | hash, level 2 | 5 | 33.8 | 0 / 30 | 0 | 59986052 | 59948845 | 17903 | 17896 |
| Q1 l_shipmode, join | hash, level 2 | 6 | 36.0 | 0 / 30 | 0 | 59986052 | 59956044 | 14526 | 14519 |
| Q1 l_shipmode, join | hash, level 3 | 1 | 12.3 | 0 / 30 | 26 | 8000000 | 7965194 | 16851 | 16844 |
| Q1 l_shipmode, join | hash, level 3 | 2 | 12.5 | 0 / 30 | 26 | 8000000 | 7963992 | 17465 | 17458 |
| Q1 l_shipmode, join | hash, level 3 | 3 | 12.9 | 0 / 30 | 26 | 8000000 | 7965842 | 16429 | 16422 |
| Q1 l_shipmode, join | hash, level 3 | 4 | 12.4 | 0 / 30 | 26 | 8000000 | 7962790 | 17948 | 17941 |
| Q1 l_shipmode, join | hash, level 3 | 5 | 11.8 | 0 / 30 | 26 | 8000000 | 7967590 | 15593 | 15586 |
| Q1 l_shipmode, join | hash, level 3 | 6 | 13.8 | 0 / 30 | 26 | 8000000 | 7961594 | 18555 | 18548 |
| Q1 l_shipmode, join | flat, level 0 | 1 | 35.8 | 0 / 30 | 0 | 0 | 0 | 29058120 | 29058113 |
| Q1 l_shipmode, join | flat, level 0 | 2 | 38.6 | 0 / 30 | 0 | 0 | 0 | 29058120 | 29058113 |
| Q1 l_shipmode, join | flat, level 0 | 3 | 36.5 | 0 / 30 | 0 | 0 | 0 | 29058120 | 29058113 |
| Q1 l_shipmode, join | flat, level 0 | 4 | 37.3 | 0 / 30 | 0 | 0 | 0 | 29058120 | 29058113 |
| Q1 l_shipmode, join | flat, level 0 | 5 | 37.1 | 0 / 30 | 0 | 0 | 0 | 29058120 | 29058113 |
| Q1 l_shipmode, join | flat, level 0 | 6 | 40.4 | 0 / 30 | 0 | 0 | 0 | 29058120 | 29058113 |
| Q1 l_shipmode, join | flat, level 1 | 1 | 34.8 | 0 / 30 | 0 | 59986052 | 59953137 | 15775 | 15768 |
| Q1 l_shipmode, join | flat, level 1 | 2 | 36.2 | 0 / 30 | 0 | 59986052 | 59947638 | 18387 | 18380 |
| Q1 l_shipmode, join | flat, level 1 | 3 | 36.4 | 0 / 30 | 0 | 59986052 | 59948785 | 17788 | 17781 |
| Q1 l_shipmode, join | flat, level 1 | 4 | 36.1 | 0 / 30 | 0 | 59986052 | 59950046 | 17426 | 17419 |
| Q1 l_shipmode, join | flat, level 1 | 5 | 37.1 | 0 / 30 | 0 | 59986052 | 59947641 | 18420 | 18413 |
| Q1 l_shipmode, join | flat, level 1 | 6 | 36.9 | 0 / 30 | 0 | 59986052 | 59952445 | 16174 | 16167 |
| Q1 l_shipmode, join | flat, level 2 | 1 | 34.2 | 0 / 30 | 0 | 59986052 | 59951955 | 16341 | 16334 |
| Q1 l_shipmode, join | flat, level 2 | 2 | 36.4 | 0 / 30 | 0 | 59986052 | 59947646 | 18529 | 18522 |
| Q1 l_shipmode, join | flat, level 2 | 3 | 34.6 | 0 / 30 | 0 | 59986052 | 59948843 | 17827 | 17820 |
| Q1 l_shipmode, join | flat, level 2 | 4 | 35.6 | 0 / 30 | 0 | 59986052 | 59947647 | 18545 | 18538 |
| Q1 l_shipmode, join | flat, level 2 | 5 | 37.4 | 0 / 30 | 0 | 59986052 | 59944382 | 20125 | 20118 |
| Q1 l_shipmode, join | flat, level 2 | 6 | 35.3 | 0 / 30 | 0 | 59986052 | 59946446 | 19125 | 19118 |
| Q1 l_shipmode, join | flat, level 3 | 1 | 12.8 | 0 / 30 | 26 | 8000000 | 7965187 | 16810 | 16803 |
| Q1 l_shipmode, join | flat, level 3 | 2 | 12.6 | 0 / 30 | 26 | 8000000 | 7960393 | 19096 | 19089 |
| Q1 l_shipmode, join | flat, level 3 | 3 | 11.9 | 0 / 30 | 26 | 8000000 | 7963990 | 17417 | 17410 |
| Q1 l_shipmode, join | flat, level 3 | 4 | 13.4 | 0 / 30 | 26 | 8000000 | 7963991 | 17451 | 17444 |
| Q1 l_shipmode, join | flat, level 3 | 5 | 12.6 | 0 / 30 | 26 | 8000000 | 7969997 | 14382 | 14375 |
| Q1 l_shipmode, join | flat, level 3 | 6 | 12.5 | 0 / 30 | 26 | 8000000 | 7965187 | 16748 | 16741 |
| Q1 l_shipmode, join | roaring, level 0 | 1 | 35.5 | 0 / 30 | 0 | 0 | 0 | 29058120 | 29058113 |
| Q1 l_shipmode, join | roaring, level 0 | 2 | 36.4 | 0 / 30 | 0 | 0 | 0 | 29058120 | 29058113 |
| Q1 l_shipmode, join | roaring, level 0 | 3 | 38.1 | 0 / 30 | 0 | 0 | 0 | 29058120 | 29058113 |
| Q1 l_shipmode, join | roaring, level 0 | 4 | 37.8 | 0 / 30 | 0 | 0 | 0 | 29058120 | 29058113 |
| Q1 l_shipmode, join | roaring, level 0 | 5 | 36.0 | 0 / 30 | 0 | 0 | 0 | 29058120 | 29058113 |
| Q1 l_shipmode, join | roaring, level 0 | 6 | 36.8 | 0 / 30 | 0 | 0 | 0 | 29058120 | 29058113 |
| Q1 l_shipmode, join | roaring, level 1 | 1 | 36.5 | 0 / 30 | 0 | 59986052 | 59947647 | 18519 | 18512 |
| Q1 l_shipmode, join | roaring, level 1 | 2 | 37.0 | 0 / 30 | 0 | 59986052 | 59952449 | 16258 | 16251 |
| Q1 l_shipmode, join | roaring, level 1 | 3 | 34.3 | 0 / 30 | 0 | 59986052 | 59950045 | 17226 | 17219 |
| Q1 l_shipmode, join | roaring, level 1 | 4 | 34.8 | 0 / 30 | 0 | 59986052 | 59950042 | 17285 | 17278 |
| Q1 l_shipmode, join | roaring, level 1 | 5 | 38.9 | 0 / 30 | 0 | 59986052 | 59952447 | 16076 | 16069 |
| Q1 l_shipmode, join | roaring, level 1 | 6 | 36.8 | 0 / 30 | 0 | 59986052 | 59946446 | 19058 | 19051 |
| Q1 l_shipmode, join | roaring, level 2 | 1 | 35.9 | 0 / 30 | 0 | 59986052 | 59954841 | 15119 | 15112 |
| Q1 l_shipmode, join | roaring, level 2 | 2 | 33.2 | 0 / 30 | 0 | 59986052 | 59952818 | 15944 | 15937 |
| Q1 l_shipmode, join | roaring, level 2 | 3 | 33.7 | 0 / 30 | 0 | 59986052 | 59948844 | 18049 | 18042 |
| Q1 l_shipmode, join | roaring, level 2 | 4 | 32.8 | 0 / 30 | 0 | 59986052 | 59945243 | 19635 | 19628 |
| Q1 l_shipmode, join | roaring, level 2 | 5 | 34.0 | 0 / 30 | 0 | 59986052 | 59954843 | 15199 | 15192 |
| Q1 l_shipmode, join | roaring, level 2 | 6 | 34.7 | 0 / 30 | 0 | 59986052 | 59946440 | 18990 | 18983 |
| Q1 l_shipmode, join | roaring, level 3 | 1 | 12.2 | 0 / 30 | 26 | 8000000 | 7960807 | 18765 | 18758 |
| Q1 l_shipmode, join | roaring, level 3 | 2 | 11.5 | 0 / 30 | 26 | 8000000 | 7966391 | 16078 | 16071 |
| Q1 l_shipmode, join | roaring, level 3 | 3 | 13.0 | 0 / 30 | 26 | 8000000 | 7965193 | 16757 | 16750 |
| Q1 l_shipmode, join | roaring, level 3 | 4 | 12.9 | 0 / 30 | 26 | 8000000 | 7962793 | 17865 | 17858 |
| Q1 l_shipmode, join | roaring, level 3 | 5 | 12.7 | 0 / 30 | 26 | 8000000 | 7963991 | 17365 | 17358 |
| Q1 l_shipmode, join | roaring, level 3 | 6 | 12.8 | 0 / 30 | 26 | 8000000 | 7966392 | 16184 | 16177 |
| Q2 l_suppkey, join | Baleen off | 1 | 156.9 | | | | | | |
| Q2 l_suppkey, join | Baleen off | 2 | 158.7 | | | | | | |
| Q2 l_suppkey, join | Baleen off | 3 | 156.7 | | | | | | |
| Q2 l_suppkey, join | Baleen off | 4 | 157.6 | | | | | | |
| Q2 l_suppkey, join | Baleen off | 5 | 158.7 | | | | | | |
| Q2 l_suppkey, join | Baleen off | 6 | 159.2 | | | | | | |
| Q2 l_suppkey, join | hash, level 0 | 1 | 36.3 | 0 / 30 | 0 | 0 | 0 | 29058120 | 28958120 |
| Q2 l_suppkey, join | hash, level 0 | 2 | 35.1 | 0 / 30 | 0 | 0 | 0 | 29058120 | 28958120 |
| Q2 l_suppkey, join | hash, level 0 | 3 | 34.6 | 0 / 30 | 0 | 0 | 0 | 29058120 | 28958120 |
| Q2 l_suppkey, join | hash, level 0 | 4 | 36.0 | 0 / 30 | 0 | 0 | 0 | 29058120 | 28958120 |
| Q2 l_suppkey, join | hash, level 0 | 5 | 35.2 | 0 / 30 | 0 | 0 | 0 | 29058120 | 28958120 |
| Q2 l_suppkey, join | hash, level 0 | 6 | 36.5 | 0 / 30 | 0 | 0 | 0 | 29058120 | 28958120 |
| Q2 l_suppkey, join | hash, level 1 | 1 | 41.5 | 0 / 30 | 0 | 59986052 | 59671040 | 152409 | 52409 |
| Q2 l_suppkey, join | hash, level 1 | 2 | 43.2 | 0 / 30 | 0 | 59986052 | 59665339 | 154957 | 54957 |
| Q2 l_suppkey, join | hash, level 1 | 3 | 41.6 | 0 / 30 | 0 | 59986052 | 59666370 | 154501 | 54501 |
| Q2 l_suppkey, join | hash, level 1 | 4 | 39.7 | 0 / 30 | 0 | 59986052 | 59669336 | 152974 | 52974 |
| Q2 l_suppkey, join | hash, level 1 | 5 | 43.4 | 0 / 30 | 0 | 59986052 | 59666716 | 154480 | 54480 |
| Q2 l_suppkey, join | hash, level 1 | 6 | 42.2 | 0 / 30 | 0 | 59986052 | 59670707 | 152599 | 52599 |
| Q2 l_suppkey, join | hash, level 2 | 1 | 41.0 | 0 / 30 | 0 | 59986052 | 59671430 | 152368 | 52368 |
| Q2 l_suppkey, join | hash, level 2 | 2 | 42.7 | 0 / 30 | 0 | 59986052 | 59669699 | 152975 | 52975 |
| Q2 l_suppkey, join | hash, level 2 | 3 | 40.5 | 0 / 30 | 0 | 59986052 | 59661427 | 157001 | 57001 |
| Q2 l_suppkey, join | hash, level 2 | 4 | 41.7 | 0 / 30 | 0 | 59986052 | 59671833 | 152045 | 52045 |
| Q2 l_suppkey, join | hash, level 2 | 5 | 43.9 | 0 / 30 | 0 | 59986052 | 59672384 | 151594 | 51594 |
| Q2 l_suppkey, join | hash, level 2 | 6 | 42.7 | 0 / 30 | 0 | 59986052 | 59672118 | 151689 | 51689 |
| Q2 l_suppkey, join | hash, level 3 | 1 | 40.9 | 0 / 30 | 0 | 59986052 | 59667281 | 154317 | 54317 |
| Q2 l_suppkey, join | hash, level 3 | 2 | 42.6 | 0 / 30 | 0 | 59986052 | 59663094 | 156130 | 56130 |
| Q2 l_suppkey, join | hash, level 3 | 3 | 42.3 | 0 / 30 | 0 | 59986052 | 59670643 | 152529 | 52529 |
| Q2 l_suppkey, join | hash, level 3 | 4 | 42.6 | 0 / 30 | 0 | 59986052 | 59673379 | 151200 | 51200 |
| Q2 l_suppkey, join | hash, level 3 | 5 | 43.3 | 0 / 30 | 0 | 59986052 | 59671661 | 151834 | 51834 |
| Q2 l_suppkey, join | hash, level 3 | 6 | 43.0 | 0 / 30 | 0 | 59986052 | 59664042 | 155753 | 55753 |
| Q2 l_suppkey, join | flat, level 0 | 1 | 33.9 | 0 / 30 | 0 | 0 | 0 | 29058120 | 28958120 |
| Q2 l_suppkey, join | flat, level 0 | 2 | 34.5 | 0 / 30 | 0 | 0 | 0 | 29058120 | 28958120 |
| Q2 l_suppkey, join | flat, level 0 | 3 | 37.0 | 0 / 30 | 0 | 0 | 0 | 29058120 | 28958120 |
| Q2 l_suppkey, join | flat, level 0 | 4 | 34.9 | 0 / 30 | 0 | 0 | 0 | 29058120 | 28958120 |
| Q2 l_suppkey, join | flat, level 0 | 5 | 36.4 | 0 / 30 | 0 | 0 | 0 | 29058120 | 28958120 |
| Q2 l_suppkey, join | flat, level 0 | 6 | 36.5 | 0 / 30 | 0 | 0 | 0 | 29058120 | 28958120 |
| Q2 l_suppkey, join | flat, level 1 | 1 | 36.6 | 0 / 30 | 0 | 59986052 | 59670050 | 152692 | 52692 |
| Q2 l_suppkey, join | flat, level 1 | 2 | 33.2 | 0 / 30 | 0 | 59986052 | 59671797 | 152001 | 52001 |
| Q2 l_suppkey, join | flat, level 1 | 3 | 33.7 | 0 / 30 | 0 | 59986052 | 59668489 | 153561 | 53561 |
| Q2 l_suppkey, join | flat, level 1 | 4 | 34.6 | 0 / 30 | 0 | 59986052 | 59667301 | 154215 | 54215 |
| Q2 l_suppkey, join | flat, level 1 | 5 | 34.7 | 0 / 30 | 0 | 59986052 | 59667401 | 154038 | 54038 |
| Q2 l_suppkey, join | flat, level 1 | 6 | 35.4 | 0 / 30 | 0 | 59986052 | 59666957 | 154169 | 54169 |
| Q2 l_suppkey, join | flat, level 2 | 1 | 22.6 | 14 / 30 | 0 | 32000000 | 31683746 | 153078 | 53078 |
| Q2 l_suppkey, join | flat, level 2 | 2 | 23.5 | 10 / 30 | 0 | 40000000 | 39683057 | 153182 | 53182 |
| Q2 l_suppkey, join | flat, level 2 | 3 | 19.7 | 17 / 30 | 0 | 26000000 | 25683120 | 153294 | 53294 |
| Q2 l_suppkey, join | flat, level 2 | 4 | 33.1 | 0 / 30 | 0 | 59986052 | 59667179 | 153993 | 53993 |
| Q2 l_suppkey, join | flat, level 2 | 5 | 17.7 | 18 / 30 | 0 | 24000000 | 23684082 | 152820 | 52820 |
| Q2 l_suppkey, join | flat, level 2 | 6 | 18.4 | 18 / 30 | 0 | 24000000 | 23683564 | 153046 | 53046 |
| Q2 l_suppkey, join | flat, level 3 | 1 | 18.8 | 18 / 30 | 0 | 24000000 | 23686523 | 151586 | 51586 |
| Q2 l_suppkey, join | flat, level 3 | 2 | 35.3 | 0 / 30 | 0 | 59986052 | 59680544 | 147543 | 47543 |
| Q2 l_suppkey, join | flat, level 3 | 3 | 19.6 | 18 / 30 | 0 | 24000000 | 23686769 | 151404 | 51404 |
| Q2 l_suppkey, join | flat, level 3 | 4 | 18.2 | 20 / 30 | 0 | 20000000 | 19687250 | 151025 | 51025 |
| Q2 l_suppkey, join | flat, level 3 | 5 | 30.6 | 2 / 30 | 0 | 56000000 | 55683970 | 152780 | 52780 |
| Q2 l_suppkey, join | flat, level 3 | 6 | 33.8 | 0 / 30 | 0 | 59986052 | 59670536 | 152473 | 52473 |
| Q2 l_suppkey, join | roaring, level 0 | 1 | 36.4 | 0 / 30 | 0 | 0 | 0 | 29058120 | 28958120 |
| Q2 l_suppkey, join | roaring, level 0 | 2 | 36.5 | 0 / 30 | 0 | 0 | 0 | 29058120 | 28958120 |
| Q2 l_suppkey, join | roaring, level 0 | 3 | 36.3 | 0 / 30 | 0 | 0 | 0 | 29058120 | 28958120 |
| Q2 l_suppkey, join | roaring, level 0 | 4 | 35.0 | 0 / 30 | 0 | 0 | 0 | 29058120 | 28958120 |
| Q2 l_suppkey, join | roaring, level 0 | 5 | 35.9 | 0 / 30 | 0 | 0 | 0 | 29058120 | 28958120 |
| Q2 l_suppkey, join | roaring, level 0 | 6 | 37.0 | 0 / 30 | 0 | 0 | 0 | 29058120 | 28958120 |
| Q2 l_suppkey, join | roaring, level 1 | 1 | 34.3 | 0 / 30 | 0 | 59986052 | 59656637 | 159365 | 59365 |
| Q2 l_suppkey, join | roaring, level 1 | 2 | 33.8 | 0 / 30 | 0 | 59986052 | 59653278 | 161182 | 61182 |
| Q2 l_suppkey, join | roaring, level 1 | 3 | 33.9 | 0 / 30 | 0 | 59986052 | 59659224 | 158181 | 58181 |
| Q2 l_suppkey, join | roaring, level 1 | 4 | 35.6 | 0 / 30 | 0 | 59986052 | 59655969 | 159741 | 59741 |
| Q2 l_suppkey, join | roaring, level 1 | 5 | 35.2 | 0 / 30 | 0 | 59986052 | 59661496 | 156833 | 56833 |
| Q2 l_suppkey, join | roaring, level 1 | 6 | 33.3 | 0 / 30 | 0 | 59986052 | 59652920 | 161162 | 61162 |
| Q2 l_suppkey, join | roaring, level 2 | 1 | 20.3 | 14 / 30 | 0 | 32000000 | 31668602 | 160326 | 60326 |
| Q2 l_suppkey, join | roaring, level 2 | 2 | 17.6 | 21 / 30 | 0 | 18000000 | 17666040 | 161351 | 61351 |
| Q2 l_suppkey, join | roaring, level 2 | 3 | 20.6 | 14 / 30 | 0 | 32000000 | 31653750 | 167549 | 67549 |
| Q2 l_suppkey, join | roaring, level 2 | 4 | 32.8 | 1 / 30 | 0 | 58000000 | 57657806 | 165409 | 65409 |
| Q2 l_suppkey, join | roaring, level 2 | 5 | 18.2 | 18 / 30 | 0 | 24000000 | 23667756 | 160523 | 60523 |
| Q2 l_suppkey, join | roaring, level 2 | 6 | 21.0 | 14 / 30 | 0 | 32000000 | 31672076 | 158606 | 58606 |
| Q2 l_suppkey, join | roaring, level 3 | 1 | 31.7 | 0 / 30 | 0 | 59986052 | 59649703 | 162999 | 62999 |
| Q2 l_suppkey, join | roaring, level 3 | 2 | 32.7 | 1 / 30 | 0 | 57986052 | 57662705 | 156344 | 56344 |
| Q2 l_suppkey, join | roaring, level 3 | 3 | 16.5 | 22 / 30 | 0 | 16000000 | 15670235 | 159465 | 59465 |
| Q2 l_suppkey, join | roaring, level 3 | 4 | 31.1 | 2 / 30 | 0 | 56000000 | 55665434 | 161795 | 61795 |
| Q2 l_suppkey, join | roaring, level 3 | 5 | 15.7 | 24 / 30 | 0 | 12000000 | 11668356 | 160350 | 60350 |
| Q2 l_suppkey, join | roaring, level 3 | 6 | 23.4 | 12 / 30 | 0 | 36000000 | 35666460 | 161193 | 61193 |
| Q3 l_partkey, join | Baleen off | 1 | 121.7 | | | | | | |
| Q3 l_partkey, join | Baleen off | 2 | 123.6 | | | | | | |
| Q3 l_partkey, join | Baleen off | 3 | 125.6 | | | | | | |
| Q3 l_partkey, join | Baleen off | 4 | 122.0 | | | | | | |
| Q3 l_partkey, join | Baleen off | 5 | 125.1 | | | | | | |
| Q3 l_partkey, join | Baleen off | 6 | 122.7 | | | | | | |
| Q3 l_partkey, join | hash, level 0 | 1 | 90.8 | 0 / 30 | 0 | 0 | 0 | 29058120 | 14214336 |
| Q3 l_partkey, join | hash, level 0 | 2 | 91.3 | 0 / 30 | 0 | 0 | 0 | 29058120 | 14214613 |
| Q3 l_partkey, join | hash, level 0 | 3 | 96.2 | 0 / 30 | 0 | 0 | 0 | 29058120 | 14214687 |
| Q3 l_partkey, join | hash, level 0 | 4 | 113.0 | 0 / 30 | 0 | 0 | 0 | 29058120 | 14214764 |
| Q3 l_partkey, join | hash, level 0 | 5 | 94.4 | 0 / 30 | 0 | 0 | 0 | 29058120 | 14215243 |
| Q3 l_partkey, join | hash, level 0 | 6 | 92.0 | 0 / 30 | 0 | 0 | 0 | 29058120 | 14214843 |
| Q3 l_partkey, join | hash, level 1 | 1 | 99.4 | 0 / 30 | 0 | 59986052 | 29303986 | 14859243 | 15524 |
| Q3 l_partkey, join | hash, level 1 | 2 | 105.2 | 0 / 30 | 0 | 59986052 | 29301883 | 14860239 | 17007 |
| Q3 l_partkey, join | hash, level 1 | 3 | 114.6 | 0 / 30 | 0 | 59986052 | 29299341 | 14861514 | 18036 |
| Q3 l_partkey, join | hash, level 1 | 4 | 115.9 | 0 / 30 | 0 | 59986052 | 29306948 | 14857235 | 14234 |
| Q3 l_partkey, join | hash, level 1 | 5 | 101.4 | 0 / 30 | 0 | 59986052 | 29302470 | 14860159 | 16385 |
| Q3 l_partkey, join | hash, level 1 | 6 | 99.2 | 0 / 30 | 0 | 59986052 | 29304496 | 14858900 | 15540 |
| Q3 l_partkey, join | hash, level 2 | 1 | 101.3 | 0 / 30 | 0 | 59986052 | 29301070 | 14860332 | 17099 |
| Q3 l_partkey, join | hash, level 2 | 2 | 101.1 | 0 / 30 | 0 | 59986052 | 29307737 | 14858328 | 13969 |
| Q3 l_partkey, join | hash, level 2 | 3 | 101.0 | 0 / 30 | 0 | 59986052 | 29306046 | 14858470 | 15128 |
| Q3 l_partkey, join | hash, level 2 | 4 | 99.3 | 0 / 30 | 0 | 59986052 | 29309192 | 14856253 | 13416 |
| Q3 l_partkey, join | hash, level 2 | 5 | 98.1 | 0 / 30 | 0 | 59986052 | 29305729 | 14858252 | 14848 |
| Q3 l_partkey, join | hash, level 2 | 6 | 96.5 | 0 / 30 | 0 | 59986052 | 29305807 | 14857904 | 14765 |
| Q3 l_partkey, join | hash, level 3 | 1 | 100.2 | 0 / 30 | 0 | 59986052 | 29306559 | 14858027 | 14377 |
| Q3 l_partkey, join | hash, level 3 | 2 | 102.1 | 0 / 30 | 0 | 59986052 | 29305593 | 14858580 | 15336 |
| Q3 l_partkey, join | hash, level 3 | 3 | 96.5 | 0 / 30 | 0 | 59986052 | 29304575 | 14858604 | 15654 |
| Q3 l_partkey, join | hash, level 3 | 4 | 109.4 | 0 / 30 | 0 | 59986052 | 29304102 | 14858937 | 16149 |
| Q3 l_partkey, join | hash, level 3 | 5 | 107.6 | 0 / 30 | 0 | 59986052 | 29304014 | 14859585 | 15814 |
| Q3 l_partkey, join | hash, level 3 | 6 | 99.7 | 0 / 30 | 0 | 59986052 | 29304874 | 14858844 | 15662 |
| Q3 l_partkey, join | flat, level 0 | 1 | 44.0 | 0 / 30 | 0 | 0 | 0 | 29058120 | 27058122 |
| Q3 l_partkey, join | flat, level 0 | 2 | 43.2 | 0 / 30 | 0 | 0 | 0 | 29058120 | 27058122 |
| Q3 l_partkey, join | flat, level 0 | 3 | 46.5 | 0 / 30 | 0 | 0 | 0 | 29058120 | 27058122 |
| Q3 l_partkey, join | flat, level 0 | 4 | 44.7 | 0 / 30 | 0 | 0 | 0 | 29058120 | 27058122 |
| Q3 l_partkey, join | flat, level 0 | 5 | 45.7 | 0 / 30 | 0 | 0 | 0 | 29058120 | 27058122 |
| Q3 l_partkey, join | flat, level 0 | 6 | 45.5 | 0 / 30 | 0 | 0 | 0 | 29058120 | 27058122 |
| Q3 l_partkey, join | flat, level 1 | 1 | 41.3 | 0 / 30 | 0 | 59986052 | 55659457 | 2092736 | 92738 |
| Q3 l_partkey, join | flat, level 1 | 2 | 44.8 | 0 / 30 | 0 | 59986052 | 55664407 | 2090505 | 90507 |
| Q3 l_partkey, join | flat, level 1 | 3 | 40.9 | 0 / 30 | 0 | 59986052 | 55653481 | 2096056 | 96058 |
| Q3 l_partkey, join | flat, level 1 | 4 | 43.5 | 0 / 30 | 0 | 59986052 | 55655678 | 2094975 | 94977 |
| Q3 l_partkey, join | flat, level 1 | 5 | 41.8 | 0 / 30 | 0 | 59986052 | 55655146 | 2095123 | 95125 |
| Q3 l_partkey, join | flat, level 1 | 6 | 42.5 | 0 / 30 | 0 | 59986052 | 55648921 | 2098077 | 98079 |
| Q3 l_partkey, join | flat, level 2 | 1 | 42.0 | 0 / 30 | 0 | 59986052 | 55659332 | 2092727 | 92729 |
| Q3 l_partkey, join | flat, level 2 | 2 | 41.1 | 0 / 30 | 0 | 59986052 | 55656120 | 2094480 | 94482 |
| Q3 l_partkey, join | flat, level 2 | 3 | 41.1 | 0 / 30 | 0 | 59986052 | 55655767 | 2094604 | 94606 |
| Q3 l_partkey, join | flat, level 2 | 4 | 43.0 | 0 / 30 | 0 | 59986052 | 55657241 | 2094179 | 94181 |
| Q3 l_partkey, join | flat, level 2 | 5 | 40.1 | 0 / 30 | 0 | 59986052 | 55652852 | 2096206 | 96208 |
| Q3 l_partkey, join | flat, level 2 | 6 | 41.2 | 0 / 30 | 0 | 59986052 | 55663606 | 2091236 | 91238 |
| Q3 l_partkey, join | flat, level 3 | 1 | 43.3 | 0 / 30 | 0 | 59986052 | 55669975 | 2087854 | 87856 |
| Q3 l_partkey, join | flat, level 3 | 2 | 44.0 | 0 / 30 | 0 | 59986052 | 55657469 | 2093508 | 93510 |
| Q3 l_partkey, join | flat, level 3 | 3 | 42.5 | 0 / 30 | 0 | 59986052 | 55665444 | 2090301 | 90303 |
| Q3 l_partkey, join | flat, level 3 | 4 | 40.8 | 0 / 30 | 0 | 59986052 | 55653470 | 2095906 | 95908 |
| Q3 l_partkey, join | flat, level 3 | 5 | 39.1 | 0 / 30 | 0 | 59986052 | 55654400 | 2095350 | 95352 |
| Q3 l_partkey, join | flat, level 3 | 6 | 41.9 | 0 / 30 | 0 | 59986052 | 55667245 | 2089390 | 89392 |
| Q3 l_partkey, join | roaring, level 0 | 1 | 44.0 | 0 / 30 | 0 | 0 | 0 | 29058120 | 27058122 |
| Q3 l_partkey, join | roaring, level 0 | 2 | 43.2 | 0 / 30 | 0 | 0 | 0 | 29058120 | 27058122 |
| Q3 l_partkey, join | roaring, level 0 | 3 | 43.6 | 0 / 30 | 0 | 0 | 0 | 29058120 | 27058122 |
| Q3 l_partkey, join | roaring, level 0 | 4 | 42.2 | 0 / 30 | 0 | 0 | 0 | 29058120 | 27058122 |
| Q3 l_partkey, join | roaring, level 0 | 5 | 42.5 | 0 / 30 | 0 | 0 | 0 | 29058120 | 27058122 |
| Q3 l_partkey, join | roaring, level 0 | 6 | 42.9 | 0 / 30 | 0 | 0 | 0 | 29058120 | 27058122 |
| Q3 l_partkey, join | roaring, level 1 | 1 | 41.3 | 0 / 30 | 0 | 59986052 | 55601097 | 2121193 | 121195 |
| Q3 l_partkey, join | roaring, level 1 | 2 | 41.9 | 0 / 30 | 0 | 59986052 | 55615264 | 2114350 | 114352 |
| Q3 l_partkey, join | roaring, level 1 | 3 | 41.6 | 0 / 30 | 0 | 59986052 | 55609184 | 2117039 | 117041 |
| Q3 l_partkey, join | roaring, level 1 | 4 | 40.8 | 0 / 30 | 0 | 59986052 | 55587083 | 2128045 | 128047 |
| Q3 l_partkey, join | roaring, level 1 | 5 | 40.9 | 0 / 30 | 0 | 59986052 | 55606830 | 2118331 | 118333 |
| Q3 l_partkey, join | roaring, level 1 | 6 | 42.7 | 0 / 30 | 0 | 59986052 | 55609494 | 2117176 | 117178 |
| Q3 l_partkey, join | roaring, level 2 | 1 | 41.3 | 0 / 30 | 0 | 59986052 | 55603863 | 2119911 | 119913 |
| Q3 l_partkey, join | roaring, level 2 | 2 | 42.0 | 0 / 30 | 0 | 59986052 | 55617066 | 2113317 | 113319 |
| Q3 l_partkey, join | roaring, level 2 | 3 | 40.9 | 0 / 30 | 0 | 59986052 | 55615771 | 2114020 | 114022 |
| Q3 l_partkey, join | roaring, level 2 | 4 | 41.8 | 0 / 30 | 0 | 59986052 | 55616989 | 2113720 | 113722 |
| Q3 l_partkey, join | roaring, level 2 | 5 | 41.1 | 0 / 30 | 0 | 59986052 | 55620999 | 2111655 | 111657 |
| Q3 l_partkey, join | roaring, level 2 | 6 | 44.4 | 0 / 30 | 0 | 59986052 | 55622236 | 2110885 | 110887 |
| Q3 l_partkey, join | roaring, level 3 | 1 | 41.8 | 0 / 30 | 0 | 59986052 | 55615771 | 2114095 | 114097 |
| Q3 l_partkey, join | roaring, level 3 | 2 | 40.6 | 0 / 30 | 0 | 59986052 | 55601930 | 2120809 | 120811 |
| Q3 l_partkey, join | roaring, level 3 | 3 | 42.1 | 0 / 30 | 0 | 59986052 | 55610310 | 2117071 | 117073 |
| Q3 l_partkey, join | roaring, level 3 | 4 | 42.4 | 0 / 30 | 0 | 59986052 | 55623746 | 2110174 | 110176 |
| Q3 l_partkey, join | roaring, level 3 | 5 | 41.9 | 0 / 30 | 0 | 59986052 | 55610612 | 2116679 | 116681 |
| Q3 l_partkey, join | roaring, level 3 | 6 | 40.9 | 0 / 30 | 0 | 59986052 | 55609564 | 2117471 | 117473 |
| Q4 l_shipmode, no join | Baleen off | 1 | 403.9 | | | | | | |
| Q4 l_shipmode, no join | Baleen off | 2 | 399.3 | | | | | | |
| Q4 l_shipmode, no join | Baleen off | 3 | 396.2 | | | | | | |
| Q4 l_shipmode, no join | Baleen off | 4 | 403.4 | | | | | | |
| Q4 l_shipmode, no join | Baleen off | 5 | 392.8 | | | | | | |
| Q4 l_shipmode, no join | Baleen off | 6 | 404.8 | | | | | | |
| Q4 l_shipmode, no join | hash, level 0 | 1 | 18.5 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | hash, level 0 | 2 | 18.8 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | hash, level 0 | 3 | 19.1 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | hash, level 0 | 4 | 19.9 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | hash, level 0 | 5 | 20.3 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | hash, level 0 | 6 | 18.8 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | hash, level 1 | 1 | 18.6 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | hash, level 1 | 2 | 18.3 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | hash, level 1 | 3 | 19.9 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | hash, level 1 | 4 | 19.6 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | hash, level 1 | 5 | 19.7 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | hash, level 1 | 6 | 19.3 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | hash, level 2 | 1 | 18.7 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | hash, level 2 | 2 | 18.9 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | hash, level 2 | 3 | 19.5 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | hash, level 2 | 4 | 19.4 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | hash, level 2 | 5 | 18.9 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | hash, level 2 | 6 | 19.0 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | hash, level 3 | 1 | 2.7 | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999993 |
| Q4 l_shipmode, no join | hash, level 3 | 2 | 3.0 | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999993 |
| Q4 l_shipmode, no join | hash, level 3 | 3 | 3.0 | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999993 |
| Q4 l_shipmode, no join | hash, level 3 | 4 | 2.9 | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999993 |
| Q4 l_shipmode, no join | hash, level 3 | 5 | 2.8 | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999993 |
| Q4 l_shipmode, no join | hash, level 3 | 6 | 2.7 | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999993 |
| Q4 l_shipmode, no join | flat, level 0 | 1 | 19.5 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | flat, level 0 | 2 | 18.0 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | flat, level 0 | 3 | 19.4 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | flat, level 0 | 4 | 18.9 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | flat, level 0 | 5 | 17.9 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | flat, level 0 | 6 | 18.9 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | flat, level 1 | 1 | 19.0 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | flat, level 1 | 2 | 19.8 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | flat, level 1 | 3 | 18.9 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | flat, level 1 | 4 | 20.0 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | flat, level 1 | 5 | 18.9 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | flat, level 1 | 6 | 18.9 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | flat, level 2 | 1 | 19.8 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | flat, level 2 | 2 | 18.4 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | flat, level 2 | 3 | 19.4 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | flat, level 2 | 4 | 17.9 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | flat, level 2 | 5 | 18.4 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | flat, level 2 | 6 | 19.5 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | flat, level 3 | 1 | 2.6 | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999993 |
| Q4 l_shipmode, no join | flat, level 3 | 2 | 2.9 | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999993 |
| Q4 l_shipmode, no join | flat, level 3 | 3 | 3.0 | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999993 |
| Q4 l_shipmode, no join | flat, level 3 | 4 | 2.9 | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999993 |
| Q4 l_shipmode, no join | flat, level 3 | 5 | 2.8 | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999993 |
| Q4 l_shipmode, no join | flat, level 3 | 6 | 2.5 | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999993 |
| Q4 l_shipmode, no join | roaring, level 0 | 1 | 20.9 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | roaring, level 0 | 2 | 22.1 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | roaring, level 0 | 3 | 20.5 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | roaring, level 0 | 4 | 21.4 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | roaring, level 0 | 5 | 21.2 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | roaring, level 0 | 6 | 21.4 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | roaring, level 1 | 1 | 20.9 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | roaring, level 1 | 2 | 21.4 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | roaring, level 1 | 3 | 20.1 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | roaring, level 1 | 4 | 21.1 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | roaring, level 1 | 5 | 20.2 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | roaring, level 1 | 6 | 22.0 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | roaring, level 2 | 1 | 21.2 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | roaring, level 2 | 2 | 21.0 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | roaring, level 2 | 3 | 20.1 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | roaring, level 2 | 4 | 21.2 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | roaring, level 2 | 5 | 22.2 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | roaring, level 2 | 6 | 22.1 | 0 / 30 | 0 | 0 | 0 | 59986052 | 59986045 |
| Q4 l_shipmode, no join | roaring, level 3 | 1 | 3.1 | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999993 |
| Q4 l_shipmode, no join | roaring, level 3 | 2 | 3.3 | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999993 |
| Q4 l_shipmode, no join | roaring, level 3 | 3 | 3.4 | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999993 |
| Q4 l_shipmode, no join | roaring, level 3 | 4 | 3.0 | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999993 |
| Q4 l_shipmode, no join | roaring, level 3 | 5 | 2.8 | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999993 |
| Q4 l_shipmode, no join | roaring, level 3 | 6 | 3.1 | 0 / 30 | 26 | 0 | 0 | 8000000 | 7999993 |

## What the numbers say

- **Q1 (7 values, string, dictionary-encoded).** Levels 0 to 2 take 34 to 38 s with every seen-set. Level 3 takes 12.4 to 12.7 s (about 17x over 212.7 s). Four files are read in full before the seen-set holds all 7 values; the other 26 are opened with the NOT IN filter and return nothing.
- **Q2 (100,000 values, BIGINT, not dictionary-encoded).** Level 0 is 35.6 to 36.4 s. File skipping happens only with flat and roaring, and the number of files skipped varies from 0 to 24 between runs of the same configuration; times follow it (15.7 s with 24 skipped, 31 to 35 s with 0 to 2 skipped). Level 3 does the same thing as level 2 here.
- **Q2, hash, levels 1 to 3** are about 6 s slower than hash at level 0 (41.9 to 42.6 s against 35.6 s). Flat and roaring do not show this.
- **Q3 (1,999,998 values, BIGINT).** Hash is 93 to 103 s because of the 1,000,000 cap: about 14.8 million rows reach the observer or the sort instead of about 2.1 million. Flat and roaring are 41 to 45 s at every level. No file is ever skipped.
- **Q4 (no join).** Levels 0 to 2 are 19 to 21 s (about 20x over 401.4 s). Level 3 is 2.8 to 3.1 s (130x to 143x). Roaring is about 2 s slower than hash and flat at levels 0 to 2.
- **Observer versus the earlier levels.** With a join, the observer alone (level 0) gives 5.6x on Q1, 4.4x on Q2 and 2.9x on Q3 (flat, roaring). The tuple filter (level 1) changes the time by a few percent in either direction. The next large step comes only from not reading files: level 3 on Q1 and Q4, level 2 on Q2.

## Changes against the first run of 2026-11-16

- Baleen off is now a median of 6: Q1 212.7 s (was 211.1), Q2 158.2 s (was 162.0), Q3 123.2 s (was 139.8), Q4 401.4 s (was 388.7). The Q3 speedups are lower than first reported for that reason (roaring level 2: 2.97x, was 3.29x).
- Flat at levels 0 and 1 is now a real bitmap. Q3 flat level 1 went from 113.6 s to 42.2 s, Q2 flat level 1 from 42.0 s to 34.7 s.
- Q4 works at every level (first run: observer did nothing before the fix).

## Why `l_suppkey` and `l_partkey` have no dictionary (checked later on 2026-11-17)

The loader (`/scratch/asterixdb_eightynode/iceberg/loader/TpchLoader.java`) uses Iceberg's Parquet writer and sets only the row-group size (16 MB). Everything else is Iceberg 1.12.0's default: page row limit 20,000, page size 1 MB, dictionary size 2 MB (read from `TableProperties` in the jar).

Reproduced with the same writer on 2,000,000 random longs (`/scratch/asterixdb_eightynode/iceberg/loader/DictTest.java`):

| Value range | Writer settings | Encodings | Dictionary page | Column bytes |
|---|---|---|---|---|
| 1 to 100,000 | loader's settings | BIT_PACKED, PLAIN | no | 5,896,848 |
| 1 to 100,000 | page row limit 1,000,000 | PLAIN_DICTIONARY, BIT_PACKED | yes | 4,546,588 |
| 1 to 100,000 | page row limit 1,000,000, page size 8 MB | PLAIN_DICTIONARY, BIT_PACKED | yes | 4,545,884 |
| 1 to 2,000,000 | page row limit 10,000,000, page size 32 MB, dictionary size 32 MB, row group 128 MB | PLAIN_DICTIONARY, BIT_PACKED | yes | 9,524,800 |

The dictionary is dropped because of the 20,000-row page limit, not because the values do not fit a dictionary. With 20,000 rows drawn from 100,000 values, almost every value in the first page is new, so the dictionary saves nothing on that page and the writer switches the column to plain for the rest of the row group.

Consequence: any Iceberg table written with default settings will have no dictionary on a column like this, so level 3 cannot work on it whatever the NOT IN cap is. Raising the cap can only be tested on a table rewritten with a larger page row limit (and a larger dictionary size for `l_partkey`).

Not verified: the exact rule the writer applies after the first page (described from knowledge of parquet-mr, the source was not read). The table above shows the behaviour only.

## Decisions I made without asking

- Put this run in a new note dated 2026-11-17 instead of extending the 2026-11-16 results note.
- The benchmark script kept counters only for the median run. I reconstructed every run's counters from the server log by job order (jobs 0.3 to 0.314, one per run) with a new script, `per_run_distinct.py`. The reconstruction agrees with the script's own median-run counters in every row.
- A footer read of the 30 Parquet files (encoding check) ran during the benchmark and may have added noise to the run in progress at that moment.

## Stated but not verified

- Why hash at levels 1 to 3 on Q2 is 6 s slower than at level 0. No profile was taken.
- Why roaring is 2 s slower on Q4. No profile was taken.
- That the run-to-run spread in files skipped on Q2 comes from rows waiting in partly filled frames. This is the hypothesis the flush change tests.
