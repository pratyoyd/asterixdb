package org.apache.asterix.algebra.operators.physical;

import java.util.List;

import org.apache.asterix.runtime.operators.plaque.PlaqueCrossExprExchangeFilterFactory;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.common.utils.Pair;
import org.apache.hyracks.algebricks.core.algebra.base.IHyracksJobBuilder.TargetConstraint;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalVariable;
import org.apache.hyracks.algebricks.core.algebra.expressions.IVariableTypeEnvironment;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.IOperatorSchema;
import org.apache.hyracks.algebricks.core.algebra.operators.physical.HashPartitionExchangePOperator;
import org.apache.hyracks.algebricks.core.algebra.properties.INodeDomain;
import org.apache.hyracks.algebricks.core.jobgen.impl.JobGenContext;
import org.apache.hyracks.algebricks.data.IBinaryHashFunctionFactoryProvider;
import org.apache.hyracks.api.dataflow.IConnectorDescriptor;
import org.apache.hyracks.api.dataflow.value.IBinaryHashFunctionFactory;
import org.apache.hyracks.api.dataflow.value.ITuplePartitionComputerFactory;
import org.apache.hyracks.api.job.IConnectorDescriptorRegistry;
import org.apache.hyracks.dataflow.common.data.partition.FieldHashPartitionComputerFactory;
import org.apache.hyracks.dataflow.std.connectors.IPlaqueExchangeFilterFactory;
import org.apache.hyracks.dataflow.std.connectors.MToNPartitioningConnectorDescriptor;

/**
 * Hash partition exchange with PLAQUE cross-expression filtering.
 * Produces a MToNPartitioningConnectorDescriptor with a filter factory
 * that drops tuples whose probe-side value can't improve the aggregate.
 */
public class PlaqueCrossExprExchangePOperator extends HashPartitionExchangePOperator {

    private final String thresholdHandle;
    private final String buildSideHandle;
    private final int probeColumnIdx;
    private final PlaqueCrossExprExchangeFilterFactory.CombineOp combineOp;
    private final PlaqueCrossExprExchangeFilterFactory.FilterDirection filterDirection;

    public PlaqueCrossExprExchangePOperator(List<LogicalVariable> hashFields, INodeDomain domain,
            int[][] partitionsMap, String thresholdHandle, String buildSideHandle, int probeColumnIdx,
            PlaqueCrossExprExchangeFilterFactory.CombineOp combineOp,
            PlaqueCrossExprExchangeFilterFactory.FilterDirection filterDirection) {
        super(hashFields, domain, partitionsMap);
        this.thresholdHandle = thresholdHandle;
        this.buildSideHandle = buildSideHandle;
        this.probeColumnIdx = probeColumnIdx;
        this.combineOp = combineOp;
        this.filterDirection = filterDirection;
    }

    @Override
    public Pair<IConnectorDescriptor, TargetConstraint> createConnectorDescriptor(IConnectorDescriptorRegistry spec,
            ILogicalOperator op, IOperatorSchema opSchema, JobGenContext context) throws AlgebricksException {
        List<LogicalVariable> hashFields = getHashFields();
        int[] keys = new int[hashFields.size()];
        IBinaryHashFunctionFactory[] hashFunctionFactories = new IBinaryHashFunctionFactory[hashFields.size()];
        int i = 0;
        IBinaryHashFunctionFactoryProvider hashFunProvider = context.getBinaryHashFunctionFactoryProvider();
        IVariableTypeEnvironment env = context.getTypeEnvironment(op);
        for (LogicalVariable v : hashFields) {
            keys[i] = opSchema.findVariable(v);
            hashFunctionFactories[i] = hashFunProvider.getBinaryHashFunctionFactory(env.getVarType(v));
            ++i;
        }
        ITuplePartitionComputerFactory tpcf;
        int[][] partitionsMap = getPartitionsMap();
        if (partitionsMap == null) {
            tpcf = FieldHashPartitionComputerFactory.of(keys, hashFunctionFactories);
        } else {
            tpcf = FieldHashPartitionComputerFactory.withMap(keys, hashFunctionFactories, partitionsMap);
        }

        IPlaqueExchangeFilterFactory filterFactory = new PlaqueCrossExprExchangeFilterFactory(
                thresholdHandle, buildSideHandle, probeColumnIdx, combineOp, filterDirection);

        IConnectorDescriptor conn = new MToNPartitioningConnectorDescriptor(spec, tpcf, filterFactory);
        return new Pair<>(conn, null);
    }
}
