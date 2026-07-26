# PLAQUE on AsterixDB — Milestone 1 (Standalone Spec)


## Scope

Single-table `SELECT MAX(col)` or `SELECT MIN(col)` with no GROUP BY, no joins, and a plain column reference as the aggregate input. When `plaque.enabled` is set, the optimizer inserts a `PlaqueFilter` operator between the base-table scan and the local aggregate, and wraps the local aggregate so it feeds threshold updates into a shared `FilterState`.

The filter is a **pure reader**: it consults the FilterState to decide pass/drop, but never updates it. The **local aggregate is the writer**: a thin wrapper around the standard `SqlMinMaxAggregateFunction` pushes the new running max/min into the FilterState whenever the aggregate's internal value strengthens. Both operators share the same FilterState instance via Hyracks's per-task state-object registry.

```
     +-------------------+
     | DATASOURCE_SCAN   |
     +---------+---------+
               |
               v
     +-------------------+     reads only
     | PlaqueFilter      |--------------------+
     | (SELECT annot.)   |                    |
     +---------+---------+                    v
               |                      +--------------+
               v                      | FilterState  |
     +-------------------+   writes   | (per-task)   |
     | local AGGREGATE   |---------->-+--------------+
     | (plaque-wrapped)  |
     +---------+---------+
               |
               v
     RANDOM_MERGE_EXCHANGE --> global AGGREGATE
```


## Reading tasks

Before writing code, read these files to understand the plan structure, the runtime patterns, and the sharing mechanism.

### 1. `IntroduceAggregateCombinerRule` — the rule that creates the local/global split
**Path:** `hyracks-fullstack/algebricks/algebricks-rewriter/src/main/java/org/apache/hyracks/algebricks/rewriter/rules/IntroduceAggregateCombinerRule.java`

For scalar aggregation (no GROUP BY), the optimizer pipeline is:
1. SQL++ rewriter creates an implicit GROUP BY with `groupAll=true`
2. **Phase 3** (condPushDown): `EliminateGroupByEmptyKeyRule` fires — lifts the nested AGGREGATE out of the GROUP BY, producing a standalone `AggregateOperator`
3. **Phase 5** (consolidation): `IntroduceAggregateCombinerRule` fires — splits the standalone AGGREGATE into `agg-local-sql-max` + `agg-global-sql-max` with a `RANDOM_MERGE_EXCHANGE` between them

`IntroduceAggregateCombinerRule` matches on `LogicalOperatorTag.AGGREGATE`, checks `aggOp.isGlobal()` and that execution mode is not LOCAL, then calls `tryToPushAgg()` (inherited from `AbstractIntroduceCombinerRule`) which checks `aggFun.isTwoStep()`, creates step-one (local) and step-two (global) aggregate functions, and inserts the exchange.

Note: `AsterixIntroduceGroupByCombinerRule` (line 269 in consolidation) handles GROUP BY operators — it is NOT the one that creates the local/global split for scalar aggregation. The PLAQUE rule targets the plan AFTER `IntroduceAggregateCombinerRule` has fired.

**Registered at:** `RuleCollections.buildConsolidationRuleCollection()`, line 270:
```java
consolidation.add(new IntroduceAggregateCombinerRule());
```

### 2. Rule pipeline registration
**Path:** `asterixdb/asterix-algebra/src/main/java/org/apache/asterix/optimizer/base/RuleCollections.java`

All optimizer rule lists are here. The consolidation rules (lines 262-285) contain both combiner rules. The PLAQUE rule should be registered in `buildConsolidationRuleCollection()` after line 270 (after `IntroduceAggregateCombinerRule`):

```java
consolidation.add(new IntroduceAggregateCombinerRule());  // existing, line 270
consolidation.add(new PlaqueRewriteRule());               // NEW
```

The key constraint: the PLAQUE rule must see `agg-local-sql-max` / `agg-local-sql-min` already present as a standalone `AggregateOperator`.

