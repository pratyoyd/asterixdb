package org.apache.asterix.algebra.operators.physical;

import java.util.List;

import org.apache.asterix.om.functions.BuiltinFunctions;
import org.apache.asterix.om.types.IAType;
import org.apache.asterix.runtime.operators.plaque.PlaqueLocalAggregateEvaluatorFactory;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.core.algebra.base.IHyracksJobBuilder;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalExpression;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.expressions.AggregateFunctionCallExpression;
import org.apache.hyracks.algebricks.core.algebra.expressions.IExpressionRuntimeProvider;
import org.apache.hyracks.algebricks.core.algebra.functions.FunctionIdentifier;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AggregateOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.IOperatorSchema;
import org.apache.hyracks.algebricks.core.algebra.operators.physical.AggregatePOperator;
import org.apache.hyracks.algebricks.core.jobgen.impl.JobGenContext;
import org.apache.hyracks.algebricks.core.jobgen.impl.JobGenHelper;
import org.apache.hyracks.algebricks.runtime.base.IAggregateEvaluatorFactory;
import org.apache.hyracks.algebricks.runtime.base.IScalarEvaluatorFactory;
import org.apache.hyracks.algebricks.runtime.operators.aggreg.AggregateRuntimeFactory;
import org.apache.hyracks.api.dataflow.value.RecordDescriptor;

public class PlaqueAggregatePOperator extends AggregatePOperator {

    private final String handle;
    private final boolean isMax;
    private final boolean propagationEnabled;
    private final int propagationInterval;

    public PlaqueAggregatePOperator(String handle, boolean isMax,
            boolean propagationEnabled, int propagationInterval) {
        this.handle = handle;
        this.isMax = isMax;
        this.propagationEnabled = propagationEnabled;
        this.propagationInterval = propagationInterval;
    }

    @Override
    public void contributeRuntimeOperator(IHyracksJobBuilder builder, JobGenContext context, ILogicalOperator op,
            IOperatorSchema opSchema, IOperatorSchema[] inputSchemas, IOperatorSchema outerPlanSchema)
            throws AlgebricksException {

        AggregateOperator aggOp = (AggregateOperator) op;
        List<Mutable<ILogicalExpression>> expressions = aggOp.getExpressions();
        IAggregateEvaluatorFactory[] aggFactories = new IAggregateEvaluatorFactory[expressions.size()];

        IExpressionRuntimeProvider exprProvider = context.getExpressionRuntimeProvider();
        ILogicalOperator inputOp = aggOp.getInputs().get(0).getValue();

        for (int i = 0; i < aggFactories.length; i++) {
            AggregateFunctionCallExpression aggFun =
                    (AggregateFunctionCallExpression) expressions.get(i).getValue();

            FunctionIdentifier fid = aggFun.getFunctionIdentifier();
            boolean isMin = fid.equals(BuiltinFunctions.LOCAL_SQL_MIN);

            IScalarEvaluatorFactory[] scalarArgs = new IScalarEvaluatorFactory[aggFun.getArguments().size()];
            for (int j = 0; j < scalarArgs.length; j++) {
                scalarArgs[j] = exprProvider.createEvaluatorFactory(aggFun.getArguments().get(j).getValue(),
                        context.getTypeEnvironment(inputOp), inputSchemas, context);
            }

            IAType aggFieldType =
                    (IAType) context.getTypeEnvironment(inputOp).getType(aggFun.getArguments().get(0).getValue());

            aggFactories[i] = new PlaqueLocalAggregateEvaluatorFactory(scalarArgs, isMin,
                    aggFun.getSourceLocation(), aggFieldType, handle, isMax,
                    propagationEnabled, propagationInterval);
        }

        AggregateRuntimeFactory runtime = new AggregateRuntimeFactory(aggFactories);
        RecordDescriptor recDesc = JobGenHelper.mkRecordDescriptor(
                context.getTypeEnvironment(op), opSchema, context);
        builder.contributeMicroOperator(aggOp, runtime, recDesc);
        ILogicalOperator src = aggOp.getInputs().get(0).getValue();
        builder.contributeGraphEdge(src, 0, aggOp, 0);
    }

    @Override
    public String toString() {
        return "PLAQUE_AGGREGATE(" + (isMax ? "MAX" : "MIN") + ", " + handle + ")";
    }
}
