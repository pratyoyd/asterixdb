package org.apache.asterix.runtime.operators.plaque;

import org.apache.asterix.common.messaging.api.INCMessageBroker;
import org.apache.asterix.om.types.IAType;
import org.apache.asterix.runtime.aggregates.std.PlaqueGroupByMinMaxAggregateFunction;
import org.apache.asterix.runtime.message.PlaqueGroupThresholdUpdateMessage;
import org.apache.hyracks.algebricks.runtime.base.IAggregateEvaluator;
import org.apache.hyracks.algebricks.runtime.base.IAggregateEvaluatorFactory;
import org.apache.hyracks.algebricks.runtime.base.IScalarEvaluatorFactory;
import org.apache.hyracks.api.application.INCServiceContext;
import org.apache.hyracks.api.context.IEvaluatorContext;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.exceptions.SourceLocation;
import org.apache.hyracks.api.job.JobId;

/**
 * Factory for PLAQUE GROUP BY aggregate evaluator. Creates PlaqueGroupByMinMaxAggregateFunction
 * which tracks per-group thresholds in PlaqueGroupThresholdState.
 */
public class PlaqueGroupByAggregateEvaluatorFactory implements IAggregateEvaluatorFactory {

    private static final long serialVersionUID = 1L;

    private final IScalarEvaluatorFactory[] args;
    private final boolean isMin;
    private final SourceLocation sourceLoc;
    private final IAType aggFieldType;
    private final String handle;
    private final boolean isMax;
    private final boolean propagationEnabled;
    private final int propagationInterval;
    private final int groupKeyColumnIndex;

    public PlaqueGroupByAggregateEvaluatorFactory(IScalarEvaluatorFactory[] args, boolean isMin,
            SourceLocation sourceLoc, IAType aggFieldType, String handle, boolean isMax, boolean propagationEnabled,
            int propagationInterval, int groupKeyColumnIndex) {
        this.args = args;
        this.isMin = isMin;
        this.sourceLoc = sourceLoc;
        this.aggFieldType = aggFieldType;
        this.handle = handle;
        this.isMax = isMax;
        this.propagationEnabled = propagationEnabled;
        this.propagationInterval = propagationInterval;
        this.groupKeyColumnIndex = groupKeyColumnIndex;
    }

    @Override
    public IAggregateEvaluator createAggregateEvaluator(IEvaluatorContext ctx) throws HyracksDataException {
        JobId jobId = ctx.getTaskContext().getJobletContext().getJobId();
        PlaqueGroupThresholdState state = PlaqueThresholdRegistry.INSTANCE.getOrCreateGroupState(jobId, handle, isMax);
        PlaqueGroupByMinMaxAggregateFunction agg = new PlaqueGroupByMinMaxAggregateFunction(args, ctx, isMin, sourceLoc,
                aggFieldType, state, groupKeyColumnIndex, jobId, handle, propagationInterval);
        if (propagationEnabled) {
            try {
                INCServiceContext serviceCtx =
                        (INCServiceContext) ctx.getTaskContext().getJobletContext().getServiceContext();
                INCMessageBroker broker = (INCMessageBroker) serviceCtx.getMessageBroker();
                agg.setPropagator((jid, h, gKey, gLen, threshold, tLen, max) -> {
                    PlaqueGroupThresholdUpdateMessage msg =
                            new PlaqueGroupThresholdUpdateMessage(jid, h, gKey, gLen, threshold, tLen, max);
                    broker.sendMessageToPrimaryCC(msg);
                });
            } catch (ClassCastException e) {
                // Test environment without AsterixDB messaging
            }
        }
        return agg;
    }
}
