# PLAQUE: Theta Join Filter

## Status: Research / Design Phase

## What It Does

When a query has a theta join (non-equi join) like `R1.a > R2.b`, tuples from the outer relation that fail to join with ANY tuple in the inner relation reveal a bound on the inner relation's values. PLAQUE learns a range predicate from these failures and pushes it to the outer relation's scan to prune tuples early.

**Motivating example** (from thesis Section 4.3.4, Figure 4.1-4.2):

```sql
SELECT MAX(l_discount)
FROM part, lineitem, orders
WHERE p_retailprice < l_extendedprice
  AND o_orderkey = l_orderkey
  AND o_orderdate < '1993-01-01'
  AND p_brand = ':10'
```

The theta join `l_extendedprice > p_retailprice` is implemented as a nested loop join (NLJ). When a lineitem tuple with `l_extendedprice = 10` arrives at the NLJ and fails to match any part tuple, we know all `p_retailprice` values that reached the join are >= 10. Therefore, any future lineitem tuple needs `l_extendedprice > 10` to have any chance of joining. We learn the predicate `l_extendedprice > 10` and push it near the lineitem scan.

As more tuples fail, the predicate tightens monotonically (e.g., `> 10` becomes `> 12`).


## Thesis Formalization (Section 4.3.4, p.77-79)

### Event 7: Predicate Creation from Theta Join

- **WHEN:** tuple `t in R1` arrives at theta join `R1 join_{a > b} R2`
- **IF:** `t` is the first tuple that *fails to join with any tuples* in `R2`
- **THEN:** create predicate `p: a > $a`, where `$a = t.a`

