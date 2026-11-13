/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hyracks.dataflow.std.group.preclustered;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import static org.apache.hyracks.dataflow.std.util.ProfilingUtils.profiling;
import java.nio.ByteBuffer;
import java.time.format.DateTimeFormatter;
import java.util.*;

import org.apache.hyracks.api.comm.IFrameTupleAccessor;
import org.apache.hyracks.api.comm.IFrameWriter;
import org.apache.hyracks.api.comm.VSizeFrame;
import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.dataflow.value.IBinaryComparator;
import org.apache.hyracks.api.dataflow.value.RecordDescriptor;
import org.apache.hyracks.api.exceptions.ErrorCode;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.job.profiling.IOperatorStats;
import org.apache.hyracks.api.job.profiling.NoOpOperatorStats;
import org.apache.hyracks.data.std.util.ArrayBackedValueStorage;
import org.apache.hyracks.dataflow.common.comm.io.ArrayTupleBuilder;
import org.apache.hyracks.dataflow.common.comm.io.FrameTupleAccessor;
import org.apache.hyracks.dataflow.common.comm.io.FrameTupleAppender;
import org.apache.hyracks.dataflow.common.comm.io.FrameTupleAppenderWrapper;
import org.apache.hyracks.dataflow.common.data.accessors.FrameTupleReference;
import org.apache.hyracks.dataflow.common.data.accessors.ITupleReference;
import org.apache.hyracks.dataflow.common.data.accessors.PermutingFrameTupleReference;
import org.apache.hyracks.dataflow.common.data.accessors.PointableTupleReference;
import org.apache.hyracks.dataflow.std.group.AggregateState;
import org.apache.hyracks.dataflow.std.group.IAggregatorDescriptor;
import org.apache.hyracks.dataflow.std.group.IAggregatorDescriptorFactory;
import org.apache.hyracks.dataflow.std.group.IProfiledAggregatorDescriptor;

public class PreclusteredGroupWriter implements IFrameWriter {
    private final int[] groupFields;
    private final IBinaryComparator[] comparators;
    private final IAggregatorDescriptor aggregator;
    private final AggregateState aggregateState;
    private final FrameTupleAccessor inFrameAccessor;
    private final FrameTupleReference groupFieldsRef;
    private final PointableTupleReference groupFieldsPrevCopy;

    private final FrameTupleAppenderWrapper appenderWrapper;
    private final ArrayTupleBuilder tupleBuilder;
    private final boolean groupAll;
    private final boolean outputPartial;
    private boolean first;
    private boolean isFailed = false;
    private final long memoryLimit;
    private int groupCounter = 0;
    private boolean isInteractive = false;
    private boolean isGlobal = false;
    private final boolean profiling;

    public PreclusteredGroupWriter(IHyracksTaskContext ctx, int[] groupFields, IBinaryComparator[] comparators,
            IAggregatorDescriptorFactory aggregatorFactory, RecordDescriptor inRecordDesc,
            RecordDescriptor outRecordDesc, IFrameWriter writer, boolean outputPartial) throws HyracksDataException {
        this(ctx, groupFields, comparators, aggregatorFactory, inRecordDesc, outRecordDesc, writer, outputPartial,
                false, -1);
    }

    public PreclusteredGroupWriter(IHyracksTaskContext ctx, int[] groupFields, IBinaryComparator[] comparators,
            IAggregatorDescriptorFactory aggregatorFactory, RecordDescriptor inRecordDesc,
            RecordDescriptor outRecordDesc, IFrameWriter writer, boolean outputPartial, boolean groupAll,
            int framesLimit) throws HyracksDataException {
        this(ctx, groupFields, comparators, aggregatorFactory, inRecordDesc, outRecordDesc, writer, outputPartial,
                groupAll, framesLimit, NoOpOperatorStats.INSTANCE);

    }

