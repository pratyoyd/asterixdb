package org.apache.asterix.optimizer.rules;

import org.apache.asterix.om.functions.BuiltinFunctions;
import org.apache.asterix.om.types.ARecordType;
import org.apache.asterix.om.types.BuiltinType;
import org.apache.asterix.om.types.IAType;
import org.apache.asterix.runtime.projection.ColumnDatasetProjectionFiltrationInfo;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalExpression;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.base.IOptimizationContext;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalOperatorTag;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalVariable;
import org.apache.hyracks.algebricks.core.algebra.expressions.AbstractFunctionCallExpression;
import org.apache.hyracks.algebricks.core.algebra.expressions.ConstantExpression;
import org.apache.hyracks.algebricks.core.algebra.metadata.IProjectionFiltrationInfo;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AggregateOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AssignOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.DataSourceScanOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.OrderOperator;
import org.apache.hyracks.algebricks.core.rewriter.base.IAlgebraicRewriteRule;

/**
 * Sets PLAQUE page-level filter metadata on the DataSourceScanOperator's
 * ColumnDatasetProjectionFiltrationInfo. Runs after PushValueAccessAndFilterDownRule
 * which creates the projectionFiltrationInfo.
 *
 * Walks from the PLAQUE-annotated aggregate down to the DataSourceScan, finds the
 * ASSIGN that defines the filtered variable, extracts the field name from the
 * field-access expression, and builds an ARecordType path for the column.
 */
public class PlaquePageFilterRule implements IAlgebraicRewriteRule {

    @Override
    public boolean rewritePre(Mutable<ILogicalOperator> opRef, IOptimizationContext context) {
        return false;
    }

