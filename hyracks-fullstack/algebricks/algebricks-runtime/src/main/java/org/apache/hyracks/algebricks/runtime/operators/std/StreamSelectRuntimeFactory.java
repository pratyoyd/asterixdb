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
package org.apache.hyracks.algebricks.runtime.operators.std;

import java.io.BufferedReader;
import java.io.DataOutput;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hyracks.algebricks.data.IBinaryBooleanInspector;
import org.apache.hyracks.algebricks.data.IBinaryBooleanInspectorFactory;
import org.apache.hyracks.algebricks.runtime.base.IScalarEvaluator;
import org.apache.hyracks.algebricks.runtime.base.IScalarEvaluatorFactory;
import org.apache.hyracks.algebricks.runtime.evaluators.EvaluatorContext;
import org.apache.hyracks.algebricks.runtime.operators.base.AbstractOneInputOneOutputOneFieldFramePushRuntime;
import org.apache.hyracks.algebricks.runtime.operators.base.AbstractOneInputOneOutputOneFramePushRuntime;
import org.apache.hyracks.algebricks.runtime.operators.base.AbstractOneInputOneOutputRuntimeFactory;
import org.apache.hyracks.algebricks.runtime.util.DynamicFilterSerializer;
import org.apache.hyracks.api.context.IEvaluatorContext;
import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.dataflow.value.IMissingWriter;
import org.apache.hyracks.api.dataflow.value.IMissingWriterFactory;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.exceptions.IWarningCollector;
import org.apache.hyracks.data.std.api.IPointable;
import org.apache.hyracks.data.std.primitive.VoidPointable;
import org.apache.hyracks.dataflow.common.comm.io.ArrayTupleBuilder;
import org.apache.hyracks.dataflow.std.util.SmartRabbitHybridExecutionDirResolver;

public class StreamSelectRuntimeFactory extends AbstractOneInputOneOutputRuntimeFactory {

    private static final long serialVersionUID = 1L;
    // Final
    protected final IScalarEvaluatorFactory cond;
    protected final IBinaryBooleanInspectorFactory binaryBooleanInspectorFactory;
    protected final IMissingWriterFactory missingWriterFactory;
    // Mutable
    protected boolean retainMissing;
    private int missingPlaceholderVariableIndex;
    protected boolean isDynamicFilter;
    protected boolean first = true;

    public StreamSelectRuntimeFactory(IScalarEvaluatorFactory cond, int[] projectionList,
            IBinaryBooleanInspectorFactory binaryBooleanInspectorFactory, boolean retainMissing,
            int missingPlaceholderVariableIndex, IMissingWriterFactory missingWriterFactory) {
        super(projectionList);
        this.cond = cond;
        this.binaryBooleanInspectorFactory = binaryBooleanInspectorFactory;
        this.retainMissing = retainMissing;
        this.missingPlaceholderVariableIndex = missingPlaceholderVariableIndex;
        this.missingWriterFactory = missingWriterFactory;
    }

    public StreamSelectRuntimeFactory(IScalarEvaluatorFactory cond, int[] projectionList,
            IBinaryBooleanInspectorFactory binaryBooleanInspectorFactory, boolean retainMissing,
            int missingPlaceholderVariableIndex, IMissingWriterFactory missingWriterFactory, boolean isDynamicFilter) {
        super(projectionList);
        this.cond = cond;

        this.binaryBooleanInspectorFactory = binaryBooleanInspectorFactory;
        this.retainMissing = retainMissing;
        this.missingPlaceholderVariableIndex = missingPlaceholderVariableIndex;
        this.missingWriterFactory = missingWriterFactory;
        this.isDynamicFilter = isDynamicFilter;
    }

    @Override
    public String toString() {
        return "stream-select " + cond.toString();
    }

    @Override
    public AbstractOneInputOneOutputOneFramePushRuntime createOneOutputPushRuntime(final IHyracksTaskContext ctx) {
        final IBinaryBooleanInspector bbi = binaryBooleanInspectorFactory.createBinaryBooleanInspector(ctx);
        return new StreamSelectRuntime(ctx, bbi);
    }

    public void retainMissing(boolean retainMissing, int index) {
        this.retainMissing = retainMissing;
        this.missingPlaceholderVariableIndex = index;
    }

    public IScalarEvaluatorFactory getCond() {
        return cond;
    }

    public IBinaryBooleanInspectorFactory getBinaryBooleanInspectorFactory() {
        return binaryBooleanInspectorFactory;
    }

    public IMissingWriterFactory getMissingWriterFactory() {
        return missingWriterFactory;
    }

    public boolean isRetainMissing() {
        return retainMissing;
    }

