package org.javaup.ai.chatagent.rag.service;

import org.javaup.ai.chatagent.rag.model.EvidenceRole;
import org.javaup.ai.chatagent.rag.model.EvidenceApplicabilityResult;
import org.javaup.ai.chatagent.rag.model.QueryUnderstandingResult;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceApplicabilityServiceTest {

    private final EvidenceApplicabilityService service = new EvidenceApplicabilityService();

    @Test
    void rejectsEvidenceThatOnlyMatchesExcludedEntity() {
        QueryUnderstandingResult understanding = QueryUnderstandingResult.builder()
            .targetEntities(List.of("知识引用错误率突然升高"))
            .excludedEntities(List.of("人工转接率异常升高"))
            .negativeBoundary(true)
            .confidence(0.9D)
            .source("test")
            .build();

        Document evidence = doc("e1", "# 14.3.1\n1. 看是不是服务时间策略误配置。", 0.9D, Map.of(
            DocumentKnowledgeMetadataKeys.SECTION_PATH, "14.3.1",
            DocumentKnowledgeMetadataKeys.TITLE, "人工转接率异常升高"
        ));

        EvidenceApplicabilityResult result = service.evaluate(understanding, evidence);

        assertThat(result.isApplicable()).isFalse();
        assertThat(result.getReason()).contains("excluded");
    }

    @Test
    void keepsEvidenceWhenTargetEntityIsGrounded() {
        QueryUnderstandingResult understanding = QueryUnderstandingResult.builder()
            .targetEntities(List.of("知识引用错误率突然升高"))
            .excludedEntities(List.of("人工转接率异常升高"))
            .negativeBoundary(true)
            .confidence(0.9D)
            .source("test")
            .build();

        Document evidence = doc("e1", "文档明确说明知识引用错误率突然升高时，应先检查引用来源。", 0.9D, Map.of(
            DocumentKnowledgeMetadataKeys.SECTION_PATH, "14.4",
            DocumentKnowledgeMetadataKeys.TITLE, "知识引用错误率突然升高"
        ));

        EvidenceApplicabilityResult result = service.evaluate(understanding, evidence);

        assertThat(result.isApplicable()).isTrue();
        assertThat(result.getStatus()).isEqualTo("APPLICABLE");
    }

    @Test
    void doesNotInferEvidenceRoleFromSectionTitle() {
        QueryUnderstandingResult understanding = QueryUnderstandingResult.builder()
            .expectedEvidenceRoles(List.of(EvidenceRole.SYMPTOM))
            .confidence(0.9D)
            .source("test")
            .build();

        Document evidence = doc("cause", "父子块策略配置错误、索引任务未完成、finalTopK 被下调。", 0.9D, Map.of(
            DocumentKnowledgeMetadataKeys.SECTION_PATH, "十四、常见问题处理 > 14.1 检索命中率突然下降 > 14.1.2 可能原因",
            DocumentKnowledgeMetadataKeys.TITLE, "14.1.2 可能原因"
        ));

        EvidenceApplicabilityResult result = service.evaluate(understanding, evidence);

        assertThat(result.isApplicable()).isTrue();
        assertThat(result.getStatus()).isEqualTo(EvidenceApplicabilityResult.APPLICABLE_UNKNOWN);
        assertThat(result.getReason()).contains("evidence role is GENERAL");
        assertThat(evidence.getMetadata())
            .containsEntry(DocumentKnowledgeMetadataKeys.EVIDENCE_ROLE, "GENERAL");
    }

    @Test
    void keepsEvidenceWhenStructuredRoleMatches() {
        QueryUnderstandingResult understanding = QueryUnderstandingResult.builder()
            .expectedEvidenceRoles(List.of(EvidenceRole.SYMPTOM))
            .confidence(0.9D)
            .source("test")
            .build();

        Document evidence = doc("symptom", "日志中 finalTopK 下降明显，召回片段数量低于平时。", 0.9D, Map.of(
            DocumentKnowledgeMetadataKeys.SECTION_PATH, "十四、常见问题处理 > 14.1 检索命中率突然下降 > 14.1.1 现象",
            DocumentKnowledgeMetadataKeys.TITLE, "14.1.1 现象",
            DocumentKnowledgeMetadataKeys.EVIDENCE_ROLE, "SYMPTOM"
        ));

        EvidenceApplicabilityResult result = service.evaluate(understanding, evidence);

        assertThat(result.isApplicable()).isTrue();
        assertThat(result.getReason()).contains("evidence role matched");
        assertThat(evidence.getMetadata())
            .containsEntry(DocumentKnowledgeMetadataKeys.EVIDENCE_ROLE, "SYMPTOM");
    }

    private static Document doc(String id, String text, double score, Map<String, Object> metadata) {
        LinkedHashMap<String, Object> mergedMetadata = new LinkedHashMap<>(metadata);
        mergedMetadata.put(DocumentKnowledgeMetadataKeys.SCORE, score);
        return Document.builder()
            .id(id)
            .text(text)
            .metadata(mergedMetadata)
            .score(score)
            .build();
    }
}