    public PreclusteredGroupWriter(IHyracksTaskContext ctx, int[] groupFields, IBinaryComparator[] comparators,
            IAggregatorDescriptorFactory aggregatorFactory, RecordDescriptor inRecordDesc,
            RecordDescriptor outRecordDesc, IFrameWriter writer, boolean outputPartial, boolean groupAll,
            int framesLimit, IOperatorStats stats) throws HyracksDataException {
        this.groupFields = groupFields;
        this.comparators = comparators;

        if (framesLimit >= 0 && framesLimit <= 2) {
            throw HyracksDataException.create(ErrorCode.ILLEGAL_MEMORY_BUDGET, "GROUP BY",
                    Long.toString(((long) (framesLimit)) * ctx.getInitialFrameSize()),
                    Long.toString(2L * ctx.getInitialFrameSize()));
        }

        // Deducts input/output frames.
        this.memoryLimit = framesLimit <= 0 ? -1 : ((long) (framesLimit - 2)) * ctx.getInitialFrameSize();
        if (profiling(ctx)) {
            IProfiledAggregatorDescriptor profAgg = aggregatorFactory.createProfiledAggregator(ctx, inRecordDesc,
                    outRecordDesc, groupFields, groupFields, writer, this.memoryLimit, stats);
            this.profiling = true;
            this.aggregator = profAgg;
        } else {
            this.profiling = false;
            this.aggregator = aggregatorFactory.createAggregator(ctx, inRecordDesc, outRecordDesc, groupFields,
                    groupFields, writer, this.memoryLimit);
        }

        this.aggregateState = aggregator.createAggregateStates();
        inFrameAccessor = new FrameTupleAccessor(inRecordDesc);
        groupFieldsRef = new PermutingFrameTupleReference(groupFields);
        groupFieldsPrevCopy = PointableTupleReference.create(groupFields.length, ArrayBackedValueStorage::new);
        VSizeFrame outFrame = ctx.allocateVSizeFrame();
        FrameTupleAppender appender = new FrameTupleAppender();
        appender.reset(outFrame, true);
        appenderWrapper = new FrameTupleAppenderWrapper(appender, writer);

        tupleBuilder = new ArrayTupleBuilder(outRecordDesc.getFields().length);
        this.outputPartial = outputPartial;
        this.groupAll = groupAll;
    }

    public PreclusteredGroupWriter(IHyracksTaskContext ctx, int[] groupFields, IBinaryComparator[] comparators,
            IAggregatorDescriptorFactory aggregatorFactory, RecordDescriptor inRecordDesc,
            RecordDescriptor outRecordDesc, IFrameWriter writer, boolean outputPartial, boolean groupAll,
            int framesLimit, boolean isInteractive, boolean isGlobal) throws HyracksDataException {
        this(ctx, groupFields, comparators, aggregatorFactory, inRecordDesc, outRecordDesc, writer, outputPartial,
                groupAll, framesLimit, isInteractive, isGlobal, NoOpOperatorStats.INSTANCE);
    }

    public PreclusteredGroupWriter(IHyracksTaskContext ctx, int[] groupFields, IBinaryComparator[] comparators,
            IAggregatorDescriptorFactory aggregatorFactory, RecordDescriptor inRecordDesc,
            RecordDescriptor outRecordDesc, IFrameWriter writer, boolean outputPartial, boolean groupAll,
            int framesLimit, boolean isInteractive, boolean isGlobal, IOperatorStats stats)
            throws HyracksDataException {
        this(ctx, groupFields, comparators, aggregatorFactory, inRecordDesc, outRecordDesc, writer, outputPartial,
                groupAll, framesLimit, stats);
        this.isInteractive = isInteractive;
        this.isGlobal = isGlobal;
    }

    @Override
    public void open() throws HyracksDataException {
        appenderWrapper.open();
        first = true;
    }

