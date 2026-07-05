package org.javaup.ai.chatagent.rag.retrieve.channel;

import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.support.SearchReferenceMapper;
import org.javaup.ai.manage.model.DocumentRetrieveRequest;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.ai.manage.model.raptor.RaptorSearchResult;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.ai.manage.service.RaptorSearchService;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RaptorRetrievalChannelTest {

    @Test
    void summaryOnlyResultIsMarkedAsWeakBackgroundEvidence() {
        RaptorRetrievalChannel channel = new RaptorRetrievalChannel(
            new StaticRaptorSearchService(List.of(RaptorSearchResult.builder()
                .documentId(10L)
                .taskId(20L)
                .raptorNodeId(3001L)
                .raptorNodeTitle("灰度上线观察摘要")
                .raptorNodeLevel(1)
                .raptorSummary("灰度上线需要观察核心质量指标。")
                .sourceStatus("SUMMARY_ONLY")
                .sectionPath("上线治理 / 灰度观察")
                .score(0.82D)
                .build())),
            new StaticDocumentKnowledgeService(),
            new ChatRagProperties()
        );

        RetrievalChannelResult result = channel.retrieve(
            "总结灰度上线观察规则",
            ConversationExecutionPlan.builder().selectedDocumentId(10L).selectedTaskId(20L).build()
        );

        assertThat(result.getDocuments()).hasSize(1);
        Document document = result.getDocuments().get(0);
        assertThat(document.getId()).isEqualTo("raptor-3001-summary");
        assertThat(document.getText()).contains("本证据仅作为摘要背景");
        assertThat(document.getMetadata())
            .containsEntry(DocumentKnowledgeMetadataKeys.RAPTOR_NODE_ID, 3001L)
            .containsEntry(DocumentKnowledgeMetadataKeys.RAPTOR_SOURCE_STATUS, "SUMMARY_ONLY")
            .containsEntry(DocumentKnowledgeMetadataKeys.CHUNK_TYPE, "RAPTOR_SUMMARY")
            .containsEntry(DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET, "灰度上线需要观察核心质量指标。");
        assertThat(document.getMetadata()).doesNotContainKey(DocumentKnowledgeMetadataKeys.CHUNK_ID);

        SearchReference reference = SearchReferenceMapper.fromDocument(document, 1, "总结灰度上线观察规则", 1);
        assertThat(reference.getRaptorNodeId()).isEqualTo(3001L);
        assertThat(reference.getRaptorSourceStatus()).isEqualTo("SUMMARY_ONLY");
        assertThat(reference.uniqueKey()).isEqualTo("RAPTOR:3001:SUMMARY_ONLY");
    }

    private record StaticRaptorSearchService(List<RaptorSearchResult> results) implements RaptorSearchService {

        @Override
        public List<RaptorSearchResult> search(String question,
                                               List<Long> documentIds,
                                               List<Long> taskIds,
                                               int topK,
                                               int sourceChunkTopK) {
            return results;
        }
    }

    private static class StaticDocumentKnowledgeService implements DocumentKnowledgeService {

        @Override
        public List<KnowledgeDocumentDescriptor> listRetrievableDocuments() {
            return List.of(new KnowledgeDocumentDescriptor(
                10L,
                "上线治理手册.md",
                20L,
                1L,
                "测试知识库"
            ));
        }

        @Override
        public List<KnowledgeDocumentDescriptor> listRetrievableDocumentsByKnowledgeBaseIds(Collection<Long> knowledgeBaseIds) {
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
