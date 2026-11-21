# PLAQUE DISTINCT filter on native columnar tables: 6-run benchmark (2026-11-20)

Result of the benchmark planned in `2026_11_20_PLAQUE_DISTINCT_NATIVE_COLUMNAR.md` and approved by the user.
240 measured runs. **All 240 results are equal to the Baleen-off result.**

With the defaults (roaring seen-set, level 4):

| Query | Baleen off | Baleen on | Speedup |
|---|---|---|---|
| Q1 `l_shipmode`, join | 155.0 s | 14.5 s | 10.72x |
| Q2 `l_suppkey`, join | 125.0 s | 9.5 s | 13.17x |
| Q3 `l_partkey`, join | 108.1 s | 21.7 s | 4.98x |
| Q4 `l_shipmode`, no join | 295.5 s | 8.9 s | 33.38x |

## Setup

- Server: one JVM, 4 node controllers, build of 2026-11-20 with level 4 and the native scan filters. Not
  restarted during the run.
- Tables: `Lineitem_column_10` (59,986,052 rows, 37,100 leaves, primary key `l_orderkey, l_linenumber`) and
  `Orders_column_10`, dataverse `Default`, native columnar, fields declared not nullable.
- Configurations per query: Baleen off, and {hash, flat, roaring} x level {1, 2, 4}: 10 configurations.
  Flush (`fj`) and propagation (on, interval 5000) at their defaults.
- Level 3 is not run: on native it adds nothing to level 2.
- 6 runs per configuration, round-robin within a query (run 1 of all 10, then run 2 of all 10, ...), one
  discarded warm-up per query (roaring, level 4).
- Started 18:22, finished 22:21 (3 h 59 min).
- Script `build_logs/bench_distinct_native.py`; raw files `build_logs/bench_result_distinct_native.md` and
  `build_logs/bench_result_distinct_native_per_run.md`; server log `build_logs/asterix_distinct_native.log`.

## Queries (full text)

```sql
USE Default;

-- Q1
SELECT DISTINCT l.l_shipmode FROM Lineitem_column_10 l, Orders_column_10 o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
-- Q2
SELECT DISTINCT l.l_suppkey FROM Lineitem_column_10 l, Orders_column_10 o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
-- Q3
SELECT DISTINCT l.l_partkey FROM Lineitem_column_10 l, Orders_column_10 o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
-- Q4
SELECT DISTINCT l.l_shipmode FROM Lineitem_column_10 l;
```

Settings put in front of each query:

```sql
-- Baleen off
SET `compiler.plaque.enabled` "false";
-- Baleen on, <set> in hash, flat, roaring; <level> in 1, 2, 4
SET `compiler.plaque.enabled` "true"; SET `compiler.plaque.distinct.set` "<set>"; SET `compiler.plaque.distinct.level` "<level>";
```

Result sizes and md5 (first 10 hex digits), same as on Iceberg: Q1 7 rows 86ca9c0b89; Q2 100,000 rows
fe689b6606; Q3 1,999,998 rows 1169248e70; Q4 7 rows 86ca9c0b89.

## What each level does on a native table

| Level | Place | Columns |
|---|---|---|
| 0 | Observer above the join | any |
| 1 | + tuple filter above the scan | any |
| 2 | + leaf skip: a leaf whose lowest-to-highest range is all seen is not read | BIGINT, not primary key |
| 3 | nothing more (no dictionary on native) | |
| 4 | + rows with a seen value are dropped inside the scan, before the record is built | BIGINT or string, not primary key |

## Medians (seconds, 6 runs) and speedup over Baleen off

| Query | Baleen off | Seen-set | Level 1 | Level 2 | Level 4 |
|---|---|---|---|---|---|
| Q1 `l_shipmode`, join | 155.0 | hash | 35.8 (4.33x) | 34.9 (4.45x) | 15.1 (10.30x) |
| Q1 `l_shipmode`, join | 155.0 | flat | 35.8 (4.33x) | 35.9 (4.31x) | 15.0 (10.36x) |
| Q1 `l_shipmode`, join | 155.0 | roaring | 35.9 (4.31x) | 35.1 (4.41x) | 14.5 (10.72x) |
| Q2 `l_suppkey`, join | 125.0 | hash | 42.2 (2.96x) | 42.9 (2.92x) | 21.6 (5.80x) |
| Q2 `l_suppkey`, join | 125.0 | flat | 45.9 (2.72x) | 47.1 (2.65x) | 25.4 (4.92x) |
| Q2 `l_suppkey`, join | 125.0 | roaring | 32.9 (3.81x) | 17.4 (7.20x) | 9.5 (13.17x) |
| Q3 `l_partkey`, join | 108.1 | hash | 110.5 (0.98x) | 102.5 (1.05x) | 113.7 (0.95x) |
| Q3 `l_partkey`, join | 108.1 | flat | 108.3 (1.00x) | 109.1 (0.99x) | 123.1 (0.88x) |
| Q3 `l_partkey`, join | 108.1 | roaring | 40.2 (2.69x) | 41.9 (2.58x) | 21.7 (4.98x) |
| Q4 `l_shipmode`, no join | 295.5 | hash | 27.7 (10.66x) | 27.6 (10.71x) | 8.8 (33.66x) |
| Q4 `l_shipmode`, no join | 295.5 | flat | 26.8 (11.02x) | 27.1 (10.89x) | 8.9 (33.20x) |
| Q4 `l_shipmode`, no join | 295.5 | roaring | 27.3 (10.83x) | 26.8 (11.01x) | 8.9 (33.38x) |

## Raw summary table (as written by the script)

Counters are those of the upper-median run, summed over the 4 nodes. "Leaves skipped / tested" is 0 / 0 where
no leaf filter is attached (level 1, and the string column `l_shipmode` at every level).

