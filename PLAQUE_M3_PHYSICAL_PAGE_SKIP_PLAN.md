# PLAQUE M3: Physical Page Skip for Single-Component Columnar Scans

## Status: Complete (July 2-3, 2026)

## Problem

The PLAQUE page filter (from `PLAQUE_M3_COLUMNAR_PAGE_FILTER_PLAN.md`) skips 99.4% of pages but scan time only drops 9%. The reason: `AbstractColumnTupleReference.reset()` decompresses primary key columns for EVERY page before checking the page filter. For cached data, PK decompression is the dominant per-page cost. The actual column data skip (via `skipMegaLeafNode()`) only avoids the cheap part.

## Why PK Skip is Unsafe in General

In a multi-component LSM scan, the merge cursor compares PKs across components to:
- Deduplicate: if key 42 exists in component A (old) and component B (new), emit only B's version
- Handle deletes: if component B has a tombstone for key 42, suppress key 42 from component A

Skipping PKs would cause the merge to emit stale or deleted rows -- wrong answers.

## Why PK Skip is Safe for Single-Component Scans

Single-component = one flushed/merged LSM component, no memory component with unflushed data. This means:
- No duplicate keys across components (nothing to reconcile)
- No tombstones to suppress (nothing to reconcile)
- The scan just iterates one B-tree's leaf pages sequentially

Page advancement uses B-tree pointers (`frame.getNextLeaf()`), not PK values. So skipping a page's PKs doesn't break cursor navigation.

## Detection

At cursor open time in `LSMBTreeRangeSearchCursor.doOpen()`:
```java
int numBTrees = operationalComponents.size(); // line 378
```
`numBTrees == 1` -- single component -- safe to skip PKs for filtered pages.

**When is a table single-component?** After bulk load, after full compaction, or any read-heavy analytical table that isn't actively being updated -- the typical PLAQUE target workload.


## Implementation (Completed July 2-3)

### What Was Changed

The fix was in `newPage()` inside `AbstractColumnTupleReference`: check the page filter BEFORE PK decompression, and loop in `fetchNextLeafPage()` to skip consecutive filtered pages.

**Simplified logic:**

```java
boolean readColumnPages = startNewPage(pageZero, frame.getNumberOfColumns(), numberOfTuples);
unpinColumnsPages();

if (!readColumnPages && skipPrimaryKeysOnFilteredPages) {
    // FAST PATH: single-component scan, page filtered out -> skip everything
    skipMegaLeafNode();
    numOfSkippedMegaLeafNodes++;
} else {
    // NORMAL PATH: read PKs, then maybe read columns
    int skipCount = setPrimaryKeysAt(startIndex, startIndex);
    if (readColumnPages) {
        for (...) { startColumnFilter(...); }
    }
    if (readColumnPages && evaluateFilter()) {
        for (...) { startColumn(...); }
        skip(Math.max(skipCount, 0));
    } else {
        skipMegaLeafNode();
        numOfSkippedMegaLeafNodes++;
    }
}
```

### Files Modified

1. **`AbstractColumnTupleReference.java`** (`hyracks-storage-am-lsm-btree-column`) -- Added `skipPrimaryKeysOnFilteredPages` flag and modified `reset()` / `newPage()` to use it
2. **`ColumnBTreeRangeSearchCursor.java`** (`hyracks-storage-am-lsm-btree-column`) -- Added method to forward the flag to `frameTuple`
3. **`LSMColumnBTreeRangeSearchCursor.java`** (`hyracks-storage-am-lsm-btree-column`) -- Set the flag when `operationalComponents.size() == 1`

### AsterixDB Bug Found: DoubleColumnFilterWriter

`DoubleColumnFilterWriter.reset()` has swapped initial values:
- **Bug:** `min = Double.MIN_VALUE, max = Double.MAX_VALUE` 
- **Should be:** `min = Double.MAX_VALUE, max = Double.MIN_VALUE`

Result: all page-level min/max metadata for double columns is always wrong (stores the reset defaults, never updates correctly). Page filters never skip pages for double columns.

**Workaround:** All experiments use `l_suppkey` (bigint) instead of `l_extendedprice` (double). Bigint columns use `LongColumnFilterWriter` which has correct initialization.


## Columnar Storage Background

### Mega Leaf Node Layout (one page group = one batch of ~15,000 tuples)

```
+----------------------------------------------------------------------+
| PAGE ZERO (header page)                                              |
|  +-------------------------------------------------------------+    |
|  | Fixed Header: tuple count, next-leaf B-tree pointer           |    |
|  +-------------------------------------------------------------+    |
|  | Column Offsets: [col0_offset, col1_offset, ... col15_offset]  |    |
|  +-------------------------------------------------------------+    |
|  | Min/Max Zone Maps: 16 bytes per column (normalized min + max) |    |
|  |   col0: [min_long, max_long]  <- l_orderkey                  |    |
|  |   col5: [min_long, max_long]  <- l_extendedprice             |    |
|  |   ...                                                         |    |
|  +-------------------------------------------------------------+    |
|  | Primary Key Columns (compressed): l_orderkey, l_linenumber    |    |
|  |   -> Decompression walks all ~15K tuples                      |    |
|  +-------------------------------------------------------------+    |
+----------------------------------------------------------------------+
| PAGE 1: Non-key column data (e.g., l_suppkey, compressed)            |
+----------------------------------------------------------------------+
| PAGE 2: Non-key column data (e.g., l_extendedprice, compressed)      |
+----------------------------------------------------------------------+
| PAGE 3+: More non-key columns...                                     |
+----------------------------------------------------------------------+
```

