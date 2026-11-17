# Baleen DISTINCT filter: early-send (flush) plan (2026-11-16)

## Summary

File skipping in the DISTINCT filter is unreliable because rows that carry the last unseen values wait in
partly filled output buffers on the way to the observer. The plan is to make the operators between the
tuple filter and the observer hand their output on after every input batch. Approved by Pratyoy on
2026-11-16, to be done after the uniform benchmark finishes.

## Problem

Operators collect output rows in a frame (32 KB) and hand it to the next operator only when it is full or
when the input ends. Path of a lineitem row in the join queries:

scan → tuple filter → exchange → hash join (probe) → project → observer

Example on `l_suppkey` (100,000 values, every lineitem file has bounds 1 to 100,000):

1. Late in the query the tuple filter drops over 99% of rows.
2. A row with a value not yet seen is kept and placed in the filter's output frame.
3. The frame holds a few rows and needs a few hundred, so it is not handed on.
4. The observer has not received the row, so the value is not in the seen-set.
5. The scan tests the next file: "are all values 1 to 100,000 seen?" One is missing, so the file is read.

The same wait can repeat in the exchange, the join's result frame and the project's frame. Measured effect:
`l_suppkey`, Roaring bitmap, level 2 skipped between 2 and 26 of 30 files depending on the run.

## Changes

| # | Where | Change | Core code |
|---|---|---|---|
| 1 | `PlaqueDistinctRuntimeFactory` (tuple filter) | After each input frame from which at least one row was kept, hand the output frame on and call `flush()` on the next operator | No |
| 2 | `OptimizedHybridHashJoinOperatorDescriptor` probe, and the in-memory hash join the optimizer can substitute | New flag, default off. When on: after each probe input frame, hand the result frame on and call `flush()` on the next operator | Yes |
| 3 | `PlaqueRewriteRule` | When the filter and observer are placed, mark every join between them | No |
| 4 | `HybridHashJoinPOperator` (and the in-memory hash join POperator) | Read the mark and pass the flag to the join descriptor | Yes, small |

Not changed:

- Exchange (sending side), project, select, assign: they already forward `flush()`.
- Observer: it records a value when the row arrives.
- Joins not between a DISTINCT filter and its observer: flag off, same behaviour as today.
- MAX/MIN, GROUP BY, Top-K and theta-join filters.

No runtime detection is involved. The plan decides which joins send early.

## Cost

More, smaller frames on the marked path. This happens only when the filter's output is sparse. Level 1
(tuple filter, no scan skipping) is rerun to measure this cost by itself.

## Limits that remain

- If the join spills probe rows to disk, those rows are joined at the end and their values arrive late.
  To be checked in the logs (join memory is 2 GB).
- `l_partkey` will still skip no files at level 2: two values in 1 to 2,000,000 never appear in the answer,
  so "whole range seen" can never be true. This is a limit of the level 2 test.

## Test

Before: the uniform benchmark running on 2026-11-16 (4 queries × 3 seen-sets × 4 levels, 6 runs each,
6 Baleen-off runs per query), output `/scratch/asterixdb_eightynode/build_logs/bench_result_distinct_full.md`.

After the change, rerun only the configurations whose plan changes, 6 runs each:

| Configurations | Rerun | Reason |
|---|---|---|
| Q1, Q2, Q3 (join), levels 1 to 3, hash / flat / roaring (27 configurations) | Yes | Tuple filter sends early, join is marked |
| Q1, Q2, Q3, level 0 | No | No tuple filter, no join marked |
| Q4 (no join), all levels | No | No join, no tuple filter |
| Baleen off | No | Baleen not in the plan |

Speedups of the rerun rows use the Baleen-off medians of the "before" benchmark.

Queries:

```sql
USE baleen_ice;
SET `compiler.plaque.enabled` "true";
SET `compiler.plaque.distinct.set` "<hash|flat|roaring>";
SET `compiler.plaque.distinct.level` "<1|2|3>";
SELECT DISTINCT l.l_shipmode FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
SELECT DISTINCT l.l_suppkey FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
SELECT DISTINCT l.l_partkey FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
```

(One SELECT per request.)

Success:

- Every result equal to Baleen off.
- `l_suppkey`, Roaring, level 2: files skipped about the same in all 6 runs (today 2 to 26 of 30).
- Level 1 times not worse than before the change.

## Steps

1. Wait for the uniform benchmark; record the raw table in the results document.
2. Make changes 1 to 4, rebuild, restart the server.
3. Check the plans of Q1 to Q4 and one result per query against Baleen off.
4. Rerun the 27 configurations, 6 runs each.
5. Record before and after side by side in the results document.
