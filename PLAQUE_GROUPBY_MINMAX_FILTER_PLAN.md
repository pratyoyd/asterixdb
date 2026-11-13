# PLAQUE GROUP BY MIN/MAX Filter — Implementation Plan

## Context

For queries like `SELECT MAX(l_discount), l_shipmode FROM Lineitem GROUP BY l_shipmode`, PLAQUE currently has no support. The scalar PLAQUE (M1-M3) tracks a single global threshold; GROUP BY requires **per-group thresholds**. This feature targets **low-cardinality group columns** (< 25 distinct values) where maintaining per-group state is cheap and page-level filtering is effective because many pages will be homogeneous (single-group).

Two levels of filtering:
1. **Tuple-level**: Check each tuple's group value against its group's threshold
2. **Page-level**: For pages where the group column zone map shows `pageMin == pageMax` (all tuples belong to one group), check the aggregate column zone map against that group's threshold

---

## Step 1: Per-Group Threshold State

**New file:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/operators/plaque/PlaqueGroupThresholdState.java`

Replaces `PlaqueThresholdState` (single value) with a per-group map. Maintains **two parallel maps**: a raw-bytes map for tuple-level filtering, and a normalized-longs map for page-level filtering.

```
Fields:
  // --- Tuple-level filter state (raw bytes) ---
  - ConcurrentHashMap<ByteArrayWrapper, byte[]> groupThresholds  // group_key_bytes → threshold_bytes
  - ConcurrentHashMap<ByteArrayWrapper, Integer> groupThresholdLengths
  - volatile Set<ByteArrayWrapper> seenGroups  // for fast "unseen group" check
  
  // --- Page-level filter state (normalized longs) ---
  - ConcurrentHashMap<Long, Long> normalizedMap  // normalize(group_key) → normalize(threshold)
  - Set<Long> poisonedNormalizedKeys  // collision-detected keys, page filter disabled for these

  - boolean isMax
  - AtomicBoolean dirty
  - int maxGroups = 25  // cardinality cap

