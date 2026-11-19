# PLAQUE DISTINCT filter: the early flushing problem and what we did about it (2026-11-19)

This note puts the whole early-flushing story in one place: the problem, the changes, the measurements and the
default we chose. It adds no new measurement. Sources, with all raw tables:

- `2026_11_16_PLAQUE_DISTINCT_FLUSH_PLAN.md` (problem and plan)
- `2026_11_17_PLAQUE_DISTINCT_UNIFORM_BENCHMARK.md` (before)
- `2026_11_17_PLAQUE_DISTINCT_FLUSH_RESULTS.md` (after, 162 runs, and the observer bug)
- `2026_11_17_PLAQUE_DISTINCT_FLUSH_ABLATION.md` (342 runs, one switch per flush point)
- `2026_11_18_PLAQUE_DISTINCT_PERNODE_VERIFICATION.md` (168 runs, per-node seen-sets)

Scope: DISTINCT with the observer and the filter on the probe side.

## 1. The problem

The DISTINCT filter has two ends. The observer sits above the join and records every value it sees in the
seen-set. The filter sits above the scan and uses the seen-set to drop rows (level 1) and to skip whole files
(level 2: a file is skipped when every value between its lower and upper bound is already seen).

Between the two ends a row passes through several operators:

```
scan -> tuple filter -> exchange -> hash join (probe) -> project -> observer
```

Each operator collects its output rows in a frame (32 KB) and hands the frame to the next operator only when
it is full or when the input ends. A value is in the seen-set only after its row has reached the observer.

Late in a query this goes wrong:

1. The tuple filter drops over 99% of the rows.
2. A row with a value not yet seen is kept and placed in the filter's output frame.
3. The frame holds a few rows and needs a few hundred to be full, so it is not handed on.
4. The observer has not received the row, so the value is not in the seen-set.
5. The scan tests the next file: "are all values between the bounds seen?" One is missing, so the file is read.

So the better the filter works, the slower the last unseen values travel, and the file skip waits for exactly
those values. The same wait can repeat in the join's result frame.

Measured effect, Q2 (`l_suppkey`, 100,000 values, every file has bounds 1 to 100,000), before any change:
flat and roaring at levels 2 and 3 skipped between 0 and 24 of 30 files depending on the run, with medians
of 20.4 to 27.2 s.

## 2. The changes

| # | Where | Change |
|---|---|---|
| 1 | Tuple filter (`PlaqueDistinctRuntimeFactory`) | After each input frame from which at least one row was kept, hand the output frame on and call `flush()` on the next operator |
| 2 | Hash join probe (`OptimizedHybridHashJoinOperatorDescriptor` and the in-memory hash join) | New flag, default off. When on: after each probe input frame, hand the result frame on and call `flush()` on the next operator |
| 3 | `PlaqueRewriteRule` | When the filter and the observer are placed, mark every join between them (`FLUSH_PROBE_OUTPUT`) |
| 4 | Hash join physical operator | Read the mark and pass the flag to the join |
| 5 | Observer | Its `flush()` does nothing (see section 3) |

Exchange, project, select and assign already forward `flush()` and were not changed. Joins that are not
between a DISTINCT filter and its observer keep the flag off. The plan decides which joins send early; nothing
is detected at run time. The MAX/MIN, GROUP BY, Top-K and theta filters were not touched.

The setting `compiler.plaque.distinct.flush` turns each flush point on by a letter:

| Letter | Flush point |
|---|---|
| `f` | the tuple filter sends what it kept after every input frame |
| `j` | the hash join sends its buffered results after every probe frame |
| `o` | the observer passes an early send on to the operators above it |

Default: `fj`.

## 3. Bug found on the way: the observer forwarded early sends

The observer and the tuple filter share one runtime class, whose `flush()` handed the output frame on and
flushed the next operator. Once the join flushed after every probe frame, the observer passed each of those
flushes to the hash exchange above it, which sent a partly filled frame to each of the 4 sort partitions per
probe frame. Nothing above the observer waits for those rows. Q3 became slower: flat and roaring 47.1 to
48.7 s against 41.1 to 42.2 s before. Fix: the observer's `flush()` does nothing. That behaviour is what the
letter `o` turns back on.

## 4. Measurements

Table `lineitem` (Iceberg, 30 files), dataverse `baleen_ice`, 6 runs per configuration. Every run of every
benchmark below returned the same result as Baleen off.

Queries:

```sql
-- Q1
SELECT DISTINCT l.l_shipmode FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
-- Q2
SELECT DISTINCT l.l_suppkey FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
-- Q3
SELECT DISTINCT l.l_partkey FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
```

### 4.1 Ablation, level 2, 342 runs (2026-11-17)

Baleen off medians: Q1 212.8 s, Q2 160.9 s, Q3 143.0 s.

Median time (s):

| Flush | Q1 hash | Q1 flat | Q1 roaring | Q2 hash | Q2 flat | Q2 roaring | Q3 hash | Q3 flat | Q3 roaring |
|---|---|---|---|---|---|---|---|---|---|
| none | 36.3 | 35.6 | 35.7 | 43.2 | 29.2 | 20.1 | 104.2 | 41.3 | 42.8 |
| `f` | 35.8 | 36.5 | 36.1 | 42.3 | 29.7 | 16.5 | 121.9 | 43.5 | 45.3 |
| `j` | 35.2 | 35.4 | 34.6 | 41.7 | 17.1 | 15.5 | 104.9 | 41.6 | 43.3 |
| `fj` | 35.8 | 36.0 | 34.7 | 40.5 | 12.6 | 12.2 | 120.2 | 43.1 | 45.1 |
| `jo` | 35.7 | 34.9 | 34.9 | 42.0 | 26.1 | 22.6 | 101.4 | 43.8 | 43.4 |
| `fjo` | 34.6 | 35.1 | 35.8 | 42.2 | 12.6 | 12.8 | 116.4 | 46.9 | 48.1 |

