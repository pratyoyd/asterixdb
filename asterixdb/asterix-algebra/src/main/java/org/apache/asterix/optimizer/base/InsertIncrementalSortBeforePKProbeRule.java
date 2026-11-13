//package org.apache.asterix.optimizer.base;
//
//import java.util.ArrayList;
//import java.util.Collections;
//import java.util.List;
//
//import org.apache.asterix.om.functions.BuiltinFunctions;
//import org.apache.asterix.optimizer.rules.am.BTreeJobGenParams;
//import org.apache.commons.lang3.mutable.Mutable;
//import org.apache.commons.lang3.mutable.MutableObject;
//import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
//import org.apache.hyracks.algebricks.common.utils.Pair;
//import org.apache.hyracks.algebricks.core.algebra.base.ILogicalExpression;
//import org.apache.hyracks.algebricks.core.algebra.base.ILogicalOperator;
//import org.apache.hyracks.algebricks.core.algebra.base.IOptimizationContext;
//import org.apache.hyracks.algebricks.core.algebra.base.LogicalOperatorTag;
//import org.apache.hyracks.algebricks.core.algebra.base.LogicalVariable;
//import org.apache.hyracks.algebricks.core.algebra.expressions.AbstractFunctionCallExpression;
//import org.apache.hyracks.algebricks.core.algebra.expressions.VariableReferenceExpression;
//import org.apache.hyracks.algebricks.core.algebra.operators.logical.*;
//import org.apache.hyracks.algebricks.core.algebra.operators.logical.visitors.VariableUtilities;
//import org.apache.hyracks.algebricks.core.algebra.operators.physical.IncrementalSortPOperator;
//import org.apache.hyracks.algebricks.core.algebra.properties.OrderColumn;
//import org.apache.hyracks.algebricks.core.algebra.base.PhysicalOperatorTag;
//import org.apache.hyracks.algebricks.core.rewriter.base.IAlgebraicRewriteRule;
//
//public class InsertIncrementalSortBeforePKProbeRule implements IAlgebraicRewriteRule {
//
//    @Override
//    public boolean rewritePre(Mutable<ILogicalOperator> opRef, IOptimizationContext ctx) throws AlgebricksException {
//        if (!ctx.getPhysicalOptimizationConfig().getInteractiveMode()) {
//            return false;
//        }
//        return apply(opRef, ctx, null);
//    }
//
//    @Override
//    public boolean rewritePost(Mutable<ILogicalOperator> opRef, IOptimizationContext ctx) {
//        return false;
//    }
//
//    private boolean apply(Mutable<ILogicalOperator> opRef, IOptimizationContext ctx, LogicalVariable gVar)
//            throws AlgebricksException {
//        AbstractLogicalOperator op = (AbstractLogicalOperator) opRef.getValue();
//        boolean modified = false;
//
//        // Carry G from nearest upstream GROUP BY (first grouping var)
//        LogicalVariable nextG = gVar;
//        if (op.getOperatorTag() == LogicalOperatorTag.GROUP) {
//            GroupByOperator gby = (GroupByOperator) op;
//            List<LogicalVariable> gbl = gby.getGroupByVarList();
//            if (gbl != null && !gbl.isEmpty()) {
//                nextG = gbl.get(0);
//            }
//        }
//
//        // Recurse first
//        for (Mutable<ILogicalOperator> in : op.getInputs()) {
//            modified |= apply(in, ctx, nextG);
//        }
//
//        // If this op is a primary index probe, insert IncrementalSort just before it (at the last non-reordering parent)
//        if (isPrimaryIndexProbe(op)) {
//            modified |= insertBeforePkProbe(opRef, ctx, nextG);
//            if (modified) {
//                ctx.addToDontApplySet(this, opRef.getValue());
//            }
//        }
//        return modified;
//    }
//
//    /** Insert ORDER BY K (IncrementalSort) after the last non-reordering parent that has K (and optionally G) live. */
//    private boolean insertBeforePkProbe(Mutable<ILogicalOperator> pkProbeRef,
//                                        IOptimizationContext ctx,
//                                        LogicalVariable gVar) throws AlgebricksException {
//        // PK probe (must be primary-index search)
//        AbstractLogicalOperator pkProbe = (AbstractLogicalOperator) pkProbeRef.getValue();
//        if (pkProbe.getOperatorTag() != LogicalOperatorTag.UNNEST_MAP) return false;
//        if (pkProbe.getInputs().isEmpty()) return false;
//
//        // K (PK key vars)
//        List<LogicalVariable> kVars = getPkVarsFromProbe((AbstractUnnestMapOperator) pkProbe);
//        if (kVars.isEmpty()) return false;
//
//        // Expect: PK -> EX1 -> PROJECT -> EX2 -> SK
//        // EX1
//        Mutable<ILogicalOperator> ex1Ref = pkProbe.getInputs().get(0);
//        AbstractLogicalOperator ex1Op = (AbstractLogicalOperator) ex1Ref.getValue();
//        if (ex1Op.getOperatorTag() != LogicalOperatorTag.EXCHANGE) return false;
//
//        // PROJECT
//        if (ex1Op.getInputs().isEmpty()) return false;
//        Mutable<ILogicalOperator> projectRef = ex1Op.getInputs().get(0);
//        AbstractLogicalOperator projectOp = (AbstractLogicalOperator) projectRef.getValue();
//        if (projectOp.getOperatorTag() != LogicalOperatorTag.PROJECT) return false;
//
//        // EX2
//        if (projectOp.getInputs().isEmpty()) return false;
//        Mutable<ILogicalOperator> ex2Ref = projectOp.getInputs().get(0);
//        AbstractLogicalOperator ex2Op = (AbstractLogicalOperator) ex2Ref.getValue();
//        if (ex2Op.getOperatorTag() != LogicalOperatorTag.EXCHANGE) return false;
//
//        // SK (secondary index-search, same dataset as PK)
//        if (ex2Op.getInputs().isEmpty()) return false;
//        Mutable<ILogicalOperator> skRef = ex2Op.getInputs().get(0);
//        AbstractLogicalOperator skOp = (AbstractLogicalOperator) skRef.getValue();
//        if (skOp.getOperatorTag() != LogicalOperatorTag.UNNEST_MAP
//                || skOp.getPhysicalOperator() == null
//                || skOp.getPhysicalOperator().getOperatorTag() != PhysicalOperatorTag.BTREE_SEARCH) {
//            return false;
//        }
//        BTreeJobGenParams pkParams = readParams((AbstractUnnestMapOperator) pkProbe);
//        BTreeJobGenParams skParams = readParams((AbstractUnnestMapOperator) skOp);
//        if (!pkParams.isPrimaryIndex() || skParams.isPrimaryIndex()) return false;
//        if (!safeEq(pkParams.getDatasetName(), skParams.getDatasetName())) return false;
//
//        // Sort input will be EXnew → PROJECT output. Ensure G and all K live there.
//        if (!hasAllLive(projectOp, kVars)) return false;
//        if (gVar == null || !isLive(projectOp, gVar)) return false;
//
//        // Build ORDER BY on K, group by G
//        List<Pair<OrderOperator.IOrder, Mutable<ILogicalExpression>>> orderExprs = new ArrayList<>();
//        List<OrderColumn> orderCols = new ArrayList<>();
//        for (LogicalVariable v : kVars) {
//            orderExprs.add(new Pair<>(OrderOperator.ASC_ORDER,
//                    new MutableObject<ILogicalExpression>(new VariableReferenceExpression(v))));
//            orderCols.add(new OrderColumn(v, OrderOperator.IOrder.OrderKind.ASC));
//        }
//        OrderOperator incOrder = new OrderOperator(orderExprs);
//        incOrder.setPhysicalOperator(new IncrementalSortPOperator(
//                orderCols.toArray(new OrderColumn[0]),
//                Collections.singletonList(gVar)));
//        incOrder.setExecutionMode(pkProbe.getExecutionMode());
//
//        // Create the new ONE_TO_ONE EXCHANGE that will sit between IncrementalSort and PROJECT
//        ExchangeOperator exNew = new ExchangeOperator();
//        exNew.setPhysicalOperator(new org.apache.hyracks.algebricks.core.algebra.operators.physical.OneToOneExchangePOperator());
//        exNew.setExecutionMode(pkProbe.getExecutionMode());
//
//        // Wire new shape EXACTLY as requested:
//        // PK -> EX1 -> incOrder -> exNew -> PROJECT -> EX2 -> SK
//
//        // 1) EX1’s child becomes incOrder (replacing PROJECT at that edge)
//        ex1Op.getInputs().set(0, new MutableObject<>(incOrder));
//
//        // 2) incOrder’s child is exNew
//        incOrder.getInputs().add(new MutableObject<>(exNew));
//
//        // 3) exNew’s child is PROJECT (keep PROJECT → EX2 → SK as-is)
//        exNew.getInputs().add(projectRef);
//
//        // Recompute types/properties from PK down
//        recomputePropsAndTypes(pkProbeRef.getValue(), ctx);
//        return true;
//    }
//
//
//
//    /** Primary index probe = UNNEST_MAP + BTREE_SEARCH + INDEX_SEARCH with isPrimaryIndex()==true. */
//    private boolean isPrimaryIndexProbe(ILogicalOperator op) throws AlgebricksException {
//        if (op.getOperatorTag() != LogicalOperatorTag.UNNEST_MAP) return false;
//        AbstractLogicalOperator a = (AbstractLogicalOperator) op;
//        if (a.getPhysicalOperator() == null
//                || a.getPhysicalOperator().getOperatorTag() != PhysicalOperatorTag.BTREE_SEARCH) return false;
//        ILogicalExpression e = ((AbstractUnnestMapOperator) op).getExpressionRef().getValue();
//        if (!(e instanceof AbstractFunctionCallExpression)) return false;
//        AbstractFunctionCallExpression f = (AbstractFunctionCallExpression) e;
//        if (!f.getFunctionIdentifier().equals(BuiltinFunctions.INDEX_SEARCH)) return false;
//        BTreeJobGenParams p = new BTreeJobGenParams();
//        p.readFromFuncArgs(f.getArguments());
//        return p.isPrimaryIndex();
//    }
//
//    /** PK key variables (supports composite PK). */
//    private List<LogicalVariable> getPkVarsFromProbe(AbstractUnnestMapOperator um) throws AlgebricksException {
//        AbstractFunctionCallExpression f =
//                (AbstractFunctionCallExpression) um.getExpressionRef().getValue();
//        BTreeJobGenParams p = new BTreeJobGenParams();
//        p.readFromFuncArgs(f.getArguments());
//        List<LogicalVariable> pk = p.getLowKeyVarList();
//        return pk != null ? pk : Collections.emptyList();
//    }
//
//    /** Non-reordering operators for our walk. */
//    private boolean isNonReordering(AbstractLogicalOperator op) {
//        LogicalOperatorTag tag = op.getOperatorTag();
//        if (tag == LogicalOperatorTag.ASSIGN || tag == LogicalOperatorTag.PROJECT
//                || tag == LogicalOperatorTag.SELECT) {
//            return true;
//        }
//        return tag == LogicalOperatorTag.EXCHANGE
//                && op.getPhysicalOperator() != null
//                && op.getPhysicalOperator().getOperatorTag() == PhysicalOperatorTag.ONE_TO_ONE_EXCHANGE;
//    }
//
//    /** Do all K vars appear in the live set of this op? */
//    private boolean hasAllLive(AbstractLogicalOperator op, List<LogicalVariable> vars) throws AlgebricksException {
//        List<LogicalVariable> live = new ArrayList<>();
//        VariableUtilities.getLiveVariables(op, live);
//        for (LogicalVariable v : vars) {
//            if (!live.contains(v)) return false;
//        }
//        return true;
//    }
//
//    /** Is v live at the output of op? */
//    private boolean isLive(AbstractLogicalOperator op, LogicalVariable v) throws AlgebricksException {
//        List<LogicalVariable> live = new ArrayList<>();
//        VariableUtilities.getLiveVariables(op, live);
//        return live.contains(v);
//    }
//
//    private void recomputePropsAndTypes(ILogicalOperator op, IOptimizationContext ctx) throws AlgebricksException {
//        for (Mutable<ILogicalOperator> in : op.getInputs()) {
//            recomputePropsAndTypes(in.getValue(), ctx);
//        }
//        ctx.computeAndSetTypeEnvironmentForOperator(op);
//        op.computeDeliveredPhysicalProperties(ctx);
//    }
//    private boolean hasSecondaryOnSameDatasetUpstream(AbstractLogicalOperator projectOp, String datasetName)
//            throws AlgebricksException {
//        if (projectOp.getInputs().isEmpty()) {
//            return false;
//        }
//        Mutable<ILogicalOperator> slot = projectOp.getInputs().get(0);
//        while (slot != null) {
//            AbstractLogicalOperator op = (AbstractLogicalOperator) slot.getValue();
//
//            // Found an index-search?
//            if (op.getOperatorTag() == LogicalOperatorTag.UNNEST_MAP
//                    && op.getPhysicalOperator() != null
//                    && op.getPhysicalOperator().getOperatorTag() == PhysicalOperatorTag.BTREE_SEARCH) {
//                BTreeJobGenParams p = readParams((AbstractUnnestMapOperator) op);
//                // Secondary on the SAME dataset?
//                return !p.isPrimaryIndex() && safeEq(p.getDatasetName(), datasetName);
//            }
//
//            // Stop if reordering; otherwise keep walking downstream
//            if (!isNonReordering(op) || op.getInputs().isEmpty()) {
//                return false;
//            }
//            slot = op.getInputs().get(0);
//        }
//        return false;
//    }
//
//    private BTreeJobGenParams readParams(AbstractUnnestMapOperator um) throws AlgebricksException {
//        AbstractFunctionCallExpression f =
//                (AbstractFunctionCallExpression) um.getExpressionRef().getValue();
//        BTreeJobGenParams p = new BTreeJobGenParams();
//        p.readFromFuncArgs(f.getArguments());
//        return p;
//    }
//
//    private static boolean safeEq(String a, String b) {
//        return a == null ? b == null : a.equals(b);
//    }
//
//}
package org.apache.asterix.optimizer.base;

