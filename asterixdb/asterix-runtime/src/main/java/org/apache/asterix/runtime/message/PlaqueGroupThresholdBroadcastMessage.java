package org.apache.asterix.runtime.message;

import org.apache.asterix.common.api.INcApplicationContext;
import org.apache.asterix.common.messaging.api.INcAddressedMessage;
import org.apache.asterix.runtime.operators.plaque.PlaqueThresholdRegistry;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.job.JobId;

/**
 * CC-to-NC message broadcasting a per-group threshold improvement for GROUP BY PLAQUE.
 */
public class PlaqueGroupThresholdBroadcastMessage implements INcAddressedMessage {
    private static final long serialVersionUID = 1L;

    private final JobId jobId;
    private final String handle;
    private final byte[] groupKeyBytes;
    private final int groupKeyLength;
    private final byte[] thresholdBytes;
    private final int thresholdLength;
    private final boolean isMax;

    public PlaqueGroupThresholdBroadcastMessage(JobId jobId, String handle, byte[] groupKeyBytes, int groupKeyLength,
            byte[] thresholdBytes, int thresholdLength, boolean isMax) {
        this.jobId = jobId;
        this.handle = handle;
        this.groupKeyBytes = groupKeyBytes;
        this.groupKeyLength = groupKeyLength;
        this.thresholdBytes = thresholdBytes;
        this.thresholdLength = thresholdLength;
        this.isMax = isMax;
    }

    @Override
    public void handle(INcApplicationContext appCtx) throws HyracksDataException, InterruptedException {
        PlaqueThresholdRegistry.INSTANCE.mergeGlobalGroup(
                jobId, handle, groupKeyBytes, groupKeyLength, thresholdBytes, thresholdLength, isMax);
    }

    @Override
    public String toString() {
        return "PlaqueGroupThresholdBroadcastMessage[job=" + jobId + ",handle=" + handle + "]";
    }
}
