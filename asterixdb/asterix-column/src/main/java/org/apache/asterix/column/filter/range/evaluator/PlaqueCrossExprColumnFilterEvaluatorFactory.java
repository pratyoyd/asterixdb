package org.apache.asterix.column.filter.range.evaluator;

import java.util.logging.Logger;

import org.apache.asterix.column.filter.FilterAccessorProvider;
import org.apache.asterix.column.filter.IColumnFilterEvaluator;
import org.apache.asterix.column.filter.TrueColumnFilterEvaluator;
import org.apache.asterix.column.filter.range.IColumnRangeFilterEvaluatorFactory;
import org.apache.asterix.column.filter.range.IColumnRangeFilterValueAccessor;
import org.apache.asterix.column.filter.range.accessor.NoOpColumnRangeFilterValueAccessor;
import org.apache.asterix.om.types.ARecordType;
import org.apache.asterix.runtime.operators.plaque.PlaqueBuildSideMinState;
import org.apache.asterix.runtime.operators.plaque.PlaqueCrossExprExchangeFilterFactory;
import org.apache.asterix.runtime.operators.plaque.PlaqueThresholdRegistry;
import org.apache.asterix.runtime.operators.plaque.PlaqueThresholdState;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.job.JobId;

/**
 * Page-level PLAQUE filter for cross-table expressions. Skips entire pages where
 * the page's max of the probe column cannot beat the combined bound (t + c_min).
 *
 * For MAX(a - b): skip page if pageMax(a) <= t + global_c_min
 * For MAX(a + b): skip page if pageMax(a) <= t - global_c_min (bound = t - c)
 *
 * The bound is computed as combine(t, global_c_min) using CombineOp, then
 * normalized to match the zone map normalization scheme for comparison.
 */
public class PlaqueCrossExprColumnFilterEvaluatorFactory implements IColumnRangeFilterEvaluatorFactory {

    private static final long serialVersionUID = 1L;
    private final String thresholdHandle;
    private final String buildSideHandle;
    private final boolean isMax;
    private final ARecordType columnPath;
    private final PlaqueCrossExprExchangeFilterFactory.CombineOp combineOp;
    private final PlaqueCrossExprExchangeFilterFactory.FilterDirection filterDirection;

    public PlaqueCrossExprColumnFilterEvaluatorFactory(String thresholdHandle, String buildSideHandle,
            boolean isMax, ARecordType columnPath,
            PlaqueCrossExprExchangeFilterFactory.CombineOp combineOp,
            PlaqueCrossExprExchangeFilterFactory.FilterDirection filterDirection) {
        this.thresholdHandle = thresholdHandle;
        this.buildSideHandle = buildSideHandle;
        this.isMax = isMax;
        this.columnPath = columnPath;
        this.combineOp = combineOp;
        this.filterDirection = filterDirection;
    }

