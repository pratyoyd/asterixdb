package org.apache.asterix.runtime.aggregates.std;

import java.util.logging.Logger;

import org.apache.asterix.om.types.IAType;
import org.apache.asterix.runtime.operators.plaque.PlaqueThresholdPropagator;
import org.apache.asterix.runtime.operators.plaque.PlaqueThresholdState;
import org.apache.hyracks.algebricks.runtime.base.IEvaluatorContext;
import org.apache.hyracks.algebricks.runtime.base.IScalarEvaluatorFactory;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.exceptions.SourceLocation;
import org.apache.hyracks.api.job.JobId;
import org.apache.hyracks.dataflow.common.data.accessors.IFrameTupleReference;

public class PlaqueLocalSqlMinMaxAggregateFunction extends SqlMinMaxAggregateFunction {

    private static final Logger LOGGER = Logger.getLogger(PlaqueLocalSqlMinMaxAggregateFunction.class.getName());

    private final PlaqueThresholdState thresholdState;
    private final JobId jobId;
    private final String handle;
    private final int propagationInterval;
    private PlaqueThresholdPropagator propagator;
    private int stepCount;
    private long totalSteps;
    private int thresholdUpdateCount;

    public PlaqueLocalSqlMinMaxAggregateFunction(IScalarEvaluatorFactory[] args, IEvaluatorContext context,
            boolean isMin, SourceLocation sourceLoc, IAType aggFieldType,
            PlaqueThresholdState thresholdState, JobId jobId, String handle,
            int propagationInterval)
            throws HyracksDataException {
        super(args, context, isMin, Type.LOCAL, sourceLoc, aggFieldType);
        this.thresholdState = thresholdState;
        this.jobId = jobId;
        this.handle = handle;
        this.propagationInterval = propagationInterval;
        this.stepCount = 0;
    }

    @Override
    protected void onMinMaxChanged() throws HyracksDataException {
        thresholdUpdateCount++;
        thresholdState.update(
                outputVal.getByteArray(),
                outputVal.getStartOffset(),
                outputVal.getLength());
    }

    @Override
    public void step(IFrameTupleReference tuple) throws HyracksDataException {
        totalSteps++;
        super.step(tuple);
        if (++stepCount >= propagationInterval) {
            stepCount = 0;
            maybePropagateThreshold();
        }
    }

    @Override
    public void finish(org.apache.hyracks.data.std.api.IPointable result) throws HyracksDataException {
        LOGGER.warning(String.format(
                "PLAQUE_AGG_STATS [job=%s, handle=%s] totalSteps=%d, thresholdUpdates=%d, "
                + "thresholdInitialized=%b",
                jobId, handle, totalSteps, thresholdUpdateCount, thresholdState.isInitialized()));
        maybePropagateThreshold();
        super.finish(result);
    }

    private void maybePropagateThreshold() {
        if (propagator == null || !thresholdState.isDirty()) {
            return;
        }
        try {
            byte[] snapshot = thresholdState.getThresholdSnapshot();
            if (snapshot == null) {
                return;
            }
            thresholdState.clearDirty();
            propagator.propagate(jobId, handle, snapshot, snapshot.length, thresholdState.isMax());
        } catch (Exception e) {
            // Propagation failure is non-fatal
        }
    }

    public void setPropagator(PlaqueThresholdPropagator propagator) {
        this.propagator = propagator;
    }
}
