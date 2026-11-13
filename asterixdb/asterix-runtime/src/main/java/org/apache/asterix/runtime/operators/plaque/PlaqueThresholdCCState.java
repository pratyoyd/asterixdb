package org.apache.asterix.runtime.operators.plaque;

import java.util.Arrays;
import java.util.Map;
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
            this.comparator =
                    new AGenericAscBinaryComparatorFactory(BuiltinType.ANY, BuiltinType.ANY).createBinaryComparator();
            this.initialized = false;
        }
    }

    private final ConcurrentHashMap<String, ThresholdEntry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, java.util.Map<ByteArrayWrapper, ThresholdEntry>> groupEntries =
            new ConcurrentHashMap<>();

    private PlaqueThresholdCCState() {
    }

    public boolean update(JobId jobId, String handle, byte[] data, int len, boolean isMax) throws HyracksDataException {
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

    public boolean updateGroup(JobId jobId, String handle, byte[] groupKey, int gLen, byte[] data, int len,
            boolean isMax) throws HyracksDataException {
        String key = makeKey(jobId, handle);
        Map<ByteArrayWrapper, ThresholdEntry> groupMap =
                groupEntries.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        ByteArrayWrapper gkw = new ByteArrayWrapper(groupKey, 0, gLen);
        ThresholdEntry entry = groupMap.computeIfAbsent(gkw, k -> new ThresholdEntry(isMax));
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

    public void removeJob(JobId jobId) {
        String prefix = jobId.toString() + ":";
        entries.entrySet().removeIf(e -> e.getKey().startsWith(prefix));
        groupEntries.entrySet().removeIf(e -> e.getKey().startsWith(prefix));
    }

    /**
     * Wrapper for byte[] to use as HashMap keys with proper equals/hashCode.
     */
    public static final class ByteArrayWrapper {
        private final byte[] data;

        public ByteArrayWrapper(byte[] src, int offset, int length) {
            this.data = new byte[length];
            System.arraycopy(src, offset, this.data, 0, length);
        }

        public byte[] getData() {
            return data;
        }

        public int getLength() {
            return data.length;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof ByteArrayWrapper))
                return false;
            return Arrays.equals(data, ((ByteArrayWrapper) o).data);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(data);
        }
    }

    private static String makeKey(JobId jobId, String handle) {
        return jobId.toString() + ":" + handle;
    }
}
