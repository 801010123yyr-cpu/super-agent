package org.javaup.ai.manage.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.manage.data.SuperAgentDocumentParentBlock;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentParentBlockMapper;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.javaup.ai.manage.support.GraphRagTypedChunkMetadataSupport;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentKnowledgeServiceImplTest {

    @Test
    void elevateToParentBlocksPrefersRelationMetadataWhenGraphRagChildrenCompete() {
        SuperAgentDocumentParentBlock parentBlock = new SuperAgentDocumentParentBlock();
        parentBlock.setId(1001L);
        parentBlock.setDocumentId(10L);
        parentBlock.setTaskId(20L);
        parentBlock.setParentNo(4);
        parentBlock.setSectionPath("`AuditTrail` 需记录以下权限相关行为：");
        parentBlock.setParentText("# `AuditTrail` 需记录以下权限相关行为：\n- 权限申请。\n- 权限审批。");

        Document relationChild = Document.builder()
            .id("graph-rag-relation-1")
            .text("AuditTrail RECORDS 权限申请")
            .score(1.45D)
            .metadata(metadata(
                DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "GRAPH_RAG",
                DocumentKnowledgeMetadataKeys.CHANNEL, "graph-rag",
                DocumentKnowledgeMetadataKeys.SCORE, 1.45D,
                DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, 1001L,
                DocumentKnowledgeMetadataKeys.CHUNK_ID, 2001L,
                DocumentKnowledgeMetadataKeys.KG_ENTITY_ID, 3001L,
                DocumentKnowledgeMetadataKeys.KG_ENTITY_NAME, "AuditTrail",
                DocumentKnowledgeMetadataKeys.KG_RELATION_ID, 4001L,
                DocumentKnowledgeMetadataKeys.KG_RELATION_TYPE, "RECORDS",
                DocumentKnowledgeMetadataKeys.KG_RELATED_ENTITY_NAME, "权限申请",
                DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID, 5001L,
                DocumentKnowledgeMetadataKeys.SECTION_PATH, "`AuditTrail` 需记录以下权限相关行为：",
                DocumentKnowledgeMetadataKeys.KG_GRAPH_PATH, "关系匹配：AuditTrail --RECORDS--> 权限申请"
            ))
            .build();
        Document entityChild = Document.builder()
            .id("graph-rag-entity-1")
            .text("AuditTrail")
            .score(2.05D)
            .metadata(metadata(
                DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "GRAPH_RAG",
                DocumentKnowledgeMetadataKeys.CHANNEL, "graph-rag",
                DocumentKnowledgeMetadataKeys.SCORE, 2.05D,
                DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, 1001L,
                DocumentKnowledgeMetadataKeys.CHUNK_ID, 2002L,
                DocumentKnowledgeMetadataKeys.KG_ENTITY_ID, 3001L,
                DocumentKnowledgeMetadataKeys.KG_ENTITY_NAME, "AuditTrail",
                DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID, 5002L,
                DocumentKnowledgeMetadataKeys.SECTION_PATH, "一、适用范围",
                DocumentKnowledgeMetadataKeys.KG_GRAPH_PATH, "AuditTrail"
            ))
            .build();

        SuperAgentDocumentParentBlockMapper parentBlockMapper = mapper(
            SuperAgentDocumentParentBlockMapper.class,
            List.of(parentBlock)
        );

        DocumentKnowledgeServiceImpl service = new DocumentKnowledgeServiceImpl(
            mapper(SuperAgentDocumentMapper.class, List.<org.javaup.ai.manage.data.SuperAgentDocument>of()),
            parentBlockMapper,
            null,
            null,
            null,
            new GraphRagTypedChunkMetadataSupport(new ObjectMapper())
        );

        List<Document> elevated = service.elevateToParentBlocks(List.of(entityChild, relationChild), 1024);

        assertThat(elevated).hasSize(1);
        Document parentEvidence = elevated.get(0);
        assertThat(parentEvidence.getId()).isEqualTo("parent-1001");
        assertThat(parentEvidence.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_RELATION_ID)).isEqualTo(4001L);
        assertThat(parentEvidence.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_RELATION_TYPE)).isEqualTo("RECORDS");
        assertThat(parentEvidence.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_ENTITY_NAME)).isEqualTo("AuditTrail");
        assertThat(parentEvidence.getText()).contains("命中子片段");
        assertThat(parentEvidence.getText()).contains("权限申请");
    }

    @Test
    void elevateToParentBlocksKeepsGraphRagMetadataWhenParentBecomesHybrid() {
        SuperAgentDocumentParentBlock parentBlock = new SuperAgentDocumentParentBlock();
        parentBlock.setId(1002L);
        parentBlock.setDocumentId(10L);
        parentBlock.setTaskId(20L);
        parentBlock.setParentNo(5);
        parentBlock.setSectionPath("异常权限扩散");
        parentBlock.setParentText("异常权限扩散时，AuditTrail 必须保留操作人、审批人和处理时间。");

        Document keywordChild = Document.builder()
            .id("keyword-1")
            .text("异常权限扩散字段")
            .score(2.60D)
            .metadata(metadata(
                DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "DOCUMENT",
                DocumentKnowledgeMetadataKeys.CHANNEL, "keyword",
                DocumentKnowledgeMetadataKeys.SCORE, 2.60D,
                DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, 1002L,
                DocumentKnowledgeMetadataKeys.CHUNK_ID, 2101L,
                DocumentKnowledgeMetadataKeys.SECTION_PATH, "异常权限扩散"
            ))
            .build();
        Document graphRagChild = Document.builder()
            .id("graph-rag-1")
            .text("AuditTrail RECORDS 异常权限扩散")
            .score(1.10D)
            .metadata(metadata(
                DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "GRAPH_RAG",
                DocumentKnowledgeMetadataKeys.CHANNEL, "graph-rag",
                DocumentKnowledgeMetadataKeys.SCORE, 1.10D,
                DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, 1002L,
                DocumentKnowledgeMetadataKeys.CHUNK_ID, 2102L,
                DocumentKnowledgeMetadataKeys.KG_ENTITY_ID, 3101L,
                DocumentKnowledgeMetadataKeys.KG_ENTITY_NAME, "AuditTrail",
                DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_KEY, "CONCEPT:审计系统",
                DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_NAME, "审计系统",
                DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_COUNT, 4,
                DocumentKnowledgeMetadataKeys.KG_CANONICAL_DOCUMENT_COUNT, 2,
                DocumentKnowledgeMetadataKeys.KG_RELATION_ID, 4101L,
                DocumentKnowledgeMetadataKeys.KG_RELATION_TYPE, "RECORDS",
                DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY, "CONCEPT:审计系统->RECORDS->CONCEPT:异常权限扩散",
                DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_RELATION_COUNT, 1,
                DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_EVIDENCE_COUNT, 1,
                DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_DOCUMENT_COUNT, 1,
                DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID, 5101L,
                DocumentKnowledgeMetadataKeys.KG_GRAPH_PATH, "一跳：AuditTrail --RECORDS--> 异常权限扩散",
                DocumentKnowledgeMetadataKeys.KG_QUALITY_SCORE, 0.81D,
                DocumentKnowledgeMetadataKeys.KG_QUALITY_REASONS, "groundedEvidence,strongRelationSource",
                DocumentKnowledgeMetadataKeys.KG_NOISE_REASONS, ""
            ))
            .build();

        DocumentKnowledgeServiceImpl service = new DocumentKnowledgeServiceImpl(
            mapper(SuperAgentDocumentMapper.class, List.<org.javaup.ai.manage.data.SuperAgentDocument>of()),
            mapper(SuperAgentDocumentParentBlockMapper.class, List.of(parentBlock)),
            null,
            null,
            null,
            new GraphRagTypedChunkMetadataSupport(new ObjectMapper())
        );

        List<Document> elevated = service.elevateToParentBlocks(List.of(keywordChild, graphRagChild), 1024);

        assertThat(elevated).hasSize(1);
        Map<String, Object> metadata = elevated.get(0).getMetadata();
        assertThat(metadata.get(DocumentKnowledgeMetadataKeys.CHANNEL)).isEqualTo("hybrid");
        assertThat(metadata.get(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_NAME)).isEqualTo("审计系统");
        assertThat(metadata.get(DocumentKnowledgeMetadataKeys.KG_CANONICAL_DOCUMENT_COUNT)).isEqualTo(2);
        assertThat(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY))
            .isEqualTo("CONCEPT:审计系统->RECORDS->CONCEPT:异常权限扩散");
        assertThat(metadata.get(DocumentKnowledgeMetadataKeys.KG_QUALITY_SCORE)).isEqualTo(0.81D);
        assertThat(metadata.get(DocumentKnowledgeMetadataKeys.KG_QUALITY_REASONS))
            .isEqualTo("groundedEvidence,strongRelationSource");
    }

    private static Map<String, Object> metadata(Object... values) {
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> T mapper(Class<T> mapperType, List<?> selectListResult) {
        return (T) Proxy.newProxyInstance(
            mapperType.getClassLoader(),
            new Class<?>[]{mapperType},
            (proxy, method, args) -> {
                if ("selectList".equals(method.getName())) {
                    return selectListResult;
                }
                if ("toString".equals(method.getName())) {
                    return mapperType.getSimpleName() + "Proxy";
                }
                if ("hashCode".equals(method.getName())) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(method.getName())) {
                    return proxy == args[0];
                }
                return defaultValue(method.getReturnType());
            }
        );
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == void.class) {
            return null;
        }
        return 0;
    }
}
