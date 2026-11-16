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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.apache.asterix.external.util.ExternalDataConstants.IcebergOptions;
import org.apache.asterix.om.types.ATypeTag;
import org.apache.asterix.runtime.operators.plaque.PlaqueThresholdRegistry;
import org.apache.asterix.runtime.operators.plaque.PlaqueThresholdState;
import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.Schema;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.types.Conversions;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;

/**
 * PLAQUE runtime data skipping for an Iceberg scan. The compiler names a column and the handle of a threshold that
 * the query learns while it runs (the best MAX/MIN seen so far). Before a data file is opened, its manifest bound for
 * that column is compared with the current threshold: a file that cannot beat it is not read at all, and any other
 * file is read with {@code column >= threshold} (MAX) or {@code column <= threshold} (MIN) as a Parquet row-group
 * filter. Rows equal to the threshold are kept, as the PLAQUE tuple filter above the scan keeps them.
 * <p>
 * Only top-level long and double columns are supported; anything else, a missing bound, or a double column whose file
 * may hold NaN (which the bounds do not cover) reads the file as if this class were not there.
 */
final class PlaqueIcebergScanFilter {

    private final PlaqueThresholdState state;
    private final boolean isMax;
    private final Types.NestedField field;
    private final boolean isDouble;

    private PlaqueIcebergScanFilter(PlaqueThresholdState state, boolean isMax, Types.NestedField field) {
        this.state = state;
        this.isMax = isMax;
        this.field = field;
        this.isDouble = field.type().typeId() == Type.TypeID.DOUBLE;
    }

    /** The column if it can be used for skipping, else {@code null}. */
    static Types.NestedField supportedField(Schema schema, String name) {
        Types.NestedField f = name == null ? null : schema.asStruct().field(name);
        if (f == null) {
            return null;
        }
        Type.TypeID id = f.type().typeId();
        return id == Type.TypeID.LONG || id == Type.TypeID.DOUBLE ? f : null;
    }

    /** @return the scan's filter, or {@code null} when the query has none for this scan */
    static PlaqueIcebergScanFilter create(Map<String, String> configuration, Schema schema, IHyracksTaskContext ctx) {
        String handle = configuration.get(IcebergOptions.PLAQUE_SCAN_HANDLE);
        Types.NestedField f = supportedField(schema, configuration.get(IcebergOptions.PLAQUE_SCAN_FIELD));
        if (handle == null || f == null) {
            return null;
        }
        boolean isMax = Boolean.parseBoolean(configuration.get(IcebergOptions.PLAQUE_SCAN_IS_MAX));
        return new PlaqueIcebergScanFilter(
                PlaqueThresholdRegistry.INSTANCE.getOrCreate(ctx.getJobletContext().getJobId(), handle, isMax), isMax,
                f);
    }

    /**
     * The tasks with the most promising files first (highest upper bound for MAX, lowest lower bound for MIN), so the
     * threshold tightens early. Files without a usable bound come first: they have to be read anyway.
     */
    List<FileScanTask> ordered(List<FileScanTask> tasks) {
        List<FileScanTask> sorted = new ArrayList<>(tasks);
        Comparator<FileScanTask> byBound = Comparator.comparingDouble(t -> {
            Number b = bound(t);
            return b == null ? Double.NEGATIVE_INFINITY : isMax ? -b.doubleValue() : b.doubleValue();
        });
        sorted.sort(byBound);
        return sorted;
    }

    /** The current threshold as a Long or Double matching the column, or {@code null} if there is none yet. */
    Number threshold() {
        byte[] s = state.isInitialized() ? state.getThresholdSnapshot() : null;
        if (s == null || s.length != 9) {
            return null;
        }
        long bits = ByteBuffer.wrap(s, 1, 8).getLong();
        if (isDouble) {
            return s[0] == ATypeTag.SERIALIZED_DOUBLE_TYPE_TAG ? (Number) Double.longBitsToDouble(bits) : null;
        }
        return s[0] == ATypeTag.SERIALIZED_INT64_TYPE_TAG ? (Number) bits : null;
    }

    /** The file's upper (MAX) or lower (MIN) bound for the column, or {@code null} if it cannot be relied on. */
    Number bound(FileScanTask task) {
        DataFile file = task.file();
        Map<Integer, ByteBuffer> bounds = isMax ? file.upperBounds() : file.lowerBounds();
        ByteBuffer b = bounds == null ? null : bounds.get(field.fieldId());
        if (b == null) {
            return null;
        }
        if (isDouble) {
            Long nans = file.nanValueCounts() == null ? null : file.nanValueCounts().get(field.fieldId());
            if (nans == null || nans != 0) {
                return null;
            }
        }
        return Conversions.fromByteBuffer(field.type(), b);
    }

    /** Whether no row of a file with this bound can reach the threshold. */
    boolean cannotBeat(Number bound, Number threshold) {
        int cmp = isDouble ? Double.compare(bound.doubleValue(), threshold.doubleValue())
                : Long.compare(bound.longValue(), threshold.longValue());
        return isMax ? cmp < 0 : cmp > 0;
    }

    Expression rowGroupFilter(Number threshold) {
        return isMax ? Expressions.greaterThanOrEqual(field.name(), threshold)
                : Expressions.lessThanOrEqual(field.name(), threshold);
    }
}