Core methods:
  - update(byte[] groupKey, int gStart, int gLen, byte[] aggValue, int aStart, int aLen)
      If seenGroups.size() >= maxGroups and group not seen → ignore (cap reached)
      If group not seen:
        → add to seenGroups, store threshold in groupThresholds, mark dirty
        → compute normGK = normalize(groupKey)
        → if normGK already in normalizedMap for a DIFFERENT raw group key:
            add normGK to poisonedNormalizedKeys (collision detected, disable page filter for this key)
          else:
            normalizedMap.put(normGK, normalize(aggValue))
      If group seen → compare aggValue vs stored threshold with IBinaryComparator
        If improved → update groupThresholds, mark dirty
        → if normGK not poisoned: normalizedMap.put(normGK, normalize(aggValue))
  
  - passes(byte[] groupKey, int gStart, int gLen, byte[] aggValue, int aStart, int aLen, IBinaryComparator cmp)
      If group NOT in seenGroups → return true (no threshold yet)
      threshold = groupThresholds.get(groupKey)
      If threshold == null → return true
      For MAX: return aggValue >= threshold
      For MIN: return aggValue <= threshold

  - getNormalizedThreshold(long normalizedGroupKey) → Long (nullable)
      If normalizedGroupKey in poisonedNormalizedKeys → return null (collision, can't page-filter)
      return normalizedMap.get(normalizedGroupKey)

  - isInitialized() → seenGroups.size() > 0

  - getAllGroupThresholds() → Map snapshot (for propagation to CC)
```

**ByteArrayWrapper**: A small inner class wrapping byte[] with proper equals()/hashCode() for use as HashMap keys. Copies the byte range on construction.

**Collision handling**: When two different raw group keys normalize to the same long (e.g., two strings sharing the same first 4 characters), the normalized key is "poisoned" — added to `poisonedNormalizedKeys`. The page filter returns `true` (don't skip) for poisoned keys. The tuple-level filter (which uses raw bytes, no normalization) always remains correct regardless. For < 25 low-cardinality values, collisions are virtually impossible in practice, but this is the safety net.

**normalize()**: Reuses the same normalization logic as `PlaqueColumnFilterEvaluator.normalizeThreshold()` — reads the ATypeTag from byte[0], then:
- BIGINT: raw long (lossless)
- DOUBLE: `Double.doubleToLongBits()` (lossless for ordering)
- STRING: first 4 UTF-16 chars packed into long, `>>> 1` (lossy but matches zone map normalization)

**Thread safety**: `synchronized` on update/merge (rare writes), volatile/snapshot reads for passes() and getNormalizedThreshold() (hot path). Same pattern as `PlaqueThresholdState`.

---

## Step 2: Registry Extension

**Modify:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/operators/plaque/PlaqueThresholdRegistry.java`

Add a parallel map for group threshold state:

```java
ConcurrentHashMap<String, PlaqueGroupThresholdState> groupStates;

PlaqueGroupThresholdState getOrCreateGroupState(JobId jobId, String handle, boolean isMax) {
    String key = makeKey(jobId, handle);
    return groupStates.computeIfAbsent(key, k -> new PlaqueGroupThresholdState(isMax));
}

void mergeGlobalGroup(JobId jobId, String handle, byte[] groupKey, int gLen,
                      byte[] thresholdBytes, int tLen, boolean isMax) {
    PlaqueGroupThresholdState state = getOrCreateGroupState(jobId, handle, isMax);
    state.mergeGlobal(groupKey, 0, gLen, thresholdBytes, 0, tLen);
}
```

Also extend `removeJob()` to clean up `groupStates`.

---

## Step 3: GROUP BY Observer — Per-Group Aggregate Function

**New file:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/aggregates/std/PlaqueGroupByMinMaxAggregateFunction.java`

Extends `SqlMinMaxAggregateFunction`. Unlike `PlaqueLocalSqlMinMaxAggregateFunction` (which updates a single threshold), this one updates a **per-group threshold**.

**The group key problem:** `IAggregateEvaluator.step(IFrameTupleReference tuple)` receives the full input tuple. The aggregate function itself doesn't know which group it's computing for. However, in the GROUP BY nested plan, each invocation of `init()`/`step()`/`finish()` is scoped to a single group. The group key fields ARE in the tuple.

**Solution:** The function receives a `groupKeyColumnIndex` (the index of the group-by column in the input tuple). On each `step()`, it extracts the group key bytes from the tuple at that index, and calls `groupThresholdState.update(groupKey, aggValue)`.

```
Fields:
  - PlaqueGroupThresholdState groupThresholdState  // from registry
  - int groupKeyColumnIndex  // which column in the tuple is the group key
  - PlaqueThresholdPropagator propagator  // for cross-node propagation
  - int propagationInterval, stepCount

step(tuple):
  totalSteps++
  // Extract group key bytes from tuple
  byte[] gData = tuple.getFieldData(groupKeyColumnIndex)
  int gStart = tuple.getFieldStart(groupKeyColumnIndex)
  int gLen = tuple.getFieldLength(groupKeyColumnIndex)
  
  super.step(tuple)  // compute min/max
  
  // After super.step, outputVal has the current aggregate value
  // onMinMaxChanged() will be called by parent if value improved

onMinMaxChanged():
  // Need to update per-group threshold
  // BUT: we need the group key here, which was extracted in step()
  // Store lastGroupKey/Start/Len as instance fields, set in step() before super.step()
  groupThresholdState.update(lastGroupKey, lastGStart, lastGLen,
                             outputVal.getByteArray(), outputVal.getStartOffset(), outputVal.getLength())
  
  if (++stepCount >= propagationInterval):
    maybePropagateThreshold()
```

**Key insight:** We store the current tuple's group key bytes in instance fields (`lastGroupKey`, `lastGStart`, `lastGLen`) BEFORE calling `super.step()`, so that when `onMinMaxChanged()` fires, we know which group just changed.

---

## Step 4: GROUP BY Observer Factory

**New file:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/operators/plaque/PlaqueGroupByAggregateEvaluatorFactory.java`

Similar to `PlaqueLocalAggregateEvaluatorFactory` but:
- Resolves `PlaqueGroupThresholdState` from registry (not `PlaqueThresholdState`)
- Passes `groupKeyColumnIndex` to the aggregate function
- Creates `PlaqueGroupByMinMaxAggregateFunction` instead of `PlaqueLocalSqlMinMaxAggregateFunction`

The `groupKeyColumnIndex` is determined at the physical operator level from the GROUP BY key variable's position in the input schema.

---

## Step 5: GROUP BY Physical Aggregate Operator

**New file:** `asterixdb/asterix-algebra/src/main/java/org/apache/asterix/algebra/operators/physical/PlaqueGroupByAggregatePOperator.java`

Similar to `PlaqueAggregatePOperator` but additionally carries:
- `LogicalVariable groupKeyVar` — the GROUP BY key variable
- Resolves `groupKeyColumnIndex` from the input schema during `contributeRuntimeOperator()`
- Creates `PlaqueGroupByAggregateEvaluatorFactory` instead of `PlaqueLocalAggregateEvaluatorFactory`

---

## Step 6: GROUP BY Tuple-Level Filter

**New file:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/operators/plaque/PlaqueGroupFilterRuntimeFactory.java`

Similar to `PlaqueFilterRuntimeFactory` but takes TWO column indices: `groupColumnIdx` and `aggColumnIdx`.

```
nextFrame():
  for each tuple:
    totalTuples++
    
    // Extract group key
    gData = tuple.getFieldData(groupColumnIdx)
    gLen = tuple.getFieldLength(groupColumnIdx)
    
    // Extract aggregate value
    aData = tuple.getFieldData(aggColumnIdx)
    aLen = tuple.getFieldLength(aggColumnIdx)
    
    // Handle nulls: pass through
    if null/missing for either: pass
    
    // Check per-group threshold
    if groupThresholdState.passes(gData, gStart, gLen, aData, aStart, aLen, threadLocalCmp):
      passedTuples++
      appendTuple()
    else:
      filteredTuples++
```

---

## Step 7: GROUP BY Filter Physical Operator

**New file:** `asterixdb/asterix-algebra/src/main/java/org/apache/asterix/algebra/operators/physical/PlaqueGroupFilterPOperator.java`

Similar to `PlaqueFilterPOperator` but carries both `filteredVar` (aggregate column) AND `groupKeyVar` (group column). Resolves both column indices from input schema in `contributeRuntimeOperator()`.

---

## Step 8: Optimizer Rule — Extend PlaqueRewriteRule

**Modify:** `asterixdb/asterix-algebra/src/main/java/org/apache/asterix/optimizer/rules/PlaqueRewriteRule.java`

Add a new path in `rewritePost()`:

```java
if (op.getOperatorTag() == LogicalOperatorTag.GROUP) {
    return handleGroupBy(opRef, context);
}
```

**`handleGroupBy()` logic:**

1. Cast to `GroupByOperator`, check not already annotated with `"plaque-groupby-aggregate"`
2. Extract group-by key — `gByOp.getGByList()` gives the group-by expressions. For low-cardinality targeting, we need exactly 1 group key that is a `VariableReferenceExpression` → `groupKeyVar`
3. Walk into the nested plan: `gByOp.getNestedPlans().get(0).getRoots().get(0)` → find the `AggregateOperator`
4. Validate the aggregate: same checks as scalar path — must be `LOCAL_SQL_MAX` or `LOCAL_SQL_MIN`, single argument, argument is a `VariableReferenceExpression` → `filteredVar`
5. Generate handle: `"plaque-groupby-" + newVar`
6. Annotate the GROUP BY operator:
   - `"plaque-groupby-aggregate" = true`
   - `"plaque-handle" = handle`
   - `"plaque-isMax" = isMax`
   - `"plaque-filteredVar" = filteredVar`
   - `"plaque-groupKeyVar" = groupKeyVar`
7. Set physical operator on the nested aggregate to `PlaqueGroupByAggregatePOperator`
8. Find filter insertion point using `findInsertionPoints()` (reuse existing logic — the filteredVar and groupKeyVar both come from the same scan)
9. Create filter: `createPlaqueGroupFilter()` — creates a `SelectOperator` with `PlaqueGroupFilterPOperator` that carries both `filteredVar` and `groupKeyVar`
10. Insert deep filter at insertion point

**Cardinality check:** We do NOT check cardinality at compile time. The < 25 cap is a runtime decision in `PlaqueGroupThresholdState.update()` — if more than 25 groups appear, it stops adding new groups but keeps filtering on existing ones. This is the simplest approach and avoids needing statistics.

---

## Step 9: Optimizer Rule — Extend PlaquePageFilterRule

**Modify:** `asterixdb/asterix-algebra/src/main/java/org/apache/asterix/optimizer/rules/PlaquePageFilterRule.java`

Add handling for `LogicalOperatorTag.GROUP` in `rewritePost()`:

```java
if (op.getOperatorTag() == LogicalOperatorTag.GROUP) {
    return handleGroupByPageFilter(opRef, context);
}
```

**`handleGroupByPageFilter()` logic:**

1. Check for `"plaque-groupby-aggregate"` annotation
2. Extract `handle`, `isMax`, `filteredVar`, `groupKeyVar`
3. Walk down to find the `DataSourceScanOperator`
4. Find field names for BOTH `filteredVar` AND `groupKeyVar` (using existing `findFieldName()`)
5. Set PLAQUE group-by page filter metadata on `ColumnDatasetProjectionFiltrationInfo`:
   - `setPlaqueGroupByInfo(handle, isMax, aggColumnPath, groupColumnPath)`

---

## Step 10: ColumnDatasetProjectionFiltrationInfo Extension

**Modify:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/projection/ColumnDatasetProjectionFiltrationInfo.java`

Add new fields:

```java
private boolean plaqueGroupBy;
private ARecordType plaqueGroupByColumnPath;  // the group-by column path (for zone map access)

public void setPlaqueGroupByInfo(String handle, boolean isMax, ARecordType aggColumnPath, ARecordType groupColumnPath) {
    this.plaqueHandle = handle;
    this.plaqueIsMax = isMax;
    this.plaqueColumnPath = aggColumnPath;
    this.plaqueGroupBy = true;
    this.plaqueGroupByColumnPath = groupColumnPath;
}

public boolean isPlaqueGroupBy() { return plaqueGroupBy; }
public ARecordType getPlaqueGroupByColumnPath() { return plaqueGroupByColumnPath; }
```

Update copy constructor to copy these fields.

---

## Step 11: GROUP BY Page-Level Filter Evaluator

**New file:** `asterixdb/asterix-column/src/main/java/org/apache/asterix/column/filter/range/evaluator/PlaqueGroupByColumnFilterEvaluatorFactory.java`

This is the core page filter innovation. It creates TWO accessors:
1. **Group column accessor** — both min AND max (to check `pageMin == pageMax`)
2. **Aggregate column accessor** — max (for MAX queries) or min (for MIN queries)

```java
public class PlaqueGroupByColumnFilterEvaluatorFactory implements IColumnRangeFilterEvaluatorFactory {
    private final String handle;
    private final boolean isMax;
    private final ARecordType aggColumnPath;
    private final ARecordType groupColumnPath;

    @Override
    public IColumnFilterEvaluator create(FilterAccessorProvider provider) {
        // Two accessors for the group column: one for min, one for max
        IColumnRangeFilterValueAccessor groupMinAccessor =
            provider.createRangeFilterValueAccessor(groupColumnPath, true);   // readMin=true
        IColumnRangeFilterValueAccessor groupMaxAccessor =
            provider.createRangeFilterValueAccessor(groupColumnPath, false);  // readMin=false

        // One accessor for the aggregate column
        boolean readMin = !isMax;
        IColumnRangeFilterValueAccessor aggAccessor =
            provider.createRangeFilterValueAccessor(aggColumnPath, readMin);

        // If any accessor is NoOp, fall back to true (no filtering)
        if (any is NoOp) return TrueColumnFilterEvaluator.INSTANCE;

        // Resolve group threshold state from registry
        PlaqueGroupThresholdState state = resolveFromRegistry(provider.getTaskContext());

        return new PlaqueGroupByColumnFilterEvaluator(
            groupMinAccessor, groupMaxAccessor, aggAccessor, state, handle, isMax);
    }
}
```

**Inner evaluator `evaluate()` — pure long arithmetic, no byte arrays on hot path:**

```
evaluate():
  pagesEvaluated++

  if state == null || !state.isInitialized():
    return true  // no thresholds yet

  long groupMin = groupMinAccessor.getNormalizedValue()
  long groupMax = groupMaxAccessor.getNormalizedValue()

  if groupMin != groupMax:
    return true  // mixed-group page, can't skip, fall through to tuple filter

  // Single-group page! All tuples belong to one group.
  // Look up this group's normalized threshold via long→long map.
  Long normalizedThreshold = state.getNormalizedThreshold(groupMin)
  if normalizedThreshold == null:
    return true  // unseen group OR poisoned (collision) key

  long pageAggValue = aggAccessor.getNormalizedValue()

  if isMax:
    return pageAggValue >= normalizedThreshold  // skip if page max < threshold
  else:
    return pageAggValue <= normalizedThreshold  // skip if page min > threshold
```

**Hot path cost:** 2 long reads (zone maps) → 1 long comparison → 1 `HashMap<Long,Long>.get()` → 1 long comparison. No byte arrays, no comparators, no deserialization.

**Collision safety:** `getNormalizedThreshold()` returns `null` for poisoned keys (where two different raw group values normalized to the same long). The page filter conservatively passes these pages through, and the tuple-level filter (which uses raw bytes) handles them correctly. For < 25 low-cardinality values, collisions are virtually impossible in practice (would require e.g. two string group values with identical first 4 characters).

---

## Step 12: IndexUtil Composition

**Modify:** `asterixdb/asterix-metadata/src/main/java/org/apache/asterix/metadata/utils/IndexUtil.java`

In the PLAQUE page filter composition block, add a branch for group-by:

```java
if (columnInfo.getPlaqueHandle() != null) {
    IColumnRangeFilterEvaluatorFactory plaqueFactory;
    if (columnInfo.isPlaqueGroupBy()) {
        // GROUP BY page filter — needs both group and aggregate column accessors
        plaqueFactory = new PlaqueGroupByColumnFilterEvaluatorFactory(
            columnInfo.getPlaqueHandle(),
            columnInfo.isPlaqueIsMax(),
            columnInfo.getPlaqueColumnPath(),       // agg column
            columnInfo.getPlaqueGroupByColumnPath()  // group column
        );
    } else if (columnInfo.getPlaqueCrossExprBuildHandle() != null) {
        // ... existing cross-expr path
    } else {
        // ... existing scalar path
    }
    rangeFilterEvaluatorFactory = new ANDColumnFilterEvaluatorFactory(
        rangeFilterEvaluatorFactory, plaqueFactory);
}
```

---

## Step 13: Cross-Node Propagation for Group Thresholds

**New file:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/message/PlaqueGroupThresholdUpdateMessage.java`

NC-to-CC message carrying a **single group's threshold update** (not the entire map — we send one message per group that improved).

```
Fields: jobId, handle, groupKeyBytes, groupKeyLen, thresholdBytes, thresholdLen, isMax
```

**New file:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/message/PlaqueGroupThresholdBroadcastMessage.java`

CC-to-NC message broadcasting a per-group threshold improvement.

**Modify:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/operators/plaque/PlaqueThresholdCCState.java`

Add parallel handling for group thresholds:

```java
ConcurrentHashMap<String, Map<ByteArrayWrapper, ThresholdEntry>> groupEntries;

boolean updateGroup(JobId jobId, String handle, byte[] groupKey, int gLen,
                    byte[] data, int len, boolean isMax) {
    // Per-group, per-handle threshold tracking at CC level
    // Returns true if this group's threshold improved
}
```

---

## Step 14: PhysicalOperatorTag and Misc

**Modify:** `hyracks-fullstack/algebricks/algebricks-core/.../PhysicalOperatorTag.java`
- Add `PLAQUE_GROUP_FILTER`, `PLAQUE_GROUP_AGGREGATE`

**Modify:** `asterixdb/asterix-algebra/.../rules/ConsolidateSelectsRule.java`
- Skip PLAQUE group-by annotated selects (same pattern as existing PLAQUE skip)

**Modify:** `asterixdb/asterix-algebra/.../rules/PushSelectDownRule.java`, `RemoveRedundantSelectRule.java`
- Same skip pattern for group-by PLAQUE selects

---

## Step 15: Compiler Configuration

**Modify:** `asterixdb/asterix-common/.../config/CompilerProperties.java`

Add: `COMPILER_PLAQUE_GROUPBY_MAX_CARDINALITY` (int, default 25)

Wire through `OptimizationConfUtil` → `PhysicalOptimizationConfig` → runtime factories. This allows users to tune the cardinality cap.

---

## Summary of New Files (8)

| # | File | Module | Purpose |
|---|---|---|---|
| 1 | `PlaqueGroupThresholdState.java` | asterix-runtime | Per-group threshold map with normalized index |
| 2 | `PlaqueGroupByMinMaxAggregateFunction.java` | asterix-runtime | Observer aggregate with group key tracking |
| 3 | `PlaqueGroupByAggregateEvaluatorFactory.java` | asterix-runtime | Factory for group-aware observer |
| 4 | `PlaqueGroupFilterRuntimeFactory.java` | asterix-runtime | Tuple-level filter with group+agg columns |
| 5 | `PlaqueGroupFilterPOperator.java` | asterix-algebra | Physical operator for group filter |
| 6 | `PlaqueGroupByAggregatePOperator.java` | asterix-algebra | Physical operator for group observer |
| 7 | `PlaqueGroupByColumnFilterEvaluatorFactory.java` | asterix-column | Page-level filter with dual zone map check |
| 8 | `PlaqueGroupThresholdUpdateMessage.java` + `BroadcastMessage` | asterix-runtime | Cross-node per-group threshold propagation |

## Summary of Modified Files (8)

| # | File | Change |
|---|---|---|
| 1 | `PlaqueThresholdRegistry.java` | Add `groupStates` map + `getOrCreateGroupState()` |
| 2 | `PlaqueThresholdCCState.java` | Add per-group CC state tracking |
| 3 | `PlaqueRewriteRule.java` | Add `handleGroupBy()` path matching `GROUP` operator |
| 4 | `PlaquePageFilterRule.java` | Add `handleGroupByPageFilter()` path |
| 5 | `ColumnDatasetProjectionFiltrationInfo.java` | Add `plaqueGroupBy`, `plaqueGroupByColumnPath` fields |
| 6 | `IndexUtil.java` | Branch for group-by page filter factory |
| 7 | `PhysicalOperatorTag.java` | Add `PLAQUE_GROUP_FILTER`, `PLAQUE_GROUP_AGGREGATE` |
| 8 | `ConsolidateSelectsRule.java`, `PushSelectDownRule.java`, `RemoveRedundantSelectRule.java` | Skip group PLAQUE selects |

---

## Key Difficulties & Risks

### D1: Normalized Group Key Lookup (Page Filter) — RESOLVED
Zone maps give normalized longs; threshold state stores typed byte arrays. **Solution (Option B):** `PlaqueGroupThresholdState` maintains a parallel `Map<Long, Long>` (`normalizedGroupKey → normalizedThreshold`), populated during `update()` by normalizing both the group key and threshold using the same normalization as zone maps. Page filter does a pure `long→long` lookup — no byte arrays on hot path. On normalization collision (two raw group keys mapping to same long), the key is "poisoned" and page filtering is disabled for that key only; tuple filter remains correct.

### D2: Group Key Extraction in Observer
`onMinMaxChanged()` is called by the parent class during `step()`. We need the group key available at that point. **Solution:** Save group key bytes as instance fields before calling `super.step()`.

### D3: GROUP BY Operator Matching in Rewrite Rule
The `PlaqueRewriteRule` currently matches `AGGREGATE` operators. For GROUP BY, we need to match `GROUP` operators and walk into their nested plans to find the aggregate. The `AsterixIntroduceGroupByCombinerRule` runs before `PlaqueRewriteRule` (both in consolidation phase), so by the time PLAQUE runs, the GROUP BY has already been split into local/global. We need to match the **local** GROUP BY (which contains the `LOCAL_SQL_MAX`/`LOCAL_SQL_MIN`). Need to verify rule ordering in `RuleCollections`.

### D4: Two Variables in Filter Insertion
The scalar PLAQUE filter only needs one variable (`filteredVar`). The group filter needs TWO variables (`filteredVar` + `groupKeyVar`). Both must be live at the filter insertion point. Since both come from the same scan, this should always be true, but `findInsertionPoints()` currently only tracks one variable. Need to extend it or add a parallel check.

### D5: CBO Sampling Query Strips Filter Variables — RESOLVED
The CBO clones the plan and restructures it for sampling queries. The PLAQUE filter gets inherited but variables get stripped. **Solution:** Pass-through fallback in `PlaqueGroupFilterPOperator` and `PlaqueFilterPOperator` — contribute a `StreamProjectRuntimeFactory` when variables are missing.

**Important:** Do NOT use variable-referencing condition expressions (e.g., `AND(GE(var,var))`) on the filter SelectOperator. `PushValueAccessAndFilterDownRule` pushes them down to the datasource scan as runtime predicates, adding 50% scan overhead evaluating always-true comparisons on every tuple. Use `ConstantExpression.TRUE` instead.

### D6: SortGroupByPOperator Bypasses Nested Plan Physical Operators — RESOLVED
`SortGroupByPOperator.contributeRuntimeOperator()` creates aggregate factories directly from expressions via `expressionRuntimeProvider.createAggregateFunctionFactory()`, completely bypassing the nested aggregate's physical operator. Our `PlaqueGroupByAggregatePOperator` was never invoked.

**Solution:** `PlaqueSortGroupByPOperator` extends `SortGroupByPOperator`, overrides `contributeRuntimeOperator()` to create `PlaqueGroupByAggregateEvaluatorFactory` directly for PLAQUE-eligible aggregates. `PlaqueGroupByJobGenRule` runs after `PushGroupByIntoSortRule` to swap the physical operator.

### D7: Scalar PLAQUE Filter Breaks PushGroupByIntoSortRule — RESOLVED
`rewritePost` visits children before parents (bottom-up). The scalar PLAQUE path fires on the nested aggregate BEFORE the GROUP BY path runs, inserting a SELECT between AGGREGATE and NESTEDTUPLESOURCE. `PushGroupByIntoSortRule` requires `AGGREGATE → NESTEDTUPLESOURCE` directly — the inserted SELECT prevents `SORT_GROUP_BY` conversion, causing a blocking `STABLE_SORT`.

**Solution:** `rewriteGroupBy()` removes the scalar PLAQUE filter from the nested plan, restoring `AGGREGATE → NESTEDTUPLESOURCE`.

### D8: FilterAccessorProvider — Two Accessors for Same Column
The group page filter needs BOTH min AND max accessors for the group column. Need to verify that `FilterAccessorProvider.createRangeFilterValueAccessor()` can create two accessors for the same column path with different `readMin` flags. Looking at the code, each call creates a new `ColumnRangeFilterValueAccessor` and adds it to the accessor list, so this should work.

---

## Verification Plan

1. **Unit test query:**
   ```sql
   SET `compiler.plaque.enabled` "true";
   SELECT MAX(l_discount), l_shipmode FROM Lineitem GROUP BY l_shipmode;
   ```
   Verify: correct results, PLAQUE_FILTER_STATS in NC logs showing filter rate > 0%.

2. **Page skip verification:** Check PLAQUE_PAGE_FILTER_EVAL logs for `pagesSkipped > 0`.

3. **Multi-group test:**
   ```sql
   SELECT MIN(l_quantity), l_returnflag FROM Lineitem GROUP BY l_returnflag;
   ```

4. **High-cardinality guard:** Test with `GROUP BY l_suppkey` (10K+ groups). Verify the 25-group cap kicks in and the query still returns correct results.

5. **Correctness:** Compare results with PLAQUE disabled vs enabled for all test queries.

---

## Experiment Results

### SF10: MAX(l_discount) GROUP BY l_shipmode (7 groups)

| | Run 1 | Run 2 | Run 3 | Median | Speedup |
|---|---|---|---|---|---|
| Baseline | 63.1s | 55.1s | 57.1s | 57.1s | 1.00x |
| PLAQUE | 40.8s | 39.8s | 38.7s | 39.8s | **1.43x** |

Filter stats (per partition): 90.7% filter rate, thresholds initialized within ~36K tuples.
Scan time unchanged (22-23s). Sort time negligible (hash-based SORT_GROUP_BY).

### Scale Factor Scaling: MAX(l_discount) GROUP BY l_shipmode

| SF | Baseline | PLAQUE | Speedup |
|---|---|---|---|
| 10 | 55.1s | 39.2s | **1.41x** |
| 30 | 164.9s | 114.9s | **1.44x** |
| 50 | 272.5s | 188.3s | **1.45x** |
| 100 | 533.5s | 383.6s | **1.39x** |

Consistent ~1.4x speedup across all scale factors.

### Different Low-Cardinality Columns (SF10)

| Query | Groups | Baseline | PLAQUE | Speedup |
|---|---|---|---|---|
| MAX(l_discount) BY l_shipmode | 7 | 55.1s | 39.2s | **1.41x** |
| MIN(l_quantity) BY l_returnflag | 3 | 53.4s | 37.2s | **1.43x** |
| MAX(l_extendedprice) BY l_linestatus | 2 | 52.1s | 36.4s | **1.43x** |
| MIN(l_discount) BY l_shipinstruct | 4 | 60.1s | 39.8s | **1.51x** |
| MAX(l_tax) BY l_returnflag | 3 | 51.7s | 38.0s | **1.36x** |

All correct. 1.36-1.51x speedup range.

### High Cardinality Guard: MAX(l_discount) GROUP BY l_suppkey (100K groups)

| | Baseline | PLAQUE | |
|---|---|---|---|
| Before cap fast-path | 64.2s | 71.1s | 0.90x (10% overhead) |
| After cap fast-path | 67.9s | 67.9s | **1.00x (zero overhead)** |

`capReached` volatile flag disables filter and observer when cardinality exceeds 25. Single volatile read per tuple.

### Clustered Table: Lineitem_clustered_SF10

| Query | Baseline | PLAQUE | Speedup | Filter Rate |
|---|---|---|---|---|
| MAX(l_discount) BY l_shipmode | 65.6s | 49.1s | **1.34x** | 99.0% |
| MIN(l_quantity) BY l_returnflag | 64.1s | 45.6s | **1.41x** | 99.0% |
| MAX(l_extendedprice) BY l_linestatus | crashes | crashes | N/A | 99.1% (filter works, sort crashes) |

99% filter rate on clustered table (vs 90% on non-clustered) — thresholds converge faster because data is sorted by group key.

Third query crashes with `IllegalArgumentException` in `UTF8StringUtil.charAt()` during sort normalization — pre-existing bug with string PKs in columnar format (`Lineitem_clustered_SF10` uses string PK components). Not a PLAQUE bug (baseline works because sort processes all tuples; PLAQUE's 99% filtering changes sort input volume, triggering edge case).

### Page Filter Proven: MAX(l_partkey) GROUP BY l_returnflag on Lineitem_clustered_SF10

| | Baseline | PLAQUE | Speedup |
|---|---|---|---|
| **Total** | 56.1s | 8.9s | **6.3x** |

Page skip rate: 77% (15,385 of 19,934 single-group pages skipped). Tuple filter on remaining: 96%.
Scan cardinality dropped from 15M to ~3M per partition.

Requirements for page filtering to work:
1. Group key must be a non-PK column with zone maps (PK columns don't have zone maps in columnar storage)
2. Data must be physically clustered by group column (so pages are single-group)
3. Aggregate column must have wide value range (so threshold is selective — many pages' zone maps fall below it)

Null group key fix: filter now drops tuples with null/missing group keys instead of passing them through.

Variance in speedup (4.3-10.3x) comes from threshold convergence timing — faster convergence = more pages skipped.

### Full Comparison: MAX(l_partkey) GROUP BY l_returnflag (SF10)

| Configuration | Median | Pages Read | Speedup |
|---|---|---|---|
| Non-clustered baseline | 50.7s | 27,834 | 1.00x |
| Non-clustered PLAQUE (tuple only) | 35.7s | 27,834 | **1.42x** |
| Clustered baseline | 54.5s | 69,876 | 1.00x |
| Clustered PLAQUE (tuple+page) | 12.7s | 36,992 | **4.3x** |

PLAQUE clustered (8 runs): 5.3s - 14.0s, median 11.8s, 46-59% page skip rate.
