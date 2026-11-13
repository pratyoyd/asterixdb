package org.apache.asterix.column.filter.range.evaluator;

import java.util.logging.Logger;

import org.apache.asterix.column.filter.FilterAccessorProvider;
import org.apache.asterix.column.filter.IColumnFilterEvaluator;
import org.apache.asterix.column.filter.TrueColumnFilterEvaluator;
import org.apache.asterix.column.filter.range.IColumnRangeFilterEvaluatorFactory;
import org.apache.asterix.column.filter.range.IColumnRangeFilterValueAccessor;
import org.apache.asterix.column.filter.range.accessor.NoOpColumnRangeFilterValueAccessor;
import org.apache.asterix.om.types.ARecordType;
import org.apache.asterix.runtime.operators.plaque.PlaqueGroupThresholdState;
import org.apache.asterix.runtime.operators.plaque.PlaqueThresholdRegistry;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.job.JobId;

/**
 * Page-level PLAQUE filter for GROUP BY MIN/MAX queries with low-cardinality group keys.
 *
 * Uses two zone maps per page:
 * 1. Group column zone map (min + max) — to check if page is single-group (min == max)
 * 2. Aggregate column zone map (max for MAX, min for MIN) — to compare against group's threshold
 *
 * For single-group pages, looks up the group's threshold in a normalized long→long map.
 * For mixed-group pages, passes through (tuple filter handles these).
 */
public class PlaqueGroupByColumnFilterEvaluatorFactory implements IColumnRangeFilterEvaluatorFactory {

    private static final long serialVersionUID = 1L;
    private final String handle;
    private final boolean isMax;
    private final ARecordType aggColumnPath;
    private final ARecordType groupColumnPath;

    public PlaqueGroupByColumnFilterEvaluatorFactory(String handle, boolean isMax, ARecordType aggColumnPath,
            ARecordType groupColumnPath) {
        this.handle = handle;
        this.isMax = isMax;
        this.aggColumnPath = aggColumnPath;
        this.groupColumnPath = groupColumnPath;
    }

    @Override
    public IColumnFilterEvaluator create(FilterAccessorProvider filterAccessorProvider) throws HyracksDataException {
        // Two accessors for the group column: min and max
        IColumnRangeFilterValueAccessor groupMinAccessor =
                filterAccessorProvider.createRangeFilterValueAccessor(groupColumnPath, true);
        IColumnRangeFilterValueAccessor groupMaxAccessor =
                filterAccessorProvider.createRangeFilterValueAccessor(groupColumnPath, false);

        // One accessor for the aggregate column
        boolean readMin = !isMax;
        IColumnRangeFilterValueAccessor aggAccessor =
                filterAccessorProvider.createRangeFilterValueAccessor(aggColumnPath, readMin);

        // If any accessor is NoOp, can't filter
        if (groupMinAccessor == NoOpColumnRangeFilterValueAccessor.INSTANCE
                || groupMaxAccessor == NoOpColumnRangeFilterValueAccessor.INSTANCE
                || aggAccessor == NoOpColumnRangeFilterValueAccessor.INSTANCE) {
            return TrueColumnFilterEvaluator.INSTANCE;
        }

        // Resolve group threshold state from registry
        PlaqueGroupThresholdState state = null;
        org.apache.hyracks.api.context.IHyracksTaskContext ctx = filterAccessorProvider.getTaskContext();
        if (ctx != null) {
            JobId jobId = ctx.getJobletContext().getJobId();
            state = PlaqueThresholdRegistry.INSTANCE.getOrCreateGroupState(jobId, handle, isMax);
        }

        return new PlaqueGroupByColumnFilterEvaluator(groupMinAccessor, groupMaxAccessor, aggAccessor, state, handle,
                isMax);
    }

    @Override
    public String toString() {
        return "PLAQUE_GROUPBY_PAGE_FILTER(" + (isMax ? "MAX" : "MIN") + ", " + handle + ")";
    }

    private static class PlaqueGroupByColumnFilterEvaluator implements IColumnFilterEvaluator {
        private static final Logger LOGGER = Logger.getLogger(PlaqueGroupByColumnFilterEvaluator.class.getName());

        private final IColumnRangeFilterValueAccessor groupMinAccessor;
        private final IColumnRangeFilterValueAccessor groupMaxAccessor;
        private final IColumnRangeFilterValueAccessor aggAccessor;
        private final PlaqueGroupThresholdState state;
        private final String handle;
        private final boolean isMax;

        private long pagesEvaluated;
        private long pagesSkipped;
        private long singleGroupPages;
        private long mixedGroupPages;

        PlaqueGroupByColumnFilterEvaluator(IColumnRangeFilterValueAccessor groupMinAccessor,
                IColumnRangeFilterValueAccessor groupMaxAccessor, IColumnRangeFilterValueAccessor aggAccessor,
                PlaqueGroupThresholdState state, String handle, boolean isMax) {
            this.groupMinAccessor = groupMinAccessor;
            this.groupMaxAccessor = groupMaxAccessor;
            this.aggAccessor = aggAccessor;
            this.state = state;
            this.handle = handle;
            this.isMax = isMax;
        }

        @Override
        public boolean evaluate() throws HyracksDataException {
            pagesEvaluated++;

            if (state == null || !state.isInitialized()) {
                return true; // no thresholds yet
            }

            long groupMin = groupMinAccessor.getNormalizedValue();
            long groupMax = groupMaxAccessor.getNormalizedValue();

            if (groupMin != groupMax) {
                mixedGroupPages++;
                return true; // mixed-group page, can't skip
            }

            // Single-group page — all tuples belong to one group
            singleGroupPages++;

            // Look up this group's normalized threshold
            Long normalizedThreshold = state.getNormalizedThreshold(groupMin);
            if (normalizedThreshold == null) {
                return true; // unseen group or poisoned key
            }

            long pageAggValue = aggAccessor.getNormalizedValue();

            boolean pass;
            if (isMax) {
                pass = pageAggValue >= normalizedThreshold;
            } else {
                pass = pageAggValue <= normalizedThreshold;
            }

            if (!pass) {
                pagesSkipped++;
            }

            return pass;
        }
    }
}
