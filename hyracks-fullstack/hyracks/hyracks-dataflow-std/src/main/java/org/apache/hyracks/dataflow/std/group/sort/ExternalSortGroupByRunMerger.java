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
package org.apache.hyracks.dataflow.std.group.sort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.hyracks.api.comm.IFrameWriter;
import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.dataflow.value.IBinaryComparator;
import org.apache.hyracks.api.dataflow.value.INormalizedKeyComputer;
import org.apache.hyracks.api.dataflow.value.RecordDescriptor;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.io.FileReference;
import org.apache.hyracks.dataflow.common.io.GeneratedRunFileReader;
import org.apache.hyracks.dataflow.common.io.RunFileWriter;
import org.apache.hyracks.dataflow.std.group.IAggregatorDescriptorFactory;
import org.apache.hyracks.dataflow.std.group.preclustered.PreclusteredGroupWriter;
import org.apache.hyracks.dataflow.std.sort.AbstractExternalSortRunMerger;
import org.apache.hyracks.dataflow.std.util.SmartRabbitHybridExecutionDirResolver;

/**
 * Group-by aggregation is pushed into multi-pass merge of external sort.
 *
 * @author yingyib
 */
public class ExternalSortGroupByRunMerger extends AbstractExternalSortRunMerger {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final String B2I_LOG_FILENAME = "B2I_stdout.log";

    private final RecordDescriptor inputRecordDesc;
    private final RecordDescriptor partialAggRecordDesc;
    private final RecordDescriptor outRecordDesc;

    private final int[] groupFields;
    private final IAggregatorDescriptorFactory mergeAggregatorFactory;
    private final IAggregatorDescriptorFactory partialAggregatorFactory;
    private final boolean localSide;

    private final int[] mergeSortFields;
    private final int[] mergeGroupFields;
    private final IBinaryComparator[] groupByComparators;

    private final boolean isGlobalGBY;

    // SmartRabbit signaling/logging
    private final Path hybridDir;
    private final Path b2iLogPath;

    public ExternalSortGroupByRunMerger(IHyracksTaskContext ctx, List<GeneratedRunFileReader> runs, int[] sortFields,
            RecordDescriptor inRecordDesc, RecordDescriptor partialAggRecordDesc, RecordDescriptor outRecordDesc,
            int framesLimit, int[] groupFields, INormalizedKeyComputer nmk, IBinaryComparator[] comparators,
            IAggregatorDescriptorFactory partialAggregatorFactory, IAggregatorDescriptorFactory aggregatorFactory,
            boolean localStage, boolean isGlobalGBY) throws IOException {

        super(ctx, runs, comparators, nmk, partialAggRecordDesc, framesLimit);

        this.inputRecordDesc = inRecordDesc;
        this.partialAggRecordDesc = partialAggRecordDesc;
        this.outRecordDesc = outRecordDesc;

        this.groupFields = groupFields;
        this.mergeAggregatorFactory = aggregatorFactory;
        this.partialAggregatorFactory = partialAggregatorFactory;
        this.localSide = localStage;

        this.isGlobalGBY = isGlobalGBY;

        this.hybridDir = SmartRabbitHybridExecutionDirResolver.resolve(ctx);
        this.b2iLogPath = hybridDir.resolve(B2I_LOG_FILENAME);

        // Signal only when asked (e.g., global group-by stage)
        maybeSendB2ISignalAndWait(isGlobalGBY);

        // Create merge sort fields: output of merge uses [0..numSortFields-1]
        int numSortFields = sortFields.length;
        this.mergeSortFields = new int[numSortFields];
        for (int i = 0; i < numSortFields; i++) {
            mergeSortFields[i] = i;
        }

        // Create merge group fields: output of partial agg uses [0..numGroupFields-1]
        int numGroupFields = groupFields.length;
        this.mergeGroupFields = new int[numGroupFields];
        for (int i = 0; i < numGroupFields; i++) {
            mergeGroupFields[i] = i;
        }

        // Setup comparators for grouping (first k comparators correspond to group prefix)
        int k = Math.min(mergeGroupFields.length, comparators.length);
        this.groupByComparators = new IBinaryComparator[k];
        for (int i = 0; i < k; i++) {
            groupByComparators[i] = comparators[i];
        }
    }

    /**
     * Backward-compatible constructor: no signaling by default.
     * (Equivalent to isGlobalGBY = false)
     */
    public ExternalSortGroupByRunMerger(IHyracksTaskContext ctx, List<GeneratedRunFileReader> runs, int[] sortFields,
            RecordDescriptor inRecordDesc, RecordDescriptor partialAggRecordDesc, RecordDescriptor outRecordDesc,
            int framesLimit, int[] groupFields, INormalizedKeyComputer nmk, IBinaryComparator[] comparators,
            IAggregatorDescriptorFactory partialAggregatorFactory, IAggregatorDescriptorFactory aggregatorFactory,
            boolean localStage) throws IOException {
        this(ctx, runs, sortFields, inRecordDesc, partialAggRecordDesc, outRecordDesc, framesLimit, groupFields, nmk,
                comparators, partialAggregatorFactory, aggregatorFactory, localStage, false);
    }

