# PLAQUE Top-K Cross-Table Expression Threshold

## Target Query

```sql
SELECT l.l_extendedprice - ps.ps_supplycost AS val
FROM Lineitem l, Partsupp ps
WHERE l.l_partkey = ps.ps_partkey AND l.l_suppkey = ps.ps_suppkey
ORDER BY val DESC
LIMIT K
```

## Mechanism

This extends the `MAX(expr)` cross-expression threshold to Top-K queries. The K-th value in the sort heap replaces the running MAX as the threshold `t`. All other components (build-side observer, exchange filter, page filter) are reused unchanged.

| Component | MAX query | Top-K query |
|-----------|-----------|-------------|
| Threshold source | Running MAX from aggregate | K-th value from sort heap root |
| Threshold `t` | Monotonically increases | Monotonically increases |
| Filter condition | `l_extendedprice > t + c_min` | `l_extendedprice > t + c_min` |
| Build-side bound | `c_min = MIN(ps_supplycost)` per NC | `c_min = MIN(ps_supplycost)` per NC |

## How the Top-K Threshold Works

AsterixDB's `STABLE_SORT [topK: K]` uses `TupleSorterHeapSort` with a `MaxHeap` of size K. The comparator is inverted for DESC sorting: the heap root (`peekMax()`) is the WORST entry in the top-K set — the K-th largest value.

In `TupleSorterHeapSort.insertTuple()`:
1. Compute normalized key for the incoming tuple
2. If heap has K entries: compare incoming against root (`peekMax`)
3. If incoming is worse than or equal to root (`compareTuple >= 0`): discard
4. If incoming is better (`compareTuple < 0`): evict root, insert incoming via `replaceMax()`

After `replaceMax()`, the new root is the new K-th value. This is the threshold `t`.

The K-th value only tightens over time (increases for DESC ordering). A stale `t` under-prunes, never mis-prunes.

## Plan Structure

```
distribute-result
  LIMIT K (global)
    SORT_MERGE_EXCHANGE [val DESC]
      LIMIT K (local per NC)
        STABLE_SORT [topK: K] [val DESC]   ← PLAQUE threshold observer
          ASSIGN [val] <- [subtract($$54, $$55)]
            JOIN (equi)
              HASH_PARTITION_EXCHANGE (Partsupp, build, input 0)
              HASH_PARTITION_EXCHANGE (Lineitem, probe, input 1)  ← PLAQUE exchange filter
                data-scan (Lineitem)  ← PLAQUE page filter
```

In the Top-K plan: build = input 0 (Partsupp), probe = input 1 (Lineitem). This is opposite from the MAX aggregate plan where probe = input 0.

## Soundness

1. **Monotonicity:** The K-th value only increases (for DESC). A stale `t` under-prunes. Correct.
2. **Build-side bound:** `c_min` is exact and immutable after build. Same as MAX.
3. **Filter safety:** If `l_extendedprice <= t + c_min[p]`, then for every partner on NC p with `ps_supplycost >= c_min[p]`: `l_extendedprice - ps_supplycost <= l_extendedprice - c_min[p] <= t`. Cannot enter top-K. Safe to drop.
4. **Temporal guarantee:** Build completes before probe (blocking edge). `c_min` is available when probe tuples arrive.

## Implementation

### New Files

| File | Layer | Role |
|------|-------|------|
| `PlaqueTopKSortPOperator.java` | asterix-algebra | Extends `StableSortPOperator`, creates `PlaqueTopKSorterOperatorDescriptor` |
| `PlaqueTopKSorterOperatorDescriptor.java` | hyracks-dataflow-std | Extends `TopKSorterOperatorDescriptor`, passes observer factory to run generator |
| `ITopKThresholdObserver.java` | hyracks-dataflow-std | `void onKthValueChanged(FrameTupleAccessor, int tupleIndex, int sortKeyFieldIndex)` |
| `ITopKThresholdObserverFactory.java` | hyracks-dataflow-std | `ITopKThresholdObserver createObserver(IHyracksTaskContext)` (Serializable) |
| `PlaqueTopKThresholdObserverFactory.java` | asterix-runtime | Extracts sort key bytes from heap root, calls `PlaqueThresholdState.update()` |

### Modified Files

| File | Change |
|------|--------|
| `PlaqueRewriteRule.java` | Add `rewriteTopKCrossTableExpression()` for ORDER operator detection |
| `TupleSorterHeapSort.java` | Add `ITopKThresholdObserver` field; call observer after `replaceMax()` |
| `HybridTopKSortRunGenerator.java` | Accept `ITopKThresholdObserverFactory`, pass to `TupleSorterHeapSort` |
| `PlaqueCrossExprJobGenRule.java` | Handle ORDER operator: replace sort, join, and probe exchange |
| `PlaquePageFilterRule.java` | Handle ORDER operator for page filter attachment |

### Reused Unchanged

`PlaqueBuildSideMinState`, `PlaqueBuildSideObserverFactory`, `PlaqueCrossExprHashJoinPOperator`, `PlaqueCrossExprExchangePOperator`, `PlaqueCrossExprExchangeFilterFactory`, `PlaqueCrossExprColumnFilterEvaluatorFactory`, `PlaqueThresholdState`, `PlaqueThresholdRegistry`.

