package org.javaup.ai.chatagent.rag.retrieve.channel;

import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.service.DocumentRetrieveRequestFactory;
import org.javaup.ai.manage.model.DocumentRetrieveRequest;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentScopeRetrievalChannelTest {

    @Test
    void vectorChannelSupportsMultiDocumentRetrievalScope() {
        CapturingDocumentKnowledgeService documentKnowledgeService = new CapturingDocumentKnowledgeService();
        VectorRetrievalChannel channel = new VectorRetrievalChannel(
            documentKnowledgeService,
            new ChatRagProperties(),
            new DocumentRetrieveRequestFactory()
        );
        ConversationExecutionPlan plan = multiDocumentPlan();

        assertThat(channel.supports(plan)).isTrue();

        RetrievalChannelResult result = channel.retrieve("审计系统中负责权限审批的部门是谁？", plan);

        assertThat(result.getDocuments()).hasSize(1);
        assertThat(documentKnowledgeService.vectorRequest).isNotNull();
        assertThat(documentKnowledgeService.vectorRequest.getDocumentId()).isNull();
        assertThat(documentKnowledgeService.vectorRequest.getTaskId()).isNull();
        assertThat(documentKnowledgeService.vectorRequest.getDocumentIds()).containsExactly(10L, 11L);
        assertThat(documentKnowledgeService.vectorRequest.getTaskIds()).containsExactly(20L, 21L);
    }

    @Test
    void keywordChannelSupportsMultiDocumentRetrievalScopeWhenEnabled() {
        CapturingDocumentKnowledgeService documentKnowledgeService = new CapturingDocumentKnowledgeService();
        ChatRagProperties properties = new ChatRagProperties();
        KeywordRetrievalChannel channel = new KeywordRetrievalChannel(
            documentKnowledgeService,
            properties,
            new DocumentRetrieveRequestFactory()
        );
        ConversationExecutionPlan plan = multiDocumentPlan();

        assertThat(channel.supports(plan)).isTrue();

        RetrievalChannelResult result = channel.retrieve("审计系统中负责权限审批的部门是谁？", plan);

        assertThat(result.getDocuments()).hasSize(1);
        assertThat(documentKnowledgeService.keywordRequest).isNotNull();
        assertThat(documentKnowledgeService.keywordRequest.getDocumentId()).isNull();
        assertThat(documentKnowledgeService.keywordRequest.getTaskId()).isNull();
        assertThat(documentKnowledgeService.keywordRequest.getDocumentIds()).containsExactly(10L, 11L);
        assertThat(documentKnowledgeService.keywordRequest.getTaskIds()).containsExactly(20L, 21L);
    }

    @Test
    void keywordChannelStillHonorsEnabledSwitch() {
        ChatRagProperties properties = new ChatRagProperties();
        properties.setKeywordChannelEnabled(false);
        KeywordRetrievalChannel channel = new KeywordRetrievalChannel(
            new CapturingDocumentKnowledgeService(),
            properties,
            new DocumentRetrieveRequestFactory()
        );

        assertThat(channel.supports(multiDocumentPlan())).isFalse();
    }

    @Test
    void channelsRejectPlanWithoutCompleteDocumentScope() {
        VectorRetrievalChannel vectorChannel = new VectorRetrievalChannel(
            new CapturingDocumentKnowledgeService(),
            new ChatRagProperties(),
            new DocumentRetrieveRequestFactory()
        );
        KeywordRetrievalChannel keywordChannel = new KeywordRetrievalChannel(
            new CapturingDocumentKnowledgeService(),
            new ChatRagProperties(),
            new DocumentRetrieveRequestFactory()
        );
        ConversationExecutionPlan noTaskScope = ConversationExecutionPlan.builder()
            .retrievalDocumentIds(List.of(10L, 11L))
            .build();

        assertThat(vectorChannel.supports(noTaskScope)).isFalse();
        assertThat(keywordChannel.supports(noTaskScope)).isFalse();
    }

    private static ConversationExecutionPlan multiDocumentPlan() {
        return ConversationExecutionPlan.builder()
            .retrievalDocumentIds(List.of(10L, 11L))
            .retrievalTaskIds(List.of(20L, 21L))
            .build();
    }

    private static class CapturingDocumentKnowledgeService implements DocumentKnowledgeService {

        private DocumentRetrieveRequest vectorRequest;

        private DocumentRetrieveRequest keywordRequest;

        @Override
        public List<KnowledgeDocumentDescriptor> listRetrievableDocuments() {
            return List.of();
        }

        @Override
        public List<KnowledgeDocumentDescriptor> listRetrievableDocumentsByKnowledgeBaseIds(java.util.Collection<Long> knowledgeBaseIds) {
            return List.of();
        }

        @Override
        public List<Document> vectorSearch(DocumentRetrieveRequest request) {
            this.vectorRequest = request;
            return List.of(Document.builder()
                .id("vector-1")
                .text("vector evidence")
                .metadata(Map.of())
                .build());
        }

        @Override
        public List<Document> keywordSearch(DocumentRetrieveRequest request) {
            this.keywordRequest = request;
            return List.of(Document.builder()
                .id("keyword-1")
                .text("keyword evidence")
                .metadata(Map.of())
                .build());
        }

        @Override
        public List<Document> elevateToParentBlocks(List<Document> childDocuments, int maxChars) {
            return childDocuments;
        }
    }
}
