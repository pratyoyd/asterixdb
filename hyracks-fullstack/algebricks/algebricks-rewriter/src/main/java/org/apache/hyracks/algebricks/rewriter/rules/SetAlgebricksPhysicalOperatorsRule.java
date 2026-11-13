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
package org.apache.hyracks.algebricks.rewriter.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.common.exceptions.NotImplementedException;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalExpression;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalPlan;
import org.apache.hyracks.algebricks.core.algebra.base.IOptimizationContext;
import org.apache.hyracks.algebricks.core.algebra.base.IPhysicalOperator;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalExpressionTag;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalOperatorTag;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalVariable;
import org.apache.hyracks.algebricks.core.algebra.base.OperatorAnnotations;
import org.apache.hyracks.algebricks.core.algebra.expressions.IMergeAggregationExpressionFactory;
import org.apache.hyracks.algebricks.core.algebra.expressions.VariableReferenceExpression;
import org.apache.hyracks.algebricks.core.algebra.metadata.IDataSource;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.*;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.InsertDeleteUpsertOperator.Kind;
import org.apache.hyracks.algebricks.core.algebra.operators.physical.*;
import org.apache.hyracks.algebricks.core.algebra.properties.OrderColumn;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.KMeansStageOperator;
import org.apache.hyracks.algebricks.core.algebra.visitors.ILogicalOperatorVisitor;
import org.apache.hyracks.algebricks.core.rewriter.base.IAlgebraicRewriteRule;
import org.apache.hyracks.algebricks.core.rewriter.base.PhysicalOptimizationConfig;
import org.apache.hyracks.algebricks.rewriter.util.JoinUtils;
import org.apache.hyracks.api.exceptions.ErrorCode;
import org.apache.hyracks.api.exceptions.IWarningCollector;
import org.apache.hyracks.api.exceptions.Warning;

public class SetAlgebricksPhysicalOperatorsRule implements IAlgebraicRewriteRule {

    protected enum GroupByAlgorithm {
        HASH_GROUP_BY,
        SORT_GROUP_BY
    }