### 3. `agg-local-sql-max` runtime
**Path:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/aggregates/std/AbstractMinMaxAggregateFunction.java`

The lifecycle is `init()` -> `step(tuple)` x N -> `finish(result)`. In `init()`, `aggType` is set to `SYSTEM_NULL`. In `step()`, the first non-null value becomes the running max; subsequent values are compared via `ILogicalBinaryComparator`. In `finish()`, if `aggType` is still `SYSTEM_NULL` (no tuples), local phase emits `SYSTEM_NULL`; global phase emits `NULL`.

Key internal fields:
- `outputVal` (private `ArrayBackedValueStorage`) — holds the current running max/min. We change this to `protected` so the plaque wrapper subclass can read it.
- `isMin` (private boolean) — determines comparison direction.
- `compareAndUpdate()` — the method that replaces `outputVal` when a new value wins.

The concrete subclass is `SqlMinMaxAggregateFunction` which adds SQL NULL semantics (local ignores NULL, global treats NULL as type invalidity).

The descriptor `LocalSqlMaxAggregateDescriptor` produces the factory:
```java
public IAggregateEvaluatorFactory createAggregateEvaluatorFactory(final IScalarEvaluatorFactory[] args) {
    return new IAggregateEvaluatorFactory() {
        public IAggregateEvaluator createAggregateEvaluator(final IEvaluatorContext ctx) throws HyracksDataException {
            return new SqlMinMaxAggregateFunction(args, ctx, false, Type.LOCAL, sourceLoc, aggFieldType);
        }
    };
}
```
The plaque wrapper follows the same pattern, substituting `PlaqueLocalSqlMinMaxAggregateFunction`.

### 4. `StreamSelectRuntimeFactory` and `StreamSelectRuntime`
**Path:** `hyracks-fullstack/algebricks/algebricks-runtime/src/main/java/org/apache/hyracks/algebricks/runtime/operators/std/StreamSelectRuntimeFactory.java`

This is the template for understanding the runtime structure. Key elements:

- **Factory** extends `AbstractOneInputOneOutputRuntimeFactory`, implements `Serializable`. Constructor takes config. `createOneOutputPushRuntime(ctx)` instantiates the runtime.
- **Runtime** (inner class) extends `AbstractOneInputOneOutputOneFieldFramePushRuntime`. In `open()`, it lazily creates the evaluator via `initAccessAppendFieldRef(ctx)`. In `nextFrame()`, it iterates tuples and calls `appendTupleToFrame(t)` for passing tuples.
- **Physical operator** `StreamSelectPOperator` returns `isMicroOperator() = true` and contributes the factory via `builder.contributeMicroOperator(op, runtime, recDesc)`.

The core per-tuple loop (cleaned up):
```java
public void nextFrame(ByteBuffer buffer) throws HyracksDataException {
    tAccess.reset(buffer);
    int nTuple = tAccess.getTupleCount();
    for (int t = 0; t < nTuple; t++) {
        tRef.reset(tAccess, t);
        eval.evaluate(tRef, p);
        if (bbi.getBooleanValue(p.getByteArray(), p.getStartOffset(), p.getLength())) {
            appendTupleToFrame(t);
        }
    }
}
```

### 5. Physical operator assignment for SELECT
**Path:** `hyracks-fullstack/algebricks/algebricks-rewriter/src/main/java/org/apache/hyracks/algebricks/rewriter/rules/SetAlgebricksPhysicalOperatorsRule.java`

Line 72 — **critical detail**: this rule SKIPS operators that already have a physical operator:
```java
if (op.getPhysicalOperator() != null) {
    return false;
}
```

Line 281 — SELECT gets `StreamSelectPOperator` by default:
```java
public IPhysicalOperator visitSelectOperator(SelectOperator op, Boolean topLevelOp) {
    return new StreamSelectPOperator();
}
```

This means the PLAQUE rewrite rule can pre-assign `PlaqueFilterPOperator` on the SelectOperator AND `PlaqueAggregatePOperator` on the AggregateOperator, and the later physical-operator-assignment pass will leave both alone. No need to modify any visitor.

### 6. `AggregatePushRuntime` — how aggregate evaluators are created at runtime
**Path:** `hyracks-fullstack/algebricks/algebricks-runtime/src/main/java/org/apache/hyracks/algebricks/runtime/operators/aggreg/AggregatePushRuntime.java`

Aggregate evaluators are created lazily inside the aggregate runtime's `open()`:
```java
public void open() throws HyracksDataException {
    if (first) {
        first = false;
        initAccessAppendRef(ctx.getTaskContext());
        for (int i = 0; i < aggFactories.length; i++) {
            aggEvals[i] = aggFactories[i].createAggregateEvaluator(ctx);
        }
    }
    for (int i = 0; i < aggFactories.length; i++) {
        aggEvals[i].init();
    }
    super.open();
}
```

`ctx` is `IEvaluatorContext`, and `ctx.getTaskContext()` gives `IHyracksTaskContext` — the registry where the shared FilterState lives. The plaque wrapper aggregate's factory resolves the FilterState here via `FilterStateRegistry.getOrCreate(ctx.getTaskContext(), handle, isMax)`.

### 7. Open order in a micro-op pipeline
**Path:** `hyracks-fullstack/algebricks/algebricks-runtime/src/main/java/org/apache/hyracks/algebricks/runtime/operators/base/AbstractOneInputPushRuntime.java`

```java
public void open() throws HyracksDataException {
    isOpen = true;
    writer.open();  // cascades downstream
}
```

Open cascades **source -> filter -> aggregate**. The filter's `open()` runs its own setup BEFORE calling `super.open()`, so the FilterState is already in the registry when the aggregate's `open()` runs and calls `createAggregateEvaluator()`. Both operators use a create-if-absent helper, so order is not strictly required, but the cascade guarantees it in practice.

### 8. Hyracks state-object registry — the sharing API
**Path:** `hyracks-fullstack/hyracks/hyracks-api/src/main/java/org/apache/hyracks/api/job/IOperatorEnvironment.java`

```java
public interface IOperatorEnvironment {
    void setStateObject(IStateObject taskState);
    IStateObject getStateObject(Object id);
}
```

`IHyracksTaskContext extends IOperatorEnvironment`. The `id` is opaque — any `Object` with well-defined `equals/hashCode` works. We use a plan-unique `String` generated by the rewrite rule. This is the same mechanism join operators use to share state between build/probe activities — see `NestedLoopJoinOperatorDescriptor.java:139,167`.


## Design decisions

### Reusing SelectOperator with an annotation (no new logical operator)

Creating a new `LogicalOperatorTag` requires updating every `ILogicalOperatorVisitor` in the codebase (10+ visitor classes). This is pure boilerplate with no research value.

Instead, the PLAQUE rewrite rule inserts a standard `SelectOperator` with a marker annotation:
```java
selectOp.getAnnotations().put("plaque-filter", true);
selectOp.getAnnotations().put("plaque-handle", handle);
```

The rule also pre-assigns the physical operator:
```java
selectOp.setPhysicalOperator(new PlaqueFilterPOperator(filteredVar, isMax, handle));
```

Because `SetAlgebricksPhysicalOperatorsRule` checks `op.getPhysicalOperator() != null` before assigning defaults (line 72), the pre-assigned `PlaqueFilterPOperator` will not be overwritten. The same trick is used for the AggregateOperator:
```java
aggOp.setPhysicalOperator(new PlaqueAggregatePOperator(handle, isMax));
```

### Why the aggregate owns the FilterState (not the filter)

The aggregate already compares and updates its running max/min on every tuple — the exact information the filter needs. Having the aggregate push threshold updates into the FilterState:
- **Avoids duplicate comparison.** The filter would otherwise need its own type-aware compare per tuple.
- **Makes the filter a pure reader.** Its `nextFrame()` body (`passes()` + `appendTupleToFrame`) does not change between M1 and M2. Only who writes to the FilterState changes.
- **M2 becomes a substitution, not a rewrite.** Swap the local-writer for a remote-writer; the filter is untouched.

### Why `IHyracksTaskContext.setStateObject`, not a direct object reference

The two runtime factories (filter and wrapper aggregate) are **serializable**. They ship from CC -> NC as part of the job spec. A shared Java object reference in the factories is lost across serialization — each NC deserializes two independent copies.

The established Hyracks pattern for per-task shared state is `IOperatorEnvironment.setStateObject`. Both factories carry a **serializable handle** (`String`), and at runtime they use the handle to look up / register the same `FilterState` instance in the task context.

Not chosen:
- **Static `Map<handle, FilterState>`** — leaks across queries on long-lived NCs; tricky to clean up; cross-partition bleed. Task-context state is automatically scoped to the task.
- **Extending the aggregate's IAggregateEvaluator contract** — requires changes to `AggregatePOperator` / `AggregateRuntimeFactory`, which are shared Algebricks code.

### Why a hook in `AbstractMinMaxAggregateFunction`, not a composition wrapper

A composition wrapper (`class PlaqueWrapper implements IAggregateEvaluator { IAggregateEvaluator inner; }`) cannot cheaply detect "the running max strengthened on this tuple" after `inner.step()` — the running max lives in `inner`'s private `outputVal` field. The wrapper would need to re-deserialize and re-compare, duplicating the aggregate's work.

The localized alternative: add a no-op protected hook `onMinMaxChanged()` inside `AbstractMinMaxAggregateFunction.compareAndUpdate()` and inside the first-value branch. `PlaqueLocalSqlMinMaxAggregateFunction extends SqlMinMaxAggregateFunction` overrides the hook to call `filterState.update(outputVal)`. Zero extra per-tuple work when the running max does not change; one byte-array copy when it does.

This requires changing `outputVal` from `private` to `protected` — a single-visibility change. No interface changes.

### Module placement

`PlaqueFilterRuntimeFactory`, `PlaqueFilterRuntime`, `FilterState`, `FilterStateRegistry`, and `PlaqueLocalAggregateEvaluatorFactory` live in `asterixdb/asterix-runtime/` (not `hyracks-fullstack/`). Reason: the runtime needs AsterixDB type tags (`ATypeTag.SERIALIZED_NULL_TYPE_TAG`, etc.) and AsterixDB binary comparators (`AGenericAscBinaryComparatorFactory`) for type-aware value comparison. These classes are in the AsterixDB layer (`asterix-om`), which `asterix-runtime` depends on but `hyracks-fullstack` does not.

`PlaqueFilterPOperator` and `PlaqueAggregatePOperator` live in `asterixdb/asterix-algebra/` alongside other AsterixDB-specific physical operators.

`PlaqueLocalSqlMinMaxAggregateFunction` lives in the same package as `SqlMinMaxAggregateFunction` (`asterixdb/asterix-runtime/.../aggregates/std/`) because it subclasses it and accesses package-private members.

### Type-aware comparison

Raw byte comparison does not work for signed numeric types (negative numbers sort incorrectly). AsterixDB provides `AGenericAscBinaryComparatorFactory` which reads the type tag from the first byte of a serialized value and dispatches to the correct type-specific comparator:

**Path:** `asterixdb/asterix-om/src/main/java/org/apache/asterix/dataflow/data/nontagged/comparators/AGenericAscBinaryComparator.java`

```java
public int compare(byte[] b1, int s1, int l1, byte[] b2, int s2, int l2) throws HyracksDataException {
    leftValue.set(b1, s1 + 1, l1 - 1, VALUE_TYPE_MAPPING[b1[s1]]);
    rightValue.set(b2, s2 + 1, l2 - 1, VALUE_TYPE_MAPPING[b2[s2]]);
    return compare(leftType, leftValue, rightType, rightValue);
}
```

The `FilterState` creates this comparator once and reuses it for all comparisons. No need to know the type at plan time.


## Components to build

### 1. `FilterState`
**Path:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/operators/plaque/FilterState.java`

