# Baleen DISTINCT filter: early-send (flush) results (2026-11-17)

Rerun of the configurations whose plan the flush change touches, on the build with the four changes of
`2026_11_16_PLAQUE_DISTINCT_FLUSH_PLAN.md`. "Before" is `2026_11_17_PLAQUE_DISTINCT_UNIFORM_BENCHMARK.md`.

- 27 configurations: Q1, Q2, Q3 × hash, flat, roaring × levels 1, 2, 3; 6 runs each, 162 runs.
- Not rerun: level 0, Q4, Baleen off (plans unchanged). Speedups use the Baleen-off medians of the uniform benchmark:
  Q1 212.7 s, Q2 158.2 s, Q3 123.2 s.
- All 162 results equal the Baleen-off result (md5 of a Baleen-off run on this build: Q1 `86ca9c0b89`, Q2 `fe689b6606`, Q3 `1169248e70`).
- Limits in the code unchanged: hash cap 1,000,000; hash skips a file only when lower bound = upper bound; flat range
  16,000,000; level 2 BIGINT only, null count 0; level 3 NOT IN list up to 10,000 values and a dictionary-encoded column.
- Server log `/scratch/asterixdb_eightynode/build_logs/asterix_distinct_flush.log`, script `bench_distinct_flush.py` there.

## Queries

Each run is one request:

```sql
USE baleen_ice;
SET `compiler.plaque.enabled` "true";
SET `compiler.plaque.distinct.set` "<hash|flat|roaring>";
SET `compiler.plaque.distinct.level` "<1|2|3>";
<query>
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

## Before and after

| Query | Variant | Runs before (s) | Runs after (s) | Median before | Median after | Speedup before | Speedup after | Files skipped per run, before | Files skipped per run, after | Rows into observer before | Rows into observer after |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Query | Variant | Runs (s) | Runs (s) | Median (s) | Median (s) | Speedup | Speedup | Files skipped | Files skipped per run | Rows into observer | Rows into observer |
| Q1 l_shipmode, join | hash, level 1 | 34.4 36.5 35.6 36.5 34.7 34.7 | 36.0 34.9 37.2 35.3 35.3 40.1 | 35.1 | 35.7 | 6.05x | 5.96x | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 16713 | 2253 |
| Q1 l_shipmode, join | hash, level 2 | 36.7 36.5 37.2 35.5 33.8 36.0 | 36.4 35.8 35.4 35.1 37.1 34.4 | 36.2 | 35.6 | 5.87x | 5.98x | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 14940 | 582 |
| Q1 l_shipmode, join | hash, level 3 | 12.3 12.5 12.9 12.4 11.8 13.8 | 12.6 13.0 12.7 14.2 12.8 12.7 | 12.4 | 12.8 | 17.10x | 16.67x | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 17465 | 582 |
| Q1 l_shipmode, join | flat, level 1 | 34.8 36.2 36.4 36.1 37.1 36.9 | 38.4 36.4 36.5 36.4 35.9 36.2 | 36.3 | 36.4 | 5.86x | 5.85x | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 17788 | 1112 |
| Q1 l_shipmode, join | flat, level 2 | 34.2 36.4 34.6 35.6 37.4 35.3 | 34.8 34.1 35.2 33.5 34.1 34.4 | 35.4 | 34.3 | 6.00x | 6.21x | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 18545 | 965 |
| Q1 l_shipmode, join | flat, level 3 | 12.8 12.6 11.9 13.4 12.6 12.5 | 12.8 12.4 12.3 12.1 12.1 12.5 | 12.6 | 12.3 | 16.89x | 17.25x | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 19096 | 1146 |
| Q1 l_shipmode, join | roaring, level 1 | 36.5 37.0 34.3 34.8 38.9 36.8 | 34.5 35.3 34.1 33.8 35.7 34.1 | 36.6 | 34.3 | 5.80x | 6.20x | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 19058 | 1146 |
| Q1 l_shipmode, join | roaring, level 2 | 35.9 33.2 33.7 32.8 34.0 34.7 | 34.3 35.0 35.2 34.9 34.2 35.4 | 33.9 | 34.9 | 6.28x | 6.09x | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 15199 | 574 |
| Q1 l_shipmode, join | roaring, level 3 | 12.2 11.5 13.0 12.9 12.7 12.8 | 13.1 12.8 13.0 13.7 12.9 11.9 | 12.7 | 13.0 | 16.73x | 16.38x | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 16184 | 1154 |
| Q2 l_suppkey, join | hash, level 1 | 41.5 43.2 41.6 39.7 43.4 42.2 | 41.5 40.3 42.7 42.6 41.1 42.6 | 41.9 | 42.1 | 3.78x | 3.76x | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 152599 | 105051 |
| Q2 l_suppkey, join | hash, level 2 | 41.0 42.7 40.5 41.7 43.9 42.7 | 40.1 42.7 42.8 45.0 42.9 41.6 | 42.2 | 42.8 | 3.75x | 3.70x | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 151689 | 103839 |
| Q2 l_suppkey, join | hash, level 3 | 40.9 42.6 42.3 42.6 43.3 43.0 | 41.5 42.6 43.6 41.8 41.6 42.0 | 42.6 | 41.9 | 3.71x | 3.77x | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 156130 | 104401 |
| Q2 l_suppkey, join | flat, level 1 | 36.6 33.2 33.7 34.6 34.7 35.4 | 34.6 32.5 32.6 35.7 32.1 33.5 | 34.7 | 33.1 | 4.56x | 4.79x | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 154038 | 105806 |
| Q2 l_suppkey, join | flat, level 2 | 22.6 23.5 19.7 33.1 17.7 18.4 | 12.9 12.7 12.7 12.6 12.9 12.0 | 21.2 | 12.7 | 7.47x | 12.45x | 14 10 17 0 18 18 | 26 26 26 26 26 26 | 153078 | 104378 |
| Q2 l_suppkey, join | flat, level 3 | 18.8 35.3 19.6 18.2 30.6 33.8 | 12.8 12.8 12.1 13.3 12.7 12.4 | 25.1 | 12.8 | 6.30x | 12.40x | 18 0 18 20 2 0 | 26 26 26 26 26 26 | 152780 | 104758 |
| Q2 l_suppkey, join | roaring, level 1 | 34.3 33.8 33.9 35.6 35.2 33.3 | 32.9 35.0 34.2 34.1 34.2 33.2 | 34.1 | 34.1 | 4.64x | 4.63x | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 159365 | 108670 |
| Q2 l_suppkey, join | roaring, level 2 | 20.3 17.6 20.6 32.8 18.2 21.0 | 13.5 12.8 12.8 12.3 12.9 13.2 | 20.4 | 12.8 | 7.75x | 12.32x | 14 21 14 1 18 14 | 26 26 26 26 26 26 | 167549 | 108129 |
| Q2 l_suppkey, join | roaring, level 3 | 31.7 32.7 16.5 31.1 15.7 23.4 | 12.3 13.4 12.7 12.8 12.4 12.6 | 27.2 | 12.7 | 5.81x | 12.48x | 0 1 22 2 24 12 | 26 26 26 26 26 26 | 161795 | 106300 |
| Q3 l_partkey, join | hash, level 1 | 99.4 105.2 114.6 115.9 101.4 99.2 | 114.6 117.4 112.4 115.1 117.2 114.6 | 103.3 | 114.9 | 1.19x | 1.07x | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 14860239 | 14845968 |
| Q3 l_partkey, join | hash, level 2 | 101.3 101.1 101.0 99.3 98.1 96.5 | 108.8 117.3 125.0 110.7 117.1 116.2 | 100.2 | 116.6 | 1.23x | 1.06x | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 14858470 | 14846928 |
| Q3 l_partkey, join | hash, level 3 | 100.2 102.1 96.5 109.4 107.6 99.7 | 118.7 114.1 115.7 113.2 111.5 112.9 | 101.1 | 113.7 | 1.22x | 1.08x | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 14858580 | 14845878 |
| Q3 l_partkey, join | flat, level 1 | 41.3 44.8 40.9 43.5 41.8 42.5 | 46.7 47.0 45.8 47.8 47.8 47.3 | 42.2 | 47.1 | 2.92x | 2.61x | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 2098077 | 2005066 |
| Q3 l_partkey, join | flat, level 2 | 42.0 41.1 41.1 43.0 40.1 41.2 | 47.3 49.0 48.2 48.2 46.9 46.7 | 41.1 | 47.7 | 2.99x | 2.58x | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 2091236 | 2004530 |
| Q3 l_partkey, join | flat, level 3 | 43.3 44.0 42.5 40.8 39.1 41.9 | 46.9 47.9 47.4 47.4 48.0 46.1 | 42.2 | 47.4 | 2.92x | 2.60x | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 2090301 | 2005096 |
| Q3 l_partkey, join | roaring, level 1 | 41.3 41.9 41.6 40.8 40.9 42.7 | 47.0 48.1 50.9 48.7 47.8 47.0 | 41.4 | 48.0 | 2.97x | 2.57x | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 2117039 | 2010916 |
| Q3 l_partkey, join | roaring, level 2 | 41.3 42.0 40.9 41.8 41.1 44.4 | 48.1 48.3 48.3 47.7 46.6 48.3 | 41.5 | 48.2 | 2.97x | 2.56x | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 2113720 | 2011996 |
| Q3 l_partkey, join | roaring, level 3 | 41.8 40.6 42.1 42.4 41.9 40.9 | 49.9 48.7 47.8 50.0 47.3 48.6 | 41.8 | 48.7 | 2.94x | 2.53x | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 2116679 | 2012233 |

## After: summary with counters (counters of the median run)

| Query | Variant | Runs (s) | Median (s) | Speedup | Result = off | Files skipped per run | Files skipped / total | Files read with row-group filter | Rows out of scan | Dropped by tuple filter | Rows into observer | Dropped by observer |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Q1 l_shipmode, join | hash, level 1 | 36.0 34.9 37.2 35.3 35.3 40.1 | 35.7 | 5.96x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 59981285 | 2253 | 2246 |
| Q1 l_shipmode, join | hash, level 2 | 36.4 35.8 35.4 35.1 37.1 34.4 | 35.6 | 5.98x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 59984850 | 582 | 575 |
| Q1 l_shipmode, join | hash, level 3 | 12.6 13.0 12.7 14.2 12.8 12.7 | 12.8 | 16.67x | yes | 0 0 0 0 0 0 | 0 / 30 | 26 | 8000000 | 7998798 | 582 | 575 |
| Q1 l_shipmode, join | flat, level 1 | 38.4 36.4 36.5 36.4 35.9 36.2 | 36.4 | 5.85x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 59983654 | 1112 | 1105 |
| Q1 l_shipmode, join | flat, level 2 | 34.8 34.1 35.2 33.5 34.1 34.4 | 34.3 | 6.21x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 59983999 | 965 | 958 |
| Q1 l_shipmode, join | flat, level 3 | 12.8 12.4 12.3 12.1 12.1 12.5 | 12.3 | 17.25x | yes | 0 0 0 0 0 0 | 0 / 30 | 26 | 8000000 | 7997601 | 1146 | 1139 |
| Q1 l_shipmode, join | roaring, level 1 | 34.5 35.3 34.1 33.8 35.7 34.1 | 34.3 | 6.20x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 59983653 | 1146 | 1139 |
| Q1 l_shipmode, join | roaring, level 2 | 34.3 35.0 35.2 34.9 34.2 35.4 | 34.9 | 6.09x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 59984854 | 574 | 567 |
| Q1 l_shipmode, join | roaring, level 3 | 13.1 12.8 13.0 13.7 12.9 11.9 | 13.0 | 16.38x | yes | 0 0 0 0 0 0 | 0 / 30 | 26 | 8000000 | 7997597 | 1154 | 1147 |
| Q2 l_suppkey, join | hash, level 1 | 41.5 40.3 42.7 42.6 41.1 42.6 | 42.1 | 3.76x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 59768823 | 105051 | 5051 |
| Q2 l_suppkey, join | hash, level 2 | 40.1 42.7 42.8 45.0 42.9 41.6 | 42.8 | 3.70x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 59771322 | 103839 | 3839 |
| Q2 l_suppkey, join | hash, level 3 | 41.5 42.6 43.6 41.8 41.6 42.0 | 41.9 | 3.77x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 59770349 | 104401 | 4401 |
| Q2 l_suppkey, join | flat, level 1 | 34.6 32.5 32.6 35.7 32.1 33.5 | 33.1 | 4.79x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 59767155 | 105806 | 5806 |
| Q2 l_suppkey, join | flat, level 2 | 12.9 12.7 12.7 12.6 12.9 12.0 | 12.7 | 12.45x | yes | 26 26 26 26 26 26 | 26 / 30 | 0 | 8000000 | 7784180 | 104378 | 4378 |
| Q2 l_suppkey, join | flat, level 3 | 12.8 12.8 12.1 13.3 12.7 12.4 | 12.8 | 12.40x | yes | 26 26 26 26 26 26 | 26 / 30 | 0 | 8000000 | 7783430 | 104758 | 4758 |
| Q2 l_suppkey, join | roaring, level 1 | 32.9 35.0 34.2 34.1 34.2 33.2 | 34.1 | 4.63x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 59761450 | 108670 | 8670 |
| Q2 l_suppkey, join | roaring, level 2 | 13.5 12.8 12.8 12.3 12.9 13.2 | 12.8 | 12.32x | yes | 26 26 26 26 26 26 | 26 / 30 | 0 | 8000000 | 7776204 | 108129 | 8129 |
| Q2 l_suppkey, join | roaring, level 3 | 12.3 13.4 12.7 12.8 12.4 12.6 | 12.7 | 12.48x | yes | 26 26 26 26 26 26 | 26 / 30 | 0 | 8000000 | 7780244 | 106300 | 6300 |
| Q3 l_partkey, join | hash, level 1 | 114.6 117.4 112.4 115.1 117.2 114.6 | 114.9 | 1.07x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 29332150 | 14845968 | 2129 |
| Q3 l_partkey, join | hash, level 2 | 108.8 117.3 125.0 110.7 117.1 116.2 | 116.6 | 1.06x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 29329760 | 14846928 | 3461 |
| Q3 l_partkey, join | hash, level 3 | 118.7 114.1 115.7 113.2 111.5 112.9 | 113.7 | 1.08x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 29331813 | 14845878 | 2393 |
| Q3 l_partkey, join | flat, level 1 | 46.7 47.0 45.8 47.8 47.8 47.3 | 47.1 | 2.61x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 55840201 | 2005066 | 5068 |
| Q3 l_partkey, join | flat, level 2 | 47.3 49.0 48.2 48.2 46.9 46.7 | 47.7 | 2.58x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 55841652 | 2004530 | 4532 |
| Q3 l_partkey, join | flat, level 3 | 46.9 47.9 47.4 47.4 48.0 46.1 | 47.4 | 2.60x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 55840499 | 2005096 | 5098 |
| Q3 l_partkey, join | roaring, level 1 | 47.0 48.1 50.9 48.7 47.8 47.0 | 48.0 | 2.57x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 55828514 | 2010916 | 10918 |
| Q3 l_partkey, join | roaring, level 2 | 48.1 48.3 48.3 47.7 46.6 48.3 | 48.2 | 2.56x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 55826542 | 2011996 | 11998 |
| Q3 l_partkey, join | roaring, level 3 | 49.9 48.7 47.8 50.0 47.3 48.6 | 48.7 | 2.53x | yes | 0 0 0 0 0 0 | 0 / 30 | 0 | 59986052 | 55825732 | 2012233 | 12235 |

## After: every run

| Query | Variant | Run | Time (s) | Result = off | Files skipped / total | Files read with row-group filter | Rows out of scan | Dropped by tuple filter | Rows into observer | Dropped by observer |
|---|---|---|---|---|---|---|---|---|---|---|
| Q1 l_shipmode, join | hash, level 1 | 1 | 36.0 | yes | 0 / 30 | 0 | 59986052 | 59981285 | 2253 | 2246 |
| Q1 l_shipmode, join | hash, level 1 | 2 | 34.9 | yes | 0 / 30 | 0 | 59986052 | 59981899 | 1945 | 1938 |
| Q1 l_shipmode, join | hash, level 1 | 3 | 37.2 | yes | 0 / 30 | 0 | 59986052 | 59984098 | 916 | 909 |
| Q1 l_shipmode, join | hash, level 1 | 4 | 35.3 | yes | 0 / 30 | 0 | 59986052 | 59983654 | 1112 | 1105 |
| Q1 l_shipmode, join | hash, level 1 | 5 | 35.3 | yes | 0 / 30 | 0 | 59986052 | 59982449 | 1692 | 1685 |
| Q1 l_shipmode, join | hash, level 1 | 6 | 40.1 | yes | 0 / 30 | 0 | 59986052 | 59984850 | 582 | 575 |
| Q1 l_shipmode, join | hash, level 2 | 1 | 36.4 | yes | 0 / 30 | 0 | 59986052 | 59983653 | 1146 | 1139 |
| Q1 l_shipmode, join | hash, level 2 | 2 | 35.8 | yes | 0 / 30 | 0 | 59986052 | 59984850 | 582 | 575 |
| Q1 l_shipmode, join | hash, level 2 | 3 | 35.4 | yes | 0 / 30 | 0 | 59986052 | 59984854 | 574 | 567 |
| Q1 l_shipmode, join | hash, level 2 | 4 | 35.1 | yes | 0 / 30 | 0 | 59986052 | 59982451 | 1728 | 1721 |
| Q1 l_shipmode, join | hash, level 2 | 5 | 37.1 | yes | 0 / 30 | 0 | 59986052 | 59984852 | 538 | 531 |
| Q1 l_shipmode, join | hash, level 2 | 6 | 34.4 | yes | 0 / 30 | 0 | 59986052 | 59984852 | 538 | 531 |
| Q1 l_shipmode, join | hash, level 3 | 1 | 12.6 | yes | 0 / 30 | 26 | 8000000 | 7995199 | 2266 | 2259 |
| Q1 l_shipmode, join | hash, level 3 | 2 | 13.0 | yes | 0 / 30 | 26 | 8000000 | 7998802 | 574 | 567 |
| Q1 l_shipmode, join | hash, level 3 | 3 | 12.7 | yes | 0 / 30 | 26 | 8000000 | 7998800 | 538 | 531 |
| Q1 l_shipmode, join | hash, level 3 | 4 | 14.2 | yes | 0 / 30 | 26 | 8000000 | 7997598 | 1120 | 1113 |
| Q1 l_shipmode, join | hash, level 3 | 5 | 12.8 | yes | 0 / 30 | 26 | 8000000 | 7998798 | 582 | 575 |
| Q1 l_shipmode, join | hash, level 3 | 6 | 12.7 | yes | 0 / 30 | 26 | 8000000 | 7996400 | 1694 | 1687 |
| Q1 l_shipmode, join | flat, level 1 | 1 | 38.4 | yes | 0 / 30 | 0 | 59986052 | 59981294 | 2248 | 2241 |
| Q1 l_shipmode, join | flat, level 1 | 2 | 36.4 | yes | 0 / 30 | 0 | 59986052 | 59983654 | 1112 | 1105 |
| Q1 l_shipmode, join | flat, level 1 | 3 | 36.5 | yes | 0 / 30 | 0 | 59986052 | 59982449 | 1692 | 1685 |
| Q1 l_shipmode, join | flat, level 1 | 4 | 36.4 | yes | 0 / 30 | 0 | 59986052 | 59984850 | 582 | 575 |
| Q1 l_shipmode, join | flat, level 1 | 5 | 35.9 | yes | 0 / 30 | 0 | 59986052 | 59984851 | 572 | 565 |
| Q1 l_shipmode, join | flat, level 1 | 6 | 36.2 | yes | 0 / 30 | 0 | 59986052 | 59982450 | 1729 | 1722 |
| Q1 l_shipmode, join | flat, level 2 | 1 | 34.8 | yes | 0 / 30 | 0 | 59986052 | 59981251 | 2266 | 2259 |
| Q1 l_shipmode, join | flat, level 2 | 2 | 34.1 | yes | 0 / 30 | 0 | 59986052 | 59984851 | 572 | 565 |
| Q1 l_shipmode, join | flat, level 2 | 3 | 35.2 | yes | 0 / 30 | 0 | 59986052 | 59983652 | 1156 | 1149 |
| Q1 l_shipmode, join | flat, level 2 | 4 | 33.5 | yes | 0 / 30 | 0 | 59986052 | 59983650 | 1120 | 1113 |
| Q1 l_shipmode, join | flat, level 2 | 5 | 34.1 | yes | 0 / 30 | 0 | 59986052 | 59984851 | 572 | 565 |
| Q1 l_shipmode, join | flat, level 2 | 6 | 34.4 | yes | 0 / 30 | 0 | 59986052 | 59983999 | 965 | 958 |
| Q1 l_shipmode, join | flat, level 3 | 1 | 12.8 | yes | 0 / 30 | 26 | 8000000 | 7997593 | 1193 | 1186 |
| Q1 l_shipmode, join | flat, level 3 | 2 | 12.4 | yes | 0 / 30 | 26 | 8000000 | 7997601 | 1146 | 1139 |
| Q1 l_shipmode, join | flat, level 3 | 3 | 12.3 | yes | 0 / 30 | 26 | 8000000 | 7998799 | 572 | 565 |
| Q1 l_shipmode, join | flat, level 3 | 4 | 12.1 | yes | 0 / 30 | 26 | 8000000 | 7997602 | 1112 | 1105 |
| Q1 l_shipmode, join | flat, level 3 | 5 | 12.1 | yes | 0 / 30 | 26 | 8000000 | 7997601 | 1146 | 1139 |
| Q1 l_shipmode, join | flat, level 3 | 6 | 12.5 | yes | 0 / 30 | 26 | 8000000 | 7996401 | 1684 | 1677 |
| Q1 l_shipmode, join | roaring, level 1 | 1 | 34.5 | yes | 0 / 30 | 0 | 59986052 | 59983653 | 1146 | 1139 |
| Q1 l_shipmode, join | roaring, level 1 | 2 | 35.3 | yes | 0 / 30 | 0 | 59986052 | 59983650 | 1120 | 1113 |
| Q1 l_shipmode, join | roaring, level 1 | 3 | 34.1 | yes | 0 / 30 | 0 | 59986052 | 59983649 | 1154 | 1147 |
| Q1 l_shipmode, join | roaring, level 1 | 4 | 33.8 | yes | 0 / 30 | 0 | 59986052 | 59981251 | 2266 | 2259 |
| Q1 l_shipmode, join | roaring, level 1 | 5 | 35.7 | yes | 0 / 30 | 0 | 59986052 | 59984854 | 574 | 567 |
| Q1 l_shipmode, join | roaring, level 1 | 6 | 34.1 | yes | 0 / 30 | 0 | 59986052 | 59984851 | 572 | 565 |
| Q1 l_shipmode, join | roaring, level 2 | 1 | 34.3 | yes | 0 / 30 | 0 | 59986052 | 59984851 | 572 | 565 |
| Q1 l_shipmode, join | roaring, level 2 | 2 | 35.0 | yes | 0 / 30 | 0 | 59986052 | 59984854 | 574 | 567 |
| Q1 l_shipmode, join | roaring, level 2 | 3 | 35.2 | yes | 0 / 30 | 0 | 59986052 | 59983649 | 1154 | 1147 |
| Q1 l_shipmode, join | roaring, level 2 | 4 | 34.9 | yes | 0 / 30 | 0 | 59986052 | 59984852 | 538 | 531 |
| Q1 l_shipmode, join | roaring, level 2 | 5 | 34.2 | yes | 0 / 30 | 0 | 59986052 | 59983649 | 1154 | 1147 |
| Q1 l_shipmode, join | roaring, level 2 | 6 | 35.4 | yes | 0 / 30 | 0 | 59986052 | 59984850 | 582 | 575 |
| Q1 l_shipmode, join | roaring, level 3 | 1 | 13.1 | yes | 0 / 30 | 26 | 8000000 | 7996401 | 1684 | 1677 |
| Q1 l_shipmode, join | roaring, level 3 | 2 | 12.8 | yes | 0 / 30 | 26 | 8000000 | 7998800 | 538 | 531 |
| Q1 l_shipmode, join | roaring, level 3 | 3 | 13.0 | yes | 0 / 30 | 26 | 8000000 | 7997597 | 1154 | 1147 |
| Q1 l_shipmode, join | roaring, level 3 | 4 | 13.7 | yes | 0 / 30 | 26 | 8000000 | 7996400 | 1694 | 1687 |
| Q1 l_shipmode, join | roaring, level 3 | 5 | 12.9 | yes | 0 / 30 | 26 | 8000000 | 7997600 | 1156 | 1149 |
| Q1 l_shipmode, join | roaring, level 3 | 6 | 11.9 | yes | 0 / 30 | 26 | 8000000 | 7998798 | 582 | 575 |
| Q2 l_suppkey, join | hash, level 1 | 1 | 41.5 | yes | 0 / 30 | 0 | 59986052 | 59767052 | 105950 | 5950 |
| Q2 l_suppkey, join | hash, level 1 | 2 | 40.3 | yes | 0 / 30 | 0 | 59986052 | 59766124 | 106367 | 6367 |
| Q2 l_suppkey, join | hash, level 1 | 3 | 42.7 | yes | 0 / 30 | 0 | 59986052 | 59769949 | 104524 | 4524 |
| Q2 l_suppkey, join | hash, level 1 | 4 | 42.6 | yes | 0 / 30 | 0 | 59986052 | 59768823 | 105051 | 5051 |
| Q2 l_suppkey, join | hash, level 1 | 5 | 41.1 | yes | 0 / 30 | 0 | 59986052 | 59770215 | 104362 | 4362 |
| Q2 l_suppkey, join | hash, level 1 | 6 | 42.6 | yes | 0 / 30 | 0 | 59986052 | 59770827 | 104158 | 4158 |
| Q2 l_suppkey, join | hash, level 2 | 1 | 40.1 | yes | 0 / 30 | 0 | 59986052 | 59769528 | 104750 | 4750 |
| Q2 l_suppkey, join | hash, level 2 | 2 | 42.7 | yes | 0 / 30 | 0 | 59986052 | 59769494 | 104722 | 4722 |
| Q2 l_suppkey, join | hash, level 2 | 3 | 42.8 | yes | 0 / 30 | 0 | 59986052 | 59771322 | 103839 | 3839 |
| Q2 l_suppkey, join | hash, level 2 | 4 | 45.0 | yes | 0 / 30 | 0 | 59986052 | 59767803 | 105467 | 5467 |
| Q2 l_suppkey, join | hash, level 2 | 5 | 42.9 | yes | 0 / 30 | 0 | 59986052 | 59768252 | 105277 | 5277 |
| Q2 l_suppkey, join | hash, level 2 | 6 | 41.6 | yes | 0 / 30 | 0 | 59986052 | 59770516 | 104262 | 4262 |
| Q2 l_suppkey, join | hash, level 3 | 1 | 41.5 | yes | 0 / 30 | 0 | 59986052 | 59770893 | 104134 | 4134 |
| Q2 l_suppkey, join | hash, level 3 | 2 | 42.6 | yes | 0 / 30 | 0 | 59986052 | 59766551 | 106224 | 6224 |
| Q2 l_suppkey, join | hash, level 3 | 3 | 43.6 | yes | 0 / 30 | 0 | 59986052 | 59767573 | 105763 | 5763 |
| Q2 l_suppkey, join | hash, level 3 | 4 | 41.8 | yes | 0 / 30 | 0 | 59986052 | 59769081 | 104944 | 4944 |
| Q2 l_suppkey, join | hash, level 3 | 5 | 41.6 | yes | 0 / 30 | 0 | 59986052 | 59770671 | 104150 | 4150 |
| Q2 l_suppkey, join | hash, level 3 | 6 | 42.0 | yes | 0 / 30 | 0 | 59986052 | 59770349 | 104401 | 4401 |
| Q2 l_suppkey, join | flat, level 1 | 1 | 34.6 | yes | 0 / 30 | 0 | 59986052 | 59767044 | 105875 | 5875 |
| Q2 l_suppkey, join | flat, level 1 | 2 | 32.5 | yes | 0 / 30 | 0 | 59986052 | 59768074 | 105481 | 5481 |
| Q2 l_suppkey, join | flat, level 1 | 3 | 32.6 | yes | 0 / 30 | 0 | 59986052 | 59768040 | 105435 | 5435 |
| Q2 l_suppkey, join | flat, level 1 | 4 | 35.7 | yes | 0 / 30 | 0 | 59986052 | 59768524 | 105232 | 5232 |
| Q2 l_suppkey, join | flat, level 1 | 5 | 32.1 | yes | 0 / 30 | 0 | 59986052 | 59770128 | 104415 | 4415 |
| Q2 l_suppkey, join | flat, level 1 | 6 | 33.5 | yes | 0 / 30 | 0 | 59986052 | 59767155 | 105806 | 5806 |
| Q2 l_suppkey, join | flat, level 2 | 1 | 12.9 | yes | 26 / 30 | 0 | 8000000 | 7779630 | 106561 | 6561 |
| Q2 l_suppkey, join | flat, level 2 | 2 | 12.7 | yes | 26 / 30 | 0 | 8000000 | 7784408 | 104315 | 4315 |
| Q2 l_suppkey, join | flat, level 2 | 3 | 12.7 | yes | 26 / 30 | 0 | 8000000 | 7784180 | 104378 | 4378 |
| Q2 l_suppkey, join | flat, level 2 | 4 | 12.6 | yes | 26 / 30 | 0 | 8000000 | 7782081 | 105357 | 5357 |
| Q2 l_suppkey, join | flat, level 2 | 5 | 12.9 | yes | 26 / 30 | 0 | 8000000 | 7782180 | 105436 | 5436 |
| Q2 l_suppkey, join | flat, level 2 | 6 | 12.0 | yes | 26 / 30 | 0 | 8000000 | 7779149 | 106849 | 6849 |
| Q2 l_suppkey, join | flat, level 3 | 1 | 12.8 | yes | 26 / 30 | 0 | 8000000 | 7783430 | 104758 | 4758 |
| Q2 l_suppkey, join | flat, level 3 | 2 | 12.8 | yes | 26 / 30 | 0 | 8000000 | 7782420 | 105261 | 5261 |
| Q2 l_suppkey, join | flat, level 3 | 3 | 12.1 | yes | 26 / 30 | 0 | 8000000 | 7781829 | 105520 | 5520 |
| Q2 l_suppkey, join | flat, level 3 | 4 | 13.3 | yes | 26 / 30 | 0 | 8000000 | 7781801 | 105527 | 5527 |
| Q2 l_suppkey, join | flat, level 3 | 5 | 12.7 | yes | 26 / 30 | 0 | 8000000 | 7784227 | 104355 | 4355 |
| Q2 l_suppkey, join | flat, level 3 | 6 | 12.4 | yes | 26 / 30 | 0 | 8000000 | 7781002 | 105985 | 5985 |
| Q2 l_suppkey, join | roaring, level 1 | 1 | 32.9 | yes | 0 / 30 | 0 | 59986052 | 59756346 | 111099 | 11099 |
| Q2 l_suppkey, join | roaring, level 1 | 2 | 35.0 | yes | 0 / 30 | 0 | 59986052 | 59765474 | 106591 | 6591 |
| Q2 l_suppkey, join | roaring, level 1 | 3 | 34.2 | yes | 0 / 30 | 0 | 59986052 | 59761450 | 108670 | 8670 |
| Q2 l_suppkey, join | roaring, level 1 | 4 | 34.1 | yes | 0 / 30 | 0 | 59986052 | 59762973 | 107929 | 7929 |
| Q2 l_suppkey, join | roaring, level 1 | 5 | 34.2 | yes | 0 / 30 | 0 | 59986052 | 59765677 | 106638 | 6638 |
| Q2 l_suppkey, join | roaring, level 1 | 6 | 33.2 | yes | 0 / 30 | 0 | 59986052 | 59762823 | 107958 | 7958 |
| Q2 l_suppkey, join | roaring, level 2 | 1 | 13.5 | yes | 26 / 30 | 0 | 8000000 | 7778031 | 107400 | 7400 |
| Q2 l_suppkey, join | roaring, level 2 | 2 | 12.8 | yes | 26 / 30 | 0 | 8000000 | 7778577 | 107148 | 7148 |
| Q2 l_suppkey, join | roaring, level 2 | 3 | 12.8 | yes | 26 / 30 | 0 | 8000000 | 7777312 | 107699 | 7699 |
| Q2 l_suppkey, join | roaring, level 2 | 4 | 12.3 | yes | 26 / 30 | 0 | 8000000 | 7772624 | 109944 | 9944 |
| Q2 l_suppkey, join | roaring, level 2 | 5 | 12.9 | yes | 26 / 30 | 0 | 8000000 | 7776204 | 108129 | 8129 |
| Q2 l_suppkey, join | roaring, level 2 | 6 | 13.2 | yes | 26 / 30 | 0 | 8000000 | 7779251 | 106713 | 6713 |
| Q2 l_suppkey, join | roaring, level 3 | 1 | 12.3 | yes | 26 / 30 | 0 | 8000000 | 7774994 | 108900 | 8900 |
| Q2 l_suppkey, join | roaring, level 3 | 2 | 13.4 | yes | 26 / 30 | 0 | 8000000 | 7778722 | 106979 | 6979 |
| Q2 l_suppkey, join | roaring, level 3 | 3 | 12.7 | yes | 26 / 30 | 0 | 8000000 | 7780244 | 106300 | 6300 |
| Q2 l_suppkey, join | roaring, level 3 | 4 | 12.8 | yes | 26 / 30 | 0 | 8000000 | 7781265 | 105749 | 5749 |
| Q2 l_suppkey, join | roaring, level 3 | 5 | 12.4 | yes | 26 / 30 | 0 | 8000000 | 7778580 | 107101 | 7101 |
| Q2 l_suppkey, join | roaring, level 3 | 6 | 12.6 | yes | 26 / 30 | 0 | 8000000 | 7775187 | 108632 | 8632 |
| Q3 l_partkey, join | hash, level 1 | 1 | 114.6 | yes | 0 / 30 | 0 | 59986052 | 29332259 | 14845652 | 2332 |
| Q3 l_partkey, join | hash, level 1 | 2 | 117.4 | yes | 0 / 30 | 0 | 59986052 | 29331230 | 14846464 | 2391 |
| Q3 l_partkey, join | hash, level 1 | 3 | 112.4 | yes | 0 / 30 | 0 | 59986052 | 29332477 | 14845165 | 2236 |
| Q3 l_partkey, join | hash, level 1 | 4 | 115.1 | yes | 0 / 30 | 0 | 59986052 | 29332150 | 14845968 | 2129 |
| Q3 l_partkey, join | hash, level 1 | 5 | 117.2 | yes | 0 / 30 | 0 | 59986052 | 29331515 | 14845669 | 2343 |
| Q3 l_partkey, join | hash, level 1 | 6 | 114.6 | yes | 0 / 30 | 0 | 59986052 | 29332104 | 14845671 | 2294 |
| Q3 l_partkey, join | hash, level 2 | 1 | 108.8 | yes | 0 / 30 | 0 | 59986052 | 29331666 | 14846106 | 2279 |
| Q3 l_partkey, join | hash, level 2 | 2 | 117.3 | yes | 0 / 30 | 0 | 59986052 | 29332644 | 14845780 | 1942 |
| Q3 l_partkey, join | hash, level 2 | 3 | 125.0 | yes | 0 / 30 | 0 | 59986052 | 29330860 | 14846350 | 2914 |
| Q3 l_partkey, join | hash, level 2 | 4 | 110.7 | yes | 0 / 30 | 0 | 59986052 | 29331288 | 14846108 | 2179 |
| Q3 l_partkey, join | hash, level 2 | 5 | 117.1 | yes | 0 / 30 | 0 | 59986052 | 29329760 | 14846928 | 3461 |
| Q3 l_partkey, join | hash, level 2 | 6 | 116.2 | yes | 0 / 30 | 0 | 59986052 | 29329702 | 14846477 | 3543 |
| Q3 l_partkey, join | hash, level 3 | 1 | 118.7 | yes | 0 / 30 | 0 | 59986052 | 29330610 | 14846119 | 2844 |
| Q3 l_partkey, join | hash, level 3 | 2 | 114.1 | yes | 0 / 30 | 0 | 59986052 | 29331813 | 14845878 | 2393 |
| Q3 l_partkey, join | hash, level 3 | 3 | 115.7 | yes | 0 / 30 | 0 | 59986052 | 29331840 | 14845852 | 2620 |
| Q3 l_partkey, join | hash, level 3 | 4 | 113.2 | yes | 0 / 30 | 0 | 59986052 | 29330375 | 14846478 | 3221 |
| Q3 l_partkey, join | hash, level 3 | 5 | 111.5 | yes | 0 / 30 | 0 | 59986052 | 29330976 | 14846131 | 2595 |
| Q3 l_partkey, join | hash, level 3 | 6 | 112.9 | yes | 0 / 30 | 0 | 59986052 | 29331421 | 14845473 | 2494 |
| Q3 l_partkey, join | flat, level 1 | 1 | 46.7 | yes | 0 / 30 | 0 | 59986052 | 55840441 | 2005132 | 5134 |
| Q3 l_partkey, join | flat, level 1 | 2 | 47.0 | yes | 0 / 30 | 0 | 59986052 | 55841695 | 2004558 | 4560 |
| Q3 l_partkey, join | flat, level 1 | 3 | 45.8 | yes | 0 / 30 | 0 | 59986052 | 55841145 | 2004788 | 4790 |
| Q3 l_partkey, join | flat, level 1 | 4 | 47.8 | yes | 0 / 30 | 0 | 59986052 | 55841826 | 2004435 | 4437 |
| Q3 l_partkey, join | flat, level 1 | 5 | 47.8 | yes | 0 / 30 | 0 | 59986052 | 55839066 | 2005698 | 5700 |
| Q3 l_partkey, join | flat, level 1 | 6 | 47.3 | yes | 0 / 30 | 0 | 59986052 | 55840201 | 2005066 | 5068 |
| Q3 l_partkey, join | flat, level 2 | 1 | 47.3 | yes | 0 / 30 | 0 | 59986052 | 55839820 | 2005228 | 5230 |
| Q3 l_partkey, join | flat, level 2 | 2 | 49.0 | yes | 0 / 30 | 0 | 59986052 | 55839614 | 2005555 | 5557 |
| Q3 l_partkey, join | flat, level 2 | 3 | 48.2 | yes | 0 / 30 | 0 | 59986052 | 55841287 | 2004696 | 4698 |
| Q3 l_partkey, join | flat, level 2 | 4 | 48.2 | yes | 0 / 30 | 0 | 59986052 | 55841652 | 2004530 | 4532 |
| Q3 l_partkey, join | flat, level 2 | 5 | 46.9 | yes | 0 / 30 | 0 | 59986052 | 55840968 | 2004983 | 4985 |
| Q3 l_partkey, join | flat, level 2 | 6 | 46.7 | yes | 0 / 30 | 0 | 59986052 | 55841267 | 2004792 | 4794 |
| Q3 l_partkey, join | flat, level 3 | 1 | 46.9 | yes | 0 / 30 | 0 | 59986052 | 55840416 | 2005163 | 5165 |
| Q3 l_partkey, join | flat, level 3 | 2 | 47.9 | yes | 0 / 30 | 0 | 59986052 | 55841334 | 2004831 | 4833 |
| Q3 l_partkey, join | flat, level 3 | 3 | 47.4 | yes | 0 / 30 | 0 | 59986052 | 55840499 | 2005096 | 5098 |
| Q3 l_partkey, join | flat, level 3 | 4 | 47.4 | yes | 0 / 30 | 0 | 59986052 | 55841330 | 2004793 | 4795 |
| Q3 l_partkey, join | flat, level 3 | 5 | 48.0 | yes | 0 / 30 | 0 | 59986052 | 55840985 | 2004933 | 4935 |
| Q3 l_partkey, join | flat, level 3 | 6 | 46.1 | yes | 0 / 30 | 0 | 59986052 | 55840686 | 2004827 | 4829 |
| Q3 l_partkey, join | roaring, level 1 | 1 | 47.0 | yes | 0 / 30 | 0 | 59986052 | 55826621 | 2011815 | 11817 |
| Q3 l_partkey, join | roaring, level 1 | 2 | 48.1 | yes | 0 / 30 | 0 | 59986052 | 55828514 | 2010916 | 10918 |
| Q3 l_partkey, join | roaring, level 1 | 3 | 50.9 | yes | 0 / 30 | 0 | 59986052 | 55828639 | 2010856 | 10858 |
| Q3 l_partkey, join | roaring, level 1 | 4 | 48.7 | yes | 0 / 30 | 0 | 59986052 | 55828775 | 2010684 | 10686 |
| Q3 l_partkey, join | roaring, level 1 | 5 | 47.8 | yes | 0 / 30 | 0 | 59986052 | 55828381 | 2011021 | 11023 |
| Q3 l_partkey, join | roaring, level 1 | 6 | 47.0 | yes | 0 / 30 | 0 | 59986052 | 55826922 | 2011676 | 11678 |
| Q3 l_partkey, join | roaring, level 2 | 1 | 48.1 | yes | 0 / 30 | 0 | 59986052 | 55828097 | 2011067 | 11069 |
| Q3 l_partkey, join | roaring, level 2 | 2 | 48.3 | yes | 0 / 30 | 0 | 59986052 | 55828510 | 2010862 | 10864 |
| Q3 l_partkey, join | roaring, level 2 | 3 | 48.3 | yes | 0 / 30 | 0 | 59986052 | 55827365 | 2011495 | 11497 |
| Q3 l_partkey, join | roaring, level 2 | 4 | 47.7 | yes | 0 / 30 | 0 | 59986052 | 55824997 | 2012701 | 12703 |
| Q3 l_partkey, join | roaring, level 2 | 5 | 46.6 | yes | 0 / 30 | 0 | 59986052 | 55825992 | 2012117 | 12119 |
| Q3 l_partkey, join | roaring, level 2 | 6 | 48.3 | yes | 0 / 30 | 0 | 59986052 | 55826542 | 2011996 | 11998 |
| Q3 l_partkey, join | roaring, level 3 | 1 | 49.9 | yes | 0 / 30 | 0 | 59986052 | 55829070 | 2010591 | 10593 |
| Q3 l_partkey, join | roaring, level 3 | 2 | 48.7 | yes | 0 / 30 | 0 | 59986052 | 55825732 | 2012233 | 12235 |
| Q3 l_partkey, join | roaring, level 3 | 3 | 47.8 | yes | 0 / 30 | 0 | 59986052 | 55827805 | 2011241 | 11243 |
| Q3 l_partkey, join | roaring, level 3 | 4 | 50.0 | yes | 0 / 30 | 0 | 59986052 | 55828939 | 2010578 | 10580 |
| Q3 l_partkey, join | roaring, level 3 | 5 | 47.3 | yes | 0 / 30 | 0 | 59986052 | 55826754 | 2011779 | 11781 |
| Q3 l_partkey, join | roaring, level 3 | 6 | 48.6 | yes | 0 / 30 | 0 | 59986052 | 55827697 | 2011331 | 11333 |

## What the numbers say

- **Q2: file skipping is stable.** Flat and roaring at levels 2 and 3 skip 26 of 30 files in all 24 runs (before: 0 to 24). Medians 12.7 to 12.8 s, 12.3x to 12.5x over Baleen off (before: 20.4 to 27.2 s).
- **Q1: unchanged**, within 2 s in both directions.
- **Q3: slower.** Flat and roaring take 47.1 to 48.7 s (before 41.1 to 42.2 s); hash takes 113.7 to 116.6 s (before 100.2 to 103.3 s). No files skipped, as before.
- **Fewer rows reach the observer** in every configuration with a selective filter: Q1 about 600 to 2,300 (before 15,000 to 19,000); Q2 about 104,000 to 108,000 (before 152,000 to 168,000).
- Hash never skips a file (range test passes only for a single-value file), so Q2 hash stays at about 42 s.

## Bug found: the observer forwarded early sends

The observer and the tuple filter share one runtime class, whose `flush()` handed the output frame on and flushed the
next operator. With the join now flushing after every probe frame, the observer passed each of those flushes on to the
hash exchange above it, which sent a partly filled frame to each of the 4 sort partitions per probe frame. Nothing
above the observer waits for those rows. Fixed after this benchmark: the observer's `flush()` does nothing.

Single runs after the fix (not a benchmark): Q3 roaring level 1 55.5 s (first query after restart), 46.9 s, 43.6 s;
Q2 roaring level 2 14.2 s and 13.2 s with 26 of 30 files skipped. Whether this removes the Q3 cost needs a 6-run rerun.

## Decisions I made without asking

- Reran 27 configurations, not all 48, following "only rerun where you would expect to see changes".
- Compared results against the md5 of a Baleen-off run on the same build instead of fresh Baleen-off runs.
- Made the observer swallow `flush()` after seeing the Q3 slowdown.

## Stated but not verified

- That the observer forwarding flushes is the cause of the Q3 slowdown. Three single runs after the fix are not enough.
- Whether the join spills probe rows to disk at 2 GB.

## Added later the same day: frame counters and a switch per flush point

Purpose: stop guessing where the early send costs time. No default behaviour changed.

**New setting** `compiler.plaque.distinct.flush`, a string of letters, default `"fj"` (what the code did before):

| Letter | Flush point |
|---|---|
| `f` | tuple filter sends what it kept after every input frame |
| `j` | hash join sends its buffered results after every probe frame |
| `o` | observer passes an early send on to the operators above it |

**New counters** (server log, one line per task):

- `PLAQUE_DISTINCT_FILTER_STATS` and `PLAQUE_DISTINCT_OBSERVER_STATS` now also print `framesIn`, `flushesIn`,
  `framesOut`, `earlySends` (frames sent partly filled) and `flush` (the switch).
- `PLAQUE_JOIN_PROBE_STATS [job, partition] framesIn, flush`: frames arriving at the probe side of a hybrid hash
  join under a DISTINCT filter. The in-memory hash join has the flush but not this counter.

**Check that the counters work: one run each, roaring, level 2, table `lineitem`. Not a measurement.**

Query text, `<F>` being the flush setting:

```
USE baleen_ice;
SET `compiler.plaque.enabled` "true"; SET `compiler.plaque.distinct.set` "roaring";
SET `compiler.plaque.distinct.level` "2"; SET `compiler.plaque.distinct.flush` "<F>";
SELECT DISTINCT l.l_suppkey FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
SELECT DISTINCT l.l_partkey FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
```

Frames are summed over the 4 partitions.

| Query | Flush | Time (s) | Files skipped | Rows scanned | Filter frames out | Join probe frames in | Observer frames in | Observer frames out |
|---|---|---|---|---|---|---|---|---|
| Q2 l_suppkey | `""` | 17.2 | 22 / 30 | 16,000,000 | 329 | 339 | 92 | 51 |
| Q2 l_suppkey | `"fj"` | 19.5 | 26 / 30 | 8,000,000 | 1,453 | 5,812 | 4,207 | 49 |
| Q2 l_suppkey | `"fjo"` | 13.0 | 26 / 30 | 8,000,000 | 1,452 | 5,808 | 4,200 | 4,200 |
| Q3 l_partkey | `""` | 41.3 | 0 / 30 | 59,986,052 | 4,017 | 4,026 | 1,103 | 1,035 |
| Q3 l_partkey | `"fj"` | 46.3 | 0 / 30 | 59,986,052 | 28,568 | 114,272 | 82,461 | 1,036 |
| Q3 l_partkey | `"fjo"` | 48.9 | 0 / 30 | 59,986,052 | 28,607 | 114,428 | 82,466 | 82,466 |

What the counters show (the times are single runs and show nothing):

- With `f`, every join partition receives exactly as many probe frames as the 4 filter tasks send in total
  (1,453 each on Q2, 28,568 each on Q3). One early send by the filter becomes one frame to each of the 4 join
  partitions at the hash exchange.
- On Q3 the join receives 114,272 probe frames instead of 4,026, about 28 times as many, for the same rows.
- With `o` off the observer sends about as many frames as with no early sends at all (1,036 against 1,035).
- No early send reaches the observer unless `j` is on (`flushesIn` is 0 with `""`).