**Why it's correct:** If `t` fails to join with every tuple `t' in R2` under `a > b`, then for all `t'`, `t'.b >= t.a`. Since the join requires `a > b`, any future tuple needs `a > t.a` to have a chance.

### Event 8: Predicate Refinement from Theta Join

- **WHEN:** tuple `t in R1` arrives at theta join `R1 join_{a > b} R2`
- **IF:** predicate `p = a > $a` exists, `t` fails to join, and `t.a > $a`
- **THEN:** update `p` to `a > $a` where `$a = t.a`

**Monotonic refinement:** The threshold `$a` only increases (for `>`), so the predicate only gets more selective. Never drops a tuple that should pass.

### Full Predicate Table (Table 4.1)

| Theta join `R1 join_{a op b} R2` | Outer=R1, op is `>` or `>=` | Outer=R1, op is `<` or `<=` | Outer=R2, op is `>` or `>=` | Outer=R2, op is `<` or `<=` |
|---|---|---|---|---|
| Learned predicate | `a > max_a` | `a < min_a` | `b < min_b` | `b > max_b` |

Where `max_a` / `min_a` is the max/min of attribute `a` among tuples from R1 that failed the join test.

### When the Predicate is Effective

The predicate helps when:
- The join uses **nested loop join** (block NLJ or index NLJ) -- the outer relation streams tuple-by-tuple
- The values in `R1.a` reaching the join are **not sorted** -- if sorted, the predicate never filters (all remaining tuples satisfy it)
- Sort-merge join does NOT benefit -- tuples are processed in sorted order, so all remaining tuples already satisfy the monotonic predicate

### Sideway Information Passing (SIP) via Theta Join (Section 4.3.6)

When PLAQUE learns a predicate on `R1.a` (e.g., `R1.a > 10`) and there's a theta join `R1.a op R2.b`:
- If `op` is `>`: learn `R2.b <= x` where `x = max(R1.a values in range predicates)`
- If `op` is `<`: learn `R2.b >= x` where `x = min(R1.a values in range predicates)`

This propagates the learned predicate across the join to the other relation.


## AsterixDB Implementation Considerations

### How AsterixDB Handles Theta Joins

AsterixDB uses Nested Loop Join (NLJ) for all non-equi join conditions. The optimizer decides this in:

**`JoinUtils.java`** (`algebricks-rewriter/.../util/JoinUtils.java`, lines 60-132):
- `isHashJoinCondition()` (lines 173-220) checks if the condition is equality-only. Non-equality operators (`>`, `<`, `>=`, `<=`) return `false`.
- When hash join is not possible, `setNestedLoopJoinOp()` (line 126) assigns `NestedLoopJoinPOperator` with BROADCAST partitioning.

**Physical plan for theta join:** The inner relation (e.g., Part) is broadcast to all partitions. The outer relation (e.g., Lineitem) streams through. The join condition is evaluated via `TuplePairEvaluatorFactory`.

### NLJ Runtime: Zero-Match Detection Already Exists

**`NestedLoopJoin.java`** (`hyracks-dataflow-std/.../join/NestedLoopJoin.java`, lines 194-210):

```java
for (int i = 0; i < outerTupleCount; ++i) {
    boolean matchFound = false;
    for (int j = 0; j < innerTupleCount; ++j) {
        int c = tpComparator.compare(accessorOuter, i, accessorInner, j);
        if (c == 0) {  // condition TRUE
            matchFound = true;
            appendToResults(i, j, writer);
        }
    }
    // matchFound == false means this outer tuple failed to join with ANY inner tuple
}
```

The `matchFound` boolean already tracks exactly what we need. The observer hook goes here: when `matchFound == false`, extract the outer tuple's join-column value and call `thresholdState.update()`.

### Can We Reuse the Existing PLAQUE Filter Infrastructure?

**YES.** The theta join filter is the same shape as the MIN/MAX filter:

| Aspect | MIN/MAX PLAQUE (M1-M3) | Theta Join Filter |
|---|---|---|
| **Threshold state** | `PlaqueThresholdState` -- single monotonic threshold | Same `PlaqueThresholdState` -- same structure |
| **Registry** | `PlaqueThresholdRegistry` -- NC-level singleton | Same registry, same key scheme |
| **Filter runtime** | `PlaqueFilterRuntime` -- per-tuple `passes()` check | Same runtime (with strict/non-strict flag) |
| **Page-level filter** | `PlaqueColumnFilterEvaluator` -- zone map check | Same page filter |
| **Physical page skip** | `AbstractColumnTupleReference` -- skip PK decompression | Same mechanism |
| **Cross-node propagation** | Layer 1 + Layer 2 | Same propagation |
| **Observer** | `onMinMaxChanged()` hook in aggregate | **NEW:** hook in NLJ `blockJoin()` when `matchFound == false` |

The only new code needed is the **observer** inside the NLJ runtime.

### One Difference: Strict vs Non-Strict Inequality

| | MIN/MAX filter | Theta join filter (`a > b`) |
|---|---|---|
| **passes() condition** | `value >= threshold` (non-strict) | `value > threshold` (strict) |
| **Why** | A value equal to the running max could be the answer | A value equal to the max-failed value will also fail the join |

The existing `PlaqueThresholdState.passes()` uses `cmp >= 0` for MAX. For theta join `>`, we need `cmp > 0`. This requires a small change: add a `strictComparison` boolean to `PlaqueThresholdState` (or parameterize `passes()`).

For theta join `>=`: same as MIN/MAX -- non-strict.
For theta join `<` or `<=`: the threshold goes the opposite direction (like MIN instead of MAX).

### Observer Placement

The observer hooks into `NestedLoopJoin.blockJoin()`. Two approaches:

**Approach A (hook in NestedLoopJoin):** Add a callback interface (`PlaqueNLJObserver`) that `NestedLoopJoin` calls when `matchFound == false`. The observer is set via the operator descriptor.

**Approach B (wrapper/subclass):** Create `PlaqueNestedLoopJoin extends NestedLoopJoin` that overrides `blockJoin()` to detect zero-matches and update the threshold.

Approach A is cleaner -- minimal change to existing Hyracks code.

### Filter Placement

Same as M2's deep filter: on the **outer relation's input**, below the join, near the scan. The filter checks the outer relation's join column (e.g., `l_extendedprice`) against the threshold.

For columnar storage, the page-level filter and physical page skip apply identically -- the threshold is compared against the page's zone map for the join column.

### Key Files

| File | Role |
|---|---|
| `JoinUtils.java` | Decides NLJ for non-equi conditions |
| `NestedLoopJoin.java` | Core NLJ runtime -- `blockJoin()` with `matchFound` |
| `NestedLoopJoinOperatorDescriptor.java` | Operator descriptor (two activities) |
| `NestedLoopJoinPOperator.java` | Physical operator |
| `PlaqueThresholdState.java` | Reuse -- add `strictComparison` flag |
| `PlaqueFilterRuntime.java` | Reuse as-is (or with strict flag) |
| `PlaqueRewriteRule.java` | Extend to detect theta join conditions |


## Example Queries for Testing

```sql
-- Theta join: l_extendedprice > p_retailprice
SELECT MAX(l_discount)
FROM Lineitem l, Part p, Orders o
WHERE l.l_extendedprice > p.p_retailprice
  AND l.l_orderkey = o.o_orderkey
  AND o.o_orderdate < '1993-01-01'
  AND p.p_brand = 'Brand#10';

