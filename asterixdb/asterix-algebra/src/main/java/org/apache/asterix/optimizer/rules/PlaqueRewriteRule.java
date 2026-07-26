package org.apache.asterix.optimizer.rules;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.asterix.algebra.operators.physical.PlaqueAggregatePOperator;
import org.apache.asterix.algebra.operators.physical.PlaqueCrossExprExchangePOperator;
import org.apache.asterix.algebra.operators.physical.PlaqueCrossExprHashJoinPOperator;
import org.apache.asterix.algebra.operators.physical.PlaqueFilterPOperator;
import org.apache.asterix.algebra.operators.physical.PlaqueThetaJoinPOperator;
import org.apache.asterix.runtime.operators.plaque.PlaqueCrossExprExchangeFilterFactory;
import org.apache.asterix.om.functions.BuiltinFunctions;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalExpression;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.base.IOptimizationContext;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalExpressionTag;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalOperatorTag;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalVariable;
import org.apache.hyracks.algebricks.core.algebra.expressions.AbstractFunctionCallExpression;
import org.apache.hyracks.algebricks.core.algebra.expressions.AggregateFunctionCallExpression;
import org.apache.hyracks.algebricks.core.algebra.expressions.ConstantExpression;
import org.apache.hyracks.algebricks.core.algebra.expressions.VariableReferenceExpression;
import org.apache.hyracks.algebricks.core.algebra.functions.AlgebricksBuiltinFunctions;
import org.apache.hyracks.algebricks.core.algebra.functions.FunctionIdentifier;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractBinaryJoinOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractBinaryJoinOperator.JoinKind;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AggregateOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AssignOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.OrderOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.SelectOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.visitors.VariableUtilities;
import org.apache.hyracks.algebricks.core.algebra.operators.physical.AbstractJoinPOperator.JoinPartitioningType;
import org.apache.hyracks.algebricks.core.rewriter.base.IAlgebraicRewriteRule;
import org.apache.hyracks.algebricks.core.rewriter.base.PhysicalOptimizationConfig;

public class PlaqueRewriteRule implements IAlgebraicRewriteRule {

    private static final String PLAQUE_FILTER = "plaque-filter";
    private static final String PLAQUE_AGGREGATE = "plaque-aggregate";
    private static final String PLAQUE_HANDLE = "plaque-handle";
    private static final String PLAQUE_CROSS_EXPR = "plaque-cross-expr";

    @Override
    public boolean rewritePre(Mutable<ILogicalOperator> opRef, IOptimizationContext context) {
        return false;
    }

