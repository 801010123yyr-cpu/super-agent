package org.javaup.ai.chatagent.rag.retrieve.channel;

import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.HistoryPlanningContext;
import org.javaup.ai.chatagent.rag.service.DocumentRetrieveRequestFactory;
import org.javaup.ai.manage.model.DocumentRetrieveRequest;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.ai.manage.model.graph.GraphRagSearchResult;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.ai.manage.service.GraphRagSearchService;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphRagRetrievalChannelTest {

    @Test
    void entityEvidenceSkipsMissingRelationMetadata() {
        GraphRagSearchResult entityEvidence = GraphRagSearchResult.builder()
            .documentId(100L)
            .entityId(200L)
            .entityName("检索命中率")
            .canonicalEntityKey("SYSTEM:retrieval-hit-rate")
            .canonicalEntityName("检索命中率")
            .canonicalEntityCount(2)
            .canonicalDocumentCount(2)
            .relationGroupKey("SYSTEM:retrieval-hit-rate->RECORDS->PROCESS:build-task")
            .relationGroupRelationCount(2)
            .relationGroupEvidenceCount(3)
            .relationGroupDocumentCount(2)
            .kgQualityScore(0.86D)
            .kgQualityReasons("groundedEvidence,crossDocument")
            .kgNoiseReasons("")
            .kgPagerank(0.17D)
            .kgRankPosition(2)
            .kgDegree(3)
            .crossDocumentCommunityKey("xdoc-community:retrieval-hit-rate-build-task")
            .crossDocumentCommunityEntityCount(2)
            .crossDocumentCommunityRelationGroupCount(1)
            .crossDocumentCommunityEvidenceCount(3)
            .crossDocumentCommunityDocumentCount(2)
            .evidenceId(300L)
            .quoteText("检索命中率突然下降时，需要先确认最近一次构建任务是否成功。")
            .sectionPath("14.1 场景一：检索命中率突然下降")
            .graphPath("检索命中率")
            .queryPlanSource("java.graph_query_profile.v2,llm.controlled.query_plan.v1")
            .queryPlanAnswerTypes("SYSTEM")
            .queryPlanEntities("检索命中率")
            .nHopSeedEntityId(200L)
            .nHopSeedEntityName("检索命中率")
            .nHopPath("检索命中率 --RECORDS--> 构建任务")
            .score(0.92D)
            .build();
        GraphRagRetrievalChannel channel = new GraphRagRetrievalChannel(
            new StaticGraphRagSearchService(List.of(entityEvidence)),
            new StaticDocumentKnowledgeService(),
            new ChatRagProperties(),
            new DocumentRetrieveRequestFactory()
        );

        RetrievalChannelResult result = channel.retrieve(
            "检索命中率突然下降的处理步骤有哪些？",
            ConversationExecutionPlan.builder().selectedDocumentId(100L).selectedTaskId(900L).build()
        );

        assertThat(result.getDocuments()).hasSize(1);
        Document document = result.getDocuments().get(0);
        assertThat(document.getId()).isEqualTo("graphrag-300");
        assertThat(document.getText()).contains("检索命中率突然下降");
        assertThat(document.getText()).contains("关系类型：RECORDS");
        assertThat(document.getText()).contains("不能单独证明目标事项的执行者、审批者、负责人或决策主体");
        assertThat(document.getText()).contains("n-hop路径：检索命中率 --RECORDS--> 构建任务");
        assertThat(document.getText()).contains("跨文档社区：2 份文档 / 1 个关系组 / 3 条证据支撑");
        assertThat(document.getMetadata().values()).doesNotContainNull();
        assertThat(document.getMetadata())
            .containsEntry(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "GRAPH_RAG")
            .containsEntry(DocumentKnowledgeMetadataKeys.CHANNEL, "graph-rag")
            .containsEntry(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, 100L)
            .containsEntry(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, "星联智服全渠道客服平台上线与运营管理手册.md")
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_ENTITY_ID, 200L)
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_ENTITY_NAME, "检索命中率")
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_KEY, "SYSTEM:retrieval-hit-rate")
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_NAME, "检索命中率")
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_COUNT, 2)
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_CANONICAL_DOCUMENT_COUNT, 2)
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY, "SYSTEM:retrieval-hit-rate->RECORDS->PROCESS:build-task")
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_RELATION_COUNT, 2)
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_EVIDENCE_COUNT, 3)
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_DOCUMENT_COUNT, 2)
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_SOURCE, "java.graph_query_profile.v2,llm.controlled.query_plan.v1")
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_ANSWER_TYPES, "SYSTEM")
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_ENTITIES, "检索命中率")
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_NHOP_SEED_ENTITY_ID, 200L)
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_NHOP_SEED_ENTITY_NAME, "检索命中率")
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_NHOP_PATH, "检索命中率 --RECORDS--> 构建任务")
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_QUALITY_SCORE, 0.86D)
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_QUALITY_REASONS, "groundedEvidence,crossDocument")
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_NOISE_REASONS, "")
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_PAGERANK, 0.17D)
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_RANK_POSITION, 2)
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_DEGREE, 3)
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY, "xdoc-community:retrieval-hit-rate-build-task")
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_ENTITY_COUNT, 2)
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_RELATION_GROUP_COUNT, 1)
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_EVIDENCE_COUNT, 3)
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_DOCUMENT_COUNT, 2)
            .doesNotContainKeys(
                DocumentKnowledgeMetadataKeys.KG_RELATION_ID,
                DocumentKnowledgeMetadataKeys.KG_RELATED_ENTITY_ID,
                DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID,
                DocumentKnowledgeMetadataKeys.CHUNK_ID
            );
    }

    @Test
    void graphSearchKeepsHistoryHintsOutOfFreshTopicQueryAndEvidenceText() {
        GraphRagSearchResult evidence = GraphRagSearchResult.builder()
            .documentId(100L)
            .taskId(900L)
            .entityId(200L)
            .entityName("AuditTrail")
            .relationId(300L)
            .relationType("RECORDS")
            .relatedEntityId(201L)
            .relatedEntityName("权限审批")
            .evidenceId(400L)
            .quoteText("AuditTrail 需记录权限申请、权限审批、权限回收和临时权限延长。")
            .sectionPath("二、审计记录要求")
            .graphPath("关系匹配：AuditTrail --RECORDS--> 权限审批")
            .score(0.91D)
            .build();
        CapturingGraphRagSearchService graphRagSearchService = new CapturingGraphRagSearchService(List.of(evidence));
        GraphRagRetrievalChannel channel = new GraphRagRetrievalChannel(
            graphRagSearchService,
            new StaticDocumentKnowledgeService(),
            new ChatRagProperties(),
            new DocumentRetrieveRequestFactory()
        );
        ConversationExecutionPlan plan = ConversationExecutionPlan.builder()
            .selectedDocumentId(100L)
            .selectedTaskId(900L)
            .historyPlanningContext(HistoryPlanningContext.builder()
                .queryContextHints(List.of("审计系统", "AuditTrail", "权限审批"))
                .build())
            .build();

        RetrievalChannelResult result = channel.retrieve("这个相关部门是谁？", plan);

        assertThat(graphRagSearchService.question)
            .isEqualTo("这个相关部门是谁？")
            .doesNotContain("审计系统", "AuditTrail", "权限审批");
        assertThat(graphRagSearchService.documentIds).containsExactly(100L);
        assertThat(graphRagSearchService.taskIds).containsExactly(900L);
        assertThat(result.getDocuments()).hasSize(1);
        assertThat(result.getDocuments().get(0).getText())
            .contains("用户问题：这个相关部门是谁？")
            .doesNotContain("用户问题：这个相关部门是谁？ 审计系统 AuditTrail 权限审批");
    }

    @Test
    void communityReportCandidateKeepsIndependentIdWhenItSharesEvidenceWithRelationCandidate() {
        GraphRagSearchResult communityReport = GraphRagSearchResult.builder()
            .documentId(100L)
            .taskId(900L)
            .chunkId(700L)
            .parentBlockId(800L)
            .evidenceId(400L)
            .quoteText("AuditTrail 需记录权限申请、权限审批、权限回收和临时权限延长。")
            .sectionPath("二、审计记录要求")
            .graphPath("跨文档社区：审计系统 / 权限申请 / 权限审批")
            .communityTitle("跨文档图谱社区：审计系统 / 权限申请 / 权限审批")
            .communitySummary("社区报告覆盖审计系统、权限申请、权限审批和留痕证据。")
            .crossDocumentCommunityKey("xdoc-community:concept审计系统")
            .crossDocumentCommunityEntityCount(6)
            .crossDocumentCommunityRelationGroupCount(5)
            .crossDocumentCommunityEvidenceCount(5)
            .crossDocumentCommunityDocumentCount(2)
            .relationGroupKey("CONCEPT:审计系统->RECORDS->CONCEPT:权限申请")
            .kgQualityScore(0.92D)
            .score(1.20D)
            .build();
        GraphRagSearchResult relationEvidence = GraphRagSearchResult.builder()
            .documentId(100L)
            .taskId(900L)
            .chunkId(700L)
            .parentBlockId(800L)
            .entityId(200L)
            .entityName("AuditTrail")
            .relationId(300L)
            .relationType("RECORDS")
            .relatedEntityId(201L)
            .relatedEntityName("权限申请")
            .evidenceId(400L)
            .quoteText("AuditTrail 需记录权限申请、权限审批、权限回收和临时权限延长。")
            .sectionPath("二、审计记录要求")
            .graphPath("一跳：AuditTrail --RECORDS--> 权限申请")
            .relationGroupKey("CONCEPT:审计系统->RECORDS->CONCEPT:权限申请")
            .crossDocumentCommunityKey("xdoc-community:concept审计系统")
            .crossDocumentCommunityEntityCount(6)
            .crossDocumentCommunityRelationGroupCount(5)
            .crossDocumentCommunityEvidenceCount(5)
            .crossDocumentCommunityDocumentCount(2)
            .kgQualityScore(0.91D)
            .score(1.10D)
            .build();
        GraphRagRetrievalChannel channel = new GraphRagRetrievalChannel(
            new StaticGraphRagSearchService(List.of(communityReport, relationEvidence)),
            new StaticDocumentKnowledgeService(),
            new ChatRagProperties(),
            new DocumentRetrieveRequestFactory()
        );

        RetrievalChannelResult result = channel.retrieve(
            "审计系统相关的跨文档图谱社区总结是什么？",
            ConversationExecutionPlan.builder().selectedDocumentId(100L).selectedTaskId(900L).build()
        );

        assertThat(result.getDocuments()).hasSize(2);
        assertThat(result.getDocuments())
            .extracting(Document::getId)
            .containsExactly(
                "graphrag-xcommunity-xdoc-community-concept-evidence-400",
                "graphrag-400"
            );
        Document communityDocument = result.getDocuments().get(0);
        assertThat(communityDocument.getMetadata())
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY, "xdoc-community:concept审计系统")
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_TITLE, "跨文档图谱社区：审计系统 / 权限申请 / 权限审批")
            .doesNotContainKey(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID);
        assertThat(communityDocument.getText())
            .contains("图谱路径：跨文档社区：审计系统 / 权限申请 / 权限审批")
            .contains("社区报告：社区报告覆盖审计系统、权限申请、权限审批和留痕证据。")
            .contains("跨文档社区：2 份文档 / 5 个关系组 / 5 条证据支撑");
        assertThat(result.getDocuments().get(1).getMetadata())
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_RELATION_ID, 300L)
            .containsEntry(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, 800L);
    }

    private record StaticGraphRagSearchService(List<GraphRagSearchResult> results) implements GraphRagSearchService {

        @Override
        public List<GraphRagSearchResult> search(String question,
                                                 List<Long> documentIds,
                                                 List<Long> taskIds,
                                                 int topK,
                                                 int maxHops) {
            return results;
        }
    }

    private static class CapturingGraphRagSearchService implements GraphRagSearchService {

        private final List<GraphRagSearchResult> results;

        private String question;

        private List<Long> documentIds;

        private List<Long> taskIds;

        private CapturingGraphRagSearchService(List<GraphRagSearchResult> results) {
            this.results = results;
        }

        @Override
        public List<GraphRagSearchResult> search(String question,
                                                 List<Long> documentIds,
                                                 List<Long> taskIds,
                                                 int topK,
                                                 int maxHops) {
            this.question = question;
            this.documentIds = documentIds;
            this.taskIds = taskIds;
            return results;
        }
    }

    private static class StaticDocumentKnowledgeService implements DocumentKnowledgeService {

        @Override
        public List<KnowledgeDocumentDescriptor> listRetrievableDocuments() {
            return List.of(new KnowledgeDocumentDescriptor(
                100L,
                "星联智服全渠道客服平台上线与运营管理手册.md",
                900L,
                1L,
                "测试知识库"
            ));
        }

        @Override
        public List<KnowledgeDocumentDescriptor> listRetrievableDocumentsByKnowledgeBaseIds(java.util.Collection<Long> knowledgeBaseIds) {
            return listRetrievableDocuments();
        }

        @Override
        public List<Document> vectorSearch(DocumentRetrieveRequest request) {
            return List.of();
        }

        @Override
        public List<Document> keywordSearch(DocumentRetrieveRequest request) {
            return List.of();
        }

        @Override
        public List<Document> elevateToParentBlocks(List<Document> childDocuments, int maxChars) {
            return childDocuments;
        }
    }
}
