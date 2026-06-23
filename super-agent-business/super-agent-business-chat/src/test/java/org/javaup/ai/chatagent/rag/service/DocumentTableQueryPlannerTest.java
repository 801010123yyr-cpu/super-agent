package org.javaup.ai.chatagent.rag.service;

import org.javaup.ai.chatagent.rag.model.DocumentTableQueryPlan;
import org.javaup.ai.chatagent.rag.model.DocumentTableQueryPlanAdvice;
import org.javaup.ai.manage.model.table.DocumentTableDescriptor;
import org.javaup.ai.manage.model.table.DocumentTableQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTableQueryPlannerTest {

    private final DocumentTableQueryPlanner planner = new DocumentTableQueryPlanner();

    @Test
    void plansGroupSumForExplicitTableQuestion() {
        DocumentTableDescriptor table = table();

        Optional<DocumentTableQueryPlan> plan = planner.plan("按部门统计报销金额合计是多少", List.of(table));

        assertThat(plan).isPresent();
        DocumentTableQuery query = plan.get().getQuery();
        assertThat(query.getTableId()).isEqualTo(100L);
        assertThat(query.getOperation()).isEqualTo(DocumentTableQuery.Operation.GROUP_SUM);
        assertThat(query.getMetricColumn()).isEqualTo("报销金额");
        assertThat(query.getGroupByColumn()).isEqualTo("部门");
    }

    @Test
    void ignoresNonTableQuestion() {
        Optional<DocumentTableQueryPlan> plan = planner.plan("请解释一下报销流程", List.of(table()));

        assertThat(plan).isEmpty();
    }

    @Test
    void ignoresColumnMentionWithoutTableOperationCue() {
        Optional<DocumentTableQueryPlan> plan = planner.plan("报销金额这个字段是什么意思", List.of(table()));

        assertThat(plan).isEmpty();
    }

    @Test
    void plansCountWithTextFilter() {
        Optional<DocumentTableQueryPlan> plan = planner.plan("部门为研发部的数量是多少", List.of(table()));

        assertThat(plan).isPresent();
        DocumentTableQuery query = plan.get().getQuery();
        assertThat(query.getOperation()).isEqualTo(DocumentTableQuery.Operation.COUNT);
        assertThat(query.getFilters()).hasSize(1);
        assertThat(query.getFilters().get(0).getColumn()).isEqualTo("部门");
        assertThat(query.getFilters().get(0).getOperator()).isEqualTo(DocumentTableQuery.Operator.EQ);
        assertThat(query.getFilters().get(0).getValue()).isEqualTo("研发部");
    }

    @Test
    void acceptsAdvisorControlledJsonWhenRuleCannotReadFilter() {
        DocumentTableQueryPlanner advisorPlanner = new DocumentTableQueryPlanner((question, tables) -> Optional.of(
            DocumentTableQueryPlanAdvice.builder()
                .queryTable(true)
                .tableId(100L)
                .operation("GROUP_SUM")
                .metricColumn("报销金额")
                .groupByColumn("部门")
                .confidence(0.91D)
                .reason("用户要求统计金额，并限定月份。")
                .filters(List.of(DocumentTableQueryPlanAdvice.FilterAdvice.builder()
                    .column("月份")
                    .operator("EQ")
                    .value("2026-01")
                    .build()))
                .build()
        ));

        Optional<DocumentTableQueryPlan> plan = advisorPlanner.plan("请筛选 2026-01 月，按部门分别统计报销金额", List.of(table()));

        assertThat(plan).isPresent();
        DocumentTableQuery query = plan.get().getQuery();
        assertThat(query.getOperation()).isEqualTo(DocumentTableQuery.Operation.GROUP_SUM);
        assertThat(query.getMetricColumn()).isEqualTo("报销金额");
        assertThat(query.getGroupByColumn()).isEqualTo("部门");
        assertThat(query.getFilters()).singleElement().satisfies(filter -> {
            assertThat(filter.getColumn()).isEqualTo("月份");
            assertThat(filter.getOperator()).isEqualTo(DocumentTableQuery.Operator.EQ);
            assertThat(filter.getValue()).isEqualTo("2026-01");
        });
        assertThat(plan.get().getReason()).contains("advisor");
    }

    @Test
    void rejectsAdvisorJsonWithUnknownTableOrColumn() {
        DocumentTableQueryPlanner advisorPlanner = new DocumentTableQueryPlanner((question, tables) -> Optional.of(
            DocumentTableQueryPlanAdvice.builder()
                .queryTable(true)
                .tableId(999L)
                .operation("SUM")
                .metricColumn("不存在列")
                .confidence(0.99D)
                .build()
        ));

        Optional<DocumentTableQueryPlan> plan = advisorPlanner.plan("筛选一月数据并求和", List.of(table()));

        assertThat(plan).isEmpty();
    }

    @Test
    void rejectsAdvisorJsonWhenNumericOperationUsesTextColumn() {
        DocumentTableQueryPlanner advisorPlanner = new DocumentTableQueryPlanner((question, tables) -> Optional.of(
            DocumentTableQueryPlanAdvice.builder()
                .queryTable(true)
                .tableId(100L)
                .operation("SUM")
                .metricColumn("部门")
                .confidence(0.99D)
                .build()
        ));

        Optional<DocumentTableQueryPlan> plan = advisorPlanner.plan("请按部门筛选并统计合计", List.of(table()));

        assertThat(plan).isEmpty();
    }

    @Test
    void rejectsPlanWhenAdvisorFailsForAdvisorRoutedQuestion() {
        DocumentTableQueryPlanner advisorPlanner = new DocumentTableQueryPlanner((question, tables) -> {
            throw new IllegalStateException("invalid json");
        });

        Optional<DocumentTableQueryPlan> plan = advisorPlanner.plan("请筛选研发部并统计报销金额合计", List.of(table()));

        assertThat(plan).isEmpty();
    }

    private DocumentTableDescriptor table() {
        return DocumentTableDescriptor.builder()
            .tableId(100L)
            .documentId(10L)
            .taskId(20L)
            .tableNo(1)
            .title("报销明细表")
            .sectionPath("财务制度 / 报销明细")
            .columns(List.of(
                column(1, "部门", "TEXT"),
                column(2, "报销金额", "NUMBER"),
                column(3, "月份", "TEXT")
            ))
            .build();
    }

    private DocumentTableDescriptor.Column column(int columnNo, String name, String valueType) {
        return DocumentTableDescriptor.Column.builder()
            .columnNo(columnNo)
            .columnName(name)
            .normalizedName(name)
            .valueType(valueType)
            .build();
    }
}
