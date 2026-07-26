package org.apache.hyracks.dataflow.std.connectors;

import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.dataflow.common.comm.io.FrameTupleAccessor;

/**
 * Filter applied during hash-partition exchange routing.
 * Called after the destination partition is computed, before the tuple is sent.
 */
public interface IPlaqueExchangeFilter {

    /**
     * @param accessor       frame tuple accessor
     * @param tupleIndex     index of the tuple in the frame
     * @param destPartition  the destination partition (NC) the tuple would be routed to
     * @return true if the tuple should be sent, false to drop it
     */
    boolean passes(FrameTupleAccessor accessor, int tupleIndex, int destPartition) throws HyracksDataException;

    /**
     * Called when the exchange closes. For logging statistics.
     */
    void close() throws HyracksDataException;
}