A mutable cell holding the current threshold. Extends `AbstractStateObject` so it can be stored in the per-task state-object registry. Exposes a monotonic update method (called by the wrapper aggregate) and a pass/fail check (called by the filter).

```java
public class FilterState extends AbstractStateObject {
    private final boolean isMax;
    private final IBinaryComparator comparator;
    private byte[] thresholdBytes;
    private int thresholdLength;
    private boolean initialized;

    public FilterState(boolean isMax) {
        super();  // jobId and id set later by FilterStateRegistry
        this.isMax = isMax;
        // Handles all AsterixDB types — reads type tag from first byte
        this.comparator = AGenericAscBinaryComparatorFactory.INSTANCE.createBinaryComparator();
        this.initialized = false;
    }

    /** Called ONLY by the wrapper aggregate when its running min/max strengthens. */
    public void update(byte[] data, int start, int len) throws HyracksDataException {
        if (!initialized) {
            thresholdBytes = new byte[len];
            System.arraycopy(data, start, thresholdBytes, 0, len);
            thresholdLength = len;
            initialized = true;
            return;
        }
        int cmp = comparator.compare(data, start, len, thresholdBytes, 0, thresholdLength);
        if ((isMax && cmp > 0) || (!isMax && cmp < 0)) {
            if (thresholdBytes.length < len) {
                thresholdBytes = new byte[len];
            }
            System.arraycopy(data, start, thresholdBytes, 0, len);
            thresholdLength = len;
        }
    }

    /** Called ONLY by the filter. Never mutates state.
     *  Returns true if value should pass through (>= threshold for MAX, <= for MIN). */
    public boolean passes(byte[] data, int start, int len) throws HyracksDataException {
        if (!initialized) return true;
        int cmp = comparator.compare(data, start, len, thresholdBytes, 0, thresholdLength);
        return isMax ? cmp >= 0 : cmp <= 0;
    }
}
```

Note: `passes()` uses `>=` for MAX and `<=` for MIN, not strict inequality. A value equal to the current threshold must pass — it could be the final answer.

**Threading note:** Within one partition, filter and aggregate run on the same thread (standard micro-op pipeline). The per-tuple sequence is `filter.passes(v)` -> (if pass) `aggregate.step(v)` -> (if strengthening) `filterState.update(v)`. No concurrent access, so no locking or `volatile` needed. Cross-partition sharing is a M2 concern.

### 2. `FilterStateRegistry`
**Path:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/operators/plaque/FilterStateRegistry.java`

Create-if-absent helper that encapsulates the task-context registry lookup. Both the filter and the wrapper aggregate call this with the same handle; whichever runs first creates the instance.

```java
public final class FilterStateRegistry {
    private FilterStateRegistry() {}

