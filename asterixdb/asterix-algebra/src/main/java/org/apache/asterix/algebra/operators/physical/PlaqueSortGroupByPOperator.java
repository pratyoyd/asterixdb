package org.apache.asterix.algebra.operators.physical;

import java.util.ArrayList;
import java.util.List;

import org.apache.asterix.om.functions.BuiltinFunctions;
import org.apache.asterix.om.types.IAType;
import org.apache.asterix.runtime.operators.plaque.PlaqueGroupByAggregateEvaluatorFactory;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.core.algebra.base.IHyracksJobBuilder;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalExpression;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalPlan;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalVariable;
import org.apache.hyracks.algebricks.core.algebra.expressions.AggregateFunctionCallExpression;
import org.apache.hyracks.algebricks.core.algebra.expressions.IExpressionRuntimeProvider;
import org.apache.hyracks.algebricks.core.algebra.expressions.IPartialAggregationTypeComputer;
import org.apache.hyracks.algebricks.core.algebra.expressions.IVariableTypeEnvironment;
import org.apache.hyracks.algebricks.core.algebra.functions.FunctionIdentifier;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AggregateOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.GroupByOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.IOperatorSchema;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.OrderOperator.IOrder.OrderKind;
import org.apache.hyracks.algebricks.core.algebra.operators.physical.SortGroupByPOperator;
import org.apache.hyracks.algebricks.core.algebra.properties.OrderColumn;
import org.apache.hyracks.algebricks.core.jobgen.impl.JobGenContext;
import org.apache.hyracks.algebricks.core.jobgen.impl.JobGenHelper;
import org.apache.hyracks.algebricks.core.jobgen.impl.OperatorSchemaImpl;
import org.apache.hyracks.algebricks.data.IBinaryComparatorFactoryProvider;
import org.apache.hyracks.algebricks.data.INormalizedKeyComputerFactoryProvider;
import org.apache.hyracks.algebricks.runtime.base.IAggregateEvaluatorFactory;
import org.apache.hyracks.algebricks.runtime.base.IScalarEvaluatorFactory;
import org.apache.hyracks.algebricks.runtime.operators.aggreg.SimpleAlgebricksAccumulatingAggregatorFactory;
import org.apache.hyracks.api.dataflow.value.IBinaryComparatorFactory;
import org.apache.hyracks.api.dataflow.value.INormalizedKeyComputerFactory;
import org.apache.hyracks.api.dataflow.value.RecordDescriptor;
import org.apache.hyracks.api.job.IOperatorDescriptorRegistry;
import org.apache.hyracks.dataflow.std.group.AbstractAggregatorDescriptorFactory;
import org.apache.hyracks.dataflow.std.group.sort.SortGroupByOperatorDescriptor;

/**
 * PLAQUE-aware SortGroupByPOperator. Wraps the aggregate evaluator factory with
 * PlaqueGroupByAggregateEvaluatorFactory so the PLAQUE observer fires during
 * hash-based GROUP BY execution.
 */
public class PlaqueSortGroupByPOperator extends SortGroupByPOperator {

    private final String handle;
    private final boolean isMax;
    private final boolean propagationEnabled;
    private final int propagationInterval;
    private final LogicalVariable groupKeyVar;

    public PlaqueSortGroupByPOperator(List<LogicalVariable> columnList, OrderColumn[] orderColumns, String handle,
            boolean isMax, boolean propagationEnabled, int propagationInterval, LogicalVariable groupKeyVar) {
        super(columnList, orderColumns);
        this.handle = handle;
        this.isMax = isMax;
        this.propagationEnabled = propagationEnabled;
        this.propagationInterval = propagationInterval;
        this.groupKeyVar = groupKeyVar;
    }