## Threshold Observer Integration Point

In `TupleSorterHeapSort.insertTuple()`, after line 203 (`heap.replaceMax(newEntry)`):

```java
if (observer != null) {
    heap.peekMax(maxEntry);  // new K-th value is new root
    bufferAccessor1.reset(maxEntry.tuplePointer);
    observer.onKthValueChanged(bufferAccessor1, 0, sortFields[0]);
}
```

The observer reads the sort key field from the new heap root. `sortFields[0]` is the index of the sort key column (e.g., the `val = subtract(...)` field). The observer extracts the serialized bytes and calls `PlaqueThresholdState.update()`.

## Sort Key Field Index

The sort key is `val = l_suppkey - ps_availqty`. In the tuple schema at the sort operator, this is the field at `sortFields[0]`. The descriptor knows this index. The observer extracts the serialized bytes at that field offset and calls `PlaqueThresholdState.update()`.

## Experimental Results

### Setup
- TPC-H SF10/30/50/100, Lineitem + Partsupp columnar storage
- 4 NCs on single machine, buffer cache 16GB, join memory 2GB
- Query: `SELECT l.l_suppkey - ps.ps_availqty AS val ... ORDER BY val DESC LIMIT 10`

### Scalability Results (warm cache, 4 NCs, TPC-H l_suppkey - ps_availqty ORDER BY DESC LIMIT 10)

| SF | Baseline Time | Exchange-Only Time | Exchange-Only Speedup | Exchange+Page Time | Exchange+Page Speedup | Tuples Joined (Baseline) | Tuples Joined (PLAQUE) | Join Reduction | Pages Read (Baseline) | Pages Read (PLAQUE) | Pages Skipped | Objects Read (Baseline) | Objects Read (PLAQUE) |
|----|--------------|--------------------|-----------------------|--------------------|----------------------|--------------------------|------------------------|----------------|----------------------|---------------------|---------------|-------------------------|-----------------------|
| 10 | 59.6s | 42.1s | **1.42x** | 27.9s | **2.07x** | 59,986,052 | 135,465 | 442x | 121,154 | 95,634 | 21% | 67,986,052 | 47,362,424 |
| 30 | 215.7s | 130.2s | **1.66x** | 54.7s | **4.04x** | 179,998,372 | 117,558 | 1,531x | 363,468 | 224,310 | 38% | 203,998,372 | 91,493,710 |
| 50 | 383.6s | 213.1s | **1.80x** | 73.9s | **4.98x** | 300,005,811 | 124,660 | 2,406x | 605,770 | 334,410 | 45% | 340,005,811 | 120,616,581 |
| 100 | 879.4s | 423.7s | **2.08x** | 110.9s | **7.77x** | 600,037,902 | 134,238 | 4,469x | 1,211,559 | 585,705 | 52% | 680,037,902 | 174,041,557 |

All results verified correct against baseline.

**Exchange filter only** achieves 1.42-2.08x speedup by dropping 99.7% of probe tuples before the join. The scan remains the bottleneck (1.1x improvement).

**Exchange + page filter** achieves 2.07-7.77x speedup. The page filter skips entire columnar pages where `pageMax(l_suppkey) <= threshold + c_min`, reducing both page reads and processed objects. Page skip rate increases with SF (21% → 52%) because larger data has more pages that fall entirely below the tightening threshold.

Tuples joined stays constant (~130K) regardless of SF — only tuples with `l_suppkey` near the maximum can enter the top-K set.

Speedup scales superlinearly: 2.07x (SF10) → 7.77x (SF100). Baseline cost grows superlinearly (join spilling at larger SF), PLAQUE cost grows sublinearly (page filter skips proportionally more, join work constant).

### Implementation Trip-Ups

**1. PushLimitIntoOrderByRule destroys annotations.**
`PushLimitIntoOrderByRule` creates a NEW `OrderOperator` with `topK`, replacing the original. Annotations set during consolidation are lost. Fix: `PlaqueCrossExprJobGenRule.handleTopKSort()` doesn't rely on ORDER annotations. Instead, it walks down from ORDER to find a join with `plaque-crossexpr-done` and a `plaque-topk-` prefixed threshold handle.

**2. topK not set during consolidation.**
`PlaqueRewriteRule` runs during consolidation, before `PushLimitIntoOrderByRule` merges LIMIT into ORDER (setting `topK`). Fix: detect the LIMIT → ORDER pattern directly by walking down from LIMIT, instead of checking `orderOp.getTopK()`.

**3. sortColumns null after physical operator replacement.**
`sortColumns` is set by `computeDeliveredProperties()` during optimizer property computation. When `PlaqueCrossExprJobGenRule` replaces the physical operator with `PlaqueTopKSortPOperator`, properties aren't recomputed. Fix: call `computeLocalProperties(orderOp)` on the new operator after construction.

**4. Probe/build variable swap.**
The optimizer may place join inputs in either order. The probe/build assignment follows AsterixDB's convention: plan input 0 → probe (descriptor `addSourceEdge(0, probeActivity, 0)`), plan input 1 → build. The rewrite rule must use `VariableUtilities.getLiveVariables()` on each input to determine which variable is on which side.