    public static FilterState getOrCreate(IHyracksTaskContext ctx, String handle, boolean isMax) {
        FilterState state = (FilterState) ctx.getStateObject(handle);
        if (state == null) {
            state = new FilterState(isMax);
            state.setJobId(ctx.getJobletContext().getJobId());
            state.setId(handle);
            ctx.setStateObject(state);
        }
        return state;
    }
}
```

### 3. `PlaqueFilterRuntime`
**Path:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/operators/plaque/PlaqueFilterRuntimeFactory.java` (inner class)

Per-tuple filter runtime. Extends `AbstractOneInputOneOutputOneFramePushRuntime`. Resolves the shared FilterState in `open()` and only calls `passes()` in `nextFrame()` — never `update()`.

```java
public class PlaqueFilterRuntime extends AbstractOneInputOneOutputOneFramePushRuntime {
    private final int columnIdx;
    private final boolean isMax;
    private final String handle;
    private FilterState filterState;  // resolved in open()
    private IHyracksTaskContext ctx;

    PlaqueFilterRuntime(IHyracksTaskContext ctx, int columnIdx, boolean isMax, String handle) {
        this.ctx = ctx;
        this.columnIdx = columnIdx;
        this.isMax = isMax;
        this.handle = handle;
    }

    @Override
    public void open() throws HyracksDataException {
        initAccessAppendRef(ctx);
        // Create-or-fetch BEFORE super.open() so the FilterState is in the
        // registry when the aggregate's open() cascade arrives next.
        filterState = FilterStateRegistry.getOrCreate(ctx, handle, isMax);
        super.open();
    }

    @Override
    public void nextFrame(ByteBuffer buffer) throws HyracksDataException {
        tAccess.reset(buffer);
        int nTuple = tAccess.getTupleCount();
        for (int t = 0; t < nTuple; t++) {
            tRef.reset(tAccess, t);
            byte[] data = tRef.getFieldData(columnIdx);
            int start = tRef.getFieldStart(columnIdx);
            int len = tRef.getFieldLength(columnIdx);
            byte typeTag = data[start];

            // Pass NULL/MISSING unchanged — the aggregate handles them
            if (typeTag == ATypeTag.SERIALIZED_NULL_TYPE_TAG
                    || typeTag == ATypeTag.SERIALIZED_MISSING_TYPE_TAG
                    || typeTag == ATypeTag.SERIALIZED_SYSTEM_NULL_TYPE_TAG) {
                appendTupleToFrame(t);
                continue;
            }

            // READ-ONLY — no update() call
            if (filterState.passes(data, start, len)) {
                appendTupleToFrame(t);
            }
        }
    }

    @Override
    public void flush() throws HyracksDataException {
        appender.flush(writer);
    }
}
```

Note on `initAccessAppendRef`: this is inherited from `AbstractOneInputOneOutputOneFramePushRuntime` and sets up `tAccess` (FrameTupleAccessor), `tRef` (FrameTupleReference), and the frame appender. It requires `inputRecordDesc` to be set, which happens via `setInputRecordDescriptor()` during pipeline assembly.

### 4. `PlaqueFilterRuntimeFactory`
**Path:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/operators/plaque/PlaqueFilterRuntimeFactory.java`

Serializable factory. Carries `columnIdx`, `isMax`, and the shared `handle`. Constructs a `PlaqueFilterRuntime` per task.

```java
public class PlaqueFilterRuntimeFactory extends AbstractOneInputOneOutputRuntimeFactory {
    private static final long serialVersionUID = 1L;
    private final int columnIdx;
    private final boolean isMax;
    private final String handle;

    public PlaqueFilterRuntimeFactory(int columnIdx, boolean isMax, String handle) {
        super(null); // no projection list
        this.columnIdx = columnIdx;
        this.isMax = isMax;
        this.handle = handle;
    }

    @Override
    public AbstractOneInputOneOutputOneFramePushRuntime createOneOutputPushRuntime(
            final IHyracksTaskContext ctx) {
        return new PlaqueFilterRuntime(ctx, columnIdx, isMax, handle);
    }

    @Override
    public String toString() {
        return "plaque-filter [col=" + columnIdx + ", " + (isMax ? "MAX" : "MIN") + ", h=" + handle + "]";
    }
}
```

### 5. `AbstractMinMaxAggregateFunction` — add a strengthening hook
**Path:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/aggregates/std/AbstractMinMaxAggregateFunction.java`

Two changes:
- `outputVal` field: `private final` -> `protected final` (subclasses need to read current value bytes).
- Add protected no-op hook `onMinMaxChanged()`, called at every point where `outputVal` is (re)assigned.

Concrete diff (line numbers from current file):

At line 49 (field declaration), change visibility:
```java
-    private final ArrayBackedValueStorage outputVal = new ArrayBackedValueStorage();
+    protected final ArrayBackedValueStorage outputVal = new ArrayBackedValueStorage();
```

At line 100 (first-value branch in `step()`), add hook after the assign:
```java
             aggType = typeTag;
             cmp = ComparatorUtil.createLogicalComparator(aggFieldType, aggFieldType, false);
             outputVal.assign(inputVal);
+            onMinMaxChanged();
```

At line 188 (LT branch of `compareAndUpdate()`), add hook:
```java
             case LT:
                 if (isMin) {
                     currentVal.assign(newVal);
+                    onMinMaxChanged();
                 }
                 break;
```

At line 194 (GT branch of `compareAndUpdate()`), add hook:
```java
             case GT:
                 if (!isMin) {
                     // update the current value with the new maximum
                     currentVal.assign(newVal);
+                    onMinMaxChanged();
                 }
                 break;
```

Add protected hook method (near `processNull()`):
```java
/**
 * Called after {@code outputVal} is (re)assigned with a new running min/max.
 * Default: no-op. Overridden by plaque wrappers to push the new threshold
 * into the shared FilterState.
 */
protected void onMinMaxChanged() throws HyracksDataException {
    // no-op in base
}
```

Nothing else in this class changes. Exception safety: the hook is called after the mutation, so if it throws, the aggregate's running min/max is already updated. `compareAndUpdate` already propagates `HyracksDataException`.

