package org.apache.hyracks.dataflow.std.connectors;

import java.io.Serializable;

import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.exceptions.HyracksDataException;

/**
 * Serializable factory for creating exchange filters.
 * Transported from CC to NC as part of the connector descriptor.
 */
public interface IPlaqueExchangeFilterFactory extends Serializable {

    IPlaqueExchangeFilter createFilter(IHyracksTaskContext ctx) throws HyracksDataException;
}
