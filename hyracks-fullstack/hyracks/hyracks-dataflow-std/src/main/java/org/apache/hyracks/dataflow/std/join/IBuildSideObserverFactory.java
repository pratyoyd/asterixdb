package org.apache.hyracks.dataflow.std.join;

import java.io.Serializable;

import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.exceptions.HyracksDataException;

/**
 * Serializable factory for creating build-side observers.
 * Transported from CC to NC as part of the job specification.
 */
public interface IBuildSideObserverFactory extends Serializable {

    /**
     * Creates an observer for the given partition.
     *
     * @param ctx          task context (provides JobId for registry lookup)
     * @param partitionIdx the partition index of this NC (0-based)
     */
    IBuildSideObserver createObserver(IHyracksTaskContext ctx, int partitionIdx) throws HyracksDataException;
}
