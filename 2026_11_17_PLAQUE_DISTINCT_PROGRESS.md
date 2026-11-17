# Baleen DISTINCT filter: progress on 2026-11-17

One page for the whole day. Tables with every run are in the notes this page points to.
Written at 11:40; the flush ablation was still running.

| Note | What it holds |
|---|---|
| `2026_11_17_PLAQUE_DISTINCT_UNIFORM_BENCHMARK.md` | 312-run uniform benchmark, limits in the code, why two columns have no dictionary |
| `2026_11_17_PLAQUE_DISTINCT_FLUSH_RESULTS.md` | 162-run rerun after the early send, the observer bug, the frame counters and their check run |
| this note | the order of events, what was decided, what is open |

## 1. Uniform benchmark (done)

Q1 to Q4, Baleen off plus hash, flat, roaring at levels 0 to 3, 6 runs each, 312 runs, table `lineitem`.
Medians in seconds:

| Query | Off | Level 0 | Level 1 | Level 2 | Level 3 |
|---|---|---|---|---|---|
| Q1 `l_shipmode`, join | 212.7 | about 34 to 38 | about 34 to 38 | about 34 to 38 | 12.4 to 12.7 |
| Q2 `l_suppkey`, join | 158.2 | 35.6 to 36.4 | hash about 42; flat, roaring about 34 | hash about 42; flat, roaring 20 to 27 | hash about 42; flat, roaring 20 to 27 |
| Q3 `l_partkey`, join | 123.2 | hash 93.2; flat 45.1; roaring 43.0 | hash 93 to 103; flat, roaring 41 to 45 | same | same |
| Q4 `l_shipmode`, no join | 401.4 | about 19 to 21 | about 19 to 21 | about 19 to 21 | 2.8 to 3.1 |

Design-level finding: on Q2 the number of files skipped at levels 2 and 3 varied from 0 to 24 of 30 between runs of
the same configuration. Rows the filter let through sat in partly filled frames on the way to the observer, so the
seen-set was incomplete when the scan asked whether a file could be skipped.

## 2. Early send, also called flush (done, 162 runs)

Change: the tuple filter sends what it kept after every input frame, and the hash joins under a DISTINCT filter send
their buffered results after every probe frame.

| Query | Before | After |
|---|---|---|
| Q1 | 33.9 to 36.6 s (levels 1, 2), 12.4 to 12.7 s (level 3) | unchanged |
| Q2 flat, roaring, levels 2 and 3 | 20.4 to 27.2 s, 0 to 24 files skipped per run | 12.7 to 12.8 s, 26 of 30 files skipped in all 24 runs |
| Q2 hash | about 42 s, 0 files skipped | unchanged |
| Q3 flat, roaring | 41.1 to 42.2 s | 47.1 to 48.7 s |
| Q3 hash | 100.2 to 103.3 s | 113.7 to 116.6 s |

The early send fixes Q2 and costs Q3 5 to 16 s. Q3 skips no files, so there it can only cost.

Bug found afterwards (details in the flush note): the observer passed the join's early sends on to the exchange and
the sort above it. It now ignores them. Spot check only, Q3 roaring level 1: 55.5 (first run after restart), 46.9,
43.6 s. The 162 runs above are from the build before this fix.

## 3. Why `l_suppkey` and `l_partkey` have no dictionary (done)

Level 3 needs a dictionary-encoded column. In `lineitem` only `l_shipmode` has one.

- Cause: Iceberg 1.12.0 writes at most 20,000 rows per page by default. With 20,000 rows drawn from 100,000 or
  2,000,000 values the dictionary does not pay off on the first page and the writer falls back to plain encoding.
- Reproduced with a synthetic file (`iceberg/loader/DictTest.java`): range 100,000 with defaults gives PLAIN; with
  page row limit 1,000,000 it gives a dictionary.
- A column keeps its dictionary only when a row group holds many more rows than the column has distinct values.

## 4. NOT IN cap made a setting (done, built, not benchmarked)

The level 3 limit of 10,000 values was a constant I had picked without a basis. It is now
`compiler.plaque.distinct.notinmax`, default 10,000. Checked in the plan output:
`DISTINCT(l_suppkey), roaring, level 3, not-in max 100000`.