    @Override
    public boolean rewritePost(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
            throws AlgebricksException {
        return false;
    }

    @Override
    public boolean rewritePre(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
            throws AlgebricksException {
        AbstractLogicalOperator op = (AbstractLogicalOperator) opRef.getValue();
        if (op.getPhysicalOperator() != null) {
            return false;
        }
        computeDefaultPhysicalOp(op, true, createPhysicalOperatorFactoryVisitor(context));
        return true;
    }

    protected static void computeDefaultPhysicalOp(AbstractLogicalOperator op, boolean topLevelOp,
            ILogicalOperatorVisitor<IPhysicalOperator, Boolean> physOpFactory) throws AlgebricksException {
        if (op.getPhysicalOperator() == null) {
            IPhysicalOperator physOp = op.accept(physOpFactory, topLevelOp);
            if (physOp == null) {
                throw AlgebricksException.create(ErrorCode.PHYS_OPERATOR_NOT_SET, op.getSourceLocation(),
                        op.getOperatorTag());
            }
            op.setPhysicalOperator(physOp);
        }
        if (op.hasNestedPlans()) {
            AbstractOperatorWithNestedPlans nested = (AbstractOperatorWithNestedPlans) op;
            for (ILogicalPlan p : nested.getNestedPlans()) {
                for (Mutable<ILogicalOperator> root : p.getRoots()) {
                    computeDefaultPhysicalOp((AbstractLogicalOperator) root.getValue(), false, physOpFactory);
                }
            }
        }
        for (Mutable<ILogicalOperator> opRef : op.getInputs()) {
            computeDefaultPhysicalOp((AbstractLogicalOperator) opRef.getValue(), topLevelOp, physOpFactory);
        }
    }

    protected ILogicalOperatorVisitor<IPhysicalOperator, Boolean> createPhysicalOperatorFactoryVisitor(
            IOptimizationContext context) {
        return new AlgebricksPhysicalOperatorFactoryVisitor(context);
    }

    protected static class AlgebricksPhysicalOperatorFactoryVisitor
            implements ILogicalOperatorVisitor<IPhysicalOperator, Boolean> {

        protected final IOptimizationContext context;

        protected final PhysicalOptimizationConfig physConfig;

        protected AlgebricksPhysicalOperatorFactoryVisitor(IOptimizationContext context) {
            this.context = context;
            this.physConfig = context.getPhysicalOptimizationConfig();
        }

        @Override
        public IPhysicalOperator visitAggregateOperator(AggregateOperator op, Boolean topLevelOp) {
            return new AggregatePOperator();
        }

        @Override
        public IPhysicalOperator visitAssignOperator(AssignOperator op, Boolean topLevelOp) throws AlgebricksException {
            return new AssignPOperator();
        }

        @Override
        public IPhysicalOperator visitDistinctOperator(DistinctOperator distinct, Boolean topLevelOp) {
            if (topLevelOp) {
                return new PreSortedDistinctByPOperator(distinct.getDistinctByVarList());
            } else {
                return new MicroPreSortedDistinctByPOperator(distinct.getDistinctByVarList());
            }
        }

        @Override
        public IPhysicalOperator visitEmptyTupleSourceOperator(EmptyTupleSourceOperator op, Boolean topLevelOp) {
            return new EmptyTupleSourcePOperator();
        }

        @Override
        public final IPhysicalOperator visitGroupByOperator(GroupByOperator gby, Boolean topLevelOp)
                throws AlgebricksException {

            ensureAllVariables(gby.getGroupByList(), Pair::getRight);
            if (groupByAlgorithm(gby, topLevelOp) == GroupByAlgorithm.HASH_GROUP_BY) {
                ExternalGroupByPOperator extGby = createExternalGroupByPOperator(gby);
                if (extGby != null) {
                    return extGby;
                } else if (((gby.getAnnotations().get(OperatorAnnotations.USE_HASH_GROUP_BY) == Boolean.TRUE)
                        || (gby.getAnnotations().get(OperatorAnnotations.USE_EXTERNAL_GROUP_BY) == Boolean.TRUE))
                        && gby.getSourceLocation() != null) {
                    IWarningCollector warningCollector = context.getWarningCollector();
                    if (warningCollector.shouldWarn()) {
                        warningCollector.warn(
                                Warning.of(gby.getSourceLocation(), ErrorCode.INAPPLICABLE_HINT, "Group By", "hash"));
                    }
                }
            }

            if (topLevelOp) {
                return new PreclusteredGroupByPOperator(gby.getGroupByVarList(), gby.isGroupAll());
            } else {
                return new MicroPreclusteredGroupByPOperator(gby.getGroupByVarList());
            }
        }

        protected Enum groupByAlgorithm(GroupByOperator gby, Boolean topLevelOp) {
            if (hashGroupPossible(gby, topLevelOp) && hashGroupHint(gby)) {
                return GroupByAlgorithm.HASH_GROUP_BY;
            }
            return GroupByAlgorithm.SORT_GROUP_BY;
        }

        protected boolean hashGroupPossible(GroupByOperator gby, Boolean topLevelOp) {
            if (topLevelOp && gby.getNestedPlans().size() == 1) {
                List<Mutable<ILogicalOperator>> gbyRoots = gby.getNestedPlans().get(0).getRoots();
                if (gbyRoots.size() == 1) {
                    Mutable<ILogicalOperator> op = gbyRoots.get(0);
                    if (op.getValue().getInputs().get(0).getValue()
                            .getOperatorTag() == LogicalOperatorTag.NESTEDTUPLESOURCE) {
                        return true;
                    }
                }
            }
            return false;
        }

        protected boolean hashGroupHint(GroupByOperator gby) {
            if ((gby.getAnnotations().get(OperatorAnnotations.USE_HASH_GROUP_BY) == Boolean.TRUE)
                    || (gby.getAnnotations().get(OperatorAnnotations.USE_EXTERNAL_GROUP_BY) == Boolean.TRUE)) {
                return true;
            }
            return false;
        }

        protected ExternalGroupByPOperator createExternalGroupByPOperator(GroupByOperator gby)
                throws AlgebricksException {
            boolean hasIntermediateAgg = generateMergeAggregationExpressions(gby);
            if (!hasIntermediateAgg) {
                return null;
            }
            return new ExternalGroupByPOperator(gby.getGroupByVarList());
        }

        @Override
        public IPhysicalOperator visitInnerJoinOperator(InnerJoinOperator op, Boolean topLevelOp)
                throws AlgebricksException {
            return visitAbstractBinaryJoinOperator(op, topLevelOp);
        }

        @Override
        public IPhysicalOperator visitLeftOuterJoinOperator(LeftOuterJoinOperator op, Boolean topLevelOp)
                throws AlgebricksException {
            return visitAbstractBinaryJoinOperator(op, topLevelOp);
        }

        protected IPhysicalOperator visitAbstractBinaryJoinOperator(AbstractBinaryJoinOperator op, Boolean topLevelOp)
                throws AlgebricksException {
            if (!topLevelOp) {
                throw AlgebricksException.create(ErrorCode.OPERATOR_NOT_IMPLEMENTED, op.getSourceLocation(),
                        op.getOperatorTag().toString() + " (micro)");
            }
            JoinUtils.setJoinAlgorithmAndExchangeAlgo(op, topLevelOp, context);
            return op.getPhysicalOperator();
        }

        @Override
        public IPhysicalOperator visitLimitOperator(LimitOperator op, Boolean topLevelOp) {
            return new StreamLimitPOperator();
        }

        @Override
        public IPhysicalOperator visitNestedTupleSourceOperator(NestedTupleSourceOperator op, Boolean topLevelOp) {
            return new NestedTupleSourcePOperator();
        }

        @Override
        public IPhysicalOperator visitOrderOperator(OrderOperator oo, Boolean topLevelOp) throws AlgebricksException {
            ensureAllVariables(oo.getOrderExpressions(), Pair::getRight);
            if (topLevelOp) {
                return new StableSortPOperator(oo.getTopK());
            } else {
                return new MicroStableSortPOperator();
            }
        }

        @Override
        public IPhysicalOperator visitProjectOperator(ProjectOperator op, Boolean topLevelOp) {
            return new StreamProjectPOperator();
        }

        @Override
        public IPhysicalOperator visitRunningAggregateOperator(RunningAggregateOperator op, Boolean topLevelOp) {
            return new RunningAggregatePOperator();
        }

        @Override
        public IPhysicalOperator visitReplicateOperator(ReplicateOperator op, Boolean topLevelOp) {
            return new ReplicatePOperator();
        }

        @Override
        public IPhysicalOperator visitSplitOperator(SplitOperator op, Boolean topLevelOp) {
            return new SplitPOperator();
        }

        @Override
        public IPhysicalOperator visitSwitchOperator(SwitchOperator op, Boolean topLevelOp) {
            return new SwitchPOperator();
        }

        @Override
        public IPhysicalOperator visitScriptOperator(ScriptOperator op, Boolean topLevelOp) {
            return new StringStreamingScriptPOperator();
        }

        @Override
        public IPhysicalOperator visitSelectOperator(SelectOperator op, Boolean topLevelOp) {
            return new StreamSelectPOperator();
        }

        @Override
        public IPhysicalOperator visitSubplanOperator(SubplanOperator op, Boolean topLevelOp) {
            return new SubplanPOperator();
        }

        @Override
        public IPhysicalOperator visitUnionOperator(UnionAllOperator op, Boolean topLevelOp) {
            if (topLevelOp) {
                return new UnionAllPOperator();
            } else {
                return new MicroUnionAllPOperator();
            }
        }

        @Override
        public IPhysicalOperator visitIntersectOperator(IntersectOperator op, Boolean topLevelOp)
                throws AlgebricksException {
            if (topLevelOp) {
                return new IntersectPOperator();
            } else {
                throw AlgebricksException.create(ErrorCode.OPERATOR_NOT_IMPLEMENTED, op.getSourceLocation(),
                        op.getOperatorTag().toString() + " (micro)");
            }
        }

        @Override
        public IPhysicalOperator visitKMeansStageOperator(KMeansStageOperator op, Boolean topLevelOp)
                throws AlgebricksException {
            // Realized by the Asterix-level physical rule (the runtime descriptor lives in asterix-runtime).
            throw new NotImplementedException("kmeans-stage is not supported at the algebricks level");
        }

        @Override
        public IPhysicalOperator visitClusterByOperator(ClusterByOperator op, Boolean topLevelOp)
                throws AlgebricksException {
            // Realized by the Asterix-level physical rule (the runtime descriptor lives in asterix-runtime).
            throw new NotImplementedException("kmeans-stage is not supported at the algebricks level");
        }

        @Override
        public IPhysicalOperator visitUnnestOperator(UnnestOperator op, Boolean topLevelOp) {
            return new UnnestPOperator();
        }

        @Override
        public IPhysicalOperator visitLeftOuterUnnestOperator(LeftOuterUnnestOperator op, Boolean topLevelOp) {
            return new LeftOuterUnnestPOperator();
        }

        @Override
        public IPhysicalOperator visitDataScanOperator(DataSourceScanOperator scan, Boolean topLevelOp) {
            IDataSource dataSource = scan.getDataSource();
            DataSourceScanPOperator dss = new DataSourceScanPOperator(dataSource);
            if (dataSource.isScanAccessPathALeaf()) {
                dss.disableJobGenBelowMe();
            }
            return dss;
        }

        @Override
        public IPhysicalOperator visitWriteOperator(WriteOperator op, Boolean topLevelOp) throws AlgebricksException {
            ILogicalExpression sourceExpr = op.getSourceExpression().getValue();
            if (sourceExpr.getExpressionTag() != LogicalExpressionTag.VARIABLE) {
                throw AlgebricksException.create(ErrorCode.EXPR_NOT_NORMALIZED, sourceExpr.getSourceLocation());
            }
            ensureAllVariables(op.getPartitionExpressions(), v -> v);
            ensureAllVariables(op.getOrderExpressions(), Pair::getRight);
            return new SinkWritePOperator(op.getSourceVariable(), op.getPartitionVariables(), op.getOrderColumns());
        }

        @Override
        public IPhysicalOperator visitDistributeResultOperator(DistributeResultOperator op, Boolean topLevelOp) {
            return new DistributeResultPOperator();
        }

        @Override
        public IPhysicalOperator visitInsertDeleteUpsertOperator(InsertDeleteUpsertOperator opLoad,
                Boolean topLevelOp) {
            // Primary index
            List<LogicalVariable> keys = new ArrayList<>();
            List<LogicalVariable> additionalFilteringKeys = null;
            List<LogicalVariable> additionalNonFilterVariables = null;
            if (opLoad.getAdditionalNonFilteringExpressions() != null) {
                additionalNonFilterVariables = new ArrayList<>();
                getKeys(opLoad.getAdditionalNonFilteringExpressions(), additionalNonFilterVariables);
            }
            LogicalVariable payload =
                    getKeysAndLoad(opLoad.getPayloadExpression(), opLoad.getPrimaryKeyExpressions(), keys);
            if (opLoad.getAdditionalFilteringExpressions() != null) {
                additionalFilteringKeys = new ArrayList<>();
                getKeys(opLoad.getAdditionalFilteringExpressions(), additionalFilteringKeys);
            }
            if (opLoad.isBulkload()) {
                return new BulkloadPOperator(payload, keys, additionalFilteringKeys, additionalNonFilterVariables,
                        opLoad.getDataSource());
            } else {
                return new InsertDeleteUpsertPOperator(payload, keys, additionalFilteringKeys, opLoad.getDataSource(),
                        opLoad.getOperation(), additionalNonFilterVariables);
            }
        }

        @Override
        public IPhysicalOperator visitIndexInsertDeleteUpsertOperator(IndexInsertDeleteUpsertOperator opInsDel,
                Boolean topLevelOp) {
            // Secondary index
            List<LogicalVariable> primaryKeys = new ArrayList<>();
            List<LogicalVariable> secondaryKeys = new ArrayList<>();
            List<LogicalVariable> additionalFilteringKeys = null;
            getKeys(opInsDel.getPrimaryKeyExpressions(), primaryKeys);
            getKeys(opInsDel.getSecondaryKeyExpressions(), secondaryKeys);
            if (opInsDel.getAdditionalFilteringExpressions() != null) {
                additionalFilteringKeys = new ArrayList<>();
                getKeys(opInsDel.getAdditionalFilteringExpressions(), additionalFilteringKeys);
            }
            if (opInsDel.isBulkload()) {
                return new IndexBulkloadPOperator(primaryKeys, secondaryKeys, additionalFilteringKeys,
                        opInsDel.getFilterExpression(), opInsDel.getDataSourceIndex());
            } else {
                LogicalVariable operationVar = null;
                List<LogicalVariable> prevSecondaryKeys = null;
                LogicalVariable prevAdditionalFilteringKey = null;
                if (opInsDel.getOperation() == Kind.UPSERT) {
                    operationVar = getKey(opInsDel.getOperationExpr().getValue());
                    prevSecondaryKeys = new ArrayList<>();
                    getKeys(opInsDel.getPrevSecondaryKeyExprs(), prevSecondaryKeys);
                    if (opInsDel.getPrevAdditionalFilteringExpression() != null) {
                        prevAdditionalFilteringKey =
                                ((VariableReferenceExpression) (opInsDel.getPrevAdditionalFilteringExpression())
                                        .getValue()).getVariableReference();
                    }
                }
                return new IndexInsertDeleteUpsertPOperator(primaryKeys, secondaryKeys, additionalFilteringKeys,
                        opInsDel.getFilterExpression(), opInsDel.getBeforeOpFilterExpression(),
                        opInsDel.getDataSourceIndex(), operationVar, prevSecondaryKeys, prevAdditionalFilteringKey,
                        opInsDel.getNumberOfAdditionalNonFilteringFields());
            }
        }

        @Override
        public IPhysicalOperator visitTokenizeOperator(TokenizeOperator opTokenize, Boolean topLevelOp)
                throws AlgebricksException {
            List<LogicalVariable> primaryKeys = new ArrayList<>();
            List<LogicalVariable> secondaryKeys = new ArrayList<>();
            getKeys(opTokenize.getPrimaryKeyExpressions(), primaryKeys);
            getKeys(opTokenize.getSecondaryKeyExpressions(), secondaryKeys);
            // Tokenize Operator only operates with a bulk load on a data set with an index
            if (!opTokenize.isBulkload()) {
                throw AlgebricksException.create(ErrorCode.OPERATOR_NOT_IMPLEMENTED, opTokenize.getSourceLocation(),
                        opTokenize.getOperatorTag().toString() + " (no bulkload)");
            }
            return new TokenizePOperator(primaryKeys, secondaryKeys, opTokenize.getDataSourceIndex());
        }

        @Override
        public IPhysicalOperator visitSinkOperator(SinkOperator op, Boolean topLevelOp) {
            return new SinkPOperator();
        }

        @Override
        public IPhysicalOperator visitForwardOperator(ForwardOperator op, Boolean topLevelOp) {
            return new SortForwardPOperator();
        }

        @Override
        public final IPhysicalOperator visitWindowOperator(WindowOperator op, Boolean topLevelOp)
                throws AlgebricksException {
            ensureAllVariables(op.getPartitionExpressions(), v -> v);
            ensureAllVariables(op.getOrderExpressions(), Pair::getRight);
            return createWindowPOperator(op);
        }

        @Override
        public IPhysicalOperator visitIncrementalOrderOperator(IncrementalOrderOperator incrementalOrderOperator,
                Boolean arg) throws AlgebricksException {

            List<Pair<OrderOperator.IOrder, Mutable<ILogicalExpression>>> orderExpressions =
                    incrementalOrderOperator.getOrderExpressions();
            ensureAllVariables(orderExpressions, Pair::getSecond);
            OrderColumn[] orderColumns = new OrderColumn[orderExpressions.size()];
            //List<LogicalVariable> varLst = orderExpressions.second.getValue().getUsedVariables(usedVariables);
            for (int i = 0; i < orderExpressions.size(); i++) {
                ILogicalExpression expression = orderExpressions.get(i).second.getValue();
                VariableReferenceExpression variableReferenceExpression = (VariableReferenceExpression) expression;
                LogicalVariable orderVar = variableReferenceExpression.getVariableReference();
                OrderOperator.IOrder.OrderKind kind = orderExpressions.get(i).first.getKind();
                OrderColumn ordercolumn = new OrderColumn(orderVar, kind);
                orderColumns[i] = ordercolumn;

            }
            return new IncrementalSortPOperator(orderColumns, incrementalOrderOperator.getGroupByColumns());
        }

        @Override
        public IPhysicalOperator visitEagerMergeOperator(EagerMergeOperator eagerMergeOperator, Boolean arg)
                throws AlgebricksException {
            EagerMergePOperator physicalOp = new EagerMergePOperator(eagerMergeOperator.getGroupByColumns());

            return physicalOp;
        }

        protected AbstractWindowPOperator createWindowPOperator(WindowOperator op) throws AlgebricksException {
            return new WindowPOperator(op.getPartitionVarList(), op.getOrderColumnList(), false, false, false);
        }

        // Physical operators for these operators must have been set already by rules that introduced them

        @Override
        public IPhysicalOperator visitDelegateOperator(DelegateOperator op, Boolean topLevelOp)
                throws AlgebricksException {
            throw AlgebricksException.create(ErrorCode.PHYS_OPERATOR_NOT_SET, op.getSourceLocation(),
                    op.getOperatorTag());
        }

        @Override
        public IPhysicalOperator visitExchangeOperator(ExchangeOperator op, Boolean topLevelOp)
                throws AlgebricksException {
            throw AlgebricksException.create(ErrorCode.PHYS_OPERATOR_NOT_SET, op.getSourceLocation(),
                    op.getOperatorTag());
        }

        @Override
        public IPhysicalOperator visitMaterializeOperator(MaterializeOperator op, Boolean topLevelOp)
                throws AlgebricksException {
            throw AlgebricksException.create(ErrorCode.PHYS_OPERATOR_NOT_SET, op.getSourceLocation(),
                    op.getOperatorTag());
        }

        // Physical operators for these operators cannot be instantiated by Algebricks

        @Override
        public IPhysicalOperator visitUnnestMapOperator(UnnestMapOperator op, Boolean topLevelOp)
                throws AlgebricksException {
            throw AlgebricksException.create(ErrorCode.OPERATOR_NOT_IMPLEMENTED, op.getSourceLocation(),
                    op.getOperatorTag());
        }

        @Override
        public IPhysicalOperator visitLeftOuterUnnestMapOperator(LeftOuterUnnestMapOperator op, Boolean topLevelOp)
                throws AlgebricksException {
            throw AlgebricksException.create(ErrorCode.OPERATOR_NOT_IMPLEMENTED, op.getSourceLocation(),
                    op.getOperatorTag());
        }

        // Helper methods

        private static void getKeys(List<Mutable<ILogicalExpression>> keyExpressions, List<LogicalVariable> keys) {
            for (Mutable<ILogicalExpression> kExpr : keyExpressions) {
                keys.add(getKey(kExpr.getValue()));
            }
        }

        private static LogicalVariable getKey(ILogicalExpression keyExpression) {
            if (keyExpression.getExpressionTag() != LogicalExpressionTag.VARIABLE) {
                throw new NotImplementedException();
            }
            return ((VariableReferenceExpression) keyExpression).getVariableReference();
        }

        private static LogicalVariable getKeysAndLoad(Mutable<ILogicalExpression> payloadExpr,
                List<Mutable<ILogicalExpression>> keyExpressions, List<LogicalVariable> keys) {
            LogicalVariable payload;
            if (payloadExpr.getValue().getExpressionTag() != LogicalExpressionTag.VARIABLE) {
                throw new NotImplementedException();
            }
            payload = ((VariableReferenceExpression) payloadExpr.getValue()).getVariableReference();

            for (Mutable<ILogicalExpression> kExpr : keyExpressions) {
                ILogicalExpression e = kExpr.getValue();
                if (e.getExpressionTag() != LogicalExpressionTag.VARIABLE) {
                    throw new NotImplementedException();
                }
                keys.add(((VariableReferenceExpression) e).getVariableReference());
            }
            return payload;
        }

        private boolean generateMergeAggregationExpressions(GroupByOperator gby) throws AlgebricksException {
            if (gby.getNestedPlans().size() != 1) {
                //External/Sort group-by currently works only for one nested plan with one root containing
                //an aggregate and a nested-tuple-source.
                throw new AlgebricksException(
                        "External group-by currently works only for one nested plan with one root containing"
                                + "an aggregate and a nested-tuple-source.");
            }
            ILogicalPlan p0 = gby.getNestedPlans().get(0);
            if (p0.getRoots().size() != 1) {
                //External/Sort group-by currently works only for one nested plan with one root containing
                //an aggregate and a nested-tuple-source.
                throw new AlgebricksException(
                        "External group-by currently works only for one nested plan with one root containing"
                                + "an aggregate and a nested-tuple-source.");
            }
            IMergeAggregationExpressionFactory mergeAggregationExpressionFactory =
                    context.getMergeAggregationExpressionFactory();
            Mutable<ILogicalOperator> r0 = p0.getRoots().get(0);
            AbstractLogicalOperator r0Logical = (AbstractLogicalOperator) r0.getValue();
            if (r0Logical.getOperatorTag() != LogicalOperatorTag.AGGREGATE) {
                return false;
            }

            // Check whether there are multiple aggregates in the sub plan.
            ILogicalOperator r1Logical = r0Logical;
            while (r1Logical.hasInputs()) {
                r1Logical = r1Logical.getInputs().get(0).getValue();
                if (r1Logical.getOperatorTag() == LogicalOperatorTag.AGGREGATE) {
                    return false;
                }
            }

            AggregateOperator aggOp = (AggregateOperator) r0.getValue();
            List<Mutable<ILogicalExpression>> aggFuncRefs = aggOp.getExpressions();
            List<LogicalVariable> originalAggVars = aggOp.getVariables();
            int n = aggOp.getExpressions().size();
            List<Mutable<ILogicalExpression>> mergeExpressionRefs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                ILogicalExpression mergeExpr = mergeAggregationExpressionFactory
                        .createMergeAggregation(originalAggVars.get(i), aggFuncRefs.get(i).getValue(), context);
                if (mergeExpr == null) {
                    return false;
                }
                mergeExpressionRefs.add(new MutableObject<>(mergeExpr));
            }
            aggOp.setMergeExpressions(mergeExpressionRefs);
            return true;
        }

        static <E> void ensureAllVariables(Collection<E> exprList, Function<E, Mutable<ILogicalExpression>> accessor)
                throws AlgebricksException {
            for (E item : exprList) {
                ILogicalExpression e = accessor.apply(item).getValue();
                if (e.getExpressionTag() != LogicalExpressionTag.VARIABLE) {
                    throw AlgebricksException.create(ErrorCode.EXPR_NOT_NORMALIZED, e.getSourceLocation());
                }
            }
        }
    }
}
