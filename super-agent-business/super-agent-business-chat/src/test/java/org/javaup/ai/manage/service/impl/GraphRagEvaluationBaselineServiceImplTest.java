package org.javaup.ai.manage.service.impl;

import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.model.graph.GraphRagEvaluationBaselineSuites;
import org.javaup.ai.manage.model.graph.GraphRagEvaluationBatchReport;
import org.javaup.ai.manage.model.graph.GraphRagEvaluationReport;
import org.javaup.ai.manage.model.graph.GraphRagSearchResult;
import org.javaup.ai.manage.model.graph.GraphRagEvaluationSuite;
import org.javaup.ai.manage.service.GraphRagEvaluationService;
import org.javaup.ai.manage.service.GraphRagSearchService;
import org.javaup.enums.BusinessStatus;
import org.javaup.enums.DocumentIndexStatusEnum;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphRagEvaluationBaselineServiceImplTest {

    @Test
    void bindsO6BaselineSuitesToIndexedSampleDocuments() {
        CapturingEvaluationService evaluationService = new CapturingEvaluationService();
        GraphRagEvaluationBaselineServiceImpl service = new GraphRagEvaluationBaselineServiceImpl(
            mapper(List.of(
                document(9L, "旧生产规范", "生产环境发布与回滚操作规范.md", 19L, 1000L),
                document(10L, "生产规范", "生产环境发布与回滚操作规范.md", 20L, 2000L),
                document(11L, "客户数据分级与访问控制管理制度", "客户数据访问.md", 21L, 1500L),
                unindexedDocument(12L, "客户数据分级与访问控制管理制度.md", "客户数据分级与访问控制管理制度.md")
            )),
            evaluationService,
            new StubGraphRagSearchService(List.of())
        );

        GraphRagEvaluationBatchReport report = service.evaluateO6LlmNerBaseline();

        assertThat(report.getBatchId()).isEqualTo(GraphRagEvaluationBaselineSuites.O6_LLM_NER_BATCH_ID);
        assertThat(evaluationService.capturedSuites).hasSize(8);
        assertThat(evaluationService.capturedSuites)
            .filteredOn(suite -> GraphRagEvaluationBaselineSuites.SOURCE_PRODUCTION_RELEASE.equals(suite.getSourceDocument()))
            .hasSize(3)
            .allSatisfy(suite -> {
                assertThat(suite.getDocumentId()).isEqualTo(10L);
                assertThat(suite.getTaskId()).isEqualTo(20L);
            });
        assertThat(evaluationService.capturedSuites)
            .filteredOn(suite -> GraphRagEvaluationBaselineSuites.SOURCE_CUSTOMER_DATA_ACCESS.equals(suite.getSourceDocument()))
            .hasSize(5)
            .allSatisfy(suite -> {
                assertThat(suite.getDocumentId()).isEqualTo(11L);
                assertThat(suite.getTaskId()).isEqualTo(21L);
            });
    }

    @Test
    void missingSampleDocumentsStillProduceFailedSuitesInsteadOfSilentEmptyBatch() {
        CapturingEvaluationService evaluationService = new CapturingEvaluationService();
        GraphRagEvaluationBaselineServiceImpl service = new GraphRagEvaluationBaselineServiceImpl(
            mapper(List.of()),
            evaluationService,
            new StubGraphRagSearchService(List.of())
        );

        service.evaluateO6LlmNerBaseline();

        assertThat(evaluationService.capturedSuites).hasSize(8);
        assertThat(evaluationService.capturedSuites)
            .allSatisfy(suite -> {
                assertThat(suite.getDocumentId()).isNull();
                assertThat(suite.getTaskId()).isNull();
            });
    }

    @Test
    void crossDocumentBaselineUsesBothAuditDocumentsAndPassesWhenCanonicalEvidenceIsFound() {
        StubGraphRagSearchService searchService = new StubGraphRagSearchService(List.of(GraphRagSearchResult.builder()
            .documentId(30L)
            .taskId(40L)
            .entityId(1001L)
            .entityName("AuditTrail")
            .canonicalEntityName("AuditTrail")
            .canonicalEntityCount(2)
            .canonicalDocumentCount(2)
            .relationId(2001L)
            .relationType("RECORDS")
            .relatedEntityId(1002L)
            .relatedEntityName("权限申请")
            .evidenceId(3001L)
            .chunkId(4001L)
            .quoteText("AuditTrail 需记录权限申请、权限审批、权限回收和临时权限延长。")
            .graphPath("一跳：AuditTrail --RECORDS--> 权限申请")
            .communityTitle("跨文档图谱社区：AuditTrail / 权限申请")
            .communitySummary(structuredCommunityReport())
            .crossDocumentCommunityKey("xdoc-community:audittrail-permission")
            .crossDocumentCommunityEntityCount(4)
            .crossDocumentCommunityRelationGroupCount(2)
            .crossDocumentCommunityEvidenceCount(4)
            .crossDocumentCommunityDocumentCount(2)
            .score(3.0D)
            .build()));
        GraphRagEvaluationBaselineServiceImpl service = new GraphRagEvaluationBaselineServiceImpl(
            mapper(List.of(
                document(30L, "O6跨文档图谱-审计证据规范A.md", "O6跨文档图谱-审计证据规范A.md", 40L, 3000L),
                document(31L, "O6跨文档图谱-审计系统别名说明B.md", "O6跨文档图谱-审计系统别名说明B.md", 41L, 3001L)
            )),
            new CapturingEvaluationService(),
            searchService
        );

        GraphRagEvaluationBatchReport report = service.evaluateO6CrossDocumentBaseline();

        assertThat(report.getBatchId()).isEqualTo(GraphRagEvaluationBaselineSuites.O6_CROSS_DOCUMENT_BATCH_ID);
        assertThat(report.getPassedSuiteCount()).isEqualTo(1L);
        assertThat(report.getFailedSuiteCount()).isZero();
        assertThat(report.getOverallRecall()).isEqualTo(1D);
        assertThat(report.getExpectedEntityCount()).isEqualTo(1L);
        assertThat(report.getMatchedEntityCount()).isEqualTo(1L);
        assertThat(report.getExpectedRelationCount()).isEqualTo(1L);
        assertThat(report.getMatchedRelationCount()).isEqualTo(1L);
        assertThat(report.getEntityRecall()).isEqualTo(1D);
        assertThat(report.getRelationRecall()).isEqualTo(1D);
        assertThat(report.getRelationPrecision()).isEqualTo(1D);
        assertThat(report.getReports()).hasSize(1);
        GraphRagEvaluationReport crossDocumentReport = report.getReports().get(0);
        assertThat(crossDocumentReport.getEntityResults()).hasSize(1);
        assertThat(crossDocumentReport.getRelationResults()).hasSize(1);
        assertThat(crossDocumentReport.getEntityResults().get(0).getMatched()).isTrue();
        assertThat(crossDocumentReport.getRelationResults().get(0).getMatched()).isTrue();
        assertThat(searchService.capturedDocumentIds).containsExactly(30L, 31L);
        assertThat(searchService.capturedTaskIds).containsExactly(40L, 41L);
        assertThat(searchService.capturedQuestion).isEqualTo("审计系统有哪些权限相关要求？");
    }

    @Test
    void crossDocumentBaselineFailsWhenCommunityReportLacksStructuredBoundary() {
        StubGraphRagSearchService searchService = new StubGraphRagSearchService(List.of(GraphRagSearchResult.builder()
            .documentId(30L)
            .taskId(40L)
            .entityId(1001L)
            .entityName("AuditTrail")
            .canonicalEntityName("AuditTrail")
            .canonicalEntityCount(2)
            .canonicalDocumentCount(2)
            .relationId(2001L)
            .relationType("RECORDS")
            .relatedEntityId(1002L)
            .relatedEntityName("权限申请")
            .evidenceId(3001L)
            .chunkId(4001L)
            .quoteText("AuditTrail 需记录权限申请、权限审批、权限回收和临时权限延长。")
            .graphPath("一跳：AuditTrail --RECORDS--> 权限申请")
            .communityTitle("跨文档图谱社区：AuditTrail / 权限申请")
            .communitySummary("该社区覆盖审计系统和权限申请。")
            .crossDocumentCommunityKey("xdoc-community:audittrail-permission")
            .crossDocumentCommunityEntityCount(4)
            .crossDocumentCommunityRelationGroupCount(2)
            .crossDocumentCommunityEvidenceCount(4)
            .crossDocumentCommunityDocumentCount(2)
            .score(3.0D)
            .build()));
        GraphRagEvaluationBaselineServiceImpl service = new GraphRagEvaluationBaselineServiceImpl(
            mapper(List.of(
                document(30L, "O6跨文档图谱-审计证据规范A.md", "O6跨文档图谱-审计证据规范A.md", 40L, 3000L),
                document(31L, "O6跨文档图谱-审计系统别名说明B.md", "O6跨文档图谱-审计系统别名说明B.md", 41L, 3001L)
            )),
            new CapturingEvaluationService(),
            searchService
        );

        GraphRagEvaluationBatchReport report = service.evaluateO6CrossDocumentBaseline();

        assertThat(report.getPassedSuiteCount()).isZero();
        assertThat(report.getFailedSuiteCount()).isEqualTo(1L);
        assertThat(report.getOverallRecall()).isEqualTo(0.78D);
        assertThat(report.getFailedSuites().get(0).getReason())
            .contains("community report 质量未达标")
            .contains("report 缺少结构段");
    }

    @Test
    void crossDocumentBaselineRejectsGenericCanonicalPermissionEvenWhenQuoteMatches() {
        StubGraphRagSearchService searchService = new StubGraphRagSearchService(List.of(GraphRagSearchResult.builder()
            .documentId(30L)
            .taskId(40L)
            .entityId(1002L)
            .entityName("权限")
            .canonicalEntityName("权限")
            .canonicalEntityCount(2)
            .canonicalDocumentCount(2)
            .relationId(2001L)
            .relationType("RECORDS")
            .relatedEntityId(1001L)
            .relatedEntityName("AuditTrail")
            .evidenceId(3001L)
            .chunkId(4001L)
            .quoteText("AuditTrail 需记录权限申请、权限审批、权限回收和临时权限延长。")
            .graphPath("一跳：AuditTrail --RECORDS--> 权限")
            .score(3.0D)
            .build()));
        GraphRagEvaluationBaselineServiceImpl service = new GraphRagEvaluationBaselineServiceImpl(
            mapper(List.of(
                document(30L, "O6跨文档图谱-审计证据规范A.md", "O6跨文档图谱-审计证据规范A.md", 40L, 3000L),
                document(31L, "O6跨文档图谱-审计系统别名说明B.md", "O6跨文档图谱-审计系统别名说明B.md", 41L, 3001L)
            )),
            new CapturingEvaluationService(),
            searchService
        );

        GraphRagEvaluationBatchReport report = service.evaluateO6CrossDocumentBaseline();

        assertThat(report.getPassedSuiteCount()).isZero();
        assertThat(report.getFailedSuiteCount()).isEqualTo(1L);
        assertThat(report.getOverallRecall()).isZero();
        assertThat(report.getFailedSuites().get(0).getReason())
            .contains("未从“审计系统”别名扩展到 AuditTrail");
    }

    @Test
    void crossDocumentBaselineRejectsTechnicalLabelTargetEvenWhenQuoteMatches() {
        StubGraphRagSearchService searchService = new StubGraphRagSearchService(List.of(GraphRagSearchResult.builder()
            .documentId(30L)
            .taskId(40L)
            .entityId(1001L)
            .entityName("AuditTrail")
            .canonicalEntityName("AuditTrail")
            .canonicalEntityCount(2)
            .canonicalDocumentCount(2)
            .relationId(2001L)
            .relationType("RECORDS")
            .relatedEntityId(1002L)
            .relatedEntityName("CONTENT")
            .evidenceId(3001L)
            .chunkId(4001L)
            .quoteText("AuditTrail 需记录权限申请、权限审批、权限回收和临时权限延长。")
            .graphPath("一跳：AuditTrail --RECORDS--> CONTENT")
            .score(3.0D)
            .build()));
        GraphRagEvaluationBaselineServiceImpl service = new GraphRagEvaluationBaselineServiceImpl(
            mapper(List.of(
                document(30L, "O6跨文档图谱-审计证据规范A.md", "O6跨文档图谱-审计证据规范A.md", 40L, 3000L),
                document(31L, "O6跨文档图谱-审计系统别名说明B.md", "O6跨文档图谱-审计系统别名说明B.md", 41L, 3001L)
            )),
            new CapturingEvaluationService(),
            searchService
        );

        GraphRagEvaluationBatchReport report = service.evaluateO6CrossDocumentBaseline();

        assertThat(report.getPassedSuiteCount()).isZero();
        assertThat(report.getFailedSuiteCount()).isEqualTo(1L);
        assertThat(report.getOverallRecall()).isZero();
        assertThat(report.getFailedSuites().get(0).getReason())
            .contains("未从“审计系统”别名扩展到 AuditTrail");
    }

    @Test
    void crossDocumentBaselineFailsClearlyWhenAuditDocumentsAreMissing() {
        StubGraphRagSearchService searchService = new StubGraphRagSearchService(List.of());
        GraphRagEvaluationBaselineServiceImpl service = new GraphRagEvaluationBaselineServiceImpl(
            mapper(List.of()),
            new CapturingEvaluationService(),
            searchService
        );

        GraphRagEvaluationBatchReport report = service.evaluateO6CrossDocumentBaseline();

        assertThat(report.getBatchId()).isEqualTo(GraphRagEvaluationBaselineSuites.O6_CROSS_DOCUMENT_BATCH_ID);
        assertThat(report.getPassedSuiteCount()).isZero();
        assertThat(report.getFailedSuiteCount()).isEqualTo(1L);
        assertThat(report.getFailedSuites()).hasSize(1);
        assertThat(report.getFailedSuites().get(0).getReason()).contains("未找到已完成索引构建的跨文档 O6 样例");
        assertThat(searchService.searchCallCount).isZero();
    }

    private static SuperAgentDocument document(Long id,
                                               String documentName,
                                               String originalFileName,
                                               Long lastIndexTaskId,
                                               long editTime) {
        SuperAgentDocument document = new SuperAgentDocument();
        document.setId(id);
        document.setDocumentName(documentName);
        document.setOriginalFileName(originalFileName);
        document.setStatus(BusinessStatus.YES.getCode());
        document.setIndexStatus(DocumentIndexStatusEnum.BUILD_SUCCESS.getCode());
        document.setLastIndexTaskId(lastIndexTaskId);
        document.setEditTime(new Date(editTime));
        return document;
    }

    private static SuperAgentDocument unindexedDocument(Long id, String documentName, String originalFileName) {
        SuperAgentDocument document = document(id, documentName, originalFileName, null, 3000L);
        document.setIndexStatus(DocumentIndexStatusEnum.BUILD_FAILED.getCode());
        return document;
    }

    private static String structuredCommunityReport() {
        return """
            跨文档社区报告：
            - 核心实体：AuditTrail；权限申请
            - 关键关系类型：RECORDS
            - 覆盖范围：4 个 canonical 实体组、2 个关系组、4 条可追溯证据、2 份文档。
            - 证据边界：仅总结 KG evidence 支撑的实体和关系。
            - 不可推断：RECORDS 仅表示记录、留痕或审计覆盖，不等同于审批、负责或执行。
            """;
    }

    @SuppressWarnings("unchecked")
    private static SuperAgentDocumentMapper mapper(List<SuperAgentDocument> documents) {
        return (SuperAgentDocumentMapper) Proxy.newProxyInstance(
            SuperAgentDocumentMapper.class.getClassLoader(),
            new Class<?>[]{SuperAgentDocumentMapper.class},
            (proxy, method, args) -> {
                if ("selectList".equals(method.getName())) {
                    return documents;
                }
                if ("toString".equals(method.getName())) {
                    return "SuperAgentDocumentMapperProxy";
                }
                if ("hashCode".equals(method.getName())) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(method.getName())) {
                    return proxy == args[0];
                }
                return defaultValue(method.getReturnType());
            }
        );
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == void.class) {
            return null;
        }
        return 0;
    }

    private static class CapturingEvaluationService implements GraphRagEvaluationService {

        private List<GraphRagEvaluationSuite> capturedSuites = new ArrayList<>();

        @Override
        public GraphRagEvaluationReport evaluate(GraphRagEvaluationSuite suite) {
            return null;
        }

        @Override
        public GraphRagEvaluationBatchReport evaluateBatch(String batchId,
                                                           String name,
                                                           List<GraphRagEvaluationSuite> suites) {
            this.capturedSuites = suites;
            return GraphRagEvaluationBatchReport.builder()
                .batchId(batchId)
                .name(name)
                .suiteCount((long) suites.size())
                .build();
        }
    }

    private static class StubGraphRagSearchService implements GraphRagSearchService {

        private final List<GraphRagSearchResult> results;

        private int searchCallCount;

        private String capturedQuestion;

        private List<Long> capturedDocumentIds = List.of();

        private List<Long> capturedTaskIds = List.of();

        private StubGraphRagSearchService(List<GraphRagSearchResult> results) {
            this.results = results;
        }

        @Override
        public List<GraphRagSearchResult> search(String question,
                                                 List<Long> documentIds,
                                                 List<Long> taskIds,
                                                 int topK,
                                                 int maxHops) {
            this.searchCallCount++;
            this.capturedQuestion = question;
            this.capturedDocumentIds = documentIds;
            this.capturedTaskIds = taskIds;
            return results;
        }
    }
}
