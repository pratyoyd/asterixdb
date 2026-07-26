package org.apache.asterix.algebra.operators.physical;

import java.util.List;

import org.apache.asterix.runtime.operators.plaque.PlaqueBuildSideObserverFactory;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.common.exceptions.NotImplementedException;
import org.apache.hyracks.algebricks.core.algebra.base.IHyracksJobBuilder;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalVariable;
import org.apache.hyracks.algebricks.core.algebra.expressions.IExpressionRuntimeProvider;
import org.apache.hyracks.algebricks.core.algebra.expressions.IVariableTypeEnvironment;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractBinaryJoinOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractBinaryJoinOperator.JoinKind;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.IOperatorSchema;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.LeftOuterJoinOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.physical.HybridHashJoinPOperator;
import org.apache.hyracks.algebricks.core.jobgen.impl.JobGenContext;
import org.apache.hyracks.algebricks.core.jobgen.impl.JobGenHelper;
import org.apache.hyracks.algebricks.runtime.base.IScalarEvaluatorFactory;
import org.apache.hyracks.algebricks.runtime.evaluators.TuplePairEvaluatorFactory;
import org.apache.hyracks.api.dataflow.IOperatorDescriptor;
import org.apache.hyracks.api.dataflow.value.IBinaryHashFunctionFamily;
import org.apache.hyracks.api.dataflow.value.IMissingWriterFactory;
import org.apache.hyracks.api.dataflow.value.IPredicateEvaluatorFactory;
import org.apache.hyracks.api.dataflow.value.IPredicateEvaluatorFactoryProvider;
import org.apache.hyracks.api.dataflow.value.ITuplePairComparatorFactory;
import org.apache.hyracks.api.dataflow.value.RecordDescriptor;
import org.apache.hyracks.api.job.IOperatorDescriptorRegistry;
import org.apache.hyracks.dataflow.std.join.IBuildSideObserverFactory;
import org.apache.hyracks.dataflow.std.join.OptimizedHybridHashJoinOperatorDescriptor;

/**
 * Physical operator for hash join with PLAQUE build-side observation.
 * Extends standard HybridHashJoinPOperator to inject a build-side observer
 * that tracks MIN/MAX of a build column per NC.
 */
public class PlaqueCrossExprHashJoinPOperator extends HybridHashJoinPOperator {

    private final String handle;
    private final int buildColumnIdx;
    private final boolean trackMin;
    private final int numPartitions;

    public PlaqueCrossExprHashJoinPOperator(JoinKind kind, JoinPartitioningType partitioningType,
            List<LogicalVariable> sideLeftOfEqualities, List<LogicalVariable> sideRightOfEqualities,
            int maxInputSizeInFrames, int aveRecordsPerFrame, double fudgeFactor,
            String handle, int buildColumnIdx, boolean trackMin, int numPartitions) {
        super(kind, partitioningType, sideLeftOfEqualities, sideRightOfEqualities,
                maxInputSizeInFrames, aveRecordsPerFrame, fudgeFactor);
        this.handle = handle;
        this.buildColumnIdx = buildColumnIdx;
        this.trackMin = trackMin;
        this.numPartitions = numPartitions;
    }