| Query | Variant | Runs (s) | Median (s) | Speedup | Result = off | Leaves skipped per run | Leaves skipped / tested | Rows dropped in reader | Rows into tuple filter | Dropped by tuple filter | Rows into observer | Dropped by observer | Deltas sent | Deltas merged | Values merged |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Q1 l_shipmode, join | Baleen off | 150.9 152.9 158.2 152.2 157.7 157.1 | 155.0 | 1.00x | yes |  |  |  |  |  |  |  |  |  |  |
| Q1 l_shipmode, join | hash, level 1 | 36.2 35.4 36.8 34.7 34.9 36.1 | 35.8 | 4.33x | yes | 0 0 0 0 0 0 | 0 / 0 | 0 | 59986052 | 59982457 | 1727 | 1699 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | 34.9 34.8 35.7 34.7 34.5 36.1 | 34.9 | 4.45x | yes | 0 0 0 0 0 0 | 0 / 0 | 0 | 59986052 | 59982453 | 1730 | 1702 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 4 | 15.5 14.2 15.0 15.3 15.1 14.3 | 15.1 | 10.30x | yes | 0 0 0 0 0 0 | 0 / 0 | 59980256 | 5796 | 3395 | 1133 | 1105 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 1 | 36.9 33.9 38.9 36.4 35.2 33.0 | 35.8 | 4.33x | yes | 0 0 0 0 0 0 | 0 / 0 | 0 | 59986052 | 59982453 | 1730 | 1702 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | 36.0 36.9 35.9 35.0 36.0 34.5 | 35.9 | 4.31x | yes | 0 0 0 0 0 0 | 0 / 0 | 0 | 59986052 | 59984851 | 573 | 545 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 4 | 15.3 14.0 14.5 14.6 16.7 15.8 | 15.0 | 10.36x | yes | 0 0 0 0 0 0 | 0 / 0 | 59978665 | 7387 | 2590 | 2301 | 2273 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 1 | 35.1 35.6 37.0 33.8 38.7 36.2 | 35.9 | 4.31x | yes | 0 0 0 0 0 0 | 0 / 0 | 0 | 59986052 | 59982453 | 1730 | 1702 | 4 | 2 | 14 |
| Q1 l_shipmode, join | roaring, level 2 | 35.4 34.9 34.9 35.4 34.9 36.3 | 35.1 | 4.41x | yes | 0 0 0 0 0 0 | 0 / 0 | 0 | 59986052 | 59982455 | 1741 | 1713 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 4 | 15.3 14.4 14.0 14.6 14.4 14.5 | 14.5 | 10.72x | yes | 0 0 0 0 0 0 | 0 / 0 | 59979172 | 6880 | 3283 | 1741 | 1713 | 4 | 0 | 0 |
| Q2 l_suppkey, join | Baleen off | 126.4 121.4 121.2 128.3 124.7 125.4 | 125.0 | 1.00x | yes |  |  |  |  |  |  |  |  |  |  |
| Q2 l_suppkey, join | hash, level 1 | 42.1 42.5 42.3 40.8 42.8 39.7 | 42.2 | 2.96x | yes | 0 0 0 0 0 0 | 0 / 0 | 0 | 59986052 | 59693498 | 141929 | 18750 | 30 | 78 | 299979 |
| Q2 l_suppkey, join | hash, level 2 | 47.4 43.5 38.4 42.1 42.3 46.7 | 42.9 | 2.92x | yes | 0 0 0 0 0 0 | 0 / 37100 | 0 | 59986052 | 59697354 | 139998 | 17638 | 29 | 75 | 298878 |
| Q2 l_suppkey, join | hash, level 4 | 21.5 19.5 21.6 24.8 24.5 21.5 | 21.6 | 5.80x | yes | 0 0 0 0 0 0 | 0 / 37100 | 59675527 | 310525 | 18383 | 141517 | 18721 | 30 | 78 | 299979 |
| Q2 l_suppkey, join | flat, level 1 | 46.5 46.1 45.8 39.1 39.2 46.0 | 45.9 | 2.72x | yes | 0 0 0 0 0 0 | 0 / 0 | 0 | 59986052 | 59696141 | 140435 | 17481 | 30 | 79 | 299862 |
| Q2 l_suppkey, join | flat, level 2 | 48.2 46.5 41.3 46.8 47.4 48.0 | 47.1 | 2.65x | yes | 0 0 0 0 0 0 | 0 / 37100 | 0 | 59986052 | 59697949 | 139884 | 17251 | 29 | 75 | 298347 |
| Q2 l_suppkey, join | flat, level 4 | 26.5 25.9 24.5 27.7 24.9 24.8 | 25.4 | 4.92x | yes | 0 0 0 0 0 0 | 0 / 37100 | 59674399 | 311653 | 17952 | 142431 | 19242 | 30 | 78 | 299922 |
| Q2 l_suppkey, join | roaring, level 1 | 33.0 33.2 32.8 32.1 35.5 31.0 | 32.9 | 3.81x | yes | 0 0 0 0 0 0 | 0 / 0 | 0 | 59986052 | 59677531 | 149563 | 27285 | 30 | 81 | 300000 |
| Q2 l_suppkey, join | roaring, level 2 | 18.7 22.3 16.1 13.8 15.6 21.6 | 17.4 | 7.20x | yes | 27896 22035 26981 30133 29308 24245 | 27896 / 37100 | 0 | 14882868 | 14592392 | 140957 | 18560 | 30 | 78 | 299946 |
| Q2 l_suppkey, join | roaring, level 4 | 7.9 8.2 8.7 11.6 10.3 12.0 | 9.5 | 13.17x | yes | 29733 31561 28638 21269 22281 0 | 22281 / 37100 | 23649840 | 312483 | 17666 | 142858 | 19649 | 31 | 81 | 299967 |
| Q3 l_partkey, join | Baleen off | 109.4 105.8 107.7 112.3 107.5 108.5 | 108.1 | 1.00x | yes |  |  |  |  |  |  |  |  |  |  |
| Q3 l_partkey, join | hash, level 1 | 124.2 130.6 106.3 114.8 106.1 102.1 | 110.5 | 0.98x | yes | 0 0 0 0 0 0 | 0 / 0 | 0 | 59986052 | 29322745 | 14852937 | 42478 | 203 | 609 | 3026760 |
| Q3 l_partkey, join | hash, level 2 | 108.1 102.6 103.8 102.4 100.3 97.9 | 102.5 | 1.05x | yes | 0 0 0 0 0 0 | 0 / 37100 | 0 | 59986052 | 29323441 | 14852366 | 50101 | 203 | 609 | 3034599 |
| Q3 l_partkey, join | hash, level 4 | 112.0 115.0 125.2 112.3 116.5 111.8 | 113.7 | 0.95x | yes | 0 0 0 0 0 0 | 0 / 37100 | 29321824 | 30664228 | 2358 | 14852076 | 42993 | 202 | 606 | 3027942 |
| Q3 l_partkey, join | flat, level 1 | 104.6 105.8 108.2 108.3 113.2 109.4 | 108.3 | 1.00x | yes | 0 0 0 0 0 0 | 0 / 0 | 0 | 59986052 | 29323107 | 14852707 | 39596 | 2950 | 6009 | 5999994 |
| Q3 l_partkey, join | flat, level 2 | 113.4 100.5 104.3 111.8 107.8 110.4 | 109.1 | 0.99x | yes | 0 0 0 0 0 0 | 0 / 37100 | 0 | 59986052 | 29322744 | 14852507 | 43296 | 2951 | 5958 | 5999994 |
| Q3 l_partkey, join | flat, level 4 | 103.2 124.4 121.9 128.0 128.6 114.6 | 123.1 | 0.88x | yes | 0 0 0 0 0 0 | 0 / 37100 | 29322496 | 30663556 | 2270 | 14852295 | 40544 | 2930 | 5964 | 5999994 |
| Q3 l_partkey, join | roaring, level 1 | 40.8 40.9 39.7 39.9 40.1 40.4 | 40.2 | 2.69x | yes | 0 0 0 0 0 0 | 0 / 0 | 0 | 59986052 | 55716724 | 2064648 | 25741 | 411 | 1221 | 5993211 |
| Q3 l_partkey, join | roaring, level 2 | 41.9 41.9 41.1 40.9 42.7 42.0 | 41.9 | 2.58x | yes | 0 0 0 0 0 0 | 0 / 37100 | 0 | 59986052 | 55722288 | 2062066 | 24314 | 412 | 1224 | 5996823 |
| Q3 l_partkey, join | roaring, level 4 | 22.8 23.1 21.1 21.7 21.2 21.7 | 21.7 | 4.98x | yes | 0 0 0 0 0 0 | 0 / 37100 | 55668349 | 4317703 | 22711 | 2077024 | 35973 | 411 | 1221 | 5995296 |
| Q4 l_shipmode, no join | Baleen off | 293.1 297.9 301.2 295.0 296.0 290.4 | 295.5 | 1.00x | yes |  |  |  |  |  |  |  |  |  |  |
| Q4 l_shipmode, no join | hash, level 1 | 26.0 26.6 27.9 28.3 27.7 27.7 | 27.7 | 10.66x | yes | 0 0 0 0 0 0 | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 2 | 27.5 29.8 27.2 26.4 28.8 27.7 | 27.6 | 10.71x | yes | 0 0 0 0 0 0 | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 4 | 8.3 8.8 8.8 9.1 8.8 9.2 | 8.8 | 33.66x | yes | 0 0 0 0 0 0 | 0 / 0 | 59974492 | 0 | 0 | 11560 | 11532 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 1 | 27.1 28.3 26.4 27.1 26.4 26.5 | 26.8 | 11.02x | yes | 0 0 0 0 0 0 | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 2 | 26.2 27.0 26.9 27.5 27.3 27.9 | 27.1 | 10.89x | yes | 0 0 0 0 0 0 | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 4 | 9.1 8.5 8.9 8.9 8.5 9.5 | 8.9 | 33.20x | yes | 0 0 0 0 0 0 | 0 / 0 | 59974492 | 0 | 0 | 11560 | 11532 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 1 | 28.4 27.4 26.6 26.5 27.9 27.1 | 27.3 | 10.83x | yes | 0 0 0 0 0 0 | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 2 | 26.6 27.4 26.3 28.6 26.8 26.9 | 26.8 | 11.01x | yes | 0 0 0 0 0 0 | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 4 | 9.9 9.0 8.9 8.4 8.6 8.8 | 8.9 | 33.38x | yes | 0 0 0 0 0 0 | 0 / 0 | 59974492 | 0 | 0 | 11560 | 11532 | 4 | 3 | 21 |

