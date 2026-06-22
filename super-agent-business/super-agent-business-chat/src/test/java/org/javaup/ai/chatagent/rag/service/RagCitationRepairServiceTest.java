package org.javaup.ai.chatagent.rag.service;

import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.ragtools.client.RagToolsClient;
import org.javaup.ai.ragtools.config.RagToolsProperties;
import org.javaup.ai.ragtools.model.RagToolsCitationRepairRequest;
import org.javaup.ai.ragtools.model.RagToolsCitationRepairResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagCitationRepairServiceTest {

    @Test
    void repairKeepsMatchedDocumentReferencesAndRemovesUnmatchedDocumentReferences() {
        CapturingRagToolsClient ragToolsClient = new CapturingRagToolsClient();
        RagCitationRepairService service = new RagCitationRepairService(ragToolsClient);

        SearchReference matched = documentReference("1", 101L, "出差结束后 10 个工作日内提交报销。", 3, "[0,0,10,10]");
        SearchReference unmatched = documentReference("2", 102L, "生产发布需要准备回滚方案。", 5, "[0,0,20,20]");
        SearchReference webReference = new SearchReference("网页", "https://example.com", "外部搜索结果");

        List<SearchReference> result = service.repair(
            "报销时限是出差结束后 10 个工作日内提交。",
            List.of(matched, unmatched, webReference),
            null,
            "RETRIEVAL"
        );

        assertThat(ragToolsClient.lastRequest.getEvidences())
            .extracting(RagToolsCitationRepairRequest.Evidence::getId)
            .containsExactly("1", "2");
        assertThat(result).extracting(SearchReference::getReferenceId)
            .containsExactly(null, "1");
        assertThat(result.get(1).isCitationRepaired()).isTrue();
        assertThat(result.get(1).getAnswerSegment()).isEqualTo("报销时限是出差结束后 10 个工作日内提交。");
        assertThat(result.get(1).getQuoteText()).isEqualTo("出差结束后 10 个工作日内提交报销。");
        assertThat(result.get(1).getPageNo()).isEqualTo(3);
        assertThat(result.get(1).getBboxJson()).isEqualTo("[0,0,10,10]");
    }

    private static SearchReference documentReference(String referenceId,
                                                     Long chunkId,
                                                     String snippet,
                                                     Integer pageNo,
                                                     String bboxJson) {
        SearchReference reference = new SearchReference();
        reference.setReferenceId(referenceId);
        reference.setSourceType("DOCUMENT");
        reference.setDocumentId(10L);
        reference.setDocumentName("制度文档");
        reference.setChunkId(chunkId);
        reference.setSnippet(snippet);
        reference.setPageNo(pageNo);
        reference.setPageRange(String.valueOf(pageNo));
        reference.setBboxJson(bboxJson);
        return reference;
    }

    private static class CapturingRagToolsClient extends RagToolsClient {

        private RagToolsCitationRepairRequest lastRequest;

        private CapturingRagToolsClient() {
            super(new RagToolsProperties());
        }

        @Override
        public RagToolsCitationRepairResponse repairCitations(RagToolsCitationRepairRequest request) {
            this.lastRequest = request;
            RagToolsCitationRepairResponse response = new RagToolsCitationRepairResponse();
            RagToolsCitationRepairResponse.Result result = new RagToolsCitationRepairResponse.Result();
            result.setEvidenceId("1");
            result.setAnswerSegment("报销时限是出差结束后 10 个工作日内提交。");
            result.setSegmentIndex(1);
            result.setQuoteText("出差结束后 10 个工作日内提交报销。");
            result.setScore(0.87D);
            result.setRank(1);
            result.setChunkId(101L);
            result.setPageNo(3);
            result.setPageRange("3");
            result.setBboxJson("[0,0,10,10]");
            response.setCitations(List.of(result));
            return response;
        }
    }
}
