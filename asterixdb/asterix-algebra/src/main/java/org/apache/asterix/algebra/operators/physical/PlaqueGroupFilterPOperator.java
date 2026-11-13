package org.apache.asterix.algebra.operators.physical;

import org.apache.asterix.runtime.operators.plaque.PlaqueGroupFilterRuntimeFactory;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.core.algebra.base.IHyracksJobBuilder;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.base.IOptimizationContext;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalVariable;
import org.apache.hyracks.algebricks.core.algebra.base.PhysicalOperatorTag;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.IOperatorSchema;
import org.apache.hyracks.algebricks.core.algebra.operators.physical.AbstractPhysicalOperator;
import org.apache.hyracks.algebricks.core.algebra.properties.IPartitioningRequirementsCoordinator;
import org.apache.hyracks.algebricks.core.algebra.properties.IPhysicalPropertiesVector;
import org.apache.hyracks.algebricks.core.algebra.properties.PhysicalRequirements;
import org.apache.hyracks.algebricks.core.algebra.properties.StructuralPropertiesVector;
import org.apache.hyracks.algebricks.core.jobgen.impl.JobGenContext;
import org.apache.hyracks.algebricks.core.jobgen.impl.JobGenHelper;
import org.apache.hyracks.api.dataflow.value.RecordDescriptor;

/**
 * Physical operator for PLAQUE GROUP BY filter. Similar to PlaqueFilterPOperator but carries
 * both filteredVar (aggregate column) and groupKeyVar (group column), resolving both column
 * indices from the input schema.
 */
public class PlaqueGroupFilterPOperator extends AbstractPhysicalOperator {

    private final LogicalVariable filteredVar;
    private final LogicalVariable groupKeyVar;
    private final boolean isMax;
    private final String handle;
    private final boolean requiresPartitioning;

    public PlaqueGroupFilterPOperator(LogicalVariable filteredVar, LogicalVariable groupKeyVar, boolean isMax,
            String handle) {
        this(filteredVar, groupKeyVar, isMax, handle, false);
    }

    public PlaqueGroupFilterPOperator(LogicalVariable filteredVar, LogicalVariable groupKeyVar, boolean isMax,
            String handle, boolean requiresPartitioning) {
        this.filteredVar = filteredVar;
        this.groupKeyVar = groupKeyVar;
        this.isMax = isMax;
        this.handle = handle;
        this.requiresPartitioning = requiresPartitioning;
    }

    @Override
    public PhysicalOperatorTag getOperatorTag() {
        return PhysicalOperatorTag.PLAQUE_GROUP_FILTER;
    }

    @Override
    public boolean isMicroOperator() {
        return true;
    }

    @Override
    public void computeDeliveredProperties(ILogicalOperator op, IOptimizationContext context) {
        ILogicalOperator child = op.getInputs().get(0).getValue();
        deliveredProperties = ((AbstractLogicalOperator) child).getDeliveredPhysicalProperties();
    }

    @Override
    public PhysicalRequirements getRequiredPropertiesForChildren(ILogicalOperator op,
            IPhysicalPropertiesVector reqdByParent, IOptimizationContext context) {
        if (requiresPartitioning && reqdByParent != null && reqdByParent.getPartitioningProperty() != null) {
            StructuralPropertiesVector[] reqs = new StructuralPropertiesVector[] {
                    new StructuralPropertiesVector(reqdByParent.getPartitioningProperty(), null) };
            return new PhysicalRequirements(reqs, IPartitioningRequirementsCoordinator.NO_COORDINATION);
        }
        return emptyUnaryRequirements();
    }

    @Override
    public void contributeRuntimeOperator(IHyracksJobBuilder builder, JobGenContext context, ILogicalOperator op,
            IOperatorSchema opSchema, IOperatorSchema[] inputSchemas, IOperatorSchema outerPlanSchema)
            throws AlgebricksException {
        int aggColumnIdx = inputSchemas[0].findVariable(filteredVar);
        int groupColumnIdx = inputSchemas[0].findVariable(groupKeyVar);
        if (aggColumnIdx < 0 || groupColumnIdx < 0) {
            // Variables not in schema — happens when CBO sampling queries clone and
            // restructure the plan. Degrade to pass-through (no filtering).
            RecordDescriptor recDesc =
                    JobGenHelper.mkRecordDescriptor(context.getTypeEnvironment(op), opSchema, context);
            int[] projections = JobGenHelper.projectAllVariables(opSchema);
            builder.contributeMicroOperator(op,
                    new org.apache.hyracks.algebricks.runtime.operators.std.StreamProjectRuntimeFactory(projections),
                    recDesc);
            ILogicalOperator src = op.getInputs().get(0).getValue();
            builder.contributeGraphEdge(src, 0, op, 0);
            return;
        }
        PlaqueGroupFilterRuntimeFactory runtime =
                new PlaqueGroupFilterRuntimeFactory(groupColumnIdx, aggColumnIdx, isMax, handle);
        RecordDescriptor recDesc = JobGenHelper.mkRecordDescriptor(context.getTypeEnvironment(op), opSchema, context);
        builder.contributeMicroOperator(op, runtime, recDesc);
        ILogicalOperator src = op.getInputs().get(0).getValue();
        builder.contributeGraphEdge(src, 0, op, 0);
    }

    @Override
    public boolean expensiveThanMaterialization() {
        return false;
    }

    @Override
    public String toString() {
        return "PLAQUE_GROUP_FILTER(" + filteredVar + ", gk=" + groupKeyVar + ", " + (isMax ? "MAX" : "MIN") + ", "
                + handle + ")";
    }
}
