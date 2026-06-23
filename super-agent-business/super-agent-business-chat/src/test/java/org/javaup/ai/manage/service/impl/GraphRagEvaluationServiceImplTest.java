package org.javaup.ai.manage.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.manage.data.SuperAgentKgEntity;
import org.javaup.ai.manage.data.SuperAgentKgEvidence;
import org.javaup.ai.manage.data.SuperAgentKgRelation;
import org.javaup.ai.manage.mapper.SuperAgentKgEntityMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgEvidenceMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgRelationMapper;
import org.javaup.ai.manage.model.graph.GraphRagEvaluationBatchReport;
import org.javaup.ai.manage.model.graph.GraphRagEvaluationBaselineSuites;
import org.javaup.ai.manage.model.graph.GraphRagEvaluationReport;
import org.javaup.ai.manage.model.graph.GraphRagEvaluationSuite;
import org.javaup.ai.manage.model.graph.GraphRagQualityReport;
import org.javaup.ai.manage.service.GraphRagQualityService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphRagEvaluationServiceImplTest {

    @Test
    void evaluatesExpectedEntityRelationAndEvidenceRecall() {
        SuperAgentKgEntity orderService = entity(
            1001L,
            "OrderService",
            "orderservice",
            "SYSTEM",
            "{\"aliases\":[\"订单服务\"],\"rankBoost\":0.9}"
        );
        SuperAgentKgEntity paymentService = entity(
            1002L,
            "PaymentService",
            "paymentservice",
            "SYSTEM",
            "{\"aliases\":[\"支付服务\"]}"
        );
        SuperAgentKgRelation relation = relation(2001L, 1001L, 1002L, "CALLS");
        SuperAgentKgEvidence evidence = evidence(3001L, null, 2001L, "OrderService 调用 PaymentService 完成支付。");
        GraphRagEvaluationServiceImpl service = service(
            List.of(orderService, paymentService),
            List.of(relation),
            List.of(evidence)
        );

        GraphRagEvaluationSuite suite = GraphRagEvaluationSuite.builder()
            .documentId(10L)
            .taskId(20L)
            .expectedEntities(List.of(GraphRagEvaluationSuite.ExpectedEntity.builder()
                .name("订单服务")
                .entityType("SYSTEM")
                .build()))
            .expectedRelations(List.of(GraphRagEvaluationSuite.ExpectedRelation.builder()
                .sourceName("OrderService")
                .targetName("PaymentService")
                .relationType("CALLS")
                .build()))
            .expectedEvidences(List.of(GraphRagEvaluationSuite.ExpectedEvidence.builder()
                .quoteText("调用 PaymentService")
                .sourceName("OrderService")
                .targetName("PaymentService")
                .relationType("CALLS")
                .build()))
            .build();

        GraphRagEvaluationReport report = service.evaluate(suite);

        assertThat(report.getEvaluationLevel()).isEqualTo(GraphRagQualityReport.LEVEL_STRONG);
        assertThat(report.getOverallRecall()).isEqualTo(1D);
        assertThat(report.getEntityRecall()).isEqualTo(1D);
        assertThat(report.getRelationRecall()).isEqualTo(1D);
        assertThat(report.getEvidenceRecall()).isEqualTo(1D);
        assertThat(report.getExpectedEntityCount()).isEqualTo(1L);
        assertThat(report.getMatchedEntityCount()).isEqualTo(1L);
        assertThat(report.getMatchedRelationCount()).isEqualTo(1L);
        assertThat(report.getMatchedEvidenceCount()).isEqualTo(1L);
        assertThat(report.getEntityResults().get(0).getActualEntityId()).isEqualTo(1001L);
        assertThat(report.getRelationResults().get(0).getActualRelationId()).isEqualTo(2001L);
        assertThat(report.getEvidenceResults().get(0).getActualEvidenceId()).isEqualTo(3001L);
        assertThat(report.getQualityReport()).isNotNull();
        assertThat(report.getQualityReport().getQualityLevel()).isEqualTo(GraphRagQualityReport.LEVEL_STRONG);
    }

    @Test
    void reportsMissesForUnmatchedExpectations() {
        SuperAgentKgEntity orderService = entity(1001L, "OrderService", "orderservice", "SYSTEM", "{}");
        GraphRagEvaluationServiceImpl service = service(List.of(orderService), List.of(), List.of());
        GraphRagEvaluationSuite suite = GraphRagEvaluationSuite.builder()
            .documentId(10L)
            .taskId(20L)
            .expectedEntities(List.of(GraphRagEvaluationSuite.ExpectedEntity.builder()
                .name("InventoryService")
                .entityType("SYSTEM")
                .build()))
            .expectedRelations(List.of(GraphRagEvaluationSuite.ExpectedRelation.builder()
                .sourceName("OrderService")
                .targetName("PaymentService")
                .relationType("CALLS")
                .build()))
            .expectedEvidences(List.of(GraphRagEvaluationSuite.ExpectedEvidence.builder()
                .quoteText("PaymentService 完成支付")
                .sourceName("OrderService")
                .targetName("PaymentService")
                .relationType("CALLS")
                .build()))
            .build();

        GraphRagEvaluationReport report = service.evaluate(suite);

        assertThat(report.getEvaluationLevel()).isEqualTo(GraphRagQualityReport.LEVEL_WEAK);
        assertThat(report.getOverallRecall()).isZero();
        assertThat(report.getMatchedEntityCount()).isZero();
        assertThat(report.getMatchedRelationCount()).isZero();
        assertThat(report.getMatchedEvidenceCount()).isZero();
        assertThat(report.getEntityResults().get(0).getMatched()).isFalse();
        assertThat(report.getRelationResults().get(0).getReason()).contains("终点实体");
        assertThat(report.getEvidenceResults().get(0).getMatched()).isFalse();
    }

    @Test
    void emptySuiteReturnsEmptyEvaluation() {
        GraphRagEvaluationServiceImpl service = service(List.of(), List.of(), List.of());
        GraphRagEvaluationReport report = service.evaluate(GraphRagEvaluationSuite.builder()
            .documentId(10L)
            .taskId(20L)
            .build());

        assertThat(report.getEvaluationLevel()).isEqualTo(GraphRagQualityReport.LEVEL_EMPTY);
        assertThat(report.getOverallRecall()).isZero();
        assertThat(report.getEntityResults()).isEmpty();
        assertThat(report.getQualityReport()).isNotNull();
    }

    @Test
    void missingDocumentOrTaskBindingReturnsFailedExpectationReport() {
        GraphRagEvaluationServiceImpl service = service(List.of(), List.of(), List.of());

        GraphRagEvaluationReport report = service.evaluate(GraphRagEvaluationSuite.builder()
            .suiteId("missing-binding")
            .name("未绑定样例")
            .sourceDocument(GraphRagEvaluationBaselineSuites.SOURCE_PRODUCTION_RELEASE)
            .expectedEntities(List.of(GraphRagEvaluationSuite.ExpectedEntity.builder()
                .name("发布负责人")
                .build()))
            .expectedRelations(List.of(GraphRagEvaluationSuite.ExpectedRelation.builder()
                .sourceName("发布负责人")
                .targetName("回滚")
                .relationType("TRIGGERS")
                .build()))
            .expectedEvidences(List.of(GraphRagEvaluationSuite.ExpectedEvidence.builder()
                .quoteKeywords(List.of("发布负责人", "回滚"))
                .build()))
            .build());

        assertThat(report.getEvaluationLevel()).isEqualTo(GraphRagQualityReport.LEVEL_WEAK);
        assertThat(report.getPassed()).isFalse();
        assertThat(report.getOverallRecall()).isZero();
        assertThat(report.getExpectedEntityCount()).isEqualTo(1L);
        assertThat(report.getExpectedRelationCount()).isEqualTo(1L);
        assertThat(report.getExpectedEvidenceCount()).isEqualTo(1L);
        assertThat(report.getSummary()).contains("未绑定真实 documentId/taskId");
        assertThat(report.getEntityResults().get(0).getReason()).contains("未绑定真实 documentId/taskId");
        assertThat(report.getRelationResults().get(0).getReason()).contains("未绑定真实 documentId/taskId");
        assertThat(report.getEvidenceResults().get(0).getReason()).contains("未绑定真实 documentId/taskId");
    }

    @Test
    void evaluatesBatchReportsPassRateAndFailedSuites() {
        SuperAgentKgEntity orderService = entity(
            1001L,
            "OrderService",
            "orderservice",
            "SYSTEM",
            "{\"aliases\":[\"订单服务\"]}"
        );
        SuperAgentKgEntity paymentService = entity(1002L, "PaymentService", "paymentservice", "SYSTEM", "{}");
        SuperAgentKgRelation relation = relation(2001L, 1001L, 1002L, "CALLS");
        SuperAgentKgEvidence evidence = evidence(3001L, null, 2001L, "OrderService 调用 PaymentService 完成支付。");
        GraphRagEvaluationServiceImpl service = service(
            List.of(orderService, paymentService),
            List.of(relation),
            List.of(evidence)
        );

        GraphRagEvaluationSuite matchedSuite = GraphRagEvaluationSuite.builder()
            .suiteId("graph-pass")
            .name("支付链路")
            .documentId(10L)
            .taskId(20L)
            .expectedEntities(List.of(GraphRagEvaluationSuite.ExpectedEntity.builder()
                .name("订单服务")
                .entityType("SYSTEM")
                .build()))
            .expectedRelations(List.of(GraphRagEvaluationSuite.ExpectedRelation.builder()
                .sourceName("OrderService")
                .targetName("PaymentService")
                .relationType("CALLS")
                .build()))
            .expectedEvidences(List.of(GraphRagEvaluationSuite.ExpectedEvidence.builder()
                .quoteText("调用 PaymentService")
                .sourceName("OrderService")
                .targetName("PaymentService")
                .relationType("CALLS")
                .build()))
            .build();
        GraphRagEvaluationSuite missedSuite = GraphRagEvaluationSuite.builder()
            .suiteId("graph-miss")
            .name("库存链路")
            .documentId(10L)
            .taskId(20L)
            .expectedEntities(List.of(GraphRagEvaluationSuite.ExpectedEntity.builder()
                .name("InventoryService")
                .entityType("SYSTEM")
                .build()))
            .build();

        GraphRagEvaluationBatchReport report = service.evaluateBatch(
            "o6-batch",
            "O6 GraphRAG 样例",
            List.of(matchedSuite, missedSuite)
        );

        assertThat(report.getBatchId()).isEqualTo("o6-batch");
        assertThat(report.getSuiteCount()).isEqualTo(2L);
        assertThat(report.getPassedSuiteCount()).isEqualTo(1L);
        assertThat(report.getFailedSuiteCount()).isEqualTo(1L);
        assertThat(report.getPassRate()).isEqualTo(0.5D);
        assertThat(report.getExpectedEntityCount()).isEqualTo(2L);
        assertThat(report.getMatchedEntityCount()).isEqualTo(1L);
        assertThat(report.getExpectedRelationCount()).isEqualTo(1L);
        assertThat(report.getMatchedRelationCount()).isEqualTo(1L);
        assertThat(report.getExpectedEvidenceCount()).isEqualTo(1L);
        assertThat(report.getMatchedEvidenceCount()).isEqualTo(1L);
        assertThat(report.getOverallRecall()).isEqualTo(0.8D);
        assertThat(report.getEvaluationLevel()).isEqualTo(GraphRagQualityReport.LEVEL_WATCH);
        assertThat(report.getFailedSuites()).hasSize(1);
        assertThat(report.getFailedSuites().get(0).getSuiteId()).isEqualTo("graph-miss");
        assertThat(report.getFailedSuites().get(0).getReason()).contains("实体未命中");
    }

    @Test
    void evaluatesO6BatchBaselineWithAliasRequirementsThresholdAndWeakEvidenceKeywords() {
        SuperAgentKgEntity superAgent = entity(
            1001L,
            "SuperAgent",
            "superagent",
            "SYSTEM",
            "{\"aliases\":[\"超级智能体\",\"SA\"],\"rankBoost\":0.95}"
        );
        SuperAgentKgEntity ragTools = entity(
            1002L,
            "RagTools",
            "ragtools",
            "SYSTEM",
            "{\"aliases\":[\"rag-tools\"]}"
        );
        SuperAgentKgEntity graphRag = entity(
            1003L,
            "GraphRAG",
            "graphrag",
            "CONCEPT",
            "{\"aliases\":[\"知识图谱增强检索\"]}"
        );
        SuperAgentKgRelation calls = relation(2001L, 1001L, 1002L, "CALLS");
        SuperAgentKgRelation supports = relation(2002L, 1002L, 1003L, "SUPPORTS");
        SuperAgentKgEvidence callsEvidence = evidence(3001L, null, 2001L, "SA 调用 RagTools 完成 GraphRAG 候选抽取。");
        SuperAgentKgEvidence supportsEvidence = evidence(3002L, null, 2002L, "RagTools 支持 GraphRAG 实体关系抽取。");
        GraphRagEvaluationServiceImpl service = service(
            List.of(superAgent, ragTools, graphRag),
            List.of(calls, supports),
            List.of(callsEvidence, supportsEvidence)
        );

        GraphRagEvaluationSuite aliasAndWeakEvidenceSuite = GraphRagEvaluationSuite.builder()
            .suiteId("o6-alias-weak-evidence")
            .name("别名和弱证据 baseline")
            .scenario("O6 LLM/NER 抽取质量")
            .question("SA 和 RagTools 的调用关系是什么？")
            .documentId(10L)
            .taskId(20L)
            .passThreshold(0.9D)
            .tags(List.of("alias", "weak-evidence", "relation"))
            .expectedEntities(List.of(GraphRagEvaluationSuite.ExpectedEntity.builder()
                .name("SuperAgent")
                .entityType("SYSTEM")
                .aliases(List.of("超级智能体"))
                .mustHaveAliases(List.of("SA", "超级智能体"))
                .build()))
            .expectedRelations(List.of(GraphRagEvaluationSuite.ExpectedRelation.builder()
                .sourceName("SA")
                .targetName("RagTools")
                .relationType("调用")
                .relationTypeAliases(List.of("CALLS"))
                .build()))
            .expectedEvidences(List.of(GraphRagEvaluationSuite.ExpectedEvidence.builder()
                .quoteKeywords(List.of("SA", "调用", "RagTools"))
                .sourceName("SuperAgent")
                .targetName("rag-tools")
                .relationType("调用")
                .relationTypeAliases(List.of("CALLS"))
                .build()))
            .build();
        GraphRagEvaluationSuite missingAliasSuite = GraphRagEvaluationSuite.builder()
            .suiteId("o6-missing-alias")
            .name("歧义别名缺失 baseline")
            .scenario("O6 entity resolution 歧义样例")
            .question("SuperAgent 是否也叫 SAG？")
            .documentId(10L)
            .taskId(20L)
            .passThreshold(0.95D)
            .tags(List.of("alias", "ambiguity"))
            .expectedEntities(List.of(GraphRagEvaluationSuite.ExpectedEntity.builder()
                .name("SuperAgent")
                .entityType("SYSTEM")
                .mustHaveAliases(List.of("SAG"))
                .build()))
            .build();

        GraphRagEvaluationBatchReport report = service.evaluateBatch(
            "o6-llm-ner-baseline",
            "O6 LLM/NER 抽取质量 baseline",
            List.of(aliasAndWeakEvidenceSuite, missingAliasSuite)
        );

        assertThat(report.getSuiteCount()).isEqualTo(2L);
        assertThat(report.getPassedSuiteCount()).isEqualTo(1L);
        assertThat(report.getFailedSuiteCount()).isEqualTo(1L);
        assertThat(report.getMinSuiteRecall()).isZero();
        assertThat(report.getMaxSuiteRecall()).isEqualTo(1D);
        assertThat(report.getReports().get(0).getScenario()).isEqualTo("O6 LLM/NER 抽取质量");
        assertThat(report.getReports().get(0).getQuestion()).contains("调用关系");
        assertThat(report.getReports().get(0).getPassThreshold()).isEqualTo(0.9D);
        assertThat(report.getReports().get(0).getPassed()).isTrue();
        assertThat(report.getReports().get(0).getEvidenceResults().get(0).getExpectedQuoteKeywords())
            .containsExactly("SA", "调用", "RagTools");
        assertThat(report.getReports().get(1).getPassed()).isFalse();
        assertThat(report.getReports().get(1).getEntityResults().get(0).getMissingAliases()).containsExactly("SAG");
        assertThat(report.getFailedSuites()).hasSize(1);
        assertThat(report.getFailedSuites().get(0).getSuiteId()).isEqualTo("o6-missing-alias");
        assertThat(report.getFailedSuites().get(0).getReason()).contains("缺少必须别名");
    }

    @Test
    void evaluatesO6RealDocumentBaselineSuites() {
        SuperAgentKgEntity releaseOwner = entity(
            1001L,
            "发布负责人",
            "发布负责人",
            "ROLE",
            "{\"aliases\":[\"对应研发团队\"]}"
        );
        SuperAgentKgEntity rollback = entity(
            1002L,
            "回滚",
            "回滚",
            "PROCESS",
            "{\"aliases\":[\"强制回滚\",\"人工回滚\"]}"
        );
        SuperAgentKgEntity sre = entity(
            1003L,
            "值班 SRE",
            "值班sre",
            "ROLE",
            "{\"aliases\":[\"SRE 团队\"]}"
        );
        SuperAgentKgEntity trafficSwitch = entity(
            1004L,
            "流量切换",
            "流量切换",
            "PROCESS",
            "{\"aliases\":[\"流量切回旧版本\"]}"
        );
        SuperAgentKgEntity l4Data = entity(
            1005L,
            "L4 数据",
            "l4数据",
            "CONCEPT",
            "{\"aliases\":[\"高敏感信息\"]}"
        );
        SuperAgentKgEntity securityDept = entity(
            1006L,
            "信息安全部",
            "信息安全部",
            "ORG",
            "{}"
        );
        SuperAgentKgEntity auditTrail = entity(
            1007L,
            "AuditTrail",
            "audittrail",
            "SYSTEM",
            "{}"
        );
        SuperAgentKgEntity permissionApply = entity(
            1008L,
            "权限申请",
            "权限申请",
            "PROCESS",
            "{\"aliases\":[\"审批\",\"回收\",\"延长\"]}"
        );
        SuperAgentKgRelation triggers = relation(2001L, 1001L, 1002L, "TRIGGERS");
        SuperAgentKgRelation executes = relation(2002L, 1003L, 1004L, "EXECUTES");
        SuperAgentKgRelation approves = relation(2003L, 1005L, 1006L, "APPROVES");
        SuperAgentKgRelation records = relation(2004L, 1007L, 1008L, "RECORDS");
        GraphRagEvaluationServiceImpl service = service(
            List.of(releaseOwner, rollback, sre, trafficSwitch, l4Data, securityDept, auditTrail, permissionApply),
            List.of(triggers, executes, approves, records),
            List.of(
                evidence(3001L, null, 2001L, "发布负责人在触发强制回滚条件时必须立即发起回滚。"),
                evidence(3002L, null, 2002L, "值班 SRE 控制发布窗口、观察监控、执行流量切换。"),
                evidence(3003L, null, 2003L, "L4 权限申请需部门负责人、数据治理负责人和信息安全部三级审批。"),
                evidence(3004L, null, 2004L, "AuditTrail 需记录权限申请、审批、回收、延长。")
            )
        );

        GraphRagEvaluationBatchReport report = service.evaluateBatch(
            GraphRagEvaluationBaselineSuites.O6_LLM_NER_BATCH_ID,
            GraphRagEvaluationBaselineSuites.O6_LLM_NER_BATCH_NAME,
            GraphRagEvaluationBaselineSuites.o6LlmNerBaseline(10L, 20L)
        );

        assertThat(report.getSuiteCount()).isEqualTo(4L);
        assertThat(report.getPassedSuiteCount()).isEqualTo(4L);
        assertThat(report.getFailedSuiteCount()).isZero();
        assertThat(report.getPassRate()).isEqualTo(1D);
        assertThat(report.getMinSuiteRecall()).isEqualTo(1D);
        assertThat(report.getMaxSuiteRecall()).isEqualTo(1D);
        assertThat(report.getFailedSuites()).isEmpty();
        assertThat(report.getReports()).extracting(GraphRagEvaluationReport::getSourceDocument)
            .contains(
                GraphRagEvaluationBaselineSuites.SOURCE_PRODUCTION_RELEASE,
                GraphRagEvaluationBaselineSuites.SOURCE_CUSTOMER_DATA_ACCESS
            );
        assertThat(report.getReports()).allSatisfy(suiteReport -> {
            assertThat(suiteReport.getPassed()).isTrue();
            assertThat(suiteReport.getOverallRecall()).isEqualTo(1D);
        });
    }

    private static GraphRagEvaluationServiceImpl service(List<SuperAgentKgEntity> entities,
                                                         List<SuperAgentKgRelation> relations,
                                                         List<SuperAgentKgEvidence> evidences) {
        return new GraphRagEvaluationServiceImpl(
            mapper(SuperAgentKgEntityMapper.class, entities),
            mapper(SuperAgentKgRelationMapper.class, relations),
            mapper(SuperAgentKgEvidenceMapper.class, evidences),
            qualityService(),
            new ObjectMapper()
        );
    }

    private static GraphRagQualityService qualityService() {
        return (documentId, taskId) -> GraphRagQualityReport.builder()
            .documentId(documentId)
            .taskId(taskId)
            .qualityLevel(GraphRagQualityReport.LEVEL_STRONG)
            .qualityScore(1D)
            .summary("quality ok")
            .build();
    }

    private static SuperAgentKgEntity entity(Long id,
                                             String name,
                                             String normalizedName,
                                             String entityType,
                                             String metadataJson) {
        SuperAgentKgEntity entity = new SuperAgentKgEntity();
        entity.setId(id);
        entity.setDocumentId(10L);
        entity.setTaskId(20L);
        entity.setEntityKey("ENT_" + id);
        entity.setName(name);
        entity.setNormalizedName(normalizedName);
        entity.setEntityType(entityType);
        entity.setDescription(name + " description");
        entity.setMetadataJson(metadataJson);
        return entity;
    }

    private static SuperAgentKgRelation relation(Long id,
                                                 Long sourceEntityId,
                                                 Long targetEntityId,
                                                 String relationType) {
        SuperAgentKgRelation relation = new SuperAgentKgRelation();
        relation.setId(id);
        relation.setDocumentId(10L);
        relation.setTaskId(20L);
        relation.setSourceEntityId(sourceEntityId);
        relation.setTargetEntityId(targetEntityId);
        relation.setRelationType(relationType);
        relation.setDescription("OrderService calls PaymentService.");
        relation.setWeight(BigDecimal.valueOf(0.9D));
        relation.setMetadataJson("{}");
        return relation;
    }

    private static SuperAgentKgEvidence evidence(Long id,
                                                 Long entityId,
                                                 Long relationId,
                                                 String quoteText) {
        SuperAgentKgEvidence evidence = new SuperAgentKgEvidence();
        evidence.setId(id);
        evidence.setDocumentId(10L);
        evidence.setTaskId(20L);
        evidence.setEntityId(entityId);
        evidence.setRelationId(relationId);
        evidence.setChunkId(5001L);
        evidence.setParentBlockId(6001L);
        evidence.setQuoteText(quoteText);
        evidence.setPageNo(1);
        evidence.setPageRange("P1");
        evidence.setSectionPath("支付流程");
        evidence.setMetadataJson("{}");
        return evidence;
    }

    @SuppressWarnings("unchecked")
    private static <T> T mapper(Class<T> mapperType, List<?> selectListResult) {
        return (T) Proxy.newProxyInstance(
            mapperType.getClassLoader(),
            new Class<?>[]{mapperType},
            (proxy, method, args) -> {
                if ("selectList".equals(method.getName())) {
                    return selectListResult;
                }
                if ("toString".equals(method.getName())) {
                    return mapperType.getSimpleName() + "Proxy";
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
}
