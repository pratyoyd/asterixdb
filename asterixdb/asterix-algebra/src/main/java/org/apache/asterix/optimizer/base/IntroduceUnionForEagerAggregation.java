package org.apache.asterix.optimizer.base;


import org.apache.asterix.lang.common.util.FunctionUtil;
import org.apache.asterix.om.base.AInt32;
import org.apache.asterix.om.base.AInt64;
import org.apache.asterix.om.base.AString;
import org.apache.asterix.om.base.IAObject;
import org.apache.asterix.om.constants.AsterixConstantValue;
import org.apache.asterix.om.types.IAType;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hyracks.algebricks.common.utils.Triple;
import org.apache.hyracks.algebricks.core.algebra.base.*;
import org.apache.hyracks.algebricks.core.algebra.expressions.*;
import org.apache.hyracks.algebricks.core.algebra.functions.AlgebricksBuiltinFunctions;
import org.apache.hyracks.algebricks.core.algebra.functions.FunctionIdentifier;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.*;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.visitors.VariableUtilities;
import org.apache.hyracks.algebricks.core.algebra.util.OperatorManipulationUtil;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class IntroduceUnionForEagerAggregation implements org.apache.hyracks.algebricks.core.rewriter.base.IAlgebraicRewriteRule {
    private static final String AGGREGATE_UNION = "AggregatingUnionAll";
    @Override
    public boolean rewritePre(Mutable<ILogicalOperator> opRef, IOptimizationContext context) throws AlgebricksException {
        if (context.checkIfInDontApplySet(this, opRef.getValue())) {
            return false;
        }
        if(!context.getPhysicalOptimizationConfig().getInteractiveMode()) return false;
        ILogicalOperator op = opRef.getValue();
        if (op.getOperatorTag() != LogicalOperatorTag.DISTRIBUTE_RESULT) return false;
        if(findAndApplyUnions(opRef, context)){
            context.addToDontApplySet(this, opRef.getValue());
            return true;
        }
        return false;

    }

    private boolean findAndApplyUnions(Mutable<ILogicalOperator> parentOpRef, IOptimizationContext context) throws AlgebricksException {
        Mutable<ILogicalOperator> opRef = parentOpRef.getValue().getInputs().get(0);
        ILogicalOperator op = opRef.getValue();
        for (int i = 0; i < op.getInputs().size(); i++) {
            ILogicalOperator child = op.getInputs().get(i).getValue();
            Mutable<ILogicalOperator> inputOpRef = op.getInputs().get(i);
            ILogicalOperator inputOp = inputOpRef.getValue();
            if(inputOp.getAnnotations().containsKey("right_Side_of_Union"))continue;
            Pair<ConstantExpression, ConstantExpression> valuePair =  extractValuePairFromAssign(inputOpRef, context);
            List<Pair<Mutable<ILogicalExpression>, Mutable<ILogicalExpression>>> filterExpressionList = generateIntermediatePairs(valuePair);
            Mutable<ILogicalOperator> IteratingUnionOpRef = new MutableObject<>(op);
            //create the Unions
            for(int j = 0; j < filterExpressionList.size() -1; j++){
                Mutable<ILogicalOperator> newUnionOperatorRef = createUnionOperator(IteratingUnionOpRef, context);
                ILogicalOperator newUnionOperator = newUnionOperatorRef.getValue();
                IteratingUnionOpRef.setValue(newUnionOperator);
            }
            //Fill in the right selects and assigns
            IteratingUnionOpRef.setValue(op.getInputs().get(0).getValue());
            return modifyLeftDeepBranch(IteratingUnionOpRef,filterExpressionList, context);
            //return true;






        }
        return false;

    }

    private boolean replaceAssignAndSelectOps(Mutable<ILogicalOperator> iteratingUnionOpRef,
                                              Pair<Mutable<ILogicalExpression>, Mutable<ILogicalExpression>> valuePair,
                                              IOptimizationContext context) throws AlgebricksException {
        // Step 1: Traverse to find the leftmost two selects
        List<Mutable<ILogicalExpression>>newAssignExpressionList = new ArrayList<>();
        newAssignExpressionList.add((Mutable<ILogicalExpression>) valuePair.getLeft());
        newAssignExpressionList.add((Mutable<ILogicalExpression>) valuePair.getRight());
        List<Mutable<ILogicalOperator>> selectOps = new ArrayList<>();
        findLeftmostSelectOps(iteratingUnionOpRef, selectOps, 2);
        for (Mutable<ILogicalOperator> opRef : selectOps) {
            createNewSelectOp(opRef, valuePair, context);
        }



        List<Mutable<ILogicalOperator>> assignOpRefList = findAssignsAboveEmptyTuple(iteratingUnionOpRef);
        for(Mutable<ILogicalOperator> assignOpRef : assignOpRefList) {
            if (assignOpRef == null) {
                throw new IllegalStateException("Could not find Assign operator above EmptyTupleSource");
            }

            AssignOperator assignOp = (AssignOperator) assignOpRef.getValue();
            assignOp.setExpressions(newAssignExpressionList);
            context.computeAndSetTypeEnvironmentForOperator(assignOp);
        }


        return true;
    }

    private void createNewSelectOp(Mutable<ILogicalOperator> opRef, Pair<Mutable<ILogicalExpression>, Mutable<ILogicalExpression>> valuePair, IOptimizationContext context) throws AlgebricksException {
        SelectOperator oldSelectOp = (SelectOperator) opRef.getValue();
        ILogicalExpression leftValue = new ConstantExpression(new AsterixConstantValue(new AString(valuePair.getLeft().toString())));
        ILogicalExpression rightValue = new ConstantExpression(new AsterixConstantValue(new AString(valuePair.getRight().toString())));
        Mutable<ILogicalExpression> filterExpr = oldSelectOp.getCondition();
        ScalarFunctionCallExpression oldAndExpr = (ScalarFunctionCallExpression) filterExpr.getValue();
        List<Mutable<ILogicalExpression>> andArgs = oldAndExpr.getArguments();
        ILogicalExpression gtExpr = andArgs.get(1).getValue();  // First argument of AND
        ILogicalExpression ltExpr = andArgs.get(0).getValue();
        LogicalVariable var = extractVariableFromCondition(ltExpr);// Second argument of AND




        // Step 4: Create new `SELECT` condition expressions
        FunctionIdentifier geFunction = AlgebricksBuiltinFunctions.GE;
        FunctionIdentifier leFunction = AlgebricksBuiltinFunctions.LE;

        AbstractFunctionCallExpression newGeExpr = new ScalarFunctionCallExpression(
                FunctionUtil.getFunctionInfo(geFunction),
                List.of(new MutableObject<>(new VariableReferenceExpression(var)), new MutableObject<>(leftValue))
        );

        AbstractFunctionCallExpression newLeExpr = new ScalarFunctionCallExpression(
                FunctionUtil.getFunctionInfo(leFunction),
                List.of(new MutableObject<>(new VariableReferenceExpression(var)), new MutableObject<>(rightValue))
        );

        // Step 6: Construct the new AND condition: (gt && lt)
        AbstractFunctionCallExpression newAndExpr = new ScalarFunctionCallExpression(
                FunctionUtil.getFunctionInfo(AlgebricksBuiltinFunctions.AND),
                List.of(new MutableObject<>(newGeExpr), new MutableObject<>(newLeExpr))
        );

        // Step 7: Create a new `SELECT` operator with the updated filter condition
        SelectOperator newSelectOp = new SelectOperator(new MutableObject<>(newAndExpr));
        newSelectOp.getInputs().add(oldSelectOp.getInputs().get(0)); // Attach the same child as the old `SELECT`
        newSelectOp.setExecutionMode(AbstractLogicalOperator.ExecutionMode.PARTITIONED);
        newSelectOp.setSourceLocation(oldSelectOp.getSourceLocation());

        // Step 8: Replace the old `SELECT` operator with the new one
        opRef.setValue(newSelectOp);
        context.computeAndSetTypeEnvironmentForOperator(newSelectOp);
    }
    private LogicalVariable extractVariableFromCondition(ILogicalExpression expr) {
        if (expr instanceof ScalarFunctionCallExpression) {
            ScalarFunctionCallExpression funcExpr = (ScalarFunctionCallExpression) expr;
            List<Mutable<ILogicalExpression>> args = funcExpr.getArguments();

            // First argument is always the filtering variable (LogicalVariable)
            ILogicalExpression varExpr = args.get(0).getValue();

            if (varExpr instanceof VariableReferenceExpression) {
                return ((VariableReferenceExpression) varExpr).getVariableReference();
            }
        }
        throw new IllegalArgumentException("Expected a GE or LE function with a variable.");
    }


//    private List<Mutable<ILogicalOperator>> findAssignAboveEmptyTuple(Mutable<ILogicalOperator> iteratingUnionOpRef) {
//        List<Mutable<ILogicalOperator>> assignOps = new ArrayList<>();
//        ILogicalOperator currentOp = iteratingUnionOpRef.getValue();
//        ILogicalOperator parentOp = null;
//        while (true) {
//            List<Mutable<ILogicalOperator>> inputs = currentOp.getInputs();
//
//            if (inputs.isEmpty()) {
//
//                break;// No more operators to traverse
//            }
//
//
//
//            ILogicalOperator nextOp = inputs.get(0).getValue();
//
//            if (nextOp.getOperatorTag() == LogicalOperatorTag.EMPTYTUPLESOURCE) {
//                //parentOp = currentOp; // Store the parent before moving deeper
//                //currentOp = nextOp;
//                break; // Stop traversal when reaching EMPTY_TUPLE_SOURCE
//            }
//
//            parentOp = currentOp; // Store the parent before moving deeper
//            currentOp = nextOp;
//        }
//        if (currentOp.getOperatorTag() == LogicalOperatorTag.ASSIGN) {
//            assignOps.add(new MutableObject<>(currentOp));
//        }
//        return null;
//    }


    private List<Mutable<ILogicalOperator>> findAssignsAboveEmptyTuple(Mutable<ILogicalOperator> iteratingUnionOpRef) {
        List<Mutable<ILogicalOperator>> assignOps = new ArrayList<>();
        findAssignsRecursive(iteratingUnionOpRef, assignOps);
        return assignOps;
    }

    private void findAssignsRecursive(Mutable<ILogicalOperator> opRef, List<Mutable<ILogicalOperator>> assignOps) {
        if (opRef == null || opRef.getValue() == null) {
            return; // Base case: stop recursion on null operator
        }

        ILogicalOperator currentOp = opRef.getValue();
        List<Mutable<ILogicalOperator>> inputs = currentOp.getInputs();

        // If we find an EMPTY_TUPLE_SOURCE, check if the parent is an ASSIGN
        if (!inputs.isEmpty() && inputs.get(0).getValue().getOperatorTag() == LogicalOperatorTag.EMPTYTUPLESOURCE) {
            if (currentOp.getOperatorTag() == LogicalOperatorTag.ASSIGN) {
                assignOps.add(opRef); // Store the ASSIGN operator above EMPTY_TUPLE_SOURCE
            }
        }

        // Recursively process left child
        if (!inputs.isEmpty()) {
            findAssignsRecursive(inputs.get(0), assignOps);
        }

        // If it's a UNIONALL operator, also explore the right child
        if (currentOp.getOperatorTag() == LogicalOperatorTag.UNIONALL && inputs.size() > 1) {
            findAssignsRecursive(inputs.get(1), assignOps);
        }
    }


    private Pair extractValuePairFromAssign(Mutable<ILogicalOperator> opRef, IOptimizationContext context) {
        ILogicalOperator currentOp = opRef.getValue();
        ILogicalOperator parentOp = null;
        while (true) {
            List<Mutable<ILogicalOperator>> inputs = currentOp.getInputs();

            if (inputs.isEmpty()) {
                break; // No more operators to traverse
            }

            ILogicalOperator nextOp = inputs.get(0).getValue();

            if (nextOp.getOperatorTag() == LogicalOperatorTag.EMPTYTUPLESOURCE) {
                //parentOp = currentOp; // Store the parent before moving deeper
                //currentOp = nextOp;
                break; // Stop traversal when reaching EMPTY_TUPLE_SOURCE
            }

            parentOp = currentOp; // Store the parent before moving deeper
            currentOp = nextOp;
        }
        if (currentOp instanceof AssignOperator) {
            AssignOperator assignOp = (AssignOperator) currentOp;
            List<Mutable<ILogicalExpression>> expressions = assignOp.getExpressions();

            if (expressions.size() >= 2) { // Extract the two assigned values
                return Pair.of(expressions.get(0).getValue(), expressions.get(1).getValue());
            }
        }
        return null;
    }

    @Override
    public boolean rewritePost(Mutable<ILogicalOperator> opRef, IOptimizationContext context) throws AlgebricksException {
        return false;
    }
    public static List<Pair<Mutable<ILogicalExpression>, Mutable<ILogicalExpression>>> generateIntermediatePairs(Pair<ConstantExpression, ConstantExpression> valuePair) throws AlgebricksException {
        System.out.println("valuePair.getLeft() class: " + valuePair.getLeft().getClass().getName());
        System.out.println("valuePair.getLeft() value: " + valuePair.getLeft());
        ILogicalExpression leftExpr = valuePair.getLeft();
        ILogicalExpression rightExpr = valuePair.getRight();
        // Now safely cast
        ConstantExpression leftConstExpr = (ConstantExpression) leftExpr;
        ConstantExpression rightConstExpr = (ConstantExpression) rightExpr;
        AsterixConstantValue leftConstValue = (AsterixConstantValue) leftConstExpr.getValue();
        AsterixConstantValue rightConstValue = (AsterixConstantValue) rightConstExpr.getValue();
        IAObject leftObject = leftConstValue.getObject();
        IAObject rightObject = rightConstValue.getObject();



        List<Pair<Mutable<ILogicalExpression>, Mutable<ILogicalExpression>>> expressionPairs = new ArrayList<>();



        // Check if values are dates
        if ((leftObject instanceof AString && rightObject instanceof AString)) {
            try {
                LocalDate startDate = LocalDate.parse(((AString) leftObject ).getStringValue());
                LocalDate endDate = LocalDate.parse(((AString) rightObject).getStringValue());

                while (!startDate.isAfter(endDate.minusDays(1))) {
                    ILogicalExpression left = new ConstantExpression(new AsterixConstantValue(new AString(startDate.toString())));
                    ILogicalExpression right = new ConstantExpression(new AsterixConstantValue(new AString(startDate.plusDays(1).toString())));
                    Mutable<ILogicalExpression> leftRef = new MutableObject<>(left);
                    Mutable<ILogicalExpression> rightRef = new MutableObject<>(right);
                    expressionPairs.add(Pair.of(leftRef, rightRef));
                    startDate = startDate.plusDays(1);
                }
                return expressionPairs;
            } catch (DateTimeParseException ignored) {
                // Not a date, continue checking other types
            }
        }

        // Check if values are integers
        if (leftConstExpr.getValue()instanceof  AInt32 && rightConstExpr.getValue() instanceof AInt32) {
            int start = ((AInt32) leftConstExpr.getValue() ).getIntegerValue();
            int end = ((AInt32) rightConstExpr.getValue() ).getIntegerValue();
            for (int i = start; i < end; i++) {  // Notice `i < end` to form (i, i+1)
                ILogicalExpression left = new ConstantExpression(new AsterixConstantValue(new AInt32(i)));
                ILogicalExpression right = new ConstantExpression(new AsterixConstantValue(new AInt32(i + 1)));
                Mutable<ILogicalExpression> leftRef = new MutableObject<>(left);
                Mutable<ILogicalExpression> rightRef = new MutableObject<>(right);
                expressionPairs.add(Pair.of(leftRef, rightRef));
            }
            return expressionPairs;
        }

        // Check if values are BigIntegers (AInt64)
        if (leftConstExpr.getValue() instanceof AInt64 && rightConstExpr.getValue() instanceof AInt64) {
            long start = ((AInt64) leftConstExpr.getValue() ).getLongValue();
            long end = ((AInt64) rightConstExpr.getValue()).getLongValue();
            while (start < end) {
                ILogicalExpression left = new ConstantExpression(new AsterixConstantValue(new AInt64(start)));
                ILogicalExpression right = new ConstantExpression(new AsterixConstantValue(new AInt64(start + 1)));
                Mutable<ILogicalExpression> leftRef = new MutableObject<>(left);
                Mutable<ILogicalExpression> rightRef = new MutableObject<>(right);
                expressionPairs.add(Pair.of(leftRef, rightRef));
                start++;
            }
            return expressionPairs;
        }
        return null;



    }

    private Mutable<ILogicalOperator> createUnionOperator(Mutable<ILogicalOperator> opRef, IOptimizationContext context) throws AlgebricksException {
        ILogicalOperator op = opRef.getValue();
        Mutable<ILogicalOperator> leftInputOpRef = op.getInputs().get(0);
        Mutable<ILogicalOperator> rightInputOpRef = op.getInputs().get(1);
        ILogicalOperator inputOp = leftInputOpRef.getValue();
        org.apache.hyracks.algebricks.common.utils.Pair<ILogicalOperator, Map<LogicalVariable, LogicalVariable>> copiedRootWithVars =
                OperatorManipulationUtil.deepCopyWithNewVars(inputOp, context);
        ILogicalOperator clonedOp = copiedRootWithVars.getFirst();

        // Compute type environments for input and cloned operators
        context.computeAndSetTypeEnvironmentForOperator(inputOp);
        context.computeAndSetTypeEnvironmentForOperator(clonedOp);

        // Prepare variable mappings for the UnionAllOperator
        List<LogicalVariable> leftVariables = new ArrayList<>();
        VariableUtilities.getLiveVariables(clonedOp, leftVariables);
        List<LogicalVariable> rightVariables = new ArrayList<>();
        VariableUtilities.getLiveVariables(inputOp, rightVariables);
        List<Triple<LogicalVariable, LogicalVariable, LogicalVariable>> varMap = new ArrayList<>();
        List<LogicalVariable> resultVariables = new ArrayList<>();

        // Create the UnionAllOperator

        LogicalVariable resultVar = null, rightVar = null;
        for (int i = 0; i < leftVariables.size(); i++) {
            LogicalVariable leftVar = leftVariables.get(i);
            rightVar = rightVariables.get(i);
            resultVar = context.newVar();

            // Set the type of the new result variable
            IAType rightVarType = (IAType) context.getOutputTypeEnvironment(clonedOp).getVarType(rightVar);
            context.getOutputTypeEnvironment(inputOp).setVarType(resultVar, rightVarType);
            resultVariables.add(resultVar);

            varMap.add(new Triple<>(leftVar, rightVar, resultVar));
        }
        UnionAllOperator unionAllOp = new UnionAllOperator(varMap);
        // Connect inputs to the UnionAllOperator
        unionAllOp.setSourceLocation(inputOp.getSourceLocation());
        unionAllOp.getInputs().add(new MutableObject<>(clonedOp));
        unionAllOp.getInputs().add(new MutableObject<>(inputOp));
        unionAllOp.setExecutionMode(AbstractLogicalOperator.ExecutionMode.PARTITIONED);
        unionAllOp.getAnnotations().put(AGGREGATE_UNION, true);

        // Compute the type environment for the union operator
        context.computeAndSetTypeEnvironmentForOperator(unionAllOp);


        // Replace the input operator with the union operator

        Mutable<ILogicalOperator> unionAllOpRef = new MutableObject<ILogicalOperator>(unionAllOp);

        List<Triple<LogicalVariable, LogicalVariable, LogicalVariable>> parentVarMap = ((UnionAllOperator) op).getVariableMappings();


        if (resultVariables.size() != parentVarMap.size()) {
            throw new IllegalArgumentException("Mismatch between varMap size and newVars size.");
        }

        for (int i = 0; i < parentVarMap.size(); i++) {
            Triple<LogicalVariable, LogicalVariable, LogicalVariable> t = parentVarMap.get(i);
            parentVarMap.set(i, new Triple<>(resultVariables.get(i), t.second, t.third));
        }


        op.getInputs().clear();
        op.getInputs().add(unionAllOpRef);
        op.getInputs().add(rightInputOpRef);
        context.computeAndSetTypeEnvironmentForOperator(op);
        return  unionAllOpRef;
    }



//        //Recompute variables and recreate union operator
//        UnionAllOperator unionOp = (UnionAllOperator) op;
//        List<Triple<LogicalVariable, LogicalVariable, LogicalVariable>> originalVarMap = unionOp.getVariableMappings();
//        List<LogicalVariable> thirdVariables = new ArrayList<>();
//        for (Triple<LogicalVariable, LogicalVariable, LogicalVariable> triple : originalVarMap) {
//            thirdVariables.add(triple.third);
//        }
//        List<LogicalVariable> rightinputVariables = new ArrayList<>();
//        VariableUtilities.getLiveVariables(rightInputOp, rightinputVariables);
//        for (int i = 0; i < resultVariables.size(); i++) {
//            LogicalVariable leftVar = resultVariables.get(i);
//            rightVar = rightinputVariables.get(i);
//            resultVar = thirdVariables.get(i);
//
//            // Set the type of the new result variable
//
//
//            varMap.add(new Triple<>(leftVar, rightVar, resultVar));
//        }
//        UnionAllOperator parentUnionAllOp = new UnionAllOperator(varMap);
//        parentUnionAllOp.getInputs().add(unionAllOpRef);
//        parentUnionAllOp.getInputs().add(rightInputOpRef);
//        Mutable<ILogicalOperator> parentUnionAllOpRef = new MutableObject<ILogicalOperator>(parentUnionAllOp);
//
//
//
//
//
//
//
//
//
//
//// Set the expressions list in the DistributeResultOperator



    private void findLeftmostSelectOps(Mutable<ILogicalOperator> opRef,
                                       List<Mutable<ILogicalOperator>> selectOps,
                                       int maxSelects) {
        if (selectOps.size() >= maxSelects) {
            return;
        }

        ILogicalOperator op = opRef.getValue();

        if (op.getOperatorTag() == LogicalOperatorTag.SELECT) {
            selectOps.add(opRef);
        }

        // Traverse children while prioritizing the leftmost path
        for (Mutable<ILogicalOperator> childOpRef : op.getInputs()) {
            if (selectOps.size() >= maxSelects) {
                return;  // Stop early if we already found enough selects
            }
            findLeftmostSelectOps(childOpRef, selectOps, maxSelects);
        }
    }
    private boolean modifyLeftDeepBranch(Mutable<ILogicalOperator> iteratingUnionOpRef,
                                         List<Pair<Mutable<ILogicalExpression>, Mutable<ILogicalExpression>>> filterExpressionList,
                                         IOptimizationContext context) throws AlgebricksException {
        boolean modified = false;
        Mutable<ILogicalOperator> currentOpRef = iteratingUnionOpRef;

        // Traverse down modifying right branches
        int j =0;
        while (currentOpRef.getValue() instanceof UnionAllOperator) {
            UnionAllOperator unionOp = (UnionAllOperator) currentOpRef.getValue();
            List<Mutable<ILogicalOperator>> inputs = unionOp.getInputs();

            if (inputs.size() < 2) {
                break; // Safety check
            }

            // Modify the right branch
            boolean rightModified = replaceAssignAndSelectOps(inputs.get(1), filterExpressionList.get(j), context);
            if (rightModified) {
                modified = true;
            }

            // Move to the left child
            currentOpRef = inputs.get(0);
            j++;
        }

            // Now modify the leftmost branch (only once)
            boolean leftModified = replaceAssignAndSelectOps(currentOpRef, filterExpressionList.get(j), context);
            if (leftModified) {
                modified = true;
            }


        return modified;
    }






}
