package org.apache.asterix.runtime.operators.plaque;

import java.util.logging.Logger;

import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.job.JobId;
import org.apache.hyracks.dataflow.common.comm.io.FrameTupleAccessor;
import org.apache.hyracks.dataflow.std.join.IBuildSideObserver;
import org.apache.hyracks.dataflow.std.join.IBuildSideObserverFactory;

/**
 * Factory for build-side observers that track MIN (or MAX) of a column
 * during hash join build phase. Publishes per-NC result to PlaqueBuildSideMinState.
 */
public class PlaqueBuildSideObserverFactory implements IBuildSideObserverFactory {

    private static final long serialVersionUID = 1L;

    private final String handle;
    private final int buildColumnIdx;
    private final boolean trackMin; // true = MIN, false = MAX
    private final int numPartitions; // total NCs

    public PlaqueBuildSideObserverFactory(String handle, int buildColumnIdx, boolean trackMin, int numPartitions) {
        this.handle = handle;
        this.buildColumnIdx = buildColumnIdx;
        this.trackMin = trackMin;
        this.numPartitions = numPartitions;
    }

    @Override
    public IBuildSideObserver createObserver(IHyracksTaskContext ctx, int partitionIdx) throws HyracksDataException {
        JobId jobId = ctx.getJobletContext().getJobId();
        PlaqueBuildSideMinState state =
                PlaqueThresholdRegistry.INSTANCE.getOrCreateBuildSideMinState(jobId, handle, numPartitions, trackMin);
        return new PlaqueBuildSideObserverImpl(state, buildColumnIdx, trackMin, partitionIdx, jobId, handle);
    }

    private static final class PlaqueBuildSideObserverImpl implements IBuildSideObserver {

        private static final Logger LOGGER = Logger.getLogger(PlaqueBuildSideObserverImpl.class.getName());

        private final PlaqueBuildSideMinState state;
        private final int buildColumnIdx;
        private final boolean trackMin;
        private final int partitionIdx;
        private final JobId jobId;
        private final String handle;
        private double localBound;
        private long observedCount;

        PlaqueBuildSideObserverImpl(PlaqueBuildSideMinState state, int buildColumnIdx, boolean trackMin,
                int partitionIdx, JobId jobId, String handle) {
            this.state = state;
            this.buildColumnIdx = buildColumnIdx;
            this.trackMin = trackMin;
            this.partitionIdx = partitionIdx;
            this.jobId = jobId;
            this.handle = handle;
            this.localBound = trackMin ? Double.MAX_VALUE : -Double.MAX_VALUE;
            this.observedCount = 0;
        }

        @Override
        public void observe(FrameTupleAccessor accessor, int tupleIndex) throws HyracksDataException {
            int fStart = accessor.getAbsoluteFieldStartOffset(tupleIndex, buildColumnIdx);
            int fLen = accessor.getFieldLength(tupleIndex, buildColumnIdx);
            if (fLen <= 0) {
                return;
            }
            byte[] data = accessor.getBuffer().array();
            byte typeTag = data[fStart];
            double value = extractNumericValue(data, fStart, fLen, typeTag);
            if (Double.isNaN(value)) {
                return;
            }
            if (trackMin) {
                if (value < localBound) {
                    localBound = value;
                }
            } else {
                if (value > localBound) {
                    localBound = value;
                }
            }
            observedCount++;
        }

        private static double extractNumericValue(byte[] data, int offset, int len, byte typeTag) {
            switch (typeTag) {
                case 12: // DOUBLE
                    if (len < 9) return Double.NaN;
                    long dBits = 0;
                    for (int i = 1; i <= 8; i++) {
                        dBits = (dBits << 8) | (data[offset + i] & 0xFF);
                    }
                    return Double.longBitsToDouble(dBits);
                case 11: // FLOAT
                    if (len < 5) return Double.NaN;
                    int fBits = 0;
                    for (int i = 1; i <= 4; i++) {
                        fBits = (fBits << 8) | (data[offset + i] & 0xFF);
                    }
                    return Float.intBitsToFloat(fBits);
                case 4: // BIGINT
                    if (len < 9) return Double.NaN;
                    long lVal = 0;
                    for (int i = 1; i <= 8; i++) {
                        lVal = (lVal << 8) | (data[offset + i] & 0xFF);
                    }
                    return (double) lVal;
                case 3: // INTEGER
                    if (len < 5) return Double.NaN;
                    int iVal = 0;
                    for (int i = 1; i <= 4; i++) {
                        iVal = (iVal << 8) | (data[offset + i] & 0xFF);
                    }
                    return (double) iVal;
                case 2: // SMALLINT
                    if (len < 3) return Double.NaN;
                    short sVal = (short) (((data[offset + 1] & 0xFF) << 8) | (data[offset + 2] & 0xFF));
                    return (double) sVal;
                case 1: // TINYINT
                    if (len < 2) return Double.NaN;
                    return (double) data[offset + 1];
                default:
                    return Double.NaN;
            }
        }

        @Override
        public void publishResult() throws HyracksDataException {
            state.publish(partitionIdx, localBound);
            LOGGER.warning(String.format(
                    "PLAQUE_BUILD_OBSERVER [job=%s, handle=%s, partition=%d] observed=%d, %s=%.4f",
                    jobId, handle, partitionIdx, observedCount,
                    trackMin ? "min" : "max", localBound));
        }
    }
}
