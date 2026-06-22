package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.rag.model.DocumentTableQueryPlan;
import org.javaup.ai.manage.model.table.DocumentTableDescriptor;
import org.javaup.ai.manage.model.table.DocumentTableQuery;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class DocumentTableQueryPlanner {

    private static final List<String> TABLE_INTENT_TERMS = List.of(
        "表", "表格", "数据", "统计", "总数", "数量", "个数", "多少", "合计", "总和", "求和",
        "汇总", "平均", "最大", "最高", "最小", "最低", "分组", "按", "每个", "各"
    );
    private static final List<String> GROUP_TERMS = List.of("按", "每个", "各", "分组", "分别", "分类");
    private static final List<String> SUM_TERMS = List.of("合计", "总和", "求和", "总额", "累计", "汇总");
    private static final List<String> MAX_TERMS = List.of("最大", "最高", "最多", "峰值");
    private static final List<String> MIN_TERMS = List.of("最小", "最低", "最少");
    private static final List<String> COUNT_TERMS = List.of("数量", "个数", "多少", "几条", "多少条", "总数", "记录数");
    private static final List<String> FILTER_CONNECTORS = List.of("为", "是", "等于", "包含", "大于等于", "不小于", "小于等于", "不大于", "大于", "超过", "小于", "低于");

    public Optional<DocumentTableQueryPlan> plan(String question, List<DocumentTableDescriptor> tables) {
        if (StrUtil.isBlank(question) || tables == null || tables.isEmpty() || !looksLikeTableQuestion(question)) {
            return Optional.empty();
        }
        List<ScoredPlan> candidates = new ArrayList<>();
        for (DocumentTableDescriptor table : tables) {
            Optional<DocumentTableQueryPlan> plan = planForTable(question, table);
            plan.ifPresent(value -> candidates.add(new ScoredPlan(value, scorePlan(question, table, value.getQuery()))));
        }
        return candidates.stream()
            .filter(candidate -> candidate.score() > 0)
            .max(Comparator.comparingInt(ScoredPlan::score))
            .map(ScoredPlan::plan);
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

    private record ColumnScore(DocumentTableDescriptor.Column column, int score) {
    }

    private record ScoredPlan(DocumentTableQueryPlan plan, int score) {
    }
}
