package org.apache.hyracks.dataflow.std.join;

import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.dataflow.common.comm.io.FrameTupleAccessor;

/**
 * Observer for build-side tuples during hash join build phase.
 * Called once per build tuple to track column statistics (e.g., running min/max).
 * Results are published when build completes.
 */
public interface IBuildSideObserver {

    /**
     * Called for each build tuple during the build phase.
     */
    void observe(FrameTupleAccessor accessor, int tupleIndex) throws HyracksDataException;

    /**
     * Called when the build phase completes. Publishes accumulated results
     * to shared state so the probe-side filter can use them.
     */
    void publishResult() throws HyracksDataException;
}
