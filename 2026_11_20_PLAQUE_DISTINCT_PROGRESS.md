# PLAQUE DISTINCT filter: progress notes (2026-11-20)

Scope: DISTINCT with the observer and filter on the probe side only.

## Headline

Level 4 was built and measured today, and is now the default. With the defaults, `SELECT DISTINCT l.l_shipmode
FROM lineitem l;` (Q4) runs in 0.4 s against 402.5 s with Baleen off, and the same query with a join on `orders`
(Q1) in 9.4 s against 203.8 s.

## Where each query stands (defaults: roaring, level 4, flush `fj`, propagation on)

| Query | Baleen off (2026-11-18) | Level 3 | Level 4 | Level 4 over Baleen off | Source of the Baleen-on numbers |
|---|---|---|---|---|---|
| Q1 `l_shipmode`, join | 203.8 s | 13.1 s | 9.4 s | 21.65x | 6-run medians, 2026-11-20 |
| Q2 `l_suppkey`, join | 164.6 s | 16.0 s | 16.0 s and 19.2 s | about 10x | level 3: 6-run median, 2026-11-18; level 4: two single runs today |
| Q3 `l_partkey`, join | 143.1 s | 47.6 s | 44.7 s and 47.8 s | about 3x | level 3: 6-run median, 2026-11-18; level 4: two single runs today |
| Q4 `l_shipmode`, no join | 402.5 s | 2.9 s | 0.4 s | 919.42x | 6-run medians, 2026-11-20 |

Level 4 acts on string columns only, so Q2 and Q3 (BIGINT) behave as at level 3. Their level 4 entries are two
single runs made after the default was changed, to confirm that; they are not a benchmark.

## How Q4 got from 402.5 s to 0.4 s

| Q4 | Rows leaving the scan | Time | Over Baleen off | Measured |
|---|---|---|---|---|
| Baleen off | 59,986,052 | 402.5 s | 1x | 2026-11-18 |
| Level 2 (observer, tuple filter, file skip) | 59,986,052 | 19.8 s | 20x | 2026-11-18 |
| Level 3 (+ row groups skipped by dictionary) | 8,000,000 | 2.9 s | 138x | 2026-11-20 |
| Level 4 (+ rows dropped inside the reader) | 12,347 | 0.4 s | 919x | 2026-11-20 |

## What was done today

1. One-page description of the seen-set, the levels and the settings: `2026_11_20_PLAQUE_DISTINCT_HOW_IT_WORKS.md`.
2. Level 4 implemented: `PlaqueDistinctDictionaryReader` in `asterix-external-data`; 10 unit tests, all pass.
3. Level 4 measured on Q1 and Q4, 72 runs, all results equal to Baleen off:
   `2026_11_20_PLAQUE_DISTINCT_LEVEL4.md` (summary table and every run).
4. Level 4 made the default: `AlgebricksConfig.PLAQUE_DISTINCT_LEVEL_DEFAULT = 4`. Server rebuilt and restarted
   (log `build_logs/asterix_distinct_level4_default.log`).
5. Check after the default change, two single runs per query with only `compiler.plaque.enabled` set:

| Query | Run 1 | Run 2 | Result = Baleen off | Files skipped / total | Rows dropped in reader |
|---|---|---|---|---|---|
| Q1 | 10.5 s | 8.7 s | yes, yes | 0 / 30, 0 / 30 | 7,991,764 and 7,995,884 |
| Q2 | 16.0 s | 19.2 s | yes, yes | 24 / 30, 22 / 30 | 0 and 0 |
| Q3 | 44.7 s | 47.8 s | yes, yes | 0 / 30, 0 / 30 | 0 and 0 |
| Q4 | 0.7 s | 0.4 s | yes, yes | 0 / 30, 0 / 30 | 7,987,653 and 7,987,653 |

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

## Later the same day

6. DISTINCT on native columnar collections implemented: leaf skip by min and max (BIGINT, level 2 and up) and
   row drop inside the columnar reader (BIGINT and strings, level 4 and up). 4 unit tests pass. Details and the
   single-run check: `2026_11_20_PLAQUE_DISTINCT_NATIVE_COLUMNAR.md`.

Single runs on `Lineitem_column_10` / `Orders_column_10`, roaring (not a benchmark):

| Query | Baleen off | Level 1 | Level 2 | Level 3 | Level 4 | Level 4 over Baleen off |
|---|---|---|---|---|---|---|
| Q1 `l_shipmode`, join | 179.1 s | 41.3 s | 35.9 s | 35.0 s | 15.4 s | 11.6x |
| Q2 `l_suppkey`, join | 119.3 s | 33.3 s | 14.8 s | 14.3 s | 9.6 s | 12.4x |
| Q3 `l_partkey`, join | 110.7 s | 41.7 s | 42.4 s | 41.7 s | 21.7 s | 5.1x |
| Q4 `l_shipmode`, no join | 282.7 s | 26.5 s | 27.4 s | 27.5 s | 8.6 s | 32.9x |

7. A wrong result found and fixed on the way: Q2 on native at level 2 returned 100,004 rows, because a leaf
   skipped on a scan without a row filter comes out as empty records. A row filter is now always attached with
   the leaf skip.
8. The 240-run native benchmark (Q1 to Q4; Baleen off and hash, flat, roaring at levels 1, 2, 4; 6 runs) was
   approved and started. The first start was cut off at 18:19 after the first round of Q1 when the session
   ended (kept as `build_logs/bench_result_distinct_native_per_run_cutoff_1819.md`); it was started again at
   18:22, detached from the session. Results go to `build_logs/bench_result_distinct_native.md`.
9. Explanation of level 4 as row-level skipping, its relation to page skipping and to known techniques, and
   whether it applies to MAX, MIN, Top-K and GROUP BY: `2026_11_20_PLAQUE_ROW_LEVEL_SKIPPING.md`.

## Next, as set by the user

1. DISTINCT for native columnar collections: implemented; 6-run benchmark running.
2. Design for DISTINCT with more than one expression.

## Bugs

- First build of level 4 was inactive on the server because it accepted only optional columns and `l_shipmode`
  is required. Results were correct. Fixed, test added.
- My first check after the default change ran with Baleen off, because `compiler.plaque.enabled` is off by
  default. Two Baleen-off runs of Q1 (231.0 s and 206.3 s) came out of it before I stopped it.

## Decisions made without asking

- The check after the default change was two single runs per query, not a 6-run benchmark.
- Baleen off was not rerun today, as instructed; all "over Baleen off" figures use the medians of 2026-11-18.

## Stated but not verified

- Q2 and Q3 at level 4 rest on two single runs each.
- The DISTINCT code is still uncommitted on `baleen`.
