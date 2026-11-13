package org.apache.asterix.runtime.message;

import java.util.Set;

import org.apache.asterix.common.dataflow.ICcApplicationContext;
import org.apache.asterix.common.messaging.api.ICCMessageBroker;
import org.apache.asterix.common.messaging.api.ICcAddressedMessage;
import org.apache.asterix.runtime.operators.plaque.PlaqueThresholdCCState;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.job.JobId;

/**
 * NC-to-CC message carrying a single group's threshold update for GROUP BY PLAQUE.
 * Sent per group that improved, not the entire map.
 */
public class PlaqueGroupThresholdUpdateMessage implements ICcAddressedMessage {
    private static final long serialVersionUID = 1L;

    private final JobId jobId;
    private final String handle;
    private final byte[] groupKeyBytes;
    private final int groupKeyLength;
    private final byte[] thresholdBytes;
    private final int thresholdLength;
    private final boolean isMax;

    public PlaqueGroupThresholdUpdateMessage(JobId jobId, String handle, byte[] groupKeyBytes, int groupKeyLength,
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
    public void handle(ICcApplicationContext appCtx) throws HyracksDataException, InterruptedException {
        try {
            boolean improved = PlaqueThresholdCCState.INSTANCE.updateGroup(jobId, handle, groupKeyBytes, groupKeyLength,
                    thresholdBytes, thresholdLength, isMax);
            if (improved) {
                ICCMessageBroker broker = (ICCMessageBroker) appCtx.getServiceContext().getMessageBroker();
                // Broadcast the improved group threshold to all NCs
                PlaqueGroupThresholdBroadcastMessage broadcast = new PlaqueGroupThresholdBroadcastMessage(jobId, handle,
                        groupKeyBytes, groupKeyLength, thresholdBytes, thresholdLength, isMax);
                Set<String> nodes = appCtx.getClusterStateManager().getParticipantNodes();
                for (String nodeId : nodes) {
                    try {
                        broker.sendApplicationMessageToNC(broadcast, nodeId);
                    } catch (Exception e) {
                        // Node may have left the cluster
                    }
                }
            }
        } catch (Exception e) {
            throw HyracksDataException.create(e);
        }
    }

    @Override
    public String toString() {
        return "PlaqueGroupThresholdUpdateMessage[job=" + jobId + ",handle=" + handle + "]";
    }
}
