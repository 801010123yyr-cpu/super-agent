package org.javaup.ai.manage.service.impl;

import com.baidu.fsg.uid.UidGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.manage.config.GraphRagBuildProperties;
import org.javaup.ai.manage.data.SuperAgentDocumentChunk;
import org.javaup.ai.manage.data.SuperAgentKgCommunity;
import org.javaup.ai.manage.data.SuperAgentKgEntity;
import org.javaup.ai.manage.data.SuperAgentKgEvidence;
import org.javaup.ai.manage.data.SuperAgentKgRelation;
import org.javaup.ai.manage.mapper.SuperAgentKgCommunityMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgEntityMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgEvidenceMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgRelationMapper;
import org.javaup.ai.manage.model.graph.GraphRagBuildResult;
import org.javaup.ai.manage.model.graph.GraphRagCommunityReportAdvice;
import org.javaup.ai.manage.model.graph.GraphRagCommunityReportContext;
import org.javaup.ai.manage.model.graph.GraphRagEntityResolutionAdvice;
import org.javaup.ai.manage.model.graph.GraphRagEntityResolutionContext;
import org.javaup.ai.manage.model.graph.GraphRagExtractionAdvice;
import org.javaup.ai.manage.model.graph.GraphRagExtractionContext;
import org.javaup.ai.manage.service.GraphRagBuildCheckpointService;
import org.javaup.ai.manage.service.GraphRagCommunityReportAdvisor;
import org.javaup.ai.manage.service.GraphRagEntityResolutionAdvisor;
import org.javaup.ai.manage.service.GraphRagExtractionAdvisor;
import org.javaup.ai.ragtools.client.RagToolsClient;
import org.javaup.ai.ragtools.config.RagToolsProperties;
import org.javaup.ai.ragtools.model.RagToolsGraphExtractRequest;
import org.javaup.ai.ragtools.model.RagToolsGraphExtractResponse;
import org.javaup.lease.RedisLeaseManager;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphRagBuildServiceImplTest {

    @Test
    void buildMergesAliasEntitiesAndRemapsRelationsCommunities() throws Exception {
        InsertCollector entityCollector = new InsertCollector();
        InsertCollector relationCollector = new InsertCollector();
        InsertCollector evidenceCollector = new InsertCollector();
        InsertCollector communityCollector = new InsertCollector();
        ObjectMapper objectMapper = new ObjectMapper();

        GraphRagBuildServiceImpl service = service(
            entityCollector,
            relationCollector,
            evidenceCollector,
            communityCollector,
            new StaticRagToolsClient(graphResponse()),
            objectMapper,
            properties(2, 0L),
            new TestRedisLeaseManager(true),
            new RecordingCheckpointService()
        );

        GraphRagBuildResult result = service.rebuildDocumentGraph(10L, 20L, List.of(orderPaymentChunk(101L)));

        assertThat(result.getEntityCount()).isEqualTo(2);
        assertThat(result.getRelationCount()).isEqualTo(1);
        assertThat(result.getEvidenceCount()).isEqualTo(4);
        assertThat(result.getCommunityCount()).isEqualTo(1);

        assertThat(entityCollector.items()).hasSize(2);
        SuperAgentKgEntity mergedEntity = entityCollector.items(SuperAgentKgEntity.class).stream()
            .filter(entity -> entity.getName().equals("SuperAgent"))
            .findFirst()
            .orElseThrow();
        Map<String, Object> metadata = objectMapper.readValue(mergedEntity.getMetadataJson(), Map.class);
        assertThat(metadata.get("sourceEntityIds")).isEqualTo(List.of("E1", "E2"));
        assertThat(String.valueOf(metadata.get("aliases"))).contains("超级智能体");
        assertThat(String.valueOf(metadata.get("candidateSources"))).contains("textPhrase", "ner.pattern");
        assertThat(String.valueOf(metadata.get("extractorSources"))).contains("rule", "ner");
        assertThat(metadata)
            .containsEntry("rankAlgorithm", "java.pagerank.v1")
            .containsKeys("pagerank", "rankBoost", "rankPosition", "degree", "weightedDegree");
        assertThat(((Number) metadata.get("rankBoost")).doubleValue()).isGreaterThan(0D);

        assertThat(relationCollector.items()).hasSize(1);
        SuperAgentKgRelation mergedRelation = relationCollector.items(SuperAgentKgRelation.class).get(0);
        assertThat(mergedRelation.getRelationType()).isEqualTo("CALLS");
        Map<String, Object> relationMetadata = objectMapper.readValue(mergedRelation.getMetadataJson(), Map.class);
        assertThat(relationMetadata.get("sourceRelationIds")).isEqualTo(List.of("R1", "R2"));
        assertThat(String.valueOf(relationMetadata.get("candidateSources"))).contains("rule.relationWord");
        assertThat(String.valueOf(relationMetadata.get("extractorSources"))).contains("rule");
        assertThat(relationMetadata)
            .containsEntry("rankAlgorithm", "java.pagerank.v1")
            .containsKeys("rankBoost", "sourceEntityRankBoost", "targetEntityRankBoost", "relationWeightBoost");

        assertThat(evidenceCollector.items()).hasSize(4);
        SuperAgentKgEvidence firstEvidence = evidenceCollector.items(SuperAgentKgEvidence.class).get(0);
        Map<String, Object> evidenceMetadata = objectMapper.readValue(firstEvidence.getMetadataJson(), Map.class);
        assertThat(String.valueOf(evidenceMetadata.get("extractorSources"))).contains("rule", "ner");
        assertThat(communityCollector.items()).hasSize(1);
        SuperAgentKgCommunity community = communityCollector.items(SuperAgentKgCommunity.class).get(0);
        List<?> entityIds = objectMapper.readValue(community.getEntityIdsJson(), List.class);
        List<?> relationIds = objectMapper.readValue(community.getRelationIdsJson(), List.class);
        Map<String, Object> communityMetadata = objectMapper.readValue(community.getMetadataJson(), Map.class);
        assertThat(entityIds).hasSize(2);
        assertThat(relationIds).hasSize(1);
        assertThat(communityMetadata)
            .containsEntry("rankAlgorithm", "java.pagerank.v1")
            .containsKeys("rankBoost", "maxEntityRankBoost", "avgEntityRankBoost", "topRankedEntityIds");
    }

    @Test
    void retryExtractFailureAndPersistSuccessfulAttemptOnly() {
        InsertCollector entityCollector = new InsertCollector();
        InsertCollector relationCollector = new InsertCollector();
        InsertCollector evidenceCollector = new InsertCollector();
        InsertCollector communityCollector = new InsertCollector();
        FailingOnceRagToolsClient ragToolsClient = new FailingOnceRagToolsClient();
        TestRedisLeaseManager leaseManager = new TestRedisLeaseManager(true);
        RecordingCheckpointService checkpointService = new RecordingCheckpointService();

        GraphRagBuildServiceImpl service = service(
            entityCollector,
            relationCollector,
            evidenceCollector,
            communityCollector,
            ragToolsClient,
            new ObjectMapper(),
            properties(2, 0L),
            leaseManager,
            checkpointService
        );

        GraphRagBuildResult result = service.rebuildDocumentGraph(10L, 20L, List.of(orderPaymentChunk(101L)));

        assertThat(result.getEntityCount()).isEqualTo(2);
        assertThat(ragToolsClient.callCount()).isEqualTo(2);
        assertThat(entityCollector.items()).hasSize(2);
        assertThat(entityCollector.deleteCount()).isEqualTo(1);
        assertThat(leaseManager.acquireCount()).isEqualTo(1);
        assertThat(leaseManager.renewCount()).isEqualTo(1);
        assertThat(leaseManager.releaseCount()).isEqualTo(1);
        assertThat(checkpointService.events()).contains("retry:EXTRACTING:1", "success:2");
    }

    @Test
    void extractedCheckpointIncludesPythonExtractorMetadata() {
        InsertCollector entityCollector = new InsertCollector();
        InsertCollector relationCollector = new InsertCollector();
        InsertCollector evidenceCollector = new InsertCollector();
        InsertCollector communityCollector = new InsertCollector();
        RecordingCheckpointService checkpointService = new RecordingCheckpointService();

        GraphRagBuildServiceImpl service = service(
            entityCollector,
            relationCollector,
            evidenceCollector,
            communityCollector,
            new StaticRagToolsClient(graphResponse()),
            new ObjectMapper(),
            properties(2, 0L),
            new TestRedisLeaseManager(true),
            checkpointService
        );

        service.rebuildDocumentGraph(10L, 20L, List.of(orderPaymentChunk(101L)));

        Map<String, Object> extractedDetail = checkpointService.detail("EXTRACTED");
        assertThat(extractedDetail).containsKeys("entityCount", "relationCount", "evidenceCount", "communityCount", "extractorMetadata");
        assertThat(String.valueOf(extractedDetail.get("extractorMetadata")))
            .contains("extractorLayers")
            .contains("ner.model")
            .contains("extractorSourceCounts")
            .contains("llmExtractionAdvisor")
            .contains("disabled");
    }

    @Test
    void leaseConflictFailsBeforeExtractAndDoesNotDeleteGraph() {
        InsertCollector entityCollector = new InsertCollector();
        InsertCollector relationCollector = new InsertCollector();
        InsertCollector evidenceCollector = new InsertCollector();
        InsertCollector communityCollector = new InsertCollector();
        StaticRagToolsClient ragToolsClient = new StaticRagToolsClient(graphResponse());
        TestRedisLeaseManager leaseManager = new TestRedisLeaseManager(false);
        RecordingCheckpointService checkpointService = new RecordingCheckpointService();

        GraphRagBuildServiceImpl service = service(
            entityCollector,
            relationCollector,
            evidenceCollector,
            communityCollector,
            ragToolsClient,
            new ObjectMapper(),
            properties(2, 0L),
            leaseManager,
            checkpointService
        );

        assertThatThrownBy(() -> service.rebuildDocumentGraph(10L, 20L, List.of(chunk(101L))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("租约已被占用");

        assertThat(ragToolsClient.callCount()).isZero();
        assertThat(entityCollector.items()).isEmpty();
        assertThat(entityCollector.deleteCount()).isZero();
        assertThat(leaseManager.acquireCount()).isEqualTo(1);
        assertThat(leaseManager.releaseCount()).isZero();
        assertThat(checkpointService.events()).contains("rejected:LOCK_CONFLICT");
    }

    @Test
    void controlledCommunityReportAdvisorEnhancesReportWhenEvidenceIsLegal() throws Exception {
        InsertCollector entityCollector = new InsertCollector();
        InsertCollector relationCollector = new InsertCollector();
        InsertCollector evidenceCollector = new InsertCollector();
        InsertCollector communityCollector = new InsertCollector();
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingCommunityReportAdvisor advisor = new RecordingCommunityReportAdvisor(context -> GraphRagCommunityReportAdvice.builder()
            .reportable(true)
            .title("SuperAgent 调用 RagTools 社区")
            .summary("SuperAgent 通过 RagTools 完成 GraphRAG 工具调用，社区核心关系是系统到工具服务的调用链路。")
            .findings(List.of(GraphRagCommunityReportAdvice.Finding.builder()
                .summary("调用链路清晰")
                .explanation("证据显示 SuperAgent 调用 RagTools，说明该社区表达的是主系统到工具服务的依赖关系。")
                .evidenceIds(List.of(context.getEvidences().get(0).getEvidenceId()))
                .build()))
            .evidenceIds(List.of(context.getEvidences().get(0).getEvidenceId()))
            .rating(6.5D)
            .ratingExplanation("该关系影响 GraphRAG 构建链路。")
            .confidence(0.88D)
            .reason("关系证据充足")
            .build());

        GraphRagBuildServiceImpl service = service(
            entityCollector,
            relationCollector,
            evidenceCollector,
            communityCollector,
            new StaticRagToolsClient(graphResponse()),
            objectMapper,
            properties(2, 0L),
            new TestRedisLeaseManager(true),
            new RecordingCheckpointService(),
            advisor
        );

        GraphRagBuildResult result = service.rebuildDocumentGraph(10L, 20L, List.of(orderPaymentChunk(101L)));

        assertThat(result.getCommunityCount()).isEqualTo(1);
        assertThat(advisor.callCount()).isEqualTo(1);
        SuperAgentKgCommunity community = communityCollector.items(SuperAgentKgCommunity.class).get(0);
        assertThat(community.getTitle()).isEqualTo("SuperAgent 调用 RagTools 社区");
        assertThat(community.getSummary())
            .contains("SuperAgent 通过 RagTools")
            .contains("关键发现")
            .contains("调用链路清晰")
            .contains("重要性评分");
        Map<String, Object> metadata = objectMapper.readValue(community.getMetadataJson(), Map.class);
        assertThat(metadata)
            .containsEntry("communityReportStrategy", "llm.controlled.v1")
            .containsEntry("communityReportEnhanced", true)
            .containsEntry("communityReportFindingCount", 1);
        assertThat(((Number) metadata.get("communityReportConfidence")).doubleValue()).isEqualTo(0.88D);
    }

    @Test
    void controlledCommunityReportAdvisorRejectsUnknownEvidenceIds() throws Exception {
        InsertCollector entityCollector = new InsertCollector();
        InsertCollector relationCollector = new InsertCollector();
        InsertCollector evidenceCollector = new InsertCollector();
        InsertCollector communityCollector = new InsertCollector();
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingCommunityReportAdvisor advisor = new RecordingCommunityReportAdvisor(context -> GraphRagCommunityReportAdvice.builder()
            .reportable(true)
            .title("非法证据报告")
            .summary("这份报告引用了不存在的证据。")
            .evidenceIds(List.of(999999L))
            .rating(9.0D)
            .confidence(0.91D)
            .reason("测试非法证据")
            .build());

        GraphRagBuildServiceImpl service = service(
            entityCollector,
            relationCollector,
            evidenceCollector,
            communityCollector,
            new StaticRagToolsClient(graphResponse()),
            objectMapper,
            properties(2, 0L),
            new TestRedisLeaseManager(true),
            new RecordingCheckpointService(),
            advisor
        );

        GraphRagBuildResult result = service.rebuildDocumentGraph(10L, 20L, List.of(orderPaymentChunk(101L)));

        assertThat(result.getCommunityCount()).isEqualTo(1);
        assertThat(advisor.callCount()).isEqualTo(1);
        SuperAgentKgCommunity community = communityCollector.items(SuperAgentKgCommunity.class).get(0);
        assertThat(community.getTitle()).isEqualTo("SuperAgent / RagTools");
        assertThat(community.getSummary()).isEqualTo("SuperAgent 与 RagTools 的调用关系。");
        Map<String, Object> metadata = objectMapper.readValue(community.getMetadataJson(), Map.class);
        assertThat(metadata)
            .containsEntry("communityReportStrategy", "extractive.template.v1")
            .containsEntry("communityReportEnhanced", false)
            .containsEntry("communityReportRejectedReason", "UNKNOWN_EVIDENCE_ID");
    }

    @Test
    void controlledEntityResolutionAdvisorMergesLegalEntityGroup() throws Exception {
        InsertCollector entityCollector = new InsertCollector();
        InsertCollector relationCollector = new InsertCollector();
        InsertCollector evidenceCollector = new InsertCollector();
        InsertCollector communityCollector = new InsertCollector();
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingEntityResolutionAdvisor entityResolutionAdvisor = new RecordingEntityResolutionAdvisor(context -> GraphRagEntityResolutionAdvice.builder()
            .resolvable(true)
            .mergeGroups(List.of(GraphRagEntityResolutionAdvice.MergeGroup.builder()
                .entityIds(List.of("E1", "E4"))
                .canonicalName("SuperAgent")
                .aliases(List.of("SA"))
                .confidence(0.91D)
                .reason("同一系统的缩写和全称")
                .build()))
            .confidence(0.91D)
            .reason("测试实体消歧")
            .build());

        GraphRagBuildServiceImpl service = service(
            entityCollector,
            relationCollector,
            evidenceCollector,
            communityCollector,
            new StaticRagToolsClient(entityResolutionGraphResponse()),
            objectMapper,
            properties(2, 0L),
            new TestRedisLeaseManager(true),
            new RecordingCheckpointService(),
            null,
            null,
            entityResolutionAdvisor
        );

        GraphRagBuildResult result = service.rebuildDocumentGraph(10L, 20L, List.of(chunk(101L)));

        assertThat(result.getEntityCount()).isEqualTo(2);
        assertThat(result.getRelationCount()).isEqualTo(1);
        assertThat(entityResolutionAdvisor.callCount()).isEqualTo(1);
        SuperAgentKgEntity mergedEntity = entityCollector.items(SuperAgentKgEntity.class).stream()
            .filter(entity -> entity.getName().equals("SuperAgent"))
            .findFirst()
            .orElseThrow();
        Map<String, Object> metadata = objectMapper.readValue(mergedEntity.getMetadataJson(), Map.class);
        assertThat(metadata)
            .containsEntry("entityResolutionStrategy", "llm.controlled.v1")
            .containsEntry("entityResolutionEnhanced", true)
            .containsEntry("entityResolutionCanonicalName", "SuperAgent");
        assertThat(((Number) metadata.get("entityResolutionConfidence")).doubleValue()).isEqualTo(0.91D);
        assertThat(String.valueOf(metadata.get("sourceEntityIds"))).contains("E1", "E4");
        assertThat(String.valueOf(metadata.get("entityResolutionSourceEntityIds"))).contains("E1", "E4");
    }

    @Test
    void controlledEntityResolutionAdvisorRejectsCrossTypeMerge() throws Exception {
        InsertCollector entityCollector = new InsertCollector();
        InsertCollector relationCollector = new InsertCollector();
        InsertCollector evidenceCollector = new InsertCollector();
        InsertCollector communityCollector = new InsertCollector();
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingEntityResolutionAdvisor entityResolutionAdvisor = new RecordingEntityResolutionAdvisor(context -> GraphRagEntityResolutionAdvice.builder()
            .resolvable(true)
            .mergeGroups(List.of(GraphRagEntityResolutionAdvice.MergeGroup.builder()
                .entityIds(List.of("E1", "E5"))
                .canonicalName("SuperAgent")
                .confidence(0.95D)
                .reason("跨类型非法合并")
                .build()))
            .confidence(0.95D)
            .reason("测试跨类型拒绝")
            .build());

        GraphRagBuildServiceImpl service = service(
            entityCollector,
            relationCollector,
            evidenceCollector,
            communityCollector,
            new StaticRagToolsClient(crossTypeEntityResolutionGraphResponse()),
            objectMapper,
            properties(2, 0L),
            new TestRedisLeaseManager(true),
            new RecordingCheckpointService(),
            null,
            null,
            entityResolutionAdvisor
        );

        GraphRagBuildResult result = service.rebuildDocumentGraph(10L, 20L, List.of(chunk(101L)));

        assertThat(result.getEntityCount()).isEqualTo(3);
        assertThat(entityResolutionAdvisor.callCount()).isEqualTo(1);
        for (SuperAgentKgEntity entity : entityCollector.items(SuperAgentKgEntity.class)) {
            Map<String, Object> metadata = objectMapper.readValue(entity.getMetadataJson(), Map.class);
            assertThat(metadata)
                .containsEntry("entityResolutionStrategy", "java.alias.v1")
                .containsEntry("entityResolutionEnhanced", false);
        }
    }

    @Test
    void controlledGraphExtractionAdvisorAddsGroundedEntitiesRelationsAndEvidences() throws Exception {
        InsertCollector entityCollector = new InsertCollector();
        InsertCollector relationCollector = new InsertCollector();
        InsertCollector evidenceCollector = new InsertCollector();
        InsertCollector communityCollector = new InsertCollector();
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingCheckpointService checkpointService = new RecordingCheckpointService();
        RagToolsGraphExtractResponse pythonResponse = new RagToolsGraphExtractResponse();
        pythonResponse.setMetadata(Map.of(
            "extractorLayers", List.of(Map.of("name", "ner.model", "status", "disabled")),
            "extractorSourceCounts", Map.of("rule", 4, "ner", 2)
        ));
        RecordingGraphExtractionAdvisor extractionAdvisor = new RecordingGraphExtractionAdvisor(context -> GraphRagExtractionAdvice.builder()
            .graphable(true)
            .entities(List.of(
                GraphRagExtractionAdvice.EntityItem.builder()
                    .id("E1")
                    .name("OrderService")
                    .normalizedName("orderservice")
                    .entityType("SYSTEM")
                    .aliases(List.of("订单服务"))
                    .description("订单服务负责创建和处理订单。")
                    .confidence(0.92D)
                    .sourceChunkIds(List.of(101L))
                    .build(),
                GraphRagExtractionAdvice.EntityItem.builder()
                    .id("E2")
                    .name("PaymentService")
                    .normalizedName("paymentservice")
                    .entityType("SYSTEM")
                    .aliases(List.of("支付服务"))
                    .description("支付服务负责完成支付。")
                    .confidence(0.91D)
                    .sourceChunkIds(List.of(101L))
                    .build()
            ))
            .relations(List.of(
                GraphRagExtractionAdvice.RelationItem.builder()
                    .id("R1")
                    .sourceEntityId("E1")
                    .targetEntityId("E2")
                    .relationType("CALLS")
                    .supportMode("EXPLICIT_ACTION")
                    .predicateQuoteText("调用")
                    .relationTypeReason("原文明确说明 OrderService 调用 PaymentService。")
                    .description("OrderService 调用 PaymentService。")
                    .weight(0.93D)
                    .confidence(0.94D)
                    .build()
            ))
            .evidences(List.of(
                GraphRagExtractionAdvice.EvidenceItem.builder()
                    .id("EV1")
                    .entityId("E1")
                    .chunkId(101L)
                    .quoteText("OrderService 调用 PaymentService 完成支付。")
                    .confidence(0.92D)
                    .build(),
                GraphRagExtractionAdvice.EvidenceItem.builder()
                    .id("EV2")
                    .entityId("E2")
                    .chunkId(101L)
                    .quoteText("OrderService 调用 PaymentService 完成支付。")
                    .confidence(0.92D)
                    .build(),
                GraphRagExtractionAdvice.EvidenceItem.builder()
                    .id("EV3")
                    .relationId("R1")
                    .chunkId(101L)
                    .quoteText("OrderService 调用 PaymentService 完成支付。")
                    .confidence(0.92D)
                    .build()
            ))
            .confidence(0.92D)
            .reason("chunk 中存在明确实体和调用关系")
            .build());

        GraphRagBuildServiceImpl service = service(
            entityCollector,
            relationCollector,
            evidenceCollector,
            communityCollector,
            new StaticRagToolsClient(pythonResponse),
            objectMapper,
            properties(2, 0L),
            new TestRedisLeaseManager(true),
            checkpointService,
            extractionAdvisor,
            null,
            null
        );

        GraphRagBuildResult result = service.rebuildDocumentGraph(10L, 20L, List.of(orderPaymentChunk(101L)));

        assertThat(result.getEntityCount()).isEqualTo(2);
        assertThat(result.getRelationCount()).isEqualTo(1);
        assertThat(result.getEvidenceCount()).isEqualTo(3);
        assertThat(extractionAdvisor.callCount()).isEqualTo(1);
        SuperAgentKgEntity orderService = entityCollector.items(SuperAgentKgEntity.class).stream()
            .filter(entity -> entity.getName().equals("OrderService"))
            .findFirst()
            .orElseThrow();
        Map<String, Object> entityMetadata = objectMapper.readValue(orderService.getMetadataJson(), Map.class);
        assertThat(String.valueOf(entityMetadata.get("sourceMetadata"))).contains("llm.controlled.extract.v1");
        SuperAgentKgRelation relation = relationCollector.items(SuperAgentKgRelation.class).get(0);
        Map<String, Object> relationMetadata = objectMapper.readValue(relation.getMetadataJson(), Map.class);
        assertThat(String.valueOf(relationMetadata.get("sourceMetadata"))).contains("llm.controlled.extract.v1");
        assertThat(String.valueOf(relationMetadata.get("sourceMetadata")))
            .contains("accepted_strong")
            .contains("EXPLICIT_ACTION")
            .contains("CALLS");
        assertThat(String.valueOf(checkpointService.detail("EXTRACTED").get("extractorMetadata")))
            .contains("extractorLayers")
            .contains("ner.model")
            .contains("extractorSourceCounts");
        Map<String, Object> extractorMetadata = (Map<String, Object>) checkpointService.detail("EXTRACTED").get("extractorMetadata");
        Map<String, Object> advisorMetadata = (Map<String, Object>) extractorMetadata.get("llmExtractionAdvisor");
        assertThat(advisorMetadata)
            .containsEntry("strategy", "llm.controlled.extract.v1")
            .containsEntry("status", "accepted")
            .containsEntry("acceptedEntityCount", 2)
            .containsEntry("acceptedRelationCount", 1)
            .containsEntry("acceptedEvidenceCount", 3);
    }

    @Test
    void controlledGraphExtractionAdvisorDowngradesWeakSupportStrongRelation() throws Exception {
        InsertCollector entityCollector = new InsertCollector();
        InsertCollector relationCollector = new InsertCollector();
        InsertCollector evidenceCollector = new InsertCollector();
        InsertCollector communityCollector = new InsertCollector();
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingCheckpointService checkpointService = new RecordingCheckpointService();
        RecordingGraphExtractionAdvisor extractionAdvisor = new RecordingGraphExtractionAdvisor(context -> GraphRagExtractionAdvice.builder()
            .graphable(true)
            .entities(List.of(
                GraphRagExtractionAdvice.EntityItem.builder()
                    .id("E1")
                    .name("智能客服平台")
                    .normalizedName("智能客服平台")
                    .entityType("SYSTEM")
                    .confidence(0.93D)
                    .sourceChunkIds(List.of(104L))
                    .build(),
                GraphRagExtractionAdvice.EntityItem.builder()
                    .id("E2")
                    .name("GitLab")
                    .normalizedName("gitlab")
                    .entityType("SYSTEM")
                    .confidence(0.92D)
                    .sourceChunkIds(List.of(104L))
                    .build()
            ))
            .relations(List.of(GraphRagExtractionAdvice.RelationItem.builder()
                .id("R1")
                .sourceEntityId("E1")
                .targetEntityId("E2")
                .relationType("DEPENDS_ON")
                .supportMode("LIST_MEMBERSHIP")
                .predicateQuoteText("核心平台")
                .relationTypeReason("二者出现在同一核心平台清单中。")
                .description("智能客服平台与 GitLab 出现在同一核心平台清单。")
                .weight(0.82D)
                .confidence(0.91D)
                .build()))
            .evidences(List.of(GraphRagExtractionAdvice.EvidenceItem.builder()
                .id("EV1")
                .relationId("R1")
                .chunkId(104L)
                .quoteText("适用系统：智能客服平台。核心平台：GitLab。")
                .confidence(0.9D)
                .build()))
            .confidence(0.92D)
            .reason("chunk 中存在清单型弱关联")
            .build());

        GraphRagBuildServiceImpl service = service(
            entityCollector,
            relationCollector,
            evidenceCollector,
            communityCollector,
            new StaticRagToolsClient(new RagToolsGraphExtractResponse()),
            objectMapper,
            properties(2, 0L),
            new TestRedisLeaseManager(true),
            checkpointService,
            extractionAdvisor,
            null,
            null
        );

        GraphRagBuildResult result = service.rebuildDocumentGraph(10L, 20L, List.of(platformListChunk(104L)));

        assertThat(result.getRelationCount()).isEqualTo(1);
        SuperAgentKgRelation relation = relationCollector.items(SuperAgentKgRelation.class).get(0);
        assertThat(relation.getRelationType()).isEqualTo("ASSOCIATED_WITH");
        Map<String, Object> relationMetadata = objectMapper.readValue(relation.getMetadataJson(), Map.class);
        assertThat(String.valueOf(relationMetadata.get("sourceMetadata")))
            .contains("requestedRelationType=DEPENDS_ON")
            .contains("effectiveRelationType=ASSOCIATED_WITH")
            .contains("supportMode=LIST_MEMBERSHIP")
            .contains("downgraded_weak_support");
        assertThat(evidenceCollector.items(SuperAgentKgEvidence.class)).hasSize(1);
    }

    @Test
    void controlledGraphExtractionAdvisorRejectsUngroundedQuotes() {
        InsertCollector entityCollector = new InsertCollector();
        InsertCollector relationCollector = new InsertCollector();
        InsertCollector evidenceCollector = new InsertCollector();
        InsertCollector communityCollector = new InsertCollector();
        RecordingCheckpointService checkpointService = new RecordingCheckpointService();
        RecordingGraphExtractionAdvisor extractionAdvisor = new RecordingGraphExtractionAdvisor(context -> GraphRagExtractionAdvice.builder()
            .graphable(true)
            .entities(List.of(
                GraphRagExtractionAdvice.EntityItem.builder()
                    .id("E1")
                    .name("OrderService")
                    .normalizedName("orderservice")
                    .entityType("SYSTEM")
                    .confidence(0.95D)
                    .sourceChunkIds(List.of(101L))
                    .build()
            ))
            .confidence(0.95D)
            .reason("测试未命中原文片段")
            .build());

        GraphRagBuildServiceImpl service = service(
            entityCollector,
            relationCollector,
            evidenceCollector,
            communityCollector,
            new StaticRagToolsClient(new RagToolsGraphExtractResponse()),
            new ObjectMapper(),
            properties(2, 0L),
            new TestRedisLeaseManager(true),
            checkpointService,
            extractionAdvisor,
            null,
            null
        );

        GraphRagBuildResult result = service.rebuildDocumentGraph(10L, 20L, List.of(ungroundedChunk(102L)));

        assertThat(result.getEntityCount()).isZero();
        assertThat(result.getRelationCount()).isZero();
        assertThat(result.getEvidenceCount()).isZero();
        assertThat(extractionAdvisor.callCount()).isEqualTo(1);
        assertThat(entityCollector.items()).isEmpty();
        Map<String, Object> extractorMetadata = (Map<String, Object>) checkpointService.detail("EXTRACTED").get("extractorMetadata");
        Map<String, Object> advisorMetadata = (Map<String, Object>) extractorMetadata.get("llmExtractionAdvisor");
        assertThat(advisorMetadata)
            .containsEntry("status", "rejected")
            .containsEntry("rejectedReason", "NO_VALID_ENTITY");
    }

    private static GraphRagBuildServiceImpl service(InsertCollector entityCollector,
                                                    InsertCollector relationCollector,
                                                    InsertCollector evidenceCollector,
                                                    InsertCollector communityCollector,
                                                    RagToolsClient ragToolsClient,
                                                    ObjectMapper objectMapper,
                                                    GraphRagBuildProperties properties,
                                                    TestRedisLeaseManager leaseManager,
                                                    GraphRagBuildCheckpointService checkpointService) {
        return service(entityCollector, relationCollector, evidenceCollector, communityCollector, ragToolsClient,
            objectMapper, properties, leaseManager, checkpointService, null, null, null);
    }

    private static GraphRagBuildServiceImpl service(InsertCollector entityCollector,
                                                    InsertCollector relationCollector,
                                                    InsertCollector evidenceCollector,
                                                    InsertCollector communityCollector,
                                                    RagToolsClient ragToolsClient,
                                                    ObjectMapper objectMapper,
                                                    GraphRagBuildProperties properties,
                                                    TestRedisLeaseManager leaseManager,
                                                    GraphRagBuildCheckpointService checkpointService,
                                                    GraphRagCommunityReportAdvisor communityReportAdvisor) {
        return service(entityCollector, relationCollector, evidenceCollector, communityCollector, ragToolsClient,
            objectMapper, properties, leaseManager, checkpointService, null, communityReportAdvisor, null);
    }

    private static GraphRagBuildServiceImpl service(InsertCollector entityCollector,
                                                    InsertCollector relationCollector,
                                                    InsertCollector evidenceCollector,
                                                    InsertCollector communityCollector,
                                                    RagToolsClient ragToolsClient,
                                                    ObjectMapper objectMapper,
                                                    GraphRagBuildProperties properties,
                                                    TestRedisLeaseManager leaseManager,
                                                    GraphRagBuildCheckpointService checkpointService,
                                                    GraphRagExtractionAdvisor extractionAdvisor,
                                                    GraphRagCommunityReportAdvisor communityReportAdvisor,
                                                    GraphRagEntityResolutionAdvisor entityResolutionAdvisor) {
        return new GraphRagBuildServiceImpl(
            mapper(SuperAgentKgEntityMapper.class, entityCollector),
            mapper(SuperAgentKgRelationMapper.class, relationCollector),
            mapper(SuperAgentKgEvidenceMapper.class, evidenceCollector),
            mapper(SuperAgentKgCommunityMapper.class, communityCollector),
            ragToolsClient,
            objectMapper,
            uidGenerator(),
            properties,
            leaseManager,
            checkpointService,
            transactionTemplate(),
            extractionAdvisor,
            communityReportAdvisor,
            entityResolutionAdvisor
        );
    }

    private static GraphRagBuildProperties properties(int maxAttempts, long retryBackoffMillis) {
        GraphRagBuildProperties properties = new GraphRagBuildProperties();
        properties.setLeaseEnabled(true);
        properties.setLeaseTtlSeconds(120);
        properties.setMaxAttempts(maxAttempts);
        properties.setRetryBackoffMillis(retryBackoffMillis);
        return properties;
    }

    private static TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        });
    }

    private static RagToolsGraphExtractResponse graphResponse() {
        RagToolsGraphExtractResponse response = new RagToolsGraphExtractResponse();
        response.setEntities(List.of(
            entity("E1", "SuperAgent", "superagent", List.of("超级智能体"), "SYSTEM", List.of(101L), List.of("EV1")),
            entity("E2", "超级智能体", "超级智能体", List.of("SuperAgent"), "SYSTEM", List.of(102L), List.of("EV2")),
            entity("E3", "RagTools", "ragtools", List.of(), "SYSTEM", List.of(101L), List.of())
        ));
        response.setRelations(List.of(
            relation("R1", "E1", "E3", "CALLS", List.of("EV3")),
            relation("R2", "E2", "E3", "CALLS", List.of("EV4"))
        ));
        response.setEvidences(List.of(
            evidence("EV1", "E1", "", 101L, 201L, "SuperAgent 是超级智能体。"),
            evidence("EV2", "E2", "", 102L, 202L, "超级智能体也称 SuperAgent。"),
            evidence("EV3", "", "R1", 101L, 201L, "SuperAgent 调用 RagTools。"),
            evidence("EV4", "", "R2", 102L, 202L, "超级智能体调用 RagTools。")
        ));
        response.setCommunities(List.of(community()));
        response.setMetadata(Map.of(
            "extractorLayers", List.of(
                Map.of("name", "rule", "enabled", true, "status", "ready"),
                Map.of("name", "ner.model", "enabled", false, "status", "disabled")
            ),
            "extractorSourceCounts", Map.of("rule", 4, "ner", 2)
        ));
        return response;
    }

    private static RagToolsGraphExtractResponse entityResolutionGraphResponse() {
        RagToolsGraphExtractResponse response = new RagToolsGraphExtractResponse();
        response.setEntities(new ArrayList<>(List.of(
            entity("E1", "SuperAgent", "superagent", List.of(), "SYSTEM", List.of(101L), List.of("EV1")),
            entity("E4", "SA", "sa", List.of("SuperAgent"), "SYSTEM", List.of(102L), List.of("EV2")),
            entity("E3", "RagTools", "ragtools", List.of(), "SYSTEM", List.of(101L), List.of())
        )));
        response.setRelations(new ArrayList<>(List.of(
            relation("R1", "E4", "E3", "CALLS", List.of("EV3"))
        )));
        response.setEvidences(new ArrayList<>(List.of(
            evidence("EV1", "E1", "", 101L, 201L, "SuperAgent 是主系统。"),
            evidence("EV2", "E4", "", 102L, 202L, "SA 是 SuperAgent 的缩写。"),
            evidence("EV3", "", "R1", 102L, 202L, "SA 调用 RagTools。")
        )));
        response.setCommunities(new ArrayList<>());
        return response;
    }

    private static RagToolsGraphExtractResponse crossTypeEntityResolutionGraphResponse() {
        RagToolsGraphExtractResponse response = entityResolutionGraphResponse();
        response.getEntities().add(entity("E5", "SuperAgent", "superagent", List.of(), "MODULE", List.of(103L), List.of("EV5")));
        response.getEvidences().add(evidence("EV5", "E5", "", 103L, 203L, "SuperAgent 模块负责对外接口。"));
        return response;
    }

    private static RagToolsGraphExtractResponse.Entity entity(String id,
                                                              String name,
                                                              String normalizedName,
                                                              List<String> aliases,
                                                              String type,
                                                              List<Long> sourceChunkIds,
                                                              List<String> evidenceIds) {
        RagToolsGraphExtractResponse.Entity entity = new RagToolsGraphExtractResponse.Entity();
        entity.setId(id);
        entity.setName(name);
        entity.setNormalizedName(normalizedName);
        entity.setAliases(new ArrayList<>(aliases));
        entity.setType(type);
        entity.setDescription(name + " 描述");
        entity.setConfidence(0.92D);
        entity.setSourceChunkIds(new ArrayList<>(sourceChunkIds));
        entity.setEvidenceIds(new ArrayList<>(evidenceIds));
        entity.setMetadata(Map.of(
            "mentionCount", 1,
            "candidateScore", 10D,
            "candidateSources", List.of("textPhrase", "ner.pattern"),
            "extractorSources", List.of("rule", "ner")
        ));
        return entity;
    }

    private static RagToolsGraphExtractResponse.Relation relation(String id,
                                                                  String sourceEntityId,
                                                                  String targetEntityId,
                                                                  String relationType,
                                                                  List<String> evidenceIds) {
        RagToolsGraphExtractResponse.Relation relation = new RagToolsGraphExtractResponse.Relation();
        relation.setId(id);
        relation.setSourceEntityId(sourceEntityId);
        relation.setTargetEntityId(targetEntityId);
        relation.setRelationType(relationType);
        relation.setDescription(sourceEntityId + " " + relationType + " " + targetEntityId);
        relation.setWeight(0.9D);
        relation.setEvidenceIds(new ArrayList<>(evidenceIds));
        relation.setMetadata(Map.of(
            "confidence", 0.9D,
            "candidateSources", List.of("rule.relationWord"),
            "extractorSources", List.of("rule")
        ));
        return relation;
    }

    private static RagToolsGraphExtractResponse.Evidence evidence(String id,
                                                                  String entityId,
                                                                  String relationId,
                                                                  Long chunkId,
                                                                  Long parentBlockId,
                                                                  String quoteText) {
        RagToolsGraphExtractResponse.Evidence evidence = new RagToolsGraphExtractResponse.Evidence();
        evidence.setId(id);
        evidence.setEntityId(entityId);
        evidence.setRelationId(relationId);
        evidence.setChunkId(chunkId);
        evidence.setParentBlockId(parentBlockId);
        evidence.setQuoteText(quoteText);
        evidence.setPageNo(1);
        evidence.setSectionPath("架构/GraphRAG");
        evidence.setMetadata(Map.of(
            "source", "test",
            "sourceType", "ner",
            "extractorSources", List.of("rule", "ner")
        ));
        return evidence;
    }

    private static RagToolsGraphExtractResponse.Community community() {
        RagToolsGraphExtractResponse.Community community = new RagToolsGraphExtractResponse.Community();
        community.setId("C1");
        community.setTitle("SuperAgent / RagTools");
        community.setSummary("SuperAgent 与 RagTools 的调用关系。");
        community.setEntityIds(new ArrayList<>(List.of("E1", "E2", "E3")));
        community.setRelationIds(new ArrayList<>(List.of("R1", "R2")));
        community.setEvidenceIds(new ArrayList<>(List.of("EV3", "EV4")));
        community.setMetadata(Map.of("communityAlgorithm", "networkx.greedy_modularity"));
        return community;
    }

    private static SuperAgentDocumentChunk chunk(Long id) {
        SuperAgentDocumentChunk chunk = new SuperAgentDocumentChunk();
        chunk.setId(id);
        chunk.setParentBlockId(201L);
        chunk.setChunkText("SuperAgent 调用 RagTools。");
        chunk.setContentWithWeight("SuperAgent 调用 RagTools。");
        return chunk;
    }

    private static SuperAgentDocumentChunk ungroundedChunk(Long id) {
        SuperAgentDocumentChunk chunk = new SuperAgentDocumentChunk();
        chunk.setId(id);
        chunk.setParentBlockId(202L);
        chunk.setChunkText("系统完成支付流程。");
        chunk.setContentWithWeight("系统完成支付流程。");
        return chunk;
    }

    private static SuperAgentDocumentChunk orderPaymentChunk(Long id) {
        SuperAgentDocumentChunk chunk = new SuperAgentDocumentChunk();
        chunk.setId(id);
        chunk.setParentBlockId(203L);
        chunk.setChunkText("OrderService 调用 PaymentService 完成支付。");
        chunk.setContentWithWeight("OrderService 调用 PaymentService 完成支付。");
        return chunk;
    }

    private static SuperAgentDocumentChunk platformListChunk(Long id) {
        SuperAgentDocumentChunk chunk = new SuperAgentDocumentChunk();
        chunk.setId(id);
        chunk.setParentBlockId(204L);
        chunk.setChunkText("适用系统：智能客服平台。核心平台：GitLab。");
        chunk.setContentWithWeight("适用系统：智能客服平台。核心平台：GitLab。");
        return chunk;
    }

    private static UidGenerator uidGenerator() {
        AtomicLong sequence = new AtomicLong(1000L);
        return (UidGenerator) Proxy.newProxyInstance(
            UidGenerator.class.getClassLoader(),
            new Class<?>[]{UidGenerator.class},
            (proxy, method, args) -> {
                if ("getUid".equals(method.getName()) || "getId".equals(method.getName())) {
                    return sequence.incrementAndGet();
                }
                if ("parseUid".equals(method.getName())) {
                    return String.valueOf(args[0]);
                }
                if ("toString".equals(method.getName())) {
                    return "UidGeneratorProxy";
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

    @SuppressWarnings("unchecked")
    private static <T> T mapper(Class<T> mapperType, InsertCollector insertCollector) {
        return (T) Proxy.newProxyInstance(
            mapperType.getClassLoader(),
            new Class<?>[]{mapperType},
            (proxy, method, args) -> {
                if ("insert".equals(method.getName())) {
                    if (args != null && args.length > 0) {
                        insertCollector.add(args[0]);
                    }
                    return 1;
                }
                if ("delete".equals(method.getName())) {
                    insertCollector.recordDelete();
                    return 1;
                }
                if ("selectList".equals(method.getName())) {
                    return List.of();
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

    private static final class StaticRagToolsClient extends RagToolsClient {

        private final RagToolsGraphExtractResponse response;
        private final AtomicInteger callCount = new AtomicInteger();

        private StaticRagToolsClient(RagToolsGraphExtractResponse response) {
            super(new RagToolsProperties());
            this.response = response;
        }

        @Override
        public RagToolsGraphExtractResponse extractGraph(RagToolsGraphExtractRequest request) {
            callCount.incrementAndGet();
            return response;
        }

        private int callCount() {
            return callCount.get();
        }
    }

    private static final class FailingOnceRagToolsClient extends RagToolsClient {

        private final AtomicInteger callCount = new AtomicInteger();

        private FailingOnceRagToolsClient() {
            super(new RagToolsProperties());
        }

        @Override
        public RagToolsGraphExtractResponse extractGraph(RagToolsGraphExtractRequest request) {
            if (callCount.incrementAndGet() == 1) {
                throw new IllegalStateException("temporary graph extract failure");
            }
            return graphResponse();
        }

        private int callCount() {
            return callCount.get();
        }
    }

    private static final class TestRedisLeaseManager extends RedisLeaseManager {

        private final boolean acquireResult;
        private final AtomicInteger acquireCount = new AtomicInteger();
        private final AtomicInteger renewCount = new AtomicInteger();
        private final AtomicInteger releaseCount = new AtomicInteger();

        private TestRedisLeaseManager(boolean acquireResult) {
            super(null);
            this.acquireResult = acquireResult;
        }

        @Override
        public boolean acquire(String key, String ownerToken, Duration ttl) {
            acquireCount.incrementAndGet();
            return acquireResult;
        }

        @Override
        public boolean renew(String key, String ownerToken, Duration ttl) {
            renewCount.incrementAndGet();
            return true;
        }

        @Override
        public boolean release(String key, String ownerToken) {
            releaseCount.incrementAndGet();
            return true;
        }

        private int acquireCount() {
            return acquireCount.get();
        }

        private int renewCount() {
            return renewCount.get();
        }

        private int releaseCount() {
            return releaseCount.get();
        }
    }

    private static final class RecordingCheckpointService implements GraphRagBuildCheckpointService {

        private final List<String> events = new ArrayList<>();
        private final Map<String, Map<String, Object>> details = new java.util.LinkedHashMap<>();

        @Override
        public void markRunning(Long documentId,
                                Long taskId,
                                String stage,
                                int attempt,
                                int maxAttempts,
                                Map<String, Object> detail) {
            events.add("running:" + stage + ":" + attempt);
            details.put(stage, detail);
        }

        @Override
        public void markSuccess(Long documentId,
                                Long taskId,
                                GraphRagBuildResult result,
                                int attempt,
                                int maxAttempts) {
            events.add("success:" + attempt);
        }

        @Override
        public void markRetry(Long documentId,
                              Long taskId,
                              String stage,
                              int attempt,
                              int maxAttempts,
                              long backoffMillis,
                              Throwable exception) {
            events.add("retry:" + stage + ":" + attempt);
        }

        @Override
        public void markRejected(Long documentId,
                                 Long taskId,
                                 String stage,
                                 int maxAttempts,
                                 String message,
                                 Map<String, Object> detail) {
            events.add("rejected:" + stage);
        }

        @Override
        public void markFailure(Long documentId,
                                Long taskId,
                                String stage,
                                int attempt,
                                int maxAttempts,
                                Throwable exception) {
            events.add("failure:" + stage + ":" + attempt);
        }

        private List<String> events() {
            return events;
        }

        private Map<String, Object> detail(String stage) {
            return details.getOrDefault(stage, Map.of());
        }
    }

    private static final class RecordingCommunityReportAdvisor implements GraphRagCommunityReportAdvisor {

        private final java.util.function.Function<GraphRagCommunityReportContext, GraphRagCommunityReportAdvice> adviceFactory;
        private final AtomicInteger callCount = new AtomicInteger();

        private RecordingCommunityReportAdvisor(java.util.function.Function<GraphRagCommunityReportContext, GraphRagCommunityReportAdvice> adviceFactory) {
            this.adviceFactory = adviceFactory;
        }

        @Override
        public Optional<GraphRagCommunityReportAdvice> generateReport(GraphRagCommunityReportContext context) {
            callCount.incrementAndGet();
            return Optional.ofNullable(adviceFactory.apply(context));
        }

        private int callCount() {
            return callCount.get();
        }
    }

    private static final class RecordingGraphExtractionAdvisor implements GraphRagExtractionAdvisor {

        private final java.util.function.Function<GraphRagExtractionContext, GraphRagExtractionAdvice> adviceFactory;
        private final AtomicInteger callCount = new AtomicInteger();

        private RecordingGraphExtractionAdvisor(java.util.function.Function<GraphRagExtractionContext, GraphRagExtractionAdvice> adviceFactory) {
            this.adviceFactory = adviceFactory;
        }

        @Override
        public Optional<GraphRagExtractionAdvice> extract(GraphRagExtractionContext context) {
            callCount.incrementAndGet();
            return Optional.ofNullable(adviceFactory.apply(context));
        }

        private int callCount() {
            return callCount.get();
        }
    }

    private static final class RecordingEntityResolutionAdvisor implements GraphRagEntityResolutionAdvisor {

        private final java.util.function.Function<GraphRagEntityResolutionContext, GraphRagEntityResolutionAdvice> adviceFactory;
        private final AtomicInteger callCount = new AtomicInteger();

        private RecordingEntityResolutionAdvisor(java.util.function.Function<GraphRagEntityResolutionContext, GraphRagEntityResolutionAdvice> adviceFactory) {
            this.adviceFactory = adviceFactory;
        }

        @Override
        public Optional<GraphRagEntityResolutionAdvice> advise(GraphRagEntityResolutionContext context) {
            callCount.incrementAndGet();
            return Optional.ofNullable(adviceFactory.apply(context));
        }

        private int callCount() {
            return callCount.get();
        }
    }

    private static final class InsertCollector {
        private final List<Object> items = new ArrayList<>();
        private final AtomicInteger deleteCount = new AtomicInteger();

        private void add(Object value) {
            items.add(value);
        }

        private void recordDelete() {
            deleteCount.incrementAndGet();
        }

        private int deleteCount() {
            return deleteCount.get();
        }

        private List<Object> items() {
            return items;
        }

        private <T> List<T> items(Class<T> type) {
            return items.stream()
                .map(type::cast)
                .toList();
        }
    }
}
