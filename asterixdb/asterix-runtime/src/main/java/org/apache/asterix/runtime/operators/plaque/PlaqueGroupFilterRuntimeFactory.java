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

/**
 * Tuple-level PLAQUE filter for GROUP BY MIN/MAX queries. Takes TWO column indices:
 * groupColumnIdx (for looking up the group's threshold) and aggColumnIdx (for comparing
 * against the threshold).
 */
public class PlaqueGroupFilterRuntimeFactory implements IPushRuntimeFactory {

    private static final long serialVersionUID = 1L;

    private final int groupColumnIdx;
    private final int aggColumnIdx;
    private final boolean isMax;
    private final String handle;

    public PlaqueGroupFilterRuntimeFactory(int groupColumnIdx, int aggColumnIdx, boolean isMax, String handle) {
        this.groupColumnIdx = groupColumnIdx;
        this.aggColumnIdx = aggColumnIdx;
        this.isMax = isMax;
        this.handle = handle;
    }

    @Override
    public IPushRuntime[] createPushRuntime(IHyracksTaskContext ctx) throws HyracksDataException {
        return new IPushRuntime[] { new PlaqueGroupFilterRuntime(ctx, groupColumnIdx, aggColumnIdx, isMax, handle) };
    }

    @Override
    public String toString() {
        return "plaque-group-filter [gCol=" + groupColumnIdx + ", aCol=" + aggColumnIdx + ", "
                + (isMax ? "MAX" : "MIN") + ", h=" + handle + "]";
    }

    private static final class PlaqueGroupFilterRuntime extends AbstractOneInputOneOutputOneFramePushRuntime {

        private static final Logger LOGGER = Logger.getLogger(PlaqueGroupFilterRuntime.class.getName());

        private final IHyracksTaskContext ctx;
        private final int groupColumnIdx;
        private final int aggColumnIdx;
        private final boolean isMax;
        private final String handle;
        private PlaqueGroupThresholdState groupThresholdState;
        private IBinaryComparator threadLocalComparator;
        private JobId jobId;
        private long totalTuples;
        private long filteredTuples;
        private long passedTuples;
        private long nullPassedTuples;
        private long thresholdUninitializedTuples;

        private PlaqueGroupFilterRuntime(IHyracksTaskContext ctx, int groupColumnIdx, int aggColumnIdx, boolean isMax,
                String handle) {
            this.ctx = ctx;
            this.groupColumnIdx = groupColumnIdx;
            this.aggColumnIdx = aggColumnIdx;
            this.isMax = isMax;
            this.handle = handle;
        }

        @Override
        public void open() throws HyracksDataException {
            initAccessAppendRef(ctx);
            jobId = ctx.getJobletContext().getJobId();
            groupThresholdState = PlaqueThresholdRegistry.INSTANCE.getOrCreateGroupState(jobId, handle, isMax);
            threadLocalComparator =
                    new AGenericAscBinaryComparatorFactory(BuiltinType.ANY, BuiltinType.ANY).createBinaryComparator();
            super.open();
        }

        @Override
        public void nextFrame(ByteBuffer buffer) throws HyracksDataException {
            tAccess.reset(buffer);
            int nTuple = tAccess.getTupleCount();
            for (int t = 0; t < nTuple; t++) {
                totalTuples++;
                tRef.reset(tAccess, t);

                // Extract group key
                byte[] gData = tRef.getFieldData(groupColumnIdx);
                int gStart = tRef.getFieldStart(groupColumnIdx);
                int gLen = tRef.getFieldLength(groupColumnIdx);

                // Extract aggregate value
                byte[] aData = tRef.getFieldData(aggColumnIdx);
                int aStart = tRef.getFieldStart(aggColumnIdx);
                int aLen = tRef.getFieldLength(aggColumnIdx);

                // Filter out null/missing GROUP KEYs — they can't contribute to any group
                if (gLen <= 0) {
                    nullPassedTuples++;
                    filteredTuples++;
                    continue;
                }
                byte gTag = gData[gStart];
                if (gTag == ATypeTag.SERIALIZED_NULL_TYPE_TAG || gTag == ATypeTag.SERIALIZED_MISSING_TYPE_TAG
                        || gTag == ATypeTag.SERIALIZED_SYSTEM_NULL_TYPE_TAG) {
                    nullPassedTuples++;
                    filteredTuples++;
                    continue;
                }

                // Pass through null/missing aggregate values (they don't affect MIN/MAX)
                if (aLen <= 0) {
                    passedTuples++;
                    appendTupleToFrame(t);
                    continue;
                }
                byte aTag = aData[aStart];
                if (aTag == ATypeTag.SERIALIZED_NULL_TYPE_TAG || aTag == ATypeTag.SERIALIZED_MISSING_TYPE_TAG
                        || aTag == ATypeTag.SERIALIZED_SYSTEM_NULL_TYPE_TAG) {
                    passedTuples++;
                    appendTupleToFrame(t);
                    continue;
                }

                if (!groupThresholdState.isInitialized()) {
                    thresholdUninitializedTuples++;
                }

                if (groupThresholdState.passes(gData, gStart, gLen, aData, aStart, aLen, threadLocalComparator)) {
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
                    "PLAQUE_GROUP_FILTER_STATS [job=%s, handle=%s, partition=%s] "
                            + "total=%d, passed=%d, filtered=%d, nullPassed=%d, "
                            + "thresholdUninitTuples=%d, initialized=%b, filterRate=%.4f%%",
                    jobId, handle, ctx.getTaskAttemptId(), totalTuples, passedTuples, filteredTuples, nullPassedTuples,
                    thresholdUninitializedTuples, groupThresholdState.isInitialized(),
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
