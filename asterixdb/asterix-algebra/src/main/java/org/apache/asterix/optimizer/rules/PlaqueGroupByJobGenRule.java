package org.apache.asterix.optimizer.rules;

import org.apache.asterix.algebra.operators.physical.PlaqueSortGroupByPOperator;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.base.IOptimizationContext;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalOperatorTag;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalVariable;
import org.apache.hyracks.algebricks.core.algebra.base.PhysicalOperatorTag;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.GroupByOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.physical.SortGroupByPOperator;
import org.apache.hyracks.algebricks.core.rewriter.base.IAlgebraicRewriteRule;

/**
 * Runs AFTER PushGroupByIntoSortRule. If a GROUP BY has PLAQUE group-by annotations
 * and PushGroupByIntoSortRule converted it to SortGroupByPOperator, replace with
 * PlaqueSortGroupByPOperator which wraps the aggregate factory with PLAQUE observer.
 */
public class PlaqueGroupByJobGenRule implements IAlgebraicRewriteRule {

    @Override
    public boolean rewritePre(Mutable<ILogicalOperator> opRef, IOptimizationContext context) {
        return false;
    }

    @Override
    public boolean rewritePost(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
            throws AlgebricksException {
        AbstractLogicalOperator op = (AbstractLogicalOperator) opRef.getValue();
        if (op.getOperatorTag() != LogicalOperatorTag.GROUP) {
            return false;
        }
        if (!Boolean.TRUE.equals(op.getAnnotations().get("plaque-groupby-aggregate"))) {
            return false;
        }
        if (op.getPhysicalOperator() == null
                || op.getPhysicalOperator().getOperatorTag() != PhysicalOperatorTag.SORT_GROUP_BY) {
            return false;
        }
        // Already replaced
        if (op.getPhysicalOperator() instanceof PlaqueSortGroupByPOperator) {
            return false;
        }

        GroupByOperator gby = (GroupByOperator) op;
        SortGroupByPOperator sortGby = (SortGroupByPOperator) op.getPhysicalOperator();

        String handle = (String) op.getAnnotations().get("plaque-handle");
        Boolean isMax = (Boolean) op.getAnnotations().get("plaque-isMax");
        LogicalVariable groupKeyVar = (LogicalVariable) op.getAnnotations().get("plaque-groupKeyVar");

        if (handle == null || isMax == null || groupKeyVar == null) {
            return false;
        }

        boolean propagationEnabled = context.getPhysicalOptimizationConfig().getPlaquePropagation();
        int propagationInterval = context.getPhysicalOptimizationConfig().getPlaquePropagationInterval();

        op.setPhysicalOperator(new PlaqueSortGroupByPOperator(gby.getGroupByVarList(), sortGby.getSortColumns(), handle,
                isMax, propagationEnabled, propagationInterval, groupKeyVar));

        return true;
    }
}