import java.util.ArrayList;
import java.util.List;

import org.apache.asterix.om.functions.BuiltinFunctions;
import org.apache.asterix.optimizer.rules.am.BTreeJobGenParams;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalExpression;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.base.IOptimizationContext;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalOperatorTag;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalVariable;
import org.apache.hyracks.algebricks.core.algebra.base.PhysicalOperatorTag;
import org.apache.hyracks.algebricks.core.algebra.expressions.AbstractFunctionCallExpression;
import org.apache.hyracks.algebricks.core.algebra.expressions.VariableReferenceExpression;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractUnnestMapOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.ExchangeOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.GroupByOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.OrderOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.visitors.VariableUtilities;
import org.apache.hyracks.algebricks.core.algebra.operators.physical.IncrementalSortPOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.physical.OneToOneExchangePOperator;
import org.apache.hyracks.algebricks.core.algebra.properties.OrderColumn;
import org.apache.hyracks.algebricks.core.rewriter.base.IAlgebraicRewriteRule;

public class InsertIncrementalSortBeforePKProbeRule implements IAlgebraicRewriteRule {

    @Override
    public boolean rewritePre(Mutable<ILogicalOperator> opRef, IOptimizationContext ctx) throws AlgebricksException {
        if (!ctx.getPhysicalOptimizationConfig().getInteractiveMode()) {
            return false;
        }
        return apply(opRef, ctx, null);
    }

