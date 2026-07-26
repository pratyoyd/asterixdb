package org.apache.hyracks.dataflow.std.sort;

import org.apache.hyracks.api.exceptions.HyracksDataException;

/**
 * Observer for the K-th value in a Top-K sort heap.
 * Called when the heap root changes (K-th value tightens).
 */
public interface ITopKThresholdObserver {

    /**
     * Called when the K-th value changes (heap root replaced with a better entry).
     *
     * @param data   byte array containing the sort key field
     * @param offset start offset of the sort key field (includes type tag)
     * @param length length of the sort key field in bytes
     */
    void onKthValueChanged(byte[] data, int offset, int length) throws HyracksDataException;
}
