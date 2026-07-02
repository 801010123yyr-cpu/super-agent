package org.javaup.ai.chatagent.rag.service;

import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.javaup.ai.ragtools.client.RagToolsClient;
import org.javaup.ai.ragtools.config.RagToolsProperties;
import org.javaup.ai.ragtools.model.RagToolsRerankRequest;
import org.javaup.ai.ragtools.model.RagToolsRerankResponse;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagRerankServiceTest {

    @Test
    void rerankUsesRequestScopedCandidateIds() {
        CapturingRagToolsClient ragToolsClient = new CapturingRagToolsClient();
        ChatRagProperties properties = new ChatRagProperties();
        properties.setFinalTopK(2);
        RagRerankService rerankService = new RagRerankService(ragToolsClient, properties);

        Document left = document("same-document", "生产发布需要准备回滚方案。");
        Document right = document("same-document", "出差结束后 10 个工作日内提交报销。");
        Document third = document("same-document", "差旅标准按城市级别执行。");

        List<Document> result = rerankService.rerank("报销时限", List.of(left, right, third));

        assertThat(ragToolsClient.lastRequest.getTopK()).isEqualTo(3);
        assertThat(ragToolsClient.lastRequest.getCandidates())
            .extracting(RagToolsRerankRequest.Candidate::getId)
            .containsExactly("0:same-document", "1:same-document", "2:same-document");
        assertThat(result).containsExactly(right, left, third);
        assertThat(result.get(0).getMetadata())
            .containsEntry(DocumentKnowledgeMetadataKeys.RERANK_SCORE, 0.91D)
            .containsEntry(DocumentKnowledgeMetadataKeys.RERANK_RANK, 1)
            .containsEntry(DocumentKnowledgeMetadataKeys.RERANK_MODEL, "rag-tools")
            .containsEntry(DocumentKnowledgeMetadataKeys.RERANK_STATUS, "SUCCESS")
            .containsEntry(DocumentKnowledgeMetadataKeys.RERANK_CANDIDATE_COUNT, 3)
            .containsEntry(DocumentKnowledgeMetadataKeys.RERANK_TOP_K, 3);
    }

    private static Document document(String id, String text) {
        return Document.builder()
            .id(id)
            .text(text)
            .metadata(new LinkedHashMap<>())
            .build();
    }

    private static class CapturingRagToolsClient extends RagToolsClient {

        private RagToolsRerankRequest lastRequest;

        private CapturingRagToolsClient() {
            super(new RagToolsProperties(), new com.fasterxml.jackson.databind.ObjectMapper());
        }

        @Override
        public RagToolsRerankResponse rerank(RagToolsRerankRequest request) {
            this.lastRequest = request;

            RagToolsRerankResponse response = new RagToolsRerankResponse();
            RagToolsRerankResponse.Result first = new RagToolsRerankResponse.Result();
            first.setId(request.getCandidates().get(1).getId());
            first.setScore(0.91D);
            first.setRank(1);
            RagToolsRerankResponse.Result second = new RagToolsRerankResponse.Result();
            second.setId(request.getCandidates().get(0).getId());
            second.setScore(0.31D);
            second.setRank(2);
            RagToolsRerankResponse.Result third = new RagToolsRerankResponse.Result();
            third.setId(request.getCandidates().get(2).getId());
            third.setScore(0.11D);
            third.setRank(3);
            response.setResults(List.of(first, second, third));
            return response;
        }
    }
}