    public int getMissingPlaceholderVariableIndex() {
        return missingPlaceholderVariableIndex;
    }

    public int[] getProjectionList() {
        return projectionList;
    }

    public class StreamSelectRuntime extends AbstractOneInputOneOutputOneFieldFramePushRuntime {

        protected final IPointable p = VoidPointable.FACTORY.createPointable();
        protected final IEvaluatorContext ctx;
        protected final IBinaryBooleanInspector bbi;
        protected IScalarEvaluator eval;
        protected IMissingWriter missingWriter;
        protected ArrayTupleBuilder missingTupleBuilder;
        protected byte[] dynamicFilterByte;
        protected String dynamicFilterString;
        protected List<byte[]> dynamicFilterByteList;
        protected List<String> dynamicFilterStringList;
        protected String dataType;

        public StreamSelectRuntime(IHyracksTaskContext ctx, IBinaryBooleanInspector bbi) {
            this.ctx = new EvaluatorContext(ctx, initWarningCollector(ctx));
            this.bbi = bbi;
            dynamicFilterStringList = new ArrayList<>();
            dynamicFilterByteList = new ArrayList<>();

        }

        @Override
        public void open() throws HyracksDataException {
            if (eval == null) {
                initAccessAppendFieldRef(ctx.getTaskContext());
                eval = cond.createScalarEvaluator(ctx);
                if (isDynamicFilter) {
                    System.out.println("This is a dynamic filter evaluator");
                    System.out.println(eval);
                    IHyracksTaskContext taskContext = ctx.getTaskContext();
                    Path filePath =
                            SmartRabbitHybridExecutionDirResolver.resolve(taskContext).resolve("InteractiveAnswers");

                    String maxKey = null;
                    String maxValueStr = null;
                    double maxNumeric = Double.NEGATIVE_INFINITY;
                    String maxLex = null;

                    boolean foundNumeric = false;
                    boolean foundString = false;

                    try (BufferedReader reader = new BufferedReader(new FileReader(filePath.toString()))) {
                        String line;
                        String cleanedValue = "";

                        Pattern kvPattern =
                                Pattern.compile("\"(\\w+)\"\\s*:\\s*(\\d+(\\.\\d+)?|\"[^\"]*\"|true|false|null)");

                        while ((line = reader.readLine()) != null) {
                            Matcher kvMatcher = kvPattern.matcher(line);

                            if (kvMatcher.find()) {
                                maxKey = kvMatcher.group(1);
                                String value = kvMatcher.group(2);

                                // Case 1: Quoted string
                                if (value.startsWith("\"") && value.endsWith("\"")) {
                                    cleanedValue = value.substring(1, value.length() - 1); // remove quotes

                                }
                                // Case 2: Numeric value
                                else {
                                    try {
                                        double numericValue = Double.parseDouble(value);
                                        if (!foundNumeric || numericValue > maxNumeric) {
                                            cleanedValue = value;
                                        }

                                    } catch (NumberFormatException e) {
                                        // Not numeric, ignore
                                    }
                                }
                                dynamicFilterString = cleanedValue;

                                // Prepare the byte array and IPointable
                                byte[] dynamicFilterByte = cleanedValue.getBytes(StandardCharsets.UTF_8);
                                IPointable constantValue = new VoidPointable();
                                constantValue.set(dynamicFilterByte, 0, dynamicFilterByte.length);

                                // Store them
                                dynamicFilterByteList.add(dynamicFilterByte);

                            }
                        }

                        //if (!dynamicFilterStringList.isEmpty()) {
                        //                        System.out.println("Max JSON Key: " + maxKey);
                        //                        System.out.println("Max JSON Value: " + dynamicFilterString);
                        //
                        dataType = inferType(dynamicFilterString);
                        //                        System.out.println(dataType);
                        dynamicFilterByte = DynamicFilterSerializer.serialize(dynamicFilterString, dataType);
                        //                            dynamicFilterByteList.addAll(maxValueStr.getBytes(StandardCharsets.UTF_8);
                        //                            IPointable constantValue = new VoidPointable();
                        //                            constantValue.set(dynamicFilterByte, 0, dynamicFilterByte.length);
                        //                            dynamicFilterString = maxValueStr;
                        //                        } else {
                        //                            System.out.println("No valid key-value pairs found.");
                        //                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

            super.open();
            if (retainMissing && missingWriter == null) {
                missingWriter = missingWriterFactory.createMissingWriter();
                missingTupleBuilder = new ArrayTupleBuilder(1);
                DataOutput out = missingTupleBuilder.getDataOutput();
                missingWriter.writeMissing(out);
                missingTupleBuilder.addFieldEndOffset();
            }
        }

        @Override
        public void nextFrame(ByteBuffer buffer) throws HyracksDataException {
            tAccess.reset(buffer);
            int nTuple = tAccess.getTupleCount();
            if (!isDynamicFilter) {
                for (int t = 0; t < nTuple; t++) {
                    tRef.reset(tAccess, t);
                    eval.evaluate(tRef, p); //store result in p
                    if (bbi.getBooleanValue(p.getByteArray(), p.getStartOffset(), p.getLength())) {
                        if (projectionList != null) {
                            appendProjectionToFrame(t, projectionList);
                        } else {
                            appendTupleToFrame(t);
                        }
                    } else {
                        if (retainMissing) {
                            retainMissingTuple(t);
                        }
                    }
                }
            } else {
                // Serialize the dynamic filter value only once

                for (int t = 0; t < nTuple; t++) {
                    tRef.reset(tAccess, t);
                    int keyFieldIndex = 0;

                    // Extract tagged field from tuple
                    byte[] tupleFieldBytes = tRef.getFieldData(keyFieldIndex);
                    int tupleFieldStart = tRef.getFieldStart(keyFieldIndex);
                    int tupleFieldLength = tRef.getFieldLength(keyFieldIndex);
                    //                        System.out.println("Tuple:  " + toHex(tupleFieldBytes, tupleFieldStart, tupleFieldLength));
                    //                        System.out.println("Filter: " + toHex(dynamicFilterByte, 0, dynamicFilterByte.length));
                    boolean passesFilter =
                            compareBinary(tupleFieldBytes, tupleFieldStart, tupleFieldLength, dynamicFilterByte) >= 0;
                    //                        System.out.println("Compare result: " + passesFilter);

                    // Compare full serialized field with the serialized filter value

                    if (passesFilter) {
                        if (projectionList != null) {
                            appendProjectionToFrame(t, projectionList);
                        } else {
                            appendTupleToFrame(t);
                        }
                    } else {
                        if (retainMissing) {
                            retainMissingTuple(t);
                        }
                    }
                }
            }

            //            } else {

            //                for (int t = 0; t < nTuple; t++) {
            //                    tRef.reset(tAccess, t);
            //                    int keyFieldIndex = 0;
            //                    String extractedValue = null;
            //                    int intValue = 0;
            //                    long longValue = 0;
            //                    if (Objects.equals(dataType, "string")) {
            //                        byte[] keyData = tRef.getFieldData(keyFieldIndex);
            //                        int keyStart = tRef.getFieldStart(keyFieldIndex) + 1;
            //                        int keyLength = tRef.getFieldLength(keyFieldIndex) - 1;
            //                        extractedValue = new String(keyData, keyStart, keyLength, StandardCharsets.UTF_8);
            //                    } else if (Objects.equals(dataType, "int")) {
            //                        byte[] keyData = tRef.getFieldData(keyFieldIndex);
            //                        int keyStart = tRef.getFieldStart(keyFieldIndex) + 1; // Skip type tag
            //                        longValue = ((long) (keyData[keyStart] & 0xFF) << 56)
            //                                | ((long) (keyData[keyStart + 1] & 0xFF) << 48)
            //                                | ((long) (keyData[keyStart + 2] & 0xFF) << 40)
            //                                | ((long) (keyData[keyStart + 3] & 0xFF) << 32)
            //                                | ((long) (keyData[keyStart + 4] & 0xFF) << 24)
            //                                | ((long) (keyData[keyStart + 5] & 0xFF) << 16)
            //                                | ((long) (keyData[keyStart + 6] & 0xFF) << 8)
            //                                | ((long) (keyData[keyStart + 7] & 0xFF));
            //
            //                    }
            //                    //byte[] extractedBytes = extractedValue.getBytes(StandardCharsets.UTF_8);
            //                    //if(compareDates(extractedValue,dynamicFilterString) >= 0)
            //                    boolean passesFilter = false;
            //                    if (Objects.equals(dataType, "date")) {
            //                        passesFilter = compareDatesIn(extractedValue, dynamicFilterStringList);
            //                    } else if (Objects.equals(dataType, "int")) {
            //                        //                        passesFilter = true;
            //                        //                        for(String str : dynamicFilterStringList ) {
            //                        //                            if(Long.parseLong(str) == longValue)passesFilter = false;
            //                        //                        }
            //                        passesFilter = longValue >= Long.parseLong(dynamicFilterString.trim());
            //
            //                    }
            //
            //                    if (passesFilter) {
            //
            //                        if (projectionList != null) {
            //                            appendProjectionToFrame(t, projectionList);
            //                        } else {
            //                            appendTupleToFrame(t);
            //                        }
            //                    } else {
            //
            //                        if (retainMissing) {
            //                            retainMissingTuple(t);
            //                        }
            //                    }
            //                }
            //           }
        }

        @Override
        public void flush() throws HyracksDataException {
            appender.flush(writer);
        }

        protected void retainMissingTuple(int t) throws HyracksDataException {
            for (int i = 0; i < tRef.getFieldCount(); i++) {
                if (i == missingPlaceholderVariableIndex) {
                    appendField(missingTupleBuilder.getByteArray(), 0, missingTupleBuilder.getSize());
                } else {
                    appendField(tAccess, t, i);
                }
            }
        }

        protected IWarningCollector initWarningCollector(IHyracksTaskContext ctx) {
            return ctx.getWarningCollector();
        }

        private int compareValues(byte[] fieldData, byte[] constantValue) {
            String fieldStr = new String(fieldData); // Convert byte array to String
            String constantStr = new String(constantValue);
            if (first) {
                System.out.println(fieldStr);
                System.out.println(constantStr);

                first = false;
            }

            if (isNumeric(fieldStr) && isNumeric(constantStr)) {
                try {
                    double fieldNum = Double.parseDouble(fieldStr);
                    double constantNum = Double.parseDouble(constantStr);
                    return Double.compare(fieldNum, constantNum);
                } catch (NumberFormatException e) {
                    // If parsing fails, fallback to string comparison
                    return fieldStr.compareTo(constantStr);
                }
            }

            return fieldStr.compareTo(constantStr);
        }

        private boolean isNumeric(String str) {
            try {
                Double.parseDouble(str);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        public static int compareDates(String date1, String date2) {
            try {
                date1 = date1.trim().replaceAll("[^\\d-]", ""); // Keep only numbers & hyphens
                date2 = date2.trim().replaceAll("[^\\d-]", ""); // Same cleaning
                LocalDate d1 = LocalDate.parse(date1, DateTimeFormatter.ISO_LOCAL_DATE);
                LocalDate d2 = LocalDate.parse(date2, DateTimeFormatter.ISO_LOCAL_DATE);

                return d1.compareTo(d2);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Invalid date format. Expected YYYY-MM-DD.", e);
            }
        }

        public static boolean compareDatesIn(String compDate, List<String> dateList) {
            try {
                for (String date : dateList) {
                    date = date.trim().replaceAll("[^\\d-]", ""); // Keep only numbers & hyphens
                    compDate = compDate.trim().replaceAll("[^\\d-]", ""); // Same cleaning
                    LocalDate d1 = LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE);
                    LocalDate d2 = LocalDate.parse(compDate, DateTimeFormatter.ISO_LOCAL_DATE);

                    if (d1 == d2)
                        return true;
                }
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Invalid date format. Expected YYYY-MM-DD.", e);
            }
            return false;
        }

        public static int compareInt(int i1, int i2) {

            return Integer.compare(i1, i2);

        }

        public static String inferType(String value) {
            if (value == null) {
                return "null";
            }

            String s = value.trim();

            // boolean
            if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) {
                return "boolean";
            }

            // double (must have . or exponent)
            try {
                if (s.contains(".") || s.contains("e") || s.contains("E")) {
                    Double.parseDouble(s);
                    return "double";
                }
            } catch (NumberFormatException ignore) {
            }

            // int/bigint
            try {
                Long.parseLong(s);
                return "bigint";
            } catch (NumberFormatException ignore) {
            }

            // date
            //            if (isDate(s)) {
            //                return "date";
            //            }

            // fallback
            return "string";
        }

        private static boolean isDate(String value) {
            try {
                OffsetDateTime.parse(value); // handles full ISO 8601 with timezone
                return true;
            } catch (DateTimeParseException e1) {
                try {
                    LocalDateTime.parse(value); // handles ISO local datetime
                    return true;
                } catch (DateTimeParseException e2) {
                    try {
                        LocalDate.parse(value); // handles ISO date (yyyy-MM-dd)
                        return true;
                    } catch (DateTimeParseException e3) {
                        return false;
                    }
                }
            }
        }

        private int compareBinary(byte[] fieldData, int fieldOffset, int fieldLength, byte[] constantBytes) {
            int minLength = Math.min(fieldLength, constantBytes.length);
            for (int i = 0; i < minLength; i++) {
                int b1 = fieldData[fieldOffset + i] & 0xFF;
                int b2 = constantBytes[i] & 0xFF;
                if (b1 != b2) {
                    return Integer.compare(b1, b2);
                }
            }
            return Integer.compare(fieldLength, constantBytes.length);
        }

        private String toHex(byte[] bytes, int start, int length) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < length; i++) {
                sb.append(String.format("%02X ", bytes[start + i]));
            }
            return sb.toString();
        }

    }

}
