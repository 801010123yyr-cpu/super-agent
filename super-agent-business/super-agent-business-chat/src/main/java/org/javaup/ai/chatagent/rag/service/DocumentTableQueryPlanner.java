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
    private static final List<String> TABLE_INTENT_TERMS = List.of(
        "表", "表格", "数据", "统计", "总数", "数量", "个数", "多少", "合计", "总和", "求和",
        "汇总", "平均", "最大", "最高", "最小", "最低", "分组", "按", "每个", "各"
    );
    private static final List<String> ADVISOR_CUE_TERMS = List.of(
        "筛选", "过滤", "条件", "等于", "包含", "大于", "超过", "小于", "低于",
        "不小于", "不大于", "分别", "分类", "每个", "各", "按"
    );
    private static final List<String> GROUP_TERMS = List.of("按", "每个", "各", "分组", "分别", "分类");
    private static final List<String> SUM_TERMS = List.of("合计", "总和", "求和", "总额", "累计", "汇总");
    private static final List<String> MAX_TERMS = List.of("最大", "最高", "最多", "峰值");
    private static final List<String> MIN_TERMS = List.of("最小", "最低", "最少");
    private static final List<String> COUNT_TERMS = List.of("数量", "个数", "多少", "几条", "多少条", "总数", "记录数");
    private static final List<String> FILTER_CONNECTORS = List.of("为", "是", "等于", "包含", "大于等于", "不小于", "小于等于", "不大于", "大于", "超过", "小于", "低于");

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
        boolean tableQuestion = looksLikeTableQuestion(question);
        boolean advisorCuedTableSignal = containsAny(normalize(question), ADVISOR_CUE_TERMS)
            && mentionsKnownTableSignal(question, tables);
        if (!tableQuestion && !advisorCuedTableSignal) {
            return Optional.empty();
        }
        List<ScoredPlan> candidates = buildRuleCandidates(question, tables);
        if (shouldAskAdvisor(question, tables, candidates)) {
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
            Optional<ScoredPlan> advisorCandidate = buildAdvisorCandidate(question, tables, advice.get());
            if (advisorCandidate.isEmpty()) {
                return Optional.empty();
            }
            addOrReplaceCandidate(candidates, advisorCandidate.get());
        }
        return candidates.stream()
            .filter(candidate -> candidate.score() > 0)
            .max(Comparator.comparingInt(ScoredPlan::score))
            .map(ScoredPlan::plan);
    }

    private List<ScoredPlan> buildRuleCandidates(String question, List<DocumentTableDescriptor> tables) {
        List<ScoredPlan> candidates = new ArrayList<>();
        for (DocumentTableDescriptor table : tables) {
            Optional<DocumentTableQueryPlan> plan = planForTable(question, table);
            plan.ifPresent(value -> candidates.add(new ScoredPlan(value, scorePlan(question, table, value.getQuery()))));
        }
        return candidates;
    }

    private boolean shouldAskAdvisor(String question,
                                     List<DocumentTableDescriptor> tables,
                                     List<ScoredPlan> ruleCandidates) {
        if (planAdvisor == null) {
            return false;
        }
        if (ruleCandidates.isEmpty()) {
            return true;
        }
        String normalized = normalize(question);
        boolean hasAdvisorCue = containsAny(normalized, ADVISOR_CUE_TERMS);
        boolean hasMultipleTables = tables.stream().filter(table -> table != null && table.getTableId() != null).count() > 1;
        boolean hasFilterCueWithoutRuleFilter = hasAdvisorCue && ruleCandidates.stream()
            .map(ScoredPlan::plan)
            .map(DocumentTableQueryPlan::getQuery)
            .noneMatch(query -> query.getFilters() != null && !query.getFilters().isEmpty());
        return hasFilterCueWithoutRuleFilter || (hasMultipleTables && hasAdvisorCue);
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
        DocumentTableQuery.Operation operation = resolveOperation(question);
        Optional<DocumentTableDescriptor.Column> metricColumn = resolveMetricColumn(question, table);
        Optional<DocumentTableDescriptor.Column> groupColumn = resolveGroupColumn(question, table, metricColumn.orElse(null));
        List<DocumentTableQuery.Filter> filters = resolveFilters(question, table);

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

    private boolean looksLikeTableQuestion(String question) {
        String normalized = normalize(question);
        return TABLE_INTENT_TERMS.stream().anyMatch(normalized::contains);
    }

    private boolean mentionsKnownTableSignal(String question, List<DocumentTableDescriptor> tables) {
        return tables.stream()
            .filter(table -> table != null)
            .anyMatch(table -> textMentionScore(question, table.getTitle()) > 0
                || textMentionScore(question, table.getSectionPath()) > 0
                || (table.getColumns() != null && table.getColumns().stream()
                .anyMatch(column -> columnMentionScore(question, column) > 0)));
    }

    private DocumentTableQuery.Operation resolveOperation(String question) {
        String normalized = normalize(question);
        if (containsAny(normalized, MAX_TERMS)) {
            return DocumentTableQuery.Operation.MAX;
        }
        if (containsAny(normalized, MIN_TERMS)) {
            return DocumentTableQuery.Operation.MIN;
        }
        if (containsAny(normalized, SUM_TERMS)) {
            return DocumentTableQuery.Operation.SUM;
        }
        return DocumentTableQuery.Operation.COUNT;
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
        String normalized = normalize(question);
        boolean hasGroupIntent = containsAny(normalized, GROUP_TERMS);
        if (!hasGroupIntent) {
            return Optional.empty();
        }
        return table.getColumns().stream()
            .filter(column -> metricColumn == null || !StrUtil.equals(column.getColumnName(), metricColumn.getColumnName()))
            .map(column -> new ColumnScore(column, columnMentionScore(question, column)))
            .filter(item -> item.score() > 0)
            .max(Comparator.comparingInt(ColumnScore::score))
            .map(ColumnScore::column)
            .or(() -> table.getColumns().stream()
                .filter(column -> !"NUMBER".equalsIgnoreCase(StrUtil.blankToDefault(column.getValueType(), "")))
                .findFirst());
    }

    private List<DocumentTableQuery.Filter> resolveFilters(String question, DocumentTableDescriptor table) {
        Map<String, DocumentTableQuery.Filter> filters = new LinkedHashMap<>();
        for (DocumentTableDescriptor.Column column : table.getColumns()) {
            String columnName = StrUtil.blankToDefault(column.getColumnName(), "");
            if (StrUtil.isBlank(columnName)) {
                continue;
            }
            for (String connector : FILTER_CONNECTORS) {
                String rawPattern = columnName + connector;
                int index = question.indexOf(rawPattern);
                if (index < 0) {
                    continue;
                }
                String value = readFilterValue(question.substring(index + rawPattern.length()));
                if (StrUtil.isBlank(value)) {
                    continue;
                }
                DocumentTableQuery.Operator operator = resolveOperator(connector);
                filters.put(columnName, DocumentTableQuery.Filter.builder()
                    .column(columnName)
                    .operator(operator)
                    .value(value)
                    .build());
                break;
            }
        }
        return new ArrayList<>(filters.values());
    }

    private String readFilterValue(String remaining) {
        String value = StrUtil.blankToDefault(remaining, "")
            .replaceFirst("^[\\s：:，,。；;为是]+", "");
        if (value.isBlank()) {
            return "";
        }
        int end = value.length();
        for (String separator : List.of("，", ",", "。", "；", ";", "且", "并且", "的", "中")) {
            int index = value.indexOf(separator);
            if (index > 0) {
                end = Math.min(end, index);
            }
        }
        return value.substring(0, end).trim();
    }

    private DocumentTableQuery.Operator resolveOperator(String connector) {
        return switch (connector) {
            case "包含" -> DocumentTableQuery.Operator.CONTAINS;
            case "大于", "超过" -> DocumentTableQuery.Operator.GT;
            case "大于等于", "不小于" -> DocumentTableQuery.Operator.GTE;
            case "小于", "低于" -> DocumentTableQuery.Operator.LT;
            case "小于等于", "不大于" -> DocumentTableQuery.Operator.LTE;
            default -> DocumentTableQuery.Operator.EQ;
        };
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
