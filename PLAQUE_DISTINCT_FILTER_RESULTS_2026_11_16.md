# Baleen DISTINCT filter: first results (2026-11-16)

## Outcome

The DISTINCT filter is built for levels 0 to 3 and returns the same rows as Baleen off on every query and
variant tested. On TPC-H SF10 Iceberg tables it makes `SELECT DISTINCT` 3x to 17x faster with a join and
105x faster without one.

| Query | Baleen off | Best variant | Time | Speedup |
|---|---|---|---|---|
| Q1 `l_shipmode`, join (7 values) | 211.1 s | hash set, row-group skipping | 12.6 s | 16.8x |
| Q2 `l_suppkey`, join (100,000 values) | 162.0 s | Roaring bitmap, file skipping | 20.1 s | 8.1x |
| Q3 `l_partkey`, join (1,999,998 values) | 139.8 s | Roaring bitmap, file skipping | 42.5 s | 3.3x |
| Q4 `l_shipmode`, no join (7 values) | 388.7 s | hash set, row-group skipping | 3.7 s | 105x |

Nothing is committed on the code branch yet. One design decision is open (frame stall, below).

## Setup

- sensoria-2, 4 node controllers in one JVM, join memory 2 GB, sort memory 4 MB, frame 32 KB.
- Dataverse `baleen_ice`: `lineitem` 59,986,052 rows in 30 Parquet files, `orders` 15,000,000 rows.
- Join queries: `SELECT DISTINCT l.<col> FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND
  o.o_orderstatus = 'F'`. This order makes lineitem the probe side. 29,058,120 rows leave the join.
- Baleen off: one run. Every variant: three runs, median reported. Result compared with Baleen off by
  checksum of the sorted rows.
- Settings: `compiler.plaque.enabled`, `compiler.plaque.distinct.set` (`hash`, `flat`, `roaring`),
  `compiler.plaque.distinct.level` (0 to 3).

Levels:

| Level | What is active |
|---|---|
| 0 | Observer above the join: records values, drops duplicates before the sort |
| 1 | Level 0 + tuple filter above the lineitem scan |
| 2 | Level 1 + skip a file when every value in its min-max range is already seen |
| 3 | Level 2 + `column NOT IN (seen)` given to the Parquet reader, which skips row groups by dictionary |

## Queries

Every statement is sent as one request to `http://localhost:19002/query/service`.

Q1, Baleen off:

```sql
USE baleen_ice;
SELECT DISTINCT l.l_shipmode FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
```

Q2, Baleen off:

```sql
USE baleen_ice;
SELECT DISTINCT l.l_suppkey FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
```

Q3, Baleen off:

```sql
USE baleen_ice;
SELECT DISTINCT l.l_partkey FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
```

Q4, Baleen off:

```sql
USE baleen_ice;
SELECT DISTINCT l.l_shipmode FROM lineitem l;
```

A Baleen variant is the same query with three settings in front. Example, Q2 with the Roaring bitmap at level 2:

```sql
USE baleen_ice;
SET `compiler.plaque.enabled` "true";
SET `compiler.plaque.distinct.set` "roaring";
SET `compiler.plaque.distinct.level` "2";
SELECT DISTINCT l.l_suppkey FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
```

A variant named "`<set>`, level `<n>`" in the tables uses `compiler.plaque.distinct.set` = `<set>` and
`compiler.plaque.distinct.level` = `<n>`.

Lineitem as build side (last section) uses the other FROM order:

```sql
USE baleen_ice;
SELECT DISTINCT l.l_shipmode FROM orders o, lineitem l WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
```

From 2026-11-16 on, every configuration including Baleen off gets 6 runs. The runs in this document
(1 for Baleen off, 3 per variant) were done before that rule.

## Full results

Counters are from the median run.

### Q1 `l_shipmode`, join

| Variant | Runs (s) | Median | Speedup | Files read with row-group filter | Rows out of scan | Dropped by tuple filter | Rows into observer |
|---|---|---|---|---|---|---|---|
| Baleen off | 211.1 | 211.1 | 1.00x | | 59,986,052 | | |
| hash, level 0 | 36.3 35.9 42.3 | 36.3 | 5.81x | 0 | 59,986,052 | 0 | 29,058,120 |
| hash, level 1 | 36.8 35.8 33.5 | 35.8 | 5.89x | 0 | 59,986,052 | 59,953,529 | 15,567 |
| hash, level 3 | 13.0 12.1 12.6 | 12.6 | 16.75x | 26 of 30 | 8,000,000 | 7,967,316 | 15,635 |

### Q2 `l_suppkey`, join

