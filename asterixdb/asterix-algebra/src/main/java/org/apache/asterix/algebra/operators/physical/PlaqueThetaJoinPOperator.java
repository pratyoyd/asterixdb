package org.apache.asterix.algebra.operators.physical;

import org.apache.asterix.runtime.operators.plaque.PlaqueThetaJoinMismatchWriterFactory;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.common.exceptions.NotImplementedException;
import org.apache.hyracks.algebricks.core.algebra.base.IHyracksJobBuilder;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalVariable;
import org.apache.hyracks.algebricks.core.algebra.base.PhysicalOperatorTag;
import org.apache.hyracks.algebricks.core.algebra.expressions.IExpressionRuntimeProvider;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractBinaryJoinOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractBinaryJoinOperator.JoinKind;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.IOperatorSchema;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.LeftOuterJoinOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.physical.AbstractJoinPOperator;
import org.apache.hyracks.algebricks.core.jobgen.impl.JobGenContext;
import org.apache.hyracks.algebricks.core.jobgen.impl.JobGenHelper;
import org.apache.hyracks.algebricks.runtime.base.IScalarEvaluatorFactory;
import org.apache.hyracks.algebricks.runtime.evaluators.TuplePairEvaluatorFactory;
import org.apache.hyracks.api.dataflow.IOperatorDescriptor;
import org.apache.hyracks.api.dataflow.value.IMissingWriterFactory;
import org.apache.hyracks.api.dataflow.value.ITuplePairComparatorFactory;
import org.apache.hyracks.api.dataflow.value.RecordDescriptor;
import org.apache.hyracks.api.job.IOperatorDescriptorRegistry;
import org.apache.hyracks.dataflow.std.join.INLJMismatchWriterFactory;
import org.apache.hyracks.dataflow.std.join.NestedLoopJoinOperatorDescriptor;

/**
 * Physical operator for nested loop join with PLAQUE theta-join mismatch observation.
 * Extends the standard NLJ behavior by injecting a mismatch writer that updates
 * the PLAQUE threshold whenever an outer tuple fails to match any inner tuple.
 */
public class PlaqueThetaJoinPOperator extends AbstractJoinPOperator {

    private final String handle;
    private final boolean isMax;
    private final boolean strictComparison;
    private final LogicalVariable outerJoinVar;
    private final int eagerBatchSize;

    public PlaqueThetaJoinPOperator(JoinKind kind, JoinPartitioningType partitioningType,
            String handle, boolean isMax, boolean strictComparison, LogicalVariable outerJoinVar,
            int eagerBatchSize) {
        super(kind, partitioningType);
        this.handle = handle;
        this.isMax = isMax;
        this.strictComparison = strictComparison;
        this.outerJoinVar = outerJoinVar;
        this.eagerBatchSize = eagerBatchSize;
    }

    @Override
    public PhysicalOperatorTag getOperatorTag() {
        return PhysicalOperatorTag.NESTED_LOOP;
    }

    @Override
    public boolean isMicroOperator() {
        return false;
    }

    @Override
    public void computeDeliveredProperties(ILogicalOperator iop,
            org.apache.hyracks.algebricks.core.algebra.base.IOptimizationContext context) {
        // Same as NestedLoopJoinPOperator: delivered properties match left input
        org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator op =
                (org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator) iop;
        org.apache.hyracks.algebricks.core.algebra.properties.IPartitioningProperty pp;
        if (op.getExecutionMode()
                == org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator.ExecutionMode.PARTITIONED) {
            org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator leftOp =
                    (org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator) op
                            .getInputs().get(0).getValue();
            org.apache.hyracks.algebricks.core.algebra.properties.IPhysicalPropertiesVector leftProps =
                    leftOp.getPhysicalOperator().getDeliveredProperties();
            pp = leftProps == null ? null : leftProps.getPartitioningProperty();
        } else {
            pp = org.apache.hyracks.algebricks.core.algebra.properties.IPartitioningProperty.UNPARTITIONED;
        }
        this.deliveredProperties =
                new org.apache.hyracks.algebricks.core.algebra.properties.StructuralPropertiesVector(pp, null);
    }

