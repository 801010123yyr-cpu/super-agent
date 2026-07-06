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

    @Test
    void repairDoesNotPromoteNotApplicableEvidence() {
        CapturingRagToolsClient ragToolsClient = new CapturingRagToolsClient();
        ragToolsClient.responseEvidenceId = "2";
        ragToolsClient.responseChunkId = 202L;
        ragToolsClient.responseQuoteText = "当前目标对象有明确证据。";
        RagCitationRepairService service = new RagCitationRepairService(ragToolsClient);

        SearchReference notApplicable = documentReference("1", 101L, "相似对象的处理步骤。", 3, "[0,0,10,10]");
        notApplicable.setFinalSelectionReason("FILTERED_NOT_APPLICABLE_TO_TARGET_ENTITY");
        notApplicable.setEvidenceApplicabilityStatus("NOT_APPLICABLE");
        notApplicable.setEvidenceApplicabilityReason("only excluded entity matched");
        SearchReference applicable = documentReference("2", 202L, "当前目标对象有明确证据。", 4, "[0,0,20,20]");

        List<SearchReference> result = service.repair(
            "当前目标对象有明确证据。",
            List.of(notApplicable, applicable),
            null,
            "RETRIEVAL"
        );

        assertThat(ragToolsClient.lastRequest.getEvidences())
            .extracting(RagToolsCitationRepairRequest.Evidence::getId)
            .containsExactly("2");
        assertThat(result).extracting(SearchReference::getReferenceId)
            .containsExactly("2");
        assertThat(result.get(0).isCitationRepaired()).isTrue();
        assertThat(result.get(0).getChunkId()).isEqualTo(202L);
    }

    @Test
    void repairSkipsRagToolsWhenOnlyNotApplicableDocumentEvidenceExists() {
        CapturingRagToolsClient ragToolsClient = new CapturingRagToolsClient();
        RagCitationRepairService service = new RagCitationRepairService(ragToolsClient);

        SearchReference notApplicable = documentReference("1", 101L, "相似对象的处理步骤。", 3, "[0,0,10,10]");
        notApplicable.setEvidenceApplicabilityStatus("NOT_APPLICABLE");
        SearchReference webReference = new SearchReference("网页", "https://example.com", "外部搜索结果");

        List<SearchReference> result = service.repair(
            "当前目标对象没有明确证据。",
            List.of(notApplicable, webReference),
            null,
            "RETRIEVAL"
        );

        assertThat(ragToolsClient.lastRequest).isNull();
        assertThat(result).extracting(SearchReference::getSourceType)
            .containsExactly("WEB");
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
        private String responseEvidenceId = "1";
        private Long responseChunkId = 101L;
        private String responseQuoteText = "出差结束后 10 个工作日内提交报销。";

        private CapturingRagToolsClient() {
            super(new RagToolsProperties(), new com.fasterxml.jackson.databind.ObjectMapper());
        }

        @Override
        public RagToolsCitationRepairResponse repairCitations(RagToolsCitationRepairRequest request) {
            this.lastRequest = request;
            RagToolsCitationRepairResponse response = new RagToolsCitationRepairResponse();
            RagToolsCitationRepairResponse.Result result = new RagToolsCitationRepairResponse.Result();
            result.setEvidenceId(responseEvidenceId);
            result.setAnswerSegment("报销时限是出差结束后 10 个工作日内提交。");
            result.setSegmentIndex(1);
            result.setQuoteText(responseQuoteText);
            result.setScore(0.87D);
            result.setRank(1);
            result.setChunkId(responseChunkId);
            result.setPageNo(3);
            result.setPageRange("3");
            result.setBboxJson("[0,0,10,10]");
            response.setCitations(List.of(result));
            return response;
        }
    }
}
