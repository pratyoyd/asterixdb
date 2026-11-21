# PLAQUE DISTINCT filter on native columnar collections (2026-11-20)

Scope: DISTINCT with the observer and filter on the probe side only. This note covers the implementation and a
single-run check. The 6-run benchmark is not run yet; its plan is at the end.

## What exists now

| Piece | What it does | Applies to | Level |
|---|---|---|---|
| Observer, tuple filter | Unchanged; they already worked on native tables | Any column | 0, 1 |
| Leaf skip | A leaf is not read when every value between the column's smallest and largest value on that leaf is already in the answer | Column declared BIGINT, not nullable | 2 and up |
| Row drop inside the columnar reader | A row whose value is already in the answer is dropped before its other columns are assembled | Column declared BIGINT or string, not nullable | 4 and up |

Level 3 adds nothing on native: a leaf keeps only a min and a max per column (for strings, of the first four
characters), no dictionary.

## Single-run check (not a benchmark)

Tables `Default.Lineitem_column_10` and `Default.Orders_column_10` (native columnar, 59,986,052 lineitem rows,
37,100 leaves over 4 partitions). Roaring seen-set, flush and propagation at their defaults. One run per cell.
Every Baleen-on result equals the Baleen-off result of the same query.

Queries:

```sql
-- Q1
SELECT DISTINCT l.l_shipmode FROM Lineitem_column_10 l, Orders_column_10 o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
-- Q2
SELECT DISTINCT l.l_suppkey FROM Lineitem_column_10 l, Orders_column_10 o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
-- Q3
SELECT DISTINCT l.l_partkey FROM Lineitem_column_10 l, Orders_column_10 o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
-- Q4
SELECT DISTINCT l.l_shipmode FROM Lineitem_column_10 l;
```

Settings: `USE Default; SET `compiler.plaque.enabled` "true"; SET `compiler.plaque.distinct.level` "<1|2|3|4>";`
and `SET `compiler.plaque.enabled` "false";` for Baleen off.

Times (s). Baleen off is from the first check of the day, the levels from the second (after the fix below):

| Query | Baleen off | Level 1 | Level 2 | Level 3 | Level 4 | Level 4 over Baleen off |
|---|---|---|---|---|---|---|
| Q1 `l_shipmode`, join | 179.1 | 41.3 | 35.9 | 35.0 | 15.4 | 11.6x |
| Q2 `l_suppkey`, join | 119.3 | 33.3 | 14.8 | 14.3 | 9.6 | 12.4x |
| Q3 `l_partkey`, join | 110.7 | 41.7 | 42.4 | 41.7 | 21.7 | 5.1x |
| Q4 `l_shipmode`, no join | 282.7 | 26.5 | 27.4 | 27.5 | 8.6 | 32.9x |

Counters of the same runs:

| Query | Level | Result = off | Leaves tested | Leaves skipped | Rows dropped in reader | Rows into tuple filter | Rows into observer |
|---|---|---|---|---|---|---|---|
| Q1 | 1 | yes | 0 | 0 | 0 | 59,986,052 | 2,301 |
| Q1 | 2 | yes | 0 | 0 | 0 | 59,986,052 | 2,301 |
| Q1 | 3 | yes | 0 | 0 | 0 | 59,986,052 | 2,301 |
| Q1 | 4 | yes | 0 | 0 | 59,979,366 | 6,686 | 2,301 |
| Q2 | 1 | yes | 0 | 0 | 0 | 59,986,052 | 142,215 |
| Q2 | 2 | yes | 37,100 | 30,221 | 0 | 11,123,343 | 140,106 |
| Q2 | 3 | yes | 37,100 | 30,768 | 0 | 10,238,844 | 140,943 |
| Q2 | 4 | yes | 37,100 | 27,204 | 15,687,000 | 314,832 | 143,357 |
| Q3 | 1 | yes | 0 | 0 | 0 | 59,986,052 | 2,065,114 |
| Q3 | 2 | yes | 37,100 | 0 | 0 | 59,986,052 | 2,062,230 |
| Q3 | 3 | yes | 37,100 | 0 | 0 | 59,986,052 | 2,063,436 |
| Q3 | 4 | yes | 37,100 | 0 | 55,667,890 | 4,318,162 | 2,077,139 |
| Q4 | 1 | yes | 0 | 0 | 0 | 0 (no tuple filter) | 59,986,052 |
| Q4 | 2 | yes | 0 | 0 | 0 | 0 | 59,986,052 |
| Q4 | 3 | yes | 0 | 0 | 0 | 0 | 59,986,052 |
| Q4 | 4 | yes | 0 | 0 | 59,974,492 | 0 | 11,560 |

First check of the day (before the fix), for the record:

