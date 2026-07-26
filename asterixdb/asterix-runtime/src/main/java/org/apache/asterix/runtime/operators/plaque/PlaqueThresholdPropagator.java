package org.apache.asterix.runtime.operators.plaque;

import org.apache.hyracks.api.job.JobId;

@FunctionalInterface
public interface PlaqueThresholdPropagator {
    void propagate(JobId jobId, String handle, byte[] thresholdBytes, int len, boolean isMax) throws Exception;
}
