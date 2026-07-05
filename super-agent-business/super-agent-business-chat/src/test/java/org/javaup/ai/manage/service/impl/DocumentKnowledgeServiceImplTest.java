package org.javaup.ai.manage.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.manage.data.SuperAgentDocumentChunk;
import org.javaup.ai.manage.data.SuperAgentDocumentParentBlock;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.mapper.SuperAgentDocumentChunkMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentParentBlockMapper;
import org.javaup.ai.manage.model.StructureAnchoredEvidenceRequest;
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
            mapper(SuperAgentDocumentChunkMapper.class, List.<SuperAgentDocumentChunk>of()),
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
                DocumentKnowledgeMetadataKeys.KG_NOISE_REASONS, "",
                DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY, "xdoc-community:concept审计系统",
                DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_ENTITY_COUNT, 6,
                DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_RELATION_GROUP_COUNT, 4,
                DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_EVIDENCE_COUNT, 8,
                DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_DOCUMENT_COUNT, 2,
                DocumentKnowledgeMetadataKeys.KG_PAGERANK, 0.17D,
                DocumentKnowledgeMetadataKeys.KG_RANK_POSITION, 1,
                DocumentKnowledgeMetadataKeys.KG_DEGREE, 5
            ))
            .build();

        DocumentKnowledgeServiceImpl service = new DocumentKnowledgeServiceImpl(
            mapper(SuperAgentDocumentMapper.class, List.<org.javaup.ai.manage.data.SuperAgentDocument>of()),
            mapper(SuperAgentDocumentParentBlockMapper.class, List.of(parentBlock)),
            mapper(SuperAgentDocumentChunkMapper.class, List.<SuperAgentDocumentChunk>of()),
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
        assertThat(metadata.get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY))
            .isEqualTo("xdoc-community:concept审计系统");
        assertThat(metadata.get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_ENTITY_COUNT)).isEqualTo(6);
        assertThat(metadata.get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_RELATION_GROUP_COUNT)).isEqualTo(4);
        assertThat(metadata.get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_EVIDENCE_COUNT)).isEqualTo(8);
        assertThat(metadata.get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_DOCUMENT_COUNT)).isEqualTo(2);
        assertThat(metadata.get(DocumentKnowledgeMetadataKeys.KG_PAGERANK)).isEqualTo(0.17D);
        assertThat(metadata.get(DocumentKnowledgeMetadataKeys.KG_RANK_POSITION)).isEqualTo(1);
        assertThat(metadata.get(DocumentKnowledgeMetadataKeys.KG_DEGREE)).isEqualTo(5);
    }

    @Test
    void expandStructureAnchoredEvidenceUsesStructureMetadataAndKnowledgeBaseBoundary() {
        SuperAgentDocument document = new SuperAgentDocument();
        document.setId(10L);
        document.setDocumentName("测试文档");
        document.setLastIndexTaskId(20L);
        document.setKnowledgeBaseId(100L);
        document.setKnowledgeBaseName("测试知识库");

        SuperAgentDocumentParentBlock exact = new SuperAgentDocumentParentBlock();
        exact.setId(1003L);
        exact.setDocumentId(10L);
        exact.setTaskId(20L);
        exact.setParentNo(6);
        exact.setSectionPath("14.1.2");
        exact.setCanonicalPath("14.1/14.1.2");
        exact.setStructureNodeId(202L);
        exact.setParentText("# 14.1.2 可能原因");

        SuperAgentDocumentParentBlock descendant = new SuperAgentDocumentParentBlock();
        descendant.setId(1004L);
        descendant.setDocumentId(10L);
        descendant.setTaskId(20L);
        descendant.setParentNo(7);
        descendant.setSectionPath("14.1.3");
        descendant.setCanonicalPath("14.1/14.1.3");
        descendant.setStructureNodeId(203L);
        descendant.setParentText("1. 先回滚切块策略。\n2. 再重建索引。");

        SuperAgentDocumentChunk exactText = chunk(2003L, 10L, 20L, 1003L, 9, 202L, "14.1.2", "14.1/14.1.2", "TEXT",
            "1. 新版本切块异常。\n2. 父子块配置错误。\n3. 向量索引构建不完整。");
        SuperAgentDocumentChunk descendantText = chunk(2004L, 10L, 20L, 1004L, 10, 203L, "14.1.3", "14.1/14.1.3", "TEXT",
            "1. 先回滚切块策略。\n2. 再重建索引。");

        DocumentKnowledgeServiceImpl service = new DocumentKnowledgeServiceImpl(
            mapper(SuperAgentDocumentMapper.class, List.of(document)),
            mapper(SuperAgentDocumentParentBlockMapper.class, List.of(exact, descendant)),
            mapper(SuperAgentDocumentChunkMapper.class, List.of(exactText, descendantText)),
            null,
            null,
            null,
            new GraphRagTypedChunkMetadataSupport(new ObjectMapper())
        );

        List<Document> expanded = service.expandStructureAnchoredEvidence(StructureAnchoredEvidenceRequest.builder()
            .documentIds(List.of(10L))
            .taskIds(List.of(20L))
            .knowledgeBaseIds(List.of(100L))
            .structureNodeIds(List.of(202L))
            .canonicalPaths(List.of("14.1"))
            .maxPerAnchor(2)
            .maxTotal(4)
            .maxChars(512)
            .build());

        assertThat(expanded).extracting(Document::getId)
            .contains("structure-chunk-2003", "structure-chunk-2004");
        Document exactEvidence = expanded.stream()
            .filter(documentItem -> "structure-chunk-2003".equals(documentItem.getId()))
            .findFirst()
            .orElseThrow();
        assertThat(exactEvidence.getText()).contains("父子块配置错误");
        assertThat(exactEvidence.getMetadata())
            .containsEntry(DocumentKnowledgeMetadataKeys.CHANNEL, "structure-anchor")
            .containsEntry(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_ID, 100L)
            .containsEntry(DocumentKnowledgeMetadataKeys.FINAL_SELECTION_RESERVE_TYPE, "STRUCTURE_ANCHOR_BODY_CANDIDATE")
            .containsEntry(DocumentKnowledgeMetadataKeys.STRUCTURE_ANCHOR_RAW_BODY, true)
            .containsEntry(DocumentKnowledgeMetadataKeys.STRUCTURE_ANCHOR_BYPASS_RESERVE_WINDOW, true);
    }

    @Test
    void expandStructureAnchoredEvidenceUsesContinuationListWhenAnchorParentIsTitleOnly() {
        SuperAgentDocument document = new SuperAgentDocument();
        document.setId(10L);
        document.setDocumentName("测试文档");
        document.setLastIndexTaskId(20L);
        document.setKnowledgeBaseId(100L);
        document.setKnowledgeBaseName("测试知识库");

        SuperAgentDocumentParentBlock titleOnly = new SuperAgentDocumentParentBlock();
        titleOnly.setId(1003L);
        titleOnly.setDocumentId(10L);
        titleOnly.setTaskId(20L);
        titleOnly.setParentNo(6);
        titleOnly.setSectionPath("14.1.2");
        titleOnly.setCanonicalPath("14.1/14.1.2");
        titleOnly.setStructureNodeId(202L);
        titleOnly.setParentText("# 14.1.2 可能原因");

        SuperAgentDocumentChunk titleChunk = chunk(2003L, 10L, 20L, 1003L, 9, 202L, "14.1.2", "14.1/14.1.2", "TITLE",
            "# 14.1.2 可能原因");
        SuperAgentDocumentChunk continuationList = chunk(2004L, 10L, 20L, 1004L, 10, 203L, "14.1.2", "14.1/14.1.2", "TITLE",
            "1. 新版本切块异常。 2. 父子块配置错误。 3. 向量索引构建不完整。 4. 检索过滤条件误收紧。");

        DocumentKnowledgeServiceImpl service = new DocumentKnowledgeServiceImpl(
            mapper(SuperAgentDocumentMapper.class, List.of(document)),
            mapper(SuperAgentDocumentParentBlockMapper.class, List.of(titleOnly)),
            mapper(SuperAgentDocumentChunkMapper.class, List.of(titleChunk, continuationList)),
            null,
            null,
            null,
            new GraphRagTypedChunkMetadataSupport(new ObjectMapper())
        );

        List<Document> expanded = service.expandStructureAnchoredEvidence(StructureAnchoredEvidenceRequest.builder()
            .documentIds(List.of(10L))
            .taskIds(List.of(20L))
            .knowledgeBaseIds(List.of(100L))
            .structureNodeIds(List.of(202L))
            .maxPerAnchor(2)
            .maxTotal(4)
            .maxChars(512)
            .build());

        assertThat(expanded).extracting(Document::getId).containsExactly("structure-chunk-2004");
        Document evidence = expanded.get(0);
        assertThat(evidence.getText()).contains("父子块配置错误").doesNotContain("# 14.1.2");
        assertThat(evidence.getMetadata())
            .containsEntry(DocumentKnowledgeMetadataKeys.CHUNK_TYPE, "LIST")
            .containsEntry(DocumentKnowledgeMetadataKeys.STRUCTURE_ANCHOR_RAW_BODY, true)
            .containsEntry(DocumentKnowledgeMetadataKeys.STRUCTURE_BODY_RESOLVED_FROM, "CONTINUATION_LIST");
    }

    private static Map<String, Object> metadata(Object... values) {
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private static SuperAgentDocumentChunk chunk(Long id,
                                                 Long documentId,
                                                 Long taskId,
                                                 Long parentBlockId,
                                                 Integer chunkNo,
                                                 Long structureNodeId,
                                                 String sectionPath,
                                                 String canonicalPath,
                                                 String chunkType,
                                                 String chunkText) {
        SuperAgentDocumentChunk chunk = new SuperAgentDocumentChunk();
        chunk.setId(id);
        chunk.setDocumentId(documentId);
        chunk.setTaskId(taskId);
        chunk.setParentBlockId(parentBlockId);
        chunk.setChunkNo(chunkNo);
        chunk.setSourceType(1);
        chunk.setSectionPath(sectionPath);
        chunk.setStructureNodeId(structureNodeId);
        chunk.setCanonicalPath(canonicalPath);
        chunk.setChunkType(chunkType);
        chunk.setChunkText(chunkText);
        chunk.setContentWithWeight(chunkText);
        return chunk;
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
