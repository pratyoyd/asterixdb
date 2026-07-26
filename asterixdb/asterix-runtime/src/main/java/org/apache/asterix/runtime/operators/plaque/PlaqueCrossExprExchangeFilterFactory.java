package org.apache.asterix.runtime.operators.plaque;

import java.util.logging.Logger;

import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.job.JobId;
import org.apache.hyracks.dataflow.common.comm.io.FrameTupleAccessor;
import org.apache.hyracks.dataflow.std.connectors.IPlaqueExchangeFilter;
import org.apache.hyracks.dataflow.std.connectors.IPlaqueExchangeFilterFactory;

/**
 * Exchange filter for cross-table expression thresholds.
 *
 * For MAX(a - b) where a is probe-side and b is build-side:
 * - Reads scalar threshold t from PlaqueThresholdState (running MAX from aggregate)
 * - Reads per-partition build-side min c_min[p] from PlaqueBuildSideMinState
 * - Filters: keep tuple iff a > t + c_min[destPartition]
 *
 * Parameterized by CombineOp and FilterDirection for extensibility to +, *, /.
 */
public class PlaqueCrossExprExchangeFilterFactory implements IPlaqueExchangeFilterFactory {

    private static final long serialVersionUID = 1L;

    public enum CombineOp { ADD, SUBTRACT, MULTIPLY, DIVIDE }
    public enum FilterDirection { GREATER, LESS }

    private final String thresholdHandle;
    private final String buildSideHandle;
    private final int probeColumnIdx;
    private final CombineOp combineOp;
    private final FilterDirection filterDirection;

    public PlaqueCrossExprExchangeFilterFactory(String thresholdHandle, String buildSideHandle, int probeColumnIdx,
            CombineOp combineOp, FilterDirection filterDirection) {
        this.thresholdHandle = thresholdHandle;
        this.buildSideHandle = buildSideHandle;
        this.probeColumnIdx = probeColumnIdx;
        this.combineOp = combineOp;
        this.filterDirection = filterDirection;
    }

    @Override
    public IPlaqueExchangeFilter createFilter(IHyracksTaskContext ctx) throws HyracksDataException {
        JobId jobId = ctx.getJobletContext().getJobId();
        PlaqueThresholdState thresholdState =
                PlaqueThresholdRegistry.INSTANCE.getOrCreate(jobId, thresholdHandle, true);
        PlaqueBuildSideMinState buildSideState =
                PlaqueThresholdRegistry.INSTANCE.getBuildSideMinState(jobId, buildSideHandle);
        return new PlaqueCrossExprExchangeFilter(thresholdState, buildSideState, probeColumnIdx,
                combineOp, filterDirection, jobId, thresholdHandle);
    }

    private static final class PlaqueCrossExprExchangeFilter implements IPlaqueExchangeFilter {

        private static final Logger LOGGER = Logger.getLogger(PlaqueCrossExprExchangeFilter.class.getName());

        private final PlaqueThresholdState thresholdState;
        private final PlaqueBuildSideMinState buildSideState;
        private final int probeColumnIdx;
        private final CombineOp combineOp;
        private final FilterDirection filterDirection;
        private final JobId jobId;
        private final String handle;
        private long totalTuples;
        private long filteredTuples;
        private long uninitTuples;

        // Cached state to avoid per-tuple overhead
        private boolean stateReady;       // true once both threshold and build-side are available
        private double cachedT;           // cached threshold value (re-read periodically)
        private double[] cachedBounds;    // precomputed bound per destination partition
        private long refreshCounter;      // refresh cached threshold every N tuples
        private static final long REFRESH_INTERVAL = 4096;

        PlaqueCrossExprExchangeFilter(PlaqueThresholdState thresholdState, PlaqueBuildSideMinState buildSideState,
                int probeColumnIdx, CombineOp combineOp, FilterDirection filterDirection,
                JobId jobId, String handle) {
            this.thresholdState = thresholdState;
            this.buildSideState = buildSideState;
            this.probeColumnIdx = probeColumnIdx;
            this.combineOp = combineOp;
            this.filterDirection = filterDirection;
            this.jobId = jobId;
            this.handle = handle;
            this.stateReady = false;
            this.cachedT = Double.NaN;
            this.refreshCounter = 0;
        }

