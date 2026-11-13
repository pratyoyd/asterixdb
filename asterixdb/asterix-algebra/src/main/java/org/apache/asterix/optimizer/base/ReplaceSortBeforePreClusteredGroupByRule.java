//package org.apache.asterix.optimizer.base;
//
//import org.apache.commons.lang3.mutable.Mutable;
//import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
//import org.apache.hyracks.algebricks.core.algebra.base.ILogicalOperator;
//import org.apache.hyracks.algebricks.core.algebra.base.IOptimizationContext;
//import org.apache.hyracks.algebricks.core.algebra.base.LogicalOperatorTag;
//import org.apache.hyracks.algebricks.core.algebra.base.PhysicalOperatorTag;
//import org.apache.hyracks.algebricks.core.algebra.operators.logical.ExchangeOperator;
//import org.apache.hyracks.algebricks.core.algebra.operators.logical.GroupByOperator;
//import org.apache.hyracks.algebricks.core.algebra.operators.logical.OrderOperator;
//
//public class ReplaceSortBeforePreClusteredGroupByRule
//        implements org.apache.hyracks.algebricks.core.rewriter.base.IAlgebraicRewriteRule {
//    @Override
//    public boolean rewritePost(Mutable<ILogicalOperator> opRef, IOptimizationContext context) {
//
//        return false;
//    }
//
//    @Override
//    public boolean rewritePre(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
//            throws AlgebricksException {
//        if (!context.getPhysicalOptimizationConfig().getInteractiveMode()) {
//            return false;
//        }
//        return removeSortsBeforePreClusteredGroupBy(opRef, context);
//    }
//
//    private boolean removeSortsBeforePreClusteredGroupBy(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
//            throws AlgebricksException {
//        boolean modified = false;
//
//        ILogicalOperator op = opRef.getValue();
//
//        // Recurse on inputs first
//        for (Mutable<ILogicalOperator> childRef : op.getInputs()) {
//            modified |= removeSortsBeforePreClusteredGroupBy(childRef, context);
//        }
//
//        // If this is a pre clustered group by, check for the Order pattern
//        if (op.getOperatorTag() == LogicalOperatorTag.GROUP) {
//            GroupByOperator group = (GroupByOperator) op;
//            Mutable<ILogicalOperator> exchangeRef = group.getInputs().get(0);
//            ILogicalOperator exchangeOp = exchangeRef.getValue();
//
//            if (exchangeOp.getOperatorTag() == LogicalOperatorTag.EXCHANGE) {
//                ExchangeOperator exchange = (ExchangeOperator) exchangeOp;
//                Mutable<ILogicalOperator> orderRef = exchange.getInputs().get(0);
//                ILogicalOperator orderOp = orderRef.getValue();
//
//                if (orderOp.getOperatorTag() == LogicalOperatorTag.ORDER) {
//                    OrderOperator order = (OrderOperator) orderOp;
//
//                    Mutable<ILogicalOperator> secondExchangeRef = order.getInputs().get(0);
//                    ILogicalOperator secondExchangeOp = secondExchangeRef.getValue();
//
//                    if (secondExchangeOp.getOperatorTag() == LogicalOperatorTag.EXCHANGE
//                            && ((ExchangeOperator) secondExchangeOp).getPhysicalOperator()
//                                    .getOperatorTag() == PhysicalOperatorTag.HASH_PARTITION_EXCHANGE) {
//
//                        // Replace chain: UnnestMap <- Exchange <- Order <- Exchange
//                        // With:        UnnestMap <- Exchange (second one)
//                        group.getInputs().set(0, secondExchangeRef);
//                        recomputePropsAndTypes(op, context);
//                        modified = true;
//                    }
//                }
//            }
//        }
//
//        return modified;
//    }
//
//    private void recomputePropsAndTypes(ILogicalOperator op, IOptimizationContext context) throws AlgebricksException {
//        for (Mutable<ILogicalOperator> inputRef : op.getInputs()) {
//            recomputePropsAndTypes(inputRef.getValue(), context);
//        }
//        context.computeAndSetTypeEnvironmentForOperator(op);
//        op.computeDeliveredPhysicalProperties(context);
//    }
//
//}

package org.apache.asterix.optimizer.base;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalExpression;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.base.IOptimizationContext;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalExpressionTag;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalOperatorTag;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalVariable;
import org.apache.hyracks.algebricks.core.algebra.expressions.VariableReferenceExpression;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.ExchangeOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.GroupByOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.OrderOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.visitors.VariableUtilities;
import org.apache.hyracks.algebricks.core.algebra.operators.physical.IncrementalSortPOperator;
import org.apache.hyracks.algebricks.core.algebra.properties.OrderColumn;

