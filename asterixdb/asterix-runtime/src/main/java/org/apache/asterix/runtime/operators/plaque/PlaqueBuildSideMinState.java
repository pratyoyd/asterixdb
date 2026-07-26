package org.apache.asterix.runtime.operators.plaque;

/**
 * Holds a per-partition (per-NC) vector of build-side column min (or max) values.
 * Published once when the hash join build phase completes. Immutable after publication.
 * Thread-safe: each partition writes its own slot; reads use volatile array reference.
 */
public class PlaqueBuildSideMinState {

    private final double[] values;
    private final boolean[] published;
    private final int size;
    private final boolean trackMin; // true = track MIN, false = track MAX
    private volatile boolean anyPublished;

    public PlaqueBuildSideMinState(int numPartitions, boolean trackMin) {
        this.size = numPartitions;
        this.trackMin = trackMin;
        this.values = new double[numPartitions];
        this.published = new boolean[numPartitions];
        this.anyPublished = false;
        for (int i = 0; i < numPartitions; i++) {
            values[i] = trackMin ? Double.MAX_VALUE : -Double.MAX_VALUE;
            published[i] = false;
        }
    }

    /**
     * Called by the build-side observer when build completes on partition idx.
     * Each partition calls this exactly once.
     */
    public void publish(int partitionIdx, double value) {
        values[partitionIdx] = value;
        published[partitionIdx] = true;
        anyPublished = true;
    }

    public double getValue(int partitionIdx) {
        return values[partitionIdx];
    }

    public boolean isPublished(int partitionIdx) {
        return published[partitionIdx];
    }

    public boolean isAnyPublished() {
        return anyPublished;
    }

    /**
     * Returns the global min (or max) across all published partitions.
     * For page-level filtering where we can't determine per-tuple partition.
     */
    public double getGlobalBound() {
        double result = trackMin ? Double.MAX_VALUE : -Double.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            if (published[i]) {
                result = trackMin ? Math.min(result, values[i]) : Math.max(result, values[i]);
            }
        }
        return result;
    }

    public int getSize() {
        return size;
    }

    public boolean isTrackMin() {
        return trackMin;
    }
}