    @Override
    public void contributeRuntimeOperator(IHyracksJobBuilder builder, JobGenContext context, ILogicalOperator op,
            IOperatorSchema opSchema, IOperatorSchema[] inputSchemas, IOperatorSchema outerPlanSchema)
            throws AlgebricksException {
        GroupByOperator gby = (GroupByOperator) op;
        checkGroupAll(gby);
        List<LogicalVariable> gbyCols = getGroupByColumns();
        int[] keys = JobGenHelper.variablesToFieldIndexes(gbyCols, inputSchemas[0]);
        int[] fdColumns = getFdColumns(gby, inputSchemas[0]);

        if (gby.getNestedPlans().size() != 1) {
            throw new AlgebricksException(
                    "Sort group-by currently works only for one nested plan with one root containing"
                            + "an aggregate and a nested-tuple-source.");
        }
        ILogicalPlan p0 = gby.getNestedPlans().get(0);
        if (p0.getRoots().size() != 1) {
            throw new AlgebricksException(
                    "Sort group-by currently works only for one nested plan with one root containing"
                            + "an aggregate and a nested-tuple-source.");
        }
        Mutable<ILogicalOperator> r0 = p0.getRoots().get(0);
        AggregateOperator aggOp = (AggregateOperator) r0.getValue();

        IPartialAggregationTypeComputer partialAggregationTypeComputer = context.getPartialAggregationTypeComputer();
        List<Object> intermediateTypes = new ArrayList<>();
        int n = aggOp.getExpressions().size();
        IAggregateEvaluatorFactory[] aff = new IAggregateEvaluatorFactory[n];
        int i = 0;
        IExpressionRuntimeProvider expressionRuntimeProvider = context.getExpressionRuntimeProvider();
        IVariableTypeEnvironment aggOpInputEnv = context.getTypeEnvironment(aggOp.getInputs().get(0).getValue());
        IVariableTypeEnvironment outputEnv = context.getTypeEnvironment(op);
        int groupKeyColumnIndex = inputSchemas[0].findVariable(groupKeyVar);
        for (Mutable<ILogicalExpression> exprRef : aggOp.getExpressions()) {
            AggregateFunctionCallExpression aggFun = (AggregateFunctionCallExpression) exprRef.getValue();
            FunctionIdentifier fid = aggFun.getFunctionIdentifier();
            boolean isPlaqueEligible = groupKeyColumnIndex >= 0
                    && (fid.equals(BuiltinFunctions.LOCAL_SQL_MAX) || fid.equals(BuiltinFunctions.LOCAL_SQL_MIN));
            if (isPlaqueEligible) {
                // Create PLAQUE-aware aggregate factory directly
                boolean isMin = fid.equals(BuiltinFunctions.LOCAL_SQL_MIN);
                IScalarEvaluatorFactory[] scalarArgs = new IScalarEvaluatorFactory[aggFun.getArguments().size()];
                for (int j = 0; j < scalarArgs.length; j++) {
                    scalarArgs[j] = expressionRuntimeProvider.createEvaluatorFactory(
                            aggFun.getArguments().get(j).getValue(), aggOpInputEnv, inputSchemas, context);
                }
                IAType aggFieldType = (IAType) aggOpInputEnv.getType(aggFun.getArguments().get(0).getValue());
                aff[i] = new PlaqueGroupByAggregateEvaluatorFactory(scalarArgs, isMin, aggFun.getSourceLocation(),
                        aggFieldType, handle, isMax, propagationEnabled, propagationInterval, groupKeyColumnIndex);
            } else {
                aff[i] = expressionRuntimeProvider.createAggregateFunctionFactory(aggFun, aggOpInputEnv, inputSchemas,
                        context);
            }
            intermediateTypes
                    .add(partialAggregationTypeComputer.getType(aggFun, aggOpInputEnv, context.getMetadataProvider()));
            i++;
        }

        int[] keyAndDecFields = new int[keys.length + fdColumns.length];
        for (i = 0; i < keys.length; ++i) {
            keyAndDecFields[i] = keys[i];
        }
        for (i = 0; i < fdColumns.length; i++) {
            keyAndDecFields[keys.length + i] = fdColumns[i];
        }

        List<LogicalVariable> keyAndDecVariables = new ArrayList<>();
        for (Pair<LogicalVariable, Mutable<ILogicalExpression>> p : gby.getGroupByList()) {
            keyAndDecVariables.add(p.getLeft());
        }
        for (Pair<LogicalVariable, Mutable<ILogicalExpression>> p : gby.getDecorList()) {
            keyAndDecVariables.add(GroupByOperator.getDecorVariable(p));
        }

        for (LogicalVariable var : keyAndDecVariables) {
            aggOpInputEnv.setVarType(var, outputEnv.getVarType(var));
        }

        compileSubplans(inputSchemas[0], gby, opSchema, context);
        IOperatorDescriptorRegistry spec = builder.getJobSpec();

        IBinaryComparatorFactory[] compFactories = new IBinaryComparatorFactory[gbyCols.size()];
        IBinaryComparatorFactoryProvider bcfProvider = context.getBinaryComparatorFactoryProvider();
        i = 0;
        OrderColumn[] orderColumns = getSortColumns();
        for (LogicalVariable v : gbyCols) {
            Object type = aggOpInputEnv.getVarType(v);
            if (orderColumns[i].getOrder() == OrderKind.ASC) {
                compFactories[i] = bcfProvider.getBinaryComparatorFactory(type, true);
            } else {
                compFactories[i] = bcfProvider.getBinaryComparatorFactory(type, false);
            }
            i++;
        }
        RecordDescriptor recordDescriptor =
                JobGenHelper.mkRecordDescriptor(context.getTypeEnvironment(op), opSchema, context);

        IAggregateEvaluatorFactory[] merges = new IAggregateEvaluatorFactory[n];
        List<LogicalVariable> usedVars = new ArrayList<>();
        IOperatorSchema[] localInputSchemas = new IOperatorSchema[1];
        localInputSchemas[0] = new OperatorSchemaImpl();
        for (i = 0; i < n; i++) {
            AggregateFunctionCallExpression aggFun =
                    (AggregateFunctionCallExpression) aggOp.getMergeExpressions().get(i).getValue();
            aggFun.getUsedVariables(usedVars);
        }
        i = 0;
        for (Object type : intermediateTypes) {
            aggOpInputEnv.setVarType(usedVars.get(i++), type);
        }
        for (LogicalVariable keyVar : keyAndDecVariables) {
            localInputSchemas[0].addVariable(keyVar);
        }
        for (LogicalVariable usedVar : usedVars) {
            localInputSchemas[0].addVariable(usedVar);
        }
        for (i = 0; i < n; i++) {
            AggregateFunctionCallExpression mergeFun =
                    (AggregateFunctionCallExpression) aggOp.getMergeExpressions().get(i).getValue();
            merges[i] = expressionRuntimeProvider.createAggregateFunctionFactory(mergeFun, aggOpInputEnv,
                    localInputSchemas, context);
        }
        RecordDescriptor partialAggRecordDescriptor =
                JobGenHelper.mkRecordDescriptor(context.getTypeEnvironment(op), localInputSchemas[0], context);

        AbstractAggregatorDescriptorFactory aggregatorFactory =
                new SimpleAlgebricksAccumulatingAggregatorFactory(aff, keyAndDecFields);
        aggregatorFactory.setSourceLocation(gby.getSourceLocation());
        AbstractAggregatorDescriptorFactory mergeFactory =
                new SimpleAlgebricksAccumulatingAggregatorFactory(merges, keyAndDecFields);
        mergeFactory.setSourceLocation(gby.getSourceLocation());

        INormalizedKeyComputerFactory normalizedKeyFactory = null;
        INormalizedKeyComputerFactoryProvider nkcfProvider = context.getNormalizedKeyComputerFactoryProvider();
        if (nkcfProvider == null) {
            normalizedKeyFactory = null;
        }
        Object type = aggOpInputEnv.getVarType(gbyCols.get(0));
        normalizedKeyFactory =
                orderColumns[0].getOrder() == OrderKind.ASC ? nkcfProvider.getNormalizedKeyComputerFactory(type, true)
                        : nkcfProvider.getNormalizedKeyComputerFactory(type, false);
        int framesLimit = localMemoryRequirements.getMemoryBudgetInFrames();
        boolean isGlobalGBY = false;
        if (op.getAnnotations().containsKey("IS_EXTERNAL_GBY")) {
            if ((Boolean) op.getAnnotations().get("IS_EXTERNAL_GBY") == true
                    && context.getPhysicalOptimizationConfig().getBlockingMode())
                isGlobalGBY = true;
        }
        SortGroupByOperatorDescriptor gbyOpDesc = new SortGroupByOperatorDescriptor(spec, framesLimit, keys,
                keyAndDecFields, normalizedKeyFactory, compFactories, aggregatorFactory, mergeFactory,
                partialAggRecordDescriptor, recordDescriptor, false, isGlobalGBY);
        gbyOpDesc.setSourceLocation(gby.getSourceLocation());

        contributeOpDesc(builder, gby, gbyOpDesc);
        ILogicalOperator src = op.getInputs().get(0).getValue();
        builder.contributeGraphEdge(src, 0, op, 0);
    }
}