    @Override
    public boolean rewritePost(Mutable<ILogicalOperator> opRef, IOptimizationContext ctx) {
        return false;
    }

    private boolean apply(Mutable<ILogicalOperator> opRef, IOptimizationContext ctx, LogicalVariable gVar)
            throws AlgebricksException {
        AbstractLogicalOperator op = (AbstractLogicalOperator) opRef.getValue();
        boolean modified = false;

        LogicalVariable nextG = gVar;
        if (op.getOperatorTag() == LogicalOperatorTag.GROUP) {
            GroupByOperator gby = (GroupByOperator) op;
            List<LogicalVariable> gbl = gby.getGroupByVarList();
            if (gbl != null && !gbl.isEmpty()) {
                nextG = gbl.get(0); // Note: this is the GROUP-BY output var; may not be live below GROUP
            }
        }

        for (Mutable<ILogicalOperator> in : op.getInputs()) {
            modified |= apply(in, ctx, nextG);
        }

        if (isPrimaryIndexProbe(op)) {
            modified |= insertBeforePkProbe(opRef, ctx, nextG);
            if (modified) {
                ctx.addToDontApplySet(this, opRef.getValue());
            }
        }
        return modified;
    }

    /**
     * Insert:  PK_PROBE <- EXCHANGE(1:1) <- INCREMENTAL_SORT <- EXCHANGE(1:1) <- child
     * if upstream (via only non-reordering ops) we find a SAME-DATASET secondary BTREE_SEARCH.
     * Boundary = gVar (if live) else first output var of that secondary (e.g., $$107) else first PK key.
     */
    private boolean insertBeforePkProbe(Mutable<ILogicalOperator> pkProbeRef, IOptimizationContext ctx,
            LogicalVariable gVar) throws AlgebricksException {
        AbstractLogicalOperator pkProbe = (AbstractLogicalOperator) pkProbeRef.getValue();
        if (pkProbe.getOperatorTag() != LogicalOperatorTag.UNNEST_MAP || pkProbe.getInputs().isEmpty()) {
            return false;
        }

        // Ensure it's a primary index probe
        BTreeJobGenParams pkParams = readParams((AbstractUnnestMapOperator) pkProbe);
        if (!pkParams.isPrimaryIndex()) {
            return false;
        }

        // ORDER BY PK low-key vars
        List<LogicalVariable> kVars = pkParams.getLowKeyVarList();
        if (kVars == null || kVars.isEmpty()) {
            return false;
        }

        // Avoid double insertion if immediate input is already an IncrementalSort
        ILogicalOperator pkInputOp = pkProbe.getInputs().get(0).getValue();
        if (isIncrementalSort(pkInputOp)) {
            return false;
        }

        // Optional safety: ensure order keys live at insertion point
        if (!hasAllLive(pkInputOp, kVars)) {
            return false;
        }

        // Scan upstream for SAME-DATASET secondary search; collect its first output var (e.g., $$107)
        Mutable<ILogicalOperator> curRef = pkProbe.getInputs().get(0);
        LogicalVariable boundaryFromSecondary = null;

        while (curRef != null) {
            AbstractLogicalOperator cur = (AbstractLogicalOperator) curRef.getValue();

            if (cur.getOperatorTag() == LogicalOperatorTag.UNNEST_MAP && cur.getPhysicalOperator() != null
                    && cur.getPhysicalOperator().getOperatorTag() == PhysicalOperatorTag.BTREE_SEARCH) {
                BTreeJobGenParams skParams = readParams((AbstractUnnestMapOperator) cur);
                if (!skParams.isPrimaryIndex() && safeEq(pkParams.getDatasetName(), skParams.getDatasetName())) {
                    List<LogicalVariable> outVars = ((AbstractUnnestMapOperator) cur).getVariables();
                    if (outVars != null && !outVars.isEmpty()) {
                        boundaryFromSecondary = outVars.get(0); // e.g., $$107
                    }
                }
                break; // stop at first search
            }

            if (isNonReordering(cur) && !cur.getInputs().isEmpty()) {
                curRef = cur.getInputs().get(0);
            } else {
                break; // barrier
            }
        }

        if (boundaryFromSecondary == null) {
            // require a same-dataset secondary present upstream
            return false;
        }

        // --- Build ORDER (incremental sort) ---
        List<Pair<OrderOperator.IOrder, Mutable<ILogicalExpression>>> orderExprs = new ArrayList<>();
        List<OrderColumn> orderCols = new ArrayList<>();
        for (LogicalVariable v : kVars) {
            orderExprs.add(Pair.of(OrderOperator.ASC_ORDER,
                    new MutableObject<ILogicalExpression>(new VariableReferenceExpression(v))));
            orderCols.add(new OrderColumn(v, OrderOperator.IOrder.OrderKind.ASC));
        }
        OrderOperator incOrder = new OrderOperator(orderExprs);

        // Boundary must be non-empty
        List<LogicalVariable> boundary = new ArrayList<>(1);
        if (gVar != null && isLive(pkInputOp, gVar)) {
            boundary.add(gVar);
        } else if (boundaryFromSecondary != null && isLive(pkInputOp, boundaryFromSecondary)) {
            boundary.add(boundaryFromSecondary); // $$107
        } else {
            boundary.add(kVars.get(0)); // final fallback
        }

        incOrder.setPhysicalOperator(new IncrementalSortPOperator(orderCols.toArray(new OrderColumn[0]), boundary));
        incOrder.setExecutionMode(pkProbe.getExecutionMode());

        // ===== Rewiring to ensure: PK <- EX(above) <- ORDER <- EX(below) <- oldChild =====

        // Current pk input (might be EXCHANGE already)
        Mutable<ILogicalOperator> pkInputRef = pkProbe.getInputs().get(0);
        ILogicalOperator pkInput = pkInputRef.getValue();

        // (1) Ensure EXACTLY ONE EXCHANGE ABOVE the ORDER (between PK and ORDER)
        AbstractLogicalOperator exAbove;
        boolean reusedExAbove = false;
        if (pkInput.getOperatorTag() == LogicalOperatorTag.EXCHANGE
                && ((AbstractLogicalOperator) pkInput).getPhysicalOperator() != null
                && ((AbstractLogicalOperator) pkInput).getPhysicalOperator()
                        .getOperatorTag() == PhysicalOperatorTag.ONE_TO_ONE_EXCHANGE) {
            exAbove = (AbstractLogicalOperator) pkInput; // reuse
            reusedExAbove = true;

            // If exAbove already feeds an IncrementalSort, abort to avoid double insertion
            if (!exAbove.getInputs().isEmpty()) {
                ILogicalOperator childOfExAbove = exAbove.getInputs().get(0).getValue();
                if (isIncrementalSort(childOfExAbove)) {
                    return false;
                }
            }
        } else {
            ExchangeOperator exNewAbove = new ExchangeOperator();
            exNewAbove.setPhysicalOperator(new OneToOneExchangePOperator());
            exNewAbove.setExecutionMode(pkProbe.getExecutionMode());
            exAbove = exNewAbove;
            pkProbe.getInputs().set(0, new MutableObject<>(exAbove)); // attach above
        }

        // (2) Determine the old child (the subtree that ORDER should ultimately consume)
        Mutable<ILogicalOperator> oldChildRef;
        if (reusedExAbove && !exAbove.getInputs().isEmpty()) {
            oldChildRef = exAbove.getInputs().get(0); // PK -> exAbove -> oldChild
        } else {
            oldChildRef = pkInputRef; // PK -> (new exAbove) -> old pk input
        }

        // (3) Ensure EXACTLY ONE EXCHANGE BELOW the ORDER (between ORDER and oldChild)
        AbstractLogicalOperator exBelowOp;
        boolean reusedExBelow = false;
        ILogicalOperator oldChild = oldChildRef.getValue();
        if (oldChild.getOperatorTag() == LogicalOperatorTag.EXCHANGE
                && ((AbstractLogicalOperator) oldChild).getPhysicalOperator() != null
                && ((AbstractLogicalOperator) oldChild).getPhysicalOperator()
                        .getOperatorTag() == PhysicalOperatorTag.ONE_TO_ONE_EXCHANGE) {
            exBelowOp = (AbstractLogicalOperator) oldChild; // reuse
            reusedExBelow = true;
        } else {
            ExchangeOperator exNewBelow = new ExchangeOperator();
            exNewBelow.setPhysicalOperator(new OneToOneExchangePOperator());
            exNewBelow.setExecutionMode(pkProbe.getExecutionMode());
            exBelowOp = exNewBelow;
        }

        // (4) Final wiring:
        // PK_PROBE <- exAbove <- incOrder <- exBelowOp <- oldChildRef
        if (reusedExAbove) {
            if (exAbove.getInputs().isEmpty()) {
                exAbove.getInputs().add(new MutableObject<>(incOrder));
            } else {
                exAbove.getInputs().set(0, new MutableObject<>(incOrder));
            }
        } else {
            ((AbstractLogicalOperator) exAbove).getInputs().add(new MutableObject<>(incOrder));
        }

        if (reusedExBelow) {
            incOrder.getInputs().add(new MutableObject<>(exBelowOp)); // keep exBelow → oldChild as-is
        } else {
            incOrder.getInputs().add(new MutableObject<>(exBelowOp));
            exBelowOp.getInputs().add(oldChildRef);
        }

        // Recompute properties/types
        recomputePropsAndTypes(pkProbeRef.getValue(), ctx);
        ctx.addToDontApplySet(this, pkProbeRef.getValue());
        return true;
    }

