# Baleen on Iceberg: comparison with internal columnar, and Parquet/Iceberg features the filters could use (2026-11-17)

## Outcome

- Internal columnar and Iceberg are close where both skip in storage (scalar MAX, join MAX). The one large
  gap is the cross-table expression: 7.43x internal, 1.25x on Iceberg.
- The Parquet dictionary filter that the DISTINCT work uses for `NOT IN` also evaluates `>`, `<`, `=`, `IN`,
  `AND` and `OR`. That makes row-group skipping possible for GROUP BY, which no storage path does today.
- Five ideas are listed below, ranked. None is built or measured. No new runs were done today; every number
  here is from the 2026-11-16 benchmarks.

## Comparison table

TPC-H SF10, ties dropped, Baleen off then on, median in seconds. Source: `2026_11_16_PLAQUE_ICEBERG_REVIEW_TODO.md`.
These runs were 3 per configuration plus one warm-up, not the 6 the standing rule asks for.

| Query | Internal columnar | Iceberg, unordered `lineitem` | Iceberg, sorted `lineitem_by_suppkey` |
|---|---|---|---|
| MAX(l_suppkey) | 24.5 → 2.2, 11.26x | 20.4 → 2.7, 7.68x | 16.9 → 2.7, 6.38x |
| Join MAX(l_suppkey) with orders | 33.7 → 6.9, 4.89x | 34.7 → 11.2, 3.10x | 46.5 → 10.7, 4.33x |
| GROUP BY MAX(l_discount) BY l_shipmode | 53.6 → 36.6, 1.47x | 62.7 → 36.1, 1.74x | not run |
| Cross-table MAX(l_extendedprice - ps_supplycost) | 53.9 → 7.2, 7.43x | 60.4 → 48.5, 1.25x | not run |

This is not a comparison of the two storage formats: the Iceberg tables are read from S3Mock on the same
machine through Iceberg's generic Parquet reader, and the internal datasets are AsterixDB's own columnar
LSM storage. Only the Baleen speedup within each column is meaningful.

Reading:

- Scalar MAX and the join: both sides skip in storage (pages internally, files and row groups on Iceberg).
- GROUP BY: the same on both, because neither skips in the scan; the gain is from the tuple filter.
- Cross-table: internally the derived bound on the probe column skips pages (`attachCrossExprPageFilter`).
  That method accepts only an internal columnar scan, so the Iceberg reader never gets the bound.

### Queries

Baleen off is `SET \`compiler.plaque.enabled\` "false";` in front, Baleen on is `"true"`.

Iceberg (`USE baleen_ice;`):

```sql
SELECT MAX(l.l_suppkey) AS m FROM lineitem l;
SELECT MAX(l.l_suppkey) AS m FROM lineitem_by_suppkey l;
SELECT MAX(l.l_suppkey) AS m FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey;
SELECT MAX(l.l_suppkey) AS m FROM lineitem_by_suppkey l, orders o WHERE l.l_orderkey = o.o_orderkey;
SELECT MAX(l.l_discount) AS m, l.l_shipmode FROM lineitem l GROUP BY l.l_shipmode;
SELECT MAX(l.l_extendedprice - ps.ps_supplycost) AS m FROM lineitem l, partsupp ps WHERE l.l_partkey = ps.ps_partkey AND l.l_suppkey = ps.ps_suppkey;
```

Internal columnar:

```sql
SELECT MAX(l.l_suppkey) AS m FROM Lineitem_column_10 l;
SELECT MAX(l.l_suppkey) AS m FROM Lineitem_column_10 l, Orders_column_10 o WHERE l.l_orderkey = o.o_orderkey;
SELECT MAX(l.l_discount) AS m, l.l_shipmode FROM Lineitem_column_10 l GROUP BY l.l_shipmode;
SELECT MAX(l.l_extendedprice - ps.ps_supplycost) AS m FROM Lineitem_column_10 l, Partsupp_column_10 ps WHERE l.l_partkey = ps.ps_partkey AND l.l_suppkey = ps.ps_suppkey;
```

Scripts: `/scratch/asterixdb_eightynode/build_logs/bench_plaque_iceberg.py` and `bench_plaque.py`.

## What the Iceberg Parquet reader offers