| Variant | Runs (s) | Median | Speedup | Files skipped | Rows out of scan | Dropped by tuple filter | Rows into observer |
|---|---|---|---|---|---|---|---|
| Baleen off | 162.0 | 162.0 | 1.00x | | 59,986,052 | | |
| hash, level 0 | 36.7 35.6 35.3 | 35.6 | 4.55x | 0 | 59,986,052 | 0 | 29,058,120 |
| hash, level 1 | 41.7 41.8 42.4 | 41.8 | 3.88x | 0 | 59,986,052 | 59,666,082 | 154,691 |
| flat, level 1 | 41.6 42.4 42.0 | 42.0 | 3.86x | 0 | 59,986,052 | 59,667,170 | 154,076 |
| roaring, level 1 | 34.0 33.8 36.2 | 34.0 | 4.76x | 0 | 59,986,052 | 59,640,064 | 167,255 |
| flat, level 2 | 33.2 21.6 33.1 | 33.1 | 4.90x | 0 of 30 | 59,986,052 | 59,664,185 | 155,703 |
| roaring, level 2 | 20.1 24.3 18.4 | 20.1 | 8.07x | 20 of 30 | 20,000,000 | 19,649,237 | 169,671 |

### Q3 `l_partkey`, join

| Variant | Runs (s) | Median | Speedup | Files skipped | Dropped by tuple filter | Rows into observer | Dropped by observer |
|---|---|---|---|---|---|---|---|
| Baleen off | 139.8 | 139.8 | 1.00x | | | | |
| hash, level 0 | 94.5 94.9 94.7 | 94.7 | 1.48x | 0 | 0 | 29,058,120 | 14,214,737 |
| hash, level 1 | 102.3 97.0 97.7 | 97.7 | 1.43x | 0 | 29,304,885 | 14,858,181 | 15,506 |
| flat, level 1 | 113.6 124.2 112.8 | 113.6 | 1.23x | 0 | 29,304,953 | 14,858,554 | 15,295 |
| roaring, level 1 | 44.0 42.8 47.5 | 44.0 | 3.18x | 0 | 55,607,364 | 2,118,037 | 118,039 |
| roaring, level 2 | 45.3 42.5 40.5 | 42.5 | 3.29x | 0 of 30 | 55,615,516 | 2,114,292 | 114,294 |

### Q4 `l_shipmode`, no join

Measured after the fix described under "Bug found by the benchmark". The benchmark's own Q4 rows
(386.2 s and 387.9 s) are from before the fix, when the observer did nothing, and are not valid.

| Variant | Runs (s) | Median | Speedup |
|---|---|---|---|
| Baleen off | 388.7 | 388.7 | 1.00x |
| hash, level 0 | 23.7 22.9 20.3 | 22.9 | 17.0x |
| hash, level 3 | 6.2 3.7 3.5 | 3.7 | 105x |

At level 3 every partition reads its first file and the other 26 files are opened with all row groups skipped.

## What the numbers say

1. **The observer alone gives most of the gain when there are few values.** It keeps 29 million joined rows
   out of the hash exchange and the sort. Q1 and Q2 go from 211 s and 162 s to about 36 s.
2. **The tuple filter alone adds nothing.** It drops over 99% of scan rows on Q1 and Q2, but the scan has
   already read and decoded them, and the observer was already removing the cost above the join.
3. **Skipping inside the scan is the second real gain.** Row-group skipping takes Q1 from 35.8 s to 12.6 s.
   File skipping takes Q2 from 34.0 s to 20.1 s.
4. **The hash set stops helping past its cap of 1,000,000 values.** Q3 has 1,999,998 values. The hash set
   records half of them, so half of the rows pass both filter and observer (1.4x). The Roaring bitmap has no
   cap and gets 3.2x.
5. **The flat bitmap at level 1 is the hash set.** The bitmap gets its range from the scan's manifest bounds,
   which are only fetched at level 2 and above. At level 1 it has no range and every value goes to its hash
   fallback. `flat, level 1` rows therefore repeat `hash, level 1`. This needs fixing before flat and Roaring
   can be compared fairly.
6. **File skipping is unstable.** See the next section.

## Open problem: frame stall

File skipping needs every value in a file's range to be recorded. The last few unseen values reach the
observer late: with the tuple filter dropping almost everything, the rows that carry them sit in partly
filled frames (filter output, exchange, join output, project) until the scan ends. Effects:

- Q2 `roaring, level 2` skipped 20 of 30 files in the median run; other runs ranged from 2 to 26.
- Q2 `flat, level 2` skipped 0 files in two of three runs (33 s) and many in one (21.6 s).
- Q3 skipped 0 files in every run.

Fix proposed: an operator that receives a partly filled frame flushes its own output. This needs the
DISTINCT filter to flush sparse output, and small changes in the hash join probe and stream project.
Those are core operators, so this waits for a decision.

## Bug found by the benchmark

Without a join the observer was in the plan but did nothing. Cause: `RemoveUnknownCheckForKnownTypeExpressionRule`
rewrote the observer's placeholder condition to TRUE, the key variable then had one use left and was inlined
into the record constructor, and the observer found no column to read and passed every row through. The
join plans were not affected because the variable has to cross the join. Fix: that rule now skips selects
annotated `plaque-distinct`. After the fix the no-join plan has observer, key ASSIGN and scan filter.