    @Override
    public IColumnFilterEvaluator create(FilterAccessorProvider filterAccessorProvider) throws HyracksDataException {
        // For MAX with GREATER direction: we want pageMax — skip if pageMax <= bound
        // For MIN with LESS direction: we want pageMin — skip if pageMin >= bound
        boolean readMin = (filterDirection == PlaqueCrossExprExchangeFilterFactory.FilterDirection.LESS);
        IColumnRangeFilterValueAccessor pageAccessor =
                filterAccessorProvider.createRangeFilterValueAccessor(columnPath, readMin);

        if (pageAccessor == NoOpColumnRangeFilterValueAccessor.INSTANCE) {
            System.out.println("PLAQUE_CROSSEXPR_PAGE_FILTER_CREATE: NoOp accessor, returning TRUE");
            return TrueColumnFilterEvaluator.INSTANCE;
        }

        int colIdx = -1;
        if (pageAccessor instanceof org.apache.asterix.column.filter.range.accessor.ColumnRangeFilterValueAccessor) {
            colIdx = ((org.apache.asterix.column.filter.range.accessor.ColumnRangeFilterValueAccessor) pageAccessor)
                    .getColumnIndex();
        }
        System.out.println("PLAQUE_CROSSEXPR_PAGE_FILTER_CREATE: colIdx=" + colIdx
                + " columnPath=" + columnPath
                + " readMin=" + readMin
                + " inList=" + filterAccessorProvider.getFilterAccessors().contains(pageAccessor)
                + " listSize=" + filterAccessorProvider.getFilterAccessors().size());

        // Determine if the column stores values as raw longs (integer types) or doubleToLongBits (doubles)
        boolean isIntegerColumn = false;
        if (pageAccessor instanceof org.apache.asterix.column.filter.range.accessor.ColumnRangeFilterValueAccessor) {
            org.apache.asterix.om.types.ATypeTag colType =
                    ((org.apache.asterix.column.filter.range.accessor.ColumnRangeFilterValueAccessor) pageAccessor)
                            .getTypeTag();
            isIntegerColumn = (colType == org.apache.asterix.om.types.ATypeTag.BIGINT
                    || colType == org.apache.asterix.om.types.ATypeTag.INTEGER
                    || colType == org.apache.asterix.om.types.ATypeTag.SMALLINT
                    || colType == org.apache.asterix.om.types.ATypeTag.TINYINT);
        }

        PlaqueThresholdState thresholdState = null;
        PlaqueBuildSideMinState buildSideState = null;
        org.apache.hyracks.api.context.IHyracksTaskContext ctx = filterAccessorProvider.getTaskContext();
        if (ctx != null) {
            JobId jobId = ctx.getJobletContext().getJobId();
            thresholdState = PlaqueThresholdRegistry.INSTANCE.getOrCreate(jobId, thresholdHandle, isMax);
            buildSideState = PlaqueThresholdRegistry.INSTANCE.getBuildSideMinState(jobId, buildSideHandle);
        }

        return new PlaqueCrossExprColumnFilterEvaluator(pageAccessor, thresholdState, buildSideState,
                combineOp, filterDirection, thresholdHandle, isIntegerColumn);
    }

    @Override
    public String toString() {
        return "PLAQUE_CROSSEXPR_PAGE_FILTER(" + (isMax ? "MAX" : "MIN") + ", t=" + thresholdHandle
                + ", b=" + buildSideHandle + ")";
    }

    private static class PlaqueCrossExprColumnFilterEvaluator implements IColumnFilterEvaluator {
        private static final Logger LOGGER = Logger.getLogger(PlaqueCrossExprColumnFilterEvaluator.class.getName());

        private final IColumnRangeFilterValueAccessor pageAccessor;
        private final PlaqueThresholdState thresholdState;
        private final PlaqueBuildSideMinState buildSideState;
        private final PlaqueCrossExprExchangeFilterFactory.CombineOp combineOp;
        private final PlaqueCrossExprExchangeFilterFactory.FilterDirection filterDirection;
        private final String handle;
        private final boolean isIntegerColumn;
        private long pagesEvaluated;
        private long pagesSkipped;

        PlaqueCrossExprColumnFilterEvaluator(IColumnRangeFilterValueAccessor pageAccessor,
                PlaqueThresholdState thresholdState, PlaqueBuildSideMinState buildSideState,
                PlaqueCrossExprExchangeFilterFactory.CombineOp combineOp,
                PlaqueCrossExprExchangeFilterFactory.FilterDirection filterDirection,
                String handle, boolean isIntegerColumn) {
            this.pageAccessor = pageAccessor;
            this.thresholdState = thresholdState;
            this.buildSideState = buildSideState;
            this.combineOp = combineOp;
            this.filterDirection = filterDirection;
            this.handle = handle;
            this.isIntegerColumn = isIntegerColumn;
        }