### 6. `PlaqueLocalSqlMinMaxAggregateFunction`
**Path:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/aggregates/std/PlaqueLocalSqlMinMaxAggregateFunction.java`

New. Subclasses `SqlMinMaxAggregateFunction` and overrides `onMinMaxChanged()` to push the new threshold into the shared `FilterState`.

```java
public class PlaqueLocalSqlMinMaxAggregateFunction extends SqlMinMaxAggregateFunction {

    private final FilterState filterState;

    PlaqueLocalSqlMinMaxAggregateFunction(IScalarEvaluatorFactory[] args, IEvaluatorContext context,
            boolean isMin, SourceLocation sourceLoc, IAType aggFieldType, FilterState filterState)
            throws HyracksDataException {
        super(args, context, isMin, Type.LOCAL, sourceLoc, aggFieldType);
        this.filterState = filterState;
    }

    @Override
    protected void onMinMaxChanged() throws HyracksDataException {
        // outputVal is now the latest running min/max for this partition.
        filterState.update(
                outputVal.getByteArray(),
                outputVal.getStartOffset(),
                outputVal.getLength());
    }
}
```

**No new function identifier, no `BuiltinFunctions` entry.** The wrapper is selected via a custom physical operator on the existing `AggregateOperator` (below), not via the function-descriptor registry. The SQL++ surface and metadata are untouched.

### 7. `PlaqueAggregatePOperator`
**Path:** `asterixdb/asterix-algebra/src/main/java/org/apache/asterix/algebra/operators/physical/PlaqueAggregatePOperator.java`

New. Extends `AggregatePOperator` and overrides `contributeRuntimeOperator` to produce a `PlaqueLocalSqlMinMaxAggregateFunction` instead of the standard `SqlMinMaxAggregateFunction`.

Inheriting from `AggregatePOperator` means `computeDeliveredProperties` / `getRequiredPropertiesForChildren` work as-is. The only behavior we change is how the `IAggregateEvaluatorFactory` is built.

The rewrite rule pre-assigns this POperator on the existing `AggregateOperator`, and `SetAlgebricksPhysicalOperatorsRule` leaves it alone (line 72 null-check).

```java
public class PlaqueAggregatePOperator extends AggregatePOperator {

    private final String handle;
    private final boolean isMax;

    public PlaqueAggregatePOperator(String handle, boolean isMax) {
        this.handle = handle;
        this.isMax = isMax;
    }

    @Override
    public PhysicalOperatorTag getOperatorTag() {
        // Reuse AGGREGATE — no new tag needed.
        return PhysicalOperatorTag.AGGREGATE;
    }   

    @Override
    public void contributeRuntimeOperator(IHyracksJobBuilder builder, JobGenContext context,
            ILogicalOperator op, IOperatorSchema opSchema, IOperatorSchema[] inputSchemas,
            IOperatorSchema outerPlanSchema) throws AlgebricksException {
        AggregateOperator aggOp = (AggregateOperator) op;
        List<Mutable<ILogicalExpression>> expressions = aggOp.getExpressions();
        IAggregateEvaluatorFactory[] aggFactories = new IAggregateEvaluatorFactory[expressions.size()];
        IExpressionRuntimeProvider exprProvider = context.getExpressionRuntimeProvider();

        for (int i = 0; i < aggFactories.length; i++) {
            AggregateFunctionCallExpression aggFun =
                    (AggregateFunctionCallExpression) expressions.get(i).getValue();
            FunctionIdentifier fid = aggFun.getFunctionIdentifier();
            boolean isMin = fid.equals(BuiltinFunctions.LOCAL_SQL_MIN);

            // Compile the scalar argument (same as the standard path does internally).
            IScalarEvaluatorFactory[] scalarArgs = new IScalarEvaluatorFactory[aggFun.getArguments().size()];
            for (int j = 0; j < scalarArgs.length; j++) {
                scalarArgs[j] = exprProvider.createEvaluatorFactory(
                        aggFun.getArguments().get(j).getValue(),
                        context.getTypeEnvironment(op.getInputs().get(0).getValue()),
                        inputSchemas, context);
            }

            // Resolve aggFieldType (same path as AbstractMinMaxAggregateDescriptor.setImmutableStates).
            IAType aggFieldType = (IAType) context.getTypeEnvironment(op.getInputs().get(0).getValue())
                    .getType(aggFun.getArguments().get(0).getValue());

            aggFactories[i] = new PlaqueLocalAggregateEvaluatorFactory(
                    scalarArgs, isMin, aggFun.getSourceLocation(), aggFieldType, handle, isMax);
        }

        AggregateRuntimeFactory runtime = new AggregateRuntimeFactory(aggFactories);
        runtime.setSourceLocation(aggOp.getSourceLocation());
        RecordDescriptor recDesc = JobGenHelper.mkRecordDescriptor(
                context.getTypeEnvironment(op), opSchema, context);
        builder.contributeMicroOperator(aggOp, runtime, recDesc);
        ILogicalOperator src = aggOp.getInputs().get(0).getValue();
        builder.contributeGraphEdge(src, 0, aggOp, 0);
    }
}
```

### 8. `PlaqueLocalAggregateEvaluatorFactory`
**Path:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/operators/plaque/PlaqueLocalAggregateEvaluatorFactory.java`

New. Serializable `IAggregateEvaluatorFactory` that carries all the constructor args needed to build a `PlaqueLocalSqlMinMaxAggregateFunction` at runtime. At evaluator-creation time, it resolves the shared FilterState from the task context and passes it to the wrapper aggregate.

```java
public class PlaqueLocalAggregateEvaluatorFactory implements IAggregateEvaluatorFactory {
    private static final long serialVersionUID = 1L;

    private final IScalarEvaluatorFactory[] args;
    private final boolean isMin;
    private final SourceLocation sourceLoc;
    private final IAType aggFieldType;
    private final String handle;
    private final boolean isMax;

    public PlaqueLocalAggregateEvaluatorFactory(IScalarEvaluatorFactory[] args, boolean isMin,
            SourceLocation sourceLoc, IAType aggFieldType, String handle, boolean isMax) {
        this.args = args;
        this.isMin = isMin;
        this.sourceLoc = sourceLoc;
        this.aggFieldType = aggFieldType;
        this.handle = handle;
        this.isMax = isMax;
    }

    @Override
    public IAggregateEvaluator createAggregateEvaluator(IEvaluatorContext ctx) throws HyracksDataException {
        FilterState state = FilterStateRegistry.getOrCreate(ctx.getTaskContext(), handle, isMax);
        return new PlaqueLocalSqlMinMaxAggregateFunction(
                args, ctx, isMin, sourceLoc, aggFieldType, state);
    }
}
```

