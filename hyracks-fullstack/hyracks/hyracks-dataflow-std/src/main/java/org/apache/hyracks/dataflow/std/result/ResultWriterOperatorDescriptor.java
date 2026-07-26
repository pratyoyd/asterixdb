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
package org.apache.hyracks.dataflow.std.result;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.apache.hyracks.api.comm.IFrame;
import org.apache.hyracks.api.comm.IFrameWriter;
import org.apache.hyracks.api.comm.VSizeFrame;
import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.dataflow.IOperatorNodePushable;
import org.apache.hyracks.api.dataflow.value.IRecordDescriptorProvider;
import org.apache.hyracks.api.dataflow.value.IResultSerializer;
import org.apache.hyracks.api.dataflow.value.IResultSerializerFactory;
import org.apache.hyracks.api.dataflow.value.RecordDescriptor;
import org.apache.hyracks.api.exceptions.ErrorCode;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.exceptions.HyracksException;
import org.apache.hyracks.api.job.IOperatorDescriptorRegistry;
import org.apache.hyracks.api.result.IResultMetadata;
import org.apache.hyracks.api.result.IResultPartitionManager;
import org.apache.hyracks.api.result.ResultSetId;
import org.apache.hyracks.dataflow.common.comm.io.FrameOutputStream;
import org.apache.hyracks.dataflow.common.comm.io.FrameTupleAccessor;
import org.apache.hyracks.dataflow.std.base.AbstractSingleActivityOperatorDescriptor;
import org.apache.hyracks.dataflow.std.base.AbstractUnaryInputSinkOperatorNodePushable;
import org.apache.hyracks.dataflow.std.util.SmartRabbitHybridExecutionDirResolver;

public class ResultWriterOperatorDescriptor extends AbstractSingleActivityOperatorDescriptor {

    private static final long serialVersionUID = 1L;

    private final ResultSetId rsId;
    private final IResultMetadata metadata;
    private final boolean asyncMode;
    private final IResultSerializerFactory resultSerializerFactory;
    private final long maxReads;
    private boolean isExecutionInteractive = false;

    private int totalCount;

    public ResultWriterOperatorDescriptor(IOperatorDescriptorRegistry spec, ResultSetId rsId, IResultMetadata metadata,
            boolean asyncMode, IResultSerializerFactory resultSerializerFactory, long maxReads) throws IOException {
        super(spec, 1, 0);
        this.rsId = rsId;
        this.metadata = metadata;
        this.asyncMode = asyncMode;
        this.resultSerializerFactory = resultSerializerFactory;
        this.maxReads = maxReads;
    }

    public ResultWriterOperatorDescriptor(IOperatorDescriptorRegistry spec, ResultSetId rsId, IResultMetadata metadata,
            boolean asyncMode, IResultSerializerFactory resultSerializerFactory, long maxReads,
            boolean isExecutionInteractive) throws IOException {
        super(spec, 1, 0);
        this.rsId = rsId;
        this.metadata = metadata;
        this.asyncMode = asyncMode;
        this.resultSerializerFactory = resultSerializerFactory;
        this.maxReads = maxReads;
        this.isExecutionInteractive = isExecutionInteractive;
    }

