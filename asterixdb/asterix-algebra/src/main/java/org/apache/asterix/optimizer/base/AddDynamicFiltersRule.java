package org.apache.asterix.optimizer.base;

import org.apache.asterix.om.base.AInt32;
import org.apache.asterix.om.base.AString;
import org.apache.asterix.om.constants.AsterixConstantValue;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.core.algebra.base.*;
import org.apache.hyracks.algebricks.core.algebra.expressions.AbstractFunctionCallExpression;
import org.apache.hyracks.algebricks.core.algebra.expressions.ConstantExpression;
import org.apache.hyracks.algebricks.core.algebra.expressions.ScalarFunctionCallExpression;
import org.apache.hyracks.algebricks.core.algebra.expressions.VariableReferenceExpression;
import org.apache.hyracks.algebricks.core.algebra.functions.AlgebricksBuiltinFunctions;
import org.apache.hyracks.algebricks.core.algebra.functions.FunctionIdentifier;
import org.apache.hyracks.algebricks.core.algebra.functions.IFunctionInfo;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.GroupByOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.SelectOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.UnionAllOperator;

import java.util.ArrayList;
import java.util.List;

public class AddDynamicFiltersRule implements org.apache.hyracks.algebricks.core.rewriter.base.IAlgebraicRewriteRule {
    @Override
    public boolean rewritePost(Mutable<ILogicalOperator> opRef, IOptimizationContext context) {

        return false;
    }

    @Override
    public boolean rewritePre(Mutable<ILogicalOperator> opRef, IOptimizationContext context) throws AlgebricksException {
        if (!context.getPhysicalOptimizationConfig().getInteractiveMode()) return false;

        ILogicalOperator op = opRef.getValue();
        if (op.getOperatorTag() == LogicalOperatorTag.UNIONALL) {
            return findGroupByAndApplyFilter(opRef, context);
        }
        //IPhysicalOperator physicalOperator = op.get


        return false;
    }

    private boolean findGroupByAndApplyFilter(Mutable<ILogicalOperator> opRef, IOptimizationContext context) throws AlgebricksException {

        AbstractLogicalOperator op = (AbstractLogicalOperator) opRef.getValue();

        for (int i = 0; i < op.getInputs().size(); i++) {
            if (op.getOperatorTag() == LogicalOperatorTag.UNIONALL) {
                UnionAllOperator unionAllOp = (UnionAllOperator) op;
//                if (Boolean.TRUE.equals(unionAllOp.getAnnotations().get("PreExistingUnionAll")) && context.getPhysicalOptimizationConfig().getInteractiveMode() && i == 0) {
//                    continue;
//                }

            }
            Mutable<ILogicalOperator> inputOpRef = op.getInputs().get(i);
            ILogicalOperator inputOp = inputOpRef.getValue();
            if(op.getOperatorTag() == LogicalOperatorTag.GROUP )
            {
                if (context.checkIfInDontApplySet(this, opRef.getValue())) {
                    return false;
                }
                if(op.getAnnotations().containsKey("right_Side_of_Union")) {


                    GroupByOperator groupByOp = (GroupByOperator) inputOp;
                    List<LogicalVariable> logicalVariableList = groupByOp.getGroupByVarList();
                    ILogicalExpression filterCondition = createGreaterThanFilterCondition(logicalVariableList, context);
                    SelectOperator selectOperator = new SelectOperator(new MutableObject<>(filterCondition));
                    selectOperator.setSourceLocation(inputOp.getSourceLocation());
                    selectOperator.setExecutionMode(AbstractLogicalOperator.ExecutionMode.PARTITIONED);
                    selectOperator.getInputs().add(new MutableObject<>(inputOp));
                    context.computeAndSetTypeEnvironmentForOperator(selectOperator);
                    inputOpRef.setValue(selectOperator);
                    context.addToDontApplySet(this, opRef.getValue());
                }
                else if(op.getAnnotations().containsKey("left_Side_of_Union")) {


                    GroupByOperator groupByOp = (GroupByOperator) inputOp;
                    List<LogicalVariable> logicalVariableList = groupByOp.getGroupByVarList();
                    ILogicalExpression filterCondition = createLessThanFilterCondition(logicalVariableList, context);
                    SelectOperator selectOperator = new SelectOperator(new MutableObject<>(filterCondition));
                    selectOperator.setSourceLocation(inputOp.getSourceLocation());
                    selectOperator.setExecutionMode(AbstractLogicalOperator.ExecutionMode.PARTITIONED);
                    selectOperator.getInputs().add(new MutableObject<>(inputOp));
                    context.computeAndSetTypeEnvironmentForOperator(selectOperator);
                    inputOpRef.setValue(selectOperator);
                    context.addToDontApplySet(this, opRef.getValue());
                }






                return true;




                //SelectOperator selectOp = new SelectOperator()

            }
            if (findGroupByAndApplyFilter(inputOpRef, context)) {
                return true; // Stop further recursion if a transformation was applied
            }


        }
    return false;
    }