### 9. `PlaqueFilterPOperator`
**Path:** `asterixdb/asterix-algebra/src/main/java/org/apache/asterix/algebra/operators/physical/PlaqueFilterPOperator.java`

Physical operator for the filter. Returns `isMicroOperator() = true`. Contributes the runtime factory as a micro operator.

Add `PLAQUE_FILTER` to `PhysicalOperatorTag` enum:
- **Path:** `hyracks-fullstack/algebricks/algebricks-core/src/main/java/org/apache/hyracks/algebricks/core/algebra/base/PhysicalOperatorTag.java`

```java
public class PlaqueFilterPOperator extends AbstractPhysicalOperator {
    private final LogicalVariable filteredVar;
    private final boolean isMax;
    private final String handle;

    public PlaqueFilterPOperator(LogicalVariable filteredVar, boolean isMax, String handle) {
        this.filteredVar = filteredVar;
        this.isMax = isMax;
        this.handle = handle;
    }

    @Override
    public PhysicalOperatorTag getOperatorTag() {
        return PhysicalOperatorTag.PLAQUE_FILTER;
    }

    @Override
    public boolean isMicroOperator() {
        return true;
    }

    @Override
    public void computeDeliveredProperties(ILogicalOperator op, IOptimizationContext context) {
        // Pass-through: delivers whatever the child delivers
        ILogicalOperator child = op.getInputs().get(0).getValue();
        deliveredProperties = ((AbstractLogicalOperator) child).getDeliveredPhysicalProperties();
    }

    @Override
    public PhysicalRequirements getRequiredPropertiesForChildren(ILogicalOperator op,
            IPhysicalPropertiesVector reqdByParent, IOptimizationContext context) {
        return emptyUnaryRequirements();
    }

    @Override
    public void contributeRuntimeOperator(IHyracksJobBuilder builder, JobGenContext context,
            ILogicalOperator op, IOperatorSchema opSchema, IOperatorSchema[] inputSchemas,
            IOperatorSchema outerPlanSchema) throws AlgebricksException {
        int columnIdx = inputSchemas[0].findVariable(filteredVar);
        PlaqueFilterRuntimeFactory runtime = new PlaqueFilterRuntimeFactory(columnIdx, isMax, handle);
        runtime.setSourceLocation(op.getSourceLocation());
        RecordDescriptor recDesc = JobGenHelper.mkRecordDescriptor(
                context.getTypeEnvironment(op), opSchema, context);
        builder.contributeMicroOperator(op, runtime, recDesc);
        ILogicalOperator src = op.getInputs().get(0).getValue();
        builder.contributeGraphEdge(src, 0, op, 0);
    }

    @Override
    public boolean expensiveThanMaterialization() {
        return false;
    }
}
```

### 10. `PlaqueRewriteRule`
**Path:** `asterixdb/asterix-algebra/src/main/java/org/apache/asterix/optimizer/rules/PlaqueRewriteRule.java`

Optimizer rule. Registered after `IntroduceAggregateCombinerRule` in consolidation. Generates a shared handle, inserts the filter, and pre-assigns physical operators on both the filter (SelectOperator) and the local aggregate (AggregateOperator).

**How it finds the local aggregate:** The rule's `rewritePost()` is called for every operator in the plan. It checks:
1. Is this operator an `AggregateOperator`?
2. Does it have exactly one aggregate expression?
3. Is the function `LOCAL_SQL_MAX` or `LOCAL_SQL_MIN`?
4. Is the argument a plain `VariableReferenceExpression` (not a complex expression)?
5. Is `plaque.enabled` set?