    @Override
    public IOperatorNodePushable createPushRuntime(final IHyracksTaskContext ctx,
            IRecordDescriptorProvider recordDescProvider, final int partition, final int nPartitions)
            throws HyracksDataException {
        final IResultPartitionManager resultPartitionManager = ctx.getResultPartitionManager();

        final IFrame frame = new VSizeFrame(ctx);

        final FrameOutputStream frameOutputStream = new FrameOutputStream(ctx.getInitialFrameSize());
        frameOutputStream.reset(frame, true);
        PrintStream printStream = new PrintStream(frameOutputStream);

        final RecordDescriptor outRecordDesc = recordDescProvider.getInputRecordDescriptor(getActivityId(), 0);
        final IResultSerializer resultSerializer =
                resultSerializerFactory.createResultSerializer(outRecordDesc, printStream);

        final FrameTupleAccessor frameTupleAccessor = new FrameTupleAccessor(outRecordDesc);

        return new AbstractUnaryInputSinkOperatorNodePushable() {
            private IFrameWriter resultPartitionWriter;
            private boolean failed = false;
            private boolean finished;
            private Path baseDir;

            private static void ensureDir(Path dir) {
                try {
                    Files.createDirectories(dir);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to create directory: " + dir.toAbsolutePath(), e);
                }
            }

            private static void ensureParent(Path file) {
                Path parent = file.getParent();
                if (parent != null) {
                    ensureDir(parent);
                }
            }

            @Override
            public void open() throws HyracksDataException {
                try {
                    baseDir = SmartRabbitHybridExecutionDirResolver.resolve(ctx);
                    resultPartitionWriter = resultPartitionManager.createResultPartitionWriter(ctx, rsId, metadata,
                            asyncMode, partition, nPartitions, maxReads);
                    resultPartitionWriter.open();
                    finished = false;
                    resultSerializer.init();
                } catch (HyracksException e) {
                    throw HyracksDataException.create(e);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void nextFrame(ByteBuffer buffer) throws HyracksDataException {
                if (!finished) {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
                    frameTupleAccessor.reset(buffer);
                    Path interactiveRatePath = baseDir.resolve("InteractiveAnswerRate");
                    Path blockingRatePath = baseDir.resolve("BlockingAnswerRate");

                    for (int tIndex = 0; tIndex < frameTupleAccessor.getTupleCount(); tIndex++) {
                        if (totalCount == 0 && !isExecutionInteractive) {
                            System.out.println(
                                    "Tuples outputted:" + totalCount + " at: " + LocalDateTime.now().format(formatter));

                            try {

                                String line = totalCount + "," + LocalDateTime.now().format(formatter)
                                        + System.lineSeparator();
                                ensureParent(blockingRatePath);
                                Files.writeString(blockingRatePath, line, StandardOpenOption.CREATE, // create if missing
                                        StandardOpenOption.APPEND // append instead of truncate
                                );
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }

                        resultSerializer.appendTuple(frameTupleAccessor, tIndex);
                        totalCount++;

                        if (!frameOutputStream.appendTuple()) {
                            //System.out.println("Frame flushed at: " + LocalDateTime.now().format(formatter));

                            frameOutputStream.flush(resultPartitionWriter);

                            // Retry after flushing
                            if (!frameOutputStream.appendTuple()) {
                                throw HyracksDataException.create(ErrorCode.TUPLE_CANNOT_FIT_INTO_EMPTY_FRAME,
                                        frameOutputStream.getLength());
                            }
                        }

                        if (isExecutionInteractive) {
                            if (totalCount % 2 == 1) {
                                System.out.println("Tuples outputted:" + totalCount + " at: "
                                        + LocalDateTime.now().format(formatter));
                                try {

                                    String line = totalCount + "," + LocalDateTime.now().format(formatter)
                                            + System.lineSeparator();
                                    ensureParent(interactiveRatePath);
                                    Files.writeString(interactiveRatePath, line, StandardOpenOption.CREATE, // create if missing
                                            StandardOpenOption.APPEND // append instead of truncate
                                    );
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }

                            }

                            byte[] snapshot = getFrameData();
                            frameOutputStream.flush(resultPartitionWriter);

                            // Write to InteractiveAnswersAll (append)
                            //                            Path allAnswersPath = Paths.get("results", "HybridExecution", "InteractiveAnswersAll");
                            //                            writeToFile(allAnswersPath.toString(),snapshot);

                            // Overwrite InteractiveAnswers (latest flushed)
                            Path latestAnswersPath = baseDir.resolve("InteractiveAnswers");
                            writeToFileOverWrite(latestAnswersPath.toString(), snapshot);

                            // Check B2I signal file
                            Path stopSignalPath = baseDir.resolve("B2ISignal");
                            if (shouldStopProcessing(stopSignalPath.toString())) {
                                Path signalOutPath = baseDir.resolve("I2BSignal");

                                Path countPath = baseDir.resolve("InteractiveAnswerCount");

                                System.out.println("Total outputted answers: " + totalCount);
                                try {
                                    ensureParent(countPath);
                                    Files.writeString(countPath, Integer.toString(totalCount),
                                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                                try {
                                    ensureParent(interactiveRatePath);
                                    String line = totalCount + "," + LocalDateTime.now().format(formatter)
                                            + System.lineSeparator();

                                    Files.writeString(interactiveRatePath, line, StandardOpenOption.CREATE, // create if missing
                                            StandardOpenOption.APPEND // append instead of truncate
                                    );
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                                finished = true;

                                // Write I2B signal

                                try {
                                    ensureParent(signalOutPath);
                                    Files.write(signalOutPath, "Yes".getBytes(), StandardOpenOption.CREATE,
                                            StandardOpenOption.TRUNCATE_EXISTING);
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }

                                resultPartitionWriter.close();
                            }
                        }

                        // Attempt to append tuple, flush if needed

                        // Reset the output stream per tuple
                        frameOutputStream.reset();
                    }
                }
            }

            //@Override
            //public void nextFrame(ByteBuffer buffer) throws HyracksDataException {
            //    frameTupleAccessor.reset(buffer);
            //    for (int tIndex = 0; tIndex < frameTupleAccessor.getTupleCount(); tIndex++) {
            //        resultSerializer.appendTuple(frameTupleAccessor, tIndex);
            //        if (!frameOutputStream.appendTuple()) {
            //            frameOutputStream.flush(resultPartitionWriter);
            //            frameOutputStream.reset();
            //            if (!frameOutputStream.appendTuple()) {
            //                throw HyracksDataException.create(ErrorCode.TUPLE_CANNOT_FIT_INTO_EMPTY_FRAME,
            //                        frameOutputStream.getLength());
            //            }
            //        }
            //    }
            //    // Force flush after processing the frame
            //    frameOutputStream.flush(resultPartitionWriter);
            //}

            @Override
            public void fail() throws HyracksDataException {
                failed = true;
                if (resultPartitionWriter != null) {
                    resultPartitionWriter.fail();
                }
            }

            @Override
            public void close() throws HyracksDataException {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
                Path interactiveRatePath = baseDir.resolve("InteractiveAnswerRate");
                Path blockingRatePath = baseDir.resolve("BlockingAnswerRate");
                Path countPath = baseDir.resolve("InteractiveAnswerCount");
                String line = totalCount + "," + LocalDateTime.now().format(formatter) + System.lineSeparator();

                System.out.println("Total outputted tuples:close(): " + totalCount);
                if (isExecutionInteractive) {

                    System.out.println("Total outputted answers: " + totalCount);
                    try {
                        ensureParent(interactiveRatePath);
                        Files.writeString(countPath, Integer.toString(totalCount), StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    try {

                        ensureParent(interactiveRatePath);
                        Files.writeString(interactiveRatePath, line, StandardOpenOption.CREATE, // create if missing
                                StandardOpenOption.APPEND // append instead of truncate
                        );
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    ensureParent(blockingRatePath);
                    try {
                        Files.writeString(blockingRatePath, line, StandardOpenOption.CREATE, // create if missing
                                StandardOpenOption.APPEND // append instead of truncate
                        );
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }

                }
                if (resultPartitionWriter != null) {
                    try {
                        if (!failed && frameOutputStream.getTupleCount() > 0) {
                            frameOutputStream.flush(resultPartitionWriter);

                        }

                    } catch (Exception e) {
                        resultPartitionWriter.fail();
                        try {
                            throw e;
                        } catch (IOException ex) {
                            throw new RuntimeException(ex);
                        }
                    } finally {
                        resultPartitionWriter.close();
                    }
                }
                if (isExecutionInteractive) {

                    Path signalOutPath = baseDir.resolve("I2BSignal");
                    ensureParent(signalOutPath);
                    try {
                        Files.write(signalOutPath, "Yes".getBytes(), StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }

            @Override
            public String toString() {
                StringBuilder sb = new StringBuilder();
                sb.append("{ ");
                sb.append("\"rsId\": \"").append(rsId).append("\", ");
                sb.append("\"metadata\": ").append(metadata).append(", ");
                sb.append("\"asyncMode\": ").append(asyncMode).append(", ");
                sb.append("\"maxReads\": ").append(maxReads).append(" }");
                return sb.toString();
            }

            private void writeToFile(String filePath, byte[] data) {
                try {

                    // Create parent directories if they don't exist
                    Path parentDir = Paths.get(filePath).getParent();
                    if (parentDir != null && !Files.exists(parentDir)) {
                        Files.createDirectories(parentDir);
                    }
                    Files.write(Paths.get(filePath), data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException e) {
                    System.err.println("Failed to write to file: " + filePath);
                    e.printStackTrace();
                }
            }

            private void writeToFileOverWrite(String filePath, byte[] data) {
                try {
                    Path p = Paths.get(filePath);
                    ensureParent(p);
                    Files.write(p, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (IOException e) {
                    System.err.println("Failed to write to file: " + filePath);
                    e.printStackTrace();
                }
            }

            private byte[] getFrameData() {

                byte[] frameBytes = frameOutputStream.getByteArray();
                int validSize = frameOutputStream.getLength();

                byte[] extractedData = new byte[validSize];
                System.arraycopy(frameBytes, 0, extractedData, 0, validSize);
                return extractedData;
            }

            @Override
            public String getDisplayName() {
                return "Result Writer";
            }

            public boolean shouldStopProcessing(String filePath) {
                try {
                    if (Files.exists(Paths.get(filePath))) { // Check if the file exists
                        String content = Files.readString(Paths.get(filePath), StandardCharsets.UTF_8).trim();
                        return "Yes.".equals(content); // Stop only if content is "YES."
                    }
                } catch (IOException e) {
                    e.printStackTrace(); // Log the error but continue execution
                }
                return false; // Continue processing if file doesn't exist or contains different content
            }
        };
    }
}
