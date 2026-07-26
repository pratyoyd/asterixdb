package org.apache.asterix.runtime.operators.plaque;

import java.util.concurrent.ConcurrentHashMap;

import org.apache.asterix.dataflow.data.nontagged.comparators.AGenericAscBinaryComparatorFactory;
import org.apache.asterix.om.types.BuiltinType;
import org.apache.hyracks.api.dataflow.value.IBinaryComparator;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.job.JobId;

public final class PlaqueThresholdCCState {

    public static final PlaqueThresholdCCState INSTANCE = new PlaqueThresholdCCState();

    private static class ThresholdEntry {
        final boolean isMax;
        byte[] bytes;
        int length;
        boolean initialized;
        final IBinaryComparator comparator;

        ThresholdEntry(boolean isMax) {
            this.isMax = isMax;
            this.comparator = new AGenericAscBinaryComparatorFactory(BuiltinType.ANY, BuiltinType.ANY).createBinaryComparator();
            this.initialized = false;
        }
    }

    private final ConcurrentHashMap<String, ThresholdEntry> entries = new ConcurrentHashMap<>();

    private PlaqueThresholdCCState() {
    }

    public boolean update(JobId jobId, String handle, byte[] data, int len, boolean isMax)
            throws HyracksDataException {
        String key = makeKey(jobId, handle);
        ThresholdEntry entry = entries.computeIfAbsent(key, k -> new ThresholdEntry(isMax));
        synchronized (entry) {
            if (!entry.initialized) {
                entry.bytes = new byte[len];
                System.arraycopy(data, 0, entry.bytes, 0, len);
                entry.length = len;
                entry.initialized = true;
                return true;
            }
            int cmp = entry.comparator.compare(data, 0, len, entry.bytes, 0, entry.length);
            if ((isMax && cmp > 0) || (!isMax && cmp < 0)) {
                entry.bytes = new byte[len];
                System.arraycopy(data, 0, entry.bytes, 0, len);
                entry.length = len;
                return true;
            }
            return false;
        }
    }

    public byte[] getSnapshot(JobId jobId, String handle) {
        ThresholdEntry entry = entries.get(makeKey(jobId, handle));
        if (entry == null) {
            return null;
        }
        synchronized (entry) {
            if (!entry.initialized) {
                return null;
            }
            byte[] copy = new byte[entry.length];
            System.arraycopy(entry.bytes, 0, copy, 0, entry.length);
            return copy;
        }
    }

    public void removeJob(JobId jobId) {
        String prefix = jobId.toString() + ":";
        entries.entrySet().removeIf(e -> e.getKey().startsWith(prefix));
    }

    private static String makeKey(JobId jobId, String handle) {
        return jobId.toString() + ":" + handle;
    }
}