-- Simpler: two-table theta join
SELECT COUNT(*)
FROM Lineitem l, Part p
WHERE l.l_extendedprice > p.p_retailprice
  AND p.p_brand = 'Brand#10';
```


## Resolved Questions

1. **How does AsterixDB's optimizer decide NLJ for theta joins?** RESOLVED: `JoinUtils.isHashJoinCondition()` returns false for non-equality ops. Falls through to `setNestedLoopJoinOp()` which assigns NLJ with BROADCAST.

2. **Can we detect zero-match in the NLJ runtime?** RESOLVED: `NestedLoopJoin.blockJoin()` already tracks `matchFound` per outer tuple. We add a hook when `matchFound == false`.

3. **Can we reuse the existing PLAQUE infrastructure?** RESOLVED: YES. Same `PlaqueThresholdState`, `PlaqueThresholdRegistry`, `PlaqueFilterRuntime`, page-level filter, and physical page skip. Only new piece is the NLJ observer. One small change: parameterize strict vs non-strict comparison in `passes()`.

## Block-Processing Analysis (VERIFIED)

**The `matchFound` flag in `blockJoin()` does NOT survive across inner blocks. We cannot use it directly.**

### NLJ Execution Flow

Two activities (same pattern as hash join):
1. **`JoinCacheActivityNode`** (input 1 = inner side): Caches ALL inner frames to a run file via `joiner.cache()`. Fully materialized before outer starts.
2. **`NestedLoopJoinActivityNode`** (input 0 = outer side): Streams outer frames via `joiner.join()`. On `close()`, calls `joiner.completeJoin()`.

### How `multiBlockJoin()` works (NestedLoopJoin.java:155-192)

```
multiBlockJoin():
    open inner run file
    clear outerMatchLOJ (if left-outer)
    while (next inner frame):                    // OUTER LOOP = inner frames
        outerTupleRunningCount = 0
        for each buffered outer frame:           // INNER LOOP = outer frames
            blockJoin(outerTupleRunningCount)     // compare all pairs
            outerTupleRunningCount += frame.tupleCount
    // After ALL inner frames processed:
    if (isLeftOuter): appendMissing()            // emit unmatched outer tuples
