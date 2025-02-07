package org.apache.hyracks.algebricks.rewriter.rules;

import org.apache.asterix.om.types.IAType;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.common.utils.Pair;
import org.apache.hyracks.algebricks.common.utils.Triple;
import org.apache.hyracks.algebricks.core.algebra.base.*;
import org.apache.hyracks.algebricks.core.algebra.expressions.IVariableTypeEnvironment;
import org.apache.hyracks.algebricks.core.algebra.expressions.VariableReferenceExpression;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.DistributeResultOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.UnionAllOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.visitors.VariableUtilities;
import org.apache.hyracks.algebricks.core.algebra.properties.FunctionalDependency;
import org.apache.hyracks.algebricks.core.algebra.util.OperatorManipulationUtil;
import org.apache.hyracks.algebricks.core.algebra.visitors.ILogicalExpressionVisitor;
import org.apache.hyracks.algebricks.core.rewriter.base.IAlgebraicRewriteRule;
import org.apache.hyracks.api.exceptions.SourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class IntroduceUnionOperatorRule implements IAlgebraicRewriteRule {
    private static final String UNION_RULE_APPLIED = "UnionRuleApplied";
    private static final String PRE_EXISTING_UNION = "PreExistingUnionAll";

    @Override
    public boolean rewritePre(Mutable<ILogicalOperator> opRef, IOptimizationContext context) throws AlgebricksException {
        // Check if the rule has already been applied
        if (Boolean.TRUE.equals(opRef.getValue().getAnnotations().get(UNION_RULE_APPLIED))) {
            return false;
        }
        if (context.checkIfInDontApplySet(this, opRef.getValue())) {
            return false;
        }

        ILogicalOperator op = opRef.getValue();
        LogicalOperatorTag opTag = op.getOperatorTag();
        //List<LogicalVariable> varList = op.

        // Ensure the operator is of the expected type and in interactive mode
        if (opTag != LogicalOperatorTag.DISTRIBUTE_RESULT) {
            return false;
        }
        if (!context.getPhysicalOptimizationConfig().getInteractiveMode()) {
            return false;
        }

        // Process the input operator
        Mutable<ILogicalOperator> inputOpRef = op.getInputs().get(0);
        ILogicalOperator inputOp = inputOpRef.getValue();
        Pair<ILogicalOperator, Map<LogicalVariable, LogicalVariable>> copiedRootWithVars =
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

        // Create the UnionAllOperator

        LogicalVariable resultVar = null, rightVar = null;
        for (int i = 0; i < leftVariables.size(); i++) {
            LogicalVariable leftVar = leftVariables.get(i);
            rightVar = rightVariables.get(i);
            resultVar = context.newVar();

            // Set the type of the new result variable
            IAType rightVarType = (IAType) context.getOutputTypeEnvironment(clonedOp).getVarType(rightVar);
            context.getOutputTypeEnvironment(inputOp).setVarType(resultVar, rightVarType);

            varMap.add(new Triple<>(leftVar, rightVar, resultVar));
        }
        UnionAllOperator unionAllOp = new UnionAllOperator(varMap);
        // Connect inputs to the UnionAllOperator
        unionAllOp.setSourceLocation(inputOp.getSourceLocation());
        unionAllOp.getInputs().add(new MutableObject<>(clonedOp));
        unionAllOp.getInputs().add(new MutableObject<>(inputOp));
        unionAllOp.setExecutionMode(AbstractLogicalOperator.ExecutionMode.PARTITIONED);
        unionAllOp.getAnnotations().put(PRE_EXISTING_UNION, true);

        // Compute the type environment for the union operator
        context.computeAndSetTypeEnvironmentForOperator(unionAllOp);
        annotateUnionChildren(unionAllOp);

        // Replace the input operator with the union operator
        op.getInputs().clear();
        op.getInputs().add(new MutableObject<ILogicalOperator>(unionAllOp));
        DistributeResultOperator distributeResultOperator = (DistributeResultOperator) op;

// Wrap resultVar in VariableReferenceExpression
        VariableReferenceExpression varRefExpr = new VariableReferenceExpression(resultVar);

// Wrap it in Mutable<> since setExpressions() expects Mutable<ILogicalExpression>
        List<Mutable<ILogicalExpression>> varRefExprList = new ArrayList<>();
        varRefExprList.add(new MutableObject<>(varRefExpr));

// Set the expressions list in the DistributeResultOperator
        distributeResultOperator.setExpressions(varRefExprList);
        context.computeAndSetTypeEnvironmentForOperator(op);




        // expressions.add(resultVar);





        // Mark the rule as applied to this operator
        opRef.getValue().getAnnotations().put(UNION_RULE_APPLIED, true);
        context.addToDontApplySet(this, opRef.getValue());

        return true;
    }
    private void annotateOperatorsRecursively(AbstractLogicalOperator operator, String annotationKey, Object annotationValue) {
        // Add the annotation to the current operator
        operator.getAnnotations().put(annotationKey, annotationValue);

        // Recursively annotate the child operators
        for (Mutable<ILogicalOperator> childRef : operator.getInputs()) {
            AbstractLogicalOperator child = (AbstractLogicalOperator) childRef.getValue();
            annotateOperatorsRecursively(child, annotationKey, annotationValue);
        }
    }
    private void annotateUnionChildren(UnionAllOperator unionOp) {
        // Annotate the left side
        AbstractLogicalOperator leftChild = (AbstractLogicalOperator) unionOp.getInputs().get(0).getValue();
        annotateOperatorsRecursively(leftChild, "left_Side_of_Union", true);

        // Annotate the right side
        AbstractLogicalOperator rightChild = (AbstractLogicalOperator) unionOp.getInputs().get(1).getValue();
        annotateOperatorsRecursively(rightChild, "right_Side_of_Union", true);
    }


    @Override
    public boolean rewritePost(Mutable<ILogicalOperator> opRef, IOptimizationContext context) {
        return false;
    }
}
