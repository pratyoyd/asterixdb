//package org.apache.asterix.optimizer.rules.am.array;
//
//
//import org.apache.asterix.optimizer.rules.am.AbstractIntroduceAccessMethodRule;
//import org.apache.asterix.optimizer.rules.am.IAccessMethod;
//import org.apache.commons.lang3.mutable.Mutable;
//import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
//import org.apache.hyracks.algebricks.common.utils.Pair;
//import org.apache.hyracks.algebricks.core.algebra.base.*;
//import org.apache.hyracks.algebricks.core.algebra.functions.FunctionIdentifier;
//import org.apache.hyracks.algebricks.core.algebra.operators.logical.DataSourceScanOperator;
//import org.apache.hyracks.algebricks.core.algebra.operators.logical.GroupByOperator;
//
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//
//public class IntroduceInteractiveSelectAccessMethod extends AbstractIntroduceAccessMethodRule {
//
//    protected static Map<FunctionIdentifier, List<IAccessMethod>> accessMethods = new HashMap<>();
//    @Override
//    public boolean rewritePre(Mutable<ILogicalOperator> opRef, IOptimizationContext context) throws AlgebricksException {
//        if (!context.getPhysicalOptimizationConfig().getInteractiveMode()) {
//            return false;
//        }
//        ILogicalOperator op = opRef.getValue();
//        LogicalOperatorTag opTag = op.getOperatorTag();
//        if(opTag != LogicalOperatorTag.UNIONALL)return false;
//        Mutable<ILogicalOperator> inputOpRef = op.getInputs().get(0);
//        boolean planTransformed = checkAndApplyTheSelectTransformation(opRef, context);
//
//
//
//        return false;
//    }
//    /*
//    First we will try to optimize for group by, without any join.
//     */
//    private boolean checkAndApplyTheSelectTransformation(Mutable<ILogicalOperator> opRef, IOptimizationContext context) throws AlgebricksException {
//        ILogicalOperator op = opRef.getValue();
//        if(op.getOperatorTag() == LogicalOperatorTag.GROUP){
//            GroupByOperator groupOperator = (GroupByOperator) op;
//            List<Pair<LogicalVariable, Mutable<ILogicalExpression>>> gbyVarList = groupOperator.getGroupByList();
//            LogicalVariable bestGByVar = findBestGByVar(opRef, gbyVarList, context);
//
//        }
//
//
//
//        return false;
//
//    }
//    public LogicalVariable findBestGByVar(Mutable<ILogicalOperator> opRef, List<Pair<LogicalVariable, Mutable<ILogicalExpression>>> gbyVarList, IOptimizationContext context) throws AlgebricksException {
//        //if (gbyVarList.size() == 1) return gbyVarList.get(0).first;
//        ILogicalOperator currentOp = opRef.getValue();
//        while (currentOp != null) {
//            // Check if current operator is a DataSourceScanOperator.
//            if (currentOp.getOperatorTag() == LogicalOperatorTag.DATASOURCESCAN) {
//                DataSourceScanOperator scanOp = (DataSourceScanOperator) currentOp;
//
//                // For each grouping variable, check if it has a corresponding field and B-Tree index.
//                for (Pair<LogicalVariable, Mutable<ILogicalExpression>> varPair : gbyVarList) {
//                    LogicalVariable var = varPair.first;
//                    //if (currentOp.getSchema().contains(var)) {
//                    // Check if the variable has a B-Tree index
//                    if (hasBTreeIndexAccess(var, opRef, context)) {
//                        return var; // Found the leftmost variable with a B-Tree index
//                    }
//                }
//                return null; // No matching variable found in DataSourceScan.
//            }
//
//            // Traverse to the leftmost child if available.
//            if (!currentOp.getInputs().isEmpty()) {
//                currentOp = currentOp.getInputs().get(0).getValue();
//            } else {
//                currentOp = null; // End of the tree.
//            }
//        }
//        //Queue<ILogicalOperator> queue = new LinkedList<>();
//        //
//        // queue.add(root);
//
//
//
//        // No matching variable found
//        return null;
//
//    }
//
//    @Override
//    public boolean rewritePost(Mutable<ILogicalOperator> opRef, IOptimizationContext context){return false;}
//    public Map<FunctionIdentifier, List<IAccessMethod>> getAccessMethods() {
//        return accessMethods;
//    }
//
//}