```

### Why `matchFound` is per-block-pair only

In `blockJoin()` (line 194-210):
```java
for (int i = 0; i < outerTupleCount; ++i) {
    boolean matchFound = false;         // <-- reset for THIS block pair
    for (int j = 0; j < innerTupleCount; ++j) {
        if (compare == 0) { matchFound = true; appendToResults(); }
    }
    if (isLeftOuter && matchFound) {
        outerMatchLOJ.set(outerTupleStartPos + i);  // persists across blocks
    }
}
```

`matchFound` is a local variable -- it only reflects matches within THIS (outer-frame, inner-frame) pair. An outer tuple might fail in inner-frame-0 but succeed in inner-frame-1.

### How left-outer join solves this (our template)

Left-outer join uses `outerMatchLOJ` (a `BitSet`):
- Cleared once at the start of `multiBlockJoin()`
- Set whenever ANY match is found for an outer tuple (across all inner frames)
- After ALL inner frames are processed, `appendMissing()` iterates unmatched bits

**We need the same pattern for the theta join observer:**
1. Maintain a BitSet `outerMatched` (same as `outerMatchLOJ`)
2. In `blockJoin()`, set the bit when `matchFound == true` for an outer tuple
3. After ALL inner frames are processed, iterate unmatched outer tuples
4. For each unmatched outer tuple, extract its join-column value and call `thresholdState.update()` if it's a new max

### Implication for Observer Design

The observer **cannot fire per-`blockJoin()` call**. It must fire **after the full inner scan** (after the `while (next inner frame)` loop completes), by scanning the BitSet for unmatched outer tuples. This is the same point where `appendMissing()` runs for left-outer joins.

The implementation pattern:
```java
// In multiBlockJoin(), after the inner-frame loop:
if (plaqueEnabled) {
    int outerTupleRunningCount = 0;
    for (int i = 0; i < outerBufferFrameCount; i++) {
        BufferInfo info = outerBufferMngr.getFrame(i, tempInfo);
        accessorOuter.reset(info.getBuffer(), info.getStartOffset(), info.getLength());
        int tupleCount = accessorOuter.getTupleCount();
        for (int t = outerMatched.nextClearBit(outerTupleRunningCount);
             t < outerTupleRunningCount + tupleCount;
             t = outerMatched.nextClearBit(t + 1)) {
            // This outer tuple matched NOTHING across ALL inner frames
            int localIdx = t - outerTupleRunningCount;
            byte[] data = accessorOuter.getFieldData(localIdx, joinColumnIdx);
            int start = accessorOuter.getFieldSlotsLength() + accessorOuter.getFieldStartOffset(localIdx, joinColumnIdx);
            int len = accessorOuter.getFieldLength(localIdx, joinColumnIdx);
            thresholdState.update(data, start, len);
        }
        outerTupleRunningCount += tupleCount;
    }
}
```

Note: `outerBufferMngr` holds multiple outer frames, and `multiBlockJoin()` is called each time the outer buffer fills up. So the observer fires multiple times during query execution -- once per buffer flush. The threshold gets progressively tighter across flushes.

### Memory Overhead

The BitSet for `outerMatched` is the same size as `outerMatchLOJ` -- ~1 bit per outer tuple in the buffer. For inner joins that already have `isLeftOuter = false`, this is new overhead, but tiny (a few KB for millions of tuples at 1 bit each).


## Open Questions

1. **What TPC-H queries have theta joins?** Q10 variant (thesis example). Need to survey TPC-H/TPC-DS for inequality join conditions.
2. **Should the rewrite rule detect theta join conditions in the logical plan?** The join condition `a > b` appears as an expression on the `InnerJoinOperator`. The rule needs to identify which variable comes from which side, and what the comparison operator is.
3. **Performance: NLJ is O(n*m) -- is the filter's benefit dominated by the join cost?** Filtering outer tuples early reduces the quadratic cost proportionally. If the filter drops 90% of outer tuples, the NLJ does 10x less work. This could be significant for large outer relations.
4. **How to handle `isReversed`?** When `isReversed = true`, the roles of outer and inner are swapped in result output. Need to check if this affects which side we learn predicates from.


## Implementation Plan

### Architecture Summary

```
DATASOURCE_SCAN(Lineitem)
  |
  v
PlaqueFilter (reads threshold, strict comparison)     ← REUSE from M1-M3
  |
  v
[BROADCAST_EXCHANGE]                                    (inner side: Part, broadcast)
  |
  v
NestedLoopJoin (theta condition: l_extendedprice > p_retailprice)
  |                 |
  |           on zero-match outer tuple:
  |           → thresholdState.update(outerTuple.joinCol)    ← NEW observer
  v
[rest of plan: aggregate, etc.]
```

### Pre-assignment Strategy (VERIFIED SAFE)

The PLAQUE rewrite rule runs in the **consolidation** phase. Physical operator assignment runs later in `SetAsterixPhysicalOperatorsRule`.

For theta joins, `SetAsterixPhysicalOperatorsRule.visitInnerJoinOperator()` (line 206-213):
1. Calls `AsterixJoinUtils` -- does nothing for theta joins
2. Checks `if (op.getPhysicalOperator() != null)` -- **returns the pre-assigned operator**
3. Only falls to base `JoinUtils` (which would set vanilla NLJ) if nothing is pre-assigned

So pre-assigning `PlaqueThetaJoinPOperator` in the consolidation phase is safe -- it will not be overwritten.


### Component 1: Unmatched-Tuple Callback Interface (hyracks-dataflow-std)

**New file:** `hyracks-fullstack/hyracks/hyracks-dataflow-std/src/main/java/org/apache/hyracks/dataflow/std/join/INLJMismatchObserver.java`

```java
@FunctionalInterface
public interface INLJMismatchObserver extends Serializable {
    /**
     * Called after a complete inner-relation scan for a batch of outer tuples.
     * The observer receives the outer accessor and a BitSet indicating which
     * outer tuples found at least one match.
     *
     * @param outerAccessor  accessor positioned on a frame of outer tuples
     * @param outerStartPos  the running tuple index of the first tuple in this frame
     * @param outerMatched   BitSet -- bit i is set if outer tuple i found a match
     */
    void onBatchComplete(FrameTupleAccessor outerAccessor, int outerStartPos, 
                         BitSet outerMatched) throws HyracksDataException;
}
```

Actually, the observer needs to iterate across multiple outer frames in the buffer. Simpler:

```java
public interface INLJMismatchWriterFactory extends Serializable {
    INLJMismatchWriter createWriter(IHyracksTaskContext ctx) throws HyracksDataException;
}

