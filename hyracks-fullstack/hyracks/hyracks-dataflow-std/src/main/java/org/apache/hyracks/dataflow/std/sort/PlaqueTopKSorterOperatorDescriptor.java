package org.apache.hyracks.dataflow.std.sort;

import java.util.List;

import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.dataflow.ActivityId;
import org.apache.hyracks.api.dataflow.value.IBinaryComparator;
import org.apache.hyracks.api.dataflow.value.IBinaryComparatorFactory;
import org.apache.hyracks.api.dataflow.value.INormalizedKeyComputer;
import org.apache.hyracks.api.dataflow.value.INormalizedKeyComputerFactory;
import org.apache.hyracks.api.dataflow.value.IRecordDescriptorProvider;
import org.apache.hyracks.api.dataflow.value.RecordDescriptor;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.job.IOperatorDescriptorRegistry;
import org.apache.hyracks.dataflow.common.io.GeneratedRunFileReader;

/**
 * Top-K sort with PLAQUE threshold observer.
 * The observer is notified when the K-th value changes in the heap,
 * allowing the threshold to be propagated for probe-side filtering.
 */
public class PlaqueTopKSorterOperatorDescriptor extends AbstractSorterOperatorDescriptor {

    private static final long serialVersionUID = 1L;
    private final int topK;
    private final ITopKThresholdObserverFactory observerFactory;

    public PlaqueTopKSorterOperatorDescriptor(IOperatorDescriptorRegistry spec, int framesLimit, int topK,
            int[] sortFields, INormalizedKeyComputerFactory firstKeyNormalizerFactory,
            IBinaryComparatorFactory[] comparatorFactories, RecordDescriptor recordDescriptor,
            ITopKThresholdObserverFactory observerFactory) {
        super(spec, framesLimit, sortFields,
                firstKeyNormalizerFactory != null
                        ? new INormalizedKeyComputerFactory[] { firstKeyNormalizerFactory } : null,
                comparatorFactories, recordDescriptor);
        this.topK = topK;
        this.observerFactory = observerFactory;
    }

    @Override
    public SortActivity getSortActivity(ActivityId id) {
        return new SortActivity(id) {
            private static final long serialVersionUID = 1L;

            @Override
            protected IRunGenerator getRunGenerator(IHyracksTaskContext ctx,
                    IRecordDescriptorProvider recordDescProvider) throws HyracksDataException {
                HybridTopKSortRunGenerator runGen = new HybridTopKSortRunGenerator(ctx, framesLimit, topK, sortFields,
                        keyNormalizerFactories, comparatorFactories, outRecDescs[0]);
                if (observerFactory != null) {
                    ITopKThresholdObserver observer = observerFactory.createObserver(ctx);
                    runGen.setThresholdObserver(observer);
                }
                return runGen;
            }
        };
    }

    @Override
    public MergeActivity getMergeActivity(ActivityId id) {
        return new MergeActivity(id) {
            private static final long serialVersionUID = 1L;

            @Override
            protected AbstractExternalSortRunMerger getSortRunMerger(IHyracksTaskContext ctx,
                    IRecordDescriptorProvider recordDescProvider, List<GeneratedRunFileReader> runs,
                    IBinaryComparator[] comparators, INormalizedKeyComputer nmkComputer, int necessaryFrames) {
                return new ExternalSortRunMerger(ctx, runs, sortFields, comparators, nmkComputer, outRecDescs[0],
                        necessaryFrames, topK);
            }
        };
    }

    @Override
    public String getDisplayName() {
        return "PLAQUE Top K Sort";
    }
}
