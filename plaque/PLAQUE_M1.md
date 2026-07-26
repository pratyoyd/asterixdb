# PLAQUE on AsterixDB — Milestone 1


## Scope

Single-table `SELECT MAX(col)` or `SELECT MIN(col)` with no GROUP BY, no joins, and a plain column reference as the aggregate input. When `plaque.enabled` is set, the optimizer inserts a self-observing `PlaqueFilter` operator between the base-table scan and the local aggregate.

The filter is self-observing: it reads each tuple's value, updates its own threshold if the value strengthens it, and drops the tuple otherwise. No shared state, no cross-operator coordination, no observer attached to the aggregate.


## Reading tasks

Before writing code, read these files:

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

The lifecycle is `init()` → `step(tuple)` × N → `finish(result)`. In `init()`, `aggType` is set to `SYSTEM_NULL`. In `step()`, the first non-null value becomes the running max; subsequent values are compared via `ILogicalBinaryComparator`. In `finish()`, if `aggType` is still `SYSTEM_NULL` (no tuples), local phase emits `SYSTEM_NULL`; global phase emits `NULL`. You won't modify this in M1, but Milestone 3 will wrap it.

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

This means the PLAQUE rewrite rule can pre-assign `PlaqueFilterPOperator` directly on the SelectOperator, and the later physical-operator-assignment pass will leave it alone. No need to modify any visitor.


## Design decisions

### Reusing SelectOperator with an annotation (no new logical operator)

Creating a new `LogicalOperatorTag` requires updating every `ILogicalOperatorVisitor` in the codebase (10+ visitor classes). This is pure boilerplate with no research value.

Instead, the PLAQUE rewrite rule inserts a standard `SelectOperator` with a marker annotation:
```java
selectOp.getAnnotations().put("plaque-filter", true);
selectOp.getAnnotations().put("plaque-var", filteredVar);
selectOp.getAnnotations().put("plaque-is-max", isMax);
```

The rule also pre-assigns the physical operator:
```java
selectOp.setPhysicalOperator(new PlaqueFilterPOperator(filteredVar, isMax));
```

Because `SetAlgebricksPhysicalOperatorsRule` checks `op.getPhysicalOperator() != null` before assigning defaults (line 72), the pre-assigned `PlaqueFilterPOperator` will not be overwritten. This avoids modifying any visitor or assignment rule.

### Module placement

`PlaqueFilterRuntimeFactory`, `PlaqueFilterRuntime`, and `FilterState` live in `asterixdb/asterix-runtime/` (not `hyracks-fullstack/`). Reason: the runtime needs AsterixDB type tags (`ATypeTag.SERIALIZED_NULL_TYPE_TAG`, etc.) and AsterixDB binary comparators (`AGenericAscBinaryComparatorFactory`) for type-aware value comparison. These classes are in the AsterixDB layer (`asterix-om`), which `asterix-runtime` depends on but `hyracks-fullstack` does not.

`PlaqueFilterPOperator` lives in `asterixdb/asterix-algebra/` alongside other AsterixDB-specific physical operators (e.g., `BTreeSearchPOperator`, `AssignBatchPOperator`).

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

The `FilterState` creates this comparator once and reuses it for all comparisons. No need to know the type at plan time — the comparator reads the type tag from each serialized value at runtime.


## Components to build

### 1. `FilterState`
**Path:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/operators/plaque/FilterState.java`

A mutable cell holding the current threshold. Exposes a monotonic update method and a pass/fail check.

```java
public class FilterState {
    private final boolean isMax;
    private final IBinaryComparator comparator;
    private byte[] thresholdBytes;
    private int thresholdLength;
    private boolean initialized;

    public FilterState(boolean isMax) {
        this.isMax = isMax;
        // Handles all AsterixDB types — reads type tag from first byte
        this.comparator = AGenericAscBinaryComparatorFactory.INSTANCE.createBinaryComparator();
        this.initialized = false;
    }

    /** Update threshold if value strengthens it. */
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

