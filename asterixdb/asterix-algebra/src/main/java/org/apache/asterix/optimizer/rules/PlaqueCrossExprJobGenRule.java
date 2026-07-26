package org.apache.asterix.optimizer.rules;

import java.util.HashSet;
import java.util.Set;

import org.apache.asterix.algebra.operators.physical.PlaqueCrossExprExchangePOperator;
import org.apache.asterix.algebra.operators.physical.PlaqueCrossExprHashJoinPOperator;
import org.apache.asterix.algebra.operators.physical.PlaqueTopKSortPOperator;
import org.apache.asterix.runtime.operators.plaque.PlaqueCrossExprExchangeFilterFactory;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.base.IOptimizationContext;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalOperatorTag;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalVariable;
import org.apache.hyracks.algebricks.core.algebra.base.PhysicalOperatorTag;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractBinaryJoinOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.OrderOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.visitors.VariableUtilities;
import org.apache.hyracks.algebricks.core.algebra.operators.physical.HashPartitionExchangePOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.physical.HybridHashJoinPOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.physical.StableSortPOperator;
import org.apache.hyracks.algebricks.core.rewriter.base.IAlgebraicRewriteRule;

/**
 * Late-phase rule that reads PLAQUE cross-expression annotations set during consolidation
 * and replaces the standard physical operators with PLAQUE variants.
 * Runs after SetAsterixPhysicalOperatorsRule and EnforceStructuralPropertiesRule.
 */
public class PlaqueCrossExprJobGenRule implements IAlgebraicRewriteRule {

    private static final String PLAQUE_CROSS_EXPR = "plaque-cross-expr";

    @Override
    public boolean rewritePre(Mutable<ILogicalOperator> opRef, IOptimizationContext context) {
        return false;
    }