    @Override
    public void contributeRuntimeOperator(IHyracksJobBuilder builder, JobGenContext context, ILogicalOperator op,
            IOperatorSchema propagatedSchema, IOperatorSchema[] inputSchemas, IOperatorSchema outerPlanSchema)
            throws AlgebricksException {
        int[] keysLeft = JobGenHelper.variablesToFieldIndexes(getKeysLeftBranch(), inputSchemas[0]);
        int[] keysRight = JobGenHelper.variablesToFieldIndexes(getKeysRightBranch(), inputSchemas[1]);
        IVariableTypeEnvironment env = context.getTypeEnvironment(op);
        IBinaryHashFunctionFamily[] leftHashFunFamilies =
                JobGenHelper.variablesToBinaryHashFunctionFamilies(getKeysLeftBranch(), env, context);
        IBinaryHashFunctionFamily[] rightHashFunFamilies =
                JobGenHelper.variablesToBinaryHashFunctionFamilies(getKeysRightBranch(), env, context);

        IPredicateEvaluatorFactoryProvider predEvalFactoryProvider = context.getPredicateEvaluatorFactoryProvider();
        IPredicateEvaluatorFactory leftPredEvalFactory =
                predEvalFactoryProvider == null ? null : predEvalFactoryProvider.getPredicateEvaluatorFactory(keysLeft);
        IPredicateEvaluatorFactory rightPredEvalFactory = predEvalFactoryProvider == null ? null
                : predEvalFactoryProvider.getPredicateEvaluatorFactory(keysRight);

        RecordDescriptor recDescriptor =
                JobGenHelper.mkRecordDescriptor(context.getTypeEnvironment(op), propagatedSchema, context);
        IOperatorSchema[] conditionInputSchemas = new IOperatorSchema[1];
        conditionInputSchemas[0] = propagatedSchema;
        IExpressionRuntimeProvider expressionRuntimeProvider = context.getExpressionRuntimeProvider();
        AbstractBinaryJoinOperator joinOp = (AbstractBinaryJoinOperator) op;
        IScalarEvaluatorFactory cond = expressionRuntimeProvider.createEvaluatorFactory(
                joinOp.getCondition().getValue(), context.getTypeEnvironment(op), conditionInputSchemas, context);
        ITuplePairComparatorFactory comparatorFactory =
                new TuplePairEvaluatorFactory(cond, false, context.getBinaryBooleanInspectorFactory());
        ITuplePairComparatorFactory reverseComparatorFactory =
                new TuplePairEvaluatorFactory(cond, true, context.getBinaryBooleanInspectorFactory());
        IOperatorDescriptorRegistry spec = builder.getJobSpec();

        // Create the build-side observer factory
        IBuildSideObserverFactory observerFactory =
                new PlaqueBuildSideObserverFactory(handle, buildColumnIdx, trackMin, numPartitions);

        int memSizeInFrames = localMemoryRequirements.getMemoryBudgetInFrames();
        IOperatorDescriptor opDesc;
        switch (kind) {
            case INNER:
                opDesc = new OptimizedHybridHashJoinOperatorDescriptor(spec, memSizeInFrames,
                        (int) (memSizeInFrames * getFudgeFactor()), getFudgeFactor(),
                        keysLeft, keysRight, leftHashFunFamilies, rightHashFunFamilies, recDescriptor,
                        comparatorFactory, reverseComparatorFactory, leftPredEvalFactory, rightPredEvalFactory,
                        false, null, observerFactory);
                break;
            case LEFT_OUTER:
                IMissingWriterFactory[] nonMatchWriterFactories = JobGenHelper.createMissingWriterFactories(context,
                        ((LeftOuterJoinOperator) joinOp).getMissingValue(), inputSchemas[1].getSize());
                opDesc = new OptimizedHybridHashJoinOperatorDescriptor(spec, memSizeInFrames,
                        (int) (memSizeInFrames * getFudgeFactor()), getFudgeFactor(),
                        keysLeft, keysRight, leftHashFunFamilies, rightHashFunFamilies, recDescriptor,
                        comparatorFactory, reverseComparatorFactory, leftPredEvalFactory, rightPredEvalFactory,
                        true, nonMatchWriterFactories, observerFactory);
                break;
            default:
                throw new NotImplementedException();
        }

        opDesc.setSourceLocation(joinOp.getSourceLocation());
        contributeOpDesc(builder, (AbstractLogicalOperator) op, opDesc);

        ILogicalOperator src1 = op.getInputs().get(0).getValue();
        builder.contributeGraphEdge(src1, 0, op, 0);
        ILogicalOperator src2 = op.getInputs().get(1).getValue();
        builder.contributeGraphEdge(src2, 0, op, 1);
    }
}