    private void logBoth(String msg) {
        System.out.println(msg);

        // Best-effort append. No locks, no synchronization.
        try {
            Files.createDirectories(b2iLogPath.getParent());
            Files.writeString(b2iLogPath, msg + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void maybeSendB2ISignalAndWait(boolean shouldSignal) throws IOException {
        if (!shouldSignal) {
            return;
        }

        final Path b2iPath = hybridDir.resolve("B2ISignal");
        final Instant startTime = Instant.now();

        logBoth("This is when a B2I Signal goes out at " + LocalDateTime.now().format(TS_FMT));
        logBoth("Sent signal to " + b2iPath.toAbsolutePath() + " to stop interactive processing at "
                + LocalDateTime.now().format(TS_FMT));

        // Match ResultWriter's check for B2ISignal: "Yes."
        final String b2iPayload = "Yes.";
        Files.writeString(b2iPath, b2iPayload, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        logBoth("Wrote B2ISignal payload '" + b2iPayload + "' to " + b2iPath.toAbsolutePath() + " at "
                + LocalDateTime.now().format(TS_FMT));

        final int requiredAcks = 1; // keep consistent with your current behavior
        final Set<Path> confirmedFiles = new HashSet<>();

        while (true) {
            try (Stream<Path> files =
                    Files.list(hybridDir).filter(p -> p.getFileName().toString().startsWith("I2BSignal"))) {

                files.forEach(path -> {
                    if (confirmedFiles.contains(path)) {
                        return;
                    }
                    try {
                        String content = Files.readString(path).trim();
                        // ResultWriter writes "Yes" (no dot) to I2BSignal
                        if ("Yes".equals(content)) {
                            confirmedFiles.add(path);
                            logBoth("Received ok from: " + path.getFileName() + " at "
                                    + LocalDateTime.now().format(TS_FMT));
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
            }

            if (confirmedFiles.size() >= requiredAcks) {
                logBoth("Received " + confirmedFiles.size() + " 'Yes' signal(s) from interactive plan at "
                        + LocalDateTime.now().format(TS_FMT));
                break;
            }

            if (Duration.between(startTime, Instant.now()).getSeconds() > 180) {
                logBoth("Timeout: Did not receive " + requiredAcks + " 'Yes' signal(s) within 3 minutes. ("
                        + LocalDateTime.now().format(TS_FMT) + ")");
                throw new IOException(
                        "Timeout: Did not receive " + requiredAcks + " 'Yes' signal(s) within 3 minutes.");
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                logBoth("Interrupted while waiting for I2BSignal at " + LocalDateTime.now().format(TS_FMT));
                throw new IOException("Interrupted while waiting for I2BSignal", ie);
            }
        }
    }

    @Override
    public IFrameWriter prepareSkipMergingFinalResultWriter(IFrameWriter nextWriter) throws HyracksDataException {
        IAggregatorDescriptorFactory aggregatorFactory = localSide ? partialAggregatorFactory : mergeAggregatorFactory;
        return new PreclusteredGroupWriter(ctx, groupFields, groupByComparators, aggregatorFactory, inputRecordDesc,
                outRecordDesc, nextWriter, false);
    }

    @Override
    protected RunFileWriter prepareIntermediateMergeRunFile() throws HyracksDataException {
        FileReference newRun = ctx.createManagedWorkspaceFile(ExternalSortGroupByRunMerger.class.getSimpleName());
        return new RunFileWriter(newRun, ctx.getIoManager());
    }

    @Override
    protected IFrameWriter prepareIntermediateMergeResultWriter(RunFileWriter mergeFileWriter)
            throws HyracksDataException {
        // Note: original logic preserved
        IAggregatorDescriptorFactory aggregatorFactory = localSide ? mergeAggregatorFactory : partialAggregatorFactory;
        return new PreclusteredGroupWriter(ctx, mergeGroupFields, groupByComparators, aggregatorFactory,
                partialAggRecordDesc, partialAggRecordDesc, mergeFileWriter, true);
    }

    @Override
    public IFrameWriter prepareFinalMergeResultWriter(IFrameWriter nextWriter) throws HyracksDataException {
        return new PreclusteredGroupWriter(ctx, mergeGroupFields, groupByComparators, mergeAggregatorFactory,
                partialAggRecordDesc, outRecordDesc, nextWriter, false);
    }

    @Override
    protected int[] getSortFields() {
        return mergeSortFields;
    }
}