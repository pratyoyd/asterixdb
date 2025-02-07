package org.apache.asterix.optimizer.rules.am.array.smartrabbit;

import org.apache.asterix.common.config.DatasetConfig;
import org.apache.asterix.common.metadata.DataverseName;
import org.apache.asterix.lang.common.util.FunctionUtil;
import org.apache.asterix.metadata.declared.DataSource;
import org.apache.asterix.metadata.declared.DatasetDataSource;
import org.apache.asterix.metadata.declared.MetadataProvider;
import org.apache.asterix.metadata.entities.Dataset;
import org.apache.asterix.metadata.entities.Index;
import org.apache.asterix.om.base.AString;
import org.apache.asterix.om.constants.AsterixConstantValue;
import org.apache.asterix.om.functions.BuiltinFunctions;
import org.apache.asterix.om.types.ARecordType;
import org.apache.asterix.om.utils.ConstantExpressionUtil;
import org.apache.asterix.optimizer.rules.am.AbstractIntroduceAccessMethodRule;
import org.apache.asterix.optimizer.rules.am.AccessMethodUtils;
import org.apache.asterix.optimizer.rules.am.BTreeJobGenParams;
import org.apache.asterix.optimizer.rules.am.IAccessMethod;
import org.apache.asterix.optimizer.rules.cbo.JoinOperator;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.common.utils.Pair;
import org.apache.hyracks.algebricks.core.algebra.base.*;
import org.apache.hyracks.algebricks.core.algebra.expressions.AbstractFunctionCallExpression;
import org.apache.hyracks.algebricks.core.algebra.expressions.ConstantExpression;
import org.apache.hyracks.algebricks.core.algebra.expressions.ScalarFunctionCallExpression;
import org.apache.hyracks.algebricks.core.algebra.expressions.VariableReferenceExpression;
import org.apache.hyracks.algebricks.core.algebra.functions.FunctionIdentifier;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.*;
import org.apache.hyracks.algebricks.core.rewriter.base.IAlgebraicRewriteRule;

import java.util.*;

import static org.apache.asterix.metadata.declared.MetadataManagerUtil.findDataset;
import static org.apache.asterix.optimizer.rules.am.AccessMethodJobGenParams.DATASET_NAME_POS;

/*
This rewrite rule checks whether this order by is of one of the indexable attirbutes and then sees whether there is an index on the attribute.
If there is an index, it creates a split operator and creates a separate branch that starts with the index and keeps the rest of the plan the same.
	Checking part
		Not just an order by but group bys as well
		For order by, it is obvious which index to use, still need to write code
		For group by, we need to do an analysis to check which index to use.
	Add switch operator
		Add a physical operator called plan switch operator
		One child is current operator
		Other child is a new operator that originates from btree search -invoke a function that creates the branch
		Ok so maybe we need to keep stable sort as is, and add a variable that checks whether operation is interactive and knows to push answer
		Pass the switch event as finishing of hash job

			TODO: Add logic to accomodate filters, give preference to index that has an index as well



 */
public class InteractiveSortRule implements IAlgebraicRewriteRule {

    public Index chosenIndex;
    List<Mutable<ILogicalOperator>> parents = new ArrayList<>();
    private final List<Mutable<ILogicalOperator>> operatorParents;

    public InteractiveSortRule() {
        operatorParents = new ArrayList<>();
    }
    @Override
    public boolean rewritePost(Mutable<ILogicalOperator> opRef, IOptimizationContext context) {
        return false;
    }



