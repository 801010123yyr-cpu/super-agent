package org.javaup.ai.chatagent.rag.support;

import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.rag.model.CitationEvidenceType;
import org.javaup.ai.chatagent.rag.model.EvidenceIdentity;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceIdentityResolverTest {

    @Test
    void titleAndBodyUnderSameParentHaveDifferentCitationIdentity() {
        Document title = document("title", "# 14.1.2 可能原因", metadata(
            DocumentKnowledgeMetadataKeys.DOCUMENT_ID, 10L,
            DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, 9001L,
            DocumentKnowledgeMetadataKeys.CHUNK_ID, 1001L,
            DocumentKnowledgeMetadataKeys.CHUNK_TYPE, "TITLE"
        ));
        Document body = document("body", "1. 新版本切块异常。\n2. 向量索引构建不完整。", metadata(
            DocumentKnowledgeMetadataKeys.DOCUMENT_ID, 10L,
            DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, 9001L,
            DocumentKnowledgeMetadataKeys.CHUNK_ID, 1002L,
            DocumentKnowledgeMetadataKeys.CHUNK_TYPE, "TEXT"
        ));

        assertThat(EvidenceIdentityResolver.citationIdentity(title)).isNull();
        EvidenceIdentity bodyIdentity = EvidenceIdentityResolver.citationIdentity(body);
        assertThat(bodyIdentity.type()).isEqualTo(CitationEvidenceType.CHUNK);
        assertThat(bodyIdentity.value()).isEqualTo("CHUNK:10:1002");
        assertThat(EvidenceIdentityResolver.contextIdentity(title).value()).isEqualTo("PARENT:10:9001");
        assertThat(EvidenceIdentityResolver.contextIdentity(body).value()).isEqualTo("PARENT:10:9001");
        assertThat(EvidenceIdentityResolver.sameCitationEvidence(title, body)).isFalse();
        assertThat(EvidenceIdentityResolver.sameContext(title, body)).isTrue();
    }

    @Test
    void searchReferenceTitleChunkIsContextOnlyEvenWhenChunkIdExists() {
        SearchReference reference = new SearchReference();
        reference.setSourceType("DOCUMENT");
        reference.setDocumentId(10L);
        reference.setParentBlockId(9001L);
        reference.setChunkId(1001L);
        reference.setChunkType("TITLE");

        assertThat(EvidenceIdentityResolver.citationIdentity(reference)).isNull();
        assertThat(EvidenceIdentityResolver.isContextOnly(reference)).isTrue();
        assertThat(reference.uniqueKey()).isEqualTo("PARENT:10:9001");
    }

    @Test
    void graphRagWrapperIsContextOnlyButQuoteSourceIsCitationCapable() {
        Document wrapper = document("kg-wrapper", "[GraphRAG entity] 服务 A 关联团队 B", metadata(
            DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "GRAPH_RAG",
            DocumentKnowledgeMetadataKeys.CHANNEL, "graph-rag",
            DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID, 3001L
        ));
        Document quote = document("kg-quote", "原文：服务 A 由团队 B 维护。", metadata(
            DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "GRAPH_RAG",
            DocumentKnowledgeMetadataKeys.CHANNEL, "graph-rag",
            DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID, 3001L,
            DocumentKnowledgeMetadataKeys.CHUNK_ID, 5001L,
            DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET, "服务 A 由团队 B 维护。"
        ));

        assertThat(EvidenceIdentityResolver.citationIdentity(wrapper)).isNull();
        assertThat(EvidenceIdentityResolver.isContextOnly(wrapper)).isTrue();
        EvidenceIdentity quoteIdentity = EvidenceIdentityResolver.citationIdentity(quote);
        assertThat(quoteIdentity.type()).isEqualTo(CitationEvidenceType.KG_QUOTE_SOURCE);
        assertThat(quoteIdentity.value()).isEqualTo("KG_QUOTE:3001:CHUNK:5001");
    }

    @Test
    void raptorSummaryIsContextOnlyButSourceChunkIsCitationCapable() {
        Document summary = document("raptor-summary", "RAPTOR 摘要：上线后需要观察指标。", metadata(
            DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "RAPTOR",
            DocumentKnowledgeMetadataKeys.CHANNEL, "raptor",
            DocumentKnowledgeMetadataKeys.RAPTOR_NODE_ID, 7001L,
            DocumentKnowledgeMetadataKeys.RAPTOR_SOURCE_STATUS, "SUMMARY_ONLY",
            DocumentKnowledgeMetadataKeys.CHUNK_TYPE, "RAPTOR_SUMMARY"
        ));
        Document sourceChunk = document("raptor-source", "原文：上线后观察回答准确率和人工转接率。", metadata(
            DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "RAPTOR",
            DocumentKnowledgeMetadataKeys.CHANNEL, "raptor",
            DocumentKnowledgeMetadataKeys.RAPTOR_NODE_ID, 7001L,
            DocumentKnowledgeMetadataKeys.RAPTOR_SOURCE_STATUS, "SOURCE_CHUNK",
            DocumentKnowledgeMetadataKeys.CHUNK_ID, 8001L,
            DocumentKnowledgeMetadataKeys.CHUNK_TYPE, "RAPTOR_SOURCE_CHUNK"
        ));

        assertThat(EvidenceIdentityResolver.citationIdentity(summary)).isNull();
        assertThat(EvidenceIdentityResolver.isContextOnly(summary)).isTrue();
        EvidenceIdentity sourceIdentity = EvidenceIdentityResolver.citationIdentity(sourceChunk);
        assertThat(sourceIdentity.type()).isEqualTo(CitationEvidenceType.RAPTOR_SOURCE_CHUNK);
        assertThat(sourceIdentity.value()).isEqualTo("RAPTOR_SOURCE:7001:8001");
    }

    @Test
    void tableEvidenceUsesCellOrRowIdentity() {
        Document table = document("table", "表格结果：研发部报销金额合计 1200", metadata(
            DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "DOCUMENT_TABLE",
            DocumentKnowledgeMetadataKeys.TABLE_ID, 60L,
            DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_CELL_IDS, List.of(401L, 402L),
            DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_ROW_IDS, List.of(301L)
        ));

        EvidenceIdentity identity = EvidenceIdentityResolver.citationIdentity(table);

        assertThat(identity.type()).isEqualTo(CitationEvidenceType.TABLE_CELL_OR_ROW);
        assertThat(identity.value()).isEqualTo("TABLE:60:CELLS:[401, 402]");
    }

    @Test
    void mapperCarriesChunkTypeIntoSearchReferenceIdentity() {
        Document title = document("title", "# 标题", metadata(
            DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "DOCUMENT",
            DocumentKnowledgeMetadataKeys.DOCUMENT_ID, 10L,
            DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, 9001L,
            DocumentKnowledgeMetadataKeys.CHUNK_ID, 1001L,
            DocumentKnowledgeMetadataKeys.CHUNK_TYPE, "TITLE"
        ));
        Document body = document("body", "正文内容可以被引用。", metadata(
            DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "DOCUMENT",
            DocumentKnowledgeMetadataKeys.DOCUMENT_ID, 10L,
            DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, 9001L,
            DocumentKnowledgeMetadataKeys.CHUNK_ID, 1002L,
            DocumentKnowledgeMetadataKeys.CHUNK_TYPE, "TEXT"
        ));

        SearchReference titleReference = SearchReferenceMapper.fromDocument(title, 0, "问题", 1);
        SearchReference bodyReference = SearchReferenceMapper.fromDocument(body, 0, "问题", 2);

        assertThat(titleReference.getChunkType()).isEqualTo("TITLE");
        assertThat(titleReference.isContextOnly()).isTrue();
        assertThat(titleReference.getCitationEvidenceType()).isEqualTo("CONTEXT_ONLY");
        assertThat(titleReference.getCitationIdentity()).isEmpty();
        assertThat(titleReference.uniqueKey()).isEqualTo("PARENT:10:9001");
        assertThat(bodyReference.getChunkType()).isEqualTo("TEXT");
        assertThat(bodyReference.isContextOnly()).isFalse();
        assertThat(bodyReference.getCitationEvidenceType()).isEqualTo("CHUNK");
        assertThat(bodyReference.getCitationIdentity()).isEqualTo("CHUNK:10:1002");
    }

    private static Document document(String id, String text, Map<String, Object> metadata) {
        return Document.builder()
            .id(id)
            .text(text)
            .metadata(metadata)
            .build();
    }

    private static Map<String, Object> metadata(Object... values) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            metadata.put(String.valueOf(values[index]), values[index + 1]);
        }
        return metadata;
    }
}
