package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.rag.model.DocumentTableQueryPlanAdvice;
import org.javaup.ai.chatagent.rag.model.DocumentTableQueryPlan;
import org.javaup.ai.manage.model.table.DocumentTableDescriptor;
import org.javaup.ai.manage.model.table.DocumentTableQuery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
@Slf4j
public class DocumentTableQueryPlanner {

    private static final double ADVISOR_CONFIDENCE_THRESHOLD = 0.72D;
    private static final int ADVISOR_FILTER_LIMIT = 6;

    private final DocumentTableQueryPlanAdvisor planAdvisor;

    public DocumentTableQueryPlanner() {
        this.planAdvisor = null;
    }

    @Autowired
    public DocumentTableQueryPlanner(ObjectProvider<DocumentTableQueryPlanAdvisor> planAdvisorProvider) {
        this(planAdvisorProvider == null ? null : planAdvisorProvider.getIfAvailable());
    }

    DocumentTableQueryPlanner(DocumentTableQueryPlanAdvisor planAdvisor) {
        this.planAdvisor = planAdvisor;
    }

    public Optional<DocumentTableQueryPlan> plan(String question, List<DocumentTableDescriptor> tables) {
        if (StrUtil.isBlank(question) || tables == null || tables.isEmpty()) {
            return Optional.empty();
        }
        Optional<ScoredPlan> advisorCandidate = askAdvisor(question, tables);
        if (advisorCandidate.isPresent()) {
            return Optional.of(advisorCandidate.get().plan());
        }
        if (planAdvisor != null) {
            return Optional.empty();
        }
        if (!mentionsKnownTableSignal(question, tables)) {
            return Optional.empty();
        }
        return buildRuleCandidates(question, tables).stream()
            .filter(candidate -> candidate.score() > 0)
            .max(Comparator.comparingInt(ScoredPlan::score))
            .map(ScoredPlan::plan);
    }

    private Optional<ScoredPlan> askAdvisor(String question, List<DocumentTableDescriptor> tables) {
        if (planAdvisor == null) {
            return Optional.empty();
        }
        Optional<DocumentTableQueryPlanAdvice> advice;
        try {
            advice = planAdvisor.advise(question, tables);
        }
        catch (RuntimeException exception) {
            log.warn("表格查询受控计划 advisor 失败，planner 拒绝本次表格计划: question='{}', message={}",
                question,
                exception.getMessage());
            return Optional.empty();
        }
        if (advice.isEmpty()) {
            return Optional.empty();
        }
        return buildAdvisorCandidate(question, tables, advice.get());
    }

    private List<ScoredPlan> buildRuleCandidates(String question, List<DocumentTableDescriptor> tables) {
        List<ScoredPlan> candidates = new ArrayList<>();
        for (DocumentTableDescriptor table : tables) {
            Optional<DocumentTableQueryPlan> plan = planForTable(question, table);
            plan.ifPresent(value -> candidates.add(new ScoredPlan(value, scorePlan(question, table, value.getQuery()))));
        }
        return candidates;
    }

    private Optional<ScoredPlan> buildAdvisorCandidate(String question,
                                                       List<DocumentTableDescriptor> tables,
                                                       DocumentTableQueryPlanAdvice advice) {
        return validateAdvice(question, tables, advice)
            .map(plan -> new ScoredPlan(plan, scoreAdvisorPlan(question, plan)));
    }

