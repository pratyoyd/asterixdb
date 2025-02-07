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

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import org.apache.asterix.common.config.DatasetConfig;
import org.apache.asterix.metadata.declared.DataSource;
import org.apache.asterix.metadata.declared.DatasetDataSource;
import org.apache.asterix.metadata.declared.MetadataProvider;
import org.apache.asterix.metadata.entities.Dataset;
import org.apache.asterix.metadata.entities.Index;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.common.utils.Pair;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalExpression;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalPlan;
import org.apache.hyracks.algebricks.core.algebra.base.IOptimizationContext;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalOperatorTag;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalVariable;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.DataSourceScanOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.GroupByOperator;
import org.apache.hyracks.algebricks.core.rewriter.base.IAlgebraicRewriteRule;

/**
 * This rule lift out the aggregate operator out from a group-by operator
 * if the gby operator groups-by on empty key, e.g., the group-by variables are empty.
 *
 * @author yingyib
 */
public class EliminateGroupByEmptyKeyRule implements IAlgebraicRewriteRule {

    public Index chosenIndex;
    @Override
    public boolean rewritePre(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
            throws AlgebricksException {
        return false;
    }

    @Override
    public boolean rewritePost(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
            throws AlgebricksException {
        AbstractLogicalOperator op = (AbstractLogicalOperator) opRef.getValue();
        if (op.getOperatorTag() != LogicalOperatorTag.GROUP) {
            return false;
        }
        GroupByOperator groupOp = (GroupByOperator) op;

        List<Pair<LogicalVariable, Mutable<ILogicalExpression>>> gbyVarList = groupOp.getGroupByList();
        LogicalVariable bestGByVar = findBestGByVar(opRef, gbyVarList, context);
        // Only groupAll has equivalent semantics to aggregate.

        if (!groupOp.isGroupAll()) {
            return false;
        }
        List<LogicalVariable> groupVars = groupOp.getGroupByVarList();
        List<Pair<LogicalVariable, Mutable<ILogicalExpression>>> decorList = groupOp.getDecorList();
        if (!groupVars.isEmpty() || !decorList.isEmpty()) {
            return false;
        }
        List<ILogicalPlan> nestedPlans = groupOp.getNestedPlans();
        if (nestedPlans.size() > 1) {
            return false;
        }
        ILogicalPlan nestedPlan = nestedPlans.get(0);
        if (nestedPlan.getRoots().size() > 1) {
            return false;
        }
        Mutable<ILogicalOperator> topOpRef = nestedPlan.getRoots().get(0);
        ILogicalOperator topOp = nestedPlan.getRoots().get(0).getValue();
        Mutable<ILogicalOperator> nestedTupleSourceRef = getNestedTupleSourceReference(topOpRef);
        /**
         * connect nested top op into the plan
         */
        opRef.setValue(topOp);
        /**
         * connect child op into the plan
         */
        nestedTupleSourceRef.setValue(groupOp.getInputs().get(0).getValue());
        return true;
    }

    private Mutable<ILogicalOperator> getNestedTupleSourceReference(Mutable<ILogicalOperator> nestedTopOperatorRef) {
        Mutable<ILogicalOperator> currentOpRef = nestedTopOperatorRef;
        while (currentOpRef.getValue().getInputs() != null && !currentOpRef.getValue().getInputs().isEmpty()) {
            currentOpRef = currentOpRef.getValue().getInputs().get(0);
        }
        return currentOpRef;
    }

    public LogicalVariable findBestGByVar(Mutable<ILogicalOperator> opRef, List<Pair<LogicalVariable, Mutable<ILogicalExpression>>> gbyVarList, IOptimizationContext context) throws AlgebricksException {
        //if (gbyVarList.size() == 1) return gbyVarList.get(0).first;
        ILogicalOperator currentOp = opRef.getValue();
        while (currentOp != null) {
            // Check if current operator is a DataSourceScanOperator.
            if (currentOp.getOperatorTag() == LogicalOperatorTag.DATASOURCESCAN) {
                DataSourceScanOperator scanOp = (DataSourceScanOperator) currentOp;

                // For each grouping variable, check if it has a corresponding field and B-Tree index.
                for (Pair<LogicalVariable, Mutable<ILogicalExpression>> varPair : gbyVarList) {
                    LogicalVariable var = varPair.first;
                    //if (currentOp.getSchema().contains(var)) {
                    // Check if the variable has a B-Tree index
                    if (hasBTreeIndexAccess(var, opRef, context)) {
                        return var; // Found the leftmost variable with a B-Tree index
                    }
                }
                    return null; // No matching variable found in DataSourceScan.
                }

                // Traverse to the leftmost child if available.
                if (!currentOp.getInputs().isEmpty()) {
                    currentOp = currentOp.getInputs().get(0).getValue();
                } else {
                    currentOp = null; // End of the tree.
                }
            }
        //Queue<ILogicalOperator> queue = new LinkedList<>();
        //
        // queue.add(root);



        // No matching variable found
        return null;

    }

    public boolean hasBTreeIndexAccess(LogicalVariable var, Mutable<ILogicalOperator> opRef, IOptimizationContext context) throws AlgebricksException {
        // Use a queue to traverse the query plan and locate the scan operator.
        Queue<Mutable<ILogicalOperator>> queue = new LinkedList<>();
        queue.add(opRef);


        while (!queue.isEmpty()) {
            ILogicalOperator currentOp = queue.poll().getValue();

            // Check if this operator is a DataSourceScanOperator.
            if (currentOp.getOperatorTag() == LogicalOperatorTag.DATASOURCESCAN) {
                DataSourceScanOperator scanOp = (DataSourceScanOperator) currentOp;

                DataSource ds = (DataSource) scanOp.getDataSource();
                if (ds.getDatasourceType() != DataSource.Type.INTERNAL_DATASET) {
                    return false;
                }
                Dataset dataset = ((DatasetDataSource) ds).getDataset();

                // Retrieve dataset information from the scan operator.


                // Retrieve all indexes for the dataset.
                MetadataProvider mp = (MetadataProvider) context.getMetadataProvider();
                List<Index> indexes = mp.getDatasetIndexes(dataset.getDatabaseName(), dataset.getDataverseName(), dataset.getDatasetName());

                // Check if any index is a B-Tree and matches the variable.
                for (Index index : indexes) {
                    if (index.getIndexType() == DatasetConfig.IndexType.BTREE) {
                        Index.ValueIndexDetails details = (Index.ValueIndexDetails) index.getIndexDetails();
                        List<List<String>> keyFieldNames = details.getKeyFieldNames();

                        // Verify if the variable maps first indexed filed
                        String fieldName = var.toString().substring(2);
                        if(fieldName.equals(keyFieldNames.get(0).get(0))){//findFieldNameForVariable(var, scanOp);

                                chosenIndex = index;
                                return true; // Found a matching B-Tree index.
                            }
                        }
                    }

            }

            // Continue traversal with the operator's inputs.
            for (Mutable<ILogicalOperator> input : currentOp.getInputs()) {
                queue.add(input);
            }
        }
        return false;

    }
}
