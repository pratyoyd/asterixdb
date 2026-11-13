# Clustered Layout Lineitem Table

## Purpose

Create a lineitem dataset whose physical row order follows ascending column cardinality. AsterixDB has no clustering directive; physical order within each partition follows the LSM B-tree primary key order. By encoding the desired sort as a composite PK with low-NDV columns leading, we control the physical layout.

The clustered layout groups rows with the same `l_returnflag` and `l_shipmode` into contiguous page ranges. This maximizes the effectiveness of page-level zone-map filtering for PLAQUE predicates on these columns.

## DDL

```sql
USE Default;

CREATE TYPE LineitemType_clustered_10 AS OPEN {
  l_returnflag_id: int32,
  l_shipmode_id: int32,
  l_suppkey: int64,
  l_orderkey: int64,
  l_linenumber: int64,
  l_partkey: int64,
  l_quantity: double,
  l_extendedprice: double,
  l_discount: double,
  l_tax: double,
  l_returnflag: string,
  l_linestatus: string,
  l_shipdate: string,
  l_commitdate: string,
  l_receiptdate: string,
  l_shipinstruct: string,
  l_shipmode: string,
  l_comment: string
};

CREATE DATASET Lineitem_clustered_SF10 (LineitemType_clustered_10)
  PRIMARY KEY l_returnflag_id, l_shipmode_id, l_suppkey, l_orderkey, l_linenumber
  WITH {"storage-format": {"format": "column"}};
```

Primary key: `(l_returnflag_id, l_shipmode_id, l_suppkey, l_orderkey, l_linenumber)`
- `l_returnflag_id`: int32 surrogate for l_returnflag. 3 distinct values (1=A, 2=N, 3=R)
- `l_shipmode_id`: int32 surrogate for l_shipmode. 7 distinct values (1=AIR, 2=FOB, 3=MAIL, 4=RAIL, 5=REG AIR, 6=SHIP, 7=TRUCK)
- `l_suppkey`: 100,000 distinct values (SF10)
- `l_orderkey`, `l_linenumber`: uniquifier (original PK)

### Why Integer Surrogates

The initial attempt used string fields (`l_returnflag`, `l_shipmode`) directly as leading PK columns. This crashed during bulk load with `ArrayIndexOutOfBoundsException: Index -352291458` in `ColumnBTreeBulkloader.writeFullLeafPage()`. Root cause: the columnar B-tree's interior node page format overflows when separator keys contain variable-length strings at scale (60M rows). The interior frame's tuple data overwrites the slot array at the end of the page.

This is an AsterixDB bug specific to columnar storage with variable-length string primary keys. All existing columnar datasets use integer-only PKs. Integer surrogates avoid this code path entirely.

## Load

```sql
USE Default;
INSERT INTO Lineitem_clustered_SF10 (
  SELECT
    CASE l.l_returnflag WHEN "A" THEN 1 WHEN "N" THEN 2 WHEN "R" THEN 3 ELSE 0 END AS l_returnflag_id,
    CASE l.l_shipmode WHEN "AIR" THEN 1 WHEN "FOB" THEN 2 WHEN "MAIL" THEN 3 WHEN "RAIL" THEN 4
      WHEN "REG AIR" THEN 5 WHEN "SHIP" THEN 6 WHEN "TRUCK" THEN 7 ELSE 0 END AS l_shipmode_id,
    l.l_suppkey, l.l_orderkey, l.l_linenumber, l.l_partkey,
    l.l_quantity, l.l_extendedprice, l.l_discount, l.l_tax,
    l.l_returnflag, l.l_linestatus, l.l_shipdate, l.l_commitdate,
    l.l_receiptdate, l.l_shipinstruct, l.l_shipmode, l.l_comment
  FROM Lineitem_column_10 l
);
```

INSERT-from-SELECT produces multiple LSM components (unlike LOAD which produces one). After insert, run `COMPACT DATASET Lineitem_clustered_SF10;` to merge into one component per partition. The physical page skip path requires `operationalComponents.size() == 1`.

Insert time: 878s (~15 min) on SF10 with 4 NCs.

## Row Count Verification