## 5. Two tables with dictionaries (done, loaded, not benchmarked)

Same data as `lineitem` (TPC-H SF10, 59,986,052 rows, 30 files of 2,000,000 rows). The loader now takes extra writer
settings as `key=value` arguments. Commands, run from `asterixdb/asterix-app`:

```
java -cp "$(cat /scratch/asterixdb_eightynode/iceberg/loader/classpath.txt)" \
  /scratch/asterixdb_eightynode/iceberg/loader/TpchLoader.java lineitem /scratch/tpch_data/sf10/lineitem.tbl \
  lineitem_dict 2000000 write.parquet.row-group-size-bytes=134217728 write.parquet.page-row-limit=10000000 \
  write.parquet.page-size-bytes=33554432 write.parquet.dict-size-bytes=33554432

java -cp "$(cat /scratch/asterixdb_eightynode/iceberg/loader/classpath.txt)" \
  /scratch/asterixdb_eightynode/iceberg/loader/TpchLoader.java lineitem /scratch/tpch_data/sf10/lineitem.tbl \
  lineitem_dict_1rg 2000000 write.parquet.row-group-size-bytes=1073741824 write.parquet.page-row-limit=10000000 \
  write.parquet.page-size-bytes=33554432 write.parquet.dict-size-bytes=33554432
```

Encodings read from the Parquet footers of every file:

| Table | Row groups | `l_shipmode` | `l_suppkey` | `l_partkey` |
|---|---|---|---|---|
| `lineitem` (16 MB row groups) | many per file | dictionary | plain | plain |
| `lineitem_dict` (128 MB row groups) | 90, 3 per file | dictionary | dictionary | plain |
| `lineitem_dict_1rg` (1 GB row groups) | 30, 1 per file | dictionary | dictionary | dictionary |

Both are registered as external collections `lineitem_dict` and `lineitem_dict_1rg` in dataverse `baleen_ice`
(same type and catalog as `lineitem`). One check query each: `COUNT(*)` = 59,986,052 and 7 distinct `l_shipmode`,
98.6 s and 88.5 s.

## 6. Frame counters and a switch per flush point (done)

Reason: I had blamed the Q3 slowdown on the observer forwarding without measuring it. Details and the check-run
table are in the flush note.

- `compiler.plaque.distinct.flush`, letters `f` (filter), `j` (join), `o` (observer forwards), default `"fj"`.
- Counters per task in the server log: frames in and out and early sends at the filter and the observer, probe
  frames into the hybrid hash join.

Finding from the check run (one run per setting, so only the counts are meaningful): one early send by the filter
becomes one frame to each of the 4 join partitions at the hash exchange. On Q3 the join receives 114,272 probe frames
with `f` on and 4,026 with it off, for the same rows.

## 7. Flush ablation (running since 11:32, about 5 hours)

Script `build_logs/bench_distinct_ablation.py`, server log `build_logs/asterix_distinct_counters.log`, output
`build_logs/bench_result_distinct_ablation.md` and `bench_result_distinct_ablation_per_run.md`.

- Q1, Q2, Q3 with the `orders` join on `lineitem`.
- Per query: Baleen off, then hash, flat, roaring at level 2 under flush `""`, `"f"`, `"j"`, `"fj"`, `"jo"`, `"fjo"`.
- 6 runs per configuration, round-robin within a query, one discarded warm-up per query. 342 runs.
- Limits: hash cap 1,000,000 and file skip only when lower bound equals upper bound; flat range 16,000,000; level 2
  BIGINT only with null count 0, so Q1 skips no files.

Queries, `<S>` the seen-set and `<F>` the flush setting:

```
USE baleen_ice;
SET `compiler.plaque.enabled` "false";                                   -- Baleen off
SET `compiler.plaque.enabled` "true"; SET `compiler.plaque.distinct.set` "<S>";
SET `compiler.plaque.distinct.level` "2"; SET `compiler.plaque.distinct.flush` "<F>";
SELECT DISTINCT l.l_shipmode FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
SELECT DISTINCT l.l_suppkey  FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
SELECT DISTINCT l.l_partkey  FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
```

