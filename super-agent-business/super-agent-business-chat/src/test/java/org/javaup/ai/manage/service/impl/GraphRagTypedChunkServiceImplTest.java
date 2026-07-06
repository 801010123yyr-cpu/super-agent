package org.javaup.ai.manage.service.impl;

import com.baidu.fsg.uid.UidGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.manage.data.SuperAgentDocumentChunk;
import org.javaup.ai.manage.data.SuperAgentKgCommunity;
import org.javaup.ai.manage.data.SuperAgentKgEntity;
import org.javaup.ai.manage.data.SuperAgentKgEvidence;
import org.javaup.ai.manage.data.SuperAgentKgRelation;
import org.javaup.ai.manage.mapper.SuperAgentKgCommunityMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgEntityMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgEvidenceMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgRelationMapper;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.javaup.ai.manage.support.GraphRagTypedChunkMetadataSupport;
import org.javaup.enums.DocumentChunkSourceTypeEnum;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class GraphRagTypedChunkServiceImplTest {

    @Test
    void buildTypedChunksProjectsKgRowsIntoUnifiedDocumentChunks() {
        ObjectMapper objectMapper = new ObjectMapper();
        GraphRagTypedChunkMetadataSupport metadataSupport = new GraphRagTypedChunkMetadataSupport(objectMapper);
        GraphRagTypedChunkServiceImpl service = new GraphRagTypedChunkServiceImpl(
            mapper(SuperAgentKgEntityMapper.class, entities(objectMapper)),
            mapper(SuperAgentKgRelationMapper.class, relations()),
            mapper(SuperAgentKgEvidenceMapper.class, evidences()),
            mapper(SuperAgentKgCommunityMapper.class, communities(objectMapper)),
            objectMapper,
            uidGenerator(),
            metadataSupport
        );

        List<SuperAgentDocumentChunk> typedChunks = service.buildTypedChunks(10L, 20L, 30L, List.of(sourceChunk()), 8);

        assertThat(typedChunks)
            .extracting(SuperAgentDocumentChunk::getChunkType)
            .containsExactly("GRAPH_ENTITY", "GRAPH_ENTITY", "GRAPH_RELATION", "GRAPH_COMMUNITY");
        assertThat(typedChunks)
            .extracting(SuperAgentDocumentChunk::getChunkNo)
            .containsExactly(8, 9, 10, 11);
        assertThat(typedChunks)
            .allSatisfy(chunk -> {
                assertThat(chunk.getSourceType()).isEqualTo(DocumentChunkSourceTypeEnum.GRAPH_RAG.getCode());
                assertThat(chunk.getParentBlockId()).isEqualTo(201L);
                assertThat(chunk.getVectorStatus()).isEqualTo(1);
                assertThat(chunk.getSourceBlockIds()).startsWith("{");
            });

        SuperAgentDocumentChunk entityChunk = typedChunks.get(0);
        assertThat(entityChunk.getChunkText()).contains("SuperAgent", "超级智能体", "证据", "图谱Rank");
        assertThat(entityChunk.getContentWithWeight()).contains("GraphRAG entity typed chunk", "SuperAgent", "图谱Rank");

        SuperAgentDocumentChunk relationChunk = typedChunks.get(2);
        assertThat(relationChunk.getChunkText()).contains("SuperAgent -[CALLS]-> RagTools", "图谱Rank");
        assertThat(relationChunk.getQuestions()).contains("SuperAgent 和 RagTools 是什么关系");

        SuperAgentDocumentChunk communityChunk = typedChunks.get(3);
        assertThat(communityChunk.getChunkText()).contains("GraphRAG 社区", "核心实体", "核心关系", "图谱Rank");

        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "DOCUMENT");
        metadataSupport.enrichMetadata(metadata, relationChunk.getChunkType(), relationChunk.getSourceBlockIds());
        assertThat(metadata)
            .containsEntry(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "GRAPH_RAG")
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_ENTITY_ID, 1001)
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_ENTITY_NAME, "SuperAgent")
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_RELATED_ENTITY_ID, 1002)
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_RELATED_ENTITY_NAME, "RagTools")
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_RELATION_ID, 2001)
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_RELATION_TYPE, "CALLS")
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_GRAPH_PATH, "SuperAgent -[CALLS]-> RagTools")
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_RANK_BOOST, 0.82);
    }

    @Test
    void enrichMetadataDoesNotOverwriteKnowledgeBaseMetadataWithLegacyBlankValues() {
        ObjectMapper objectMapper = new ObjectMapper();
        GraphRagTypedChunkMetadataSupport metadataSupport = new GraphRagTypedChunkMetadataSupport(objectMapper);
        Map<String, Object> legacySourceMetadata = new java.util.LinkedHashMap<>();
        legacySourceMetadata.put(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_ID, "");
        legacySourceMetadata.put(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_NAME, "");
        legacySourceMetadata.put(DocumentKnowledgeMetadataKeys.KG_ENTITY_NAME, "ReleaseControl");

        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "DOCUMENT");
        metadata.put(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_ID, 1001L);
        metadata.put(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_NAME, "生产运维知识库");

        metadataSupport.enrichMetadata(
            metadata,
            GraphRagTypedChunkMetadataSupport.CHUNK_TYPE_ENTITY,
            metadataSupport.writeSourceMetadata(legacySourceMetadata)
        );

        assertThat(metadata)
            .containsEntry(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "GRAPH_RAG")
            .containsEntry(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_ID, 1001L)
            .containsEntry(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_NAME, "生产运维知识库")
            .containsEntry(DocumentKnowledgeMetadataKeys.KG_ENTITY_NAME, "ReleaseControl");
    }

    private static List<SuperAgentKgEntity> entities(ObjectMapper objectMapper) {
        return List.of(
            entity(1001L, "SuperAgent", "SYSTEM", "超级智能体主链路。",
                writeJson(objectMapper, Map.of(
                    "aliases", List.of("超级智能体"),
                    "confidence", 0.91D,
                    "sourceChunkIds", List.of(101L),
                    "rankBoost", 0.93D,
                    "pagerank", 0.52D,
                    "rankPosition", 1,
                    "degree", 3
                ))),
            entity(1002L, "RagTools", "SYSTEM", "Python 工具化服务。",
                writeJson(objectMapper, Map.of(
                    "confidence", 0.87D,
                    "sourceChunkIds", List.of(101L),
                    "rankBoost", 0.71D,
                    "pagerank", 0.35D,
                    "rankPosition", 2,
                    "degree", 2
                )))
        );
    }

    private static List<SuperAgentKgRelation> relations() {
        SuperAgentKgRelation relation = new SuperAgentKgRelation();
        relation.setId(2001L);
        relation.setDocumentId(10L);
        relation.setTaskId(20L);
        relation.setSourceEntityId(1001L);
        relation.setTargetEntityId(1002L);
        relation.setRelationType("CALLS");
        relation.setDescription("SuperAgent 调用 RagTools。");
        relation.setWeight(BigDecimal.valueOf(0.95D));
        relation.setMetadataJson("{\"confidence\":0.93,\"rankBoost\":0.82}");
        return List.of(relation);
    }

    private static List<SuperAgentKgEvidence> evidences() {
        return List.of(
            evidence(3001L, 1001L, null, "超级智能体也称 SuperAgent。"),
            evidence(3002L, null, 2001L, "SuperAgent 调用 RagTools。"),
            evidence(3003L, null, 2001L, "超级智能体调用 RagTools 完成解析。")
        );
    }

    private static List<SuperAgentKgCommunity> communities(ObjectMapper objectMapper) {
        SuperAgentKgCommunity community = new SuperAgentKgCommunity();
        community.setId(4001L);
        community.setDocumentId(10L);
        community.setTaskId(20L);
        community.setCommunityNo(1);
        community.setTitle("SuperAgent / RagTools");
        community.setSummary("SuperAgent 与 RagTools 的调用关系社区。");
        community.setEntityIdsJson(writeJson(objectMapper, List.of(1001L, 1002L)));
        community.setRelationIdsJson(writeJson(objectMapper, List.of(2001L)));
        community.setEvidenceIdsJson(writeJson(objectMapper, List.of(3002L, 3003L)));
        community.setMetadataJson("{\"rankBoost\":0.88}");
        return List.of(community);
    }

    private static SuperAgentKgEntity entity(Long id,
                                             String name,
                                             String type,
                                             String description,
                                             String metadataJson) {
        SuperAgentKgEntity entity = new SuperAgentKgEntity();
        entity.setId(id);
        entity.setDocumentId(10L);
        entity.setTaskId(20L);
        entity.setName(name);
        entity.setNormalizedName(name.toLowerCase());
        entity.setEntityType(type);
        entity.setDescription(description);
        entity.setMetadataJson(metadataJson);
        return entity;
    }

    private static SuperAgentKgEvidence evidence(Long id, Long entityId, Long relationId, String quoteText) {
        SuperAgentKgEvidence evidence = new SuperAgentKgEvidence();
        evidence.setId(id);
        evidence.setDocumentId(10L);
        evidence.setTaskId(20L);
        evidence.setEntityId(entityId);
        evidence.setRelationId(relationId);
        evidence.setChunkId(101L);
        evidence.setParentBlockId(201L);
        evidence.setQuoteText(quoteText);
        evidence.setPageNo(2);
        evidence.setPageRange("2");
        evidence.setSectionPath("架构/GraphRAG");
        return evidence;
    }

    private static SuperAgentDocumentChunk sourceChunk() {
        SuperAgentDocumentChunk chunk = new SuperAgentDocumentChunk();
        chunk.setId(101L);
        chunk.setDocumentId(10L);
        chunk.setTaskId(20L);
        chunk.setPlanId(30L);
        chunk.setParentBlockId(201L);
        chunk.setChunkNo(1);
        chunk.setSectionPath("架构/GraphRAG");
        chunk.setStructureNodeId(301L);
        chunk.setStructureNodeType(1);
        chunk.setCanonicalPath("001/002");
        chunk.setItemIndex(1);
        chunk.setPageNo(2);
        chunk.setPageRange("2");
        chunk.setBboxJson("{\"x\":1}");
        chunk.setChunkText("SuperAgent 调用 RagTools。");
        return chunk;
    }

    @SuppressWarnings("unchecked")
    private static <T> T mapper(Class<T> mapperType, List<?> rows) {
        return (T) Proxy.newProxyInstance(
            mapperType.getClassLoader(),
            new Class<?>[]{mapperType},
            (proxy, method, args) -> {
                if ("selectList".equals(method.getName())) {
                    return rows;
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

    private static UidGenerator uidGenerator() {
        AtomicLong sequence = new AtomicLong(9000L);
        return (UidGenerator) Proxy.newProxyInstance(
            UidGenerator.class.getClassLoader(),
            new Class<?>[]{UidGenerator.class},
            (proxy, method, args) -> {
                if ("getUid".equals(method.getName()) || "getId".equals(method.getName())) {
                    return sequence.incrementAndGet();
                }
                if ("parseUid".equals(method.getName())) {
                    return String.valueOf(args[0]);
                }
                if ("toString".equals(method.getName())) {
                    return "UidGeneratorProxy";
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

    private static String writeJson(ObjectMapper objectMapper, Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
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
