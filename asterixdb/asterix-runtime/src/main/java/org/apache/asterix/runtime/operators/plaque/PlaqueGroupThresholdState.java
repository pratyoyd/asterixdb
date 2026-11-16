package org.apache.asterix.runtime.operators.plaque;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.asterix.dataflow.data.nontagged.comparators.AGenericAscBinaryComparatorFactory;
import org.apache.asterix.om.types.ATypeTag;
import org.apache.asterix.om.types.BuiltinType;
import org.apache.hyracks.api.dataflow.value.IBinaryComparator;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.util.string.UTF8StringUtil;

/**
 * Per-group threshold state for PLAQUE GROUP BY MIN/MAX filter.
 * Maintains two parallel maps:
 * 1. Raw bytes map (ByteArrayWrapper → byte[]) for tuple-level filtering
 * 2. Normalized longs map (Long → Long) for page-level filtering
 *
 * The normalized map enables pure long→long lookups on the page filter hot path,
 * matching the zone map normalization scheme used by AsterixDB columnar storage.
 */
public class PlaqueGroupThresholdState {

    private final boolean isMax;
    private final IBinaryComparator comparator;

    // --- Tuple-level filter state (raw bytes) ---
    private final ConcurrentHashMap<ByteArrayWrapper, byte[]> groupThresholds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ByteArrayWrapper, Integer> groupThresholdLengths = new ConcurrentHashMap<>();
    private final Set<ByteArrayWrapper> seenGroups = ConcurrentHashMap.newKeySet();

    // --- Page-level filter state (normalized longs) ---
    private final ConcurrentHashMap<Long, Long> normalizedMap = new ConcurrentHashMap<>();
    private final Set<Long> poisonedNormalizedKeys = ConcurrentHashMap.newKeySet();
    // Track which raw group key owns each normalized key, to detect collisions
    private final ConcurrentHashMap<Long, ByteArrayWrapper> normalizedKeyOwners = new ConcurrentHashMap<>();

    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final int maxGroups;

    // When true, cardinality exceeded the cap — filter and observer should bypass all logic
    private volatile boolean capReached = false;

    // Per-group dirty tracking for propagation
    private final Set<ByteArrayWrapper> dirtyGroups = ConcurrentHashMap.newKeySet();

    public PlaqueGroupThresholdState(boolean isMax) {
        this(isMax, 25);
    }

    public PlaqueGroupThresholdState(boolean isMax, int maxGroups) {
        this.isMax = isMax;
        this.maxGroups = maxGroups;
        this.comparator =
                new AGenericAscBinaryComparatorFactory(BuiltinType.ANY, BuiltinType.ANY).createBinaryComparator();
    }

    /**
     * Update the threshold for a group. Called by the observer aggregate.
     */
    public synchronized void update(byte[] groupKey, int gStart, int gLen, byte[] aggValue, int aStart, int aLen)
            throws HyracksDataException {
        ByteArrayWrapper gk = new ByteArrayWrapper(groupKey, gStart, gLen);

        if (!seenGroups.contains(gk)) {
            // New group
            if (seenGroups.size() >= maxGroups) {
                capReached = true;
                return;
            }
            seenGroups.add(gk);
            byte[] thresholdCopy = new byte[aLen];
            System.arraycopy(aggValue, aStart, thresholdCopy, 0, aLen);
            groupThresholds.put(gk, thresholdCopy);
            groupThresholdLengths.put(gk, aLen);
            dirty.set(true);
            dirtyGroups.add(gk);

            // Update normalized map for page filter
            updateNormalizedMap(gk, groupKey, gStart, gLen, aggValue, aStart, aLen);
            return;
        }

        // Existing group — check if threshold improved
        byte[] currentThreshold = groupThresholds.get(gk);
        int currentLen = groupThresholdLengths.get(gk);
        int cmp = comparator.compare(aggValue, aStart, aLen, currentThreshold, 0, currentLen);
        if ((isMax && cmp > 0) || (!isMax && cmp < 0)) {
            byte[] thresholdCopy = new byte[aLen];
            System.arraycopy(aggValue, aStart, thresholdCopy, 0, aLen);
            groupThresholds.put(gk, thresholdCopy);
            groupThresholdLengths.put(gk, aLen);
            dirty.set(true);
            dirtyGroups.add(gk);

            // Update normalized map for page filter
            updateNormalizedMap(gk, groupKey, gStart, gLen, aggValue, aStart, aLen);
        }
    }

    private void updateNormalizedMap(ByteArrayWrapper gk, byte[] groupKey, int gStart, int gLen, byte[] aggValue,
            int aStart, int aLen) {
        long normGK = normalize(groupKey, gStart, gLen);
        long normThreshold = normalize(aggValue, aStart, aLen);

        // Check for collision
        ByteArrayWrapper existingOwner = normalizedKeyOwners.get(normGK);
        if (existingOwner != null && !existingOwner.equals(gk)) {
            // Collision: two different group keys normalize to the same long
            poisonedNormalizedKeys.add(normGK);
            normalizedMap.remove(normGK);
            return;
        }

        if (poisonedNormalizedKeys.contains(normGK)) {
            return; // already poisoned
        }

        normalizedKeyOwners.put(normGK, gk);
        normalizedMap.put(normGK, normThreshold);
    }