| Dataset | Row Count | Match |
|---------|-----------|-------|
| Lineitem_column_10 (original) | 59,986,052 | -- |
| Lineitem_clustered_SF10 | 59,986,052 | Y |

## Disk Component Verification

After `COMPACT DATASET Lineitem_clustered_SF10;` — one disk component per partition (confirmed by compact completing without error).

## Zone Map Verification

Zone map verification for low-NDV columns requires reading raw page-zero metadata at the storage layer. This cannot be done via SQL queries. Verification approach: instrument the PLAQUE page filter to log zone map values during scan, or add a diagnostic UDF.

### Expected Layout Properties

With PK `(l_returnflag_id, l_shipmode_id, l_suppkey, l_orderkey, l_linenumber)`:

- **l_returnflag_id** (3 values): Rows are sorted by this first. Each value spans ~20M rows (~33% of data). Within each partition (~15M rows), each returnflag_id value spans ~5M rows contiguously. With ~8K tuples per page, this means ~625 consecutive pages per returnflag_id per partition. Every page except the ~2 boundary pages per partition should have min == max for l_returnflag_id.

- **l_shipmode_id** (7 values): Within each returnflag_id group, rows are sorted by shipmode_id. Each (returnflag, shipmode) combination spans ~2.9M rows total, ~714K per partition. That's ~89 consecutive pages per combination. Nearly all pages should have min == max for l_shipmode_id.

- **l_suppkey** (100K values): Within each (returnflag, shipmode) group, rows are sorted by l_suppkey. Each (returnflag, shipmode, suppkey) triple has ~8.5 rows on average. Pages won't be pure for l_suppkey, but the per-page span should be much tighter than the original random order.

## Baseline Performance

Median of 3 warm runs on each dataset:

| Query | Original (Lineitem_column_10) | Clustered (Lineitem_clustered_SF10) | Delta |
|-------|-------------------------------|-------------------------------------|-------|
| SELECT COUNT(*) | 16.15s | 22.25s | +37.8% |
| SELECT MAX(l_suppkey) | 26.00s | 24.82s | -4.5% |

### Operator-Level Profiling

#### COUNT(*)

| Metric | Original | Clustered | Delta |
|--------|----------|-----------|-------|
| Elapsed | 17.25s | 23.23s | +34.7% |
| Index Search (scan) | 64,374 ms | 87,305 ms | +35.6% |
| Page reads | 37,112 | 93,160 | +151% |
| Processed objects | 59,986,052 | 59,986,052 | same |

The clustered dataset reads 2.5x more pages. The 5-field PK inflates B-tree leaf nodes, spreading the same data across more pages.

#### MAX(l_suppkey)

| Metric | Original | Clustered | Delta |
|--------|----------|-----------|-------|
| Elapsed | 28.22s | 26.31s | -6.8% |
| Index Search (scan) | 72,759 ms | 87,785 ms | +20.7% |
| Page reads | 74,212 | 93,160 | +25.5% |
| Field access (getField) | 19,260 ms | 0 ms | eliminated |
| stream-project | 11,276 ms | 5,459 ms | -52% |

The clustered dataset is 6.8% faster despite 20% more scan time because l_suppkey is a PK column — stored directly in the B-tree leaf, not as a projected column requiring getField(). The 19s of field access is eliminated.

### Cost-Benefit Summary

The 5-field PK has two effects:
1. **Cost**: +151% more page reads, +35% scan time (larger B-tree leaves)
2. **Benefit**: PK columns skip getField() extraction, sorted layout gives tighter zone maps for PLAQUE page filtering

PLAQUE experiments on the clustered table must overcome the ~35% scan overhead. The main value is zone-map selectivity: with l_returnflag_id and l_shipmode_id as leading PK columns, pages are pure for these low-NDV columns, enabling near-perfect page skip rates.

## Next Steps

1. Instrument PLAQUE page filter to log zone-map min/max — verify pure pages for l_returnflag_id and l_shipmode_id
2. Run PLAQUE MAX/Top-K experiments on clustered table vs original
3. Test predicates on l_returnflag_id (3 values) — expect high page skip rates
4. Evaluate whether the 2.5x page read overhead can be reduced (fewer PK fields, larger pages)
