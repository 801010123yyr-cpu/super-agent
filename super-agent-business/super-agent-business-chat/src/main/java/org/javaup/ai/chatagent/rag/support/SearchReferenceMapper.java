package org.javaup.ai.chatagent.rag.support;

import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: Mapper层
 * @author: 阿星不是程序员
 **/

public final class SearchReferenceMapper {

    private SearchReferenceMapper() {
    }

    public static SearchReference fromDocument(Document document,
                                               int subQuestionIndex,
                                               String subQuestion,
                                               int referenceNumber) {
        Map<String, Object> metadata = document.getMetadata();
        String sourceType = asText(metadata.get(DocumentKnowledgeMetadataKeys.SOURCE_TYPE), "DOCUMENT");
        SearchReference reference = new SearchReference();
        reference.setReferenceId(String.valueOf(referenceNumber));
        reference.setSourceType(sourceType);
        reference.setSnippet(document.getText());
        reference.setSubQuestionIndex(subQuestionIndex);
        reference.setSubQuestion(subQuestion);
        reference.setChannel(asText(metadata.get(DocumentKnowledgeMetadataKeys.CHANNEL), "vector"));
        reference.setScore(asDouble(metadata.get(DocumentKnowledgeMetadataKeys.SCORE)));

        if ("WEB".equalsIgnoreCase(sourceType)) {
            reference.setTitle(asText(metadata.get(DocumentKnowledgeMetadataKeys.TITLE), "网页来源"));
            reference.setUrl(asText(metadata.get(DocumentKnowledgeMetadataKeys.URL), ""));
            reference.setToolName(asText(metadata.get(DocumentKnowledgeMetadataKeys.TOOL_NAME), "tavily_search"));
            return reference;
        }

        reference.setTitle(asText(metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME), "文档片段"));
        reference.setDocumentId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_ID)));
        reference.setDocumentName(asText(metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME), ""));
        reference.setParentBlockId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID)));
        reference.setParentBlockNo(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_NO)));
        reference.setChunkId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_ID)));
        reference.setChunkNo(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_NO)));
        reference.setSectionPath(asText(metadata.get(DocumentKnowledgeMetadataKeys.SECTION_PATH), ""));
        reference.setStructureNodeId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_ID)));
        reference.setStructureNodeType(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_TYPE)));
        reference.setCanonicalPath(asText(metadata.get(DocumentKnowledgeMetadataKeys.CANONICAL_PATH), ""));
        reference.setItemIndex(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.ITEM_INDEX)));
        reference.setKnowledgeScopeCode(asText(metadata.get(DocumentKnowledgeMetadataKeys.KNOWLEDGE_SCOPE_CODE), ""));
        reference.setKnowledgeScopeName(asText(metadata.get(DocumentKnowledgeMetadataKeys.KNOWLEDGE_SCOPE_NAME), ""));
        reference.setPageNo(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.PAGE_NO)));
        reference.setPageRange(asText(metadata.get(DocumentKnowledgeMetadataKeys.PAGE_RANGE), ""));
        reference.setBboxJson(asText(metadata.get(DocumentKnowledgeMetadataKeys.BBOX_JSON), ""));
        reference.setSourceBlockIds(asText(metadata.get(DocumentKnowledgeMetadataKeys.SOURCE_BLOCK_IDS), ""));
        reference.setTableId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_ID)));
        reference.setTableNo(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_NO)));
        reference.setTableTitle(asText(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_TITLE), ""));
        reference.setTableOperation(asText(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_OPERATION), ""));
        reference.setTableMetricColumn(asText(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_METRIC_COLUMN), ""));
        reference.setTableGroupByColumn(asText(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_GROUP_BY_COLUMN), ""));
        reference.setTableMatchedRowCount(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_MATCHED_ROW_COUNT)));
        reference.setTableEvidenceRowIds(asLongList(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_ROW_IDS)));
        reference.setTableEvidenceRowNos(asIntegerList(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_ROW_NOS)));
        reference.setTableEvidenceColumnIds(asLongList(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_COLUMN_IDS)));
        reference.setTableEvidenceColumnNos(asIntegerList(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_COLUMN_NOS)));
        reference.setTableEvidenceColumnNames(asStringList(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_COLUMN_NAMES)));
        reference.setTableEvidenceCellIds(asLongList(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_CELL_IDS)));
        reference.setTableEvidenceCellCoordinates(asStringList(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_CELL_COORDINATES)));
        reference.setTableEvidenceCellBboxJsons(asStringList(metadata.get(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_CELL_BBOX_JSONS)));
        reference.setKgEntityId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.KG_ENTITY_ID)));
        reference.setKgEntityName(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_ENTITY_NAME), ""));
        reference.setKgCanonicalEntityKey(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_KEY), ""));
        reference.setKgCanonicalEntityName(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_NAME), ""));
        reference.setKgCanonicalEntityCount(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_COUNT)));
        reference.setKgCanonicalDocumentCount(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.KG_CANONICAL_DOCUMENT_COUNT)));
        reference.setKgRelatedEntityId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATED_ENTITY_ID)));
        reference.setKgRelatedEntityName(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATED_ENTITY_NAME), ""));
        reference.setKgRelationId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_ID)));
        reference.setKgRelationType(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_TYPE), ""));
        reference.setKgRelationGroupKey(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY), ""));
        reference.setKgRelationGroupRelationCount(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_RELATION_COUNT)));
        reference.setKgRelationGroupEvidenceCount(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_EVIDENCE_COUNT)));
        reference.setKgRelationGroupDocumentCount(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_DOCUMENT_COUNT)));
        reference.setKgEvidenceId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID)));
        reference.setKgGraphPath(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_GRAPH_PATH), ""));
        reference.setKgHopCount(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.KG_HOP_COUNT)));
        reference.setRaptorNodeId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.RAPTOR_NODE_ID)));
        reference.setRaptorNodeTitle(asText(metadata.get(DocumentKnowledgeMetadataKeys.RAPTOR_NODE_TITLE), ""));
        reference.setRaptorNodeLevel(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.RAPTOR_NODE_LEVEL)));
        reference.setRaptorSummary(asText(metadata.get(DocumentKnowledgeMetadataKeys.RAPTOR_SUMMARY), ""));
        return reference;
    }

    private static String asText(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static List<Long> asLongList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<Long> values = new ArrayList<>();
        for (Object item : iterable) {
            Long parsed = asLong(item);
            if (parsed != null) {
                values.add(parsed);
            }
        }
        return values;
    }

    private static List<Integer> asIntegerList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<Integer> values = new ArrayList<>();
        for (Object item : iterable) {
            Integer parsed = asInteger(item);
            if (parsed != null) {
                values.add(parsed);
            }
        }
        return values;
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : iterable) {
            if (item != null) {
                values.add(String.valueOf(item));
            }
        }
        return values;
    }
}