public interface INLJMismatchWriter {
    void onUnmatchedOuterTuple(FrameTupleAccessor accessor, int tupleIndex) throws HyracksDataException;
    void close() throws HyracksDataException;
}
```

The `NestedLoopJoin` calls `onUnmatchedOuterTuple()` for each outer tuple that matched nothing after the full inner scan. This mirrors the left-outer `appendMissing()` loop but for the PLAQUE observer.

### Component 2: Modify `NestedLoopJoin` (hyracks-dataflow-std)

**File:** `NestedLoopJoin.java`

Changes:
1. Add `outerMatched` BitSet field (allocated if observer != null, same sizing as `outerMatchLOJ`)
2. Add `INLJMismatchWriter mismatchWriter` field
3. Constructor: accept optional `INLJMismatchWriterFactory`, create writer
4. In `blockJoin()`: set `outerMatched.set(outerTupleStartPos + i)` when `matchFound == true` (in addition to existing `outerMatchLOJ` logic for left-outer)
5. In `multiBlockJoin()`: after the inner-frame loop, if mismatchWriter != null, iterate unmatched bits and call `mismatchWriter.onUnmatchedOuterTuple(accessor, localIdx)` for each
6. Clear `outerMatched` at the start of `multiBlockJoin()` (same as `outerMatchLOJ.clear()`)

**Key insight:** For inner joins that ALSO do left-outer, both BitSets run in parallel. For inner joins without left-outer (the common theta join case), only `outerMatched` is allocated. The BitSet cost is negligible (~1 bit per outer tuple in buffer).

### Component 3: Modify `NestedLoopJoinOperatorDescriptor` (hyracks-dataflow-std)

**File:** `NestedLoopJoinOperatorDescriptor.java`

Changes:
1. Add `INLJMismatchWriterFactory mismatchWriterFactory` field (serializable)
2. New constructor overload (or setter) that accepts the factory
3. In `JoinCacheActivityNode.createPushRuntime().open()`: pass the factory to `new NestedLoopJoin(...)` constructor
4. The factory is resolved to a writer in the NLJ constructor using the task context

### Component 4: `PlaqueThetaJoinMismatchWriterFactory` (asterix-runtime)

**New file:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/operators/plaque/PlaqueThetaJoinMismatchWriterFactory.java`

Serializable factory. Carries:
- `String handle` -- PLAQUE threshold handle
- `boolean isMax` -- threshold direction
- `int outerJoinColumnIdx` -- which column in the outer tuple to extract the value from

Creates `PlaqueThetaJoinMismatchWriter` at runtime:
- Resolves `PlaqueThresholdState` from `PlaqueThresholdRegistry`
- On `onUnmatchedOuterTuple()`: extracts the join-column bytes from the accessor and calls `thresholdState.update(data, start, len)`

```java
public class PlaqueThetaJoinMismatchWriterFactory implements INLJMismatchWriterFactory {
    private final String handle;
    private final boolean isMax;
    private final int outerJoinColumnIdx;
    
    // ...
    
    public INLJMismatchWriter createWriter(IHyracksTaskContext ctx) {
        JobId jobId = ctx.getJobletContext().getJobId();
        PlaqueThresholdState state = PlaqueThresholdRegistry.INSTANCE.getOrCreate(jobId, handle, isMax);
        return new PlaqueThetaJoinMismatchWriter(state, outerJoinColumnIdx);
    }
}
```

The writer's `onUnmatchedOuterTuple()`:
```java
void onUnmatchedOuterTuple(FrameTupleAccessor accessor, int tupleIndex) {
    byte[] data = accessor.getBuffer().array();
    int start = accessor.getAbsFieldStartOffset(tupleIndex, outerJoinColumnIdx);
    int len = accessor.getFieldLength(tupleIndex, outerJoinColumnIdx);
    thresholdState.update(data, start, len);
}
```

### Component 5: Strict Comparison in `PlaqueThresholdState` (asterix-runtime)

**File:** `PlaqueThresholdState.java`