Checked in `iceberg-parquet-1.12.0.jar` with `javap`:

| Feature | Class | What it can decide | Used today by |
|---|---|---|---|
| File min, max, null count, NaN count (manifest) | `DataFile` | Skip a whole file | MAX/MIN, DISTINCT level 2 |
| Row-group min and max | `ParquetMetricsRowGroupFilter` | Skip a row group by range | MAX/MIN |
| Row-group dictionary | `ParquetDictionaryRowGroupFilter` | Skip a row group by its exact set of values | DISTINCT level 3 (`NOT IN`) |
| Row-group Bloom filter | `ParquetBloomRowGroupFilter` | Prove a value is absent | nothing |
| Read one byte range of a file | `Parquet.ReadBuilder.split(start, length)` | Open one row group at a time | nothing |

The dictionary filter's visitor has `lt`, `ltEq`, `gt`, `gtEq`, `eq`, `notEq`, `in`, `notIn`, `startsWith`,
`and`, `or`, `not`. The jar has no class for Parquet page indexes (column index, offset index).

## Ideas, ranked

1. **GROUP BY skipping through the dictionary.** Largest expected gain.
   - Filter handed to the reader: `(g = 'A' AND x > tA) OR (g = 'B' AND x > tB) OR ... OR g NOT IN (groups seen)`,
     with one branch per group that has a threshold.
   - The dictionary of `g` says exactly which groups a row group holds. The row group is skipped when no group
     in it can still improve and it holds no unseen group.
   - On the benchmark query `l_discount` has 11 values and its maximum 0.10 is in every row group. With ties
     dropped, once all 7 ship modes have reached 0.10 every remaining row group should be skippable.
   - Needs both columns dictionary-encoded in the row group, and few groups (the expression grows with them).
   - It goes through the same reader path as DISTINCT level 3; no new reader code.

2. **Top-K and cross-table on Iceberg.** Small change. Both reduce to a bound on one column, which
   `PlaqueIcebergScanFilter` already handles for files and row groups. Top-K must keep ties (`>=`), so the
   filter needs a non-strict mode. Cross-table needs the derived bound (threshold combined with the build
   side's minimum) instead of the raw threshold.

3. **DISTINCT: test the dictionary directly against the seen-set.** Read a row group's dictionary page and
   probe the Roaring bitmap or hash set, instead of building a `NOT IN` list. Removes the list and its cap
   (`compiler.plaque.distinct.notinmax`, default 10,000). Still needs a dictionary, so with default writer
   settings it does not help `l_suppkey` or `l_partkey`.

4. **Re-check the threshold per row group.** The row-group filter is fixed when a file is opened, so each
   reader's first file is read whole. Opening one row group at a time with `split` would let later row groups
   of the same file be skipped. On the sorted-table MAX: about 4 of 120 row groups read instead of 4 of 30
   files. Modest, the query already takes 2.7 s.

5. **DISTINCT level 2 on row-group ranges.** The "every value in the range is seen" test runs on file bounds
   only. Row-group ranges are narrower on clustered data. No help on the unordered tables, where every row
   group spans the full range.

What does not help:

- **Bloom filters.** They can only prove a value is absent, which serves `=` and `IN`. They cannot answer
  `NOT IN` or a threshold.
- **Page indexes.** This reader does not use them; page skipping would mean bypassing Iceberg's reader.
- **Answering from metadata alone.** MAX with no WHERE and no join could be read from the file bounds
  without opening a file. That is a compile-time shortcut, not runtime learning, and it stops working as
  soon as there is a predicate or a join.

## Stated but not verified

- That `l_discount` is dictionary-encoded in `lineitem`. The DISTINCT notes checked `l_shipmode` (dictionary),
  `l_suppkey` and `l_partkey` (plain), not `l_discount`.
- That the dictionary filter combines an `OR` of `AND` branches across two columns the way idea 1 needs.
  The visitor methods exist; the behaviour was not run.
- The "4 of 120 row groups" estimate in idea 4.
- That the row-group metrics filter does not interfere with idea 1 (it should answer "might match" for `NOT IN`).

## Not touched

All DISTINCT code on branch `baleen` is uncommitted and being worked on in another session. It was only read.