    @Override
    public boolean rewritePost(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
            throws AlgebricksException {

        AbstractLogicalOperator op = (AbstractLogicalOperator) opRef.getValue();

        if (op.getOperatorTag() == LogicalOperatorTag.AGGREGATE) {
            return handleAggregate(opRef, context);
        }

        if (op.getOperatorTag() == LogicalOperatorTag.INNERJOIN) {
            return handleThetaJoin(opRef, context);
        }

        if (op.getOperatorTag() == LogicalOperatorTag.ORDER) {
            return handleTopKSort(opRef, context);
        }

        return false;
    }

    private boolean handleAggregate(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
            throws AlgebricksException {
        AggregateOperator aggOp = (AggregateOperator) opRef.getValue();
        if (!Boolean.TRUE.equals(aggOp.getAnnotations().get("plaque-aggregate"))) {
            return false;
        }
        if (Boolean.TRUE.equals(aggOp.getAnnotations().get("plaque-page-filter-set"))) {
            return false;
        }

        String handle = (String) aggOp.getAnnotations().get("plaque-handle");
        Boolean isMax = (Boolean) aggOp.getAnnotations().get("plaque-isMax");
        LogicalVariable filteredVar = (LogicalVariable) aggOp.getAnnotations().get("plaque-filteredVar");

        if (handle == null || isMax == null || filteredVar == null) {
            return false;
        }

        // Cross-expression: use probe variable for page filter, not the expression variable
        if (Boolean.TRUE.equals(aggOp.getAnnotations().get("plaque-cross-expr"))) {
            LogicalVariable probeVar =
                    (LogicalVariable) aggOp.getAnnotations().get("plaque-crossexpr-probe-var");
            String buildHandle = (String) aggOp.getAnnotations().get("plaque-crossexpr-build-handle");
            Object combineOpObj = aggOp.getAnnotations().get("plaque-crossexpr-combine-op");
            Object filterDirObj = aggOp.getAnnotations().get("plaque-crossexpr-filter-direction");
            if (probeVar == null || buildHandle == null || combineOpObj == null || filterDirObj == null) {
                return false;
            }
            return attachCrossExprPageFilter(aggOp, aggOp.getInputs().get(0), handle, isMax,
                    probeVar, buildHandle, ((Enum<?>) combineOpObj).name(), ((Enum<?>) filterDirObj).name());
        }

        return attachPageFilter(aggOp, aggOp.getInputs().get(0), handle, isMax, filteredVar);
    }

    private boolean handleThetaJoin(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
            throws AlgebricksException {
        AbstractLogicalOperator joinOp = (AbstractLogicalOperator) opRef.getValue();
        if (!Boolean.TRUE.equals(joinOp.getAnnotations().get("plaque-theta-join"))) {
            return false;
        }
        if (Boolean.TRUE.equals(joinOp.getAnnotations().get("plaque-page-filter-set"))) {
            return false;
        }

        String handle = (String) joinOp.getAnnotations().get("plaque-handle");
        Boolean isMax = (Boolean) joinOp.getAnnotations().get("plaque-isMax");
        LogicalVariable filteredVar = (LogicalVariable) joinOp.getAnnotations().get("plaque-filteredVar");

        if (handle == null || isMax == null || filteredVar == null) {
            return false;
        }

        // Walk the outer side (input 0) to find field name and DataSourceScan
        return attachPageFilter(joinOp, joinOp.getInputs().get(0), handle, isMax, filteredVar);
    }

    private boolean handleTopKSort(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
            throws AlgebricksException {
        AbstractLogicalOperator orderOp = (AbstractLogicalOperator) opRef.getValue();

        // PushLimitIntoOrderByRule creates a new OrderOperator, losing annotations.
        // Instead, walk down from ORDER to find a join with plaque-crossexpr-done
        // and a plaque-topk- prefixed handle.
        OrderOperator order = (OrderOperator) orderOp;
        if (order.getTopK() <= 0) {
            return false;
        }
        if (Boolean.TRUE.equals(orderOp.getAnnotations().get("plaque-topk-page-filter-set"))) {
            return false;
        }

        // Walk down to find the annotated join
        ILogicalOperator current = orderOp.getInputs().get(0).getValue();
        AbstractLogicalOperator joinOp = null;
        while (current != null) {
            if (current.getOperatorTag() == LogicalOperatorTag.INNERJOIN) {
                AbstractLogicalOperator candidate = (AbstractLogicalOperator) current;
                if (Boolean.TRUE.equals(candidate.getAnnotations().get("plaque-cross-expr"))) {
                    String th = (String) candidate.getAnnotations().get("plaque-crossexpr-threshold-handle");
                    if (th != null && th.startsWith("plaque-topk-")) {
                        joinOp = candidate;
                    }
                }
                break;
            }
            if (current.getInputs().isEmpty()) break;
            current = current.getInputs().get(0).getValue();
        }
        if (joinOp == null) {
            return false;
        }

        // Read cross-expr metadata from the join annotations
        String handle = (String) joinOp.getAnnotations().get("plaque-crossexpr-threshold-handle");
        String buildHandle = (String) joinOp.getAnnotations().get("plaque-crossexpr-build-handle");
        LogicalVariable probeVar =
                (LogicalVariable) joinOp.getAnnotations().get("plaque-crossexpr-probe-var");
        Object combineOpObj = joinOp.getAnnotations().get("plaque-crossexpr-combine-op");
        Object filterDirObj = joinOp.getAnnotations().get("plaque-crossexpr-filter-direction");

        if (handle == null || buildHandle == null || probeVar == null
                || combineOpObj == null || filterDirObj == null) {
            return false;
        }

        boolean isMax = true; // DESC Top-K = MAX direction

        boolean result = attachCrossExprPageFilter(orderOp, orderOp.getInputs().get(0), handle, isMax,
                probeVar, buildHandle, ((Enum<?>) combineOpObj).name(), ((Enum<?>) filterDirObj).name());
        if (result) {
            orderOp.getAnnotations().put("plaque-topk-page-filter-set", true);
        }
        return result;
    }

    private boolean attachCrossExprPageFilter(AbstractLogicalOperator annotatedOp, Mutable<ILogicalOperator> walkRef,
            String thresholdHandle, boolean isMax, LogicalVariable probeVar,
            String buildHandle, String combineOp, String filterDirection) throws AlgebricksException {
        // For cross-expr, the probeVar is defined in an ASSIGN above the join (e.g., $$68 from l.getField(5))
        // We need to find the field name for this variable from the Lineitem scan
        String fieldName = findFieldName(walkRef, probeVar);
        System.out.println("PLAQUE_CROSSEXPR_PAGE_FILTER: thresholdHandle=" + thresholdHandle
                + " buildHandle=" + buildHandle + " probeVar=" + probeVar + " fieldName=" + fieldName);
        if (fieldName == null) {
            return false;
        }

        // Find the probe-side DataSourceScan (walk through join's input 0)
        DataSourceScanOperator scanOp = findProbeDataSourceScan(walkRef);
        System.out.println("PLAQUE_CROSSEXPR_PAGE_FILTER: scanOp=" + (scanOp != null ? scanOp.getDataSource() : "null"));
        if (scanOp == null) {
            return false;
        }

        IProjectionFiltrationInfo info = scanOp.getProjectionFiltrationInfo();
        if (!(info instanceof ColumnDatasetProjectionFiltrationInfo)) {
            return false;
        }

        ARecordType columnPath = new ARecordType("plaque_crossexpr_path",
                new String[] { fieldName },
                new IAType[] { BuiltinType.ANY }, false);

        ColumnDatasetProjectionFiltrationInfo columnInfo = (ColumnDatasetProjectionFiltrationInfo) info;
        columnInfo.setPlaqueInfo(thresholdHandle, isMax, columnPath);
        columnInfo.setPlaqueCrossExprInfo(buildHandle, combineOp, filterDirection);
        System.out.println("PLAQUE_CROSSEXPR_PAGE_FILTER: SUCCESS — set on scan for field=" + fieldName);

        annotatedOp.getAnnotations().put("plaque-page-filter-set", true);
        return true;
    }

    /**
     * Find the probe-side DataSourceScan by walking through the plan including through joins.
     * For cross-expr, the probe scan is below the join (input 0 of the join).
     */
    private DataSourceScanOperator findProbeDataSourceScan(Mutable<ILogicalOperator> opRef) {
        ILogicalOperator current = opRef.getValue();
        while (current != null) {
            if (current.getOperatorTag() == LogicalOperatorTag.DATASOURCESCAN) {
                return (DataSourceScanOperator) current;
            }
            if (current.getOperatorTag() == LogicalOperatorTag.INNERJOIN
                    || current.getOperatorTag() == LogicalOperatorTag.LEFTOUTERJOIN) {
                // For cross-expr, the probe side is input 0 of the join
                current = current.getInputs().get(0).getValue();
                continue;
            }
            if (current.getInputs().isEmpty()) {
                return null;
            }
            current = current.getInputs().get(0).getValue();
        }
        return null;
    }

    private boolean attachPageFilter(AbstractLogicalOperator annotatedOp, Mutable<ILogicalOperator> walkRef,
            String handle, boolean isMax, LogicalVariable filteredVar) throws AlgebricksException {
        String fieldName = findFieldName(walkRef, filteredVar);
        System.out.println("PLAQUE_PAGE_FILTER: handle=" + handle + " isMax=" + isMax
                + " filteredVar=" + filteredVar + " fieldName=" + fieldName);
        if (fieldName == null) {
            return false;
        }

        DataSourceScanOperator scanOp = findDataSourceScan(walkRef);
        System.out.println("PLAQUE_PAGE_FILTER: scanOp=" + (scanOp != null ? scanOp.getDataSource() : "null"));
        if (scanOp == null) {
            return false;
        }

        IProjectionFiltrationInfo info = scanOp.getProjectionFiltrationInfo();
        if (!(info instanceof ColumnDatasetProjectionFiltrationInfo)) {
            return false;
        }

        ARecordType columnPath = new ARecordType("plaque_path",
                new String[] { fieldName },
                new IAType[] { BuiltinType.ANY }, false);

        ColumnDatasetProjectionFiltrationInfo columnInfo = (ColumnDatasetProjectionFiltrationInfo) info;
        columnInfo.setPlaqueInfo(handle, isMax, columnPath);
        System.out.println("PLAQUE_PAGE_FILTER: SUCCESS — set page filter on scan for field=" + fieldName
                + " handle=" + handle);

        annotatedOp.getAnnotations().put("plaque-page-filter-set", true);
        return true;
    }

    /**
     * Walks down the plan to find the ASSIGN that defines filteredVar,
     * extracts the field name from the field-access-by-index expression.
     */
    private String findFieldName(Mutable<ILogicalOperator> opRef, LogicalVariable filteredVar)
            throws AlgebricksException {
        ILogicalOperator current = opRef.getValue();
        while (current != null) {
            System.out.println("PLAQUE_PAGE_FILTER findFieldName: visiting " + current.getOperatorTag());
            if (current.getOperatorTag() == LogicalOperatorTag.ASSIGN) {
                AssignOperator assignOp = (AssignOperator) current;
                int idx = assignOp.getVariables().indexOf(filteredVar);
                System.out.println("PLAQUE_PAGE_FILTER findFieldName: ASSIGN vars=" + assignOp.getVariables()
                        + " looking for " + filteredVar + " idx=" + idx);
                if (idx >= 0) {
                    ILogicalExpression expr = assignOp.getExpressions().get(idx).getValue();
                    System.out.println("PLAQUE_PAGE_FILTER findFieldName: expr=" + expr + " class=" + expr.getClass().getSimpleName());
                    return extractFieldName(expr, current);
                }
            }
            if (current.getInputs().isEmpty()) {
                System.out.println("PLAQUE_PAGE_FILTER findFieldName: no more inputs, returning null");
                return null;
            }
            if (current.getOperatorTag() == LogicalOperatorTag.INNERJOIN
                    || current.getOperatorTag() == LogicalOperatorTag.LEFTOUTERJOIN) {
                current = current.getInputs().get(0).getValue();
            } else {
                current = current.getInputs().get(0).getValue();
            }
        }
        return null;
    }

    /**
     * Extracts the field name from a field-access-by-index or field-access-by-name expression.
     * For field-access-by-index: the second argument is the integer index; we resolve the name
     * from the record type of the first argument.
     */
    private String extractFieldName(ILogicalExpression expr, ILogicalOperator assignOp) throws AlgebricksException {
        if (!(expr instanceof AbstractFunctionCallExpression)) {
            System.out.println("PLAQUE_PAGE_FILTER extractFieldName: not a function call, class=" + expr.getClass().getSimpleName());
            return null;
        }
        AbstractFunctionCallExpression funcExpr = (AbstractFunctionCallExpression) expr;
        System.out.println("PLAQUE_PAGE_FILTER extractFieldName: funcId=" + funcExpr.getFunctionIdentifier());

        if (funcExpr.getFunctionIdentifier().equals(BuiltinFunctions.FIELD_ACCESS_BY_INDEX)) {
            ILogicalExpression indexExpr = funcExpr.getArguments().get(1).getValue();
            System.out.println("PLAQUE_PAGE_FILTER extractFieldName: indexExpr class=" + indexExpr.getClass().getSimpleName() + " val=" + indexExpr);
            if (indexExpr instanceof ConstantExpression) {
                try {
                    org.apache.asterix.om.constants.AsterixConstantValue cv =
                            (org.apache.asterix.om.constants.AsterixConstantValue)
                                    ((ConstantExpression) indexExpr).getValue();
                    int fieldIndex = ((org.apache.asterix.om.base.AInt32) cv.getObject()).getIntegerValue();
                    System.out.println("PLAQUE_PAGE_FILTER extractFieldName: fieldIndex=" + fieldIndex);
                    // Walk down to find the DataSourceScan to get the record type
                    DataSourceScanOperator scanOp = assignOp.getInputs().isEmpty() ? null
                            : findDataSourceScan(assignOp.getInputs().get(0));
                    System.out.println("PLAQUE_PAGE_FILTER extractFieldName: scanOp from assign=" + (scanOp != null));
                    if (scanOp != null) {
                        org.apache.asterix.metadata.declared.DataSource ds =
                                (org.apache.asterix.metadata.declared.DataSource) scanOp.getDataSource();
                        IAType itemType = ds.getItemType();
                        System.out.println("PLAQUE_PAGE_FILTER extractFieldName: itemType=" + itemType);
                        if (itemType instanceof ARecordType) {
                            ARecordType recordType = (ARecordType) itemType;
                            String[] fieldNames = recordType.getFieldNames();
                            System.out.println("PLAQUE_PAGE_FILTER extractFieldName: fieldNames=" + java.util.Arrays.toString(fieldNames));
                            if (fieldIndex >= 0 && fieldIndex < fieldNames.length) {
                                System.out.println("PLAQUE_PAGE_FILTER extractFieldName: RESOLVED=" + fieldNames[fieldIndex]);
                                return fieldNames[fieldIndex];
                            }
                        }
                    }
                } catch (Exception e) {
                    System.out.println("PLAQUE_PAGE_FILTER extractFieldName: EXCEPTION=" + e);
                }
            }
        }

        // Could also handle FIELD_ACCESS_BY_NAME here if needed
        return null;
    }

    private DataSourceScanOperator findDataSourceScan(Mutable<ILogicalOperator> opRef) {
        ILogicalOperator current = opRef.getValue();
        while (current != null) {
            if (current.getOperatorTag() == LogicalOperatorTag.DATASOURCESCAN) {
                return (DataSourceScanOperator) current;
            }
            if (current.getOperatorTag() == LogicalOperatorTag.INNERJOIN
                    || current.getOperatorTag() == LogicalOperatorTag.LEFTOUTERJOIN) {
                current = current.getInputs().get(0).getValue();
                continue;
            }
            if (current.getInputs().isEmpty()) {
                return null;
            }
            current = current.getInputs().get(0).getValue();
        }
        return null;
    }
}
