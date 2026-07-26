# PLAQUE Columnar Page-Level Filter — Implementation Plan

## Context

PLAQUE's strong filter (below the hash-partition exchange) reduces join work by 881x and aggregate work by 2,224x at SF50. But the scan still dominates wall clock because it reads all 300M lineitem tuples — the tuple-level filter sits *after* deserialization. AsterixDB's columnar format stores per-page min/max values (zone maps) and can skip entire pages via `rangeFilterEvaluator`. We want to inject the PLAQUE runtime threshold into this page-level filter so pages where `max(col) < threshold` (for MAX queries) are skipped before any column data is read.

## Approach

Inject a PLAQUE-aware page filter evaluator into the existing `IColumnRangeFilterEvaluatorFactory` pipeline. The factory is serializable (captures only `handle`, `isMax`, column index) and resolves the runtime threshold from `PlaqueThresholdRegistry` at evaluation time.

**Key challenge:** Page min/max are stored as normalized longs (e.g., `Double.doubleToLongBits(value)`). The PLAQUE threshold is raw AsterixDB typed bytes. We need to normalize the threshold at evaluation time using the same normalization as the column filter writers.

## Files to Create (3)

### 1. `PlaqueColumnFilterEvaluatorFactory.java`
**Path:** `asterix-column/src/main/java/org/apache/asterix/column/filter/range/evaluator/PlaqueColumnFilterEvaluatorFactory.java`

Serializable factory implementing `IColumnRangeFilterEvaluatorFactory`. Fields: `handle` (String), `isMax` (boolean), `columnFieldPath` (ARecordType for the aggregated column).

`create(FilterAccessorProvider)`:
1. Gets a `ColumnRangeFilterValueAccessor` for the aggregated column (to read page max/min)
2. Returns a `PlaqueColumnFilterEvaluator` that holds:
   - The page value accessor (reads page max for MAX queries, page min for MIN queries)
   - A reference to resolve `PlaqueThresholdState` at first call (lazy, needs JobId from task context)

### 2. `PlaqueColumnFilterEvaluator.java` (inner class or separate)
**Path:** Same file or separate in the evaluator package.

Implements `IColumnFilterEvaluator`. On each `evaluate()` call:
1. If threshold not initialized → return `true` (pass page, conservative)
2. Get threshold snapshot bytes from `PlaqueThresholdState`
3. Normalize the threshold bytes to a long (using the same normalization as the column filter writer for the type — e.g., `Double.doubleToLongBits()` for doubles)
4. Read page max (for MAX) or page min (for MIN) from the accessor's `getNormalizedValue()`
5. For MAX: skip page if `pageMax < normalizedThreshold` (no tuple on this page can beat the threshold)
6. For MIN: skip page if `pageMin > normalizedThreshold`
7. Return `true` (read page) or `false` (skip page)

**Normalization:** Create a static utility method that normalizes AsterixDB typed bytes to a long matching the column filter scheme:
- DOUBLE: read the 8-byte double, apply `Double.doubleToLongBits()`
- BIGINT/INT: read the integer value, direct long cast
- STRING: use the same normalization as `StringColumnFilterWriter` (first 8 bytes)
- Reference: `DoubleColumnFilterWriter.getMaxNormalizedValue()`, `LongColumnFilterWriter`, `StringColumnFilterWriter`

### 3. `PlaquePageFilterAnnotationRule.java` (optional — see Alternative below)
**Path:** `asterix-algebra/src/main/java/org/apache/asterix/optimizer/rules/PlaquePageFilterAnnotationRule.java`

Optimizer rule that finds PLAQUE-annotated aggregates, walks to the `DataSourceScanOperator`, and annotates it with PLAQUE metadata (handle, isMax, column path) so the scan's code generation can compose the page filter.

**Alternative (simpler):** Skip the separate rule. Instead, modify `PlaqueRewriteRule` to also annotate the `DataSourceScanOperator` when the dataset is columnar. The scan annotation carries the handle, isMax, and column field path.

## Files to Modify (4)

### 4. `QueryColumnMetadata.java`
**Path:** `asterix-column/src/main/java/org/apache/asterix/column/operation/query/QueryColumnMetadata.java`

