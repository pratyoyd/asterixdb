package org.apache.asterix.runtime.aggregates.std;

import java.util.Set;
import java.util.logging.Logger;

import org.apache.asterix.om.types.IAType;
import org.apache.asterix.runtime.operators.plaque.PlaqueGroupThresholdState;
import org.apache.asterix.runtime.operators.plaque.PlaqueGroupThresholdState.ByteArrayWrapper;
import org.apache.hyracks.algebricks.runtime.base.IScalarEvaluatorFactory;
import org.apache.hyracks.api.context.IEvaluatorContext;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.exceptions.SourceLocation;
import org.apache.hyracks.api.job.JobId;
import org.apache.hyracks.dataflow.common.data.accessors.IFrameTupleReference;

/**
 * PLAQUE observer aggregate for GROUP BY MIN/MAX queries with low-cardinality group keys.
 * Extends SqlMinMaxAggregateFunction and updates per-group thresholds in PlaqueGroupThresholdState.
 *
 * Key design: saves the current tuple's group key bytes BEFORE calling super.step(),
 * so that when onMinMaxChanged() fires, the group key is available.
 */
public class PlaqueGroupByMinMaxAggregateFunction extends SqlMinMaxAggregateFunction {

    private static final Logger LOGGER = Logger.getLogger(PlaqueGroupByMinMaxAggregateFunction.class.getName());

    private final PlaqueGroupThresholdState groupThresholdState;
    private final int groupKeyColumnIndex;
    private final JobId jobId;
    private final String handle;
    private final int propagationInterval;
    private PlaqueGroupThresholdPropagator propagator;
    private int stepCount;
    private long totalSteps;
    private int thresholdUpdateCount;

    // Saved group key from current tuple (set before super.step(), read in onMinMaxChanged())
    private byte[] lastGroupKeyData;
    private int lastGroupKeyStart;
    private int lastGroupKeyLen;

    public PlaqueGroupByMinMaxAggregateFunction(IScalarEvaluatorFactory[] args, IEvaluatorContext context,
            boolean isMin, SourceLocation sourceLoc, IAType aggFieldType, PlaqueGroupThresholdState groupThresholdState,
            int groupKeyColumnIndex, JobId jobId, String handle, int propagationInterval) throws HyracksDataException {
        super(args, context, isMin, Type.LOCAL, sourceLoc, aggFieldType);
        this.groupThresholdState = groupThresholdState;
        this.groupKeyColumnIndex = groupKeyColumnIndex;
        this.jobId = jobId;
        this.handle = handle;
        this.propagationInterval = propagationInterval;
        this.stepCount = 0;
    }

    @Override
    public void step(IFrameTupleReference tuple) throws HyracksDataException {
        totalSteps++;

        // Fast path: if cap reached, skip all PLAQUE logic — just run the standard aggregate
        if (groupThresholdState.isCapReached()) {
            super.step(tuple);
            return;
        }

        // Save group key BEFORE super.step() so onMinMaxChanged() can access it
        lastGroupKeyData = tuple.getFieldData(groupKeyColumnIndex);
        lastGroupKeyStart = tuple.getFieldStart(groupKeyColumnIndex);
        lastGroupKeyLen = tuple.getFieldLength(groupKeyColumnIndex);

        super.step(tuple);

        if (++stepCount >= propagationInterval) {
            stepCount = 0;
            maybePropagateThresholds();
        }
    }

    @Override
    protected void onMinMaxChanged() throws HyracksDataException {
        if (groupThresholdState.isCapReached()) {
            return;
        }
        thresholdUpdateCount++;
        groupThresholdState.update(lastGroupKeyData, lastGroupKeyStart, lastGroupKeyLen, outputVal.getByteArray(),
                outputVal.getStartOffset(), outputVal.getLength());
    }

    @Override
    public void finish(org.apache.hyracks.data.std.api.IPointable result) throws HyracksDataException {
        LOGGER.warning(String.format(
                "PLAQUE_GROUPBY_AGG_STATS [job=%s, handle=%s] totalSteps=%d, thresholdUpdates=%d, initialized=%b",
                jobId, handle, totalSteps, thresholdUpdateCount, groupThresholdState.isInitialized()));
        maybePropagateThresholds();
        super.finish(result);
    }

    private void maybePropagateThresholds() {
        if (propagator == null || !groupThresholdState.isDirty()) {
            return;
        }
        try {
            Set<ByteArrayWrapper> dirtyGroups = groupThresholdState.getDirtyGroups();
            for (ByteArrayWrapper gk : dirtyGroups) {
                byte[] thresholdSnapshot = groupThresholdState.getGroupThresholdSnapshot(gk);
                if (thresholdSnapshot != null) {
                    byte[] groupKeyBytes = gk.getBytes();
                    propagator.propagate(jobId, handle, groupKeyBytes, groupKeyBytes.length, thresholdSnapshot,
                            thresholdSnapshot.length, groupThresholdState.isMax());
                }
            }
            groupThresholdState.clearDirty();
        } catch (Exception e) {
            // Propagation failure is non-fatal
        }
    }

    public void setPropagator(PlaqueGroupThresholdPropagator propagator) {
        this.propagator = propagator;
    }

    /**
     * Functional interface for propagating per-group threshold updates to CC.
     */
    @FunctionalInterface
    public interface PlaqueGroupThresholdPropagator {
        void propagate(JobId jobId, String handle, byte[] groupKey, int gLen, byte[] threshold, int tLen, boolean isMax)
                throws Exception;
    }
}
