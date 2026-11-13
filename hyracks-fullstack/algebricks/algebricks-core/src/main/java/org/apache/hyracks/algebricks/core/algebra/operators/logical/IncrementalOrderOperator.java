package org.apache.hyracks.algebricks.core.algebra.operators.logical;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalExpression;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalOperatorTag;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalVariable;
import org.apache.hyracks.algebricks.core.algebra.visitors.ILogicalExpressionReferenceTransform;
import org.apache.hyracks.algebricks.core.algebra.visitors.ILogicalOperatorVisitor;

public class IncrementalOrderOperator extends OrderOperator {

    private List<LogicalVariable> groupByColumns;

    public IncrementalOrderOperator(List<Pair<OrderOperator.IOrder, Mutable<ILogicalExpression>>> orderExpressions,
            List<LogicalVariable> groupByColumns) {
        super(orderExpressions);
        this.groupByColumns = groupByColumns;

    }

    public IncrementalOrderOperator(List<Pair<IOrder, Mutable<ILogicalExpression>>> orderExpressions,
            List<LogicalVariable> groupByColumns, int topK) {
        super(orderExpressions, topK);
        this.groupByColumns = groupByColumns;
    }

    @Override
    public LogicalOperatorTag getOperatorTag() {
        return LogicalOperatorTag.INCREMENTAL_SORT;

    }

    public List<LogicalVariable> getGroupByColumns() {
        return groupByColumns;
    }

    @Override
    public void recomputeSchema() {
        schema = new ArrayList<>(groupByColumns);
        schema.addAll(inputs.get(0).getValue().getSchema());
    }

    @Override
    public boolean acceptExpressionTransform(ILogicalExpressionReferenceTransform visitor) throws AlgebricksException {
        boolean transformed = super.acceptExpressionTransform(visitor);
        return transformed;
    }

    @Override
    public <R, T> R accept(ILogicalOperatorVisitor<R, T> visitor, T arg) throws AlgebricksException {
        return visitor.visitIncrementalOrderOperator(this, arg);
    }

}
