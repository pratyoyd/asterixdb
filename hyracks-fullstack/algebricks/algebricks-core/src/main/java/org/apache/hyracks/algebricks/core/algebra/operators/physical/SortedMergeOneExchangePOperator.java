package org.apache.hyracks.algebricks.core.algebra.operators.physical;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.core.algebra.base.*;
import org.apache.hyracks.algebricks.core.algebra.expressions.IVariableTypeEnvironment;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.IOperatorSchema;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.OrderOperator;
import org.apache.hyracks.algebricks.core.algebra.properties.*;
import org.apache.hyracks.algebricks.core.jobgen.impl.JobGenContext;
import org.apache.hyracks.algebricks.data.IBinaryComparatorFactoryProvider;
import org.apache.hyracks.algebricks.data.INormalizedKeyComputerFactoryProvider;
import org.apache.hyracks.api.dataflow.IConnectorDescriptor;
import org.apache.hyracks.api.dataflow.value.IBinaryComparatorFactory;
import org.apache.hyracks.api.dataflow.value.INormalizedKeyComputerFactory;
import org.apache.hyracks.api.job.IConnectorDescriptorRegistry;
import org.apache.hyracks.dataflow.std.base.MToNBroadcastMergingConnectorDescriptor;

public class SortedMergeOneExchangePOperator extends AbstractExchangePOperator {
    private final List<OrderColumn> orderColumns;

    public SortedMergeOneExchangePOperator(List<OrderColumn> orderColumns) {
        this.orderColumns = orderColumns;
    }

    @Override
    public Pair<IConnectorDescriptor, IHyracksJobBuilder.TargetConstraint> createConnectorDescriptor(
            IConnectorDescriptorRegistry spec, ILogicalOperator op, IOperatorSchema opSchema, JobGenContext context)
            throws AlgebricksException {
        IVariableTypeEnvironment env = context.getTypeEnvironment(op);
        int n = orderColumns.size();
        int[] sortFields = new int[n];

        IBinaryComparatorFactory[] comparatorFactories = new IBinaryComparatorFactory[n];

        INormalizedKeyComputerFactoryProvider nkcfProvider = context.getNormalizedKeyComputerFactoryProvider();
        INormalizedKeyComputerFactory nkcf = null;

        int j = 0;
        for (OrderColumn oc : orderColumns) {
            LogicalVariable var = oc.getColumn();
            sortFields[j] = opSchema.findVariable(var);
            Object type = env.getVarType(var);
            IBinaryComparatorFactoryProvider bcfp = context.getBinaryComparatorFactoryProvider();
            comparatorFactories[j] =
                    bcfp.getBinaryComparatorFactory(type, oc.getOrder() == OrderOperator.IOrder.OrderKind.ASC);
            if (j == 0 && nkcfProvider != null && type != null) {
                nkcf = nkcfProvider.getNormalizedKeyComputerFactory(type,
                        oc.getOrder() == OrderOperator.IOrder.OrderKind.ASC);
            }
            j++;
        }
        IConnectorDescriptor conn =
                new MToNBroadcastMergingConnectorDescriptor(spec, sortFields, comparatorFactories, nkcf);
        return Pair.of(conn, IHyracksJobBuilder.TargetConstraint.ONE);
    }

    @Override
    public PhysicalOperatorTag getOperatorTag() {
        return PhysicalOperatorTag.RANDOM_MERGE_ONE_EXCHANGE;
    }

    @Override
    public PhysicalRequirements getRequiredPropertiesForChildren(ILogicalOperator op,
            IPhysicalPropertiesVector reqdByParent, IOptimizationContext context) throws AlgebricksException {
        return emptyUnaryRequirements();
    }

    @Override
    public void computeDeliveredProperties(ILogicalOperator op, IOptimizationContext context)
            throws AlgebricksException {
        this.deliveredProperties =
                new StructuralPropertiesVector(IPartitioningProperty.UNPARTITIONED, new ArrayList<>(0));
    }
}