Q2, files skipped out of 30 in each of the 6 runs (Q1 and Q3 skip no file at any setting):

| Flush | Hash | Flat | Roaring |
|---|---|---|---|
| none | 0 0 0 0 0 0 | 18 4 6 0 0 14 | 22 2 14 19 16 18 |
| `f` | 0 0 0 0 0 0 | 0 6 14 0 2 10 | 22 22 22 26 0 0 |
| `j` | 0 0 0 0 0 0 | 3 18 22 22 17 22 | 10 23 23 26 26 25 |
| `fj` | 0 0 0 0 0 0 | 26 26 26 26 26 26 | 26 26 26 26 26 26 |
| `jo` | 0 0 0 0 0 0 | 22 22 1 14 1 1 | 26 1 2 22 2 22 |
| `fjo` | 0 0 0 0 0 0 | 26 26 26 26 26 26 | 26 26 26 26 26 26 |

Q2, roaring: rows that reach the observer and rows leaving the scan, median run:

| Flush | Files skipped / total | Rows out of scan | Rows into observer | Dropped by observer |
|---|---|---|---|---|
| none | 16 / 30 | 28,000,000 | 158,902 | 58,902 |
| `f` | 22 / 30 | 16,000,000 | 134,836 | 34,836 |
| `j` | 23 / 30 | 14,000,000 | 132,842 | 32,842 |
| `fj` | 26 / 30 | 8,000,000 | 105,096 | 5,096 |

### 4.2 What the tables show

- **Q2 needs both `f` and `j`.** Only `fj` and `fjo` skip 26 of 30 files in all six runs, for flat and for
  roaring. With `f` alone, `j` alone or nothing, the skip count changes from run to run (0 to 26).
- **With `fj`, Q2 runs in 12.2 s (roaring) and 12.6 s (flat)** against 160.9 s with Baleen off: 13.2x and
  12.8x. Without early sending the medians are 20.1 s and 29.2 s.
- **With `fj` far fewer duplicates reach the observer**: on Q2 roaring, 5,096 against 58,902 with no early
  sending.
- **`o` adds nothing.** `fjo` skips the same 26 files as `fj` and is slower on Q3 for flat and roaring.
- **Early sending costs on Q3**, where 2,000,000 distinct values mean no file can be skipped: `fj` against
  none is +1.8 s on flat, +2.3 s on roaring and +16.0 s on hash. With `f` the filter sends 28,563 to 54,985
  frames where it sends 3,902 to 28,123 without, and the hash exchange turns each into one frame to each of
  the 4 join partitions.
- **Q1 does not care.** With 7 distinct values all 18 configurations are within 34.6 to 36.5 s.
- **Hash never skips a file** at any setting: its range test passes only for a single-value file.

### 4.3 Decision

Rule agreed before the ablation: among the settings where Q2 skips 26 of 30 files in all six runs for both
flat and roaring, take the one with the lowest Q3 time. Candidates: `fj` and `fjo`. `fj` is faster on Q3 for
flat (43.1 s against 46.9 s) and roaring (45.1 s against 48.1 s). Default: `fj`. `o` stays available and off.

With `fj` and roaring at level 2: Q1 34.7 s, Q2 12.2 s, Q3 45.1 s against 212.8, 160.9 and 143.0 s with
Baleen off, which is 6.1x, 13.2x and 3.2x.

### 4.4 After per-node seen-sets (2026-11-18)

The numbers above were taken while the 4 node controllers shared one seen-set. Since 2026-11-18 each node
has its own seen-set and learns the other nodes' values through the cluster controller every 5,000 rows.
Flush stayed at `fj`. Q2, files skipped out of 30, per run:

| Variant | Run 1 | Run 2 | Run 3 | Run 4 | Run 5 | Run 6 | Median (s) | Speedup |
|---|---|---|---|---|---|---|---|---|
| flat, level 2 | 25 | 25 | 25 | 22 | 25 | 23 | 16.0 | 10.27x |
| flat, level 3 | 23 | 22 | 25 | 23 | 23 | 23 | 16.3 | 10.09x |
| roaring, level 2 | 25 | 23 | 26 | 25 | 25 | 25 | 16.4 | 10.03x |
| roaring, level 3 | 25 | 22 | 25 | 21 | 25 | 25 | 16.0 | 10.31x |

The skip is now 21 to 26 of 30. Early flushing still delivers values to the node's own observer at once; the
values of the other nodes now arrive by message.

## 5. Limits that remain

- If the join spills probe rows to disk, those rows are joined at the end and their values arrive late.
- A file can be skipped only if every value between its bounds appears in the answer. Q3 has two values in
  1 to 2,000,000 that never appear, so no flush setting can make Q3 skip a file.
- Hash seen-set: file skip only for single-value files.
- The probe frame counter exists for the hybrid hash join; the in-memory hash join has the flush but not
  the counter.
- The ablation was at level 2 only and without Q4. Q4 has no join and no tuple filter.

## Decisions made without asking

- This note repeats the short tables of the earlier notes and points to them for the full per-run tables.
- Section 4.4 is included although that run did not vary the flush setting.

## Stated but not verified

- That the Q3 cost of `f` is caused by the extra frames: frame counts and times move together, no profile
  was taken.
- That the 21 to 26 skip of 2026-11-18 is caused by the delay of propagated values: not measured.
- Whether the join spills probe rows to disk at 2 GB join memory.
- `o` alone and `fo` were never run.
- Flush settings other than `fj` have not been measured with per-node seen-sets.