    @Override
    public boolean rewritePost(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
            throws AlgebricksException {
        AbstractLogicalOperator op = (AbstractLogicalOperator) opRef.getValue();

        if (op.getOperatorTag() == LogicalOperatorTag.INNERJOIN) {
            return handleJoin(opRef, context);
        }

        if (op.getOperatorTag() == LogicalOperatorTag.ORDER) {
            return handleTopKSort(opRef, context);
        }

        return false;
    }

    private boolean handleJoin(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
            throws AlgebricksException {
        AbstractLogicalOperator joinOp = (AbstractLogicalOperator) opRef.getValue();
        if (!Boolean.TRUE.equals(joinOp.getAnnotations().get(PLAQUE_CROSS_EXPR))) {
            return false;
        }
        if (Boolean.TRUE.equals(joinOp.getAnnotations().get("plaque-crossexpr-done"))) {
            return false;
        }

        if (joinOp.getPhysicalOperator() == null
                || joinOp.getPhysicalOperator().getOperatorTag() != PhysicalOperatorTag.HYBRID_HASH_JOIN) {
            return false;
        }

        HybridHashJoinPOperator existingPOp = (HybridHashJoinPOperator) joinOp.getPhysicalOperator();

        // Read all annotations
        String buildSideHandle = (String) joinOp.getAnnotations().get("plaque-crossexpr-build-handle");
        String thresholdHandle = (String) joinOp.getAnnotations().get("plaque-crossexpr-threshold-handle");
        LogicalVariable buildVar = (LogicalVariable) joinOp.getAnnotations().get("plaque-crossexpr-build-var");
        LogicalVariable probeVar = (LogicalVariable) joinOp.getAnnotations().get("plaque-crossexpr-probe-var");
        Boolean trackMin = (Boolean) joinOp.getAnnotations().get("plaque-crossexpr-track-min");
        Integer numPartitions = (Integer) joinOp.getAnnotations().get("plaque-crossexpr-num-partitions");
        PlaqueCrossExprExchangeFilterFactory.CombineOp combineOp =
                (PlaqueCrossExprExchangeFilterFactory.CombineOp)
                        joinOp.getAnnotations().get("plaque-crossexpr-combine-op");
        PlaqueCrossExprExchangeFilterFactory.FilterDirection filterDirection =
                (PlaqueCrossExprExchangeFilterFactory.FilterDirection)
                        joinOp.getAnnotations().get("plaque-crossexpr-filter-direction");

        if (buildSideHandle == null || thresholdHandle == null || buildVar == null || probeVar == null
                || trackMin == null || numPartitions == null || combineOp == null || filterDirection == null) {
            return false;
        }

        // 1. Replace the join's physical operator with the PLAQUE variant
        AbstractBinaryJoinOperator joinLogOp = (AbstractBinaryJoinOperator) joinOp;
        joinOp.setPhysicalOperator(new PlaqueCrossExprHashJoinPOperator(
                joinLogOp.getJoinKind(),
                existingPOp.getPartitioningType(),
                existingPOp.getKeysLeftBranch(),
                existingPOp.getKeysRightBranch(),
                (int) (existingPOp.getFudgeFactor() * 100),
                1,
                existingPOp.getFudgeFactor(),
                buildSideHandle, 0, trackMin, numPartitions));

        // 2. Find the probe-side HASH_PARTITION_EXCHANGE and replace it
        // Determine which input is the probe side using the probeVar
        int probeInputIdx = 0;
        if (probeVar != null) {
            Set<LogicalVariable> input0Vars = new HashSet<>();
            Set<LogicalVariable> input1Vars = new HashSet<>();
            VariableUtilities.getLiveVariables(joinOp.getInputs().get(0).getValue(), input0Vars);
            VariableUtilities.getLiveVariables(joinOp.getInputs().get(1).getValue(), input1Vars);
            if (input1Vars.contains(probeVar)) {
                probeInputIdx = 1;
            }
        }
        System.out.println("PLAQUE_CROSSEXPR_JOBGEN: probe input index = " + probeInputIdx);
        ILogicalOperator current = joinOp.getInputs().get(probeInputIdx).getValue();
        while (current != null) {
            if (current.getOperatorTag() == LogicalOperatorTag.EXCHANGE) {
                AbstractLogicalOperator exchangeOp = (AbstractLogicalOperator) current;
                if (exchangeOp.getPhysicalOperator() != null
                        && exchangeOp.getPhysicalOperator().getOperatorTag()
                        == PhysicalOperatorTag.HASH_PARTITION_EXCHANGE) {
                    HashPartitionExchangePOperator existingExchangePOp =
                            (HashPartitionExchangePOperator) exchangeOp.getPhysicalOperator();
                    exchangeOp.setPhysicalOperator(new PlaqueCrossExprExchangePOperator(
                            existingExchangePOp.getHashFields(),
                            existingExchangePOp.getDomain(),
                            existingExchangePOp.getPartitionsMap(),
                            thresholdHandle, buildSideHandle, 0, combineOp, filterDirection));
                    System.out.println("PLAQUE_CROSSEXPR_JOBGEN: replaced probe exchange physical operator");
                    break;
                }
            }
            if (current.getInputs().isEmpty()) break;
            current = current.getInputs().get(0).getValue();
        }

        joinOp.getAnnotations().put("plaque-crossexpr-done", true);
        System.out.println("PLAQUE_CROSSEXPR_JOBGEN: replaced join physical operator");
        return true;
    }

    private boolean handleTopKSort(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
            throws AlgebricksException {
        AbstractLogicalOperator orderOp = (AbstractLogicalOperator) opRef.getValue();
        if (orderOp.getOperatorTag() != LogicalOperatorTag.ORDER) {
            return false;
        }
        if (Boolean.TRUE.equals(orderOp.getAnnotations().get("plaque-topk-done"))) {
            return false;
        }

        OrderOperator order = (OrderOperator) orderOp;
        if (order.getTopK() <= 0) {
            return false;
        }
        if (orderOp.getPhysicalOperator() == null
                || orderOp.getPhysicalOperator().getOperatorTag() != PhysicalOperatorTag.STABLE_SORT) {
            return false;
        }

        // Walk down to find a join with "plaque-crossexpr-done" — this means the join was
        // already processed and we should replace the sort too.
        ILogicalOperator current = orderOp.getInputs().get(0).getValue();
        String thresholdHandle = null;
        Boolean isMax = null;
        while (current != null) {
            if (current.getOperatorTag() == LogicalOperatorTag.INNERJOIN) {
                AbstractLogicalOperator joinOp = (AbstractLogicalOperator) current;
                if (Boolean.TRUE.equals(joinOp.getAnnotations().get("plaque-crossexpr-done"))) {
                    thresholdHandle = (String) joinOp.getAnnotations().get("plaque-crossexpr-threshold-handle");
                    isMax = true; // DESC Top-K = MAX direction
                }
                break;
            }
            if (current.getInputs().isEmpty()) break;
            current = current.getInputs().get(0).getValue();
        }

        if (thresholdHandle == null || !thresholdHandle.startsWith("plaque-topk-")) {
            return false;
        }

        // Replace the sort's physical operator with PLAQUE variant
        // Must initialize sort columns from the OrderOperator (normally done during property computation)
        PlaqueTopKSortPOperator plaqueSortPOp = new PlaqueTopKSortPOperator(order.getTopK(), thresholdHandle, isMax);
        plaqueSortPOp.computeLocalProperties(order);
        orderOp.setPhysicalOperator(plaqueSortPOp);
        orderOp.getAnnotations().put("plaque-topk-done", true);
        System.out.println("PLAQUE_TOPK_JOBGEN: replaced sort physical operator, topK=" + order.getTopK()
                + " handle=" + thresholdHandle);
        return true;
    }
}