    // --------- helpers ---------

    private boolean isPrimaryIndexProbe(ILogicalOperator op) throws AlgebricksException {
        if (op.getOperatorTag() != LogicalOperatorTag.UNNEST_MAP) {
            return false;
        }
        AbstractLogicalOperator a = (AbstractLogicalOperator) op;
        if (a.getPhysicalOperator() == null
                || a.getPhysicalOperator().getOperatorTag() != PhysicalOperatorTag.BTREE_SEARCH) {
            return false;
        }
        ILogicalExpression e = ((AbstractUnnestMapOperator) op).getExpressionRef().getValue();
        if (!(e instanceof AbstractFunctionCallExpression)) {
            return false;
        }
        AbstractFunctionCallExpression f = (AbstractFunctionCallExpression) e;
        if (!f.getFunctionIdentifier().equals(BuiltinFunctions.INDEX_SEARCH)) {
            return false;
        }
        BTreeJobGenParams p = new BTreeJobGenParams();
        p.readFromFuncArgs(f.getArguments());
        return p.isPrimaryIndex();
    }

    private boolean isIncrementalSort(ILogicalOperator op) {
        if (op.getOperatorTag() != LogicalOperatorTag.ORDER) {
            return false;
        }
        AbstractLogicalOperator a = (AbstractLogicalOperator) op;
        return a.getPhysicalOperator() != null
                && a.getPhysicalOperator().getOperatorTag() == PhysicalOperatorTag.INCREMENTAL_SORT;
    }

