package org.apache.asterix.runtime.operators.plaque;

import java.util.logging.Logger;

import org.apache.asterix.dataflow.data.nontagged.comparators.AGenericAscBinaryComparatorFactory;
import org.apache.asterix.om.types.ATypeTag;
import org.apache.asterix.om.types.BuiltinType;
import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.dataflow.value.IBinaryComparator;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.job.JobId;
import org.apache.hyracks.dataflow.common.comm.io.FrameTupleAccessor;
import org.apache.hyracks.dataflow.std.join.INLJMismatchWriter;
import org.apache.hyracks.dataflow.std.join.INLJMismatchWriterFactory;

public class PlaqueThetaJoinMismatchWriterFactory implements INLJMismatchWriterFactory {

    private static final long serialVersionUID = 1L;

    private final String handle;
    private final boolean isMax;
    private final boolean strictComparison;
    private final int outerJoinColumnIdx;

    public PlaqueThetaJoinMismatchWriterFactory(String handle, boolean isMax, boolean strictComparison,
            int outerJoinColumnIdx) {
        this.handle = handle;
        this.isMax = isMax;
        this.strictComparison = strictComparison;
        this.outerJoinColumnIdx = outerJoinColumnIdx;
    }

    @Override
    public INLJMismatchWriter createWriter(IHyracksTaskContext ctx) throws HyracksDataException {
        JobId jobId = ctx.getJobletContext().getJobId();
        PlaqueThresholdState state =
                PlaqueThresholdRegistry.INSTANCE.getOrCreate(jobId, handle, isMax, strictComparison);
        return new PlaqueThetaJoinMismatchWriter(state, isMax, outerJoinColumnIdx, jobId, handle);
    }

    private static final class PlaqueThetaJoinMismatchWriter implements INLJMismatchWriter {

        private static final Logger LOGGER = Logger.getLogger(PlaqueThetaJoinMismatchWriter.class.getName());

        private final PlaqueThresholdState thresholdState;
        private final boolean isMax;
        private final int outerJoinColumnIdx;
        private final JobId jobId;
        private final String handle;
        private final IBinaryComparator localComparator;
        private long unmatchedCount;
        // Thread-local best value — avoids synchronized calls per tuple
        private byte[] localBest;
        private int localBestLen;
        private boolean localBestInitialized;

        private PlaqueThetaJoinMismatchWriter(PlaqueThresholdState thresholdState, boolean isMax,
                int outerJoinColumnIdx, JobId jobId, String handle) {
            this.thresholdState = thresholdState;
            this.isMax = isMax;
            this.outerJoinColumnIdx = outerJoinColumnIdx;
            this.jobId = jobId;
            this.handle = handle;
            this.localComparator = new AGenericAscBinaryComparatorFactory(
                    BuiltinType.ANY, BuiltinType.ANY).createBinaryComparator();
            this.localBest = new byte[64];
            this.localBestInitialized = false;
        }

        @Override
        public void onUnmatchedOuterTuple(FrameTupleAccessor accessor, int tupleIndex) throws HyracksDataException {
            int fieldCount = accessor.getFieldCount();
            if (outerJoinColumnIdx >= fieldCount) {
                return;
            }
            int fStart = accessor.getAbsoluteFieldStartOffset(tupleIndex, outerJoinColumnIdx);
            int fLen = accessor.getFieldLength(tupleIndex, outerJoinColumnIdx);
            if (fLen <= 0) {
                return;
            }
            byte[] data = accessor.getBuffer().array();
            byte typeTag = data[fStart];
            if (typeTag == ATypeTag.SERIALIZED_NULL_TYPE_TAG
                    || typeTag == ATypeTag.SERIALIZED_MISSING_TYPE_TAG
                    || typeTag == ATypeTag.SERIALIZED_SYSTEM_NULL_TYPE_TAG) {
                return;
            }
            // Accumulate local best (no synchronization)
            if (!localBestInitialized) {
                if (fLen > localBest.length) {
                    localBest = new byte[fLen];
                }
                System.arraycopy(data, fStart, localBest, 0, fLen);
                localBestLen = fLen;
                localBestInitialized = true;
            } else {
                int cmp = localComparator.compare(data, fStart, fLen, localBest, 0, localBestLen);
                if ((isMax && cmp > 0) || (!isMax && cmp < 0)) {
                    if (fLen > localBest.length) {
                        localBest = new byte[fLen];
                    }
                    System.arraycopy(data, fStart, localBest, 0, fLen);
                    localBestLen = fLen;
                }
            }
            unmatchedCount++;
        }

        @Override
        public void flushBatch() throws HyracksDataException {
            if (localBestInitialized) {
                thresholdState.update(localBest, 0, localBestLen);
                localBestInitialized = false;
            }
        }

        @Override
        public void close() throws HyracksDataException {
            flushBatch();
            LOGGER.warning(String.format(
                    "PLAQUE_THETA_JOIN_MISMATCH [job=%s, handle=%s] unmatchedOuterTuples=%d, thresholdInitialized=%b",
                    jobId, handle, unmatchedCount, thresholdState.isInitialized()));
        }
    }
}