        private boolean refreshState() {
            if (thresholdState == null || !thresholdState.isInitialized()) {
                return false;
            }
            if (buildSideState == null || !buildSideState.isAnyPublished()) {
                return false;
            }
            // Read threshold — use volatile field directly, no synchronized copy
            byte[] snap = thresholdState.getThresholdSnapshot();
            if (snap == null || snap.length < 2) {
                return false;
            }
            double t = extractNumericValue(snap, 0, snap.length);
            if (Double.isNaN(t)) {
                return false;
            }
            cachedT = t;

            // Precompute bounds per partition
            int numPartitions = buildSideState.getSize();
            if (cachedBounds == null || cachedBounds.length != numPartitions) {
                cachedBounds = new double[numPartitions];
            }
            for (int p = 0; p < numPartitions; p++) {
                double c = buildSideState.isPublished(p) ? buildSideState.getValue(p) : buildSideState.getGlobalBound();
                cachedBounds[p] = computeBound(t, c);
            }
            stateReady = true;
            return true;
        }

        private double computeBound(double t, double c) {
            switch (combineOp) {
                case ADD: return t + c;
                case SUBTRACT: return t - c;
                case MULTIPLY: return t * c;
                case DIVIDE: return c == 0 ? Double.NaN : t / c;
                default: return Double.NaN;
            }
        }

        @Override
        public boolean passes(FrameTupleAccessor accessor, int tupleIndex, int destPartition)
                throws HyracksDataException {
            totalTuples++;

            // Periodically refresh cached state
            if (!stateReady || (++refreshCounter & (REFRESH_INTERVAL - 1)) == 0) {
                if (!refreshState()) {
                    uninitTuples++;
                    return true;
                }
            }

            // Extract probe column value (numeric)
            int fStart = accessor.getAbsoluteFieldStartOffset(tupleIndex, probeColumnIdx);
            int fLen = accessor.getFieldLength(tupleIndex, probeColumnIdx);
            if (fLen < 2) {
                return true;
            }
            byte[] data = accessor.getBuffer().array();
            double probeValue = extractNumericValue(data, fStart, fLen);
            if (Double.isNaN(probeValue)) {
                return true;
            }

            // Use precomputed bound for destination partition
            double bound = cachedBounds[destPartition];
            if (Double.isNaN(bound)) {
                return true;
            }

            // Filter
            boolean pass;
            if (filterDirection == FilterDirection.GREATER) {
                pass = probeValue > bound;
            } else {
                pass = probeValue < bound;
            }

            if (!pass) {
                filteredTuples++;
            }
            return pass;
        }

        private static double extractNumericValue(byte[] data, int offset, int len) {
            byte typeTag = data[offset];
            switch (typeTag) {
                case 12: // DOUBLE
                    if (len < 9) return Double.NaN;
                    long dBits = 0;
                    for (int i = 1; i <= 8; i++) {
                        dBits = (dBits << 8) | (data[offset + i] & 0xFF);
                    }
                    return Double.longBitsToDouble(dBits);
                case 11: // FLOAT
                    if (len < 5) return Double.NaN;
                    int fBits = 0;
                    for (int i = 1; i <= 4; i++) {
                        fBits = (fBits << 8) | (data[offset + i] & 0xFF);
                    }
                    return Float.intBitsToFloat(fBits);
                case 4: // BIGINT
                    if (len < 9) return Double.NaN;
                    long lVal = 0;
                    for (int i = 1; i <= 8; i++) {
                        lVal = (lVal << 8) | (data[offset + i] & 0xFF);
                    }
                    return (double) lVal;
                case 3: // INTEGER
                    if (len < 5) return Double.NaN;
                    int iVal = 0;
                    for (int i = 1; i <= 4; i++) {
                        iVal = (iVal << 8) | (data[offset + i] & 0xFF);
                    }
                    return (double) iVal;
                case 2: // SMALLINT
                    if (len < 3) return Double.NaN;
                    short sVal = (short) (((data[offset + 1] & 0xFF) << 8) | (data[offset + 2] & 0xFF));
                    return (double) sVal;
                case 1: // TINYINT
                    if (len < 2) return Double.NaN;
                    return (double) data[offset + 1];
                default:
                    return Double.NaN;
            }
        }

        @Override
        public void close() throws HyracksDataException {
            LOGGER.warning(String.format(
                    "PLAQUE_EXCHANGE_FILTER [job=%s, handle=%s] total=%d, filtered=%d, uninit=%d, filterRate=%.4f%%",
                    jobId, handle, totalTuples, filteredTuples, uninitTuples,
                    totalTuples > 0 ? (100.0 * filteredTuples / totalTuples) : 0.0));
        }
    }
}