**Placement walk:** From the local aggregate, walk down through its input chain:
- `STREAM_PROJECT`, `ASSIGN`, `ONE_TO_ONE_EXCHANGE` -> continue
- `ASSIGN` that **defines** the filtered variable (the variable appears in the ASSIGN's output variables) -> stop, insert above this ASSIGN
- Anything else (`SELECT`, `JOIN`, `DATASOURCE_SCAN`, etc.) -> stop, insert above

**Insert:** Create a `SelectOperator` with a dummy TRUE condition (the physical operator bypasses it), annotate it, pre-assign `PlaqueFilterPOperator`, and pre-assign `PlaqueAggregatePOperator` on the matched aggregate.

```java
public class PlaqueRewriteRule implements IAlgebraicRewriteRule {

    @Override
    public boolean rewritePre(Mutable<ILogicalOperator> opRef, IOptimizationContext context) {
        return false;
    }

    @Override
    public boolean rewritePost(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
            throws AlgebricksException {
        if (!context.getPhysicalOptimizationConfig().getPlaqueEnabled()) {
            return false;
        }

        AbstractLogicalOperator op = (AbstractLogicalOperator) opRef.getValue();
        if (op.getOperatorTag() != LogicalOperatorTag.AGGREGATE) {
            return false;
        }

        AggregateOperator aggOp = (AggregateOperator) op;
        if (aggOp.getExpressions().size() != 1) {
            return false;
        }

        // Check for agg-local-sql-max or agg-local-sql-min
        ILogicalExpression expr = aggOp.getExpressions().get(0).getValue();
        if (!(expr instanceof AggregateFunctionCallExpression)) {
            return false;
        }
        AggregateFunctionCallExpression aggExpr = (AggregateFunctionCallExpression) expr;
        FunctionIdentifier fid = aggExpr.getFunctionIdentifier();
        boolean isMax;
        if (fid.equals(BuiltinFunctions.LOCAL_SQL_MAX)) {
            isMax = true;
        } else if (fid.equals(BuiltinFunctions.LOCAL_SQL_MIN)) {
            isMax = false;
        } else {
            return false;
        }

        // Check argument is a plain variable reference
        ILogicalExpression arg = aggExpr.getArguments().get(0).getValue();
        if (!(arg instanceof VariableReferenceExpression)) {
            return false;
        }
        LogicalVariable filteredVar = ((VariableReferenceExpression) arg).getVariableReference();

        // Placement walk: find where to insert the filter
        Mutable<ILogicalOperator> insertionPointParentRef = aggOp.getInputs().get(0);
        Mutable<ILogicalOperator> insertionPointRef = null;

        while (true) {
            ILogicalOperator current = insertionPointParentRef.getValue();
            LogicalOperatorTag tag = current.getOperatorTag();

            if (tag == LogicalOperatorTag.ASSIGN) {
                AssignOperator assignOp = (AssignOperator) current;
                if (assignOp.getVariables().contains(filteredVar)) {
                    // Found the ASSIGN that defines the variable — insert above it
                    insertionPointRef = insertionPointParentRef;
                    break;
                }
                // ASSIGN that doesn't define our variable — safe to continue
            } else if (tag == LogicalOperatorTag.PROJECT
                    || tag == LogicalOperatorTag.EXCHANGE) {
                // Safe to walk through
            } else {
                // Dropper, blocker, or unexpected operator — stop
                insertionPointRef = insertionPointParentRef;
                break;
            }

            if (current.getInputs().isEmpty()) {
                break; // reached bottom without finding ASSIGN
            }
            insertionPointParentRef = current.getInputs().get(0);
        }

        if (insertionPointRef == null) {
            return false;
        }

        // Generate plan-unique handle for FilterState sharing
        String handle = "plaque-" + context.newVar();

        // Create a SelectOperator as the carrier for PlaqueFilter
        // The condition is TRUE (unused — PlaqueFilterPOperator ignores it)
        ILogicalExpression trueCond = ConstantExpression.TRUE;
        SelectOperator plaqueSelect = new SelectOperator(new MutableObject<>(trueCond));
        plaqueSelect.setSourceLocation(aggOp.getSourceLocation());
        plaqueSelect.setExecutionMode(
                ((AbstractLogicalOperator) insertionPointRef.getValue()).getExecutionMode());

        // Annotate the filter
        plaqueSelect.getAnnotations().put("plaque-filter", true);
        plaqueSelect.getAnnotations().put("plaque-handle", handle);

        // Pre-assign physical operators on BOTH the filter and the aggregate
        plaqueSelect.setPhysicalOperator(new PlaqueFilterPOperator(filteredVar, isMax, handle));
        aggOp.getAnnotations().put("plaque-aggregate", true);
        aggOp.getAnnotations().put("plaque-handle", handle);
        aggOp.setPhysicalOperator(new PlaqueAggregatePOperator(handle, isMax));

        // Wire into the plan: plaqueSelect takes the current operator as input
        ILogicalOperator below = insertionPointRef.getValue();
        plaqueSelect.getInputs().add(new MutableObject<>(below));
        insertionPointRef.setValue(plaqueSelect);

        context.computeAndSetTypeEnvironmentForOperator(plaqueSelect);
        context.computeAndSetTypeEnvironmentForOperator(aggOp);
        return true;
    }
}
```

### 11. Config flag: `plaque.enabled`

Four files:

**a) `AlgebricksConfig.java`** — add default:
- **Path:** `hyracks-fullstack/algebricks/algebricks-core/src/main/java/org/apache/hyracks/algebricks/core/config/AlgebricksConfig.java`
```java
public static final boolean PLAQUE_ENABLED_DEFAULT = false;
```

**b) `CompilerProperties.java`** — add enum entry and key:
- **Path:** `asterixdb/asterix-common/src/main/java/org/apache/asterix/common/config/CompilerProperties.java`
```java
// In the Option enum (after COMPILER_BLOCKING_MODE):
COMPILER_PLAQUE_ENABLED(BOOLEAN, AlgebricksConfig.PLAQUE_ENABLED_DEFAULT,
        "Enable/disable PLAQUE filter optimization"),

// Static key constant:
public static final String COMPILER_PLAQUE_ENABLED_KEY = Option.COMPILER_PLAQUE_ENABLED.ini();
```

**c) `OptimizationConfUtil.java`** — read and propagate:
- **Path:** `asterixdb/asterix-common/src/main/java/org/apache/asterix/common/config/OptimizationConfUtil.java`
```java
// After line 104 (IsBlockingMode):
boolean plaqueEnabled = getBoolean(querySpecificConfig,
        CompilerProperties.COMPILER_PLAQUE_ENABLED_KEY, false);

// After line 136 (setBlockingMode):
physOptConf.setPlaqueEnabled(plaqueEnabled);
```

**d) `PhysicalOptimizationConfig.java`** — add getter/setter:
- **Path:** `hyracks-fullstack/algebricks/algebricks-core/src/main/java/org/apache/hyracks/algebricks/core/rewriter/base/PhysicalOptimizationConfig.java`
```java
// Constant (after BLOCKINGMODE, line 67):
private static final String PLAQUE_ENABLED = "PLAQUE_ENABLED";

// Getter/setter:
public boolean getPlaqueEnabled() {
    return getBoolean(PLAQUE_ENABLED, AlgebricksConfig.PLAQUE_ENABLED_DEFAULT);
}
public void setPlaqueEnabled(boolean enabled) {
    setBoolean(PLAQUE_ENABLED, enabled);
}
```


## Correctness argument

1. **The true max always reaches the aggregate.** The first non-null tuple always passes the filter (threshold uninitialized -> `passes()` returns `true`). The aggregate processes it and pushes the value into the FilterState via `onMinMaxChanged()`. Any subsequent tuple that beats the threshold passes the filter, reaches the aggregate, and updates the FilterState. Therefore the tuple carrying the true maximum always passes.

2. **Dropped tuples cannot affect the answer.** A tuple is dropped only if its value is strictly less than the current threshold (`passes` returns false). The current threshold is the running max of values *already processed by the aggregate*. A value less than any already-processed value cannot be the final maximum.

3. **No update/read race.** Within a partition, `nextFrame` runs single-threaded. For each tuple the sequence is: `filter.passes(v)` -> (if pass) `aggregate.step(v)` -> (if strengthening) `filterState.update(v)`. There is no window where `passes()` observes a stale write: a write in tuple `i` happens-before the `passes()` read in tuple `i+1` on the same thread.