    @Override
    public org.apache.hyracks.algebricks.core.algebra.properties.PhysicalRequirements getRequiredPropertiesForChildren(
            ILogicalOperator op,
            org.apache.hyracks.algebricks.core.algebra.properties.IPhysicalPropertiesVector reqdByParent,
            org.apache.hyracks.algebricks.core.algebra.base.IOptimizationContext context) {
        // Same as NestedLoopJoinPOperator: left=RANDOM, right=BROADCAST
        org.apache.hyracks.algebricks.core.algebra.properties.StructuralPropertiesVector[] pv =
                new org.apache.hyracks.algebricks.core.algebra.properties.StructuralPropertiesVector[2];
        pv[0] = org.apache.hyracks.algebricks.core.algebra.util.OperatorPropertiesUtil
                .checkUnpartitionedAndGetPropertiesVector(op,
                        new org.apache.hyracks.algebricks.core.algebra.properties.StructuralPropertiesVector(
                                new org.apache.hyracks.algebricks.core.algebra.properties.RandomPartitioningProperty(
                                        context.getComputationNodeDomain()),
                                null));
        pv[1] = org.apache.hyracks.algebricks.core.algebra.util.OperatorPropertiesUtil
                .checkUnpartitionedAndGetPropertiesVector(op,
                        new org.apache.hyracks.algebricks.core.algebra.properties.StructuralPropertiesVector(
                                new org.apache.hyracks.algebricks.core.algebra.properties.BroadcastPartitioningProperty(
                                        context.getComputationNodeDomain()),
                                null));
        return new org.apache.hyracks.algebricks.core.algebra.properties.PhysicalRequirements(pv,
                org.apache.hyracks.algebricks.core.algebra.properties.IPartitioningRequirementsCoordinator.NO_COORDINATION);
    }

    @Override
    public void contributeRuntimeOperator(IHyracksJobBuilder builder, JobGenContext context, ILogicalOperator op,
            IOperatorSchema propagatedSchema, IOperatorSchema[] inputSchemas, IOperatorSchema outerPlanSchema)
            throws AlgebricksException {
        AbstractBinaryJoinOperator join = (AbstractBinaryJoinOperator) op;
        RecordDescriptor recDescriptor =
                JobGenHelper.mkRecordDescriptor(context.getTypeEnvironment(op), propagatedSchema, context);
        IOperatorSchema[] conditionInputSchemas = new IOperatorSchema[1];
        conditionInputSchemas[0] = propagatedSchema;
        IExpressionRuntimeProvider expressionRuntimeProvider = context.getExpressionRuntimeProvider();
        IScalarEvaluatorFactory cond = expressionRuntimeProvider.createEvaluatorFactory(join.getCondition().getValue(),
                context.getTypeEnvironment(op), conditionInputSchemas, context);
        ITuplePairComparatorFactory comparatorFactory =
                new TuplePairEvaluatorFactory(cond, false, context.getBinaryBooleanInspectorFactory());
        IOperatorDescriptorRegistry spec = builder.getJobSpec();

        // Resolve the outer join column index from input schema 0 (outer/probe side)
        int outerColIdx = inputSchemas[0].findVariable(outerJoinVar);
        if (outerColIdx < 0) {
            throw new AlgebricksException(
                    "PLAQUE theta join: outer join variable not found in input schema: " + outerJoinVar);
        }

        INLJMismatchWriterFactory mismatchFactory =
                new PlaqueThetaJoinMismatchWriterFactory(handle, isMax, strictComparison, outerColIdx);

        int memSize = localMemoryRequirements.getMemoryBudgetInFrames();
        IOperatorDescriptor opDesc;
        switch (kind) {
            case INNER:
                opDesc = new NestedLoopJoinOperatorDescriptor(spec, comparatorFactory, recDescriptor, memSize, false,
                        null, mismatchFactory, eagerBatchSize);
                break;
            case LEFT_OUTER:
                IMissingWriterFactory[] nonMatchWriterFactories = JobGenHelper.createMissingWriterFactories(context,
                        ((LeftOuterJoinOperator) join).getMissingValue(), inputSchemas[1].getSize());
                opDesc = new NestedLoopJoinOperatorDescriptor(spec, comparatorFactory, recDescriptor, memSize, true,
                        nonMatchWriterFactories, mismatchFactory, eagerBatchSize);
                break;
            default:
                throw new NotImplementedException();
        }

        opDesc.setSourceLocation(join.getSourceLocation());
        contributeOpDesc(builder, join, opDesc);

        ILogicalOperator src1 = op.getInputs().get(0).getValue();
        builder.contributeGraphEdge(src1, 0, op, 0);
        ILogicalOperator src2 = op.getInputs().get(1).getValue();
        builder.contributeGraphEdge(src2, 0, op, 1);
    }
}
