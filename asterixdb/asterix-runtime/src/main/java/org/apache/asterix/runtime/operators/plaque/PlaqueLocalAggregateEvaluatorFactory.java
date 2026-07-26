package org.apache.asterix.runtime.operators.plaque;

import org.apache.asterix.common.messaging.api.INCMessageBroker;
import org.apache.asterix.om.types.IAType;
import org.apache.asterix.runtime.aggregates.std.PlaqueLocalSqlMinMaxAggregateFunction;
import org.apache.hyracks.algebricks.runtime.base.IAggregateEvaluator;
import org.apache.hyracks.algebricks.runtime.base.IAggregateEvaluatorFactory;
import org.apache.hyracks.algebricks.runtime.base.IEvaluatorContext;
import org.apache.hyracks.algebricks.runtime.base.IScalarEvaluatorFactory;
import org.apache.hyracks.api.application.INCServiceContext;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.exceptions.SourceLocation;
import org.apache.hyracks.api.job.JobId;

public class PlaqueLocalAggregateEvaluatorFactory implements IAggregateEvaluatorFactory {

    private static final long serialVersionUID = 2L;

    private final IScalarEvaluatorFactory[] args;
    private final boolean isMin;
    private final SourceLocation sourceLoc;
    private final IAType aggFieldType;
    private final String handle;
    private final boolean isMax;
    private final boolean propagationEnabled;
    private final int propagationInterval;

    public PlaqueLocalAggregateEvaluatorFactory(IScalarEvaluatorFactory[] args, boolean isMin,
            SourceLocation sourceLoc, IAType aggFieldType, String handle, boolean isMax,
            boolean propagationEnabled, int propagationInterval) {
        this.args = args;
        this.isMin = isMin;
        this.sourceLoc = sourceLoc;
        this.aggFieldType = aggFieldType;
        this.handle = handle;
        this.isMax = isMax;
        this.propagationEnabled = propagationEnabled;
        this.propagationInterval = propagationInterval;
    }

    @Override
    public IAggregateEvaluator createAggregateEvaluator(IEvaluatorContext ctx) throws HyracksDataException {
        JobId jobId = ctx.getTaskContext().getJobletContext().getJobId();
        PlaqueThresholdState state = PlaqueThresholdRegistry.INSTANCE.getOrCreate(jobId, handle, isMax);
        PlaqueLocalSqlMinMaxAggregateFunction agg = new PlaqueLocalSqlMinMaxAggregateFunction(
                args, ctx, isMin, sourceLoc, aggFieldType, state, jobId, handle, propagationInterval);
        if (propagationEnabled) {
            try {
                INCServiceContext serviceCtx = (INCServiceContext) ctx.getTaskContext()
                        .getJobletContext().getServiceContext();
                INCMessageBroker broker = (INCMessageBroker) serviceCtx.getMessageBroker();
                agg.setPropagator((jid, h, bytes, len, max) -> {
                    org.apache.asterix.runtime.message.PlaqueThresholdUpdateMessage msg =
                            new org.apache.asterix.runtime.message.PlaqueThresholdUpdateMessage(
                                    jid, h, bytes, len, max);
                    broker.sendMessageToPrimaryCC(msg);
                });
            } catch (ClassCastException e) {
                // Test environment without AsterixDB messaging
            }
        }
        return agg;
    }
}
