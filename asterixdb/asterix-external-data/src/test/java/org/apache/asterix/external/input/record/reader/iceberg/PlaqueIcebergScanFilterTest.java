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
package org.apache.asterix.external.input.record.reader.iceberg;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.asterix.external.util.ExternalDataConstants.IcebergOptions;
import org.apache.asterix.om.types.ATypeTag;
import org.apache.asterix.runtime.operators.plaque.PlaqueThresholdRegistry;
import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.job.JobId;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.Schema;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.UnboundPredicate;
import org.apache.iceberg.types.Conversions;
import org.apache.iceberg.types.Types;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class PlaqueIcebergScanFilterTest {

    private static final Schema SCHEMA = new Schema(Types.NestedField.required(1, "k", Types.LongType.get()),
            Types.NestedField.optional(2, "d", Types.DoubleType.get()),
            Types.NestedField.optional(3, "i", Types.IntegerType.get()),
            Types.NestedField.optional(4, "s", Types.StringType.get()));
    private static final String HANDLE = "h";
    private static final JobId JOB = new JobId(987654321L);

    @After
    public void cleanUp() {
        PlaqueThresholdRegistry.INSTANCE.removeJob(JOB);
    }

    @Test
    public void onlyTopLevelLongAndDoubleColumnsAreSupported() {
        Assert.assertNotNull(PlaqueIcebergScanFilter.supportedField(SCHEMA, "k"));
        Assert.assertNotNull(PlaqueIcebergScanFilter.supportedField(SCHEMA, "d"));
        Assert.assertNull(PlaqueIcebergScanFilter.supportedField(SCHEMA, "i"));
        Assert.assertNull(PlaqueIcebergScanFilter.supportedField(SCHEMA, "s"));
        Assert.assertNull(PlaqueIcebergScanFilter.supportedField(SCHEMA, "missing"));
        Assert.assertNull(PlaqueIcebergScanFilter.supportedField(SCHEMA, null));
    }

    @Test
    public void noFilterWithoutHandleOrSupportedColumn() {
        Assert.assertNull(PlaqueIcebergScanFilter.create(new HashMap<>(), SCHEMA, ctx()));
        Assert.assertNull(PlaqueIcebergScanFilter.create(config("s", true), SCHEMA, ctx()));
        Assert.assertNotNull(PlaqueIcebergScanFilter.create(config("k", true), SCHEMA, ctx()));
    }

    @Test
    public void thresholdIsNullUntilLearnedAndWhenItsTypeDiffersFromTheColumn() throws Exception {
        PlaqueIcebergScanFilter onLong = filter("k", true);
        PlaqueIcebergScanFilter onDouble = filter("d", true);
        Assert.assertNull(onLong.threshold());

        setThreshold(true, ATypeTag.SERIALIZED_INT64_TYPE_TAG, 42L);
        Assert.assertEquals(42L, onLong.threshold());
        // a bigint threshold must not be compared with a double column's bounds
        Assert.assertNull(onDouble.threshold());
    }

    @Test
    public void doubleThresholdIsDecoded() throws Exception {
        PlaqueIcebergScanFilter onDouble = filter("d", false);
        setThreshold(false, ATypeTag.SERIALIZED_DOUBLE_TYPE_TAG, Double.doubleToLongBits(0.25));
        Assert.assertEquals(0.25, onDouble.threshold());
        Assert.assertNull(filter("k", false).threshold());
    }

    @Test
    public void boundIsUpperForMaxAndLowerForMin() {
        FileScanTask task = task(longBounds(1, 10L), longBounds(1, 90L), null);
        Assert.assertEquals(90L, filter("k", true).bound(task));
        Assert.assertEquals(10L, filter("k", false).bound(task));
    }

    @Test
    public void missingBoundMeansTheFileIsRead() {
        Assert.assertNull(filter("k", true).bound(task(null, null, null)));
        Assert.assertNull(filter("k", true).bound(task(longBounds(1, 10L), Collections.emptyMap(), null)));
    }

    @Test
    public void doubleBoundNeedsAZeroNanCount() {
        Map<Integer, ByteBuffer> upper =
                Collections.singletonMap(2, Conversions.toByteBuffer(Types.DoubleType.get(), 0.5));
        PlaqueIcebergScanFilter f = filter("d", true);
        Assert.assertNull(f.bound(task(null, upper, null)));
        Assert.assertNull(f.bound(task(null, upper, Collections.emptyMap())));
        Assert.assertNull(f.bound(task(null, upper, Collections.singletonMap(2, 3L))));
        Assert.assertEquals(0.5, f.bound(task(null, upper, Collections.singletonMap(2, 0L))));
    }

    @Test
    public void fileIsSkippedUnlessItsBoundIsStrictlyBetter() {
        PlaqueIcebergScanFilter max = filter("k", true);
        Assert.assertTrue(max.cannotBeat(41L, 42L));
        Assert.assertTrue("ties are dropped", max.cannotBeat(42L, 42L));
        Assert.assertFalse(max.cannotBeat(43L, 42L));

        PlaqueIcebergScanFilter min = filter("k", false);
        Assert.assertTrue(min.cannotBeat(43L, 42L));
        Assert.assertTrue("ties are dropped", min.cannotBeat(42L, 42L));
        Assert.assertFalse(min.cannotBeat(41L, 42L));

        PlaqueIcebergScanFilter maxDouble = filter("d", true);
        Assert.assertTrue(maxDouble.cannotBeat(0.04, 0.05));
        Assert.assertTrue(maxDouble.cannotBeat(0.05, 0.05));
        Assert.assertFalse(maxDouble.cannotBeat(0.06, 0.05));
    }

    @Test
    public void rowGroupFilterDropsTies() {
        Assert.assertEquals(Expression.Operation.GT, op(filter("k", true).rowGroupFilter(42L)));
        Assert.assertEquals(Expression.Operation.LT, op(filter("k", false).rowGroupFilter(42L)));
    }

    @Test
    public void tasksAreOrderedMostPromisingFirstAfterThoseWithoutABound() {
        FileScanTask low = task(longBounds(1, 1L), longBounds(1, 10L), null);
        FileScanTask mid = task(longBounds(1, 11L), longBounds(1, 20L), null);
        FileScanTask high = task(longBounds(1, 21L), longBounds(1, 30L), null);
        FileScanTask unknown = task(null, null, null);
        List<FileScanTask> tasks = Arrays.asList(low, unknown, high, mid);

        Assert.assertEquals(Arrays.asList(unknown, high, mid, low), filter("k", true).ordered(tasks));
        Assert.assertEquals(Arrays.asList(unknown, low, mid, high), filter("k", false).ordered(tasks));
        Assert.assertEquals("input list is left alone", Arrays.asList(low, unknown, high, mid), tasks);
    }

    private static Expression.Operation op(Expression e) {
        return ((UnboundPredicate<?>) e).op();
    }

    private static PlaqueIcebergScanFilter filter(String field, boolean isMax) {
        return PlaqueIcebergScanFilter.create(config(field, isMax), SCHEMA, ctx());
    }

    private static Map<String, String> config(String field, boolean isMax) {
        Map<String, String> c = new HashMap<>();
        c.put(IcebergOptions.PLAQUE_SCAN_HANDLE, HANDLE);
        c.put(IcebergOptions.PLAQUE_SCAN_IS_MAX, String.valueOf(isMax));
        c.put(IcebergOptions.PLAQUE_SCAN_FIELD, field);
        return c;
    }

    private static IHyracksTaskContext ctx() {
        IHyracksTaskContext ctx = Mockito.mock(IHyracksTaskContext.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(ctx.getJobletContext().getJobId()).thenReturn(JOB);
        return ctx;
    }

    private static void setThreshold(boolean isMax, byte tag, long bits) throws Exception {
        byte[] value = ByteBuffer.allocate(9).put(tag).putLong(bits).array();
        PlaqueThresholdRegistry.INSTANCE.getOrCreate(JOB, HANDLE, isMax).update(value, 0, value.length);
    }

    private static Map<Integer, ByteBuffer> longBounds(int fieldId, long value) {
        return Collections.singletonMap(fieldId, Conversions.toByteBuffer(Types.LongType.get(), value));
    }

    private static FileScanTask task(Map<Integer, ByteBuffer> lower, Map<Integer, ByteBuffer> upper,
            Map<Integer, Long> nanCounts) {
        DataFile file = Mockito.mock(DataFile.class);
        Mockito.when(file.lowerBounds()).thenReturn(lower);
        Mockito.when(file.upperBounds()).thenReturn(upper);
        Mockito.when(file.nanValueCounts()).thenReturn(nanCounts);
        FileScanTask task = Mockito.mock(FileScanTask.class);
        Mockito.when(task.file()).thenReturn(file);
        return task;
    }
}