    @Override
    public boolean rewritePost(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
            throws AlgebricksException {

        PhysicalOptimizationConfig physConf = context.getPhysicalOptimizationConfig();
        if (!physConf.getPlaqueEnabled()) {
            return false;
        }

        AbstractLogicalOperator op = (AbstractLogicalOperator) opRef.getValue();

        // Path 2: Theta join predicate learning
        if (op.getOperatorTag() == LogicalOperatorTag.INNERJOIN) {
            return rewriteThetaJoin(opRef, context, physConf);
        }

        // Path 3: Top-K cross-table expression (LIMIT above ORDER)
        if (op.getOperatorTag() == LogicalOperatorTag.LIMIT) {
            System.out.println("PLAQUE_TOPK: visiting LIMIT operator");
            return rewriteTopKCrossTableExpression(opRef, context, physConf);
        }
        if (op.getOperatorTag() == LogicalOperatorTag.ORDER) {
            System.out.println("PLAQUE_TOPK: visiting ORDER operator");
            return rewriteTopKCrossTableExpression(opRef, context, physConf);
        }

        if (op.getOperatorTag() != LogicalOperatorTag.AGGREGATE) {
            return false;
        }

        AggregateOperator aggOp = (AggregateOperator) op;

        if (Boolean.TRUE.equals(aggOp.getAnnotations().get(PLAQUE_AGGREGATE))) {
            return false;
        }

        if (aggOp.getExpressions().size() != 1) {
            return false;
        }

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

        if (aggExpr.getArguments().size() != 1) {
            return false;
        }

        ILogicalExpression arg = aggExpr.getArguments().get(0).getValue();
        if (!(arg instanceof VariableReferenceExpression)) {
            return false;
        }
        LogicalVariable filteredVar = ((VariableReferenceExpression) arg).getVariableReference();

        // Try cross-table expression path first (e.g., MAX(a - b) across a join)
        if (rewriteCrossTableExpression(aggOp, filteredVar, isMax, physConf, context)) {
            return true;
        }

        boolean pushThroughJoin = physConf.getPlaquePushThroughJoin();

        // Find insertion points — deep (below join) and optionally shallow (at join's probe input)
        Mutable<ILogicalOperator>[] insertionPoints = findInsertionPoints(
                aggOp.getInputs().get(0), filteredVar, pushThroughJoin);

        Mutable<ILogicalOperator> deepRef = insertionPoints[0];
        Mutable<ILogicalOperator> shallowRef = insertionPoints[1]; // non-null if crossed a join

        if (deepRef == null) {
            return false;
        }

        String handle = "plaque-" + context.newVar();
        boolean propagationEnabled = physConf.getPlaquePropagation();
        int propagationInterval = physConf.getPlaquePropagationInterval();

        // Annotate the aggregate
        aggOp.getAnnotations().put(PLAQUE_AGGREGATE, true);
        aggOp.getAnnotations().put(PLAQUE_HANDLE, handle);
        aggOp.getAnnotations().put("plaque-isMax", isMax);
        aggOp.getAnnotations().put("plaque-filteredVar", filteredVar);
        aggOp.setPhysicalOperator(new PlaqueAggregatePOperator(
                handle, isMax, propagationEnabled, propagationInterval));

        // Deep filter (below exchange — opportunistic, works when pipeline overlaps)
        SelectOperator deepFilter = createPlaqueFilter(aggOp, deepRef, filteredVar, isMax, handle, false);
        ILogicalOperator belowDeep = deepRef.getValue();
        deepFilter.getInputs().add(new MutableObject<>(belowDeep));
        deepRef.setValue(deepFilter);
        context.computeAndSetTypeEnvironmentForOperator(deepFilter);

        // Shallow filter removed — redundant when deep filter is present.
        // The deep filter already prunes tuples before the hash-partition exchange.

        context.computeAndSetTypeEnvironmentForOperator(aggOp);
        return true;
    }

    private SelectOperator createPlaqueFilter(AggregateOperator aggOp,
            Mutable<ILogicalOperator> insertionRef, LogicalVariable filteredVar,
            boolean isMax, String handle, boolean requiresPartitioning) {
        SelectOperator filter = new SelectOperator(new MutableObject<>(ConstantExpression.TRUE));
        filter.setSourceLocation(aggOp.getSourceLocation());
        filter.setExecutionMode(
                ((AbstractLogicalOperator) insertionRef.getValue()).getExecutionMode());
        filter.getAnnotations().put(PLAQUE_FILTER, true);
        filter.getAnnotations().put(PLAQUE_HANDLE, handle);
        filter.setPhysicalOperator(new PlaqueFilterPOperator(filteredVar, isMax, handle, requiresPartitioning));
        return filter;
    }

    /**
     * Returns a 2-element array: [deepRef, shallowRef].
     * deepRef = insertion point below join (near scan). Always non-null on success.
     * shallowRef = join's probe input ref (for above-exchange filter). Non-null only if a join was crossed.
     */
    @SuppressWarnings("unchecked")
    private Mutable<ILogicalOperator>[] findInsertionPoints(
            Mutable<ILogicalOperator> startRef, LogicalVariable filteredVar,
            boolean pushThroughJoin) throws AlgebricksException {

        Mutable<ILogicalOperator> currentRef = startRef;
        Mutable<ILogicalOperator> joinProbeRef = null;

        while (true) {
            ILogicalOperator current = currentRef.getValue();
            LogicalOperatorTag tag = current.getOperatorTag();

            if (tag == LogicalOperatorTag.ASSIGN) {
                AssignOperator assignOp = (AssignOperator) current;
                if (assignOp.getVariables().contains(filteredVar)) {
                    return new Mutable[] { currentRef, joinProbeRef };
                }
            } else if (tag == LogicalOperatorTag.PROJECT
                    || tag == LogicalOperatorTag.EXCHANGE) {
                // Safe to walk through
            } else if ((tag == LogicalOperatorTag.INNERJOIN
                    || tag == LogicalOperatorTag.LEFTOUTERJOIN) && pushThroughJoin) {
                AbstractLogicalOperator joinOp = (AbstractLogicalOperator) current;
                if (!variableProducedBelow(joinOp.getInputs().get(0).getValue(), filteredVar)) {
                    // Variable from build side — weak filter above join
                    return new Mutable[] { currentRef, null };
                }
                // Save the join's probe input for the shallow (above-exchange) filter
                joinProbeRef = joinOp.getInputs().get(0);
                currentRef = joinProbeRef;
                continue;
            } else if (tag == LogicalOperatorTag.DATASOURCESCAN
                    || tag == LogicalOperatorTag.SELECT) {
                return new Mutable[] { currentRef, joinProbeRef };
            } else {
                // Unknown operator — stop and insert above it
                return new Mutable[] { currentRef, joinProbeRef };
            }

            if (current.getInputs().isEmpty()) {
                break;
            }
            currentRef = current.getInputs().get(0);
        }

        return new Mutable[] { null, null };
    }

    private boolean variableProducedBelow(ILogicalOperator op, LogicalVariable var)
            throws AlgebricksException {
        Set<LogicalVariable> liveVars = new HashSet<>();
        VariableUtilities.getLiveVariables(op, liveVars);
        return liveVars.contains(var);
    }

    // ---- Theta Join Predicate Learning ----

    private static final String PLAQUE_THETA_JOIN = "plaque-theta-join";

    private boolean rewriteThetaJoin(Mutable<ILogicalOperator> opRef, IOptimizationContext context,
            PhysicalOptimizationConfig physConf) throws AlgebricksException {
        AbstractBinaryJoinOperator joinOp = (AbstractBinaryJoinOperator) opRef.getValue();

        // Don't rewrite twice
        if (Boolean.TRUE.equals(joinOp.getAnnotations().get(PLAQUE_THETA_JOIN))) {
            return false;
        }

        // Check condition is a simple theta comparison: a op b
        ILogicalExpression condExpr = joinOp.getCondition().getValue();
        if (condExpr.getExpressionTag() != LogicalExpressionTag.FUNCTION_CALL) {
            return false;
        }
        AbstractFunctionCallExpression funcExpr = (AbstractFunctionCallExpression) condExpr;
        FunctionIdentifier fid = funcExpr.getFunctionIdentifier();

        // Determine comparison kind and direction
        boolean isMax;
        boolean strictComparison;
        if (fid.equals(AlgebricksBuiltinFunctions.GT)) {
            // a > b: outer needs a > max_failed, so filter is isMax=true, strict
            isMax = true;
            strictComparison = true;
        } else if (fid.equals(AlgebricksBuiltinFunctions.GE)) {
            // a >= b: outer needs a >= max_failed, isMax=true, non-strict
            isMax = true;
            strictComparison = false;
        } else if (fid.equals(AlgebricksBuiltinFunctions.LT)) {
            // a < b: outer needs a < min_failed, isMax=false, strict
            isMax = false;
            strictComparison = true;
        } else if (fid.equals(AlgebricksBuiltinFunctions.LE)) {
            // a <= b: outer needs a <= min_failed, isMax=false, non-strict
            isMax = false;
            strictComparison = false;
        } else {
            return false;
        }

        // Both arguments must be variable references
        List<Mutable<ILogicalExpression>> args = funcExpr.getArguments();
        if (args.size() != 2) {
            return false;
        }
        ILogicalExpression arg0 = args.get(0).getValue();
        ILogicalExpression arg1 = args.get(1).getValue();
        if (!(arg0 instanceof VariableReferenceExpression) || !(arg1 instanceof VariableReferenceExpression)) {
            return false;
        }
        LogicalVariable leftVar = ((VariableReferenceExpression) arg0).getVariableReference();
        LogicalVariable rightVar = ((VariableReferenceExpression) arg1).getVariableReference();

        // Determine which variable is from the outer (input 0 = probe/left) side
        // NLJ: input 0 = outer/probe, input 1 = inner/build (broadcast)
        ILogicalOperator leftInput = joinOp.getInputs().get(0).getValue();
        ILogicalOperator rightInput = joinOp.getInputs().get(1).getValue();

        Set<LogicalVariable> leftLiveVars = new HashSet<>();
        VariableUtilities.getLiveVariables(leftInput, leftLiveVars);
        Set<LogicalVariable> rightLiveVars = new HashSet<>();
        VariableUtilities.getLiveVariables(rightInput, rightLiveVars);

        LogicalVariable outerJoinVar;
        if (leftLiveVars.contains(leftVar) && rightLiveVars.contains(rightVar)) {
            // a op b where a is from outer (left), b is from inner (right)
            outerJoinVar = leftVar;
        } else if (leftLiveVars.contains(rightVar) && rightLiveVars.contains(leftVar)) {
            // a op b where b is from outer (left), a is from inner (right)
            // Flip the comparison direction: if inner.a > outer.b, the filter is on outer.b
            outerJoinVar = rightVar;
            isMax = !isMax;
            // strictness stays the same
        } else {
            return false;
        }

        // Find the insertion point for the filter on the outer side
        Mutable<ILogicalOperator> filterInsertionRef = findFilterInsertionOnOuterSide(
                joinOp.getInputs().get(0), outerJoinVar);
        if (filterInsertionRef == null) {
            return false;
        }

        String handle = "plaque-theta-" + context.newVar();

        // Create and insert the PLAQUE filter on the outer side
        SelectOperator plaqueFilter = new SelectOperator(new MutableObject<>(ConstantExpression.TRUE));
        plaqueFilter.setSourceLocation(joinOp.getSourceLocation());
        plaqueFilter.setExecutionMode(
                ((AbstractLogicalOperator) filterInsertionRef.getValue()).getExecutionMode());
        plaqueFilter.getAnnotations().put(PLAQUE_FILTER, true);
        plaqueFilter.getAnnotations().put(PLAQUE_HANDLE, handle);
        plaqueFilter.setPhysicalOperator(
                new PlaqueFilterPOperator(outerJoinVar, isMax, handle, false, strictComparison));
        ILogicalOperator below = filterInsertionRef.getValue();
        plaqueFilter.getInputs().add(new MutableObject<>(below));
        filterInsertionRef.setValue(plaqueFilter);
        context.computeAndSetTypeEnvironmentForOperator(plaqueFilter);

        // Pre-assign the PLAQUE theta join physical operator
        joinOp.getAnnotations().put(PLAQUE_THETA_JOIN, true);
        joinOp.getAnnotations().put(PLAQUE_HANDLE, handle);
        joinOp.getAnnotations().put("plaque-isMax", isMax);
        joinOp.getAnnotations().put("plaque-filteredVar", outerJoinVar);
        int eagerBatchSize = context.getPhysicalOptimizationConfig().getPlaqueEagerBatchSize();
        joinOp.setPhysicalOperator(new PlaqueThetaJoinPOperator(
                joinOp.getJoinKind(), JoinPartitioningType.BROADCAST,
                handle, isMax, strictComparison, outerJoinVar, eagerBatchSize));

        context.computeAndSetTypeEnvironmentForOperator(joinOp);
        return true;
    }

    /**
     * Walk down the outer (probe) side to find where to insert the PLAQUE filter.
     * Walks through ASSIGN, PROJECT, EXCHANGE, stops at DATASOURCE_SCAN, SELECT, or unknown.
     */
    private Mutable<ILogicalOperator> findFilterInsertionOnOuterSide(
            Mutable<ILogicalOperator> startRef, LogicalVariable filteredVar) throws AlgebricksException {
        Mutable<ILogicalOperator> currentRef = startRef;
        while (true) {
            ILogicalOperator current = currentRef.getValue();
            LogicalOperatorTag tag = current.getOperatorTag();

            if (tag == LogicalOperatorTag.ASSIGN) {
                AssignOperator assignOp = (AssignOperator) current;
                if (assignOp.getVariables().contains(filteredVar)) {
                    return currentRef;
                }
            } else if (tag == LogicalOperatorTag.PROJECT || tag == LogicalOperatorTag.EXCHANGE) {
                // Safe to walk through
            } else if (tag == LogicalOperatorTag.DATASOURCESCAN || tag == LogicalOperatorTag.SELECT) {
                return currentRef;
            } else {
                return currentRef;
            }

            if (current.getInputs().isEmpty()) {
                break;
            }
            currentRef = current.getInputs().get(0);
        }
        return null;
    }

    // ==================== Cross-Table Expression Path ====================

    /**
     * Detects MAX(subtract(probeVar, buildVar)) or similar cross-table expressions
     * above a hash join. If found, injects build-side observer on the join and
     * exchange filter on the probe-side exchange.
     */
    private boolean rewriteCrossTableExpression(AggregateOperator aggOp, LogicalVariable filteredVar,
            boolean isMax, PhysicalOptimizationConfig physConf, IOptimizationContext context)
            throws AlgebricksException {

        // Walk down from aggregate to find the ASSIGN defining filteredVar
        ILogicalOperator current = aggOp.getInputs().get(0).getValue();
        AssignOperator exprAssign = null;
        while (current != null) {
            System.out.println("PLAQUE_CROSSEXPR_TRACE: walking " + current.getOperatorTag());
            if (current.getOperatorTag() == LogicalOperatorTag.ASSIGN) {
                AssignOperator assign = (AssignOperator) current;
                if (assign.getVariables().contains(filteredVar)) {
                    exprAssign = assign;
                    break;
                }
            }
            if (current.getInputs().isEmpty()) break;
            current = current.getInputs().get(0).getValue();
        }
        if (exprAssign == null) {
            System.out.println("PLAQUE_CROSSEXPR: no ASSIGN found for " + filteredVar);
            return false;
        }

        // Check if the expression is a binary arithmetic (subtract, add, multiply, divide)
        int idx = exprAssign.getVariables().indexOf(filteredVar);
        ILogicalExpression expr = exprAssign.getExpressions().get(idx).getValue();
        if (!(expr instanceof AbstractFunctionCallExpression)) return false;

        AbstractFunctionCallExpression funcExpr = (AbstractFunctionCallExpression) expr;
        FunctionIdentifier fid = funcExpr.getFunctionIdentifier();

        // Determine CombineOp and BuildBound based on expression and aggregate type
        PlaqueCrossExprExchangeFilterFactory.CombineOp combineOp;
        boolean trackMin;
        if (fid.equals(BuiltinFunctions.NUMERIC_SUBTRACT)) {
            // MAX(a - b): filter a > t + min(b), so CombineOp=ADD, trackMin=true
            combineOp = PlaqueCrossExprExchangeFilterFactory.CombineOp.ADD;
            trackMin = true;
        } else if (fid.equals(BuiltinFunctions.NUMERIC_ADD)) {
            // MAX(a + b): filter a > t - min(b), so CombineOp=SUBTRACT, trackMin=true
            combineOp = PlaqueCrossExprExchangeFilterFactory.CombineOp.SUBTRACT;
            trackMin = true;
        } else {
            System.out.println("PLAQUE_CROSSEXPR: unsupported function " + fid);
            return false; // Only subtract and add for now
        }
        System.out.println("PLAQUE_CROSSEXPR: detected " + fid + " with combineOp=" + combineOp);
        // For MIN aggregate, flip everything
        if (!isMax) {
            trackMin = !trackMin;
        }
        PlaqueCrossExprExchangeFilterFactory.FilterDirection filterDirection =
                isMax ? PlaqueCrossExprExchangeFilterFactory.FilterDirection.GREATER
                        : PlaqueCrossExprExchangeFilterFactory.FilterDirection.LESS;

        if (funcExpr.getArguments().size() != 2) return false;
        ILogicalExpression arg0 = funcExpr.getArguments().get(0).getValue();
        ILogicalExpression arg1 = funcExpr.getArguments().get(1).getValue();
        if (!(arg0 instanceof VariableReferenceExpression) || !(arg1 instanceof VariableReferenceExpression)) {
            System.out.println("PLAQUE_CROSSEXPR: subtract args not var refs: "
                    + arg0.getClass().getSimpleName() + ", " + arg1.getClass().getSimpleName());
            return false;
        }
        LogicalVariable var0 = ((VariableReferenceExpression) arg0).getVariableReference();
        LogicalVariable var1 = ((VariableReferenceExpression) arg1).getVariableReference();
        System.out.println("PLAQUE_CROSSEXPR: var0=" + var0 + " var1=" + var1);

        // Walk down to find the hash join
        current = exprAssign.getInputs().get(0).getValue();
        AbstractBinaryJoinOperator joinOp = null;
        while (current != null) {
            if (current.getOperatorTag() == LogicalOperatorTag.INNERJOIN
                    || current.getOperatorTag() == LogicalOperatorTag.LEFTOUTERJOIN) {
                joinOp = (AbstractBinaryJoinOperator) current;
                break;
            }
            if (current.getInputs().isEmpty()) break;
            current = current.getInputs().get(0).getValue();
        }
        if (joinOp == null) {
            System.out.println("PLAQUE_CROSSEXPR: no join found below ASSIGN");
            return false;
        }
        System.out.println("PLAQUE_CROSSEXPR: found join kind=" + joinOp.getJoinKind());

        // Must be an inner join with an equi-join condition (will become hash join)
        if (joinOp.getJoinKind() != JoinKind.INNER) return false;

        // Determine which variable comes from which side of the join
        // In AsterixDB's plan: input 0 (first child) = PROBE side (Lineitem)
        //                      input 1 (second child) = BUILD side (Partsupp)
        // Descriptor maps: addSourceEdge(1, build, 0) — plan's second child feeds the build activity
        //                   addSourceEdge(0, probe, 0) — plan's first child feeds the probe activity
        Set<LogicalVariable> probeInputVars = new HashSet<>(); // input 0 = probe
        Set<LogicalVariable> buildInputVars = new HashSet<>(); // input 1 = build
        VariableUtilities.getLiveVariables(joinOp.getInputs().get(0).getValue(), probeInputVars);
        VariableUtilities.getLiveVariables(joinOp.getInputs().get(1).getValue(), buildInputVars);

        LogicalVariable probeVar, buildVar;
        if (probeInputVars.contains(var0) && buildInputVars.contains(var1)) {
            // var0 is from probe, var1 is from build
            probeVar = var0;
            buildVar = var1;
        } else if (buildInputVars.contains(var0) && probeInputVars.contains(var1)) {
            // var0 is from build, var1 is from probe → swap
            probeVar = var1;
            buildVar = var0;
            // For subtract: expression is (build - probe) not (probe - build)
            // MAX(b - a): filter a < max(b) - t → different combineOp
            if (fid.equals(BuiltinFunctions.NUMERIC_SUBTRACT)) {
                combineOp = PlaqueCrossExprExchangeFilterFactory.CombineOp.SUBTRACT;
                trackMin = false; // track MAX of build side
                filterDirection = isMax ? PlaqueCrossExprExchangeFilterFactory.FilterDirection.LESS
                        : PlaqueCrossExprExchangeFilterFactory.FilterDirection.GREATER;
            }
        } else {
            return false; // Both from same side — not a cross-table expression
        }

        // Generate handles
        String thresholdHandle = "plaque-crossexpr-t-" + context.newVar();
        String buildSideHandle = "plaque-crossexpr-b-" + context.newVar();

        boolean propagationEnabled = physConf.getPlaquePropagation();
        int propagationInterval = physConf.getPlaquePropagationInterval();

        // 1. Annotate the aggregate with PLAQUE (for threshold t learning)
        aggOp.getAnnotations().put(PLAQUE_AGGREGATE, true);
        aggOp.getAnnotations().put(PLAQUE_HANDLE, thresholdHandle);
        aggOp.getAnnotations().put(PLAQUE_CROSS_EXPR, true);
        aggOp.getAnnotations().put("plaque-isMax", isMax);
        aggOp.getAnnotations().put("plaque-filteredVar", filteredVar);
        // Cross-expr page filter needs the probe variable and build-side info
        aggOp.getAnnotations().put("plaque-crossexpr-probe-var", probeVar);
        aggOp.getAnnotations().put("plaque-crossexpr-build-handle", buildSideHandle);
        aggOp.getAnnotations().put("plaque-crossexpr-combine-op", combineOp);
        aggOp.getAnnotations().put("plaque-crossexpr-filter-direction", filterDirection);
        aggOp.setPhysicalOperator(new PlaqueAggregatePOperator(
                thresholdHandle, isMax, propagationEnabled, propagationInterval));

        // 2. Annotate the join with ALL cross-expr metadata.
        // Exchanges don't exist yet (EnforceStructuralPropertiesRule runs later).
        // PlaqueCrossExprJobGenRule (in prepareForJobGenRewrites) will read these annotations
        // and apply them to the join's physical operator and the probe-side exchange.
        int numPartitions = 4; // number of NCs
        joinOp.getAnnotations().put(PLAQUE_CROSS_EXPR, true);
        joinOp.getAnnotations().put("plaque-crossexpr-build-handle", buildSideHandle);
        joinOp.getAnnotations().put("plaque-crossexpr-build-var", buildVar);
        joinOp.getAnnotations().put("plaque-crossexpr-track-min", trackMin);
        joinOp.getAnnotations().put("plaque-crossexpr-num-partitions", numPartitions);
        joinOp.getAnnotations().put("plaque-crossexpr-threshold-handle", thresholdHandle);
        joinOp.getAnnotations().put("plaque-crossexpr-probe-var", probeVar);
        joinOp.getAnnotations().put("plaque-crossexpr-combine-op", combineOp);
        joinOp.getAnnotations().put("plaque-crossexpr-filter-direction", filterDirection);

        System.out.println("PLAQUE_CROSSEXPR: annotations set on join and aggregate. "
                + "thresholdHandle=" + thresholdHandle + " buildSideHandle=" + buildSideHandle
                + " probeVar=" + probeVar + " buildVar=" + buildVar);

        context.computeAndSetTypeEnvironmentForOperator(aggOp);
        return true;
    }

    /**
     * Detect ORDER BY subtract(probeVar, buildVar) DESC LIMIT K above a hash join.
     * Annotates the ORDER and JOIN operators for PlaqueCrossExprJobGenRule.
     */
    private boolean rewriteTopKCrossTableExpression(Mutable<ILogicalOperator> opRef, IOptimizationContext context,
            PhysicalOptimizationConfig physConf) throws AlgebricksException {
        AbstractLogicalOperator limitOrOrderOp = (AbstractLogicalOperator) opRef.getValue();

        // Detect LIMIT above ORDER pattern (PushLimitIntoOrderByRule hasn't run yet)
        OrderOperator orderOp;
        if (limitOrOrderOp.getOperatorTag() == LogicalOperatorTag.LIMIT) {
            // Walk down from LIMIT skipping PROJECT/ASSIGN/EXCHANGE until we find ORDER
            ILogicalOperator below = limitOrOrderOp.getInputs().get(0).getValue();
            int depth = 0;
            while (below != null && depth < 5) {
                System.out.println("PLAQUE_TOPK_DETECT: LIMIT walk depth=" + depth + " tag=" + below.getOperatorTag());
                if (below.getOperatorTag() == LogicalOperatorTag.ORDER) {
                    break;
                }
                if (below.getInputs().isEmpty()) {
                    below = null;
                    break;
                }
                below = below.getInputs().get(0).getValue();
                depth++;
            }
            if (below == null || below.getOperatorTag() != LogicalOperatorTag.ORDER) {
                return false;
            }
            orderOp = (OrderOperator) below;
        } else if (limitOrOrderOp.getOperatorTag() == LogicalOperatorTag.ORDER) {
            orderOp = (OrderOperator) limitOrOrderOp;
            if (orderOp.getTopK() <= 0) {
                return false;
            }
        } else {
            return false;
        }

        if (Boolean.TRUE.equals(orderOp.getAnnotations().get("plaque-topk-sort"))) {
            return false;
        }

        // Must be a single sort key
        if (orderOp.getOrderExpressions().size() != 1) {
            return false;
        }

        // Must be DESC (for Top-K largest)
        OrderOperator.IOrder order = orderOp.getOrderExpressions().get(0).first;
        if (order.getKind() != OrderOperator.IOrder.OrderKind.DESC) {
            return false;
        }

        // Extract sort key variable
        ILogicalExpression sortKeyExpr = orderOp.getOrderExpressions().get(0).second.getValue();
        if (sortKeyExpr.getExpressionTag() != LogicalExpressionTag.VARIABLE) {
            return false;
        }
        LogicalVariable sortKeyVar = ((VariableReferenceExpression) sortKeyExpr).getVariableReference();

        // Walk down to find the ASSIGN defining sortKeyVar
        ILogicalOperator current = orderOp.getInputs().get(0).getValue();
        AssignOperator exprAssign = null;
        while (current != null) {
            if (current.getOperatorTag() == LogicalOperatorTag.ASSIGN) {
                AssignOperator assign = (AssignOperator) current;
                if (assign.getVariables().contains(sortKeyVar)) {
                    exprAssign = assign;
                    break;
                }
            }
            if (current.getInputs().isEmpty()) break;
            current = current.getInputs().get(0).getValue();
        }
        if (exprAssign == null) {
            return false;
        }

        // Check if the expression is binary arithmetic
        int idx = exprAssign.getVariables().indexOf(sortKeyVar);
        ILogicalExpression expr = exprAssign.getExpressions().get(idx).getValue();
        if (!(expr instanceof AbstractFunctionCallExpression)) return false;

        AbstractFunctionCallExpression funcExpr = (AbstractFunctionCallExpression) expr;
        FunctionIdentifier fid = funcExpr.getFunctionIdentifier();

        PlaqueCrossExprExchangeFilterFactory.CombineOp combineOp;
        boolean trackMin;
        if (fid.equals(BuiltinFunctions.NUMERIC_SUBTRACT)) {
            combineOp = PlaqueCrossExprExchangeFilterFactory.CombineOp.ADD;
            trackMin = true;
        } else if (fid.equals(BuiltinFunctions.NUMERIC_ADD)) {
            combineOp = PlaqueCrossExprExchangeFilterFactory.CombineOp.SUBTRACT;
            trackMin = true;
        } else {
            return false;
        }

        // DESC Top-K = MAX direction
        boolean isMax = true;
        PlaqueCrossExprExchangeFilterFactory.FilterDirection filterDirection =
                PlaqueCrossExprExchangeFilterFactory.FilterDirection.GREATER;

        if (funcExpr.getArguments().size() != 2) return false;
        ILogicalExpression arg0 = funcExpr.getArguments().get(0).getValue();
        ILogicalExpression arg1 = funcExpr.getArguments().get(1).getValue();
        if (!(arg0 instanceof VariableReferenceExpression) || !(arg1 instanceof VariableReferenceExpression)) {
            return false;
        }
        LogicalVariable var0 = ((VariableReferenceExpression) arg0).getVariableReference();
        LogicalVariable var1 = ((VariableReferenceExpression) arg1).getVariableReference();

        // Walk down to find the hash join
        current = exprAssign.getInputs().get(0).getValue();
        AbstractBinaryJoinOperator joinOp = null;
        while (current != null) {
            if (current.getOperatorTag() == LogicalOperatorTag.INNERJOIN
                    || current.getOperatorTag() == LogicalOperatorTag.LEFTOUTERJOIN) {
                joinOp = (AbstractBinaryJoinOperator) current;
                break;
            }
            if (current.getInputs().isEmpty()) break;
            current = current.getInputs().get(0).getValue();
        }
        if (joinOp == null) {
            return false;
        }

        // Determine which variable comes from which side of the join
        Set<LogicalVariable> input0Vars = new HashSet<>();
        Set<LogicalVariable> input1Vars = new HashSet<>();
        VariableUtilities.getLiveVariables(joinOp.getInputs().get(0).getValue(), input0Vars);
        VariableUtilities.getLiveVariables(joinOp.getInputs().get(1).getValue(), input1Vars);

        // Verify each variable comes from a different join input
        boolean var0InInput0 = input0Vars.contains(var0);
        boolean var1InInput0 = input0Vars.contains(var1);
        if (var0InInput0 == var1InInput0) {
            // Both from the same input — not a cross-table expression
            return false;
        }

        // Determine probe/build based on hash join semantics:
        // In AsterixDB's hash join, input 0 maps to probe, input 1 maps to build.
        // The BUILD side is observed (MIN/MAX), the PROBE side is filtered.
        LogicalVariable probeVar, buildVar;
        if (var0InInput0) {
            // var0 is in input 0 (probe), var1 is in input 1 (build)
            probeVar = var0;
            buildVar = var1;
        } else {
            // var0 is in input 1 (build), var1 is in input 0 (probe)
            probeVar = var1;
            buildVar = var0;
            // Expression is subtract(buildVar, probeVar) → need to flip filter direction
            if (fid.equals(BuiltinFunctions.NUMERIC_SUBTRACT)) {
                combineOp = PlaqueCrossExprExchangeFilterFactory.CombineOp.SUBTRACT;
                trackMin = false;
                filterDirection = PlaqueCrossExprExchangeFilterFactory.FilterDirection.LESS;
            }
        }

        // Generate handles
        String thresholdHandle = "plaque-topk-t-" + context.newVar();
        String buildSideHandle = "plaque-topk-b-" + context.newVar();

        // Annotate the ORDER operator
        orderOp.getAnnotations().put("plaque-topk-sort", true);
        orderOp.getAnnotations().put(PLAQUE_HANDLE, thresholdHandle);
        orderOp.getAnnotations().put("plaque-isMax", isMax);
        orderOp.getAnnotations().put("plaque-filteredVar", sortKeyVar);
        orderOp.getAnnotations().put(PLAQUE_CROSS_EXPR, true);
        orderOp.getAnnotations().put("plaque-crossexpr-probe-var", probeVar);
        orderOp.getAnnotations().put("plaque-crossexpr-build-handle", buildSideHandle);
        orderOp.getAnnotations().put("plaque-crossexpr-combine-op", combineOp);
        orderOp.getAnnotations().put("plaque-crossexpr-filter-direction", filterDirection);

        // Annotate the JOIN (same as MAX cross-expr)
        int numPartitions = 4;
        joinOp.getAnnotations().put(PLAQUE_CROSS_EXPR, true);
        joinOp.getAnnotations().put("plaque-crossexpr-build-handle", buildSideHandle);
        joinOp.getAnnotations().put("plaque-crossexpr-build-var", buildVar);
        joinOp.getAnnotations().put("plaque-crossexpr-track-min", trackMin);
        joinOp.getAnnotations().put("plaque-crossexpr-num-partitions", numPartitions);
        joinOp.getAnnotations().put("plaque-crossexpr-threshold-handle", thresholdHandle);
        joinOp.getAnnotations().put("plaque-crossexpr-probe-var", probeVar);
        joinOp.getAnnotations().put("plaque-crossexpr-combine-op", combineOp);
        joinOp.getAnnotations().put("plaque-crossexpr-filter-direction", filterDirection);

        System.out.println("PLAQUE_TOPK_CROSSEXPR: annotations set on order and join. "
                + "topK=" + orderOp.getTopK() + " thresholdHandle=" + thresholdHandle
                + " buildSideHandle=" + buildSideHandle
                + " probeVar=" + probeVar + " buildVar=" + buildVar);

        return true;
    }
}
