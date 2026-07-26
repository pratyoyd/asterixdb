package org.apache.hyracks.dataflow.std.sort;

import java.io.Serializable;

import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.exceptions.HyracksDataException;

/**
 * Serializable factory for creating Top-K threshold observers.
 * Transported from CC to NC as part of the sort operator descriptor.
 */
public interface ITopKThresholdObserverFactory extends Serializable {

    ITopKThresholdObserver createObserver(IHyracksTaskContext ctx) throws HyracksDataException;
}