## What the tables show

- **Roaring at level 4 is the fastest or within 0.1 s of the fastest on every query**, and the only seen-set
  that gains on all four.
- **Level 4 is the largest single step on every query** (roaring, level 2 to level 4): Q1 35.1 to 14.5 s,
  Q2 17.4 to 9.5 s, Q3 41.9 to 21.7 s, Q4 26.8 to 8.9 s.
- Rows that still leave the scan at level 4, of 59,986,052 (roaring, median run): Q1 6,880; Q2 312,483;
  Q3 4,317,703; Q4 11,560.
- **Leaf skip (level 2) works only with roaring.** Q2, roaring, level 2: 22,035 to 30,133 of 37,100 leaves
  skipped per run, 32.9 to 17.4 s. Hash and flat tested all 37,100 leaves and skipped none in every run.
- Q2, roaring, level 4: 21,269 to 31,561 leaves skipped in runs 1 to 5. Run 6 skipped 0 leaves; the row drop
  removed 59,677,219 rows instead and the run took 12.0 s (runs 1 to 5: 7.9 to 11.6 s). The result was correct.
- **Q3 gains nothing with hash or flat at any level** (0.88x to 1.05x). Their tuple filter drops 29.3 million
  of 60 million rows, against 55.7 million with roaring, and 14.9 million rows reach the observer against
  2.1 million. Flat at level 4 is slower than Baleen off (123.1 s against 108.1 s).
- Flat on Iceberg gained on Q3 (44.4 s, 3.22x on 2026-11-18). On native flat is given no value range (listed
  as a built-in limit in the implementation note), and here it measures like hash.
- Q1 and Q4 (7 values, string column): the three seen-sets are equal within 0.6 s at every level, and level 2
  equals level 1, as expected since no leaf filter applies to a string.
- Baleen off is steady: the 6 runs of each query lie within 11 s of each other.

## Native and Iceberg side by side (roaring, level 4, medians)

Different table formats and different days; Iceberg numbers are from `2026_11_18_..._PERNODE_VERIFICATION.md`
(Baleen off, Q2, Q3 at level 3) and `2026_11_20_PLAQUE_DISTINCT_LEVEL4.md` (Q1, Q4). Iceberg Q2 and Q3 have no
6-run figure at level 4.

| Query | Native off | Native on | Native speedup | Iceberg off | Iceberg on | Iceberg speedup |
|---|---|---|---|---|---|---|
| Q1 | 155.0 s | 14.5 s | 10.72x | 203.8 s | 9.4 s | 21.65x |
| Q2 | 125.0 s | 9.5 s | 13.17x | 164.6 s | 16.0 s (level 3) | 10.31x |
| Q3 | 108.1 s | 21.7 s | 4.98x | 143.1 s | 47.6 s (level 3) | 3.01x |
| Q4 | 295.5 s | 8.9 s | 33.38x | 402.5 s | 0.4 s | 919.42x |

## Built-in limits of this run

- One JVM with 4 node controllers on one machine.
- One table size, one disk layout; tables were bulk loaded and have no deletes or updates.
- DISTINCT columns are declared, not nullable, and not part of the primary key.
- Level 0 (observer only) and level 3 were not run.
- No file-system cache control between runs; the discarded warm-up per query is the only measure taken.

## Bugs and problems

- The first start of this benchmark was cut off at 18:19 after round 1 of Q1, when the session that started
  it ended. Restarted at 18:22, detached. The partial rows are kept in
  `build_logs/bench_result_distinct_native_per_run_cutoff_1819.md` and are not used here.
- The script writes the Baleen-off rows with too few cells. The copies in this note are padded; the files in
  `build_logs` are as written.
- My estimate of the running time was 2 h 45 min; it took 3 h 59 min.

## Decisions made without asking

- Restarted the cut-off benchmark without asking, as it was already approved.
- Counters in the summary table are from the upper-median run, as in the earlier benchmarks.
- The native and Iceberg comparison table mixes days and levels; it is labelled as such.

## Stated but not verified

- Why hash and flat stop at 29.3 million dropped rows on Q3 was not traced. The hash limit of 1,000,000 values
  and the missing range for flat are the documented limits that fit; neither was confirmed in the log.
- Why run 6 of Q2 (roaring, level 4) skipped no leaf was not looked into.
- Tables with several disk components, with deletes or updates, nullable or undeclared columns: not run.
- Coexistence with the MAX/MIN, GROUP BY, Top-K and theta filters on native after today's changes: not rerun.

## Raw per-run table