    private Optional<DocumentTableQueryPlan> validateAdvice(String question,
                                                           List<DocumentTableDescriptor> tables,
                                                           DocumentTableQueryPlanAdvice advice) {
        if (advice == null || !Boolean.TRUE.equals(advice.getQueryTable())) {
            return Optional.empty();
        }
        double confidence = normalizeConfidence(advice.getConfidence());
        if (confidence < ADVISOR_CONFIDENCE_THRESHOLD) {
            return Optional.empty();
        }
        DocumentTableDescriptor table = findTable(tables, advice.getTableId()).orElse(null);
        if (table == null || table.getColumns() == null || table.getColumns().isEmpty()) {
            return Optional.empty();
        }
        DocumentTableQuery.Operation operation = parseOperation(advice.getOperation()).orElse(null);
        if (operation == null) {
            return Optional.empty();
        }
        Optional<DocumentTableDescriptor.Column> metricColumn = matchColumn(table, advice.getMetricColumn());
        Optional<DocumentTableDescriptor.Column> groupColumn = matchColumn(table, advice.getGroupByColumn());

        if (operation == DocumentTableQuery.Operation.COUNT && groupColumn.isPresent()) {
            operation = DocumentTableQuery.Operation.GROUP_COUNT;
        }
        if (operation == DocumentTableQuery.Operation.SUM && groupColumn.isPresent()) {
            operation = DocumentTableQuery.Operation.GROUP_SUM;
        }
        if (!requiresMetricColumn(operation)) {
            metricColumn = Optional.empty();
        }
        if (requiresMetricColumn(operation) && (metricColumn.isEmpty() || !isNumberColumn(metricColumn.get()))) {
            return Optional.empty();
        }
        if (requiresGroupColumn(operation) && groupColumn.isEmpty()) {
            return Optional.empty();
        }
        if (!requiresGroupColumn(operation) && groupColumn.isPresent()) {
            return Optional.empty();
        }
        if (metricColumn.isPresent() && groupColumn.isPresent()
            && StrUtil.equals(metricColumn.get().getColumnName(), groupColumn.get().getColumnName())) {
            return Optional.empty();
        }

        Optional<List<DocumentTableQuery.Filter>> filters = validateFilters(table, advice.getFilters());
        if (filters.isEmpty()) {
            return Optional.empty();
        }

        DocumentTableQuery query = DocumentTableQuery.builder()
            .tableId(table.getTableId())
            .operation(operation)
            .metricColumn(metricColumn.map(DocumentTableDescriptor.Column::getColumnName).orElse(null))
            .groupByColumn(groupColumn.map(DocumentTableDescriptor.Column::getColumnName).orElse(null))
            .filters(filters.get())
            .build();
        return Optional.of(DocumentTableQueryPlan.builder()
            .table(table)
            .query(query)
            .reason(buildAdvisorReason(operation, metricColumn.orElse(null), groupColumn.orElse(null), filters.get(), confidence, advice.getReason()))
            .build());
    }

    private Optional<List<DocumentTableQuery.Filter>> validateFilters(DocumentTableDescriptor table,
                                                                     List<DocumentTableQueryPlanAdvice.FilterAdvice> filterAdviceList) {
        List<DocumentTableQueryPlanAdvice.FilterAdvice> rawFilters = filterAdviceList == null ? List.of() : filterAdviceList;
        if (rawFilters.size() > ADVISOR_FILTER_LIMIT) {
            return Optional.empty();
        }
        Map<String, DocumentTableQuery.Filter> filters = new LinkedHashMap<>();
        for (DocumentTableQueryPlanAdvice.FilterAdvice filterAdvice : rawFilters) {
            if (filterAdvice == null) {
                return Optional.empty();
            }
            DocumentTableDescriptor.Column column = matchColumn(table, filterAdvice.getColumn()).orElse(null);
            DocumentTableQuery.Operator operator = parseOperator(filterAdvice.getOperator()).orElse(null);
            String value = StrUtil.blankToDefault(filterAdvice.getValue(), "").trim();
            if (column == null || operator == null || StrUtil.isBlank(value)) {
                return Optional.empty();
            }
            if (value.length() > 120 || !operatorAllowedForColumn(operator, column)) {
                return Optional.empty();
            }
            filters.put(column.getColumnName(), DocumentTableQuery.Filter.builder()
                .column(column.getColumnName())
                .operator(operator)
                .value(value)
                .build());
        }
        return Optional.of(new ArrayList<>(filters.values()));
    }

