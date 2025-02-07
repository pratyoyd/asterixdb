package org.apache.asterix.optimizer.rules.am.array.smartrabbit;

import org.apache.asterix.metadata.MetadataTransactionContext;
import org.apache.asterix.metadata.entities.Dataset;
import org.apache.asterix.metadata.entities.Index;
import org.apache.asterix.optimizer.rules.am.*;
import org.apache.asterix.optimizer.rules.am.array.JoinFromSubplanRewrite;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.common.utils.Pair;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalExpression;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.base.IOptimizationContext;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalOperatorTag;
import org.apache.hyracks.algebricks.core.algebra.functions.FunctionIdentifier;
import org.apache.asterix.metadata.MetadataManager;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.PlanSwitchOperator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
This checks if a join is an "applicable" hybrid hash join and if it is, changes it to physical plan switch with one branch being index join and the other being the hybrid hash join.
The plan switches when it is ready.

But here we don't worry about when to switch. We are just rewriting the plan.
Left branch = outer relation, right branch = inner relation. We will use utilize an index on the inner relation when applicable

	Checking part
		Any join in which the grouping key is on the probe side is applicable. So basically it is in the left subtree
		So this rule is applied after the other rule
			We need to see if this rule is applicable even if the other rule is not. Probably yes.
	Add switch operator
		Add a physical operator called plan switch operator
		One child is current operator -hybrid hash join
		Other child is index nested loop join operator
			And we change the data source scan to index scan. Same frame size as the other
			data source to index scan switch
	TODO: Add logic to accomodate filters, give preference to index that has a filter as well




 */
public class InteractiveJoinRule extends AbstractIntroduceAccessMethodRule {

    protected static Map<FunctionIdentifier, List<IAccessMethod>> accessMethods = new HashMap<>();
    public Index chosenIndex;


    static {
        registerAccessMethod(ArrayBTreeAccessMethod.INSTANCE, accessMethods);
        registerAccessMethod(BTreeAccessMethod.INSTANCE, accessMethods);
        registerAccessMethod(RTreeAccessMethod.INSTANCE, accessMethods);
        registerAccessMethod(InvertedIndexAccessMethod.INSTANCE, accessMethods);
        for (Pair<FunctionIdentifier, Boolean> optFunc : BTreeAccessMethod.INSTANCE.getOptimizableFunctions()) {
            JoinFromSubplanRewrite.addOptimizableFunction(optFunc.first);
        }
    }
    @Override
    public boolean rewritePost(Mutable<ILogicalOperator> opRef, IOptimizationContext context) {
        return false;
    }

    @Override
    public Map<FunctionIdentifier, List<IAccessMethod>> getAccessMethods() {
        return Map.of();
    }

    @Override
    public boolean rewritePre(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
            throws AlgebricksException {
        ILogicalOperator operator = opRef.getValue();
        //Working on inner join only now
        if (operator.getOperatorTag() != LogicalOperatorTag.INNERJOIN) {
            return false;
        }
        //if inner table has no index defined on it, return false

        if(!CheckForAvailableIndex(opRef))return false;
        Mutable<ILogicalOperator> interactiveChildOperatorRef = createIndexBasedPlan(opRef, context);
        Mutable<ILogicalOperator> hashJoinOperatorRef = new MutableObject<>(operator);

        Mutable<ILogicalExpression> condition = null;
        PlanSwitchOperator switchOperator = new PlanSwitchOperator(interactiveChildOperatorRef, hashJoinOperatorRef, condition);
        opRef.setValue(switchOperator);
        List<Mutable<ILogicalOperator>> inputs = new ArrayList<>();

        hashJoinOperatorRef.setValue(operator);
        inputs.add(hashJoinOperatorRef);          // Blocking plan
        inputs.add(interactiveChildOperatorRef);       // Interactive plan
        switchOperator.getInputs().addAll(inputs);
        context.computeAndSetTypeEnvironmentForOperator(switchOperator);
        return true;



        }

    private Mutable<ILogicalOperator> createIndexBasedPlan(Mutable<ILogicalOperator> opRef, IOptimizationContext context) {
        return null;
    }

    private boolean CheckForAvailableIndex(Mutable<ILogicalOperator> opRef) throws AlgebricksException {
        //check if there's an index on the inner table, ideally just a metadata call
        ILogicalOperator operator = opRef.getValue();
        Mutable<ILogicalOperator> rightSubTreeRef = operator.getInputs().get(1);
        ILogicalOperator rightSubTree = rightSubTreeRef.getValue();



        //Dataset innerDataset = rightSubTree.getDataset();
        //List<Index> indexes = getAllIndexes(innerDataset);

        return false;
    }
    public List<Index> getAllIndexes(Dataset dataset) throws AlgebricksException {
        List<Index> indexes = new ArrayList<>();
        MetadataTransactionContext mdTxnCtx = null;
        try {
            // Start a metadata transaction
            mdTxnCtx = MetadataManager.INSTANCE.beginTransaction();

            // Fetch all indexes for the dataset
            indexes = MetadataManager.INSTANCE.getDatasetIndexes(
                    mdTxnCtx,
                    dataset.getDatabaseName(),
                    dataset.getDataverseName(),
                    dataset.getDatasetName()
            );

            // Commit the transaction
            MetadataManager.INSTANCE.commitTransaction(mdTxnCtx);
        } catch (Exception e) {
            if (mdTxnCtx != null) {
                try {
                    MetadataManager.INSTANCE.abortTransaction(mdTxnCtx);
                } catch (Exception abortEx) {
                    throw new AlgebricksException("Failed to abort metadata transaction.", abortEx);
                }
            }
            throw new AlgebricksException("Failed to fetch indexes for dataset.", e);
        }
        return indexes;
    }


}