    private ILogicalExpression createGreaterThanFilterCondition(List<LogicalVariable> targetVars, IOptimizationContext context) {
        if (targetVars.isEmpty()) {
            throw new IllegalArgumentException("The list of variables cannot be empty.");
        }

        // Create the "AND" function
        IFunctionInfo andFunctionInfo = context.getMetadataProvider().lookupFunction(AlgebricksBuiltinFunctions.AND);


        // Create the composite condition
        List<Mutable<ILogicalExpression>> andArguments = new ArrayList<>();

        for (LogicalVariable targetVar : targetVars) {
            // Create "targetVar > 0" for each variable
            ILogicalExpression greaterThanExpression = createGreaterThanCondition(targetVar, context);
            andArguments.add(new MutableObject<>(greaterThanExpression));
        }
        if (andArguments.size() == 1) {
            return andArguments.get(0).getValue();
        }
        // Create the "AND" logical expression
        return new ScalarFunctionCallExpression(andFunctionInfo, andArguments);

    }

    private ILogicalExpression createLessThanFilterCondition(List<LogicalVariable> targetVars, IOptimizationContext context) {
        if (targetVars.isEmpty()) {
            throw new IllegalArgumentException("The list of variables cannot be empty.");
        }

        // Create the "AND" function
        IFunctionInfo andFunctionInfo = context.getMetadataProvider().lookupFunction(AlgebricksBuiltinFunctions.AND);


        // Create the composite condition
        List<Mutable<ILogicalExpression>> andArguments = new ArrayList<>();

        for (LogicalVariable targetVar : targetVars) {
            // Create "targetVar > 0" for each variable
            ILogicalExpression lessThanExpression = createLessThanCondition(targetVar, context);
            andArguments.add(new MutableObject<>(lessThanExpression));
        }
        if (andArguments.size() == 1) {
            return andArguments.get(0).getValue();
        }
        // Create the "AND" logical expression
        return new ScalarFunctionCallExpression(andFunctionInfo, andArguments);

    }
    private ILogicalExpression createLessThanCondition(LogicalVariable targetVar, IOptimizationContext context) {
        // Create the ">" function
        IFunctionInfo gtFunctionInfo = context.getMetadataProvider().lookupFunction(AlgebricksBuiltinFunctions.LT);
        //IFunctionInfo gtFunctionInfo = context.getMetadataProvider().lookupFunction(AlgebricksBuiltinFunctions.LT);

        // Use the built-in "greater than" function

        // Create the constant expression for "0"
        //ILogicalExpression constantZero = new ConstantExpression(new AsterixConstantValue(new AInt32(0)));
        ILogicalExpression constantString = new ConstantExpression(new AsterixConstantValue(new AString("1994-01-03")));


        // Create the variable reference expression
        ILogicalExpression variableExpression = new VariableReferenceExpression(targetVar);

        // Create the "targetVar > 0" logical expression
        List<Mutable<ILogicalExpression>> gtArguments = new ArrayList<>();
        gtArguments.add(new MutableObject<>(variableExpression));
        gtArguments.add(new MutableObject<>(constantString));

        return new ScalarFunctionCallExpression(gtFunctionInfo, gtArguments);
    }

    private ILogicalExpression createGreaterThanCondition(LogicalVariable targetVar, IOptimizationContext context) {
        // Create the ">" function
        IFunctionInfo gtFunctionInfo = context.getMetadataProvider().lookupFunction(AlgebricksBuiltinFunctions.GT);
        //IFunctionInfo gtFunctionInfo = context.getMetadataProvider().lookupFunction(AlgebricksBuiltinFunctions.LT);

        // Use the built-in "greater than" function

        // Create the constant expression for "0"
        //ILogicalExpression constantZero = new ConstantExpression(new AsterixConstantValue(new AInt32(0)));
        ILogicalExpression constantString = new ConstantExpression(new AsterixConstantValue(new AString("1994-01-06")));


        // Create the variable reference expression
        ILogicalExpression variableExpression = new VariableReferenceExpression(targetVar);

        // Create the "targetVar > 0" logical expression
        List<Mutable<ILogicalExpression>> gtArguments = new ArrayList<>();
        gtArguments.add(new MutableObject<>(variableExpression));
        gtArguments.add(new MutableObject<>(constantString));

        return new ScalarFunctionCallExpression(gtFunctionInfo, gtArguments);
    }
}

