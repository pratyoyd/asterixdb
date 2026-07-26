package org.apache.asterix.runtime.operators.plaque;

import java.util.logging.Logger;

import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.job.JobId;
import org.apache.hyracks.dataflow.std.sort.ITopKThresholdObserver;
import org.apache.hyracks.dataflow.std.sort.ITopKThresholdObserverFactory;

/**
 * Factory for Top-K threshold observers. The observer extracts the K-th value
 * from the sort heap and updates PlaqueThresholdState for probe-side filtering.
 */
public class PlaqueTopKThresholdObserverFactory implements ITopKThresholdObserverFactory {

    private static final long serialVersionUID = 1L;
    private final String handle;
    private final boolean isMax;

    public PlaqueTopKThresholdObserverFactory(String handle, boolean isMax) {
        this.handle = handle;
        this.isMax = isMax;
    }

    @Override
    public ITopKThresholdObserver createObserver(IHyracksTaskContext ctx) throws HyracksDataException {
        JobId jobId = ctx.getJobletContext().getJobId();
        PlaqueThresholdState state = PlaqueThresholdRegistry.INSTANCE.getOrCreate(jobId, handle, isMax);
        return new PlaqueTopKThresholdObserver(state, jobId, handle);
    }

    private static final class PlaqueTopKThresholdObserver implements ITopKThresholdObserver {

        private static final Logger LOGGER = Logger.getLogger(PlaqueTopKThresholdObserver.class.getName());

        private final PlaqueThresholdState state;
        private final JobId jobId;
        private final String handle;
        private long updateCount;

        PlaqueTopKThresholdObserver(PlaqueThresholdState state, JobId jobId, String handle) {
            this.state = state;
            this.jobId = jobId;
            this.handle = handle;
        }

        @Override
        public void onKthValueChanged(byte[] data, int offset, int length) throws HyracksDataException {
            state.update(data, offset, length);
            updateCount++;

            if (updateCount <= 3 || updateCount % 10000 == 0) {
                LOGGER.warning(String.format(
                        "PLAQUE_TOPK_THRESHOLD [job=%s, handle=%s] update #%d",
                        jobId, handle, updateCount));
            }
        }
    }
}