In `create()` (line 206-225), after `normalizedFilterEvaluator = normalizedEvaluatorFactory.create(accessorProvider)`:
- Check if a PLAQUE evaluator factory was also passed (new parameter or composed into the normalized factory)
- If so, AND-compose it: `normalizedFilterEvaluator = new ANDFilterEvaluator(normalizedFilterEvaluator, plaqueEvaluator)`
- Pass `context` (IHyracksTaskContext) to the PLAQUE evaluator so it can resolve JobId for the registry

### 5. `IndexUtil.java`
**Path:** `asterix-metadata/src/main/java/org/apache/asterix/metadata/utils/IndexUtil.java`

In `createTupleProjectorFactory()` (or wherever `rangeFilterEvaluatorFactory` is built):
- Check for PLAQUE annotations on the scan operator
- If present, AND-compose `PlaqueColumnFilterEvaluatorFactory` with the existing range filter factory:
  ```java
  if (plaqueInfo != null) {
      IColumnRangeFilterEvaluatorFactory plaqueFactory = new PlaqueColumnFilterEvaluatorFactory(handle, isMax, columnPath);
      rangeFilterFactory = new ANDColumnFilterEvaluatorFactory(rangeFilterFactory, plaqueFactory);
  }
  ```

### 6. `ColumnDatasetProjectionFiltrationInfo.java`
**Path:** `asterix-runtime/src/main/java/org/apache/asterix/runtime/projection/ColumnDatasetProjectionFiltrationInfo.java`

Add optional PLAQUE metadata fields:
- `plaqueHandle` (String, nullable)
- `plaqueIsMax` (boolean)
- `plaqueColumnPath` (ARecordType, nullable)
- Getters for each

### 7. `PlaqueRewriteRule.java`
**Path:** `asterix-algebra/src/main/java/org/apache/asterix/optimizer/rules/PlaqueRewriteRule.java`

When inserting the deep filter, also walk to the `DataSourceScanOperator` and annotate it with PLAQUE metadata (handle, isMax, column field path). This enables the columnar scan to compose the page filter during code generation. Only annotate if the scan is on a columnar dataset.

## How the PLAQUE Evaluator Gets JobId

The `PlaqueColumnFilterEvaluator` needs `JobId` to look up the threshold in `PlaqueThresholdRegistry`. Two options:

**Option A (preferred):** The evaluator factory's `create(FilterAccessorProvider)` receives the accessor provider. We add `IHyracksTaskContext` to `FilterAccessorProvider` (it's already created in `QueryColumnMetadata.create()` where `context` is available — line 212-213). Then the evaluator extracts `context.getJobletContext().getJobId()`.

**Option B:** Pass JobId as a string through the evaluator factory (serializable). But the JobId isn't known at compile time — it's assigned when the job runs. So Option A is necessary.

**Modification to FilterAccessorProvider:** Add a `context` field and `getContext()` getter. Set it in the constructor in `QueryColumnMetadata.create()`.

## Correctness Argument

1. **Conservative:** If threshold not initialized, page passes (no false skips)
2. **Monotonic:** For MAX, the threshold only increases. If `pageMax < threshold`, no tuple on the page has value >= threshold, so all would be filtered by the tuple-level filter anyway. Skipping is equivalent to filtering every tuple.
3. **Normalized comparison is correct:** We use the same normalization as the column filter writers. For doubles, `Double.doubleToLongBits()` preserves ordering for positive values and NaN-free comparison.
4. **No correctness dependency on page filter:** The tuple-level PLAQUE filter still runs as backup. The page filter is a pure optimization — if it incorrectly passes a page, the tuple filter catches it.

## What Happens for ROW Format

The page filter only applies to columnar datasets. For ROW format:
- `ColumnDatasetProjectionFiltrationInfo` is not used (row datasets use `DatasetProjectionFiltrationInfo`)
- The `PlaqueColumnFilterEvaluatorFactory` is never composed
- The tuple-level `PlaqueFilterRuntime` handles all filtering as before

## Verification

1. Run on SF30 columnar: `SELECT MAX(l.l_extendedprice) FROM Lineitem_column_30 l, Orders_column_30 o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderdate < '1995-01-01'` with PLAQUE enabled and profiling
2. Check `numOfSkippedMegaLeafNodes` counter in profiler — should be non-zero with PLAQUE
3. Compare scan time with and without page filter — expect significant reduction
4. Verify correct result (104945.5 for SF30)
5. Run on ROW format (SF50) to confirm no regression
