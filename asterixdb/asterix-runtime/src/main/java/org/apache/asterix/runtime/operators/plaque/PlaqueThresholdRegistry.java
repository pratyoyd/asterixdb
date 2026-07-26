package org.apache.asterix.runtime.operators.plaque;

import java.util.concurrent.ConcurrentHashMap;

import org.apache.hyracks.api.job.JobId;

public final class PlaqueThresholdRegistry {

    public static final PlaqueThresholdRegistry INSTANCE = new PlaqueThresholdRegistry();

    private final ConcurrentHashMap<String, PlaqueThresholdState> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PlaqueBuildSideMinState> buildSideStates = new ConcurrentHashMap<>();

    private PlaqueThresholdRegistry() {
    }

    public PlaqueThresholdState getOrCreate(JobId jobId, String handle, boolean isMax) {
        return getOrCreate(jobId, handle, isMax, false);
    }

    public PlaqueThresholdState getOrCreate(JobId jobId, String handle, boolean isMax, boolean strictComparison) {
        String key = makeKey(jobId, handle);
        return states.computeIfAbsent(key, k -> new PlaqueThresholdState(isMax, strictComparison));
    }

    public PlaqueThresholdState get(JobId jobId, String handle) {
        return states.get(makeKey(jobId, handle));
    }

    public PlaqueBuildSideMinState getOrCreateBuildSideMinState(JobId jobId, String handle, int numPartitions,
            boolean trackMin) {
        String key = makeKey(jobId, handle);
        return buildSideStates.computeIfAbsent(key, k -> new PlaqueBuildSideMinState(numPartitions, trackMin));
    }

    public PlaqueBuildSideMinState getBuildSideMinState(JobId jobId, String handle) {
        return buildSideStates.get(makeKey(jobId, handle));
    }

    public void removeJob(JobId jobId) {
        String prefix = jobId.toString() + ":";
        states.entrySet().removeIf(e -> e.getKey().startsWith(prefix));
        buildSideStates.entrySet().removeIf(e -> e.getKey().startsWith(prefix));
    }

    public void mergeGlobal(JobId jobId, String handle, byte[] thresholdBytes, int len, boolean isMax)
            throws org.apache.hyracks.api.exceptions.HyracksDataException {
        PlaqueThresholdState state = getOrCreate(jobId, handle, isMax);
        state.mergeGlobal(thresholdBytes, 0, len);
    }

    private static String makeKey(JobId jobId, String handle) {
        return jobId.toString() + ":" + handle;
    }
}
