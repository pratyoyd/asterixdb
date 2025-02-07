package org.apache.hyracks.algebricks.core.algebra.operators.logical;

import java.util.ArrayList;

import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalOperatorTag;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalExpression;
import org.apache.hyracks.algebricks.core.algebra.expressions.IVariableTypeEnvironment;
import org.apache.hyracks.algebricks.core.algebra.properties.TypePropagationPolicy;
import org.apache.hyracks.algebricks.core.algebra.properties.VariablePropagationPolicy;
import org.apache.hyracks.algebricks.core.algebra.typing.ITypeEnvPointer;
import org.apache.hyracks.algebricks.core.algebra.typing.ITypingContext;
import org.apache.hyracks.algebricks.core.algebra.typing.PropagatingTypeEnvironment;
import org.apache.hyracks.algebricks.core.algebra.visitors.ILogicalExpressionReferenceTransform;
import org.apache.hyracks.algebricks.core.algebra.visitors.ILogicalOperatorVisitor;

/**
 * PlanSwitchOperator dynamically switches between two sub-plans based on runtime conditions.
 */
public class PlanSwitchOperator extends AbstractLogicalOperator {

    // Sub-plans for execution
    private final Mutable<ILogicalOperator> interactivePlan;
    private final Mutable<ILogicalOperator> blockingPlan;

    // A generalized condition for switching plans
    private final Mutable<ILogicalExpression> switchCondition;

    // Active plan: 0 for interactivePlan, 1 for blockingPlan
    private int activePlan;

    public PlanSwitchOperator(Mutable<ILogicalOperator> interactivePlan, Mutable<ILogicalOperator> blockingPlan,
                              Mutable<ILogicalExpression> switchCondition) {
        this.interactivePlan = interactivePlan;
        this.blockingPlan = blockingPlan;
        this.switchCondition = switchCondition;

        this.activePlan = 0; // Default to interactivePlan
        recomputeSchema();
    }

    public Mutable<ILogicalOperator> getinteractivePlan() {
        return interactivePlan;
    }

    public Mutable<ILogicalOperator> getblockingPlan() {
        return blockingPlan;
    }

    public Mutable<ILogicalExpression> getSwitchCondition() {
        return switchCondition;
    }

    public int getActivePlan() {
        return activePlan;
    }

    public void setActivePlan(int activePlan) {
        this.activePlan = activePlan;
        recomputeSchema(); // Update schema when switching plans
    }


    @Override
    public LogicalOperatorTag getOperatorTag() {
        return LogicalOperatorTag.PLAN_SWITCH;
    }

    @Override
    public void recomputeSchema() {
        // Schema is determined by the active plan
        schema = new ArrayList<>();
        if (activePlan == 0) {
     //       schema.addAll(interactivePlan.getSchema());
        } else {
      //      schema.addAll(blockingPlan.getSchema());
        }
    }

    @Override
    public VariablePropagationPolicy getVariablePropagationPolicy() {
        // Use ALL propagation as both plans contribute their variables
        return VariablePropagationPolicy.ALL;
    }


    @Override
    public boolean acceptExpressionTransform(ILogicalExpressionReferenceTransform visitor) throws AlgebricksException {
        Mutable<ILogicalExpression> condition = new MutableObject<>(switchCondition.getValue());
        boolean conditionTransformed = visitor.transform(condition);
        switchCondition.setValue(condition.getValue());
        return conditionTransformed;
    }


    @Override
    public <R, T> R accept(ILogicalOperatorVisitor<R, T> visitor, T arg) throws AlgebricksException {
        // Define how visitors should process this operator
        R r = visitor.visitSwitchOperator((SwitchOperator) visitor, arg);
        return r;
    }

    @Override
    public boolean isMap() {
        return false; // The operator switches between plans, altering tuple flow
    }

    @Override
    public IVariableTypeEnvironment computeOutputTypeEnvironment(ITypingContext ctx) throws AlgebricksException {
        // Compute the type environment based on the active plan
        ITypeEnvPointer[] envPointers = new ITypeEnvPointer[1];
        envPointers[0] = new ITypeEnvPointer() {
            @Override
            public IVariableTypeEnvironment getTypeEnv() {
                ILogicalOperator activeOperator = activePlan == 0 ? interactivePlan.getValue() : blockingPlan.getValue();
                return ctx.getOutputTypeEnvironment(activeOperator);
            }
        };
        return new PropagatingTypeEnvironment(ctx.getExpressionTypeComputer(), ctx.getMissableTypeComputer(),
                ctx.getMetadataProvider(), TypePropagationPolicy.ALL, envPointers);
    }

    @Override
    public IVariableTypeEnvironment computeInputTypeEnvironment(ITypingContext ctx) throws AlgebricksException {
        // Input type environment matches output type environment for this operator
        return computeOutputTypeEnvironment(ctx);
    }
}