    private boolean operatorAllowedForColumn(DocumentTableQuery.Operator operator,
                                             DocumentTableDescriptor.Column column) {
        if (operator == DocumentTableQuery.Operator.GT
            || operator == DocumentTableQuery.Operator.GTE
            || operator == DocumentTableQuery.Operator.LT
            || operator == DocumentTableQuery.Operator.LTE) {
            return isNumberColumn(column);
        }
        if (operator == DocumentTableQuery.Operator.CONTAINS) {
            return !isNumberColumn(column);
        }
        return true;
    }

    private Optional<DocumentTableDescriptor> findTable(List<DocumentTableDescriptor> tables, Long tableId) {
        if (tableId == null) {
            return Optional.empty();
        }
        return tables.stream()
            .filter(table -> table != null && StrUtil.equals(String.valueOf(tableId), String.valueOf(table.getTableId())))
            .findFirst();
    }

    private Optional<DocumentTableDescriptor.Column> matchColumn(DocumentTableDescriptor table, String columnName) {
        String normalized = normalize(columnName);
        if (normalized.isBlank() || table.getColumns() == null) {
            return Optional.empty();
        }
        return table.getColumns().stream()
            .filter(column -> normalized.equals(normalize(column.getColumnName()))
                || normalized.equals(normalize(column.getNormalizedName())))
            .findFirst();
    }

    private Optional<DocumentTableQuery.Operation> parseOperation(String operation) {
        String normalized = StrUtil.blankToDefault(operation, "")
            .trim()
            .replace('-', '_')
            .toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(DocumentTableQuery.Operation.valueOf(normalized));
        }
        catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private Optional<DocumentTableQuery.Operator> parseOperator(String operator) {
        String normalized = StrUtil.blankToDefault(operator, "")
            .trim()
            .replace('-', '_')
            .toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(DocumentTableQuery.Operator.valueOf(normalized));
        }
        catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private boolean isNumberColumn(DocumentTableDescriptor.Column column) {
        return column != null && "NUMBER".equalsIgnoreCase(StrUtil.blankToDefault(column.getValueType(), ""));
    }

    private int scoreAdvisorPlan(String question, DocumentTableQueryPlan plan) {
        return scorePlan(question, plan.getTable(), plan.getQuery()) + 10;
    }

    private void addOrReplaceCandidate(List<ScoredPlan> candidates, ScoredPlan advisorCandidate) {
        if (advisorCandidate == null || advisorCandidate.plan() == null || advisorCandidate.plan().getQuery() == null) {
            return;
        }
        DocumentTableQuery advisorQuery = advisorCandidate.plan().getQuery();
        for (int index = 0; index < candidates.size(); index++) {
            DocumentTableQuery currentQuery = candidates.get(index).plan().getQuery();
            if (sameQueryShape(currentQuery, advisorQuery)) {
                if (advisorCandidate.score() > candidates.get(index).score()) {
                    candidates.set(index, advisorCandidate);
                }
                return;
            }
        }
        candidates.add(advisorCandidate);
    }

    private boolean sameQueryShape(DocumentTableQuery left, DocumentTableQuery right) {
        if (left == null || right == null) {
            return false;
        }
        return StrUtil.equals(String.valueOf(left.getTableId()), String.valueOf(right.getTableId()))
            && left.getOperation() == right.getOperation()
            && StrUtil.equals(StrUtil.blankToDefault(left.getMetricColumn(), ""), StrUtil.blankToDefault(right.getMetricColumn(), ""))
            && StrUtil.equals(StrUtil.blankToDefault(left.getGroupByColumn(), ""), StrUtil.blankToDefault(right.getGroupByColumn(), ""));
    }