    @Override
    public void nextFrame(ByteBuffer buffer) throws HyracksDataException {
        inFrameAccessor.reset(buffer);
        int nTuples = inFrameAccessor.getTupleCount();
        if (nTuples != 0) {
            for (int i = 0; i < nTuples; ++i) {
                if (first) {
                    tupleBuilder.reset();
                    for (int groupFieldIdx : groupFields) {
                        tupleBuilder.addField(inFrameAccessor, i, groupFieldIdx);
                    }
                    aggregator.init(tupleBuilder, inFrameAccessor, i, aggregateState);
                    first = false;
                } else {
                    if (i == 0) {
                        try {
                            switchGroupIfRequired(groupFieldsPrevCopy, inFrameAccessor, 0);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    } else {
                        groupFieldsRef.reset(inFrameAccessor, i - 1);
                        try {
                            switchGroupIfRequired(groupFieldsRef, inFrameAccessor, i);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
            groupFieldsRef.reset(inFrameAccessor, nTuples - 1);
            groupFieldsPrevCopy.set(groupFieldsRef);
        }
    }

    private void switchGroupIfRequired(ITupleReference prevTupleGroupFields, IFrameTupleAccessor currTupleAccessor,
            int currTupleIndex) throws IOException {
        if (!sameGroup(prevTupleGroupFields, currTupleAccessor, currTupleIndex, groupFields, comparators)) {

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
            groupCounter++;
            if (groupCounter % 1 == 0 && isInteractive) {
                //if(isGlobal) System.out.println("Total groups seen in PGW Global  = " + groupCounter+" at: " + LocalDateTime.now().format(formatter));
                //else System.out.println("Total groups seen in PGW Local  = " + groupCounter+" at: " + LocalDateTime.now().format(formatter));
                writeOutput(prevTupleGroupFields);
                appenderWrapper.flush();

            } else {
                writeOutput(prevTupleGroupFields);
            }
            //                        if(isInteractive) {
            //                            String groupHash = hashSerializedGroupKey(currTupleAccessor, currTupleIndex, groupFields);
            //                            String identity = InetAddress.getLocalHost().getHostName() + "_" + Thread.currentThread().getId();
            //
            //                            String barrierDir = ("/scratch/asterixdb/results/HybridExecution/GroupBarriers/");
            //
            //                            //String barrierFile = barrierDir + "group_" + groupCounter + ".txt";
            //                            Files.createDirectories(Paths.get(barrierDir));
            //
            //                            //String identity = InetAddress.getLocalHost().getHostName() + "_" + Thread.currentThread().getId();
            //                            String barrierFileName = "group_" + groupHash + "_" + identity + ".txt";
            //                            Path barrierFile = Paths.get(barrierDir, barrierFileName);
            //
            //                            if (!Files.exists(barrierFile)) {
            //                                Files.write(barrierFile, "".getBytes(), StandardOpenOption.CREATE);
            //                            }
            //                            if (!isGlobal) {
            //                                // Wait until 4 unique files for this group key exist
            //                                while (true) {
            //                                    try (Stream<Path> files = Files.list(Paths.get(barrierDir))) {
            //                                        long count = files
            //                                                .filter(p -> p.getFileName().toString().startsWith("group_" + groupHash + "_"))
            //                                                .count();
            //                                        if (count >= 4) break;
            //                                    }
            //                                    try {
            //                                        Thread.sleep(10);
            //                                    } catch (InterruptedException e) {
            //                                        throw new RuntimeException(e);
            //                                    }
            //                                }
            //                            }
            //                        }

            tupleBuilder.reset();
            for (int groupFieldIdx : groupFields) {
                tupleBuilder.addField(currTupleAccessor, currTupleIndex, groupFieldIdx);
            }
            aggregator.init(tupleBuilder, currTupleAccessor, currTupleIndex, aggregateState);
        } else {
            aggregator.aggregate(currTupleAccessor, currTupleIndex, null, 0, aggregateState);
        }
    }

    private void writeOutput(ITupleReference lastTupleGroupFields) throws HyracksDataException {
        tupleBuilder.reset();
        for (int i = 0; i < groupFields.length; i++) {
            tupleBuilder.addField(lastTupleGroupFields, i);
        }
        boolean hasOutput = outputPartial ? aggregator.outputPartialResult(tupleBuilder, null, 0, aggregateState)
                : aggregator.outputFinalResult(tupleBuilder, null, 0, aggregateState);
        if (hasOutput) {
            appenderWrapper.appendSkipEmptyField(tupleBuilder.getFieldEndOffsets(), tupleBuilder.getByteArray(), 0,
                    tupleBuilder.getSize());
        }
    }

    public static boolean sameGroup(ITupleReference prevTupleGroupFields, IFrameTupleAccessor curTupleAccessor,
            int curTupleIdx, int[] curTupleGroupFields, IBinaryComparator[] comparators) throws HyracksDataException {
        for (int i = 0; i < comparators.length; ++i) {
            byte[] prevTupleFieldData = prevTupleGroupFields.getFieldData(i);
            int prevTupleFieldStart = prevTupleGroupFields.getFieldStart(i);
            int prevTupleFieldLength = prevTupleGroupFields.getFieldLength(i);

            byte[] curTupleFieldData = curTupleAccessor.getBuffer().array();
            int curTupleFieldIdx = curTupleGroupFields[i];
            int curTupleFieldStart = curTupleAccessor.getAbsoluteFieldStartOffset(curTupleIdx, curTupleFieldIdx);
            int curTupleFieldLength = curTupleAccessor.getFieldLength(curTupleIdx, curTupleFieldIdx);

            if (comparators[i].compare(prevTupleFieldData, prevTupleFieldStart, prevTupleFieldLength, curTupleFieldData,
                    curTupleFieldStart, curTupleFieldLength) != 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void fail() throws HyracksDataException {
        isFailed = true;
        appenderWrapper.fail();
    }

    @Override
    public void close() throws HyracksDataException {
        try {
            if (!isFailed && (!first || groupAll)) {
                writeOutput(groupFieldsPrevCopy);
                appenderWrapper.flush();
            }
            aggregator.close();
            aggregateState.close();
        } catch (Exception e) {
            appenderWrapper.fail();
            throw e;
        } finally {
            appenderWrapper.close();
        }
    }

    private String hashSerializedGroupKey(IFrameTupleAccessor accessor, int tupleIndex, int[] groupFields) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (int fieldIdx : groupFields) {
            int start = accessor.getAbsoluteFieldStartOffset(tupleIndex, fieldIdx);
            int len = accessor.getFieldLength(tupleIndex, fieldIdx);
            baos.write(accessor.getBuffer().array(), start, len);
        }
        byte[] data = baos.toByteArray();
        return Integer.toHexString(Arrays.hashCode(data)); // short hash, dev-safe
    }

    public void computeTimings() {
        if (profiling) {
            ((IProfiledAggregatorDescriptor) aggregator).computeTimings();
        }
    }
}
