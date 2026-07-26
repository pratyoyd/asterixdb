package org.apache.asterix.column.filter.range.evaluator;

import java.util.logging.Logger;

import org.apache.asterix.column.filter.FilterAccessorProvider;
import org.apache.asterix.column.filter.IColumnFilterEvaluator;
import org.apache.asterix.column.filter.TrueColumnFilterEvaluator;
import org.apache.asterix.column.filter.range.IColumnRangeFilterEvaluatorFactory;
import org.apache.asterix.column.filter.range.IColumnRangeFilterValueAccessor;
import org.apache.asterix.column.filter.range.accessor.ColumnRangeFilterValueAccessor;
import org.apache.asterix.column.filter.range.accessor.NoOpColumnRangeFilterValueAccessor;
import org.apache.asterix.om.types.ARecordType;
import org.apache.asterix.om.types.ATypeTag;
import org.apache.asterix.runtime.operators.plaque.PlaqueThresholdRegistry;
import org.apache.asterix.runtime.operators.plaque.PlaqueThresholdState;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.job.JobId;

/**
 * Page-level PLAQUE filter for columnar storage. Skips entire pages where
 * the page's max (for MAX queries) or min (for MIN queries) cannot beat
 * the current PLAQUE threshold.
 *
 * The factory is serializable and captures only the handle and isMax flag.
 * At runtime, it resolves the PlaqueThresholdState from the NC-level registry.
 */
public class PlaqueColumnFilterEvaluatorFactory implements IColumnRangeFilterEvaluatorFactory {

    private static final long serialVersionUID = 1L;
    private final String handle;
    private final boolean isMax;
    private final ARecordType columnPath;

    public PlaqueColumnFilterEvaluatorFactory(String handle, boolean isMax, ARecordType columnPath) {
        this.handle = handle;
        this.isMax = isMax;
        this.columnPath = columnPath;
    }

    @Override
    public IColumnFilterEvaluator create(FilterAccessorProvider filterAccessorProvider) throws HyracksDataException {
        // Get an accessor for the page's max (for MAX queries) or min (for MIN queries)
        // For MAX: we want the page's max value — if pageMax < threshold, skip the page
        // For MIN: we want the page's min value — if pageMin > threshold, skip the page
        boolean readMin = !isMax;
        IColumnRangeFilterValueAccessor pageAccessor =
                filterAccessorProvider.createRangeFilterValueAccessor(columnPath, readMin);

        if (pageAccessor == NoOpColumnRangeFilterValueAccessor.INSTANCE) {
            return TrueColumnFilterEvaluator.INSTANCE;
        }

        // Resolve PlaqueThresholdState from NC-level registry using JobId from task context
        PlaqueThresholdState state = null;
        org.apache.hyracks.api.context.IHyracksTaskContext ctx = filterAccessorProvider.getTaskContext();
        if (ctx != null) {
            JobId jobId = ctx.getJobletContext().getJobId();
            state = PlaqueThresholdRegistry.INSTANCE.getOrCreate(jobId, handle, isMax);
        }

        int colIdx = -1;
        if (pageAccessor instanceof org.apache.asterix.column.filter.range.accessor.ColumnRangeFilterValueAccessor) {
            colIdx = ((org.apache.asterix.column.filter.range.accessor.ColumnRangeFilterValueAccessor) pageAccessor).getColumnIndex();
        }
        System.out.println("PLAQUE_PAGE_FILTER_CREATE: handle=" + handle
                + " colIdx=" + colIdx
                + " accessorIdentity=" + System.identityHashCode(pageAccessor)
                + " inList=" + filterAccessorProvider.getFilterAccessors().contains(pageAccessor)
                + " listSize=" + filterAccessorProvider.getFilterAccessors().size());

        PlaqueColumnFilterEvaluator evaluator = new PlaqueColumnFilterEvaluator(pageAccessor, handle, isMax);
        evaluator.setThresholdState(state);
        return evaluator;
    }

    @Override
    public String toString() {
        return "PLAQUE_PAGE_FILTER(" + (isMax ? "MAX" : "MIN") + ", " + handle + ")";
    }

    private static class PlaqueColumnFilterEvaluator implements IColumnFilterEvaluator {
        private static final Logger LOGGER = Logger.getLogger(PlaqueColumnFilterEvaluator.class.getName());

        private final IColumnRangeFilterValueAccessor pageAccessor;
        private final String handle;
        private final boolean isMax;
        private PlaqueThresholdState thresholdState;
        private long pagesEvaluated;
        private long pagesSkipped;

        PlaqueColumnFilterEvaluator(IColumnRangeFilterValueAccessor pageAccessor, String handle, boolean isMax) {
            this.pageAccessor = pageAccessor;
            this.handle = handle;
            this.isMax = isMax;
        }