    private Optional<DocumentTableQueryPlan> planForTable(String question, DocumentTableDescriptor table) {
        if (table == null || table.getTableId() == null || table.getColumns() == null || table.getColumns().isEmpty()) {
            return Optional.empty();
        }
        if (textMentionScore(question, table.getTitle()) <= 0
            && textMentionScore(question, table.getSectionPath()) <= 0
            && table.getColumns().stream().noneMatch(column -> columnMentionScore(question, column) > 0)) {
            return Optional.empty();
        }
        DocumentTableQuery.Operation operation = DocumentTableQuery.Operation.COUNT;
        Optional<DocumentTableDescriptor.Column> metricColumn = resolveMetricColumn(question, table);
        Optional<DocumentTableDescriptor.Column> groupColumn = resolveGroupColumn(question, table, metricColumn.orElse(null));
        List<DocumentTableQuery.Filter> filters = resolveFilters(question, table);

        String normalizedQuestion = normalize(question);
        boolean aggregateCue = containsAny(normalizedQuestion, List.of("合计", "总计", "求和", "总和", "sum"));
        if (aggregateCue && metricColumn.isPresent()) {
            operation = DocumentTableQuery.Operation.SUM;
        }
        if (groupColumn.isPresent() && aggregateCue && metricColumn.isPresent()) {
            operation = DocumentTableQuery.Operation.GROUP_SUM;
        }
        else if (groupColumn.isPresent() && containsAny(normalizedQuestion, List.of("数量", "个数", "多少", "统计"))) {
            operation = DocumentTableQuery.Operation.GROUP_COUNT;
        }

        if (operation == DocumentTableQuery.Operation.COUNT && groupColumn.isPresent()) {
            operation = DocumentTableQuery.Operation.GROUP_COUNT;
        }
        if (operation == DocumentTableQuery.Operation.SUM && groupColumn.isPresent()) {
            operation = DocumentTableQuery.Operation.GROUP_SUM;
        }
        if (!requiresMetricColumn(operation)) {
            metricColumn = Optional.empty();
        }

        if (requiresMetricColumn(operation) && metricColumn.isEmpty()) {
            return Optional.empty();
        }
        if (requiresGroupColumn(operation) && groupColumn.isEmpty()) {
            return Optional.empty();
        }

        DocumentTableQuery query = DocumentTableQuery.builder()
            .tableId(table.getTableId())
            .operation(operation)
            .metricColumn(metricColumn.map(DocumentTableDescriptor.Column::getColumnName).orElse(null))
            .groupByColumn(groupColumn.map(DocumentTableDescriptor.Column::getColumnName).orElse(null))
            .filters(filters)
            .build();
        return Optional.of(DocumentTableQueryPlan.builder()
            .table(table)
            .query(query)
            .reason(buildReason(operation, metricColumn.orElse(null), groupColumn.orElse(null), filters))
            .build());
    }

    private boolean mentionsKnownTableSignal(String question, List<DocumentTableDescriptor> tables) {
        String normalizedQuestion = normalize(question);
        boolean operationCue = containsAny(normalizedQuestion, List.of("合计", "总计", "求和", "总和", "统计", "数量", "个数", "多少", "最大", "最小", "sum", "count"));
        return operationCue && tables.stream()
            .filter(table -> table != null)
            .anyMatch(table -> textMentionScore(question, table.getTitle()) > 0
                || textMentionScore(question, table.getSectionPath()) > 0
                || (table.getColumns() != null && table.getColumns().stream()
                .anyMatch(column -> columnMentionScore(question, column) > 0)));
    }

    private Optional<DocumentTableDescriptor.Column> resolveMetricColumn(String question, DocumentTableDescriptor table) {
        return table.getColumns().stream()
            .filter(column -> "NUMBER".equalsIgnoreCase(StrUtil.blankToDefault(column.getValueType(), "")))
            .map(column -> new ColumnScore(column, columnMentionScore(question, column)))
            .filter(item -> item.score() > 0)
            .max(Comparator.comparingInt(ColumnScore::score))
            .map(ColumnScore::column)
            .or(() -> table.getColumns().stream()
                .filter(column -> "NUMBER".equalsIgnoreCase(StrUtil.blankToDefault(column.getValueType(), "")))
                .findFirst());
    }

