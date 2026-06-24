package org.javaup.ai.manage.model.graph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GraphRagEvaluationBaselineSuitesTest {

    @Test
    void o6BaselineTemplatesCoverRealDocumentsAndEvaluationDimensions() {
        List<GraphRagEvaluationSuite> suites = GraphRagEvaluationBaselineSuites.o6LlmNerBaselineTemplates();

        assertThat(suites).hasSize(8);
        assertThat(suites).extracting(GraphRagEvaluationSuite::getSourceDocument)
            .contains(
                GraphRagEvaluationBaselineSuites.SOURCE_PRODUCTION_RELEASE,
                GraphRagEvaluationBaselineSuites.SOURCE_CUSTOMER_DATA_ACCESS
            );
        assertThat(suites).allSatisfy(suite -> {
            assertThat(suite.getSuiteId()).startsWith("o6-");
            assertThat(suite.getScenario()).contains("O6");
            assertThat(suite.getQuestion()).isNotBlank();
            assertThat(suite.getPassThreshold()).isBetween(0.8D, 1D);
            assertThat(suite.getTags()).contains("real-document", "weak-evidence");
            assertThat(suite.getExpectedEntities()).isNotEmpty();
            assertThat(suite.getExpectedRelations()).isNotEmpty();
            assertThat(suite.getExpectedEvidences()).isNotEmpty();
            assertThat(suite.getExpectedEvidences().get(0).getQuoteKeywords()).isNotEmpty();
        });
        assertThat(suites)
            .flatExtracting(GraphRagEvaluationSuite::getExpectedRelations)
            .extracting(GraphRagEvaluationSuite.ExpectedRelation::getRelationType)
            .contains("触发", "执行", "审批", "记录", "回收", "存放");
        assertThat(suites)
            .flatExtracting(GraphRagEvaluationSuite::getExpectedEntities)
            .flatExtracting(GraphRagEvaluationSuite.ExpectedEntity::getMustHaveAliases)
            .contains("SRE 团队", "DBA 团队", "高敏感信息");
        assertThat(suites)
            .extracting(GraphRagEvaluationSuite::getSuiteId)
            .contains(
                "o6-prod-release-dba-executes-database-script",
                "o6-customer-data-admin-revokes-abnormal-permission",
                "o6-customer-data-stores-in-vaultdocs",
                "o6-customer-data-stores-in-datacleanroom"
            );
    }

    @Test
    void o6BaselineCanBindDocumentAndTaskIdsForEvaluation() {
        List<GraphRagEvaluationSuite> suites = GraphRagEvaluationBaselineSuites.o6LlmNerBaseline(10L, 20L);

        assertThat(suites).allSatisfy(suite -> {
            assertThat(suite.getDocumentId()).isEqualTo(10L);
            assertThat(suite.getTaskId()).isEqualTo(20L);
        });
    }

    @Test
    void o6BaselineCanBindDifferentDocumentsBySource() {
        List<GraphRagEvaluationSuite> suites = GraphRagEvaluationBaselineSuites.o6LlmNerBaselineBySource(Map.of(
            GraphRagEvaluationBaselineSuites.SOURCE_PRODUCTION_RELEASE,
            GraphRagEvaluationSourceBinding.builder()
                .documentId(10L)
                .taskId(20L)
                .build(),
            GraphRagEvaluationBaselineSuites.SOURCE_CUSTOMER_DATA_ACCESS,
            GraphRagEvaluationSourceBinding.builder()
                .documentId(11L)
                .taskId(21L)
                .build()
        ));

        assertThat(suites)
            .filteredOn(suite -> GraphRagEvaluationBaselineSuites.SOURCE_PRODUCTION_RELEASE.equals(suite.getSourceDocument()))
            .allSatisfy(suite -> {
                assertThat(suite.getDocumentId()).isEqualTo(10L);
                assertThat(suite.getTaskId()).isEqualTo(20L);
            });
        assertThat(suites)
            .filteredOn(suite -> GraphRagEvaluationBaselineSuites.SOURCE_CUSTOMER_DATA_ACCESS.equals(suite.getSourceDocument()))
            .allSatisfy(suite -> {
                assertThat(suite.getDocumentId()).isEqualTo(11L);
                assertThat(suite.getTaskId()).isEqualTo(21L);
            });
    }
}