Add a `boolean strictComparison` field:
- Set at construction time (constructor parameter)
- Used in `passes()`: 
  - `strict=false` (existing MIN/MAX): `isMax ? cmp >= 0 : cmp <= 0`
  - `strict=true` (theta join): `isMax ? cmp > 0 : cmp < 0`

```java
public PlaqueThresholdState(boolean isMax, boolean strictComparison) {
    this.isMax = isMax;
    this.strictComparison = strictComparison;
    // ...
}

public boolean passes(byte[] data, int start, int len, IBinaryComparator cmp) {
    // ...
    if (strictComparison) {
        return isMax ? c > 0 : c < 0;
    } else {
        return isMax ? c >= 0 : c <= 0;
    }
}
```

`PlaqueThresholdRegistry.getOrCreate()` also needs the `strictComparison` flag. Add it as a parameter.

**Backward compatibility:** Existing M1-M3 callers pass `strictComparison=false` (no change in behavior).

### Component 6: `PlaqueThetaJoinPOperator` (asterix-algebra)

**New file:** `asterixdb/asterix-algebra/src/main/java/org/apache/asterix/algebra/operators/physical/PlaqueThetaJoinPOperator.java`

Extends `NestedLoopJoinPOperator`. Carries:
- `String handle` -- PLAQUE threshold handle
- `boolean isMax` -- threshold direction (determined by the theta join op: `>` / `>=` → isMax=true, `<` / `<=` → isMax=false)
- `LogicalVariable outerJoinVar` -- the outer relation's variable in the theta condition

Overrides `contributeRuntimeOperator()`:
1. Build the condition evaluator + comparator factory (same as base class)
2. Resolve `outerJoinColumnIdx` from `inputSchemas[0].findVariable(outerJoinVar)`
3. Create `PlaqueThetaJoinMismatchWriterFactory(handle, isMax, outerJoinColumnIdx)`
4. Create `NestedLoopJoinOperatorDescriptor` with the mismatch writer factory (new constructor)
5. Wire up edges (same as base class)

Also delegates `computeDeliveredProperties()` and `getRequiredPropertiesForChildren()` to base (no change -- same partitioning: left=RANDOM, right=BROADCAST).

### Component 7: Extend `PlaqueRewriteRule` (asterix-algebra)

**File:** `PlaqueRewriteRule.java`

New match path in `rewritePost()`, in addition to the existing aggregate match:

1. Check: is this an `InnerJoinOperator`?
2. Check: does its condition contain a theta comparison (`>`, `>=`, `<`, `<=`) between two `VariableReferenceExpression`s?
3. Check: is `compiler.plaque.enabled` set?
4. Identify: which variable is from which side (use `VariableUtilities.getLiveVariables()` on each input)
5. Determine `isMax`:
   - `a > b` or `a >= b` with outer=left (R1): filter on `a`, `isMax=true` (threshold = max of failed `a` values)
   - `a < b` or `a <= b` with outer=left (R1): filter on `a`, `isMax=false` (threshold = min of failed `a` values)
6. Determine strict: `>` and `<` → strict=true; `>=` and `<=` → strict=false
7. Generate handle: `"plaque-theta-" + context.newVar()`
8. Insert `PlaqueFilter` on the outer relation's input (same as M2 deep filter placement -- walk down to the scan)
9. Pre-assign `PlaqueThetaJoinPOperator` on the `InnerJoinOperator`
10. Pre-assign `PlaqueFilterPOperator` on the inserted `SelectOperator`

**Variable identification from the theta condition:**
```java
AbstractFunctionCallExpression condExpr = (AbstractFunctionCallExpression) joinOp.getCondition().getValue();
FunctionIdentifier fid = condExpr.getFunctionIdentifier();
// fid is one of: GT, GE, LT, LE (from AlgebricksBuiltinFunctions)
ILogicalExpression arg0 = condExpr.getArguments().get(0).getValue();  // left side of comparison
ILogicalExpression arg1 = condExpr.getArguments().get(1).getValue();  // right side of comparison
// Both must be VariableReferenceExpression for a simple theta join
LogicalVariable leftVar = ((VariableReferenceExpression) arg0).getVariableReference();
LogicalVariable rightVar = ((VariableReferenceExpression) arg1).getVariableReference();
// Determine which is from outer (input 0) vs inner (input 1)
```

### Component 8: Optimizer Rule Guards (already covered)

The inserted `SelectOperator` carries the `"plaque-filter"` annotation (same as M1-M3). Three rules already skip PLAQUE-annotated selects:

