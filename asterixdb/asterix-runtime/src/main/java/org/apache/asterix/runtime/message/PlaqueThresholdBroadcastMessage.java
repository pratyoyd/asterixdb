package org.apache.asterix.runtime.message;

import org.apache.asterix.common.api.INcApplicationContext;
import org.apache.asterix.common.messaging.api.INcAddressedMessage;
import org.apache.asterix.runtime.operators.plaque.PlaqueThresholdRegistry;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.job.JobId;

public class PlaqueThresholdBroadcastMessage implements INcAddressedMessage {
    private static final long serialVersionUID = 1L;

    private final JobId jobId;
    private final String handle;
    private final byte[] thresholdBytes;
    private final int thresholdLength;
    private final boolean isMax;

    public PlaqueThresholdBroadcastMessage(JobId jobId, String handle,
            byte[] thresholdBytes, int thresholdLength, boolean isMax) {
        this.jobId = jobId;
        this.handle = handle;
        this.thresholdBytes = thresholdBytes;
        this.thresholdLength = thresholdLength;
        this.isMax = isMax;
    }

    @Override
    public void handle(INcApplicationContext appCtx) throws HyracksDataException, InterruptedException {
        PlaqueThresholdRegistry.INSTANCE.mergeGlobal(
                jobId, handle, thresholdBytes, thresholdLength, isMax);
    }

    @Override
    public String toString() {
        return "PlaqueThresholdBroadcastMessage[job=" + jobId + ",handle=" + handle + "]";
    }
}
