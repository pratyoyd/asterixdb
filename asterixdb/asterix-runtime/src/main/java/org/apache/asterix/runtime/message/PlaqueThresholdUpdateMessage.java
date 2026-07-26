package org.apache.asterix.runtime.message;

import java.util.Set;

import org.apache.asterix.common.dataflow.ICcApplicationContext;
import org.apache.asterix.common.messaging.api.ICCMessageBroker;
import org.apache.asterix.common.messaging.api.ICcAddressedMessage;
import org.apache.asterix.runtime.operators.plaque.PlaqueThresholdCCState;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.job.JobId;

public class PlaqueThresholdUpdateMessage implements ICcAddressedMessage {
    private static final long serialVersionUID = 1L;

    private final JobId jobId;
    private final String handle;
    private final byte[] thresholdBytes;
    private final int thresholdLength;
    private final boolean isMax;

    public PlaqueThresholdUpdateMessage(JobId jobId, String handle,
            byte[] thresholdBytes, int thresholdLength, boolean isMax) {
        this.jobId = jobId;
        this.handle = handle;
        this.thresholdBytes = thresholdBytes;
        this.thresholdLength = thresholdLength;
        this.isMax = isMax;
    }

    @Override
    public void handle(ICcApplicationContext appCtx) throws HyracksDataException, InterruptedException {
        try {
            boolean improved = PlaqueThresholdCCState.INSTANCE.update(
                    jobId, handle, thresholdBytes, thresholdLength, isMax);
            if (improved) {
                ICCMessageBroker broker =
                        (ICCMessageBroker) appCtx.getServiceContext().getMessageBroker();
                byte[] globalSnapshot = PlaqueThresholdCCState.INSTANCE.getSnapshot(jobId, handle);
                if (globalSnapshot != null) {
                    PlaqueThresholdBroadcastMessage broadcast =
                            new PlaqueThresholdBroadcastMessage(jobId, handle, globalSnapshot,
                                    globalSnapshot.length, isMax);
                    Set<String> nodes = appCtx.getClusterStateManager().getParticipantNodes();
                    for (String nodeId : nodes) {
                        try {
                            broker.sendApplicationMessageToNC(broadcast, nodeId);
                        } catch (Exception e) {
                            // Node may have left the cluster
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw HyracksDataException.create(e);
        }
    }

    @Override
    public String toString() {
        return "PlaqueThresholdUpdateMessage[job=" + jobId + ",handle=" + handle + "]";
    }
}