| Query | Variant | Run | Time (s) | Result = off | Leaves skipped / tested | Rows dropped in reader | Rows into tuple filter | Dropped by tuple filter | Rows into observer | Dropped by observer | Deltas sent | Deltas merged | Values merged |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Q1 l_shipmode, join | warm-up (roaring, level 4), discarded | 0 | 15.2 | | 0 / 0 | 59979790 | 6262 | 3863 | 1170 | 1142 | 4 | 0 | 0 |
| Q1 l_shipmode, join | Baleen off | 1 | 150.9 | yes |  |  |  |  |  |  |  |  |  |
| Q1 l_shipmode, join | hash, level 1 | 1 | 36.2 | yes | 0 / 0 | 0 | 59986052 | 59982453 | 1704 | 1676 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | 1 | 34.9 | yes | 0 / 0 | 0 | 59986052 | 59982453 | 1730 | 1702 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 4 | 1 | 15.5 | yes | 0 / 0 | 59979236 | 6816 | 2019 | 2301 | 2273 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 1 | 1 | 36.9 | yes | 0 / 0 | 0 | 59986052 | 59981255 | 2301 | 2273 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | 1 | 36.0 | yes | 0 / 0 | 0 | 59986052 | 59981255 | 2301 | 2273 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 4 | 1 | 15.3 | yes | 0 / 0 | 59978665 | 7387 | 2590 | 2301 | 2273 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 1 | 1 | 35.1 | yes | 0 / 0 | 0 | 59986052 | 59984854 | 597 | 569 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | 1 | 35.4 | yes | 0 / 0 | 0 | 59986052 | 59983654 | 1131 | 1103 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 4 | 1 | 15.3 | yes | 0 / 0 | 59979948 | 6104 | 3705 | 1144 | 1116 | 4 | 0 | 0 |
| Q1 l_shipmode, join | Baleen off | 2 | 152.9 | yes |  |  |  |  |  |  |  |  |  |
| Q1 l_shipmode, join | hash, level 1 | 2 | 35.4 | yes | 0 / 0 | 0 | 59986052 | 59983455 | 1243 | 1215 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | 2 | 34.8 | yes | 0 / 0 | 0 | 59986052 | 59983654 | 1157 | 1129 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 4 | 2 | 14.2 | yes | 0 / 0 | 59978790 | 7262 | 2465 | 2301 | 2273 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 1 | 2 | 33.9 | yes | 0 / 0 | 0 | 59986052 | 59981255 | 2301 | 2273 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | 2 | 36.9 | yes | 0 / 0 | 0 | 59986052 | 59981255 | 2301 | 2273 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 4 | 2 | 14.0 | yes | 0 / 0 | 59978547 | 7505 | 2708 | 2301 | 2273 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 1 | 2 | 35.6 | yes | 0 / 0 | 0 | 59986052 | 59982453 | 1704 | 1676 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | 2 | 34.9 | yes | 0 / 0 | 0 | 59986052 | 59981797 | 2051 | 2023 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 4 | 2 | 14.4 | yes | 0 / 0 | 59978930 | 7122 | 2325 | 2301 | 2273 | 4 | 0 | 0 |
| Q1 l_shipmode, join | Baleen off | 3 | 158.2 | yes |  |  |  |  |  |  |  |  |  |
| Q1 l_shipmode, join | hash, level 1 | 3 | 36.8 | yes | 0 / 0 | 0 | 59986052 | 59984851 | 573 | 545 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | 3 | 35.7 | yes | 0 / 0 | 0 | 59986052 | 59982455 | 1741 | 1713 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 4 | 3 | 15.0 | yes | 0 / 0 | 59979070 | 6982 | 2185 | 2301 | 2273 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 1 | 3 | 38.9 | yes | 0 / 0 | 0 | 59986052 | 59983654 | 1131 | 1103 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | 3 | 35.9 | yes | 0 / 0 | 0 | 59986052 | 59982456 | 1728 | 1700 | 4 | 3 | 21 |
| Q1 l_shipmode, join | flat, level 4 | 3 | 14.5 | yes | 0 / 0 | 59980596 | 5456 | 4258 | 597 | 569 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 1 | 3 | 37.0 | yes | 0 / 0 | 0 | 59986052 | 59982455 | 1741 | 1713 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | 3 | 34.9 | yes | 0 / 0 | 0 | 59986052 | 59982455 | 1741 | 1713 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 4 | 3 | 14.0 | yes | 0 / 0 | 59979375 | 6677 | 1880 | 2301 | 2273 | 4 | 0 | 0 |
| Q1 l_shipmode, join | Baleen off | 4 | 152.2 | yes |  |  |  |  |  |  |  |  |  |
| Q1 l_shipmode, join | hash, level 1 | 4 | 34.7 | yes | 0 / 0 | 0 | 59986052 | 59983654 | 1131 | 1103 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | 4 | 34.7 | yes | 0 / 0 | 0 | 59986052 | 59981255 | 2301 | 2273 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 4 | 4 | 15.3 | yes | 0 / 0 | 59980025 | 6027 | 3629 | 1157 | 1129 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 1 | 4 | 36.4 | yes | 0 / 0 | 0 | 59986052 | 59982453 | 1730 | 1702 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | 4 | 35.0 | yes | 0 / 0 | 0 | 59986052 | 59981255 | 2301 | 2273 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 4 | 4 | 14.6 | yes | 0 / 0 | 59980232 | 5820 | 3421 | 1144 | 1116 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 1 | 4 | 33.8 | yes | 0 / 0 | 0 | 59986052 | 59981255 | 2301 | 2273 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | 4 | 35.4 | yes | 0 / 0 | 0 | 59986052 | 59982455 | 1741 | 1713 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 4 | 4 | 14.6 | yes | 0 / 0 | 59981637 | 4415 | 3214 | 573 | 545 | 4 | 0 | 0 |
| Q1 l_shipmode, join | Baleen off | 5 | 157.7 | yes |  |  |  |  |  |  |  |  |  |
| Q1 l_shipmode, join | hash, level 1 | 5 | 34.9 | yes | 0 / 0 | 0 | 59986052 | 59984851 | 573 | 545 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | 5 | 34.5 | yes | 0 / 0 | 0 | 59986052 | 59981255 | 2301 | 2273 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 4 | 5 | 15.1 | yes | 0 / 0 | 59980256 | 5796 | 3395 | 1133 | 1105 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 1 | 5 | 35.2 | yes | 0 / 0 | 0 | 59986052 | 59982453 | 1704 | 1676 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | 5 | 36.0 | yes | 0 / 0 | 0 | 59986052 | 59984851 | 573 | 545 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 4 | 5 | 16.7 | yes | 0 / 0 | 59979789 | 6263 | 2664 | 1730 | 1702 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 1 | 5 | 38.7 | yes | 0 / 0 | 0 | 59986052 | 59981255 | 2301 | 2273 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | 5 | 34.9 | yes | 0 / 0 | 0 | 59986052 | 59983653 | 1170 | 1142 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 4 | 5 | 14.4 | yes | 0 / 0 | 59980332 | 5720 | 4522 | 571 | 543 | 4 | 0 | 0 |
| Q1 l_shipmode, join | Baleen off | 6 | 157.1 | yes |  |  |  |  |  |  |  |  |  |
| Q1 l_shipmode, join | hash, level 1 | 6 | 36.1 | yes | 0 / 0 | 0 | 59986052 | 59982457 | 1727 | 1699 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | 6 | 36.1 | yes | 0 / 0 | 0 | 59986052 | 59981255 | 2301 | 2273 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 4 | 6 | 14.3 | yes | 0 / 0 | 59980820 | 5232 | 2834 | 1131 | 1103 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 1 | 6 | 33.0 | yes | 0 / 0 | 0 | 59986052 | 59981255 | 2301 | 2273 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | 6 | 34.5 | yes | 0 / 0 | 0 | 59986052 | 59981255 | 2301 | 2273 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 4 | 6 | 15.8 | yes | 0 / 0 | 59979076 | 6976 | 3377 | 1730 | 1702 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 1 | 6 | 36.2 | yes | 0 / 0 | 0 | 59986052 | 59982453 | 1730 | 1702 | 4 | 2 | 14 |
| Q1 l_shipmode, join | roaring, level 2 | 6 | 36.3 | yes | 0 / 0 | 0 | 59986052 | 59982245 | 1822 | 1794 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 4 | 6 | 14.5 | yes | 0 / 0 | 59979172 | 6880 | 3283 | 1741 | 1713 | 4 | 0 | 0 |
| Q2 l_suppkey, join | warm-up (roaring, level 4), discarded | 0 | 12.0 | | 12127 / 37100 | 40065785 | 314559 | 19785 | 142753 | 19810 | 30 | 78 | 298971 |
| Q2 l_suppkey, join | Baleen off | 1 | 126.4 | yes |  |  |  |  |  |  |  |  |  |
| Q2 l_suppkey, join | hash, level 1 | 1 | 42.1 | yes | 0 / 0 | 0 | 59986052 | 59695499 | 140805 | 17398 | 31 | 81 | 299985 |
| Q2 l_suppkey, join | hash, level 2 | 1 | 47.4 | yes | 0 / 37100 | 0 | 59986052 | 59698166 | 139534 | 16961 | 30 | 78 | 299955 |
| Q2 l_suppkey, join | hash, level 4 | 1 | 21.5 | yes | 0 / 37100 | 59677807 | 308245 | 16464 | 141377 | 18233 | 30 | 81 | 300000 |
| Q2 l_suppkey, join | flat, level 1 | 1 | 46.5 | yes | 0 / 0 | 0 | 59986052 | 59696118 | 140514 | 17617 | 30 | 78 | 299940 |
| Q2 l_suppkey, join | flat, level 2 | 1 | 48.2 | yes | 0 / 37100 | 0 | 59986052 | 59696930 | 140370 | 17655 | 30 | 78 | 299991 |
| Q2 l_suppkey, join | flat, level 4 | 1 | 26.5 | yes | 0 / 37100 | 59677768 | 308284 | 16941 | 141416 | 18316 | 29 | 81 | 300000 |
| Q2 l_suppkey, join | roaring, level 1 | 1 | 33.0 | yes | 0 / 0 | 0 | 59986052 | 59677531 | 149563 | 27285 | 30 | 81 | 300000 |
| Q2 l_suppkey, join | roaring, level 2 | 1 | 18.7 | yes | 27896 / 37100 | 0 | 14882868 | 14592392 | 140957 | 18560 | 30 | 78 | 299946 |
| Q2 l_suppkey, join | roaring, level 4 | 1 | 7.9 | yes | 29733 / 37100 | 11598734 | 313705 | 19209 | 142898 | 19925 | 31 | 81 | 299997 |
| Q2 l_suppkey, join | Baleen off | 2 | 121.4 | yes |  |  |  |  |  |  |  |  |  |
| Q2 l_suppkey, join | hash, level 1 | 2 | 42.5 | yes | 0 / 0 | 0 | 59986052 | 59701561 | 137865 | 15660 | 30 | 78 | 299874 |
| Q2 l_suppkey, join | hash, level 2 | 2 | 43.5 | yes | 0 / 37100 | 0 | 59986052 | 59697354 | 139998 | 17638 | 29 | 75 | 298878 |
| Q2 l_suppkey, join | hash, level 4 | 2 | 19.5 | yes | 0 / 37100 | 59678222 | 307830 | 17795 | 140599 | 17787 | 30 | 78 | 299706 |
| Q2 l_suppkey, join | flat, level 1 | 2 | 46.1 | yes | 0 / 0 | 0 | 59986052 | 59706768 | 135348 | 13493 | 30 | 78 | 299808 |
| Q2 l_suppkey, join | flat, level 2 | 2 | 46.5 | yes | 0 / 37100 | 0 | 59986052 | 59697468 | 139735 | 16902 | 30 | 78 | 299973 |
| Q2 l_suppkey, join | flat, level 4 | 2 | 25.9 | yes | 0 / 37100 | 59674399 | 311653 | 17952 | 142431 | 19242 | 30 | 78 | 299922 |
| Q2 l_suppkey, join | roaring, level 1 | 2 | 33.2 | yes | 0 / 0 | 0 | 59986052 | 59690129 | 143442 | 20883 | 30 | 81 | 300000 |
| Q2 l_suppkey, join | roaring, level 2 | 2 | 22.3 | yes | 22035 / 37100 | 0 | 24360105 | 24077385 | 137184 | 15559 | 29 | 75 | 298623 |
| Q2 l_suppkey, join | roaring, level 4 | 2 | 8.2 | yes | 31561 / 37100 | 8642813 | 313750 | 17368 | 143698 | 20390 | 31 | 84 | 300000 |
| Q2 l_suppkey, join | Baleen off | 3 | 121.2 | yes |  |  |  |  |  |  |  |  |  |
| Q2 l_suppkey, join | hash, level 1 | 3 | 42.3 | yes | 0 / 0 | 0 | 59986052 | 59693498 | 141929 | 18750 | 30 | 78 | 299979 |
| Q2 l_suppkey, join | hash, level 2 | 3 | 38.4 | yes | 0 / 37100 | 0 | 59986052 | 59699782 | 138732 | 16251 | 29 | 75 | 298674 |
| Q2 l_suppkey, join | hash, level 4 | 3 | 21.6 | yes | 0 / 37100 | 59675527 | 310525 | 18383 | 141517 | 18721 | 30 | 78 | 299979 |
| Q2 l_suppkey, join | flat, level 1 | 3 | 45.8 | yes | 0 / 0 | 0 | 59986052 | 59697770 | 139773 | 17267 | 30 | 78 | 299976 |
| Q2 l_suppkey, join | flat, level 2 | 3 | 41.3 | yes | 0 / 37100 | 0 | 59986052 | 59695121 | 141048 | 18074 | 30 | 78 | 299967 |
| Q2 l_suppkey, join | flat, level 4 | 3 | 24.5 | yes | 0 / 37100 | 59677983 | 308069 | 15911 | 141699 | 18224 | 31 | 81 | 299997 |
| Q2 l_suppkey, join | roaring, level 1 | 3 | 32.8 | yes | 0 / 0 | 0 | 59986052 | 59690957 | 143055 | 20870 | 30 | 81 | 300000 |
| Q2 l_suppkey, join | roaring, level 2 | 3 | 16.1 | yes | 26981 / 37100 | 0 | 16362423 | 16075812 | 138924 | 16668 | 30 | 78 | 299979 |
| Q2 l_suppkey, join | roaring, level 4 | 3 | 8.7 | yes | 28638 / 37100 | 13369115 | 313939 | 18796 | 143043 | 20407 | 30 | 81 | 300000 |
| Q2 l_suppkey, join | Baleen off | 4 | 128.3 | yes |  |  |  |  |  |  |  |  |  |
| Q2 l_suppkey, join | hash, level 1 | 4 | 40.8 | yes | 0 / 0 | 0 | 59986052 | 59697628 | 139783 | 17272 | 30 | 78 | 299979 |
| Q2 l_suppkey, join | hash, level 2 | 4 | 42.1 | yes | 0 / 37100 | 0 | 59986052 | 59696498 | 140433 | 17895 | 29 | 75 | 298161 |
| Q2 l_suppkey, join | hash, level 4 | 4 | 24.8 | yes | 0 / 37100 | 59678582 | 307470 | 18133 | 140200 | 17285 | 30 | 78 | 299658 |
| Q2 l_suppkey, join | flat, level 1 | 4 | 39.1 | yes | 0 / 0 | 0 | 59986052 | 59702692 | 137280 | 15289 | 30 | 78 | 299880 |
| Q2 l_suppkey, join | flat, level 2 | 4 | 46.8 | yes | 0 / 37100 | 0 | 59986052 | 59696374 | 140490 | 17758 | 30 | 80 | 299992 |
| Q2 l_suppkey, join | flat, level 4 | 4 | 27.7 | yes | 0 / 37100 | 59678651 | 307401 | 17139 | 140836 | 17857 | 30 | 78 | 299850 |
| Q2 l_suppkey, join | roaring, level 1 | 4 | 32.1 | yes | 0 / 0 | 0 | 59986052 | 59692508 | 142506 | 20038 | 30 | 81 | 300000 |
| Q2 l_suppkey, join | roaring, level 2 | 4 | 13.8 | yes | 30133 / 37100 | 0 | 11265639 | 10976335 | 140139 | 17874 | 30 | 78 | 299979 |
| Q2 l_suppkey, join | roaring, level 4 | 4 | 11.6 | yes | 21269 / 37100 | 25286175 | 311416 | 17912 | 142264 | 19961 | 31 | 81 | 299997 |
| Q2 l_suppkey, join | Baleen off | 5 | 124.7 | yes |  |  |  |  |  |  |  |  |  |
| Q2 l_suppkey, join | hash, level 1 | 5 | 42.8 | yes | 0 / 0 | 0 | 59986052 | 59700048 | 138615 | 16341 | 29 | 75 | 298563 |
| Q2 l_suppkey, join | hash, level 2 | 5 | 42.3 | yes | 0 / 37100 | 0 | 59986052 | 59708399 | 134652 | 13011 | 30 | 78 | 299664 |
| Q2 l_suppkey, join | hash, level 4 | 5 | 24.5 | yes | 0 / 37100 | 59676842 | 309210 | 18572 | 140848 | 17837 | 30 | 81 | 300000 |
| Q2 l_suppkey, join | flat, level 1 | 5 | 39.2 | yes | 0 / 0 | 0 | 59986052 | 59696497 | 140368 | 17587 | 30 | 78 | 299964 |
| Q2 l_suppkey, join | flat, level 2 | 5 | 47.4 | yes | 0 / 37100 | 0 | 59986052 | 59697949 | 139884 | 17251 | 29 | 75 | 298347 |
| Q2 l_suppkey, join | flat, level 4 | 5 | 24.9 | yes | 0 / 37100 | 59676516 | 309536 | 17445 | 141576 | 18337 | 30 | 81 | 300000 |
| Q2 l_suppkey, join | roaring, level 1 | 5 | 35.5 | yes | 0 / 0 | 0 | 59986052 | 59677858 | 149282 | 26727 | 30 | 81 | 300000 |
| Q2 l_suppkey, join | roaring, level 2 | 5 | 15.6 | yes | 29308 / 37100 | 0 | 12599664 | 12314634 | 138128 | 16166 | 30 | 78 | 299880 |
| Q2 l_suppkey, join | roaring, level 4 | 5 | 10.3 | yes | 22281 / 37100 | 23649840 | 312483 | 17666 | 142858 | 19649 | 31 | 81 | 299967 |
| Q2 l_suppkey, join | Baleen off | 6 | 125.4 | yes |  |  |  |  |  |  |  |  |  |
| Q2 l_suppkey, join | hash, level 1 | 6 | 39.7 | yes | 0 / 0 | 0 | 59986052 | 59694525 | 141304 | 18432 | 30 | 78 | 299946 |
| Q2 l_suppkey, join | hash, level 2 | 6 | 46.7 | yes | 0 / 37100 | 0 | 59986052 | 59695711 | 140717 | 17930 | 30 | 78 | 299967 |
| Q2 l_suppkey, join | hash, level 4 | 6 | 21.5 | yes | 0 / 37100 | 59677416 | 308636 | 18960 | 140574 | 18151 | 29 | 75 | 298539 |
| Q2 l_suppkey, join | flat, level 1 | 6 | 46.0 | yes | 0 / 0 | 0 | 59986052 | 59696141 | 140435 | 17481 | 30 | 79 | 299862 |
| Q2 l_suppkey, join | flat, level 2 | 6 | 48.0 | yes | 0 / 37100 | 0 | 59986052 | 59695797 | 140861 | 18087 | 29 | 75 | 298332 |
| Q2 l_suppkey, join | flat, level 4 | 6 | 24.8 | yes | 0 / 37100 | 59676502 | 309550 | 17236 | 141723 | 18890 | 30 | 78 | 299988 |
| Q2 l_suppkey, join | roaring, level 1 | 6 | 31.0 | yes | 0 / 0 | 0 | 59986052 | 59698961 | 139260 | 17256 | 29 | 75 | 298419 |
| Q2 l_suppkey, join | roaring, level 2 | 6 | 21.6 | yes | 24245 / 37100 | 0 | 20786535 | 20502166 | 137947 | 16183 | 29 | 75 | 298674 |
| Q2 l_suppkey, join | roaring, level 4 | 6 | 12.0 | yes | 0 / 37100 | 59677219 | 308833 | 19110 | 140462 | 19056 | 30 | 78 | 299676 |
| Q3 l_partkey, join | warm-up (roaring, level 4), discarded | 0 | 21.7 | | 0 / 37100 | 55674996 | 4311056 | 22383 | 2074269 | 33519 | 411 | 1221 | 5996961 |
| Q3 l_partkey, join | Baleen off | 1 | 109.4 | yes |  |  |  |  |  |  |  |  |  |
| Q3 l_partkey, join | hash, level 1 | 1 | 124.2 | yes | 0 / 0 | 0 | 59986052 | 29322556 | 14853210 | 40341 | 203 | 609 | 3025902 |
| Q3 l_partkey, join | hash, level 2 | 1 | 108.1 | yes | 0 / 37100 | 0 | 59986052 | 29323756 | 14851693 | 40366 | 203 | 609 | 3025302 |
| Q3 l_partkey, join | hash, level 4 | 1 | 112.0 | yes | 0 / 37100 | 29321408 | 30664644 | 2327 | 14851435 | 40577 | 203 | 609 | 3028272 |
| Q3 l_partkey, join | flat, level 1 | 1 | 104.6 | yes | 0 / 0 | 0 | 59986052 | 29322708 | 14853057 | 41559 | 2950 | 6021 | 5999994 |
| Q3 l_partkey, join | flat, level 2 | 1 | 113.4 | yes | 0 / 37100 | 0 | 59986052 | 29322777 | 14852830 | 43414 | 2950 | 6000 | 5999994 |
| Q3 l_partkey, join | flat, level 4 | 1 | 103.2 | yes | 0 / 37100 | 29322874 | 30663178 | 2274 | 14851439 | 39510 | 2930 | 5973 | 5999994 |
| Q3 l_partkey, join | roaring, level 1 | 1 | 40.8 | yes | 0 / 0 | 0 | 59986052 | 55716925 | 2064654 | 26027 | 412 | 1224 | 5995800 |
| Q3 l_partkey, join | roaring, level 2 | 1 | 41.9 | yes | 0 / 37100 | 0 | 59986052 | 55722288 | 2062066 | 24314 | 412 | 1224 | 5996823 |
| Q3 l_partkey, join | roaring, level 4 | 1 | 22.8 | yes | 0 / 37100 | 55666547 | 4319505 | 22852 | 2077880 | 36244 | 412 | 1224 | 5997897 |
| Q3 l_partkey, join | Baleen off | 2 | 105.8 | yes |  |  |  |  |  |  |  |  |  |
| Q3 l_partkey, join | hash, level 1 | 2 | 130.6 | yes | 0 / 0 | 0 | 59986052 | 29323321 | 14852233 | 45525 | 203 | 609 | 3028014 |
| Q3 l_partkey, join | hash, level 2 | 2 | 102.6 | yes | 0 / 37100 | 0 | 59986052 | 29323441 | 14852366 | 50101 | 203 | 609 | 3034599 |
| Q3 l_partkey, join | hash, level 4 | 2 | 115.0 | yes | 0 / 37100 | 29321824 | 30664228 | 2358 | 14852076 | 42993 | 202 | 606 | 3027942 |
| Q3 l_partkey, join | flat, level 1 | 2 | 105.8 | yes | 0 / 0 | 0 | 59986052 | 29323045 | 14852733 | 36645 | 2950 | 6012 | 5999994 |
| Q3 l_partkey, join | flat, level 2 | 2 | 100.5 | yes | 0 / 37100 | 0 | 59986052 | 29323034 | 14852545 | 48979 | 2949 | 5985 | 5999994 |
| Q3 l_partkey, join | flat, level 4 | 2 | 124.4 | yes | 0 / 37100 | 29322496 | 30663556 | 2270 | 14852295 | 40544 | 2930 | 5964 | 5999994 |
| Q3 l_partkey, join | roaring, level 1 | 2 | 40.9 | yes | 0 / 0 | 0 | 59986052 | 55715974 | 2065031 | 26269 | 412 | 1224 | 5997795 |
| Q3 l_partkey, join | roaring, level 2 | 2 | 41.9 | yes | 0 / 37100 | 0 | 59986052 | 55718377 | 2064264 | 25574 | 412 | 1224 | 5995800 |
| Q3 l_partkey, join | roaring, level 4 | 2 | 23.1 | yes | 0 / 37100 | 55670411 | 4315641 | 22274 | 2076475 | 35326 | 411 | 1221 | 5996514 |
| Q3 l_partkey, join | Baleen off | 3 | 107.7 | yes |  |  |  |  |  |  |  |  |  |
| Q3 l_partkey, join | hash, level 1 | 3 | 106.3 | yes | 0 / 0 | 0 | 59986052 | 29322675 | 14852607 | 44018 | 203 | 609 | 3029340 |
| Q3 l_partkey, join | hash, level 2 | 3 | 103.8 | yes | 0 / 37100 | 0 | 59986052 | 29323641 | 14852470 | 39127 | 204 | 612 | 3025980 |
| Q3 l_partkey, join | hash, level 4 | 3 | 125.2 | yes | 0 / 37100 | 29323500 | 30662552 | 2373 | 14850943 | 40249 | 202 | 606 | 3026184 |
| Q3 l_partkey, join | flat, level 1 | 3 | 108.2 | yes | 0 / 0 | 0 | 59986052 | 29324224 | 14851921 | 48126 | 2949 | 5991 | 5999994 |
| Q3 l_partkey, join | flat, level 2 | 3 | 104.3 | yes | 0 / 37100 | 0 | 59986052 | 29323436 | 14852109 | 47850 | 2950 | 5982 | 5999994 |
| Q3 l_partkey, join | flat, level 4 | 3 | 121.9 | yes | 0 / 37100 | 29321845 | 30664207 | 2325 | 14852223 | 39464 | 2930 | 5916 | 5999994 |
| Q3 l_partkey, join | roaring, level 1 | 3 | 39.7 | yes | 0 / 0 | 0 | 59986052 | 55719496 | 2063112 | 24900 | 412 | 1224 | 5997114 |
| Q3 l_partkey, join | roaring, level 2 | 3 | 41.1 | yes | 0 / 37100 | 0 | 59986052 | 55721735 | 2062199 | 24413 | 412 | 1224 | 5996988 |
| Q3 l_partkey, join | roaring, level 4 | 3 | 21.1 | yes | 0 / 37100 | 55670984 | 4315068 | 22298 | 2076294 | 35542 | 412 | 1224 | 5998923 |
| Q3 l_partkey, join | Baleen off | 4 | 112.3 | yes |  |  |  |  |  |  |  |  |  |
| Q3 l_partkey, join | hash, level 1 | 4 | 114.8 | yes | 0 / 0 | 0 | 59986052 | 29322745 | 14852937 | 42478 | 203 | 609 | 3026760 |
| Q3 l_partkey, join | hash, level 2 | 4 | 102.4 | yes | 0 / 37100 | 0 | 59986052 | 29323582 | 14852547 | 40893 | 204 | 612 | 3025929 |
| Q3 l_partkey, join | hash, level 4 | 4 | 112.3 | yes | 0 / 37100 | 29320901 | 30665151 | 2275 | 14852130 | 44426 | 202 | 606 | 3029760 |
| Q3 l_partkey, join | flat, level 1 | 4 | 108.3 | yes | 0 / 0 | 0 | 59986052 | 29323107 | 14852707 | 39596 | 2950 | 6009 | 5999994 |
| Q3 l_partkey, join | flat, level 2 | 4 | 111.8 | yes | 0 / 37100 | 0 | 59986052 | 29322863 | 14853085 | 39374 | 2950 | 5985 | 5999994 |
| Q3 l_partkey, join | flat, level 4 | 4 | 128.0 | yes | 0 / 37100 | 29323716 | 30662336 | 2271 | 14851143 | 38492 | 2929 | 5922 | 5999994 |
| Q3 l_partkey, join | roaring, level 1 | 4 | 39.9 | yes | 0 / 0 | 0 | 59986052 | 55717034 | 2064544 | 25627 | 412 | 1224 | 5996205 |
| Q3 l_partkey, join | roaring, level 2 | 4 | 40.9 | yes | 0 / 37100 | 0 | 59986052 | 55720845 | 2063016 | 24812 | 412 | 1224 | 5996397 |
| Q3 l_partkey, join | roaring, level 4 | 4 | 21.7 | yes | 0 / 37100 | 55670843 | 4315209 | 23228 | 2075894 | 34816 | 412 | 1224 | 5998701 |
| Q3 l_partkey, join | Baleen off | 5 | 107.5 | yes |  |  |  |  |  |  |  |  |  |
| Q3 l_partkey, join | hash, level 1 | 5 | 106.1 | yes | 0 / 0 | 0 | 59986052 | 29323425 | 14852557 | 36131 | 204 | 612 | 3023646 |
| Q3 l_partkey, join | hash, level 2 | 5 | 100.3 | yes | 0 / 37100 | 0 | 59986052 | 29323624 | 14852611 | 36898 | 204 | 612 | 3023685 |
| Q3 l_partkey, join | hash, level 4 | 5 | 116.5 | yes | 0 / 37100 | 29322545 | 30663507 | 2336 | 14851878 | 39525 | 202 | 606 | 3026547 |
| Q3 l_partkey, join | flat, level 1 | 5 | 113.2 | yes | 0 / 0 | 0 | 59986052 | 29323473 | 14852741 | 46760 | 2950 | 5973 | 5999994 |
| Q3 l_partkey, join | flat, level 2 | 5 | 107.8 | yes | 0 / 37100 | 0 | 59986052 | 29323398 | 14852398 | 44486 | 2950 | 5994 | 5999994 |
| Q3 l_partkey, join | flat, level 4 | 5 | 128.6 | yes | 0 / 37100 | 29322436 | 30663616 | 2345 | 14851744 | 36694 | 2929 | 5940 | 5999994 |
| Q3 l_partkey, join | roaring, level 1 | 5 | 40.1 | yes | 0 / 0 | 0 | 59986052 | 55718727 | 2063859 | 25186 | 412 | 1224 | 5996985 |
| Q3 l_partkey, join | roaring, level 2 | 5 | 42.7 | yes | 0 / 37100 | 0 | 59986052 | 55716102 | 2064742 | 25632 | 411 | 1221 | 5993388 |
| Q3 l_partkey, join | roaring, level 4 | 5 | 21.2 | yes | 0 / 37100 | 55671412 | 4314640 | 23043 | 2075771 | 34622 | 411 | 1221 | 5994645 |
| Q3 l_partkey, join | Baleen off | 6 | 108.5 | yes |  |  |  |  |  |  |  |  |  |
| Q3 l_partkey, join | hash, level 1 | 6 | 102.1 | yes | 0 / 0 | 0 | 59986052 | 29323226 | 14852495 | 39765 | 204 | 612 | 3026394 |
| Q3 l_partkey, join | hash, level 2 | 6 | 97.9 | yes | 0 / 37100 | 0 | 59986052 | 29322788 | 14853106 | 44917 | 203 | 609 | 3028353 |
| Q3 l_partkey, join | hash, level 4 | 6 | 111.8 | yes | 0 / 37100 | 29322908 | 30663144 | 2344 | 14851112 | 45308 | 202 | 606 | 3028266 |
| Q3 l_partkey, join | flat, level 1 | 6 | 109.4 | yes | 0 / 0 | 0 | 59986052 | 29323785 | 14852438 | 47030 | 2949 | 5973 | 5999994 |
| Q3 l_partkey, join | flat, level 2 | 6 | 110.4 | yes | 0 / 37100 | 0 | 59986052 | 29322744 | 14852507 | 43296 | 2951 | 5958 | 5999994 |
| Q3 l_partkey, join | flat, level 4 | 6 | 114.6 | yes | 0 / 37100 | 29322491 | 30663561 | 2292 | 14851869 | 46675 | 2930 | 5940 | 5999994 |
| Q3 l_partkey, join | roaring, level 1 | 6 | 40.4 | yes | 0 / 0 | 0 | 59986052 | 55716724 | 2064648 | 25741 | 411 | 1221 | 5993211 |
| Q3 l_partkey, join | roaring, level 2 | 6 | 42.0 | yes | 0 / 37100 | 0 | 59986052 | 55717793 | 2064131 | 25419 | 412 | 1224 | 5997933 |
| Q3 l_partkey, join | roaring, level 4 | 6 | 21.7 | yes | 0 / 37100 | 55668349 | 4317703 | 22711 | 2077024 | 35973 | 411 | 1221 | 5995296 |
| Q4 l_shipmode, no join | warm-up (roaring, level 4), discarded | 0 | 8.8 | | 0 / 0 | 59974492 | 0 | 0 | 11560 | 11532 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | Baleen off | 1 | 293.1 | yes |  |  |  |  |  |  |  |  |  |
| Q4 l_shipmode, no join | hash, level 1 | 1 | 26.0 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 2 | 1 | 27.5 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 4 | 1 | 8.3 | yes | 0 / 0 | 59974492 | 0 | 0 | 11560 | 11532 | 4 | 2 | 14 |
| Q4 l_shipmode, no join | flat, level 1 | 1 | 27.1 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 2 | 1 | 26.2 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 4 | 1 | 9.1 | yes | 0 / 0 | 59974492 | 0 | 0 | 11560 | 11532 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 1 | 1 | 28.4 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 2 | 1 | 26.6 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 4 | 1 | 9.9 | yes | 0 / 0 | 59974492 | 0 | 0 | 11560 | 11532 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | Baleen off | 2 | 297.9 | yes |  |  |  |  |  |  |  |  |  |
| Q4 l_shipmode, no join | hash, level 1 | 2 | 26.6 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 2 | 2 | 29.8 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 4 | 2 | 8.8 | yes | 0 / 0 | 59974492 | 0 | 0 | 11560 | 11532 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 1 | 2 | 28.3 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 2 | 2 | 27.0 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 4 | 2 | 8.5 | yes | 0 / 0 | 59974492 | 0 | 0 | 11560 | 11532 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 1 | 2 | 27.4 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 2 | 2 | 27.4 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 4 | 2 | 9.0 | yes | 0 / 0 | 59974492 | 0 | 0 | 11560 | 11532 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | Baleen off | 3 | 301.2 | yes |  |  |  |  |  |  |  |  |  |
| Q4 l_shipmode, no join | hash, level 1 | 3 | 27.9 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 2 | 3 | 27.2 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 4 | 3 | 8.8 | yes | 0 / 0 | 59974492 | 0 | 0 | 11560 | 11532 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 1 | 3 | 26.4 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 2 | 3 | 26.9 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 4 | 3 | 8.9 | yes | 0 / 0 | 59974492 | 0 | 0 | 11560 | 11532 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 1 | 3 | 26.6 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 2 | 3 | 26.3 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 4 | 3 | 8.9 | yes | 0 / 0 | 59974492 | 0 | 0 | 11560 | 11532 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | Baleen off | 4 | 295.0 | yes |  |  |  |  |  |  |  |  |  |
| Q4 l_shipmode, no join | hash, level 1 | 4 | 28.3 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 2 | 4 | 26.4 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 4 | 4 | 9.1 | yes | 0 / 0 | 59974492 | 0 | 0 | 11560 | 11532 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 1 | 4 | 27.1 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 2 | 4 | 27.5 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 4 | 4 | 8.9 | yes | 0 / 0 | 59974492 | 0 | 0 | 11560 | 11532 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 1 | 4 | 26.5 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 2 | 4 | 28.6 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 4 | 4 | 8.4 | yes | 0 / 0 | 59974492 | 0 | 0 | 11560 | 11532 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | Baleen off | 5 | 296.0 | yes |  |  |  |  |  |  |  |  |  |
| Q4 l_shipmode, no join | hash, level 1 | 5 | 27.7 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 2 | 5 | 28.8 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 4 | 5 | 8.8 | yes | 0 / 0 | 59974492 | 0 | 0 | 11560 | 11532 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 1 | 5 | 26.4 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 2 | 5 | 27.3 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 4 | 5 | 8.5 | yes | 0 / 0 | 59974492 | 0 | 0 | 11560 | 11532 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 1 | 5 | 27.9 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 2 | 5 | 26.8 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 4 | 5 | 8.6 | yes | 0 / 0 | 59974492 | 0 | 0 | 11560 | 11532 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | Baleen off | 6 | 290.4 | yes |  |  |  |  |  |  |  |  |  |
| Q4 l_shipmode, no join | hash, level 1 | 6 | 27.7 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 2 | 6 | 27.7 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 4 | 6 | 9.2 | yes | 0 / 0 | 59974492 | 0 | 0 | 11560 | 11532 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 1 | 6 | 26.5 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 2 | 6 | 27.9 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 4 | 6 | 9.5 | yes | 0 / 0 | 59974492 | 0 | 0 | 11560 | 11532 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 1 | 6 | 27.1 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 2 | 6 | 26.9 | yes | 0 / 0 | 0 | 0 | 0 | 59986052 | 59986024 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 4 | 6 | 8.8 | yes | 0 / 0 | 59974492 | 0 | 0 | 11560 | 11532 | 4 | 3 | 21 |