    /** Returns true if value should pass through (>= threshold for MAX, <= for MIN). */
    public boolean passes(byte[] data, int start, int len) throws HyracksDataException {
        if (!initialized) return true;
        int cmp = comparator.compare(data, start, len, thresholdBytes, 0, thresholdLength);
        return isMax ? cmp >= 0 : cmp <= 0;
    }
}
```

Note: `passes()` uses `>=` for MAX and `<=` for MIN, not strict inequality. A value equal to the current threshold must pass — it could be the final answer.

In Milestone 1 the filter owns its own `FilterState` instance. In later milestones the same interface will be shared across operators and nodes — isolating the storage now avoids reshaping later.

### 2. `PlaqueFilterRuntime`
**Path:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/operators/plaque/PlaqueFilterRuntimeFactory.java` (inner class)

Per-tuple filter runtime. Extends `AbstractOneInputOneOutputOneFramePushRuntime`.

```java
public class PlaqueFilterRuntime extends AbstractOneInputOneOutputOneFramePushRuntime {
    private final int columnIdx;
    private final FilterState filterState;

    PlaqueFilterRuntime(int columnIdx, boolean isMax) {
        this.columnIdx = columnIdx;
        this.filterState = new FilterState(isMax);
    }

    @Override
    public void open() throws HyracksDataException {
        initAccessAppendRef(ctx.getTaskContext());
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

            // Update threshold, then check
            filterState.update(data, start, len);
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

### 3. `PlaqueFilterRuntimeFactory`
**Path:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/operators/plaque/PlaqueFilterRuntimeFactory.java`

Serializable factory. Carries `columnIdx` and `isMax`. Constructs a `PlaqueFilterRuntime` per task.

```java
public class PlaqueFilterRuntimeFactory extends AbstractOneInputOneOutputRuntimeFactory {
    private static final long serialVersionUID = 1L;
    private final int columnIdx;
    private final boolean isMax;

    public PlaqueFilterRuntimeFactory(int columnIdx, boolean isMax) {
        super(null); // no projection list
        this.columnIdx = columnIdx;
        this.isMax = isMax;
    }

    @Override
    public AbstractOneInputOneOutputOneFramePushRuntime createOneOutputPushRuntime(
            final IHyracksTaskContext ctx) {
        return new PlaqueFilterRuntime(columnIdx, isMax);
    }

    @Override
    public String toString() {
        return "plaque-filter [col=" + columnIdx + ", " + (isMax ? "MAX" : "MIN") + "]";
    }
}
```

### 4. `PlaqueFilterPOperator`
**Path:** `asterixdb/asterix-algebra/src/main/java/org/apache/asterix/algebra/operators/physical/PlaqueFilterPOperator.java`

Physical operator. Returns `isMicroOperator() = true`. Contributes the runtime factory as a micro operator.

Add `PLAQUE_FILTER` to `PhysicalOperatorTag` enum:
- **Path:** `hyracks-fullstack/algebricks/algebricks-core/src/main/java/org/apache/hyracks/algebricks/core/algebra/base/PhysicalOperatorTag.java`