Predictions written down before the results (flat and roaring; hash in brackets):

| Query | `""` | `"f"` | `"j"` | `"fj"` | `"jo"` | `"fjo"` |
|---|---|---|---|---|---|---|
| Q1 time | 34 to 36 s | same | same | same | same | same |
| Q1 files skipped | 0 | 0 | 0 | 0 | 0 | 0 |
| Q2 time | 17 to 35 s (42) | like `""` (42) | like `""` (42) | about 13 s (42) | like `"j"` (42) | about 13 s or slightly more (42) |
| Q2 files skipped | erratic, 0 to 24 (0) | erratic (0) | erratic (0) | 26 every run (0) | like `"j"` (0) | 26 every run (0) |
| Q3 time | about 41 s (100) | 44 to 46 s (108 to 112) | 41 to 42 s (100) | about 46 s (110) | 42 to 43 s (101) | 47 to 48 s (114 to 117) |
| Q3 files skipped | 0 | 0 | 0 | 0 | 0 | 0 |

## 8. Decisions taken with the user today

- The flush investigation is timeboxed to today. Early sending is not the main goal of the work.
- The default is chosen from the six fixed settings in the ablation by this rule: take the settings where Q2 skips
  26 of 30 files in all 6 runs for flat and roaring; among them the one with the lowest Q3 time; if its Q3 cost is
  judged too high, early sending is off by default and stays available per query through the setting.
- A rule that sends early only when the filter's output trickles is not built. It is a possible later improvement.
- The cap test is for Q2 and Q3 only; Q1 and Q4 have 7 values and are not affected by the cap.

## 9. Open

- Ablation results, tables and the choice of the flush default (today).
- Cap test, waiting for the user's OK on the matrix: Q2 on `lineitem_dict`, Q3 on `lineitem_dict_1rg`; per query
  Baleen off, then hash, flat, roaring at levels 0, 1, 2 and at level 3 with `notinmax` 10,000, 100,000 and
  2,000,000; 6 runs each, 228 runs, about 3 hours. It runs on the flush default chosen above.
- Propagation mock on the existing NC to CC to NC threshold path.
- Helper structure (deferred from the start).
- All DISTINCT code on branch `baleen` is uncommitted.

## 10. Decisions by the user in the afternoon (about 14:30)

- The cap test is abandoned. Level 3 is used only where Parquet writes a dictionary with default settings; writer
  settings are not changed to force one. The tables `lineitem_dict` and `lineitem_dict_1rg` and the setting
  `compiler.plaque.distinct.notinmax` (default 10,000) exist but are not benchmarked. Nothing was deleted.
- The ablation is to be completed; it is what the choice of the flush default rests on.
- Next priority: the DISTINCT seen-set must travel between nodes with the same NC to CC to NC messages the MAX/MIN
  and GROUP BY filters use. Today it lives only in the per-JVM registry, which is shared by the 4 NCs of the test
  server because they run in one JVM.

Ablation status at 14:12: Q1 and Q2 finished (230 of 342 runs, all results equal to Baleen off). On Q2 only `"fj"`
and `"fjo"` skip 26 of 30 files in all 6 runs, for flat and for roaring; `"f"` or `"j"` alone stay erratic.

## Decisions I made without asking

- Loaded the two extra tables and registered them as collections.
- Ablation: level 2 only; `"o"` and `"fo"` left out; round-robin run order; warm-up run with roaring, `"fj"`;
  results compared with the first Baleen-off run of the same query in this benchmark.
- Probe-frame counter only in the hybrid hash join, the join these plans use.

## Stated but not verified

- That the observer forwarding caused part of the Q3 slowdown (three spot-check runs only).
- That the extra frames are what costs the time on Q3; the ablation is meant to show this.
- Every cell of the prediction table except Q2 `"fj"`, which repeats the 162-run result.
- That `"o"` and `"fo"` behave like `""` and `"f"`; checked for `""` only.
- The exact rule by which the Parquet writer drops a dictionary; only the outcome was reproduced.
- The expectation that level 3 adds little over level 2 on Q2 and Q3 with the dictionary tables.
- The exact DDL of the two new collections is not copied into this note.