### Normal Scan Flow (WITHOUT physical page skip)

Both filtered and non-filtered pages decompress primary keys -- this is the dominant per-page cost for cached data.

### PLAQUE Physical Page Skip Flow (single-component scan)

```
              Pin Page Zero
              (read header + zone maps)
                      |
                      v
              Evaluate PLAQUE Page Filter
              (runtime threshold vs page max from zone map)
                      |
          +-----------+-----------+
          |                       |
    PASSES FILTER          REJECTED BY FILTER
    (~1% of pages)         (~99% of pages)
          |                       |
          v                       v
  Decompress PKs          *** PHYSICAL SKIP ***
  Decompress Columns      No PK decompression
  Row-Level Filter         No column decompression
  Assemble + emit          No tuple emission
                           Advance B-tree pointer
                           to next mega leaf node
```


## Experiment Results

### M2 Join Query: `SELECT MAX(l.l_suppkey) FROM Lineitem_column_SF l, Orders_column_SF o WHERE l.l_orderkey = o.o_orderkey`

| Scale Factor | Baseline | PLAQUE + Physical Page Skip | Speedup |
|---|---|---|---|
| SF 10 (60M rows) | 38.1s | 9.1s | **4.20x** |
| SF 30 (180M rows) | 117.1s | 24.6s | **4.76x** |
| SF 50 (300M rows) | 194.1s | 37.9s | **5.11x** |
| SF 100 (600M rows) | 380.9s | 71.2s | **5.35x** |

Speedup consistently increases with scale factor.

**Detailed SF 10:**

| Metric | Baseline | PLAQUE | Delta |
|---|---|---|---|
| Exec time | 38.1s | 9.1s | 4.20x |
| Scan | 84,565 ms | 18,888 ms | 78% less |
| Scan tuples out | 74,986,052 | 19,627,647 | 74% fewer |
| Join | 72,824 ms | 57 ms | 1,277x less |
| Join tuples out | 59,986,052 | 33,795 | 1,775x fewer |
| Aggregate | 6,459 ms | 8 ms | 807x less |
| PLAQUE Filter | -- | 351 ms | -- |

**Detailed SF 100:**

| Metric | Baseline | PLAQUE | Delta |
|---|---|---|---|
| Exec time | 380.9s | 71.2s | 5.35x |
| Scan | 832,341 ms | 134,209 ms | 84% less |
| Scan tuples out | 750,037,902 | 159,275,183 | 79% fewer |
| Join | 1,000,514 ms | 286 ms | 3,498x less |
| Join tuples out | 600,037,902 | 36,934 | 16,245x fewer |
| Aggregate | 60,790 ms | 133 ms | 457x less |
| PLAQUE Filter | -- | 732 ms | -- |

Note: SF100 with 32GB buffer cache (not fully cached). Despite I/O, highest speedup.

### M1 Single-Table: `SELECT MAX(l_suppkey) FROM Lineitem_column_SF`

| Scale Factor | Baseline | PLAQUE | Speedup |
|---|---|---|---|
| SF 10 (60M rows) | 28.7s | 2.48s | **11.6x** |
| SF 30 (180M rows) | 82.0s | 0.89s | **92x** |
| SF 50 (300M rows) | 140.0s | 5.90s | **23.7x** |
| SF 100 (600M rows) | 278.2s | 6.07s | **45.8x** |

M1 speedups are dramatically higher than M2 because there is no join pipeline delay -- threshold initializes immediately and pages start getting skipped from the beginning. SF30 achieves 92x because the data is fully cached (48GB cache, SF30 fits); SF50/100 are not fully cached (32GB cache) so I/O becomes a factor.

**Detailed SF 30:**

| Metric | Baseline | PLAQUE | Delta |
|---|---|---|---|
| Exec time | 82.0s | 0.89s | 92x |
| Scan | 209,747 ms | 1,751 ms | 120x less |
| Scan tuples | 179,998,372 | 1,019,923 | 99.4% fewer |
| Aggregate | 17,685 ms | 3 ms | 5,895x less |

**Detailed SF 100:**

| Metric | Baseline | PLAQUE | Delta |
|---|---|---|---|
| Exec time | 278.2s | 6.07s | 45.8x |
| Scan | 702,873 ms | 15,493 ms | 45x less |
| Scan tuples | 600,037,902 | 12,040,253 | 98.0% fewer |
| Aggregate | 61,787 ms | 3 ms | 20,596x less |
