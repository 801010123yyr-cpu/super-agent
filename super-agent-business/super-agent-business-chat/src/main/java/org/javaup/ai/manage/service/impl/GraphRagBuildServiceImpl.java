package org.javaup.ai.manage.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.data.SuperAgentDocumentChunk;
import org.javaup.ai.manage.data.SuperAgentKgCommunity;
import org.javaup.ai.manage.data.SuperAgentKgEntity;
import org.javaup.ai.manage.data.SuperAgentKgEvidence;
import org.javaup.ai.manage.data.SuperAgentKgRelation;
import org.javaup.ai.manage.mapper.SuperAgentKgCommunityMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgEntityMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgEvidenceMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgRelationMapper;
import org.javaup.ai.manage.model.graph.GraphRagBuildResult;
import org.javaup.ai.manage.service.GraphRagBuildService;
import org.javaup.ai.ragtools.client.RagToolsClient;
import org.javaup.ai.ragtools.model.RagToolsGraphExtractRequest;
import org.javaup.ai.ragtools.model.RagToolsGraphExtractResponse;
import org.javaup.enums.BusinessStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@AllArgsConstructor
@Service
public class GraphRagBuildServiceImpl implements GraphRagBuildService {

    private final SuperAgentKgEntityMapper entityMapper;

    private final SuperAgentKgRelationMapper relationMapper;

    private final SuperAgentKgEvidenceMapper evidenceMapper;

    private final SuperAgentKgCommunityMapper communityMapper;

    private final RagToolsClient ragToolsClient;

    private final ObjectMapper objectMapper;

