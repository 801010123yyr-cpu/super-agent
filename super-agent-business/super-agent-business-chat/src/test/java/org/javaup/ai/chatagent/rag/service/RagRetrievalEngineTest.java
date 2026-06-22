package org.javaup.ai.chatagent.rag.service;

import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.model.RagRetrievalContext;
import org.javaup.ai.chatagent.rag.retrieve.channel.RetrievalChannel;
import org.javaup.ai.chatagent.rag.retrieve.channel.RetrievalChannelResult;
import org.javaup.ai.manage.model.DocumentRetrieveRequest;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.javaup.enums.RetrievalChannelEnum;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class RagRetrievalEngineTest {

    @Test
    void weightedHybridUsesMetadataBoostAndRecordsChannelScores() {
        ChatRagProperties properties = new ChatRagProperties();
        properties.setRerankEnabled(false);
        properties.setMinVectorSimilarity(0D);
        properties.setKeywordRelativeScoreFloor(0D);
        properties.setCandidateTopK(10);
        properties.setFinalTopK(2);
        properties.getHybrid().setOriginalScoreWeight(0D);
        properties.getHybrid().setMetadataBoostWeight(1D);

        Document vectorFirst = document("doc-a", "普通流程说明", 0.91D);
        Document vectorSecond = document("doc-b", "报销材料说明", 0.90D);
        Document keywordFirst = document("doc-a", "普通流程说明", 10D);
        Document keywordSecond = document("doc-b", "报销时限是 10 个工作日", 10D);
        keywordSecond.getMetadata().put(DocumentKnowledgeMetadataKeys.TITLE, "报销时限");
        keywordSecond.getMetadata().put(DocumentKnowledgeMetadataKeys.KEYWORDS, "报销 时限");

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            RagRetrievalEngine engine = new RagRetrievalEngine(
                List.of(
                    new StaticRetrievalChannel(RetrievalChannelEnum.VECTOR.getName(), List.of(vectorFirst, vectorSecond)),
                    new StaticRetrievalChannel(RetrievalChannelEnum.KEYWORD.getName(), List.of(keywordFirst, keywordSecond))
                ),
                properties,
                null,
                new PassThroughDocumentKnowledgeService(),
                executorService
            );

            ConversationExecutionPlan plan = ConversationExecutionPlan.builder()
                .mode(ExecutionMode.RETRIEVAL)
                .retrievalQuestion("报销时限")
                .retrievalSubQuestions(List.of("报销时限"))
                .build();

            RagRetrievalContext context = engine.retrieve(plan, null);

            List<Document> documents = context.getSubQuestionEvidenceList().get(0).getDocuments();
            assertThat(documents).extracting(Document::getId).containsExactly("doc-b", "doc-a");
            assertThat(context.getUsedChannels()).containsExactlyInAnyOrder(
                RetrievalChannelEnum.VECTOR.getName(),
                RetrievalChannelEnum.KEYWORD.getName()
            );

            assertThat(documents.get(0).getMetadata())
                .containsEntry(DocumentKnowledgeMetadataKeys.CHANNEL, "hybrid")
                .containsEntry(DocumentKnowledgeMetadataKeys.VECTOR_SCORE, 0.90D)
                .containsEntry(DocumentKnowledgeMetadataKeys.KEYWORD_SCORE, 10D);
            assertThat(documents.get(0).getMetadata().get(DocumentKnowledgeMetadataKeys.HYBRID_SCORE))
                .isInstanceOf(Number.class);
            assertThat(((Number) documents.get(0).getMetadata().get(DocumentKnowledgeMetadataKeys.METADATA_BOOST)).doubleValue())
                .isGreaterThan(0D);
        }
        finally {
            executorService.shutdownNow();
        }
    }

    private static Document document(String id, String text, double score) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(DocumentKnowledgeMetadataKeys.SCORE, score);
        return Document.builder()
            .id(id)
            .text(text)
            .metadata(metadata)
            .build();
    }

    private record StaticRetrievalChannel(String channelName, List<Document> documents) implements RetrievalChannel {

        @Override
        public boolean supports(ConversationExecutionPlan plan) {
            return true;
        }

        @Override
        public RetrievalChannelResult retrieve(String subQuestion, ConversationExecutionPlan plan) {
            return new RetrievalChannelResult(channelName, documents);
        }
    }

    private static class PassThroughDocumentKnowledgeService implements DocumentKnowledgeService {

        @Override
        public List<KnowledgeDocumentDescriptor> listRetrievableDocuments() {
            return List.of();
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
