package org.javaup.ai.chatagent.rag.support;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.rag.model.CitationEvidenceType;
import org.javaup.ai.chatagent.rag.model.EvidenceIdentity;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class EvidenceIdentityResolver {

    private static final List<String> BODY_CHUNK_TYPES = List.of("TEXT", "LIST", "TABLE", "BODY");

    private EvidenceIdentityResolver() {
    }

    public static EvidenceIdentity citationIdentity(Document document) {
        if (document == null || document.getMetadata() == null) {
            return null;
        }
        Map<String, Object> metadata = document.getMetadata();
        Long documentId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_ID));
        Long chunkId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_ID));
        Long kgEvidenceId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID));
        Long raptorNodeId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.RAPTOR_NODE_ID));
        Long tableId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_ID));

        if (isTableEvidence(metadata)) {
            return EvidenceIdentity.citation("TABLE:" + tableId + ":" + tableEvidenceKey(metadata), CitationEvidenceType.TABLE_CELL_OR_ROW);
        }
        if (isGraphRagQuoteEvidence(metadata, document.getText())) {
            String sourceChunk = chunkId == null ? "" : ":CHUNK:" + chunkId;
            return EvidenceIdentity.citation("KG_QUOTE:" + kgEvidenceId + sourceChunk, CitationEvidenceType.KG_QUOTE_SOURCE);
        }
        if (isRaptorSourceChunk(metadata) && chunkId != null) {
            return EvidenceIdentity.citation("RAPTOR_SOURCE:" + raptorNodeId + ":" + chunkId, CitationEvidenceType.RAPTOR_SOURCE_CHUNK);
        }
        if (isRawDocumentChunk(metadata) && chunkId != null) {
            return EvidenceIdentity.citation("CHUNK:" + documentScope(documentId) + chunkId, CitationEvidenceType.CHUNK);
        }
        return null;
    }

    public static EvidenceIdentity contextIdentity(Document document) {
        if (document == null || document.getMetadata() == null) {
            return null;
        }
        Map<String, Object> metadata = document.getMetadata();
        Long documentId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_ID));
        Long parentBlockId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID));
        Long chunkId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_ID));
        Long kgEvidenceId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID));
        Long raptorNodeId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.RAPTOR_NODE_ID));
        Long tableId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_ID));
        if (parentBlockId != null) {
            return EvidenceIdentity.context("PARENT:" + documentScope(documentId) + parentBlockId);
        }
        if (chunkId != null) {
            return EvidenceIdentity.context("CHUNK_CONTEXT:" + documentScope(documentId) + chunkId);
        }
        if (kgEvidenceId != null) {
            return EvidenceIdentity.context("KG_CONTEXT:" + kgEvidenceId);
        }
        if (raptorNodeId != null) {
            return EvidenceIdentity.context("RAPTOR_CONTEXT:" + raptorNodeId + ":" + safeText(metadata.get(DocumentKnowledgeMetadataKeys.RAPTOR_SOURCE_STATUS)));
        }
        if (tableId != null) {
            return EvidenceIdentity.context("TABLE_CONTEXT:" + tableId);
        }
        if (document.getId() != null) {
            return EvidenceIdentity.context("DOC_OBJECT:" + document.getId());
        }
        return null;
    }

    public static EvidenceIdentity citationIdentity(SearchReference reference) {
        if (reference == null) {
            return null;
        }
        if (isTableEvidence(reference)) {
            return EvidenceIdentity.citation("TABLE:" + reference.getTableId() + ":" + tableEvidenceKey(reference), CitationEvidenceType.TABLE_CELL_OR_ROW);
        }
        if (reference.getKgEvidenceId() != null && StrUtil.isNotBlank(reference.getQuoteText())) {
            String sourceChunk = reference.getChunkId() == null ? "" : ":CHUNK:" + reference.getChunkId();
            return EvidenceIdentity.citation("KG_QUOTE:" + reference.getKgEvidenceId() + sourceChunk, CitationEvidenceType.KG_QUOTE_SOURCE);
        }
        if (isRaptorSourceChunk(reference) && reference.getChunkId() != null) {
            return EvidenceIdentity.citation("RAPTOR_SOURCE:" + reference.getRaptorNodeId() + ":" + reference.getChunkId(), CitationEvidenceType.RAPTOR_SOURCE_CHUNK);
        }
        if (reference.getChunkId() != null && isRawDocumentChunk(reference)) {
            return EvidenceIdentity.citation("CHUNK:" + documentScope(reference.getDocumentId()) + reference.getChunkId(), CitationEvidenceType.CHUNK);
        }
        return null;
    }

    public static EvidenceIdentity contextIdentity(SearchReference reference) {
        if (reference == null) {
            return null;
        }
        if (reference.getParentBlockId() != null) {
            return EvidenceIdentity.context("PARENT:" + documentScope(reference.getDocumentId()) + reference.getParentBlockId());
        }
        if (reference.getChunkId() != null) {
            return EvidenceIdentity.context("CHUNK_CONTEXT:" + documentScope(reference.getDocumentId()) + reference.getChunkId());
        }
        if (reference.getKgEvidenceId() != null) {
            return EvidenceIdentity.context("KG_CONTEXT:" + reference.getKgEvidenceId());
        }
        if (reference.getRaptorNodeId() != null) {
            return EvidenceIdentity.context("RAPTOR_CONTEXT:" + reference.getRaptorNodeId() + ":" + StrUtil.blankToDefault(reference.getRaptorSourceStatus(), ""));
        }
        if (reference.getTableId() != null) {
            return EvidenceIdentity.context("TABLE_CONTEXT:" + reference.getTableId());
        }
        if (StrUtil.isNotBlank(reference.getUrl())) {
            return EvidenceIdentity.context("WEB:" + reference.getUrl());
        }
        return EvidenceIdentity.context(StrUtil.blankToDefault(reference.getSourceType(), "UNKNOWN")
            + ":" + StrUtil.blankToDefault(reference.getTitle(), "")
            + ":" + StrUtil.blankToDefault(reference.getSnippet(), ""));
    }

    public static boolean sameCitationEvidence(Document left, Document right) {
        EvidenceIdentity leftIdentity = citationIdentity(left);
        EvidenceIdentity rightIdentity = citationIdentity(right);
        return samePresentIdentity(leftIdentity, rightIdentity);
    }

    public static boolean sameContext(Document left, Document right) {
        EvidenceIdentity leftIdentity = contextIdentity(left);
        EvidenceIdentity rightIdentity = contextIdentity(right);
        return samePresentIdentity(leftIdentity, rightIdentity);
    }

    public static boolean isCitationCapable(Document document) {
        EvidenceIdentity identity = citationIdentity(document);
        return identity != null && identity.present() && identity.citationCapable();
    }

    public static boolean isContextOnly(Document document) {
        return !isCitationCapable(document);
    }

    public static boolean isContextOnly(SearchReference reference) {
        String sourceType = StrUtil.blankToDefault(reference.getSourceType(), "");
        if ("GRAPH_RAG".equalsIgnoreCase(sourceType) && (reference.getKgEvidenceId() == null || StrUtil.isBlank(reference.getQuoteText()))) {
            return true;
        }
        if (reference.getRaptorNodeId() != null && !isRaptorSourceChunk(reference)) {
            return true;
        }
        if (reference.getChunkId() != null && !isRawDocumentChunk(reference) && !isTableEvidence(reference)
            && !(reference.getKgEvidenceId() != null && StrUtil.isNotBlank(reference.getQuoteText()))
            && !isRaptorSourceChunk(reference)) {
            return true;
        }
        return false;
    }

    public static String citationIdentityValue(Document document) {
        EvidenceIdentity identity = citationIdentity(document);
        return identity == null ? "" : StrUtil.blankToDefault(identity.value(), "");
    }

    public static String contextIdentityValue(Document document) {
        EvidenceIdentity identity = contextIdentity(document);
        return identity == null ? "" : StrUtil.blankToDefault(identity.value(), "");
    }

    public static CitationEvidenceType citationEvidenceType(Document document) {
        EvidenceIdentity identity = citationIdentity(document);
        return identity == null ? CitationEvidenceType.CONTEXT_ONLY : identity.type();
    }

    private static boolean samePresentIdentity(EvidenceIdentity left, EvidenceIdentity right) {
        return left != null && right != null
            && left.present()
            && right.present()
            && Objects.equals(left.value(), right.value());
    }

    private static boolean isRawDocumentChunk(Map<String, Object> metadata) {
        String sourceType = safeText(metadata.get(DocumentKnowledgeMetadataKeys.SOURCE_TYPE));
        if ("GRAPH_RAG".equalsIgnoreCase(sourceType) || "RAPTOR".equalsIgnoreCase(sourceType) || "DOCUMENT_TABLE".equalsIgnoreCase(sourceType)) {
            return false;
        }
        String chunkType = safeText(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_TYPE)).toUpperCase();
        return BODY_CHUNK_TYPES.contains(chunkType) || chunkType.isBlank();
    }

    private static boolean isRawDocumentChunk(SearchReference reference) {
        String sourceType = StrUtil.blankToDefault(reference.getSourceType(), "");
        if ("GRAPH_RAG".equalsIgnoreCase(sourceType) || "RAPTOR".equalsIgnoreCase(sourceType) || "DOCUMENT_TABLE".equalsIgnoreCase(sourceType)) {
            return false;
        }
        String chunkType = StrUtil.blankToDefault(reference.getChunkType(), "").trim().toUpperCase();
        return BODY_CHUNK_TYPES.contains(chunkType) || chunkType.isBlank();
    }

    private static boolean isGraphRagQuoteEvidence(Map<String, Object> metadata, String text) {
        Long kgEvidenceId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID));
        if (kgEvidenceId == null) {
            return false;
        }
        String originalSnippet = safeText(metadata.get(DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET));
        return StrUtil.isNotBlank(originalSnippet);
    }

    private static boolean isRaptorSourceChunk(Map<String, Object> metadata) {
        String sourceStatus = safeText(metadata.get(DocumentKnowledgeMetadataKeys.RAPTOR_SOURCE_STATUS));
        String chunkType = safeText(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_TYPE));
        return "SOURCE_CHUNK".equalsIgnoreCase(sourceStatus) || "RAPTOR_SOURCE_CHUNK".equalsIgnoreCase(chunkType);
    }

    private static boolean isRaptorSourceChunk(SearchReference reference) {
        return "SOURCE_CHUNK".equalsIgnoreCase(StrUtil.blankToDefault(reference.getRaptorSourceStatus(), ""));
    }

    private static boolean isTableEvidence(Map<String, Object> metadata) {
        Long tableId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_ID));
        return tableId != null
            && (!asLongList(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_CELL_IDS)).isEmpty()
            || !asLongList(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_ROW_IDS)).isEmpty());
    }

    private static boolean isTableEvidence(SearchReference reference) {
        return reference.getTableId() != null
            && ((reference.getTableEvidenceCellIds() != null && !reference.getTableEvidenceCellIds().isEmpty())
            || (reference.getTableEvidenceRowIds() != null && !reference.getTableEvidenceRowIds().isEmpty()));
    }

    private static String tableEvidenceKey(Map<String, Object> metadata) {
        List<Long> cellIds = asLongList(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_CELL_IDS));
        if (!cellIds.isEmpty()) {
            return "CELLS:" + cellIds;
        }
        List<Long> rowIds = asLongList(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_ROW_IDS));
        if (!rowIds.isEmpty()) {
            return "ROWS:" + rowIds;
        }
        return "TABLE";
    }

    private static String tableEvidenceKey(SearchReference reference) {
        if (reference.getTableEvidenceCellIds() != null && !reference.getTableEvidenceCellIds().isEmpty()) {
            return "CELLS:" + reference.getTableEvidenceCellIds();
        }
        if (reference.getTableEvidenceRowIds() != null && !reference.getTableEvidenceRowIds().isEmpty()) {
            return "ROWS:" + reference.getTableEvidenceRowIds();
        }
        return "TABLE";
    }

    private static List<Long> asLongList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        java.util.ArrayList<Long> values = new java.util.ArrayList<>();
        for (Object item : iterable) {
            Long parsed = asLong(item);
            if (parsed != null) {
                values.add(parsed);
            }
        }
        return values;
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        }
        catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String safeText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String documentScope(Long documentId) {
        return documentId == null ? "" : documentId + ":";
    }
}