| Query | Baleen off | Level 1 | Level 2 | Level 4 |
|---|---|---|---|---|
| Q1 | 179.1 | 37.8 | 37.4 | 18.2 |
| Q2 | 119.3 | 33.8 | 14.6, wrong result (100,004 rows) | 7.3 |
| Q3 | 110.7 | 43.6 | 39.9 | 21.9 |
| Q4 | 282.7 | 27.8 | 29.8 | 10.3 |

## Findings

- With level 4 (the default) all four queries are faster than Baleen off in these single runs: 11.6x, 12.4x,
  5.1x and 32.9x.
- The row drop inside the reader works for BIGINT as well as strings on native, so Q3 gains too (41.7 s to
  21.7 s): 55.7 million rows never leave the scan. On Iceberg level 4 acts on strings only.
- Q2: the leaf skip removes 27,204 to 30,768 of 37,100 leaves.
- On `l_shipmode` (string) no leaf is tested: the stored min and max are of the first four characters only, so
  they cannot prove that every value on a leaf is seen.

## How it works

- `PlaquePageFilterRule.attachDistinctToColumnScan` (asterix-algebra): when the DISTINCT key is a field of a
  native columnar scan's record, accessed by index (a declared field), with declared type BIGINT or string, it
  records handle, seen-set kind, level and the column on the scan's `ColumnDatasetProjectionFiltrationInfo`.
- `IndexUtil.createTupleProjectorFactory` (asterix-metadata) turns that into two factories for the scan:
  - `PlaqueDistinctColumnFilterEvaluatorFactory` (asterix-column, range filter): per leaf, reads the column's
    min and max from page zero and asks the seen-set `containsRange(min, max)`. ANDed with the query's own
    range filter.
  - `PlaqueDistinctIterableFilterEvaluatorFactory` (asterix-column, row filter): per row, reads the column's
    value through the reader's filter column and asks the seen-set `contains(value)`. Null and missing are kept.
- Both find the node's seen-set by job, handle and node id, as the Iceberg reader does.
- Counters `scanLeaves`, `scanLeavesSkipped`, `scanRowsDropped` are kept on the seen-set and printed in the
  observer's `PLAQUE_DISTINCT_OBSERVER_STATS` line.

## Built-in limits

- Columns declared BIGINT or string and not nullable only. A leaf records no null count, so a nullable or
  undeclared column gets neither scan filter.
- Primary-key columns get no scan filter (no per-leaf range, no assign to find).
- The row drop is not attached when the query has its own filter pushed into the same scan; the leaf skip is
  then still attached.
- Leaf skip needs the seen-set to answer ranges: roaring does; hash only for a leaf with a single value; flat
  is given no range on native, so it behaves like hash.
- Top-level fields only.
- Datasets with a meta part: the scan gets no task context there (existing gap, also for MAX/MIN), so no filter.

## Tests

`asterix-column/src/test/java/org/apache/asterix/column/filter/iterable/evaluator/PlaqueDistinctColumnFilterTest.java`,
4 tests, all pass (run with JUnit directly): a leaf is skipped only when every value between its bounds is
seen; a leaf without the column is read; a primary key or a non-BIGINT column gets no leaf filter; a row is
dropped only when its value is seen, null and missing are kept.

## Bugs

- Wrong result at level 2 on Q2 (100,004 rows instead of 100,000) in the first build. A native scan that skips
  a leaf but has no row filter still hands out that leaf's rows as empty records, and they reached the output.
  Fixed: whenever the leaf skip is attached a row filter is attached with it (at levels 2 and 3 one that keeps
  every row), and the leaf skip is not attached if the scan would have no row filter. Confirmed by the second
  check: 100,000 rows, md5 equal, at levels 2, 3 and 4.

## Benchmark plan (waiting for OK)

- Queries: Q1 to Q4 above.
- Configurations per query: Baleen off, and {hash, flat, roaring} x level {1, 2, 4}. Flush and propagation at
  their defaults. 10 configurations.
- 6 runs per configuration, round-robin within a query, one discarded warm-up per query: 240 measured runs,
  about 2 hours 45 minutes by the single-run times.
- Level 3 is left out because it is identical to level 2 on native.

## Decisions made without asking

- Native reuses the level numbers of Iceberg, with level 3 adding nothing.
- Filters attach only to columns declared BIGINT or string, not nullable.
- The row drop is skipped when the query has its own pushed-down filter on the scan.
- An always-true row filter at levels 2 and 3 was the way chosen to make the leaf skip correct.
- The server was restarted three times.

## Stated but not verified

- All times are single runs.
- Tables with several disk components per partition, with deletes or updates, were not tested.
- Nullable, undeclared and primary-key columns: behaviour read from the code, not run.
- Hash and flat seen-sets were not run on native.
- Coexistence with the MAX/MIN, GROUP BY, Top-K and theta filters on native after today's changes was not rerun.
