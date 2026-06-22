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
            .evidenceId(300L)
            .quoteText("检索命中率突然下降时，需要先确认最近一次构建任务是否成功。")
            .sectionPath("14.1 场景一：检索命中率突然下降")
            .graphPath("检索命中率")
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
        assertThat(document.getMetadata().values()).doesNotContainNull();
        assertThat(document.getMetadata())
            .containsEntry(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "GRAPH_RAG")
            .containsEntry(DocumentKnowledgeMetadataKeys.CHANNEL, "graph-rag")
            .containsEntry(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, 100L)
            .containsEntry(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, "星联智服全渠道客服平台上线与运营管理手册.md")
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_ENTITY_ID, 200L)
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_ENTITY_NAME, "检索命中率")
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