    private Optional<DocumentTableDescriptor.Column> resolveGroupColumn(String question,
                                                                        DocumentTableDescriptor table,
                                                                        DocumentTableDescriptor.Column metricColumn) {
        String normalizedQuestion = normalize(question);
        if (!containsAny(normalizedQuestion, List.of("按", "分别", "分组", "各", "每"))) {
            return Optional.empty();
        }
        return table.getColumns().stream()
            .filter(column -> metricColumn == null || !StrUtil.equals(column.getColumnName(), metricColumn.getColumnName()))
            .filter(column -> !"NUMBER".equalsIgnoreCase(StrUtil.blankToDefault(column.getValueType(), "")))
            .map(column -> new ColumnScore(column, columnMentionScore(question, column)))
            .filter(item -> item.score() > 0)
            .max(Comparator.comparingInt(ColumnScore::score))
            .map(ColumnScore::column);
    }

    private List<DocumentTableQuery.Filter> resolveFilters(String question, DocumentTableDescriptor table) {
        String normalizedQuestion = normalize(question);
        List<DocumentTableQuery.Filter> filters = new ArrayList<>();
        for (DocumentTableDescriptor.Column column : table.getColumns()) {
            if (column == null || StrUtil.isBlank(column.getColumnName())) {
                continue;
            }
            String normalizedColumn = normalize(column.getColumnName());
            if (normalizedColumn.isBlank()) {
                continue;
            }
            for (String marker : List.of("为", "是", "等于")) {
                int start = normalizedQuestion.indexOf(normalizedColumn + marker);
                if (start < 0) {
                    continue;
                }
                int valueStart = start + normalizedColumn.length() + normalize(marker).length();
                String value = extractFilterValue(normalizedQuestion.substring(valueStart), table);
                if (StrUtil.isNotBlank(value)) {
                    filters.add(DocumentTableQuery.Filter.builder()
                        .column(column.getColumnName())
                        .operator(DocumentTableQuery.Operator.EQ)
                        .value(value)
                        .build());
                    break;
                }
            }
        }
        return filters;
    }

    private String extractFilterValue(String normalizedRemainder, DocumentTableDescriptor table) {
        if (StrUtil.isBlank(normalizedRemainder)) {
            return "";
        }
        String value = normalizedRemainder;
        for (DocumentTableDescriptor.Column column : table.getColumns()) {
            String normalizedColumn = normalize(column.getColumnName());
            if (StrUtil.isBlank(normalizedColumn)) {
                continue;
            }
            int index = value.indexOf(normalizedColumn);
            if (index > 0) {
                value = value.substring(0, index);
            }
        }
        for (String cue : List.of("的", "数量", "个数", "多少", "合计", "总计", "求和", "总和", "统计", "sum", "count")) {
            int index = value.indexOf(normalize(cue));
            if (index > 0) {
                value = value.substring(0, index);
            }
        }
        return value.trim();
    }

    private int scorePlan(String question, DocumentTableDescriptor table, DocumentTableQuery query) {
        int score = 0;
        score += textMentionScore(question, table.getTitle()) * 3;
        score += textMentionScore(question, table.getSectionPath()) * 2;
        for (DocumentTableDescriptor.Column column : table.getColumns()) {
            score += columnMentionScore(question, column);
        }
        if (query.getOperation() != null) {
            score += 2;
        }
        if (query.getMetricColumn() != null) {
            score += 2;
        }
        if (query.getGroupByColumn() != null) {
            score += 2;
        }
        if (query.getFilters() != null && !query.getFilters().isEmpty()) {
            score += query.getFilters().size() * 2;
        }
        return score;
    }