## Lineitem as build side

`FROM orders o, lineitem l` makes lineitem the build side. The filter is idle there (nothing is observed
until the lineitem scan is over), only the observer helps: Q1 255.7 s to 46.8 s, Q2 186.5 s to 54.6 s
(single runs).

## What was built

- Observer and tuple filter: `PlaqueDistinctRuntimeFactory`, `PlaqueDistinctPOperator`, inserted by a new
  DISTINCT path in `PlaqueRewriteRule`.
- Seen-sets in `PlaqueDistinctState`: hash (exact, cap 1,000,000), flat bitmap (range from Iceberg manifest
  bounds, up to 16,000,000 bits), Roaring (RoaringBitmap 1.6.23). Flat and Roaring fall back to a hash set
  for strings and uncovered values.
- Scan side: `PlaqueIcebergDistinctScanFilter`, wired through `PlaquePageFilterRule`,
  `ExternalDatasetProjectionFiltrationInfo`, `IcebergParquetRecordReaderFactory`, `IcebergFileRecordReader`.
- Guards so other rules leave the DISTINCT selects alone: `ConsolidateSelectsRule`, `PushSelectDownRule`,
  `RemoveRedundantSelectRule`, `RemoveUnknownCheckForKnownTypeExpressionRule`.
- Existing MAX/MIN, GROUP BY, Top-K and theta-join filters are unchanged.

## Other findings

- Join order decides everything: the filter only works when the DISTINCT table is the probe side.
- A projection can be pushed into a SELECT operator. A custom select runtime must apply it; the DISTINCT
  operators do. The older Baleen filter operators do not and have the same latent failure.
- `.gitignore` has `plaque/`, which also ignores `operators/plaque/`. New runtime files need `git add -f`.
- `SET compiler.joinmemory "4GB"` fails with "can't insert tuple in join memory". The configured 2 GB works.

## Not done

- Frame-stall fix (needs decision).
- Flat bitmap range at level 1.
- Seen-set propagation through the cluster controller, capped cache (kind D), code bitmap (level 4),
  helper structure.
- Baleen-off baselines are single runs.

## Later on 2026-11-16: decisions and next steps

- **Benchmarks get 6 runs per configuration from now on**, Baleen off included. The tables above (1 run
  for Baleen off, 3 per variant) predate this.
- **The matrix above is not uniform** (some set/level combinations were left out). A uniform benchmark is
  running: 4 queries × 3 seen-sets (hash, flat, roaring) × 4 levels (0 to 3), 6 runs each, 6 Baleen-off runs
  per query. Raw output: `/scratch/asterixdb_eightynode/build_logs/bench_result_distinct_full.md`.
- **Change made before the uniform benchmark:** the seen-set now gets the column's range from the scan at
  every level. Before, the flat bitmap only got it at levels 2 and 3 and silently used its hash fallback
  at levels 0 and 1, so the `flat, level 1` rows above repeat `hash, level 1`.
- **File bounds checked:** all 30 lineitem files have `l_suppkey` bounds 1 to 100,000 (29 files of
  2,000,000 rows, one of 1,986,052).
- **Correction to "Open problem: frame stall":** Q3 `l_partkey` skipping 0 files is not caused by the frame
  stall. The level 2 test needs every integer between a file's lower and upper bound to be seen. The answer
  has 1,999,998 values in a range of 2,000,000, so the test can never pass.
- **Level 2 and level 3 cover different cases.** Level 3 works well for few values (`l_shipmode`) and is
  switched off by its 10,000-value NOT IN cap on `l_suppkey` and `l_partkey`. Level 2 is the only scan
  skipping for those and is unreliable (`l_suppkey`) or impossible (`l_partkey`).
- **Frame-stall fix approved.** Plan: `PLAQUE_DISTINCT_FLUSH_PLAN_2026_11_16.md`. To be done after the
  uniform benchmark; afterwards only the 27 configurations whose plan changes are rerun (join queries,
  levels 1 to 3, all three seen-sets).

Limits built into the current code, all of which affect the numbers:

| Limit | Value |
|---|---|
| Hash set stops recording | 1,000,000 values |
| Flat bitmap used only if the column's range is at most | 16,000,000 |
| NOT IN list sent to the Parquet reader (level 3) | at most 10,000 values |
| Level 2 column types | integer (BIGINT) |
| Level 3 column types | integer (BIGINT), string |

## Files

- Benchmark script: `/scratch/asterixdb_eightynode/build_logs/bench_distinct.py`
- Raw table: `/scratch/asterixdb_eightynode/build_logs/bench_result_distinct.md`
- Server logs: `/scratch/asterixdb_eightynode/build_logs/asterix_distinct_bench.log`,
  `asterix_distinct_bench2.log` (Q4 reruns)
