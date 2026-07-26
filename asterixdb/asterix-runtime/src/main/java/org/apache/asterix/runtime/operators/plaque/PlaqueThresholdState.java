package org.apache.asterix.runtime.operators.plaque;

import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.asterix.dataflow.data.nontagged.comparators.AGenericAscBinaryComparatorFactory;
import org.apache.asterix.om.types.BuiltinType;
import org.apache.hyracks.api.dataflow.value.IBinaryComparator;
import org.apache.hyracks.api.exceptions.HyracksDataException;

public class PlaqueThresholdState {

    private final boolean isMax;
    private final boolean strictComparison;
    private final IBinaryComparator comparator;
    private volatile byte[] thresholdSnapshot;
    private volatile int thresholdSnapshotLength;
    private volatile boolean initialized;
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    public PlaqueThresholdState(boolean isMax) {
        this(isMax, false);
    }

    public PlaqueThresholdState(boolean isMax, boolean strictComparison) {
        this.isMax = isMax;
        this.strictComparison = strictComparison;
        this.comparator = new AGenericAscBinaryComparatorFactory(BuiltinType.ANY, BuiltinType.ANY).createBinaryComparator();
        this.initialized = false;
    }

    public synchronized void update(byte[] data, int start, int len) throws HyracksDataException {
        if (!initialized) {
            byte[] copy = new byte[len];
            System.arraycopy(data, start, copy, 0, len);
            thresholdSnapshot = copy;
            thresholdSnapshotLength = len;
            initialized = true;
            dirty.set(true);
            return;
        }
        int cmp = comparator.compare(data, start, len, thresholdSnapshot, 0, thresholdSnapshotLength);
        if ((isMax && cmp > 0) || (!isMax && cmp < 0)) {
            byte[] copy = new byte[len];
            System.arraycopy(data, start, copy, 0, len);
            thresholdSnapshotLength = len;
            thresholdSnapshot = copy;
            dirty.set(true);
        }
    }

    public boolean passes(byte[] data, int start, int len, IBinaryComparator threadLocalComparator)
            throws HyracksDataException {
        if (!initialized) {
            return true;
        }
        byte[] snap = thresholdSnapshot;
        int snapLen = thresholdSnapshotLength;
        int cmp = threadLocalComparator.compare(data, start, len, snap, 0, snapLen);
        if (strictComparison) {
            return isMax ? cmp > 0 : cmp < 0;
        }
        return isMax ? cmp >= 0 : cmp <= 0;
    }

    public synchronized void mergeGlobal(byte[] data, int start, int len) throws HyracksDataException {
        update(data, start, len);
    }

    public boolean isDirty() {
        return dirty.get();
    }

    public void clearDirty() {
        dirty.set(false);
    }

    public synchronized byte[] getThresholdSnapshot() {
        if (!initialized) {
            return null;
        }
        byte[] copy = new byte[thresholdSnapshotLength];
        System.arraycopy(thresholdSnapshot, 0, copy, 0, thresholdSnapshotLength);
        return copy;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public boolean isMax() {
        return isMax;
    }
}
