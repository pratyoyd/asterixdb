package org.apache.hyracks.dataflow.std.connectors;

import org.apache.hyracks.api.comm.IPartitionWriterFactory;
import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.dataflow.value.ITuplePartitionComputer;
import org.apache.hyracks.api.dataflow.value.RecordDescriptor;
import org.apache.hyracks.api.exceptions.HyracksDataException;

/**
 * Partition data writer with PLAQUE exchange filter.
 * After computing the destination partition, checks the filter condition.
 * If the tuple fails, it is dropped before being sent across the exchange.
 */
public class PlaqueFilteringPartitionDataWriter extends AbstractPartitionDataWriter {

    private final ITuplePartitionComputer tpc;
    private final IPlaqueExchangeFilter filter;

    public PlaqueFilteringPartitionDataWriter(IHyracksTaskContext ctx, int consumerPartitionCount,
            IPartitionWriterFactory pwFactory, RecordDescriptor recordDescriptor, ITuplePartitionComputer tpc,
            IPlaqueExchangeFilter filter) throws HyracksDataException {
        super(ctx, consumerPartitionCount, pwFactory, recordDescriptor);
        this.tpc = tpc;
        this.filter = filter;
    }

    @Override
    public void open() throws HyracksDataException {
        super.open();
        tpc.initialize();
    }

    @Override
    protected void processTuple(int tupleIndex) throws HyracksDataException {
        int p = tpc.partition(tupleAccessor, tupleIndex, consumerPartitionCount);
        if (filter == null || filter.passes(tupleAccessor, tupleIndex, p)) {
            appendToPartitionWriter(tupleIndex, p);
        }
    }

    @Override
    public void close() throws HyracksDataException {
        if (filter != null) {
            filter.close();
        }
        super.close();
    }
}
