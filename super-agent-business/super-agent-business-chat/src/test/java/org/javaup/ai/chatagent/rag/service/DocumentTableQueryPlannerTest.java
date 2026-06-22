package org.javaup.ai.chatagent.rag.service;

import org.javaup.ai.chatagent.rag.model.DocumentTableQueryPlan;
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
