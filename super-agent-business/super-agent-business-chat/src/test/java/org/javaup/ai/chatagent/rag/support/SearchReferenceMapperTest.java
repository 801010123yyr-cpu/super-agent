package org.javaup.ai.chatagent.rag.support;

import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SearchReferenceMapperTest {

    @Test
    void fromDocumentKeepsGraphRagQualityMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "DOCUMENT");
        metadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, "hybrid");
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, "O6跨文档图谱-审计证据规范A.md");
        metadata.put(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_NAME, "审计系统");
        metadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY, "CONCEPT:审计系统->RECORDS->CONCEPT:权限申请");
        metadata.put(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID, 5001L);
        metadata.put(DocumentKnowledgeMetadataKeys.KG_QUALITY_SCORE, 0.81D);
        metadata.put(DocumentKnowledgeMetadataKeys.KG_QUALITY_REASONS, "groundedEvidence,strongRelationSource");
        metadata.put(DocumentKnowledgeMetadataKeys.KG_NOISE_REASONS, "");
        metadata.put(DocumentKnowledgeMetadataKeys.KG_PAGERANK, 0.17D);
        metadata.put(DocumentKnowledgeMetadataKeys.KG_RANK_POSITION, 2);
        metadata.put(DocumentKnowledgeMetadataKeys.KG_DEGREE, 3);

        Document document = Document.builder()
            .id("parent-1001")
            .text("AuditTrail 需记录权限申请。")
            .metadata(metadata)
            .build();

        SearchReference reference = SearchReferenceMapper.fromDocument(document, 1, "审计系统有哪些权限相关要求？", 1);

        assertThat(reference.getChannel()).isEqualTo("hybrid");
        assertThat(reference.getKgCanonicalEntityName()).isEqualTo("审计系统");
        assertThat(reference.getKgRelationGroupKey()).isEqualTo("CONCEPT:审计系统->RECORDS->CONCEPT:权限申请");
        assertThat(reference.getKgEvidenceId()).isEqualTo(5001L);
        assertThat(reference.getKgQualityScore()).isEqualTo(0.81D);
        assertThat(reference.getKgQualityReasons()).isEqualTo("groundedEvidence,strongRelationSource");
        assertThat(reference.getKgNoiseReasons()).isEmpty();
        assertThat(reference.getKgPagerank()).isEqualTo(0.17D);
        assertThat(reference.getKgRankPosition()).isEqualTo(2);
        assertThat(reference.getKgDegree()).isEqualTo(3);
    }
}