    private boolean isNonReordering(AbstractLogicalOperator op) {
        LogicalOperatorTag tag = op.getOperatorTag();
        if (tag == LogicalOperatorTag.ASSIGN || tag == LogicalOperatorTag.PROJECT || tag == LogicalOperatorTag.SELECT) {
            return true;
        }
        return tag == LogicalOperatorTag.EXCHANGE && op.getPhysicalOperator() != null
                && op.getPhysicalOperator().getOperatorTag() == PhysicalOperatorTag.ONE_TO_ONE_EXCHANGE;
    }

    private boolean hasAllLive(ILogicalOperator op, List<LogicalVariable> vars) throws AlgebricksException {
        List<LogicalVariable> live = new ArrayList<>();
        VariableUtilities.getLiveVariables(op, live);
        for (LogicalVariable v : vars) {
            if (!live.contains(v)) {
                return false;
            }
        }
        return true;
    }

    private boolean isLive(ILogicalOperator op, LogicalVariable v) throws AlgebricksException {
        List<LogicalVariable> live = new ArrayList<>();
        VariableUtilities.getLiveVariables(op, live);
        return live.contains(v);
    }

    private void recomputePropsAndTypes(ILogicalOperator op, IOptimizationContext ctx) throws AlgebricksException {
        for (Mutable<ILogicalOperator> in : op.getInputs()) {
            recomputePropsAndTypes(in.getValue(), ctx);
        }
        ctx.computeAndSetTypeEnvironmentForOperator(op);
        op.computeDeliveredPhysicalProperties(ctx);
    }

    private BTreeJobGenParams readParams(AbstractUnnestMapOperator um) throws AlgebricksException {
        AbstractFunctionCallExpression f = (AbstractFunctionCallExpression) um.getExpressionRef().getValue();
        BTreeJobGenParams p = new BTreeJobGenParams();
        p.readFromFuncArgs(f.getArguments());
        return p;
    }

    private static boolean safeEq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