public class ReplaceSortBeforePreClusteredGroupByRule
        implements org.apache.hyracks.algebricks.core.rewriter.base.IAlgebraicRewriteRule {

    @Override
    public boolean rewritePost(Mutable<ILogicalOperator> opRef, IOptimizationContext context) {
        return false;
    }

    @Override
    public boolean rewritePre(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
            throws AlgebricksException {
        // Use isInteractiveMode() if that's your branch
        if (!context.getPhysicalOptimizationConfig().getInteractiveMode()) {
            return false;
        }
        return apply(opRef, context);
    }

    private boolean apply(Mutable<ILogicalOperator> opRef, IOptimizationContext ctx) throws AlgebricksException {
        boolean modified = false;

        ILogicalOperator op = opRef.getValue();
        for (Mutable<ILogicalOperator> child : op.getInputs()) {
            modified |= apply(child, ctx);
        }

        if (op.getOperatorTag() != LogicalOperatorTag.GROUP) {
            return modified;
        }

        GroupByOperator gby = (GroupByOperator) op;
        if (gby.getInputs().isEmpty()) {
            return modified;
        }

        // Expect pattern: GROUP BY <- EXCHANGE <- ORDER <- EXCHANGE <- ...
        Mutable<ILogicalOperator> exchAboveRef = gby.getInputs().get(0);
        ILogicalOperator exchAboveOp = exchAboveRef.getValue();
        if (exchAboveOp.getOperatorTag() != LogicalOperatorTag.EXCHANGE) {
            return modified;
        }
        ExchangeOperator exchAbove = (ExchangeOperator) exchAboveOp;
        if (exchAbove.getInputs().isEmpty()) {
            return modified;
        }

        Mutable<ILogicalOperator> orderRef = exchAbove.getInputs().get(0);
        ILogicalOperator orderOp = orderRef.getValue();
        if (orderOp.getOperatorTag() != LogicalOperatorTag.ORDER) {
            return modified;
        }
        OrderOperator oldOrder = (OrderOperator) orderOp;

        if (oldOrder.getInputs().isEmpty()) {
            return modified;
        }
        Mutable<ILogicalOperator> exchBelowRef = oldOrder.getInputs().get(0);
        ILogicalOperator exchBelowOp = exchBelowRef.getValue();
        if (exchBelowOp.getOperatorTag() != LogicalOperatorTag.EXCHANGE) {
            return modified;
        }
        // Optional strictness: ensure it’s a HASH_PARTITION_EXCHANGE
        // if (((ExchangeOperator) exchBelowOp).getPhysicalOperator() == null
        //     || ((ExchangeOperator) exchBelowOp).getPhysicalOperator().getOperatorTag()
        //           != PhysicalOperatorTag.HASH_PARTITION_EXCHANGE) return modified;

        // Gather input-side GBY vars in order: [g0, g1, g2, ...]
        List<LogicalVariable> gbyInputVars = getGroupByInputVars(gby);
        if (gbyInputVars.size() < 1) {
            return modified;
        }

        LogicalVariable boundary = gbyInputVars.get(0); // first group key → boundary
        List<LogicalVariable> orderVars = gbyInputVars.subList(1, gbyInputVars.size()); // the rest → sort keys

        // Liveness guards at ORDER location
        if (!isLive(orderOp, boundary)) {
            return modified;
        }
        for (LogicalVariable v : orderVars) {
            if (!isLive(orderOp, v)) {
                return modified;
            }
        }

        // Build ORDER exprs for the "other" GBY keys (ASC)
        List<Pair<OrderOperator.IOrder, Mutable<ILogicalExpression>>> orderExprs = new ArrayList<>();
        List<OrderColumn> orderColsList = new ArrayList<>();
        for (LogicalVariable v : orderVars) {
            orderExprs.add(Pair.of(OrderOperator.ASC_ORDER, new MutableObject<>(new VariableReferenceExpression(v))));
            orderColsList.add(new OrderColumn(v, OrderOperator.IOrder.OrderKind.ASC));
        }

        // If there are no "other" keys (single-key group-by), we still want an incremental boundary flush,
        // so keep ORDER with empty key list but valid boundary. IncrementalSortPOperator supports that.
        OrderOperator incOrder = new OrderOperator(orderExprs);

        OrderColumn[] orderCols = orderColsList.toArray(new OrderColumn[0]);
        List<LogicalVariable> boundaryList = Collections.singletonList(boundary);

        incOrder.setPhysicalOperator(new IncrementalSortPOperator(orderCols, boundaryList));
        incOrder.setExecutionMode(oldOrder.getExecutionMode());

        // Wire: exchAbove -> incOrder -> exchBelow (keep both exchanges)
        incOrder.getInputs().add(exchBelowRef);
        exchAbove.getInputs().set(0, new MutableObject<>(incOrder));

        // Recompute properties/types from the GROUP BY down
        recomputePropsAndTypes(opRef.getValue(), ctx);
        ctx.addToDontApplySet(this, opRef.getValue());
        return true;
    }

    /** Returns input-side vars for the group-by keys, in declaration order. */
    private List<LogicalVariable> getGroupByInputVars(GroupByOperator gby) {
        List<LogicalVariable> result = new ArrayList<>();
        if (gby.getGroupByList() == null) {
            return result;
        }
        for (Pair<LogicalVariable, Mutable<ILogicalExpression>> p : gby.getGroupByList()) {
            ILogicalExpression e = p.getRight().getValue();
            if (e.getExpressionTag() == LogicalExpressionTag.VARIABLE) {
                // Use the source (input-side) var (e.g., $$98), not the output var (e.g., $$p_brand)
                result.add(((VariableReferenceExpression) e).getVariableReference());
            } else {
                // Non-var GBY expr; skip rewrite for safety (we only handle simple var refs)
                result.clear();
                break;
            }
        }
        return result;
    }

    private boolean isLive(ILogicalOperator atOp, LogicalVariable v) throws AlgebricksException {
        List<LogicalVariable> live = new ArrayList<>();
        VariableUtilities.getLiveVariables(atOp, live);
        return live.contains(v);
    }

    private void recomputePropsAndTypes(ILogicalOperator op, IOptimizationContext ctx) throws AlgebricksException {
        for (Mutable<ILogicalOperator> in : op.getInputs()) {
            recomputePropsAndTypes(in.getValue(), ctx);
        }
        ctx.computeAndSetTypeEnvironmentForOperator(op);
        op.computeDeliveredPhysicalProperties(ctx);
    }
}
