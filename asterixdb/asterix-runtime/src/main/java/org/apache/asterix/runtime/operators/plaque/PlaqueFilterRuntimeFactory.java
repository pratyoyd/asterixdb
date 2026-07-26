package org.apache.asterix.runtime.operators.plaque;

import java.nio.ByteBuffer;
import java.util.logging.Logger;

import org.apache.asterix.dataflow.data.nontagged.comparators.AGenericAscBinaryComparatorFactory;
import org.apache.asterix.om.types.ATypeTag;
import org.apache.asterix.om.types.BuiltinType;
import org.apache.hyracks.algebricks.runtime.base.IPushRuntime;
import org.apache.hyracks.algebricks.runtime.base.IPushRuntimeFactory;
import org.apache.hyracks.algebricks.runtime.operators.base.AbstractOneInputOneOutputOneFramePushRuntime;
import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.dataflow.value.IBinaryComparator;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.job.JobId;

public class PlaqueFilterRuntimeFactory implements IPushRuntimeFactory {

    private static final long serialVersionUID = 1L;

    private final int columnIdx;
    private final boolean isMax;
    private final String handle;
    private final boolean strictComparison;

    public PlaqueFilterRuntimeFactory(int columnIdx, boolean isMax, String handle) {
        this(columnIdx, isMax, handle, false);
    }

    public PlaqueFilterRuntimeFactory(int columnIdx, boolean isMax, String handle, boolean strictComparison) {
        this.columnIdx = columnIdx;
        this.isMax = isMax;
        this.handle = handle;
        this.strictComparison = strictComparison;
    }

    @Override
    public IPushRuntime[] createPushRuntime(IHyracksTaskContext ctx) throws HyracksDataException {
        return new IPushRuntime[] { new PlaqueFilterRuntime(ctx, columnIdx, isMax, handle, strictComparison) };
    }

    @Override
    public String toString() {
        return "plaque-filter [col=" + columnIdx + ", " + (isMax ? "MAX" : "MIN") + ", h=" + handle + "]";
    }

    private static final class PlaqueFilterRuntime extends AbstractOneInputOneOutputOneFramePushRuntime {

        private static final Logger LOGGER = Logger.getLogger(PlaqueFilterRuntime.class.getName());

        private final IHyracksTaskContext ctx;
        private final int columnIdx;
        private final boolean isMax;
        private final String handle;
        private final boolean strictComparison;
        private PlaqueThresholdState thresholdState;
        private IBinaryComparator threadLocalComparator;
        private JobId jobId;
        private long totalTuples;
        private long filteredTuples;
        private long passedTuples;
        private long nullPassedTuples;
        private long thresholdUninitializedTuples;

        private PlaqueFilterRuntime(IHyracksTaskContext ctx, int columnIdx, boolean isMax, String handle,
                boolean strictComparison) {
            this.ctx = ctx;
            this.columnIdx = columnIdx;
            this.isMax = isMax;
            this.handle = handle;
            this.strictComparison = strictComparison;
        }

        @Override
        public void open() throws HyracksDataException {
            initAccessAppendRef(ctx);
            jobId = ctx.getJobletContext().getJobId();
            thresholdState = PlaqueThresholdRegistry.INSTANCE.getOrCreate(jobId, handle, isMax, strictComparison);
            threadLocalComparator = new AGenericAscBinaryComparatorFactory(BuiltinType.ANY, BuiltinType.ANY).createBinaryComparator();
            super.open();
        }

        @Override
        public void nextFrame(ByteBuffer buffer) throws HyracksDataException {
            tAccess.reset(buffer);
            int nTuple = tAccess.getTupleCount();
            for (int t = 0; t < nTuple; t++) {
                totalTuples++;
                tRef.reset(tAccess, t);
                byte[] data = tRef.getFieldData(columnIdx);
                int start = tRef.getFieldStart(columnIdx);
                int len = tRef.getFieldLength(columnIdx);

                if (len <= 0) {
                    nullPassedTuples++;
                    appendTupleToFrame(t);
                    continue;
                }

                byte typeTag = data[start];
                if (typeTag == ATypeTag.SERIALIZED_NULL_TYPE_TAG
                        || typeTag == ATypeTag.SERIALIZED_MISSING_TYPE_TAG
                        || typeTag == ATypeTag.SERIALIZED_SYSTEM_NULL_TYPE_TAG) {
                    nullPassedTuples++;
                    appendTupleToFrame(t);
                    continue;
                }

                if (!thresholdState.isInitialized()) {
                    thresholdUninitializedTuples++;
                }

                if (thresholdState.passes(data, start, len, threadLocalComparator)) {
                    passedTuples++;
                    appendTupleToFrame(t);
                } else {
                    filteredTuples++;
                }
            }
        }

        @Override
        public void close() throws HyracksDataException {
            LOGGER.warning(String.format(
                    "PLAQUE_FILTER_STATS [job=%s, handle=%s, partition=%s] "
                    + "total=%d, passed=%d, filtered=%d, nullPassed=%d, "
                    + "thresholdUninitTuples=%d, thresholdInitialized=%b, filterRate=%.4f%%",
                    jobId, handle, ctx.getTaskAttemptId(),
                    totalTuples, passedTuples, filteredTuples, nullPassedTuples,
                    thresholdUninitializedTuples, thresholdState.isInitialized(),
                    totalTuples > 0 ? (100.0 * filteredTuples / totalTuples) : 0.0));
            try {
                super.close();
            } finally {
                PlaqueThresholdRegistry.INSTANCE.removeJob(jobId);
            }
        }

        @Override
        public void flush() throws HyracksDataException {
            appender.flush(writer);
        }
    }
}
