package org.javaup.ai.chatagent.rag.retrieve.channel;

import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
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
            new ChatRagProperties()
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

    private static class StaticDocumentKnowledgeService implements DocumentKnowledgeService {

        @Override
        public List<KnowledgeDocumentDescriptor> listRetrievableDocuments() {
            return List.of(new KnowledgeDocumentDescriptor(
                100L,
                "星联智服全渠道客服平台上线与运营管理手册.md",
                900L,
                "",
                "",
                "",
                ""
            ));
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