        @Override
        public boolean evaluate() throws HyracksDataException {
            pagesEvaluated++;
            if (pagesEvaluated % 1000 == 0 || pagesEvaluated == 1) {
                System.out.println("PLAQUE_PAGE_FILTER_EVAL: pages=" + pagesEvaluated
                        + " skipped=" + pagesSkipped + " thresholdInit="
                        + (thresholdState != null && thresholdState.isInitialized()));
            }

            if (thresholdState == null) {
                // Lazy resolve — we don't have JobId at factory creation time.
                // On the first call, the threshold is unlikely to be initialized anyway.
                // We'll resolve it from the registry the first time it's available.
                // For now, return true (pass page).
                return true;
            }

            if (!thresholdState.isInitialized()) {
                return true;
            }

            // Get current threshold and normalize it
            byte[] snapshot = thresholdState.getThresholdSnapshot();
            if (snapshot == null || snapshot.length == 0) {
                return true;
            }

            long normalizedThreshold = normalizeThreshold(snapshot);
            long pageValue = pageAccessor.getNormalizedValue();

            if (pagesEvaluated <= 1005 && pagesEvaluated >= 1000) {
                System.out.println("PLAQUE_PAGE_FILTER_DEBUG: pageValue=" + pageValue
                        + " (" + Double.longBitsToDouble(pageValue) + ")"
                        + " threshold=" + normalizedThreshold
                        + " (" + Double.longBitsToDouble(normalizedThreshold) + ")"
                        + " snapshotLen=" + snapshot.length
                        + " typeTag=" + (snapshot.length > 0 ? (snapshot[0] & 0xFF) : "?"
                        + " pass=" + (pageValue >= normalizedThreshold)));
            }

            boolean pass;
            if (isMax) {
                // For MAX: skip if pageMax < threshold (no tuple on this page can beat the threshold)
                pass = pageValue >= normalizedThreshold;
            } else {
                // For MIN: skip if pageMin > threshold (no tuple on this page can beat the threshold)
                pass = pageValue <= normalizedThreshold;
            }

            if (!pass) {
                pagesSkipped++;
                if (pagesSkipped <= 5) {
                    System.out.println("PLAQUE_PAGE_FILTER_SKIP: pageValue=" + pageValue
                            + " threshold=" + normalizedThreshold + " pagesSkipped=" + pagesSkipped);
                }
            }
            return pass;
        }

        /**
         * Called externally to set the threshold state after JobId is known.
         */
        public void setThresholdState(PlaqueThresholdState state) {
            this.thresholdState = state;
        }

        public String getHandle() {
            return handle;
        }

        public long getPagesEvaluated() {
            return pagesEvaluated;
        }

        public long getPagesSkipped() {
            return pagesSkipped;
        }

        /**
         * Normalize AsterixDB typed bytes to a long matching the column filter normalization scheme.
         * The first byte is the AsterixDB type tag.
         */
        private long normalizeThreshold(byte[] data) {
            if (data.length < 2) {
                return isMax ? Long.MIN_VALUE : Long.MAX_VALUE;
            }
            ATypeTag typeTag = ATypeTag.VALUE_TYPE_MAPPING[data[0]];
            if (typeTag == null) {
                return isMax ? Long.MIN_VALUE : Long.MAX_VALUE;
            }
            switch (typeTag) {
                case DOUBLE:
                    if (data.length < 9) {
                        return isMax ? Long.MIN_VALUE : Long.MAX_VALUE;
                    }
                    long bits = 0;
                    for (int i = 1; i <= 8; i++) {
                        bits = (bits << 8) | (data[i] & 0xFF);
                    }
                    return Double.doubleToLongBits(Double.longBitsToDouble(bits));
                case BIGINT:
                    if (data.length < 9) {
                        return isMax ? Long.MIN_VALUE : Long.MAX_VALUE;
                    }
                    long val = 0;
                    for (int i = 1; i <= 8; i++) {
                        val = (val << 8) | (data[i] & 0xFF);
                    }
                    return val;
                case STRING:
                    // Normalize first 4 chars to match zone map normalization in StringColumnFilterWriter.
                    // Skip type tag (1 byte), then use UTF8StringUtil to decode chars properly.
                    long nk = 0;
                    int strOffset = 1; // skip type tag byte; length prefix starts here
                    int utfLen = org.apache.hyracks.util.string.UTF8StringUtil.getUTFLength(data, strOffset);
                    strOffset += org.apache.hyracks.util.string.UTF8StringUtil
                            .getNumBytesToStoreLength(utfLen);
                    int strEnd = data.length;
                    for (int i = 0; i < 4; i++) {
                        nk <<= 16;
                        if (strOffset < strEnd) {
                            nk += org.apache.hyracks.util.string.UTF8StringUtil.charAt(data, strOffset) & 0xFFFF;
                            strOffset += org.apache.hyracks.util.string.UTF8StringUtil.charSize(data, strOffset);
                        }
                    }
                    return nk >>> 1;
                default:
                    return isMax ? Long.MIN_VALUE : Long.MAX_VALUE;
            }
        }
    }
}