        @Override
        public boolean evaluate() throws HyracksDataException {
            pagesEvaluated++;

            if (thresholdState == null || !thresholdState.isInitialized()) {
                return true;
            }
            if (buildSideState == null || !buildSideState.isAnyPublished()) {
                return true;
            }

            // Get threshold t as double
            byte[] snapshot = thresholdState.getThresholdSnapshot();
            if (snapshot == null || snapshot.length < 2) {
                return true;
            }
            double t = extractDouble(snapshot);
            if (Double.isNaN(t)) {
                return true;
            }

            // Get global c_min (or c_max) from build side
            double c = buildSideState.getGlobalBound();
            if (c == Double.MAX_VALUE || c == -Double.MAX_VALUE) {
                return true; // not yet available
            }

            // Compute combined bound
            double bound;
            switch (combineOp) {
                case ADD:
                    bound = t + c;
                    break;
                case SUBTRACT:
                    bound = t - c;
                    break;
                case MULTIPLY:
                    bound = t * c;
                    break;
                case DIVIDE:
                    if (c == 0) return true;
                    bound = t / c;
                    break;
                default:
                    return true;
            }

            // Normalize bound to match zone map normalization
            // BIGINT zone maps store raw longs; DOUBLE zone maps store doubleToLongBits
            long normalizedBound = isIntegerColumn ? (long) bound : Double.doubleToLongBits(bound);
            long pageValue = pageAccessor.getNormalizedValue();

            // Filter decision
            boolean pass;
            if (filterDirection == PlaqueCrossExprExchangeFilterFactory.FilterDirection.GREATER) {
                // For MAX(a - b): keep page if pageMax(a) > bound
                // Skip page if pageMax(a) <= bound
                pass = pageValue > normalizedBound;
            } else {
                // For MIN(a - b): keep page if pageMin(a) < bound
                pass = pageValue < normalizedBound;
            }

            if (!pass) {
                pagesSkipped++;
            }

            if (pagesEvaluated <= 5 || pagesEvaluated % 500 == 0) {
                LOGGER.warning(String.format(
                        "PLAQUE_CROSSEXPR_PAGE_FILTER [handle=%s] evaluated=%d, skipped=%d, skipRate=%.2f%%, "
                                + "pageValue=%d (%.4f), normalizedBound=%d (%.4f), t=%.4f, c=%.4f, bound=%.4f, pass=%b",
                        handle, pagesEvaluated, pagesSkipped,
                        pagesEvaluated > 0 ? (100.0 * pagesSkipped / pagesEvaluated) : 0.0,
                        pageValue, Double.longBitsToDouble(pageValue),
                        normalizedBound, Double.longBitsToDouble(normalizedBound),
                        t, c, bound, pass));
            }

            return pass;
        }

        /**
         * Extract a double from AsterixDB typed bytes (type tag + value).
         */
        private static double extractDouble(byte[] data) {
            if (data.length < 2) return Double.NaN;
            byte typeTag = data[0];
            switch (typeTag) {
                case 12: // DOUBLE
                    if (data.length < 9) return Double.NaN;
                    long dBits = 0;
                    for (int i = 1; i <= 8; i++) {
                        dBits = (dBits << 8) | (data[i] & 0xFF);
                    }
                    return Double.longBitsToDouble(dBits);
                case 11: // FLOAT
                    if (data.length < 5) return Double.NaN;
                    int fBits = 0;
                    for (int i = 1; i <= 4; i++) {
                        fBits = (fBits << 8) | (data[i] & 0xFF);
                    }
                    return Float.intBitsToFloat(fBits);
                case 4: // BIGINT
                    if (data.length < 9) return Double.NaN;
                    long lVal = 0;
                    for (int i = 1; i <= 8; i++) {
                        lVal = (lVal << 8) | (data[i] & 0xFF);
                    }
                    return (double) lVal;
                case 3: // INTEGER
                    if (data.length < 5) return Double.NaN;
                    int iVal = 0;
                    for (int i = 1; i <= 4; i++) {
                        iVal = (iVal << 8) | (data[i] & 0xFF);
                    }
                    return (double) iVal;
                case 2: // SMALLINT
                    if (data.length < 3) return Double.NaN;
                    short sVal = (short) (((data[1] & 0xFF) << 8) | (data[2] & 0xFF));
                    return (double) sVal;
                case 1: // TINYINT
                    if (data.length < 2) return Double.NaN;
                    return (double) data[1];
                default:
                    return Double.NaN;
            }
        }
    }
}
