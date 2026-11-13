package org.apache.hyracks.algebricks.rewriter.rules;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalExpression;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalPlan;
import org.apache.hyracks.algebricks.core.algebra.base.IOptimizationContext;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalOperatorTag;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalVariable;
import org.apache.hyracks.algebricks.core.algebra.base.PhysicalOperatorTag;
import org.apache.hyracks.algebricks.core.algebra.expressions.VariableReferenceExpression;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator.ExecutionMode;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AggregateOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.GroupByOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.NestedTupleSourceOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.OrderOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.OrderOperator.IOrder;
import org.apache.hyracks.algebricks.core.algebra.operators.physical.MicroPreclusteredGroupByPOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.physical.MicroStableSortPOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.physical.NestedTupleSourcePOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.physical.PreclusteredGroupByPOperator;
import org.apache.hyracks.algebricks.core.algebra.plan.ALogicalPlanImpl;
import org.apache.hyracks.algebricks.core.rewriter.base.IAlgebraicRewriteRule;
import org.apache.hyracks.api.exceptions.SourceLocation;

public class RewriteMultiColumnGroupByRule implements IAlgebraicRewriteRule {

    @Override
    public boolean rewritePre(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
            throws AlgebricksException {
        return false;
    }

    @Override
    public boolean rewritePost(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
            throws AlgebricksException {
        if (!context.getPhysicalOptimizationConfig().getInteractiveMode())
            return false;
        AbstractLogicalOperator op = (AbstractLogicalOperator) opRef.getValue();
        if (op.getOperatorTag() != LogicalOperatorTag.GROUP) {
            return false;
        }
        if (op.getPhysicalOperator() == null
                || op.getPhysicalOperator().getOperatorTag() != PhysicalOperatorTag.PRE_CLUSTERED_GROUP_BY) {
            return false;
        }
        GroupByOperator gby = (GroupByOperator) op;
        if (gby.isGroupAll() || gby.isGlobal()) {
            return false;
        }
        List<Pair<LogicalVariable, Mutable<ILogicalExpression>>> gList = gby.getGroupByList();
        if (gList.size() < 2) {
            return false;
        }
        if (gby.getNestedPlans().size() != 1) {
            return false;
        }
        ILogicalPlan nestedPlan = gby.getNestedPlans().get(0);
        if (nestedPlan.getRoots().size() != 1) {
            return false;
        }
        AbstractLogicalOperator root = (AbstractLogicalOperator) nestedPlan.getRoots().get(0).getValue();
        if (root.getOperatorTag() != LogicalOperatorTag.AGGREGATE) {
            return false;
        }
        AggregateOperator agg = (AggregateOperator) root;
        SourceLocation sourceLoc = gby.getSourceLocation();

        List<Pair<LogicalVariable, Mutable<ILogicalExpression>>> innerGByList = new ArrayList<>();
        for (int i = 1; i < gList.size(); i++) {
            Pair<LogicalVariable, Mutable<ILogicalExpression>> p = gList.get(i);
            innerGByList.add(Pair.of(p.getLeft(), new MutableObject<>(p.getRight().getValue().cloneExpression())));
        }
        gList.subList(1, gList.size()).clear();

        GroupByOperator innerGby = new GroupByOperator(innerGByList, new ArrayList<>(), new ArrayList<>());
        innerGby.setSourceLocation(sourceLoc);
        innerGby.setGlobal(false);
        innerGby.setExecutionMode(ExecutionMode.LOCAL);
        innerGby.setPhysicalOperator(new MicroPreclusteredGroupByPOperator(innerGby.getGroupByVarList()));

        Mutable<ILogicalOperator> aggInputRef = agg.getInputs().get(0);
        NestedTupleSourceOperator oldNts = (NestedTupleSourceOperator) aggInputRef.getValue();
        oldNts.getDataSourceReference().setValue(innerGby);

        ILogicalPlan innerPlan = new ALogicalPlanImpl(new MutableObject<>(agg));
        innerGby.getNestedPlans().add(innerPlan);

        List<Pair<IOrder, Mutable<ILogicalExpression>>> orderExprs = new ArrayList<>();
        for (LogicalVariable v : innerGby.getGroupByVarList()) {
            VariableReferenceExpression vref = new VariableReferenceExpression(v);
            vref.setSourceLocation(sourceLoc);
            orderExprs.add(Pair.of(OrderOperator.ASC_ORDER, new MutableObject<>(vref)));
        }
        OrderOperator order = new OrderOperator(orderExprs);
        order.setSourceLocation(sourceLoc);
        order.setExecutionMode(ExecutionMode.LOCAL);
        order.setPhysicalOperator(new MicroStableSortPOperator());

        NestedTupleSourceOperator nts = new NestedTupleSourceOperator(new MutableObject<>(gby));
        nts.setSourceLocation(sourceLoc);
        nts.setExecutionMode(ExecutionMode.LOCAL);
        nts.setPhysicalOperator(new NestedTupleSourcePOperator());
        order.getInputs().add(new MutableObject<>(nts));

        innerGby.getInputs().add(new MutableObject<>(order));

        gby.getNestedPlans().clear();
        gby.getNestedPlans().add(new ALogicalPlanImpl(new MutableObject<>(innerGby)));

        gby.setPhysicalOperator(new PreclusteredGroupByPOperator(gby.getGroupByVarList(), false));
        gby.getAnnotations().put("isRewrittenMultiGroup", true);

        gby.setExecutionMode(ExecutionMode.LOCAL);

        context.computeAndSetTypeEnvironmentForOperator(nts);
        nts.computeDeliveredPhysicalProperties(context);
        context.computeAndSetTypeEnvironmentForOperator(order);
        order.computeDeliveredPhysicalProperties(context);
        context.computeAndSetTypeEnvironmentForOperator(agg);
        agg.computeDeliveredPhysicalProperties(context);
        context.computeAndSetTypeEnvironmentForOperator(innerGby);
        innerGby.computeDeliveredPhysicalProperties(context);
        context.computeAndSetTypeEnvironmentForOperator(gby);
        gby.computeDeliveredPhysicalProperties(context);

        return true;
    }
}