4. **Read-before-write ordering on the first tuple.** The first tuple arrives after `open()` has cascaded through both operators. At this point `filterState.initialized = false`, so `passes()` returns `true` unconditionally. The aggregate then processes the tuple and the hook sets `initialized = true`. From tuple 2 onward, the steady-state invariant holds.

5. **NULL/MISSING are passed through.** The filter passes them without consulting `filterState`. The aggregate handles them (ignores them per SQL semantics via `processNull()`). `onMinMaxChanged` is never called for NULL/MISSING tuples.

6. **Each partition is independent.** Each task partition has its own task context, hence its own FilterState. M1 has no cross-partition propagation.


## Lifecycle

FilterState instances are held in the task's `IOperatorEnvironment`, which is per-task and owned by the task lifecycle. When the task ends, its environment is released. No explicit cleanup is required.


## Testing

```bash
# Without PLAQUE
curl -s http://localhost:19002/query/service \
  -d '{"statement":"SELECT MAX(l_shipdate) FROM Lineitem_10;"}'

# With PLAQUE
curl -s http://localhost:19002/query/service \
  -d '{"statement":"SET `compiler.plaque.enabled` \"true\"; SELECT MAX(l_shipdate) FROM Lineitem_10;"}'

# Verify plan shows PlaqueFilter
curl -s http://localhost:19002/query/service \
  -d '{"statement":"SET `compiler.plaque.enabled` \"true\"; EXPLAIN SELECT MAX(l_shipdate) FROM Lineitem_10;"}'

# Same for MIN
curl -s http://localhost:19002/query/service \
  -d '{"statement":"SET `compiler.plaque.enabled` \"true\"; SELECT MIN(l_shipdate) FROM Lineitem_10;"}'
```

Success criteria:
- Identical results with flag on vs off (for both MAX and MIN)
- EXPLAIN shows PLAQUE_FILTER operator above the DATASOURCE_SCAN, below the local AGGREGATE, and the AGGREGATE carries a `plaque-aggregate` annotation
- On data where the true max appears early in scan order, wall-clock time is measurably lower with the flag on


## File summary

| Component | Path | Notes |
|---|---|---|
| `FilterState` | `asterixdb/asterix-runtime/.../operators/plaque/FilterState.java` | New. Extends `AbstractStateObject`. Uses `AGenericAscBinaryComparatorFactory` for type-aware comparison. |
| `FilterStateRegistry` | `asterixdb/asterix-runtime/.../operators/plaque/FilterStateRegistry.java` | New. Get-or-create wrapper over `ctx.getStateObject / setStateObject`. |
| `PlaqueFilterRuntime` | `asterixdb/asterix-runtime/.../operators/plaque/PlaqueFilterRuntimeFactory.java` (inner) | New. Modeled on `StreamSelectRuntime`. Reads FilterState only. |
| `PlaqueFilterRuntimeFactory` | `asterixdb/asterix-runtime/.../operators/plaque/PlaqueFilterRuntimeFactory.java` | New. Modeled on `StreamSelectRuntimeFactory`. Carries `handle`. |
| `AbstractMinMaxAggregateFunction` | `asterixdb/asterix-runtime/.../aggregates/std/AbstractMinMaxAggregateFunction.java` | Modified. `outputVal` -> `protected`. Adds `onMinMaxChanged()` hook at 3 sites. |
| `PlaqueLocalSqlMinMaxAggregateFunction` | `asterixdb/asterix-runtime/.../aggregates/std/PlaqueLocalSqlMinMaxAggregateFunction.java` | New. Subclass of `SqlMinMaxAggregateFunction`. Overrides hook to call `filterState.update`. |
| `PlaqueLocalAggregateEvaluatorFactory` | `asterixdb/asterix-runtime/.../operators/plaque/PlaqueLocalAggregateEvaluatorFactory.java` | New. Serializable factory. Carries all constructor args + `handle`. Resolves FilterState at evaluator-creation time. |
| `PlaqueAggregatePOperator` | `asterixdb/asterix-algebra/.../operators/physical/PlaqueAggregatePOperator.java` | New. Extends `AggregatePOperator`. Overrides `contributeRuntimeOperator` to produce the plaque wrapper factory. |
| `PlaqueFilterPOperator` | `asterixdb/asterix-algebra/.../operators/physical/PlaqueFilterPOperator.java` | New. Modeled on `StreamSelectPOperator`. Carries `handle`. |
| `PlaqueRewriteRule` | `asterixdb/asterix-algebra/.../optimizer/rules/PlaqueRewriteRule.java` | New. Inserts filter + pre-assigns POperators on both SelectOperator and AggregateOperator. |
| Config: `AlgebricksConfig` | `hyracks-fullstack/.../config/AlgebricksConfig.java` | Add `PLAQUE_ENABLED_DEFAULT = false` |
| Config: `CompilerProperties` | `asterixdb/asterix-common/.../config/CompilerProperties.java` | Add `COMPILER_PLAQUE_ENABLED` option |
| Config: `OptimizationConfUtil` | `asterixdb/asterix-common/.../config/OptimizationConfUtil.java` | Read flag and propagate to `PhysicalOptimizationConfig` |
| Config: `PhysicalOptimizationConfig` | `hyracks-fullstack/.../rewriter/base/PhysicalOptimizationConfig.java` | Add `getPlaqueEnabled()` / `setPlaqueEnabled()` |
| Rule registration | `asterixdb/asterix-algebra/.../optimizer/base/RuleCollections.java` line 270+ | After `IntroduceAggregateCombinerRule` |
| `PhysicalOperatorTag.PLAQUE_FILTER` | `hyracks-fullstack/.../algebra/base/PhysicalOperatorTag.java` | Add enum entry |


## What this document does NOT cover (later milestones)

- MAX/MIN with GROUP BY
- Joins (hash join probe-side filter)
- Complex aggregate expressions (e.g., `MAX(col * 2)`)
- Cross-operator shared state (`FilterState` shared between publisher and consumer across nodes)
- Cross-node threshold propagation
- Propagation policy (frequency, batching)
