package org.apache.hyracks.algebricks.rewriter.rules;

import org.apache.commons.lang3.mutable.Mutable;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.base.IOptimizationContext;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalOperatorTag;
import org.apache.hyracks.algebricks.core.rewriter.base.IAlgebraicRewriteRule;

public class IntroduceInteractiveSelectAccessMethod  implements IAlgebraicRewriteRule {
    @Override
    public boolean rewritePre(Mutable<ILogicalOperator> opRef, IOptimizationContext context) throws AlgebricksException {
        if (!context.getPhysicalOptimizationConfig().getInteractiveMode()) {
            return false;
        }
        ILogicalOperator op = opRef.getValue();
        LogicalOperatorTag opTag = op.getOperatorTag();
        if(opTag != LogicalOperatorTag.UNIONALL)return false;
        Mutable<ILogicalOperator> inputOpRef = op.getInputs().get(0);
        boolean planTransformed = checkAndApplyTheSelectTransformation(opRef, context);



        return false;
    }
/*
First we will try to optimize for group by, without any join.
 */
    private boolean checkAndApplyTheSelectTransformation(Mutable<ILogicalOperator> opRef, IOptimizationContext context) {
        ILogicalOperator op = opRef.getValue();
        if(op.getOperatorTag() == LogicalOperatorTag.GROUP){

        }



        return false;

    }

    @Override
    public boolean rewritePost(Mutable<ILogicalOperator> opRef, IOptimizationContext context){return false;}

}