| Rule | File | Guard |
|---|---|---|
| `ConsolidateSelectsRule` | `algebricks-rewriter/.../ConsolidateSelectsRule.java:58,93` | Skips merging PLAQUE selects |
| `PushSelectDownRule` | `algebricks-rewriter/.../PushSelectDownRule.java:51` | Skips pushing PLAQUE selects |
| `RemoveRedundantSelectRule` | `asterix-algebra/.../RemoveRedundantSelectRule.java:59` | Skips removing PLAQUE selects |

**No new rule modifications needed.** The theta join filter uses the same annotation, so it's automatically protected.

### Component 9: Config (minimal)

No new config parameters needed. The existing `compiler.plaque.enabled` master switch enables theta join predicate learning alongside MIN/MAX predicate learning. The existing `compiler.plaque.propagation` and `compiler.plaque.propagation.interval` apply to the theta join observer too (same `PlaqueThresholdState` + `PlaqueThresholdRegistry`).

Optional future: `compiler.plaque.theta.join.enabled` to control theta join learning independently.

### Component 10: Propagation (Layer 1 + Layer 2)

**Fully reused.** The theta join observer writes to the same `PlaqueThresholdState` in `PlaqueThresholdRegistry`. Layer 1 (intra-node) sharing works automatically. Layer 2 (cross-node CC-mediated messaging) works if the mismatch writer also calls `maybePropagateThreshold()`.

The mismatch writer's `close()` method can do a final propagation:
```java
void close() {
    if (propagationEnabled && messageBroker != null) {
        // Send final threshold to CC
    }
    PlaqueThresholdRegistry.INSTANCE.removeJob(jobId);
}
```

Or propagation can be triggered periodically in `onUnmatchedOuterTuple()` using a tuple counter (same pattern as `PlaqueLocalSqlMinMaxAggregateFunction.step()`).


### File Summary

| Component | Path | Layer | Notes |
|---|---|---|---|
| `INLJMismatchWriterFactory` | `hyracks-dataflow-std/.../join/` | hyracks | New interface (Serializable) |
| `INLJMismatchWriter` | `hyracks-dataflow-std/.../join/` | hyracks | New interface |
| `NestedLoopJoin` | `hyracks-dataflow-std/.../join/` | hyracks | Modified: add BitSet + observer callback |
| `NestedLoopJoinOperatorDescriptor` | `hyracks-dataflow-std/.../join/` | hyracks | Modified: new constructor with factory |
| `PlaqueThetaJoinMismatchWriterFactory` | `asterix-runtime/.../plaque/` | asterix | New: creates observer, resolves state |
| `PlaqueThresholdState` | `asterix-runtime/.../plaque/` | asterix | Modified: add `strictComparison` flag |
| `PlaqueThresholdRegistry` | `asterix-runtime/.../plaque/` | asterix | Modified: pass `strictComparison` to `getOrCreate()` |
| `PlaqueThetaJoinPOperator` | `asterix-algebra/.../physical/` | asterix | New: extends `NestedLoopJoinPOperator` |
| `PlaqueRewriteRule` | `asterix-algebra/.../rules/` | asterix | Modified: add theta join detection path |
| `PlaqueFilterPOperator` | `asterix-algebra/.../physical/` | asterix | Reused (pass `strictComparison` to factory) |
| `PlaqueFilterRuntimeFactory` | `asterix-runtime/.../plaque/` | asterix | Modified: pass `strictComparison` |

### Implementation Order

1. **PlaqueThresholdState** -- add `strictComparison` (backward-compatible, existing tests pass)
2. **INLJMismatchWriter/Factory** -- define interfaces in hyracks
3. **NestedLoopJoin** -- add BitSet + callback (no functional change when factory is null)
4. **NestedLoopJoinOperatorDescriptor** -- add constructor overload
5. **PlaqueThetaJoinMismatchWriterFactory** -- the observer implementation
6. **PlaqueThetaJoinPOperator** -- the physical operator
7. **PlaqueRewriteRule** -- add theta join detection
8. **PlaqueFilterPOperator/Runtime** -- pass strict flag through
9. **Test with simple query:** `SELECT COUNT(*) FROM Lineitem l, Part p WHERE l.l_extendedprice > p.p_retailprice AND p.p_brand = 'Brand#10'`
10. **Benchmark against baseline**


## Experiment Results

*(To be filled in as we run experiments)*