    /**
     * Check if a tuple passes the per-group filter. Used by tuple-level filter.
     */
    public boolean passes(byte[] groupKey, int gStart, int gLen, byte[] aggValue, int aStart, int aLen,
            IBinaryComparator threadLocalComparator) throws HyracksDataException {
        if (capReached) {
            return true; // high cardinality — filtering disabled
        }
        ByteArrayWrapper gk = new ByteArrayWrapper(groupKey, gStart, gLen);
        if (!seenGroups.contains(gk)) {
            return true; // unseen group, pass conservatively
        }
        byte[] threshold = groupThresholds.get(gk);
        if (threshold == null) {
            return true;
        }
        Integer thresholdLen = groupThresholdLengths.get(gk);
        if (thresholdLen == null) {
            return true;
        }
        int cmp = threadLocalComparator.compare(aggValue, aStart, aLen, threshold, 0, thresholdLen);
        // a value equal to the threshold of its group cannot change the MAX/MIN of that group
        return isMax ? cmp > 0 : cmp < 0;
    }

    /**
     * Look up the normalized threshold for a page-level group key.
     * Returns null if the group is unseen or the normalized key is poisoned (collision).
     */
    public Long getNormalizedThreshold(long normalizedGroupKey) {
        if (poisonedNormalizedKeys.contains(normalizedGroupKey)) {
            return null;
        }
        return normalizedMap.get(normalizedGroupKey);
    }

    /**
     * Merge a global threshold update (from CC broadcast) for a specific group.
     */
    public synchronized void mergeGlobal(byte[] groupKey, int gStart, int gLen, byte[] thresholdBytes, int tStart,
            int tLen) throws HyracksDataException {
        update(groupKey, gStart, gLen, thresholdBytes, tStart, tLen);
    }

    public boolean isCapReached() {
        return capReached;
    }

    public boolean isInitialized() {
        return !seenGroups.isEmpty();
    }

    public boolean isDirty() {
        return dirty.get();
    }

    public void clearDirty() {
        dirty.set(false);
        dirtyGroups.clear();
    }

    public Set<ByteArrayWrapper> getDirtyGroups() {
        return dirtyGroups;
    }

    public boolean isMax() {
        return isMax;
    }

    /**
     * Get a snapshot of a specific group's threshold (for propagation).
     */
    public synchronized byte[] getGroupThresholdSnapshot(ByteArrayWrapper groupKey) {
        byte[] threshold = groupThresholds.get(groupKey);
        if (threshold == null) {
            return null;
        }
        int len = groupThresholdLengths.get(groupKey);
        byte[] copy = new byte[len];
        System.arraycopy(threshold, 0, copy, 0, len);
        return copy;
    }

    /**
     * Get the raw group key bytes for a given wrapper (for propagation).
     */
    public byte[] getGroupKeyBytes(ByteArrayWrapper gk) {
        return gk.getBytes();
    }

    // ---- Normalization (matches zone map scheme) ----

    /**
     * Normalize AsterixDB typed bytes to a long matching the column filter normalization scheme.
     * Reuses the same logic as PlaqueColumnFilterEvaluator.normalizeThreshold().
     */
    public static long normalize(byte[] data, int start, int len) {
        if (len < 2) {
            return Long.MIN_VALUE;
        }
        ATypeTag typeTag = ATypeTag.VALUE_TYPE_MAPPING[data[start] & 0xFF];
        if (typeTag == null) {
            return Long.MIN_VALUE;
        }
        switch (typeTag) {
            case DOUBLE:
                if (len < 9) {
                    return Long.MIN_VALUE;
                }
                long bits = 0;
                for (int i = start + 1; i <= start + 8; i++) {
                    bits = (bits << 8) | (data[i] & 0xFF);
                }
                return Double.doubleToLongBits(Double.longBitsToDouble(bits));
            case BIGINT:
                if (len < 9) {
                    return Long.MIN_VALUE;
                }
                long val = 0;
                for (int i = start + 1; i <= start + 8; i++) {
                    val = (val << 8) | (data[i] & 0xFF);
                }
                return val;
            case INTEGER:
                if (len < 5) {
                    return Long.MIN_VALUE;
                }
                int intVal = 0;
                for (int i = start + 1; i <= start + 4; i++) {
                    intVal = (intVal << 8) | (data[i] & 0xFF);
                }
                return intVal;
            case SMALLINT:
                if (len < 3) {
                    return Long.MIN_VALUE;
                }
                return (short) (((data[start + 1] & 0xFF) << 8) | (data[start + 2] & 0xFF));
            case TINYINT:
                if (len < 2) {
                    return Long.MIN_VALUE;
                }
                return data[start + 1];
            case STRING:
                // Normalize first 4 chars to match zone map normalization in StringColumnFilterWriter
                long nk = 0;
                int strOffset = start + 1; // skip type tag
                int utfLen = UTF8StringUtil.getUTFLength(data, strOffset);
                strOffset += UTF8StringUtil.getNumBytesToStoreLength(utfLen);
                int strEnd = start + len;
                for (int i = 0; i < 4; i++) {
                    nk <<= 16;
                    if (strOffset < strEnd) {
                        nk += UTF8StringUtil.charAt(data, strOffset) & 0xFFFF;
                        strOffset += UTF8StringUtil.charSize(data, strOffset);
                    }
                }
                return nk >>> 1;
            default:
                return Long.MIN_VALUE;
        }
    }

    // ---- ByteArrayWrapper ----

    /**
     * Wraps a byte[] range with proper equals()/hashCode() for use as HashMap keys.
     */
    public static final class ByteArrayWrapper {
        private final byte[] data;
        private final int hashCode;

        public ByteArrayWrapper(byte[] source, int start, int len) {
            this.data = new byte[len];
            System.arraycopy(source, start, this.data, 0, len);
            this.hashCode = Arrays.hashCode(this.data);
        }

        public byte[] getBytes() {
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
            return hashCode;
        }
    }
}
