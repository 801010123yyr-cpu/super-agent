package org.javaup.ai.manage.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentKgCanonicalEntityGroup;
import org.javaup.ai.manage.data.SuperAgentKgCanonicalEntityMember;
import org.javaup.ai.manage.data.SuperAgentKgEntity;
import org.javaup.ai.manage.data.SuperAgentKgEvidence;
import org.javaup.ai.manage.data.SuperAgentKgRelation;
import org.javaup.ai.manage.data.SuperAgentKgRelationGroup;
import org.javaup.ai.manage.data.SuperAgentKgRelationGroupMember;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgCanonicalEntityGroupMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgCanonicalEntityMemberMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgEntityMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgEvidenceMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgRelationGroupMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgRelationGroupMemberMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgRelationMapper;
import org.javaup.ai.manage.model.graph.GraphRagCrossDocumentIndexBuildResult;
import org.javaup.ai.manage.service.GraphRagCrossDocumentIndexService;
import org.javaup.ai.manage.support.GraphRagCrossDocumentIndex;
import org.javaup.ai.manage.support.GraphRagCrossDocumentIndexSupport;
import org.javaup.enums.BusinessStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class GraphRagCrossDocumentIndexServiceImpl implements GraphRagCrossDocumentIndexService {

    private static final String SCOPE_PREFIX_KNOWLEDGE = "knowledge:";
    private static final String DERIVED_INDEX_SOURCE_TYPE = "java.cross_document_index.v1";

    private final SuperAgentDocumentMapper documentMapper;
    private final SuperAgentKgEntityMapper entityMapper;
    private final SuperAgentKgRelationMapper relationMapper;
    private final SuperAgentKgEvidenceMapper evidenceMapper;
    private final SuperAgentKgCanonicalEntityGroupMapper canonicalGroupMapper;
    private final SuperAgentKgCanonicalEntityMemberMapper canonicalMemberMapper;
    private final SuperAgentKgRelationGroupMapper relationGroupMapper;
    private final SuperAgentKgRelationGroupMemberMapper relationGroupMemberMapper;
    private final GraphRagCrossDocumentIndexSupport indexSupport;
    private final UidGenerator uidGenerator;
    private final ObjectMapper objectMapper;

    public GraphRagCrossDocumentIndexServiceImpl(SuperAgentDocumentMapper documentMapper,
                                                 SuperAgentKgEntityMapper entityMapper,
                                                 SuperAgentKgRelationMapper relationMapper,
                                                 SuperAgentKgEvidenceMapper evidenceMapper,
                                                 SuperAgentKgCanonicalEntityGroupMapper canonicalGroupMapper,
                                                 SuperAgentKgCanonicalEntityMemberMapper canonicalMemberMapper,
                                                 SuperAgentKgRelationGroupMapper relationGroupMapper,
                                                 SuperAgentKgRelationGroupMemberMapper relationGroupMemberMapper,
                                                 GraphRagCrossDocumentIndexSupport indexSupport,
                                                 UidGenerator uidGenerator,
                                                 ObjectMapper objectMapper) {
        this.documentMapper = documentMapper;
        this.entityMapper = entityMapper;
        this.relationMapper = relationMapper;
        this.evidenceMapper = evidenceMapper;
        this.canonicalGroupMapper = canonicalGroupMapper;
        this.canonicalMemberMapper = canonicalMemberMapper;
        this.relationGroupMapper = relationGroupMapper;
        this.relationGroupMemberMapper = relationGroupMemberMapper;
        this.indexSupport = indexSupport;
        this.uidGenerator = uidGenerator;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<GraphRagCrossDocumentIndexBuildResult> rebuildAll() {
        List<SuperAgentKgEntity> entities = listEntities(null, null);
        List<SuperAgentKgRelation> relations = listRelations(null, null);
        List<SuperAgentKgEvidence> evidences = listEvidences(null, null);
        if (entities.isEmpty()) {
            deleteAllDerivedIndexes();
            return List.of(GraphRagCrossDocumentIndexBuildResult.builder()
                .scopeKey(GLOBAL_SCOPE_KEY)
                .build());
        }

        Map<Long, SuperAgentDocument> documentMap = listDocuments(entities.stream()
            .map(SuperAgentKgEntity::getDocumentId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new)));
        LinkedHashMap<String, ScopeDataset> scopes = buildScopes(entities, relations, evidences, documentMap);

        deleteAllDerivedIndexes();
        List<GraphRagCrossDocumentIndexBuildResult> results = new ArrayList<>();
        for (Map.Entry<String, ScopeDataset> entry : scopes.entrySet()) {
            results.add(persistScope(entry.getKey(), entry.getValue()));
        }
        log.info("GraphRAG 跨文档持久化图谱索引重建完成: scopeCount={}, results={}", results.size(), results);
        return results;
    }

    @Override
    @Transactional(readOnly = true)
    public GraphRagCrossDocumentIndex loadIndex(List<Long> documentIds, List<Long> taskIds) {
        if (CollUtil.isEmpty(documentIds)) {
            return GraphRagCrossDocumentIndex.empty();
        }
        String scopeKey = resolveLoadScopeKey(documentIds);
        return loadIndexByScope(scopeKey, documentIds, taskIds);
    }

    private GraphRagCrossDocumentIndex loadIndexByScope(String scopeKey, List<Long> documentIds, List<Long> taskIds) {
        List<SuperAgentKgCanonicalEntityMember> members = canonicalMemberMapper.selectList(new LambdaQueryWrapper<SuperAgentKgCanonicalEntityMember>()
            .eq(SuperAgentKgCanonicalEntityMember::getScopeKey, scopeKey)
            .in(SuperAgentKgCanonicalEntityMember::getDocumentId, documentIds)
            .eq(SuperAgentKgCanonicalEntityMember::getStatus, BusinessStatus.YES.getCode()));
        if (CollUtil.isNotEmpty(taskIds)) {
            Set<Long> taskIdSet = new LinkedHashSet<>(taskIds);
            members = members.stream().filter(member -> taskIdSet.contains(member.getTaskId())).toList();
        }
        if (members.isEmpty() && !GLOBAL_SCOPE_KEY.equals(scopeKey)) {
            return loadIndexByScope(GLOBAL_SCOPE_KEY, documentIds, taskIds);
        }
        if (members.isEmpty()) {
            return GraphRagCrossDocumentIndex.empty();
        }

        Set<Long> groupIds = members.stream()
            .map(SuperAgentKgCanonicalEntityMember::getGroupId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, SuperAgentKgCanonicalEntityGroup> groupById = canonicalGroupMapper.selectList(new LambdaQueryWrapper<SuperAgentKgCanonicalEntityGroup>()
                .eq(SuperAgentKgCanonicalEntityGroup::getScopeKey, scopeKey)
                .in(SuperAgentKgCanonicalEntityGroup::getId, groupIds)
                .eq(SuperAgentKgCanonicalEntityGroup::getStatus, BusinessStatus.YES.getCode()))
            .stream()
            .collect(Collectors.toMap(SuperAgentKgCanonicalEntityGroup::getId, item -> item, (left, right) -> left, LinkedHashMap::new));

        LinkedHashMap<Long, GraphRagCrossDocumentIndex.CanonicalEntityGroup> canonicalByEntityId = new LinkedHashMap<>();
        Map<String, List<SuperAgentKgCanonicalEntityMember>> membersByGroupKey = members.stream()
            .collect(Collectors.groupingBy(SuperAgentKgCanonicalEntityMember::getGroupKey, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<String, List<SuperAgentKgCanonicalEntityMember>> entry : membersByGroupKey.entrySet()) {
            SuperAgentKgCanonicalEntityMember first = entry.getValue().get(0);
            SuperAgentKgCanonicalEntityGroup groupRow = groupById.get(first.getGroupId());
            GraphRagCrossDocumentIndex.CanonicalEntityGroup group = toCanonicalGroup(entry.getKey(), groupRow, entry.getValue());
            for (SuperAgentKgCanonicalEntityMember member : entry.getValue()) {
                canonicalByEntityId.put(member.getEntityId(), group);
            }
        }

        Set<Long> relationIds = relationIdsByDocuments(documentIds, taskIds);
        LinkedHashMap<Long, GraphRagCrossDocumentIndex.RelationGroup> relationByRelationId = new LinkedHashMap<>();
        if (CollUtil.isNotEmpty(relationIds)) {
            List<SuperAgentKgRelationGroupMember> relationMembers = relationGroupMemberMapper.selectList(new LambdaQueryWrapper<SuperAgentKgRelationGroupMember>()
                .eq(SuperAgentKgRelationGroupMember::getScopeKey, scopeKey)
                .in(SuperAgentKgRelationGroupMember::getRelationId, relationIds)
                .eq(SuperAgentKgRelationGroupMember::getStatus, BusinessStatus.YES.getCode()));
            if (CollUtil.isNotEmpty(taskIds)) {
                Set<Long> taskIdSet = new LinkedHashSet<>(taskIds);
                relationMembers = relationMembers.stream()
                    .filter(member -> taskIdSet.contains(member.getTaskId()))
                    .toList();
            }
            Set<Long> relationGroupIds = relationMembers.stream()
                .map(SuperAgentKgRelationGroupMember::getGroupId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            Map<Long, SuperAgentKgRelationGroup> relationGroupById = relationGroupIds.isEmpty()
                ? Map.of()
                : relationGroupMapper.selectList(new LambdaQueryWrapper<SuperAgentKgRelationGroup>()
                    .eq(SuperAgentKgRelationGroup::getScopeKey, scopeKey)
                    .in(SuperAgentKgRelationGroup::getId, relationGroupIds)
                    .eq(SuperAgentKgRelationGroup::getStatus, BusinessStatus.YES.getCode()))
                .stream()
                .collect(Collectors.toMap(SuperAgentKgRelationGroup::getId, item -> item, (left, right) -> left, LinkedHashMap::new));
            Map<String, List<SuperAgentKgRelationGroupMember>> relationMembersByGroupKey = relationMembers.stream()
                .collect(Collectors.groupingBy(SuperAgentKgRelationGroupMember::getGroupKey, LinkedHashMap::new, Collectors.toList()));
            for (Map.Entry<String, List<SuperAgentKgRelationGroupMember>> entry : relationMembersByGroupKey.entrySet()) {
                SuperAgentKgRelationGroupMember first = entry.getValue().get(0);
                SuperAgentKgRelationGroup groupRow = relationGroupById.get(first.getGroupId());
                GraphRagCrossDocumentIndex.RelationGroup group = toRelationGroup(entry.getKey(), groupRow, entry.getValue());
                for (SuperAgentKgRelationGroupMember member : entry.getValue()) {
                    relationByRelationId.put(member.getRelationId(), group);
                }
            }
        }
        return new GraphRagCrossDocumentIndex(canonicalByEntityId, relationByRelationId);
    }

    private GraphRagCrossDocumentIndexBuildResult persistScope(String scopeKey, ScopeDataset dataset) {
        GraphRagCrossDocumentIndex index = indexSupport.build(dataset.entities(), dataset.relations(), dataset.evidences());
        Map<String, GraphRagCrossDocumentIndex.CanonicalEntityGroup> canonicalGroups = index.distinctCanonicalGroups();
        int memberCount = 0;
        for (GraphRagCrossDocumentIndex.CanonicalEntityGroup group : canonicalGroups.values()) {
            Long groupId = uidGenerator.getUid();
            canonicalGroupMapper.insert(toCanonicalGroupRow(scopeKey, groupId, group));
            for (Long entityId : group.entityIds()) {
                SuperAgentKgEntity entity = dataset.entityById().get(entityId);
                if (entity == null) {
                    continue;
                }
                canonicalMemberMapper.insert(toCanonicalMemberRow(scopeKey, groupId, group, entity));
                memberCount++;
            }
        }

        Map<String, GraphRagCrossDocumentIndex.RelationGroup> relationGroups = index.distinctRelationGroups();
        int relationMemberCount = 0;
        for (GraphRagCrossDocumentIndex.RelationGroup group : relationGroups.values()) {
            Long groupId = uidGenerator.getUid();
            relationGroupMapper.insert(toRelationGroupRow(scopeKey, groupId, group));
            for (Long relationId : group.relationIds()) {
                SuperAgentKgRelation relation = dataset.relationById().get(relationId);
                if (relation == null) {
                    continue;
                }
                relationGroupMemberMapper.insert(toRelationGroupMemberRow(scopeKey, groupId, group, relation));
                relationMemberCount++;
            }
        }

        return GraphRagCrossDocumentIndexBuildResult.builder()
            .scopeKey(scopeKey)
            .canonicalGroupCount(canonicalGroups.size())
            .canonicalMemberCount(memberCount)
            .relationGroupCount(relationGroups.size())
            .relationGroupMemberCount(relationMemberCount)
            .build();
    }

    private LinkedHashMap<String, ScopeDataset> buildScopes(List<SuperAgentKgEntity> entities,
                                                            List<SuperAgentKgRelation> relations,
                                                            List<SuperAgentKgEvidence> evidences,
                                                            Map<Long, SuperAgentDocument> documentMap) {
        LinkedHashMap<String, ScopeDataset> scopes = new LinkedHashMap<>();
        scopes.put(GLOBAL_SCOPE_KEY, ScopeDataset.of(entities, relations, evidences));

        Map<Long, String> scopeByDocumentId = new LinkedHashMap<>();
        for (SuperAgentDocument document : documentMap.values()) {
            String scopeCode = StrUtil.blankToDefault(document.getKnowledgeScopeCode(), "").trim();
            if (StrUtil.isNotBlank(scopeCode)) {
                scopeByDocumentId.put(document.getId(), SCOPE_PREFIX_KNOWLEDGE + scopeCode);
            }
        }
        if (scopeByDocumentId.isEmpty()) {
            return scopes;
        }
        Map<String, List<SuperAgentKgEntity>> entitiesByScope = entities.stream()
            .filter(entity -> scopeByDocumentId.containsKey(entity.getDocumentId()))
            .collect(Collectors.groupingBy(entity -> scopeByDocumentId.get(entity.getDocumentId()), LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<String, List<SuperAgentKgEntity>> entry : entitiesByScope.entrySet()) {
            Set<Long> documentIds = entry.getValue().stream()
                .map(SuperAgentKgEntity::getDocumentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            List<SuperAgentKgRelation> scopedRelations = relations.stream()
                .filter(relation -> documentIds.contains(relation.getDocumentId()))
                .toList();
            List<SuperAgentKgEvidence> scopedEvidences = evidences.stream()
                .filter(evidence -> documentIds.contains(evidence.getDocumentId()))
                .toList();
            scopes.put(entry.getKey(), ScopeDataset.of(entry.getValue(), scopedRelations, scopedEvidences));
        }
        return scopes;
    }

    private String resolveLoadScopeKey(List<Long> documentIds) {
        Map<Long, SuperAgentDocument> documents = listDocuments(new LinkedHashSet<>(documentIds));
        LinkedHashSet<String> scopeCodes = documents.values().stream()
            .map(SuperAgentDocument::getKnowledgeScopeCode)
            .filter(StrUtil::isNotBlank)
            .map(String::trim)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (scopeCodes.size() == 1) {
            return SCOPE_PREFIX_KNOWLEDGE + scopeCodes.iterator().next();
        }
        return GLOBAL_SCOPE_KEY;
    }

    private List<SuperAgentKgEntity> listEntities(List<Long> documentIds, List<Long> taskIds) {
        LambdaQueryWrapper<SuperAgentKgEntity> wrapper = new LambdaQueryWrapper<SuperAgentKgEntity>()
            .eq(SuperAgentKgEntity::getStatus, BusinessStatus.YES.getCode());
        if (CollUtil.isNotEmpty(documentIds)) {
            wrapper.in(SuperAgentKgEntity::getDocumentId, documentIds);
        }
        if (CollUtil.isNotEmpty(taskIds)) {
            wrapper.in(SuperAgentKgEntity::getTaskId, taskIds);
        }
        return entityMapper.selectList(wrapper);
    }

    private List<SuperAgentKgRelation> listRelations(List<Long> documentIds, List<Long> taskIds) {
        LambdaQueryWrapper<SuperAgentKgRelation> wrapper = new LambdaQueryWrapper<SuperAgentKgRelation>()
            .eq(SuperAgentKgRelation::getStatus, BusinessStatus.YES.getCode());
        if (CollUtil.isNotEmpty(documentIds)) {
            wrapper.in(SuperAgentKgRelation::getDocumentId, documentIds);
        }
        if (CollUtil.isNotEmpty(taskIds)) {
            wrapper.in(SuperAgentKgRelation::getTaskId, taskIds);
        }
        return relationMapper.selectList(wrapper);
    }

    private List<SuperAgentKgEvidence> listEvidences(List<Long> documentIds, List<Long> taskIds) {
        LambdaQueryWrapper<SuperAgentKgEvidence> wrapper = new LambdaQueryWrapper<SuperAgentKgEvidence>()
            .eq(SuperAgentKgEvidence::getStatus, BusinessStatus.YES.getCode());
        if (CollUtil.isNotEmpty(documentIds)) {
            wrapper.in(SuperAgentKgEvidence::getDocumentId, documentIds);
        }
        if (CollUtil.isNotEmpty(taskIds)) {
            wrapper.in(SuperAgentKgEvidence::getTaskId, taskIds);
        }
        return evidenceMapper.selectList(wrapper);
    }

    private Set<Long> relationIdsByDocuments(List<Long> documentIds, List<Long> taskIds) {
        return listRelations(documentIds, taskIds).stream()
            .map(SuperAgentKgRelation::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Map<Long, SuperAgentDocument> listDocuments(Set<Long> documentIds) {
        if (CollUtil.isEmpty(documentIds)) {
            return Map.of();
        }
        return documentMapper.selectList(new LambdaQueryWrapper<SuperAgentDocument>()
                .in(SuperAgentDocument::getId, documentIds)
                .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode()))
            .stream()
            .collect(Collectors.toMap(SuperAgentDocument::getId, item -> item, (left, right) -> left, LinkedHashMap::new));
    }

    private SuperAgentKgCanonicalEntityGroup toCanonicalGroupRow(String scopeKey,
                                                                 Long groupId,
                                                                 GraphRagCrossDocumentIndex.CanonicalEntityGroup group) {
        SuperAgentKgCanonicalEntityGroup row = new SuperAgentKgCanonicalEntityGroup();
        row.setId(groupId);
        row.setScopeKey(scopeKey);
        row.setGroupKey(limit(group.key(), 255));
        row.setCanonicalName(limit(group.name(), 500));
        row.setEntityType(limit(group.entityType(), 64));
        row.setEntityCount(size(group.entityIds()));
        row.setDocumentCount(size(group.documentIds()));
        row.setTaskCount(size(group.taskIds()));
        row.setRankScore(decimal(group.rankScore()));
        row.setMetadataJson(writeJson(Map.of(
            "sourceType", "java.cross_document_index.v1",
            "variants", group.variants(),
            "entityIds", group.entityIds(),
            "documentIds", group.documentIds(),
            "taskIds", group.taskIds()
        )));
        row.setStatus(BusinessStatus.YES.getCode());
        return row;
    }

    private SuperAgentKgCanonicalEntityMember toCanonicalMemberRow(String scopeKey,
                                                                   Long groupId,
                                                                   GraphRagCrossDocumentIndex.CanonicalEntityGroup group,
                                                                   SuperAgentKgEntity entity) {
        SuperAgentKgCanonicalEntityMember row = new SuperAgentKgCanonicalEntityMember();
        row.setId(uidGenerator.getUid());
        row.setScopeKey(scopeKey);
        row.setGroupId(groupId);
        row.setGroupKey(limit(group.key(), 255));
        row.setEntityId(entity.getId());
        row.setDocumentId(entity.getDocumentId());
        row.setTaskId(entity.getTaskId());
        row.setEntityName(limit(entity.getName(), 500));
        row.setNormalizedName(limit(entity.getNormalizedName(), 500));
        row.setEntityType(limit(entity.getEntityType(), 64));
        row.setMetadataJson(writeJson(Map.of(
            "sourceType", "java.cross_document_index.v1",
            "canonicalName", group.name(),
            "rankScore", group.rankScore()
        )));
        row.setStatus(BusinessStatus.YES.getCode());
        return row;
    }

    private SuperAgentKgRelationGroup toRelationGroupRow(String scopeKey,
                                                         Long groupId,
                                                         GraphRagCrossDocumentIndex.RelationGroup group) {
        SuperAgentKgRelationGroup row = new SuperAgentKgRelationGroup();
        row.setId(groupId);
        row.setScopeKey(scopeKey);
        row.setGroupKey(relationGroupStorageKey(group.key()));
        row.setSourceGroupKey(limit(group.sourceGroupKey(), 255));
        row.setTargetGroupKey(limit(group.targetGroupKey(), 255));
        row.setRelationType(limit(group.relationType(), 64));
        row.setRelationCount(group.relationCount());
        row.setEvidenceCount(group.evidenceCount());
        row.setDocumentCount(group.documentCount());
        row.setRankScore(decimal(group.rankScore()));
        row.setMetadataJson(writeJson(Map.of(
            "sourceType", DERIVED_INDEX_SOURCE_TYPE,
            "naturalGroupKey", group.key(),
            "relationIds", group.relationIds(),
            "evidenceIds", group.evidenceIds(),
            "documentIds", group.documentIds()
        )));
        row.setStatus(BusinessStatus.YES.getCode());
        return row;
    }

    private SuperAgentKgRelationGroupMember toRelationGroupMemberRow(String scopeKey,
                                                                     Long groupId,
                                                                     GraphRagCrossDocumentIndex.RelationGroup group,
                                                                     SuperAgentKgRelation relation) {
        SuperAgentKgRelationGroupMember row = new SuperAgentKgRelationGroupMember();
        row.setId(uidGenerator.getUid());
        row.setScopeKey(scopeKey);
        row.setGroupId(groupId);
        row.setGroupKey(relationGroupStorageKey(group.key()));
        row.setRelationId(relation.getId());
        row.setDocumentId(relation.getDocumentId());
        row.setTaskId(relation.getTaskId());
        int evidenceCount = group.evidenceCountByRelationId().getOrDefault(relation.getId(), 0);
        row.setEvidenceCount(evidenceCount);
        row.setMetadataJson(writeJson(Map.of(
            "sourceType", DERIVED_INDEX_SOURCE_TYPE,
            "naturalGroupKey", group.key(),
            "relationType", StrUtil.blankToDefault(relation.getRelationType(), "ASSOCIATED_WITH"),
            "evidenceCount", evidenceCount,
            "rankScore", group.rankScore()
        )));
        row.setStatus(BusinessStatus.YES.getCode());
        return row;
    }

    private GraphRagCrossDocumentIndex.CanonicalEntityGroup toCanonicalGroup(String groupKey,
                                                                            SuperAgentKgCanonicalEntityGroup groupRow,
                                                                            List<SuperAgentKgCanonicalEntityMember> members) {
        Set<Long> entityIds = members.stream()
            .map(SuperAgentKgCanonicalEntityMember::getEntityId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> documentIds = members.stream()
            .map(SuperAgentKgCanonicalEntityMember::getDocumentId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> taskIds = members.stream()
            .map(SuperAgentKgCanonicalEntityMember::getTaskId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        SuperAgentKgCanonicalEntityMember first = members.get(0);
        return new GraphRagCrossDocumentIndex.CanonicalEntityGroup(
            groupKey,
            groupRow == null ? first.getEntityName() : groupRow.getCanonicalName(),
            groupRow == null ? first.getEntityType() : groupRow.getEntityType(),
            entityIds,
            documentIds,
            taskIds,
            List.of(),
            groupRow == null || groupRow.getRankScore() == null ? 0D : groupRow.getRankScore().doubleValue()
        );
    }

    private GraphRagCrossDocumentIndex.RelationGroup toRelationGroup(String groupKey,
                                                                     SuperAgentKgRelationGroup groupRow,
                                                                     List<SuperAgentKgRelationGroupMember> members) {
        Set<Long> relationIds = members.stream()
            .map(SuperAgentKgRelationGroupMember::getRelationId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> documentIds = members.stream()
            .map(SuperAgentKgRelationGroupMember::getDocumentId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        String metadataJson = groupRow == null ? null : groupRow.getMetadataJson();
        Map<String, Object> metadata = readMetadata(metadataJson);
        String naturalGroupKey = StrUtil.blankToDefault(stringValue(metadata.get("naturalGroupKey")), groupKey);
        Set<Long> evidenceIds = readLongSet(metadata, "evidenceIds");
        Map<Long, Integer> evidenceCountByRelationId = members.stream()
            .filter(member -> member.getRelationId() != null)
            .collect(Collectors.toMap(
                SuperAgentKgRelationGroupMember::getRelationId,
                member -> member.getEvidenceCount() == null ? 0 : member.getEvidenceCount(),
                Integer::sum,
                LinkedHashMap::new
            ));
        return new GraphRagCrossDocumentIndex.RelationGroup(
            naturalGroupKey,
            groupRow == null ? "" : groupRow.getSourceGroupKey(),
            groupRow == null ? "" : groupRow.getTargetGroupKey(),
            groupRow == null ? "" : groupRow.getRelationType(),
            relationIds,
            evidenceIds,
            documentIds,
            evidenceCountByRelationId,
            groupRow == null || groupRow.getRankScore() == null ? 0D : groupRow.getRankScore().doubleValue()
        );
    }

    private Set<Long> readLongSet(String metadataJson, String key) {
        return readLongSet(readMetadata(metadataJson), key);
    }

    private Set<Long> readLongSet(Map<String, Object> map, String key) {
        if (map.isEmpty()) {
            return Set.of();
        }
        Object value = map.get(key);
        if (!(value instanceof Collection<?> collection)) {
            return Set.of();
        }
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        for (Object item : collection) {
            if (item instanceof Number number) {
                result.add(number.longValue());
            }
            else if (item != null) {
                try {
                    result.add(Long.parseLong(String.valueOf(item)));
                }
                catch (NumberFormatException ignored) {
                    // Ignore malformed metadata values in the derived read model.
                }
            }
        }
        return result;
    }

    private Map<String, Object> readMetadata(String metadataJson) {
        if (StrUtil.isBlank(metadataJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metadataJson,
                objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        }
        catch (Exception exception) {
            return Map.of();
        }
    }

    private void deleteAllDerivedIndexes() {
        relationGroupMemberMapper.delete(new LambdaQueryWrapper<SuperAgentKgRelationGroupMember>()
            .isNotNull(SuperAgentKgRelationGroupMember::getId));
        relationGroupMapper.delete(new LambdaQueryWrapper<SuperAgentKgRelationGroup>()
            .isNotNull(SuperAgentKgRelationGroup::getId));
        canonicalMemberMapper.delete(new LambdaQueryWrapper<SuperAgentKgCanonicalEntityMember>()
            .isNotNull(SuperAgentKgCanonicalEntityMember::getId));
        canonicalGroupMapper.delete(new LambdaQueryWrapper<SuperAgentKgCanonicalEntityGroup>()
            .isNotNull(SuperAgentKgCanonicalEntityGroup::getId));
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    private int size(Collection<?> values) {
        return values == null ? 0 : values.size();
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("GraphRAG 跨文档派生索引 JSON 序列化失败", exception);
        }
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String relationGroupStorageKey(String naturalGroupKey) {
        return "sha256:" + sha256Hex(StrUtil.blankToDefault(naturalGroupKey, ""));
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用，无法生成 GraphRAG 跨文档关系组持久化 key", exception);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record ScopeDataset(List<SuperAgentKgEntity> entities,
                                List<SuperAgentKgRelation> relations,
                                List<SuperAgentKgEvidence> evidences,
                                Map<Long, SuperAgentKgEntity> entityById,
                                Map<Long, SuperAgentKgRelation> relationById) {

        private static ScopeDataset of(List<SuperAgentKgEntity> entities,
                                       List<SuperAgentKgRelation> relations,
                                       List<SuperAgentKgEvidence> evidences) {
            return new ScopeDataset(
                entities == null ? List.of() : entities,
                relations == null ? List.of() : relations,
                evidences == null ? List.of() : evidences,
                (entities == null ? List.<SuperAgentKgEntity>of() : entities).stream()
                    .filter(entity -> entity != null && entity.getId() != null)
                    .collect(Collectors.toMap(SuperAgentKgEntity::getId, item -> item, (left, right) -> left, LinkedHashMap::new)),
                (relations == null ? List.<SuperAgentKgRelation>of() : relations).stream()
                    .filter(relation -> relation != null && relation.getId() != null)
                    .collect(Collectors.toMap(SuperAgentKgRelation::getId, item -> item, (left, right) -> left, LinkedHashMap::new))
            );
        }
    }
}