    private final UidGenerator uidGenerator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GraphRagBuildResult rebuildDocumentGraph(Long documentId, Long taskId, List<SuperAgentDocumentChunk> chunks) {
        deleteByTask(documentId, taskId);
        if (documentId == null || taskId == null || CollUtil.isEmpty(chunks)) {
            return GraphRagBuildResult.builder().build();
        }

        RagToolsGraphExtractRequest request = buildRequest(documentId, taskId, chunks);
        RagToolsGraphExtractResponse response = ragToolsClient.extractGraph(request);
        if (response == null) {
            throw new IllegalStateException("Python GraphRAG 抽取接口返回为空。");
        }

        Map<String, Long> entityIdMap = saveEntities(documentId, taskId, response.getEntities());
        Map<String, Long> relationIdMap = saveRelations(documentId, taskId, response.getRelations(), entityIdMap);
        Map<String, Long> evidenceIdMap = saveEvidences(documentId, taskId, response.getEvidences(), entityIdMap, relationIdMap);
        int communityCount = saveCommunities(documentId, taskId, response.getCommunities(), entityIdMap, relationIdMap, evidenceIdMap);

        GraphRagBuildResult result = GraphRagBuildResult.builder()
            .entityCount(entityIdMap.size())
            .relationCount(relationIdMap.size())
            .evidenceCount(evidenceIdMap.size())
            .communityCount(communityCount)
            .build();
        log.info("GraphRAG 实体关系图谱构建完成: documentId={}, taskId={}, result={}", documentId, taskId, result);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByTask(Long documentId, Long taskId) {
        if (documentId == null || taskId == null) {
            return;
        }
        communityMapper.delete(new LambdaQueryWrapper<SuperAgentKgCommunity>()
            .eq(SuperAgentKgCommunity::getDocumentId, documentId)
            .eq(SuperAgentKgCommunity::getTaskId, taskId));
        evidenceMapper.delete(new LambdaQueryWrapper<SuperAgentKgEvidence>()
            .eq(SuperAgentKgEvidence::getDocumentId, documentId)
            .eq(SuperAgentKgEvidence::getTaskId, taskId));
        relationMapper.delete(new LambdaQueryWrapper<SuperAgentKgRelation>()
            .eq(SuperAgentKgRelation::getDocumentId, documentId)
            .eq(SuperAgentKgRelation::getTaskId, taskId));
        entityMapper.delete(new LambdaQueryWrapper<SuperAgentKgEntity>()
            .eq(SuperAgentKgEntity::getDocumentId, documentId)
            .eq(SuperAgentKgEntity::getTaskId, taskId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        communityMapper.delete(new LambdaQueryWrapper<SuperAgentKgCommunity>()
            .eq(SuperAgentKgCommunity::getDocumentId, documentId));
        evidenceMapper.delete(new LambdaQueryWrapper<SuperAgentKgEvidence>()
            .eq(SuperAgentKgEvidence::getDocumentId, documentId));
        relationMapper.delete(new LambdaQueryWrapper<SuperAgentKgRelation>()
            .eq(SuperAgentKgRelation::getDocumentId, documentId));
        entityMapper.delete(new LambdaQueryWrapper<SuperAgentKgEntity>()
            .eq(SuperAgentKgEntity::getDocumentId, documentId));
    }

    private RagToolsGraphExtractRequest buildRequest(Long documentId, Long taskId, List<SuperAgentDocumentChunk> chunks) {
        RagToolsGraphExtractRequest request = new RagToolsGraphExtractRequest();
        request.setDocumentId(documentId);
        request.setTaskId(taskId);

        List<RagToolsGraphExtractRequest.Chunk> requestChunks = new ArrayList<>();
        for (SuperAgentDocumentChunk chunk : chunks) {
            if (chunk == null || chunk.getId() == null || StrUtil.isBlank(chunk.getChunkText())) {
                continue;
            }
            RagToolsGraphExtractRequest.Chunk requestChunk = new RagToolsGraphExtractRequest.Chunk();
            requestChunk.setChunkId(chunk.getId());
            requestChunk.setParentBlockId(chunk.getParentBlockId());
            requestChunk.setChunkNo(chunk.getChunkNo());
            requestChunk.setChunkType(chunk.getChunkType());
            requestChunk.setTitle(chunk.getTitle());
            requestChunk.setSectionPath(chunk.getSectionPath());
            requestChunk.setPageNo(chunk.getPageNo());
            requestChunk.setPageRange(chunk.getPageRange());
            requestChunk.setBboxJson(chunk.getBboxJson());
            requestChunk.setText(chunk.getChunkText());
            requestChunk.setContentWithWeight(StrUtil.blankToDefault(chunk.getContentWithWeight(), chunk.getChunkText()));
            requestChunk.setSourceBlockIds(chunk.getSourceBlockIds());
            requestChunk.setMetadata(chunkMetadata(chunk));
            requestChunks.add(requestChunk);
        }
        request.setChunks(requestChunks);
        return request;
    }

    private Map<String, Object> chunkMetadata(SuperAgentDocumentChunk chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("planId", chunk.getPlanId());
        metadata.put("sourceType", chunk.getSourceType());
        metadata.put("structureNodeId", chunk.getStructureNodeId());
        metadata.put("structureNodeType", chunk.getStructureNodeType());
        metadata.put("canonicalPath", chunk.getCanonicalPath());
        metadata.put("itemIndex", chunk.getItemIndex());
        metadata.put("keywords", chunk.getKeywords());
        metadata.put("questions", chunk.getQuestions());
        return metadata;
    }

    private Map<String, Long> saveEntities(Long documentId,
                                           Long taskId,
                                           List<RagToolsGraphExtractResponse.Entity> entities) {
        Map<String, Long> entityIdMap = new LinkedHashMap<>();
        if (CollUtil.isEmpty(entities)) {
            return entityIdMap;
        }
        for (RagToolsGraphExtractResponse.Entity extracted : entities) {
            if (extracted == null || StrUtil.isBlank(extracted.getId()) || StrUtil.isBlank(extracted.getName())) {
                continue;
            }
            Long entityId = uidGenerator.getUid();
            SuperAgentKgEntity entity = new SuperAgentKgEntity();
            entity.setId(entityId);
            entity.setDocumentId(documentId);
            entity.setTaskId(taskId);
            entity.setEntityKey(limit(extracted.getId(), 255));
            entity.setName(limit(extracted.getName(), 500));
            entity.setNormalizedName(limit(StrUtil.blankToDefault(extracted.getNormalizedName(), extracted.getName()), 500));
            entity.setEntityType(limit(StrUtil.blankToDefault(extracted.getType(), "CONCEPT"), 64));
            entity.setDescription(limit(extracted.getDescription(), 1000));
            entity.setMetadataJson(writeJson(metadata(
                "sourceEntityId", extracted.getId(),
                "sourceChunkIds", extracted.getSourceChunkIds(),
                "evidenceIds", extracted.getEvidenceIds(),
                "sourceMetadata", extracted.getMetadata()
            )));
            entity.setStatus(BusinessStatus.YES.getCode());
            entityMapper.insert(entity);
            entityIdMap.put(extracted.getId(), entityId);
        }
        return entityIdMap;
    }

    private Map<String, Long> saveRelations(Long documentId,
                                            Long taskId,
                                            List<RagToolsGraphExtractResponse.Relation> relations,
                                            Map<String, Long> entityIdMap) {
        Map<String, Long> relationIdMap = new LinkedHashMap<>();
        if (CollUtil.isEmpty(relations)) {
            return relationIdMap;
        }
        for (RagToolsGraphExtractResponse.Relation extracted : relations) {
            if (extracted == null || StrUtil.isBlank(extracted.getId())) {
                continue;
            }
            Long sourceEntityId = entityIdMap.get(extracted.getSourceEntityId());
            Long targetEntityId = entityIdMap.get(extracted.getTargetEntityId());
            if (sourceEntityId == null || targetEntityId == null) {
                log.warn("跳过缺少端点实体的 GraphRAG 关系: documentId={}, taskId={}, relationId={}, source={}, target={}",
                    documentId, taskId, extracted.getId(), extracted.getSourceEntityId(), extracted.getTargetEntityId());
                continue;
            }

            Long relationId = uidGenerator.getUid();
            SuperAgentKgRelation relation = new SuperAgentKgRelation();
            relation.setId(relationId);
            relation.setDocumentId(documentId);
            relation.setTaskId(taskId);
            relation.setSourceEntityId(sourceEntityId);
            relation.setTargetEntityId(targetEntityId);
            relation.setRelationType(limit(StrUtil.blankToDefault(extracted.getRelationType(), "ASSOCIATED_WITH"), 64));
            relation.setDescription(limit(extracted.getDescription(), 1000));
            relation.setWeight(weight(extracted.getWeight()));
            relation.setMetadataJson(writeJson(metadata(
                "sourceRelationId", extracted.getId(),
                "sourceEntityId", extracted.getSourceEntityId(),
                "targetEntityId", extracted.getTargetEntityId(),
                "evidenceIds", extracted.getEvidenceIds(),
                "sourceMetadata", extracted.getMetadata()
            )));
            relation.setStatus(BusinessStatus.YES.getCode());
            relationMapper.insert(relation);
            relationIdMap.put(extracted.getId(), relationId);
        }
        return relationIdMap;
    }

    private Map<String, Long> saveEvidences(Long documentId,
                                            Long taskId,
                                            List<RagToolsGraphExtractResponse.Evidence> evidences,
                                            Map<String, Long> entityIdMap,
                                            Map<String, Long> relationIdMap) {
        Map<String, Long> evidenceIdMap = new LinkedHashMap<>();
        if (CollUtil.isEmpty(evidences)) {
            return evidenceIdMap;
        }
        for (RagToolsGraphExtractResponse.Evidence extracted : evidences) {
            if (extracted == null || StrUtil.isBlank(extracted.getId())) {
                continue;
            }
            Long entityId = StrUtil.isBlank(extracted.getEntityId()) ? null : entityIdMap.get(extracted.getEntityId());
            Long relationId = StrUtil.isBlank(extracted.getRelationId()) ? null : relationIdMap.get(extracted.getRelationId());
            if (entityId == null && relationId == null) {
                continue;
            }

            Long evidenceId = uidGenerator.getUid();
            SuperAgentKgEvidence evidence = new SuperAgentKgEvidence();
            evidence.setId(evidenceId);
            evidence.setDocumentId(documentId);
            evidence.setTaskId(taskId);
            evidence.setEntityId(entityId);
            evidence.setRelationId(relationId);
            evidence.setChunkId(extracted.getChunkId());
            evidence.setParentBlockId(extracted.getParentBlockId());
            evidence.setQuoteText(extracted.getQuoteText());
            evidence.setPageNo(extracted.getPageNo());
            evidence.setPageRange(limit(extracted.getPageRange(), 64));
            evidence.setBboxJson(extracted.getBboxJson());
            evidence.setSectionPath(limit(extracted.getSectionPath(), 1000));
            evidence.setMetadataJson(writeJson(metadata(
                "sourceEvidenceId", extracted.getId(),
                "sourceEntityId", extracted.getEntityId(),
                "sourceRelationId", extracted.getRelationId(),
                "sourceMetadata", extracted.getMetadata()
            )));
            evidence.setStatus(BusinessStatus.YES.getCode());
            evidenceMapper.insert(evidence);
            evidenceIdMap.put(extracted.getId(), evidenceId);
        }
        return evidenceIdMap;
    }

    private int saveCommunities(Long documentId,
                                Long taskId,
                                List<RagToolsGraphExtractResponse.Community> communities,
                                Map<String, Long> entityIdMap,
                                Map<String, Long> relationIdMap,
                                Map<String, Long> evidenceIdMap) {
        if (CollUtil.isEmpty(communities)) {
            return 0;
        }
        int communityNo = 1;
        for (RagToolsGraphExtractResponse.Community extracted : communities) {
            if (extracted == null || StrUtil.isBlank(extracted.getId())) {
                continue;
            }
            List<Long> entityIds = remapIds(extracted.getEntityIds(), entityIdMap);
            if (entityIds.isEmpty()) {
                continue;
            }

            SuperAgentKgCommunity community = new SuperAgentKgCommunity();
            community.setId(uidGenerator.getUid());
            community.setDocumentId(documentId);
            community.setTaskId(taskId);
            community.setCommunityNo(communityNo++);
            community.setTitle(limit(StrUtil.blankToDefault(extracted.getTitle(), "未命名图谱社区"), 500));
            community.setSummary(extracted.getSummary());
            community.setEntityIdsJson(writeJson(entityIds));
            community.setRelationIdsJson(writeJson(remapIds(extracted.getRelationIds(), relationIdMap)));
            community.setEvidenceIdsJson(writeJson(remapIds(extracted.getEvidenceIds(), evidenceIdMap)));
            community.setMetadataJson(writeJson(metadata(
                "sourceCommunityId", extracted.getId(),
                "sourceEntityIds", extracted.getEntityIds(),
                "sourceRelationIds", extracted.getRelationIds(),
                "sourceEvidenceIds", extracted.getEvidenceIds(),
                "sourceMetadata", extracted.getMetadata()
            )));
            community.setStatus(BusinessStatus.YES.getCode());
            communityMapper.insert(community);
        }
        return communityNo - 1;
    }

    private List<Long> remapIds(List<String> sourceIds, Map<String, Long> idMap) {
        if (CollUtil.isEmpty(sourceIds)) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (String sourceId : sourceIds) {
            Long id = idMap.get(sourceId);
            if (id != null) {
                result.add(id);
            }
        }
        return result;
    }

    private BigDecimal weight(Double value) {
        double weight = value == null ? 1.0D : value;
        return BigDecimal.valueOf(weight).setScale(4, RoundingMode.HALF_UP);
    }

    private Map<String, Object> metadata(Object... keyValues) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            Object value = keyValues[index + 1];
            if (value != null) {
                metadata.put(String.valueOf(keyValues[index]), value);
            }
        }
        return metadata;
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("GraphRAG 元数据 JSON 序列化失败", exception);
        }
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