    private int textMentionScore(String question, String value) {
        String normalizedQuestion = normalize(question);
        String normalizedValue = normalize(value);
        if (normalizedValue.isBlank()) {
            return 0;
        }
        if (normalizedQuestion.contains(normalizedValue)) {
            return 3;
        }
        int score = 0;
        for (String segment : splitTerms(value)) {
            if (segment.length() >= 2 && normalizedQuestion.contains(normalize(segment))) {
                score++;
            }
        }
        return score;
    }

    private int columnMentionScore(String question, DocumentTableDescriptor.Column column) {
        if (column == null) {
            return 0;
        }
        int score = textMentionScore(question, column.getColumnName());
        String normalizedName = normalize(column.getNormalizedName());
        if (!normalizedName.isBlank() && normalize(question).contains(normalizedName)) {
            score += 2;
        }
        return score;
    }

    private List<String> splitTerms(String value) {
        if (StrUtil.isBlank(value)) {
            return List.of();
        }
        List<String> terms = new ArrayList<>();
        for (String segment : value.split("[\\s`*_\\-，,。；;：:（）()\\[\\]{}]+")) {
            if (StrUtil.isNotBlank(segment)) {
                terms.add(segment.trim());
            }
        }
        return terms;
    }

    private boolean requiresMetricColumn(DocumentTableQuery.Operation operation) {
        return operation == DocumentTableQuery.Operation.SUM
            || operation == DocumentTableQuery.Operation.MAX
            || operation == DocumentTableQuery.Operation.MIN
            || operation == DocumentTableQuery.Operation.GROUP_SUM;
    }

    private boolean requiresGroupColumn(DocumentTableQuery.Operation operation) {
        return operation == DocumentTableQuery.Operation.GROUP_COUNT
            || operation == DocumentTableQuery.Operation.GROUP_SUM;
    }

    private boolean containsAny(String normalized, List<String> terms) {
        return terms.stream().map(this::normalize).anyMatch(normalized::contains);
    }

    private double normalizeConfidence(Double confidence) {
        if (confidence == null) {
            return 0D;
        }
        if (confidence > 1D) {
            return Math.min(confidence / 100D, 1D);
        }
        return Math.max(confidence, 0D);
    }

    private String normalize(String value) {
        return StrUtil.blankToDefault(value, "")
            .replaceAll("[\\s`*_\\-，,。；;：:（）()“”\"'\\[\\]{}]+", "")
            .toLowerCase(Locale.ROOT);
    }

    private String buildReason(DocumentTableQuery.Operation operation,
                               DocumentTableDescriptor.Column metricColumn,
                               DocumentTableDescriptor.Column groupColumn,
                               List<DocumentTableQuery.Filter> filters) {
        List<String> parts = new ArrayList<>();
        parts.add("operation=" + operation.name());
        if (metricColumn != null) {
            parts.add("metric=" + metricColumn.getColumnName());
        }
        if (groupColumn != null) {
            parts.add("groupBy=" + groupColumn.getColumnName());
        }
        if (filters != null && !filters.isEmpty()) {
            parts.add("filters=" + filters.size());
        }
        return String.join(", ", parts);
    }

    private String buildAdvisorReason(DocumentTableQuery.Operation operation,
                                      DocumentTableDescriptor.Column metricColumn,
                                      DocumentTableDescriptor.Column groupColumn,
                                      List<DocumentTableQuery.Filter> filters,
                                      double confidence,
                                      String reason) {
        String baseReason = buildReason(operation, metricColumn, groupColumn, filters);
        List<String> parts = new ArrayList<>();
        parts.add("advisor");
        parts.add(baseReason);
        parts.add("confidence=" + String.format(Locale.ROOT, "%.2f", confidence));
        if (StrUtil.isNotBlank(reason)) {
            parts.add("reason=" + reason.trim());
        }
        return String.join(", ", parts);
    }

    private record ColumnScore(DocumentTableDescriptor.Column column, int score) {
    }

    private record ScoredPlan(DocumentTableQueryPlan plan, int score) {
    }
}