```java
public class PlaqueFilterPOperator extends AbstractPhysicalOperator {
    private final LogicalVariable filteredVar;
    private final boolean isMax;

    public PlaqueFilterPOperator(LogicalVariable filteredVar, boolean isMax) {
        this.filteredVar = filteredVar;
        this.isMax = isMax;
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
        PlaqueFilterRuntimeFactory runtime = new PlaqueFilterRuntimeFactory(columnIdx, isMax);
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

### 5. `PlaqueRewriteRule`
**Path:** `asterixdb/asterix-algebra/src/main/java/org/apache/asterix/optimizer/rules/PlaqueRewriteRule.java`

Optimizer rule. Registered after `IntroduceAggregateCombinerRule` in consolidation.

**How it finds the local aggregate:** The rule's `rewritePost()` is called for every operator in the plan. It checks:
1. Is this operator an `AggregateOperator`?
2. Does it have exactly one aggregate expression?
3. Is the function `LOCAL_SQL_MAX` or `LOCAL_SQL_MIN`?
4. Is the argument a plain `VariableReferenceExpression` (not a complex expression)?
5. Is `plaque.enabled` set?

**Placement walk:** From the local aggregate, walk down through its input chain:
- `STREAM_PROJECT`, `ASSIGN`, `ONE_TO_ONE_EXCHANGE` → continue
- `ASSIGN` that **defines** the filtered variable (the variable appears in the ASSIGN's output variables) → stop, insert above this ASSIGN
- Anything else (`SELECT`, `JOIN`, `DATASOURCE_SCAN`, etc.) → stop, insert above

**Insert:** Create a `SelectOperator` with a dummy TRUE condition (it won't be used — the physical operator bypasses it), annotate it, and pre-assign `PlaqueFilterPOperator`:

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

        // Placement walk: find the ASSIGN that defines filteredVar
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

        // Create a SelectOperator as the carrier for PlaqueFilter
        // The condition is TRUE (unused — PlaqueFilterPOperator ignores it)
        ILogicalExpression trueCond = ConstantExpression.TRUE;
        SelectOperator plaqueSelect = new SelectOperator(new MutableObject<>(trueCond));
        plaqueSelect.setSourceLocation(aggOp.getSourceLocation());
        plaqueSelect.setExecutionMode(
                ((AbstractLogicalOperator) insertionPointRef.getValue()).getExecutionMode());

        // Annotate
        plaqueSelect.getAnnotations().put("plaque-filter", true);

        // Pre-assign physical operator (SetAlgebricksPhysicalOperatorsRule
        // skips operators that already have one — line 72)
        plaqueSelect.setPhysicalOperator(new PlaqueFilterPOperator(filteredVar, isMax));

        // Wire into the plan: plaqueSelect takes the current operator as input
        ILogicalOperator below = insertionPointRef.getValue();
        plaqueSelect.getInputs().add(new MutableObject<>(below));
        insertionPointRef.setValue(plaqueSelect);

        context.computeAndSetTypeEnvironmentForOperator(plaqueSelect);
        return true;
    }
}
```

### 6. Config flag: `plaque.enabled`

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

The self-observing filter never produces a wrong answer:

1. **The max value always passes.** The first non-null tuple always passes (threshold is uninitialized). Any subsequent tuple that beats the threshold updates it and passes. Therefore the tuple carrying the true maximum always passes through to the aggregate.

2. **Dropped tuples cannot affect the answer.** A tuple is dropped only if its value is strictly less than the current threshold. The current threshold is the maximum value seen so far. A value less than any previously-seen value cannot be the final maximum.

3. **NULL/MISSING are passed through.** The aggregate handles them (ignores them per SQL semantics). The filter does not interfere.

4. **Each partition is independent.** Each partition has its own `FilterState`. Partition A's threshold does not affect Partition B. The global aggregate combines all local results. This is correct because the fundamental invariant holds: each local aggregate's running max is a valid lower bound for its own partition.


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
- EXPLAIN shows PLAQUE_FILTER operator above the DATASOURCE_SCAN, below the local AGGREGATE
- On data where the true max appears early in scan order, wall-clock time is measurably lower with the flag on


## File summary

| Component | Path | Template / Notes |
|---|---|---|
| `FilterState` | `asterixdb/asterix-runtime/.../operators/plaque/FilterState.java` | New. Uses `AGenericAscBinaryComparatorFactory` for type-aware comparison |
| `PlaqueFilterRuntime` | `asterixdb/asterix-runtime/.../operators/plaque/PlaqueFilterRuntimeFactory.java` (inner class) | Modeled on `StreamSelectRuntime` |
| `PlaqueFilterRuntimeFactory` | `asterixdb/asterix-runtime/.../operators/plaque/PlaqueFilterRuntimeFactory.java` | Modeled on `StreamSelectRuntimeFactory` |
| `PlaqueFilterPOperator` | `asterixdb/asterix-algebra/.../operators/physical/PlaqueFilterPOperator.java` | Modeled on `StreamSelectPOperator`. Lives in AsterixDB layer for `asterix-runtime` access |
| `PlaqueRewriteRule` | `asterixdb/asterix-algebra/.../optimizer/rules/PlaqueRewriteRule.java` | New. Inserts annotated SelectOperator + pre-assigns POperator |
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
- Cross-operator shared state (`FilterState` shared between publisher and consumer)
- Cross-node threshold propagation
- Propagation policy (frequency, batching)