    public boolean rewritePre(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
            throws AlgebricksException {
        // remove yourself
        parents.remove(parents.size() - 1);
        // already fired this rule on this operator?
        if (context.checkIfInDontApplySet(this, opRef.getValue())) {
            return false;
        }
        ILogicalOperator operator = opRef.getValue();
        if (operator.getOperatorTag() != LogicalOperatorTag.ORDER||operator.getOperatorTag() != LogicalOperatorTag.GROUP) {
            return false;
        }
        //Find the attribute to put the index on
        LogicalVariable orderAttr = GetIndexableAttribute(opRef, context);
        if(orderAttr == null) {return false;}
        //We have already checked whether index available for just group by attribute
        if(operator.getOperatorTag() == LogicalOperatorTag.ORDER)
            if(!hasBTreeIndexAccess(orderAttr,opRef, context)) return false;
        //We have got the green signal here to create a plan switch operator

        Mutable<ILogicalOperator> interactiveChildOperatorRef = createIndexBasedPlan(opRef, context);
        //Mutable<ILogicalOperator> orderOperatorRef = new MutableObject<>(operator);

        //PlanSwitchOperator switchOperator = new PlanSwitchOperator(interactiveChildOperatorRef, orderOperatorRef, condition);
        //opRef.setValue(switchOperator);
        //List<Mutable<ILogicalOperator>> inputs = new ArrayList<>();

//        orderOperatorRef.setValue(operator);
//        inputs.add(orderOperatorRef);          // Blocking plan
//        inputs.add(interactiveChildOperatorRef);       // Interactive plan
//        switchOperator.getInputs().addAll(inputs);
     //   context.computeAndSetTypeEnvironmentForOperator(switchOperator);
        context.addToDontApplySet(this, opRef.getValue());

        return true;

    }



    private LogicalVariable GetIndexableAttribute(Mutable<ILogicalOperator> opRef, IOptimizationContext context) throws AlgebricksException {

        ILogicalOperator operator = opRef.getValue();
        if(operator.getOperatorTag() == LogicalOperatorTag.ORDER) {
            OrderOperator orderOperator = (OrderOperator) operator;
            List<Pair<OrderOperator.IOrder, Mutable<ILogicalExpression>>> orderKeys = orderOperator.getOrderExpressions();
            ILogicalExpression firstExpression = orderKeys.get(0).second.getValue();
            Set<LogicalVariable> firstKeyUsedVars = new HashSet<>();
            firstExpression.getUsedVariables(firstKeyUsedVars);
            if (firstKeyUsedVars.isEmpty()) return null;
            LogicalVariable firstSortKeyVariable = firstKeyUsedVars.iterator().next();
            return firstSortKeyVariable;
        }
        if(operator.getOperatorTag() == LogicalOperatorTag.GROUP) {
            GroupByOperator groupOperator = (GroupByOperator) operator;
            List<Pair<LogicalVariable, Mutable<ILogicalExpression>>> gbyVarList = groupOperator.getGroupByList();
            if (gbyVarList.isEmpty()) return null;
            LogicalVariable bestGByVar = findBestGByVar(opRef, gbyVarList, context);
            return bestGByVar;

        }
        return null;
    }
    //This returns the best possible key on which we should index
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



    private DataSourceScanOperator findDataSourceScan(Mutable<ILogicalOperator> opRef, Set<LogicalVariable> vars,
                                                      IOptimizationContext context) {
        // TODO: Implement logic to traverse the tree and locate a DataSourceScan operator
        return null;
    }

    private boolean findIndex(Set<LogicalVariable> vars, DataSourceScanOperator dataSourceScan,
                              IOptimizationContext context) {
        // TODO: Implement logic to verify if an index exists for the given variables
        return false;
    }


    private Mutable<ILogicalOperator> createIndexBasedPlan(Mutable<ILogicalOperator> opRef, IOptimizationContext context) throws AlgebricksException {
        // Start the cloning process from the given operator reference.

        return cloneOperator(opRef, context);
    }

    // Recursive function to clone operators.
    private Mutable<ILogicalOperator> cloneOperator(Mutable<ILogicalOperator> opRef,  IOptimizationContext context) throws AlgebricksException {

        ILogicalOperator currentOp = opRef.getValue();
        parents.add(opRef);

        ILogicalOperator clonedOp = null;
        Mutable<ILogicalOperator> clonedRef = null;

        // Handle the DataSourceScanOperator replacement with an IndexScan.
        if (currentOp.getOperatorTag() == LogicalOperatorTag.DATASOURCESCAN) {
            //DataSourceScanOperator scanOp = (DataSourceScanOperator) currentOp;
            //UnnestMapOperator indexScanOp = createIndexScanOperator(opRef, context);
            return createIndexScanOperator(opRef, context);
        }
        else if (currentOp.getOperatorTag() == LogicalOperatorTag.INNERJOIN) {
            AbstractBinaryJoinOperator joinOp = (AbstractBinaryJoinOperator) currentOp;
            ILogicalExpression joinCondition = joinOp.getCondition().getValue();
            List<LogicalVariable> joinKeys = extractJoinKeys(joinCondition, context);
            Mutable<ILogicalOperator> innerRef = joinOp.getInputs().get(1);
            AbstractLogicalOperator innerOp = (AbstractLogicalOperator) innerRef.getValue();

            // Check for an applicable index on the join keys in the inner subtree.
//            if (hasApplicableIndex(innerOp, joinKeys, context)) {
//                // Replace the join with an index nested loop join.
//                Mutable<ILogicalOperator> newJoinRef = createIndexNestedLoopJoin(joinOp, innerRef, joinKeys, context);
//                opRef.setValue(newJoinRef.getValue());
//
//                // Identify the inner (right) subtree.
//                Mutable<ILogicalOperator> innerRef = joinOp.getInputs().get(1);
//                return null;
//
//
//            }
        }

            // Create a new instance of the current operator and copy its properties.
        else {
                clonedOp = opRef.getValue();
                clonedRef = new MutableObject<>(clonedOp);

            }

            // Recursively clone inputs.
            for (Mutable<ILogicalOperator> input : currentOp.getInputs()) {
                clonedOp.getInputs().add(cloneOperator(input, context));
            }

            return clonedRef;

    }
    private List<LogicalVariable> extractJoinKeys(ILogicalExpression condition, IOptimizationContext context) throws AlgebricksException {
        // This assumes the join condition is a binary function like '='.
        if (condition.getExpressionTag() != LogicalExpressionTag.FUNCTION_CALL) {
            throw new AlgebricksException("Unsupported join condition format");
        }

        ScalarFunctionCallExpression funcExpr = (ScalarFunctionCallExpression) condition;
        if (!funcExpr.getFunctionIdentifier().equals(BuiltinFunctions.EQ)) {
            throw new AlgebricksException("Only equality joins are supported for index nested loop joins");
        }

        // Extract the left and right variables from the condition.
        List<Mutable<ILogicalExpression>> args = funcExpr.getArguments();
        LogicalVariable leftVar = extractVariableFromExpression(args.get(0).getValue());
        LogicalVariable rightVar = extractVariableFromExpression(args.get(1).getValue());

        return Arrays.asList(leftVar, rightVar);
    }

    private LogicalVariable extractVariableFromExpression(ILogicalExpression value) {
        return null;

    }

    private Mutable<ILogicalOperator> createIndexNestedLoopJoin(Mutable<ILogicalOperator> opRef, IOptimizationContext context) {
        AbstractLogicalOperator currentOp = (AbstractLogicalOperator) opRef.getValue();
        return null;






            // Update the parent reference.


    }


    // Helper method to clone an operator manually.
//    private ILogicalOperator createClonedOperator(Mutable<ILogicalOperator> opRef) throws AlgebricksException {
//        ILogicalOperator operator = opRef.getValue();
//        switch (operator.getOperatorTag()) {
//            case ORDER: {
//                OrderOperator orderOp = (OrderOperator) operator;
//                return new OrderOperator(new ArrayList<>(orderOp.getOrderExpressions()));
//            }
//            case GROUP: {
//                GroupByOperator groupOp = (GroupByOperator) operator;
//                return new GroupByOperator(new ArrayList<>(groupOp.getGroupByList()), new ArrayList<>(groupOp.getNestedPlans()));
//            }
//            case SELECT: {
//                SelectOperator selectOp = (SelectOperator) operator;
//                return new SelectOperator(selectOp.getCondition(), selectOp.getRetainInput(), selectOp.getSourceLocation());
//            }
//            case PROJECT: {
//                ProjectOperator projectOp = (ProjectOperator) operator;
//                return new ProjectOperator(new ArrayList<>(projectOp.getVariables()));
//            }
//            case EMPTYTUPLESOURCE: {
//                return new EmptyTupleSourceOperator();
//            }
//            case JOIN: {
//                JoinOperator joinOp = (JoinOperator) operator;
//                return new JoinOperator(joinOp.getJoinKind(), joinOp.getCondition(), joinOp.getInputs());
//            }
//            case EXCHANGE: {
//                ExchangeOperator exchangeOp = (ExchangeOperator) operator;
//                return new ExchangeOperator(exchangeOp.getExchangeDeliverToRuntime());
//            }
//            default:
//                throw new AlgebricksException("Unsupported operator type for cloning: " + operator.getOperatorTag());
//        }
//    }

    private Mutable<ILogicalOperator> createIndexScanOperator(Mutable<ILogicalOperator> opRef, IOptimizationContext context) throws AlgebricksException {
        // Use the chosenIndex variable to configure the index scan.
        AbstractScanOperator scanOperator = (AbstractScanOperator) opRef.getValue();
        MetadataProvider metadataProvider = (MetadataProvider) context.getMetadataProvider();
        BTreeJobGenParams originalBTreeParameters = new BTreeJobGenParams();
        Dataset dataset = findDataset(scanOperator, originalBTreeParameters, context);
        Index index = chosenIndex;

        if (dataset.getDatasetType() == DatasetConfig.DatasetType.INTERNAL) {
            // Initialize secondary BTree parameters
            boolean retainInput;
            BTreeJobGenParams newBTreeParameters;

            if (scanOperator.getOperatorTag() == LogicalOperatorTag.DATASOURCESCAN) {
                retainInput = AccessMethodUtils.retainInputs(scanOperator.getVariables(), scanOperator, parents);
                newBTreeParameters = new BTreeJobGenParams(
                        chosenIndex.getIndexName(),
                        DatasetConfig.IndexType.BTREE,
                        dataset.getDatabaseName(),
                        dataset.getDataverseName(),
                        dataset.getDatasetName(),
                        retainInput,
                        scanOperator.getInputs().get(0).getValue().getExecutionMode() == AbstractLogicalOperator.ExecutionMode.UNPARTITIONED
                );
                List<LogicalVariable> empty = new ArrayList<>();
                newBTreeParameters.setLowKeyInclusive(true);
                newBTreeParameters.setHighKeyInclusive(true);
                newBTreeParameters.setIsEqCondition(false);
                newBTreeParameters.setLowKeyVarList(empty, 0, 0);
                newBTreeParameters.setHighKeyVarList(empty, 0, 0);
            } else {
                retainInput = originalBTreeParameters.getRetainInput();
                newBTreeParameters = new BTreeJobGenParams(
                        chosenIndex.getIndexName(),
                        DatasetConfig.IndexType.BTREE,
                        dataset.getDatabaseName(),
                        dataset.getDataverseName(),
                        dataset.getDatasetName(),
                        retainInput,
                        originalBTreeParameters.getRequiresBroadcast()
                );
                newBTreeParameters.setLowKeyInclusive(originalBTreeParameters.isLowKeyInclusive());
                newBTreeParameters.setHighKeyInclusive(originalBTreeParameters.isHighKeyInclusive());
                newBTreeParameters.setIsEqCondition(originalBTreeParameters.isEqCondition());
                newBTreeParameters.setLowKeyVarList(originalBTreeParameters.getLowKeyVarList(), 0, originalBTreeParameters.getLowKeyVarList().size());
                newBTreeParameters.setHighKeyVarList(originalBTreeParameters.getHighKeyVarList(), 0, originalBTreeParameters.getHighKeyVarList().size());
            }

            ARecordType recordType = (ARecordType) metadataProvider.findType(dataset);
            ARecordType metaRecordType = (ARecordType) metadataProvider.findMetaType(dataset);
            recordType = (ARecordType) metadataProvider.findTypeForDatasetWithoutType(recordType, metaRecordType, dataset);

            // Create the new operator to replace the DataSourceScan
            AbstractUnnestMapOperator chosenIndexUnnestOperator = (AbstractUnnestMapOperator) AccessMethodUtils.createSecondaryIndexUnnestMap(
                    dataset,
                    recordType,
                    metaRecordType,
                    chosenIndex,
                    scanOperator.getInputs().get(0).getValue(),
                    newBTreeParameters,
                    context,
                    retainInput,
                    false,
                    false,
                    ConstantExpression.MISSING.getValue()
            );

            // Reuse the inputs of the DataSourceScan operator
            chosenIndexUnnestOperator.getInputs().clear();
            chosenIndexUnnestOperator.getInputs().addAll(scanOperator.getInputs());

            // Reuse the PK variables of the original scan operator
            chosenIndexUnnestOperator.getVariables().clear();
            chosenIndexUnnestOperator.getVariables().addAll(scanOperator.getVariables());

            opRef.setValue(chosenIndexUnnestOperator);
        }

        return opRef;
    }


    private Dataset findDataset(AbstractScanOperator scanOperator, BTreeJobGenParams originalBTreeParameters, IOptimizationContext context) throws AlgebricksException {
        MetadataProvider mp = (MetadataProvider) context.getMetadataProvider();
        // #1. get the dataset
        Dataset dataset;
        // case 1: dataset scan
        if (scanOperator.getOperatorTag() == LogicalOperatorTag.DATASOURCESCAN) {
            DataSourceScanOperator dss = (DataSourceScanOperator) scanOperator;
            DataSource ds = (DataSource) dss.getDataSource();
            if (ds.getDatasourceType() != DataSource.Type.INTERNAL_DATASET) {
                return null;
            }
            dataset = ((DatasetDataSource) ds).getDataset();
        } else {
            // case 2: dataset range search
            AbstractFunctionCallExpression primaryIndexFunctionCall =
                    (AbstractFunctionCallExpression) ((UnnestMapOperator) scanOperator).getExpressionRef().getValue();
            originalBTreeParameters.readFromFuncArgs(primaryIndexFunctionCall.getArguments());
            if (originalBTreeParameters.isEqCondition()) {
                return null;
            }
            dataset = mp.findDataset(originalBTreeParameters.getDatabaseName(),
                    originalBTreeParameters.getDataverseName(), originalBTreeParameters.getDatasetName());
        }
        return dataset;
    }

    // Helper method to create the index search function call expression.
//    private ILogicalExpression createIndexSearchFunctionCall(Index index, List<List<String>> keyFieldNames, List<List<String>> primaryKeys) {
//        // Construct the function call for the index scan.
//        FunctionIdentifier indexSearchFn = BuiltinFunctions.INDEX_SEARCH;
//        List<Mutable<ILogicalExpression>> args = new ArrayList<>();
//
//        // Add arguments for the index name and key fields.
//        args.add(new MutableObject<>(new ConstantExpression(new AsterixConstantValue(new AString(index.getIndexName())))));
//        for (LogicalVariable key : primaryKeys) {
//            args.add(new MutableObject<>(new VariableReferenceExpression(key)));
//        }
//
//        return new ScalarFunctionCallExpression(FunctionUtil.getFunctionInfo(indexSearchFn), args);
//    }
//
//    public Map<FunctionIdentifier, List<IAccessMethod>> getAccessMethods() {
//        return Map.of();
//    }



//this function checks whether there is a btree index on the logical variable
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

                    // Verify if the variable maps to one of the indexed fields.
                    String fieldName = var.toString().substring(2);//findFieldNameForVariable(var, scanOp);
                    for (List<String> keyField : keyFieldNames) {
                        if (fieldName.equals(keyField.get(0))) {
                            // chosenIndex = index;
                            return true; // Found a matching B-Tree index.
                        }
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


















