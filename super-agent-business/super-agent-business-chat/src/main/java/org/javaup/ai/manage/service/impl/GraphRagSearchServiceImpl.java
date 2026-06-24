package org.javaup.ai.manage.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.data.SuperAgentKgCommunity;
import org.javaup.ai.manage.data.SuperAgentKgEntity;
import org.javaup.ai.manage.data.SuperAgentKgEvidence;
import org.javaup.ai.manage.data.SuperAgentKgRelation;
import org.javaup.ai.manage.mapper.SuperAgentKgCommunityMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgEntityMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgEvidenceMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgRelationMapper;
import org.javaup.ai.manage.model.graph.GraphRagQueryCatalog;
import org.javaup.ai.manage.model.graph.GraphRagQueryPlanAdvice;
import org.javaup.ai.manage.model.graph.GraphRagSearchResult;
import org.javaup.ai.manage.service.GraphRagQueryPlanAdvisor;
import org.javaup.ai.manage.service.GraphRagSearchService;
import org.javaup.enums.BusinessStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
public class GraphRagSearchServiceImpl implements GraphRagSearchService {

    private static final Pattern ENGLISH_TERM_PATTERN = Pattern.compile("\\b[A-Za-z][A-Za-z0-9._/-]{1,40}\\b");
    private static final double ADVISOR_CONFIDENCE_THRESHOLD = 0.58D;
    private static final int CATALOG_ENTITY_LIMIT = 80;
    private static final int CATALOG_RELATION_LIMIT = 120;
    private static final int CATALOG_COMMUNITY_LIMIT = 40;
    private static final Set<String> TECHNICAL_ENTITY_LABELS = Set.of(
        "title", "section", "content", "keywords", "questions", "chunktype", "chunk_type",
        "text", "metadata", "page", "bbox", "ct"
    );
    private static final Set<String> GENERATED_ENTITY_SOURCES = Set.of(
        "metadata.question", "metadata.keyword", "metadata.autoquestion", "metadata.autokeyword"
    );
    private static final Set<String> GENERATED_EVIDENCE_MARKERS = Set.of(
        "[QUESTIONS]", "[KEYWORDS]"
    );

    private final SuperAgentKgEntityMapper entityMapper;

    private final SuperAgentKgRelationMapper relationMapper;

    private final SuperAgentKgEvidenceMapper evidenceMapper;

    private final SuperAgentKgCommunityMapper communityMapper;

    private final ObjectMapper objectMapper;

    private final GraphRagQueryPlanAdvisor queryPlanAdvisor;

    @Autowired
    public GraphRagSearchServiceImpl(SuperAgentKgEntityMapper entityMapper,
                                     SuperAgentKgRelationMapper relationMapper,
                                     SuperAgentKgEvidenceMapper evidenceMapper,
                                     SuperAgentKgCommunityMapper communityMapper,
                                     ObjectMapper objectMapper,
                                     ObjectProvider<GraphRagQueryPlanAdvisor> queryPlanAdvisorProvider) {
        this(
            entityMapper,
            relationMapper,
            evidenceMapper,
            communityMapper,
            objectMapper,
            queryPlanAdvisorProvider == null ? null : queryPlanAdvisorProvider.getIfAvailable()
        );
    }

    public GraphRagSearchServiceImpl(SuperAgentKgEntityMapper entityMapper,
                                     SuperAgentKgRelationMapper relationMapper,
                                     SuperAgentKgEvidenceMapper evidenceMapper,
                                     SuperAgentKgCommunityMapper communityMapper,
                                     ObjectMapper objectMapper) {
        this(entityMapper, relationMapper, evidenceMapper, communityMapper, objectMapper, (GraphRagQueryPlanAdvisor) null);
    }

    GraphRagSearchServiceImpl(SuperAgentKgEntityMapper entityMapper,
                              SuperAgentKgRelationMapper relationMapper,
                              SuperAgentKgEvidenceMapper evidenceMapper,
                              SuperAgentKgCommunityMapper communityMapper,
                              ObjectMapper objectMapper,
                              GraphRagQueryPlanAdvisor queryPlanAdvisor) {
        this.entityMapper = entityMapper;
        this.relationMapper = relationMapper;
        this.evidenceMapper = evidenceMapper;
        this.communityMapper = communityMapper;
        this.objectMapper = objectMapper;
        this.queryPlanAdvisor = queryPlanAdvisor;
    }

    @Override
    @Transactional(readOnly = true)
    public List<GraphRagSearchResult> search(String question,
                                             List<Long> documentIds,
                                             List<Long> taskIds,
                                             int topK,
                                             int maxHops) {
        if (StrUtil.isBlank(question) || CollUtil.isEmpty(documentIds) || topK <= 0) {
            return List.of();
        }

        List<String> terms = extractTerms(question);
        String normalizedQuestion = normalize(question);
        QueryProfile queryProfile = analyzeQuery(question, terms, maxHops);
        List<SuperAgentKgEntity> allEntities = listEntities(documentIds, taskIds);
        Map<Long, SuperAgentKgEntity> entityMap = allEntities.stream()
            .collect(Collectors.toMap(SuperAgentKgEntity::getId, item -> item, (left, right) -> left, LinkedHashMap::new));
        CanonicalEntityIndex canonicalIndex = buildCanonicalEntityIndex(allEntities);
        List<SuperAgentKgCommunity> allCommunities = listCommunities(documentIds, taskIds);
        List<SuperAgentKgRelation> loadedRelations = List.of();
        if (shouldAskAdvisor(question, queryProfile, allEntities, allCommunities)) {
            loadedRelations = listAllRelations(documentIds, taskIds);
            queryProfile = applyAdvisorProfile(
                question,
                queryProfile,
                allEntities,
                loadedRelations,
                allCommunities,
                topK,
                maxHops
            );
        }
        QueryProfile effectiveQueryProfile = queryProfile;
        List<GraphRagSearchResult> communityResults = searchCommunityReports(allCommunities, topK, normalizedQuestion, terms, effectiveQueryProfile);
        List<ScoredEntity> seedEntities = allEntities.stream()
            .map(entity -> new ScoredEntity(entity, scoreEntity(entity, normalizedQuestion, terms, effectiveQueryProfile)))
            .filter(item -> item.score() > 0D)
            .sorted(Comparator.comparingDouble(ScoredEntity::score).reversed())
            .limit(Math.max(topK * 4L, 12L))
            .toList();
        List<SuperAgentKgRelation> allRelations = effectiveQueryProfile.relationQuestion()
            ? loadedOrQueryRelations(documentIds, taskIds, loadedRelations)
            : List.of();
        List<ScoredRelation> seedRelations = allRelations.stream()
            .map(relation -> new ScoredRelation(
                relation,
                scoreRelation(relation, entityMap, normalizedQuestion, terms, effectiveQueryProfile)
            ))
            .filter(item -> item.score() > 0D)
            .sorted(Comparator.comparingDouble(ScoredRelation::score).reversed())
            .limit(Math.max(topK * 4L, 12L))
            .toList();
        if (seedEntities.isEmpty() && seedRelations.isEmpty()) {
            return limitResults(communityResults, topK);
        }

        Map<Long, Double> seedScoreMap = seedEntities.stream()
            .collect(Collectors.toMap(item -> item.entity().getId(), ScoredEntity::score, Math::max, LinkedHashMap::new));
        Map<Long, Double> relationSeedScoreMap = seedRelations.stream()
            .collect(Collectors.toMap(item -> item.relation().getId(), ScoredRelation::score, Math::max, LinkedHashMap::new));
        seedScoreMap = expandCanonicalSeedScores(seedScoreMap, canonicalIndex);
        Set<Long> frontierEntityIds = seedEntities.stream()
            .map(item -> item.entity().getId())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        frontierEntityIds.addAll(seedRelations.stream()
            .flatMap(item -> relatedEntityIds(List.of(item.relation())).stream())
            .collect(Collectors.toCollection(LinkedHashSet::new)));
        frontierEntityIds.addAll(seedScoreMap.keySet());
        frontierEntityIds = expandCanonicalEntityIds(frontierEntityIds, canonicalIndex);

        Map<Long, SuperAgentKgRelation> relationMap = new LinkedHashMap<>();
        Map<Long, Integer> relationHopMap = new LinkedHashMap<>();
        seedRelations.forEach(item -> {
            relationMap.put(item.relation().getId(), item.relation());
            relationHopMap.put(item.relation().getId(), 0);
        });
        List<SuperAgentKgRelation> oneHopRelations = listRelations(documentIds, taskIds, frontierEntityIds);
        oneHopRelations.forEach(relation -> {
            relationMap.put(relation.getId(), relation);
            relationHopMap.putIfAbsent(relation.getId(), 1);
        });

        if (Math.max(effectiveQueryProfile.maxHops(), 1) >= 2 && !oneHopRelations.isEmpty()) {
            Set<Long> neighborEntityIds = relatedEntityIds(oneHopRelations);
            neighborEntityIds.removeAll(frontierEntityIds);
            if (!neighborEntityIds.isEmpty()) {
                listRelations(documentIds, taskIds, neighborEntityIds)
                    .forEach(relation -> {
                        relationMap.putIfAbsent(relation.getId(), relation);
                        relationHopMap.putIfAbsent(relation.getId(), 2);
                    });
            }
        }

        Set<Long> relationIds = relationMap.keySet();
        Set<Long> evidenceEntityIds = new LinkedHashSet<>(frontierEntityIds);
        evidenceEntityIds.addAll(relatedEntityIds(relationMap.values()));
        List<SuperAgentKgEvidence> evidences = listEvidences(documentIds, taskIds, evidenceEntityIds, relationIds);
        if (evidences.isEmpty()) {
            return limitResults(communityResults, topK);
        }

        RelationGroupIndex relationGroupIndex = buildRelationGroupIndex(relationMap.values(), evidences, entityMap, canonicalIndex);
        Map<Long, GraphRagSearchResult> resultMap = new LinkedHashMap<>();
        for (SuperAgentKgEvidence evidence : evidences) {
            if (!isGraphEvidenceUsable(evidence)) {
                continue;
            }
            GraphRagSearchResult result = toResult(evidence, entityMap, relationMap, relationHopMap,
                seedScoreMap, relationSeedScoreMap, canonicalIndex, relationGroupIndex, terms);
            if (result == null) {
                continue;
            }
            resultMap.merge(evidence.getId(), result,
                (left, right) -> left.getScore() >= right.getScore() ? left : right);
        }

        List<GraphRagSearchResult> results = new ArrayList<>(resultMap.values());
        results.addAll(communityResults);
        return limitResults(results, topK);
    }

    private List<GraphRagSearchResult> limitResults(List<GraphRagSearchResult> results, int topK) {
        if (CollUtil.isEmpty(results) || topK <= 0) {
            return List.of();
        }
        return results.stream()
            .sorted(Comparator.comparingDouble(GraphRagSearchResult::getScore).reversed())
            .limit(topK)
            .toList();
    }

    private List<GraphRagSearchResult> searchCommunityReports(List<SuperAgentKgCommunity> communities,
                                                              int topK,
                                                              String normalizedQuestion,
                                                              List<String> terms,
                                                              QueryProfile queryProfile) {
        if (communities.isEmpty()) {
            return List.of();
        }
        List<ScoredCommunity> scoredCommunities = communities.stream()
            .map(community -> new ScoredCommunity(community, scoreCommunity(community, normalizedQuestion, terms, queryProfile)))
            .filter(item -> item.score() > 0D)
            .sorted(Comparator.comparingDouble(ScoredCommunity::score).reversed())
            .limit(Math.max(topK, 3))
            .toList();
        if (scoredCommunities.isEmpty()) {
            return List.of();
        }

        Set<Long> evidenceIds = scoredCommunities.stream()
            .flatMap(item -> readLongList(item.community().getEvidenceIdsJson()).stream())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (evidenceIds.isEmpty()) {
            return List.of();
        }
        Map<Long, SuperAgentKgEvidence> evidenceMap = listEvidencesByIds(evidenceIds).stream()
            .collect(Collectors.toMap(SuperAgentKgEvidence::getId, item -> item, (left, right) -> left, LinkedHashMap::new));

        List<GraphRagSearchResult> results = new ArrayList<>();
        for (ScoredCommunity scoredCommunity : scoredCommunities) {
            SuperAgentKgCommunity community = scoredCommunity.community();
            SuperAgentKgEvidence representativeEvidence = readLongList(community.getEvidenceIdsJson()).stream()
                .map(evidenceMap::get)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
            if (representativeEvidence == null) {
                continue;
            }
            double rankBoost = communityRankBoost(community);
            results.add(baseResult(representativeEvidence)
                .communityId(community.getId())
                .communityTitle(community.getTitle())
                .communitySummary(community.getSummary())
                .evidenceId(representativeEvidence.getId())
                .quoteText(representativeEvidence.getQuoteText())
                .graphPath("社区报告：" + StrUtil.blankToDefault(community.getTitle(), "未命名图谱社区"))
                .hopCount(0)
                .rankBoost(rankBoost)
                .score(scoredCommunity.score()
                    + graphRankScore(rankBoost)
                    + evidenceBoost(representativeEvidence.getQuoteText(), terms))
                .build());
        }
        return results;
    }

    private List<SuperAgentKgEntity> listEntities(List<Long> documentIds, List<Long> taskIds) {
        LambdaQueryWrapper<SuperAgentKgEntity> wrapper = new LambdaQueryWrapper<SuperAgentKgEntity>()
            .in(SuperAgentKgEntity::getDocumentId, documentIds)
            .eq(SuperAgentKgEntity::getStatus, BusinessStatus.YES.getCode());
        if (CollUtil.isNotEmpty(taskIds)) {
            wrapper.in(SuperAgentKgEntity::getTaskId, taskIds);
        }
        return entityMapper.selectList(wrapper);
    }

    private List<SuperAgentKgRelation> listRelations(List<Long> documentIds, List<Long> taskIds, Set<Long> entityIds) {
        if (CollUtil.isEmpty(entityIds)) {
            return List.of();
        }
        LambdaQueryWrapper<SuperAgentKgRelation> wrapper = new LambdaQueryWrapper<SuperAgentKgRelation>()
            .in(SuperAgentKgRelation::getDocumentId, documentIds)
            .eq(SuperAgentKgRelation::getStatus, BusinessStatus.YES.getCode())
            .and(query -> query
                .in(SuperAgentKgRelation::getSourceEntityId, entityIds)
                .or()
                .in(SuperAgentKgRelation::getTargetEntityId, entityIds));
        if (CollUtil.isNotEmpty(taskIds)) {
            wrapper.in(SuperAgentKgRelation::getTaskId, taskIds);
        }
        return relationMapper.selectList(wrapper);
    }

    private List<SuperAgentKgRelation> listAllRelations(List<Long> documentIds, List<Long> taskIds) {
        LambdaQueryWrapper<SuperAgentKgRelation> wrapper = new LambdaQueryWrapper<SuperAgentKgRelation>()
            .in(SuperAgentKgRelation::getDocumentId, documentIds)
            .eq(SuperAgentKgRelation::getStatus, BusinessStatus.YES.getCode());
        if (CollUtil.isNotEmpty(taskIds)) {
            wrapper.in(SuperAgentKgRelation::getTaskId, taskIds);
        }
        return relationMapper.selectList(wrapper);
    }

    private List<SuperAgentKgRelation> loadedOrQueryRelations(List<Long> documentIds,
                                                              List<Long> taskIds,
                                                              List<SuperAgentKgRelation> loadedRelations) {
        if (CollUtil.isNotEmpty(loadedRelations)) {
            return loadedRelations;
        }
        return listAllRelations(documentIds, taskIds);
    }

    private List<SuperAgentKgCommunity> listCommunities(List<Long> documentIds, List<Long> taskIds) {
        LambdaQueryWrapper<SuperAgentKgCommunity> wrapper = new LambdaQueryWrapper<SuperAgentKgCommunity>()
            .in(SuperAgentKgCommunity::getDocumentId, documentIds)
            .eq(SuperAgentKgCommunity::getStatus, BusinessStatus.YES.getCode());
        if (CollUtil.isNotEmpty(taskIds)) {
            wrapper.in(SuperAgentKgCommunity::getTaskId, taskIds);
        }
        return communityMapper.selectList(wrapper);
    }

    private List<SuperAgentKgEvidence> listEvidences(List<Long> documentIds,
                                                     List<Long> taskIds,
                                                     Set<Long> entityIds,
                                                     Set<Long> relationIds) {
        if (CollUtil.isEmpty(entityIds) && CollUtil.isEmpty(relationIds)) {
            return List.of();
        }
        LambdaQueryWrapper<SuperAgentKgEvidence> wrapper = new LambdaQueryWrapper<SuperAgentKgEvidence>()
            .in(SuperAgentKgEvidence::getDocumentId, documentIds)
            .eq(SuperAgentKgEvidence::getStatus, BusinessStatus.YES.getCode());
        if (CollUtil.isNotEmpty(taskIds)) {
            wrapper.in(SuperAgentKgEvidence::getTaskId, taskIds);
        }
        if (CollUtil.isNotEmpty(entityIds) && CollUtil.isNotEmpty(relationIds)) {
            wrapper.and(query -> query
                .in(SuperAgentKgEvidence::getEntityId, entityIds)
                .or()
                .in(SuperAgentKgEvidence::getRelationId, relationIds));
        }
        else if (CollUtil.isNotEmpty(entityIds)) {
            wrapper.in(SuperAgentKgEvidence::getEntityId, entityIds);
        }
        else {
            wrapper.in(SuperAgentKgEvidence::getRelationId, relationIds);
        }
        return evidenceMapper.selectList(wrapper);
    }

    private List<SuperAgentKgEvidence> listEvidencesByIds(Set<Long> evidenceIds) {
        if (CollUtil.isEmpty(evidenceIds)) {
            return List.of();
        }
        return evidenceMapper.selectList(new LambdaQueryWrapper<SuperAgentKgEvidence>()
            .in(SuperAgentKgEvidence::getId, evidenceIds)
            .eq(SuperAgentKgEvidence::getStatus, BusinessStatus.YES.getCode()));
    }

    private GraphRagSearchResult toResult(SuperAgentKgEvidence evidence,
                                          Map<Long, SuperAgentKgEntity> entityMap,
                                          Map<Long, SuperAgentKgRelation> relationMap,
                                          Map<Long, Integer> relationHopMap,
                                          Map<Long, Double> seedScoreMap,
                                          Map<Long, Double> relationSeedScoreMap,
                                          CanonicalEntityIndex canonicalIndex,
                                          RelationGroupIndex relationGroupIndex,
                                          List<String> terms) {
        if (evidence.getRelationId() != null) {
            SuperAgentKgRelation relation = relationMap.get(evidence.getRelationId());
            if (relation == null) {
                return null;
            }
            SuperAgentKgEntity source = entityMap.get(relation.getSourceEntityId());
            SuperAgentKgEntity target = entityMap.get(relation.getTargetEntityId());
            if (source == null || target == null) {
                return null;
            }
            if (!isGraphSearchEntityUsable(source) || !isGraphSearchEntityUsable(target)) {
                return null;
            }
            boolean sourceSeed = seedScoreMap.containsKey(source.getId());
            boolean targetSeed = seedScoreMap.containsKey(target.getId());
            SuperAgentKgEntity seed = sourceSeed || !targetSeed ? source : target;
            SuperAgentKgEntity related = sourceSeed || !targetSeed ? target : source;
            double seedScore = Math.max(
                seedScoreMap.getOrDefault(source.getId(), 0.25D),
                seedScoreMap.getOrDefault(target.getId(), 0.25D)
            );
            seedScore = Math.max(seedScore, relationSeedScoreMap.getOrDefault(relation.getId(), 0D));
            int hopCount = relationHopMap.getOrDefault(relation.getId(), 1);
            double hopPenalty = hopCount <= 1 ? 0D : 0.18D;
            double rankBoost = relationRankBoost(relation, source, target);
            RelationGroup relationGroup = relationGroupIndex == null ? null : relationGroupIndex.groupOf(relation.getId());
            double groupBoost = relationGroupBoost(relationGroup);
            double score = seedScore
                + relationWeight(relation.getWeight())
                + graphRankScore(rankBoost)
                + evidenceBoost(evidence.getQuoteText(), terms)
                + groupBoost
                - hopPenalty;
            GraphRagSearchResult.GraphRagSearchResultBuilder builder = baseResult(evidence)
                .entityId(seed.getId())
                .entityName(seed.getName())
                .relationId(relation.getId())
                .relationType(relation.getRelationType())
                .relatedEntityId(related.getId())
                .relatedEntityName(related.getName())
                .graphPath((hopCount <= 0 ? "关系匹配：" : hopCount <= 1 ? "一跳：" : "二跳：")
                    + source.getName() + " --" + relation.getRelationType() + "--> " + target.getName())
                .hopCount(hopCount)
                .rankBoost(rankBoost)
                .score(score);
            builder = withRelationGroup(builder, relationGroup);
            return withCanonicalEntity(builder, seed, canonicalIndex).build();
        }

        if (evidence.getEntityId() != null) {
            SuperAgentKgEntity entity = entityMap.get(evidence.getEntityId());
            if (entity == null) {
                return null;
            }
            if (!isGraphSearchEntityUsable(entity)) {
                return null;
            }
            double rankBoost = entityRankBoost(entity);
            double score = seedScoreMap.getOrDefault(entity.getId(), 0.35D)
                + graphRankScore(rankBoost)
                + evidenceBoost(evidence.getQuoteText(), terms);
            GraphRagSearchResult.GraphRagSearchResultBuilder builder = baseResult(evidence)
                .entityId(entity.getId())
                .entityName(entity.getName())
                .graphPath(entity.getName())
                .hopCount(0)
                .rankBoost(rankBoost)
                .score(score);
            return withCanonicalEntity(builder, entity, canonicalIndex).build();
        }
        return null;
    }

    private RelationGroupIndex buildRelationGroupIndex(Collection<SuperAgentKgRelation> relations,
                                                       List<SuperAgentKgEvidence> evidences,
                                                       Map<Long, SuperAgentKgEntity> entityMap,
                                                       CanonicalEntityIndex canonicalIndex) {
        if (CollUtil.isEmpty(relations)) {
            return RelationGroupIndex.empty();
        }
        Map<Long, Set<Long>> evidenceIdsByRelationId = new LinkedHashMap<>();
        Map<Long, Set<Long>> documentIdsByRelationId = new LinkedHashMap<>();
        for (SuperAgentKgEvidence evidence : evidences) {
            if (evidence == null || evidence.getRelationId() == null) {
                continue;
            }
            if (!isGraphEvidenceUsable(evidence)) {
                continue;
            }
            if (evidence.getId() != null) {
                evidenceIdsByRelationId.computeIfAbsent(evidence.getRelationId(), ignored -> new LinkedHashSet<>())
                    .add(evidence.getId());
            }
            if (evidence.getDocumentId() != null) {
                documentIdsByRelationId.computeIfAbsent(evidence.getRelationId(), ignored -> new LinkedHashSet<>())
                    .add(evidence.getDocumentId());
            }
        }

        Map<String, RelationGroupAccumulator> accumulators = new LinkedHashMap<>();
        for (SuperAgentKgRelation relation : relations) {
            if (relation == null || relation.getId() == null) {
                continue;
            }
            SuperAgentKgEntity source = entityMap.get(relation.getSourceEntityId());
            SuperAgentKgEntity target = entityMap.get(relation.getTargetEntityId());
            if (source == null || target == null) {
                continue;
            }
            if (!isGraphSearchEntityUsable(source) || !isGraphSearchEntityUsable(target)) {
                continue;
            }
            String groupKey = relationGroupKey(relation, source, target, canonicalIndex);
            RelationGroupAccumulator accumulator = accumulators.computeIfAbsent(groupKey, RelationGroupAccumulator::new);
            accumulator.addRelation(relation);
            accumulator.addEvidenceIds(evidenceIdsByRelationId.get(relation.getId()));
            accumulator.addDocumentIds(documentIdsByRelationId.get(relation.getId()));
            if (relation.getDocumentId() != null) {
                accumulator.addDocumentId(relation.getDocumentId());
            }
        }

        Map<Long, RelationGroup> groupByRelationId = new LinkedHashMap<>();
        for (RelationGroupAccumulator accumulator : accumulators.values()) {
            RelationGroup group = accumulator.toGroup();
            for (Long relationId : accumulator.relationIds()) {
                groupByRelationId.put(relationId, group);
            }
        }
        return new RelationGroupIndex(groupByRelationId);
    }

    private String relationGroupKey(SuperAgentKgRelation relation,
                                    SuperAgentKgEntity source,
                                    SuperAgentKgEntity target,
                                    CanonicalEntityIndex canonicalIndex) {
        String sourceKey = canonicalEntityGroupKey(source, canonicalIndex);
        String targetKey = canonicalEntityGroupKey(target, canonicalIndex);
        String relationType = StrUtil.blankToDefault(relation.getRelationType(), "ASSOCIATED_WITH")
            .trim()
            .toUpperCase(Locale.ROOT);
        return sourceKey + "->" + relationType + "->" + targetKey;
    }

    private String canonicalEntityGroupKey(SuperAgentKgEntity entity, CanonicalEntityIndex canonicalIndex) {
        CanonicalEntityGroup group = canonicalIndex == null ? null : canonicalIndex.groupOf(entity == null ? null : entity.getId());
        if (group != null && StrUtil.isNotBlank(group.key())) {
            return group.key();
        }
        String entityType = canonicalEntityType(entity);
        String name = entity == null ? "" : StrUtil.blankToDefault(entity.getNormalizedName(), entity.getName());
        String normalizedName = normalizeCanonicalVariant(name);
        String key = entityType + ":" + normalizedName;
        if (!isCanonicalVariantUsable(normalizedName)) {
            Long entityId = entity == null ? null : entity.getId();
            key += "#" + (entityId == null ? "unknown" : entityId);
        }
        return key;
    }

    private double relationGroupBoost(RelationGroup group) {
        if (group == null) {
            return 0D;
        }
        double boost = 0D;
        if (group.relationCount() > 1) {
            boost += Math.min(0.16D, (group.relationCount() - 1) * 0.06D);
        }
        if (group.evidenceCount() > 1) {
            boost += Math.min(0.12D, (group.evidenceCount() - 1) * 0.04D);
        }
        if (group.documentCount() > 1) {
            boost += Math.min(0.18D, (group.documentCount() - 1) * 0.09D);
        }
        return Math.min(0.32D, boost);
    }

    private GraphRagSearchResult.GraphRagSearchResultBuilder withRelationGroup(
        GraphRagSearchResult.GraphRagSearchResultBuilder builder,
        RelationGroup group
    ) {
        if (group == null
            || (group.relationCount() <= 1 && group.evidenceCount() <= 1 && group.documentCount() <= 1)) {
            return builder;
        }
        return builder
            .relationGroupKey(group.key())
            .relationGroupRelationCount(group.relationCount())
            .relationGroupEvidenceCount(group.evidenceCount())
            .relationGroupDocumentCount(group.documentCount());
    }

    private GraphRagSearchResult.GraphRagSearchResultBuilder withCanonicalEntity(GraphRagSearchResult.GraphRagSearchResultBuilder builder,
                                                                                SuperAgentKgEntity entity,
                                                                                CanonicalEntityIndex canonicalIndex) {
        CanonicalEntityGroup group = canonicalIndex == null ? null : canonicalIndex.groupOf(entity == null ? null : entity.getId());
        if (group == null || group.entityIds().size() <= 1) {
            return builder;
        }
        return builder
            .canonicalEntityKey(group.key())
            .canonicalEntityName(group.name())
            .canonicalEntityCount(group.entityIds().size())
            .canonicalDocumentCount(group.documentIds().size());
    }

    private GraphRagSearchResult.GraphRagSearchResultBuilder baseResult(SuperAgentKgEvidence evidence) {
        return GraphRagSearchResult.builder()
            .documentId(evidence.getDocumentId())
            .taskId(evidence.getTaskId())
            .evidenceId(evidence.getId())
            .chunkId(evidence.getChunkId())
            .parentBlockId(evidence.getParentBlockId())
            .quoteText(evidence.getQuoteText())
            .pageNo(evidence.getPageNo())
            .pageRange(evidence.getPageRange())
            .bboxJson(evidence.getBboxJson())
            .sectionPath(evidence.getSectionPath());
    }

    private Set<Long> relatedEntityIds(Iterable<SuperAgentKgRelation> relations) {
        Set<Long> entityIds = new LinkedHashSet<>();
        if (relations == null) {
            return entityIds;
        }
        for (SuperAgentKgRelation relation : relations) {
            if (relation == null) {
                continue;
            }
            entityIds.add(relation.getSourceEntityId());
            entityIds.add(relation.getTargetEntityId());
        }
        entityIds.removeIf(Objects::isNull);
        return entityIds;
    }

    private CanonicalEntityIndex buildCanonicalEntityIndex(List<SuperAgentKgEntity> entities) {
        if (CollUtil.isEmpty(entities)) {
            return CanonicalEntityIndex.empty();
        }
        Map<Long, Long> parent = new LinkedHashMap<>();
        Map<Long, SuperAgentKgEntity> entityById = new LinkedHashMap<>();
        for (SuperAgentKgEntity entity : entities) {
            if (entity == null || entity.getId() == null) {
                continue;
            }
            parent.put(entity.getId(), entity.getId());
            entityById.put(entity.getId(), entity);
        }
        Map<String, Long> firstEntityByVariant = new LinkedHashMap<>();
        for (SuperAgentKgEntity entity : entityById.values()) {
            for (String variantKey : canonicalEntityVariantKeys(entity)) {
                Long firstEntityId = firstEntityByVariant.putIfAbsent(variantKey, entity.getId());
                if (firstEntityId != null) {
                    union(parent, firstEntityId, entity.getId());
                }
            }
        }

        Map<Long, List<SuperAgentKgEntity>> grouped = new LinkedHashMap<>();
        for (SuperAgentKgEntity entity : entityById.values()) {
            grouped.computeIfAbsent(find(parent, entity.getId()), ignored -> new ArrayList<>()).add(entity);
        }
        Map<Long, CanonicalEntityGroup> groupByEntityId = new LinkedHashMap<>();
        for (List<SuperAgentKgEntity> groupEntities : grouped.values()) {
            if (groupEntities.isEmpty()) {
                continue;
            }
            String name = chooseCanonicalEntityName(groupEntities);
            String key = canonicalEntityGroupKey(groupEntities.get(0), name);
            Set<Long> entityIds = groupEntities.stream()
                .map(SuperAgentKgEntity::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<Long> groupDocumentIds = groupEntities.stream()
                .map(SuperAgentKgEntity::getDocumentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            CanonicalEntityGroup group = new CanonicalEntityGroup(key, name, entityIds, groupDocumentIds);
            for (Long entityId : entityIds) {
                groupByEntityId.put(entityId, group);
            }
        }
        return new CanonicalEntityIndex(groupByEntityId);
    }

    private String canonicalEntityGroupKey(SuperAgentKgEntity representative, String name) {
        String entityType = canonicalEntityType(representative);
        String normalizedName = normalizeCanonicalVariant(name);
        String key = entityType + ":" + normalizedName;
        if (!isCanonicalVariantUsable(normalizedName)) {
            Long entityId = representative == null ? null : representative.getId();
            key += "#" + (entityId == null ? "unknown" : entityId);
        }
        return key;
    }

    private Set<String> canonicalEntityVariantKeys(SuperAgentKgEntity entity) {
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        LinkedHashSet<String> lexicalVariants = new LinkedHashSet<>();
        if (!isGraphSearchEntityUsable(entity)) {
            return variants;
        }
        String entityType = canonicalEntityType(entity);
        addCanonicalNameVariant(lexicalVariants, entityType, entity.getName());
        addCanonicalNameVariant(lexicalVariants, entityType, entity.getNormalizedName());
        Map<String, Object> metadata = readMap(entity.getMetadataJson());
        addCanonicalNameVariant(lexicalVariants, entityType, stringValue(metadata.get("entityResolutionCanonicalName")));
        for (String alias : readStringList(metadata.get("aliases"))) {
            addCanonicalNameVariant(lexicalVariants, entityType, alias);
        }
        variants.addAll(lexicalVariants);
        if (!lexicalVariants.isEmpty()) {
            addCanonicalKeyVariant(variants, entity.getEntityKey());
            addCanonicalKeyVariant(variants, stringValue(metadata.get("canonicalKey")));
        }
        return variants;
    }

    private void addCanonicalNameVariant(Set<String> variants, String entityType, String value) {
        String normalized = normalizeCanonicalVariant(value);
        if (isCanonicalVariantUsable(normalized)) {
            variants.add(entityType + ":" + normalized);
            if (isCrossTypeCanonicalVariantUsable(normalized)) {
                variants.add("NAME:" + normalized);
            }
        }
    }

    private void addCanonicalKeyVariant(Set<String> variants, String value) {
        if (StrUtil.isNotBlank(value)) {
            variants.add("KEY:" + value.trim());
        }
    }

    private boolean isCanonicalVariantUsable(String normalized) {
        if (StrUtil.isBlank(normalized) || normalized.length() < 2 || normalized.length() > 80) {
            return false;
        }
        return !isTechnicalEntityName(normalized) && isDistinctiveCanonicalVariant(normalized);
    }

    private boolean isCrossTypeCanonicalVariantUsable(String normalized) {
        return isCanonicalVariantUsable(normalized)
            && normalized.length() >= 3
            && normalized.chars().anyMatch(ch -> (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9'));
    }

    private boolean isDistinctiveCanonicalVariant(String normalized) {
        if (StrUtil.isBlank(normalized)) {
            return false;
        }
        boolean hasLatinOrDigit = normalized.chars()
            .anyMatch(ch -> (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9'));
        if (hasLatinOrDigit) {
            return normalized.length() >= 2;
        }
        return normalized.codePointCount(0, normalized.length()) >= 3;
    }

    private String canonicalEntityType(SuperAgentKgEntity entity) {
        return StrUtil.blankToDefault(entity == null ? null : entity.getEntityType(), "CONCEPT")
            .trim()
            .toUpperCase(Locale.ROOT);
    }

    private String chooseCanonicalEntityName(List<SuperAgentKgEntity> entities) {
        for (SuperAgentKgEntity entity : entities) {
            String advisedName = stringValue(readMap(entity.getMetadataJson()).get("entityResolutionCanonicalName"));
            if (StrUtil.isNotBlank(advisedName)) {
                return advisedName;
            }
        }
        return entities.stream()
            .map(SuperAgentKgEntity::getName)
            .filter(StrUtil::isNotBlank)
            .findFirst()
            .orElse("unknown");
    }

    private String normalizeCanonicalVariant(String value) {
        return normalize(value).replaceAll("[<>《》/\\\\|?？!！]+", "");
    }

    private void union(Map<Long, Long> parent, Long left, Long right) {
        Long leftRoot = find(parent, left);
        Long rightRoot = find(parent, right);
        if (!Objects.equals(leftRoot, rightRoot)) {
            parent.put(rightRoot, leftRoot);
        }
    }

    private Long find(Map<Long, Long> parent, Long entityId) {
        if (entityId == null) {
            return null;
        }
        Long current = parent.get(entityId);
        if (current == null) {
            return entityId;
        }
        if (!Objects.equals(current, parent.get(current))) {
            current = find(parent, current);
            parent.put(entityId, current);
        }
        return current;
    }

    private Map<Long, Double> expandCanonicalSeedScores(Map<Long, Double> seedScoreMap,
                                                        CanonicalEntityIndex canonicalIndex) {
        if (seedScoreMap.isEmpty() || canonicalIndex == null || canonicalIndex.groupByEntityId().isEmpty()) {
            return seedScoreMap;
        }
        Map<Long, Double> expanded = new LinkedHashMap<>(seedScoreMap);
        for (Map.Entry<Long, Double> entry : seedScoreMap.entrySet()) {
            CanonicalEntityGroup group = canonicalIndex.groupOf(entry.getKey());
            if (group == null || group.entityIds().size() <= 1) {
                continue;
            }
            double expandedScore = Math.max(0.1D, entry.getValue() * 0.92D);
            for (Long entityId : group.entityIds()) {
                expanded.merge(entityId, expandedScore, Math::max);
            }
        }
        return expanded;
    }

    private Set<Long> expandCanonicalEntityIds(Set<Long> entityIds, CanonicalEntityIndex canonicalIndex) {
        if (CollUtil.isEmpty(entityIds) || canonicalIndex == null || canonicalIndex.groupByEntityId().isEmpty()) {
            return entityIds;
        }
        Set<Long> expanded = new LinkedHashSet<>(entityIds);
        for (Long entityId : entityIds) {
            CanonicalEntityGroup group = canonicalIndex.groupOf(entityId);
            if (group != null && group.entityIds().size() > 1) {
                expanded.addAll(group.entityIds());
            }
        }
        return expanded;
    }

    private double scoreEntity(SuperAgentKgEntity entity,
                               String normalizedQuestion,
                               List<String> terms,
                               QueryProfile queryProfile) {
        String normalizedName = normalize(StrUtil.blankToDefault(entity.getNormalizedName(), entity.getName()));
        String name = normalize(entity.getName());
        if (normalizedName.isBlank() && name.isBlank()) {
            return 0D;
        }

        double score = 0D;
        if (StrUtil.isNotBlank(normalizedName) && normalizedQuestion.contains(normalizedName)) {
            score += 1.0D;
        }
        if (StrUtil.isNotBlank(name) && normalizedQuestion.contains(name)) {
            score += 0.8D;
        }
        for (String alias : entityAliases(entity)) {
            String normalizedAlias = normalize(alias);
            if (normalizedAlias.length() >= 2 && normalizedQuestion.contains(normalizedAlias)) {
                score += 0.7D;
            }
        }
        String description = normalize(entity.getDescription());
        if (StrUtil.isNotBlank(description)) {
            for (String term : terms) {
                String normalizedTerm = normalize(term);
                if (normalizedTerm.length() >= 2 && description.contains(normalizedTerm)) {
                    score += 0.12D;
                }
            }
        }
        for (String term : terms) {
            String normalizedTerm = normalize(term);
            if (normalizedTerm.length() < 2) {
                continue;
            }
            if ((StrUtil.isNotBlank(name) && name.contains(normalizedTerm))
                || (StrUtil.isNotBlank(normalizedName) && normalizedName.contains(normalizedTerm))) {
                score += 0.35D;
            }
            else if ((StrUtil.isNotBlank(name) && normalizedTerm.contains(name))
                || (StrUtil.isNotBlank(normalizedName) && normalizedTerm.contains(normalizedName))) {
                score += 0.25D;
            }
        }
        if ("SECTION".equalsIgnoreCase(entity.getEntityType())) {
            score += 0.08D;
        }
        if (queryProfile.entityTypes().contains(StrUtil.blankToDefault(entity.getEntityType(), "").toUpperCase(Locale.ROOT))) {
            score += 0.22D;
        }
        if (queryProfile.entityIds().contains(entity.getId())) {
            score += 0.95D;
        }
        if (matchesAdvisorEntityName(entity, queryProfile.entityNames())) {
            score += 0.62D;
        }
        if (score <= 0D) {
            return 0D;
        }
        if (isLowDistinctivenessEntitySeed(entity)
            && !queryProfile.entityIds().contains(entity.getId())
            && !matchesAdvisorEntityName(entity, queryProfile.entityNames())) {
            score *= 0.62D;
        }
        score += Math.min(0.18D, metadataConfidence(entity.getMetadataJson()) * 0.18D);
        score += graphRankScore(entityRankBoost(entity)) * 0.45D;
        return Math.min(score, 2.2D);
    }

    private boolean isLowDistinctivenessEntitySeed(SuperAgentKgEntity entity) {
        if (entity == null) {
            return true;
        }
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        variants.add(normalizeCanonicalVariant(entity.getName()));
        variants.add(normalizeCanonicalVariant(StrUtil.blankToDefault(entity.getNormalizedName(), entity.getName())));
        for (String alias : entityAliases(entity)) {
            variants.add(normalizeCanonicalVariant(alias));
        }
        return variants.stream()
            .filter(StrUtil::isNotBlank)
            .noneMatch(this::isDistinctiveCanonicalVariant);
    }

    private boolean isGraphSearchEntityUsable(SuperAgentKgEntity entity) {
        if (entity == null) {
            return false;
        }
        String name = normalizeCanonicalVariant(entity.getName());
        String normalizedName = normalizeCanonicalVariant(StrUtil.blankToDefault(entity.getNormalizedName(), entity.getName()));
        return !isTechnicalEntityName(name)
            && !isTechnicalEntityName(normalizedName)
            && !isGeneratedQuestionEntity(entity, name)
            && !isGeneratedQuestionEntity(entity, normalizedName);
    }

    private boolean isTechnicalEntityName(String normalizedName) {
        if (StrUtil.isBlank(normalizedName)) {
            return false;
        }
        if (TECHNICAL_ENTITY_LABELS.contains(normalizedName)) {
            return true;
        }
        if (normalizedName.startsWith("type")) {
            String afterType = normalizedName.substring("type".length());
            if (TECHNICAL_ENTITY_LABELS.contains(afterType) || hasTechnicalLabelPrefix(afterType)) {
                return true;
            }
        }
        return hasTechnicalLabelPrefix(normalizedName);
    }

    private boolean hasTechnicalLabelPrefix(String normalizedName) {
        for (String label : TECHNICAL_ENTITY_LABELS) {
            if (!normalizedName.startsWith(label) || normalizedName.length() <= label.length()) {
                continue;
            }
            char next = normalizedName.charAt(label.length());
            boolean alphaNumericPayload = (next >= 'a' && next <= 'z') || (next >= '0' && next <= '9');
            if (!alphaNumericPayload) {
                return true;
            }
        }
        return false;
    }

    private boolean isGeneratedQuestionEntity(SuperAgentKgEntity entity, String normalizedName) {
        if (StrUtil.isBlank(normalizedName)) {
            return false;
        }
        if (isQuestionLikeEntityName(normalizedName)) {
            return true;
        }
        if (!hasGeneratedEntitySource(entity)) {
            return false;
        }
        return normalizedName.startsWith("关于")
            || normalizedName.contains("核心内容")
            || normalizedName.contains("要求或注意事项");
    }

    private boolean hasGeneratedEntitySource(SuperAgentKgEntity entity) {
        for (String source : entityCandidateSources(entity)) {
            String normalizedSource = StrUtil.blankToDefault(source, "").trim().toLowerCase(Locale.ROOT);
            if (GENERATED_ENTITY_SOURCES.contains(normalizedSource)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> entityCandidateSources(SuperAgentKgEntity entity) {
        if (entity == null) {
            return Set.of();
        }
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        Map<String, Object> metadata = readMap(entity.getMetadataJson());
        sources.addAll(readStringList(metadata.get("candidateSources")));
        Object sourceMetadata = metadata.get("sourceMetadata");
        if (sourceMetadata instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof Map<?, ?> map) {
                    sources.addAll(readStringList(map.get("candidateSources")));
                }
            }
        }
        return sources;
    }

    private boolean isGraphEvidenceUsable(SuperAgentKgEvidence evidence) {
        if (evidence == null || StrUtil.isBlank(evidence.getQuoteText())) {
            return false;
        }
        String upperQuote = evidence.getQuoteText().toUpperCase(Locale.ROOT);
        for (String marker : GENERATED_EVIDENCE_MARKERS) {
            if (upperQuote.contains(marker)) {
                return false;
            }
        }
        Map<String, Object> metadata = readMap(evidence.getMetadataJson());
        Object sourceMetadata = metadata.get("sourceMetadata");
        if (sourceMetadata instanceof Map<?, ?> map) {
            String relationPhrase = stringValue(map.get("relationPhrase"));
            return !isQuestionLikeEntityName(normalizeCanonicalVariant(relationPhrase));
        }
        return true;
    }

    private boolean isQuestionLikeEntityName(String normalizedName) {
        if (StrUtil.isBlank(normalizedName)) {
            return false;
        }
        return normalizedName.contains("哪些")
            || normalizedName.contains("什么")
            || normalizedName.contains("如何")
            || normalizedName.contains("怎么")
            || normalizedName.contains("是否")
            || normalizedName.contains("有没有");
    }

    private double scoreRelation(SuperAgentKgRelation relation,
                                 Map<Long, SuperAgentKgEntity> entityMap,
                                 String normalizedQuestion,
                                 List<String> terms,
                                 QueryProfile queryProfile) {
        SuperAgentKgEntity source = entityMap.get(relation.getSourceEntityId());
        SuperAgentKgEntity target = entityMap.get(relation.getTargetEntityId());
        if (source == null || target == null) {
            return 0D;
        }
        if (!isGraphSearchEntityUsable(source) || !isGraphSearchEntityUsable(target)) {
            return 0D;
        }
        String relationType = StrUtil.blankToDefault(relation.getRelationType(), "ASSOCIATED_WITH").toUpperCase(Locale.ROOT);
        String description = normalize(relation.getDescription());
        String sourceText = searchableEntityText(source);
        String targetText = searchableEntityText(target);
        String relationText = normalize(relationType + " " + StrUtil.blankToDefault(relation.getDescription(), "") + " " + sourceText + " " + targetText);

        double score = 0D;
        if (queryProfile.relationTypes().contains(relationType)) {
            score += 0.82D;
        }
        if (queryProfile.relationIds().contains(relation.getId())) {
            score += 0.95D;
        }
        if (StrUtil.isNotBlank(description) && normalizedQuestion.contains(description)) {
            score += 0.35D;
        }
        for (String term : terms) {
            String normalizedTerm = normalize(term);
            if (normalizedTerm.length() < 2) {
                continue;
            }
            if (sourceText.contains(normalizedTerm) || targetText.contains(normalizedTerm)) {
                score += 0.28D;
            }
            if (relationText.contains(normalizedTerm)) {
                score += 0.16D;
            }
        }
        if (!queryProfile.entityTypes().isEmpty()) {
            String sourceType = StrUtil.blankToDefault(source.getEntityType(), "").toUpperCase(Locale.ROOT);
            String targetType = StrUtil.blankToDefault(target.getEntityType(), "").toUpperCase(Locale.ROOT);
            if (queryProfile.entityTypes().contains(sourceType) || queryProfile.entityTypes().contains(targetType)) {
                score += 0.14D;
            }
        }
        if (score <= 0D) {
            return 0D;
        }
        if (queryProfile.relationQuestion() && !"ASSOCIATED_WITH".equals(relationType)) {
            score += 0.12D;
        }
        score += relationWeight(relation.getWeight()) * 0.55D;
        score += graphRankScore(relationRankBoost(relation, source, target)) * 0.40D;
        return Math.min(score, 2.0D);
    }

    private double relationWeight(BigDecimal weight) {
        if (weight == null) {
            return 0.35D;
        }
        return Math.min(0.6D, Math.max(0D, weight.doubleValue()) * 0.5D);
    }

    private double graphRankScore(double rankBoost) {
        return Math.min(0.22D, Math.max(0D, rankBoost) * 0.22D);
    }

    private double entityRankBoost(SuperAgentKgEntity entity) {
        Map<String, Object> metadata = readMap(entity == null ? null : entity.getMetadataJson());
        return numberValue(metadata.get("rankBoost"), 0D);
    }

    private double relationRankBoost(SuperAgentKgRelation relation,
                                     SuperAgentKgEntity source,
                                     SuperAgentKgEntity target) {
        Map<String, Object> metadata = readMap(relation == null ? null : relation.getMetadataJson());
        double relationRankBoost = numberValue(metadata.get("rankBoost"), -1D);
        if (relationRankBoost >= 0D) {
            return relationRankBoost;
        }
        return Math.max(entityRankBoost(source), entityRankBoost(target));
    }

    private double evidenceBoost(String quoteText, List<String> terms) {
        String normalizedQuote = normalize(quoteText);
        if (normalizedQuote.isBlank() || terms.isEmpty()) {
            return 0D;
        }
        double boost = 0D;
        for (String term : terms) {
            String normalizedTerm = normalize(term);
            if (normalizedTerm.length() >= 2 && normalizedQuote.contains(normalizedTerm)) {
                boost += 0.08D;
            }
        }
        return Math.min(0.32D, boost);
    }

    private double scoreCommunity(SuperAgentKgCommunity community,
                                  String normalizedQuestion,
                                  List<String> terms,
                                  QueryProfile queryProfile) {
        String title = normalize(community.getTitle());
        String summary = normalize(community.getSummary());
        if (title.isBlank() && summary.isBlank()) {
            return 0D;
        }
        double score = 0D;
        if (queryProfile.communityIds().contains(community.getId())) {
            score += 0.92D;
        }
        if (queryProfile.communityQuestion()) {
            score += 0.12D;
        }
        if (StrUtil.isNotBlank(title) && normalizedQuestion.contains(title)) {
            score += 0.8D;
        }
        for (String term : terms) {
            String normalizedTerm = normalize(term);
            if (normalizedTerm.length() < 2) {
                continue;
            }
            if (title.contains(normalizedTerm)) {
                score += 0.28D;
            }
            if (summary.contains(normalizedTerm)) {
                score += 0.18D;
            }
        }
        return Math.min(score, 1.2D);
    }

    private double communityRankBoost(SuperAgentKgCommunity community) {
        Map<String, Object> metadata = readMap(community == null ? null : community.getMetadataJson());
        return numberValue(metadata.get("rankBoost"), 0D);
    }

    private QueryProfile analyzeQuery(String question, List<String> terms, int requestedMaxHops) {
        String normalizedQuestion = normalize(question);
        LinkedHashSet<String> relationTypes = new LinkedHashSet<>();
        addRelationTypeIfPresent(relationTypes, normalizedQuestion, "依赖", "DEPENDS_ON");
        addRelationTypeIfPresent(relationTypes, normalizedQuestion, "依靠", "DEPENDS_ON");
        addRelationTypeIfPresent(relationTypes, normalizedQuestion, "调用", "CALLS");
        addRelationTypeIfPresent(relationTypes, normalizedQuestion, "使用", "USES");
        addRelationTypeIfPresent(relationTypes, normalizedQuestion, "采用", "USES");
        addRelationTypeIfPresent(relationTypes, normalizedQuestion, "支持", "SUPPORTS");
        addRelationTypeIfPresent(relationTypes, normalizedQuestion, "包含", "CONTAINS");
        addRelationTypeIfPresent(relationTypes, normalizedQuestion, "包括", "CONTAINS");
        addRelationTypeIfPresent(relationTypes, normalizedQuestion, "组成", "PART_OF");
        addRelationTypeIfPresent(relationTypes, normalizedQuestion, "属于", "BELONGS_TO");
        addRelationTypeIfPresent(relationTypes, normalizedQuestion, "负责", "RESPONSIBLE_FOR");
        addRelationTypeIfPresent(relationTypes, normalizedQuestion, "审批", "APPROVES");
        addRelationTypeIfPresent(relationTypes, normalizedQuestion, "批准", "APPROVES");
        addRelationTypeIfPresent(relationTypes, normalizedQuestion, "审核", "APPROVES");
        addRelationTypeIfPresent(relationTypes, normalizedQuestion, "执行", "EXECUTES");
        addRelationTypeIfPresent(relationTypes, normalizedQuestion, "触发", "TRIGGERS");
        addRelationTypeIfPresent(relationTypes, normalizedQuestion, "发起", "TRIGGERS");
        addRelationTypeIfPresent(relationTypes, normalizedQuestion, "记录", "RECORDS");
        addRelationTypeIfPresent(relationTypes, normalizedQuestion, "存放", "STORES");
        addRelationTypeIfPresent(relationTypes, normalizedQuestion, "归档", "ARCHIVES");
        addRelationTypeIfPresent(relationTypes, normalizedQuestion, "回收", "REVOKES");
        addRelationTypeIfPresent(relationTypes, normalizedQuestion, "关联", "RELATED_TO");
        addRelationTypeIfPresent(relationTypes, normalizedQuestion, "关系", "RELATED_TO");
        addRelationTypeIfPresent(relationTypes, normalizedQuestion, "映射", "MAPS_TO");
        addRelationTypeIfPresent(relationTypes, normalizedQuestion, "生成", "PRODUCES");
        addRelationTypeIfPresent(relationTypes, normalizedQuestion, "输出", "PRODUCES");
        addRelationTypeIfPresent(relationTypes, normalizedQuestion, "输入", "CONSUMES");
        LinkedHashSet<String> entityTypes = new LinkedHashSet<>();
        addEntityTypeIfPresent(entityTypes, normalizedQuestion, "系统", "SYSTEM");
        addEntityTypeIfPresent(entityTypes, normalizedQuestion, "平台", "SYSTEM");
        addEntityTypeIfPresent(entityTypes, normalizedQuestion, "服务", "SYSTEM");
        addEntityTypeIfPresent(entityTypes, normalizedQuestion, "模块", "SYSTEM");
        addEntityTypeIfPresent(entityTypes, normalizedQuestion, "组件", "SYSTEM");
        addEntityTypeIfPresent(entityTypes, normalizedQuestion, "接口", "SYSTEM");
        addEntityTypeIfPresent(entityTypes, normalizedQuestion, "部门", "ORG");
        addEntityTypeIfPresent(entityTypes, normalizedQuestion, "团队", "ORG");
        addEntityTypeIfPresent(entityTypes, normalizedQuestion, "公司", "ORG");
        addEntityTypeIfPresent(entityTypes, normalizedQuestion, "组织", "ORG");
        addEntityTypeIfPresent(entityTypes, normalizedQuestion, "流程", "PROCESS");
        addEntityTypeIfPresent(entityTypes, normalizedQuestion, "规则", "PROCESS");
        addEntityTypeIfPresent(entityTypes, normalizedQuestion, "策略", "PROCESS");
        addEntityTypeIfPresent(entityTypes, normalizedQuestion, "权限", "PROCESS");
        addEntityTypeIfPresent(entityTypes, normalizedQuestion, "指标", "METRIC");
        addEntityTypeIfPresent(entityTypes, normalizedQuestion, "金额", "METRIC");
        addEntityTypeIfPresent(entityTypes, normalizedQuestion, "数量", "METRIC");

        boolean relationQuestion = !relationTypes.isEmpty()
            || normalizedQuestion.contains("上下游")
            || normalizedQuestion.contains("链路")
            || normalizedQuestion.contains("路径");
        boolean communityQuestion = normalizedQuestion.contains("社区")
            || normalizedQuestion.contains("主题")
            || normalizedQuestion.contains("整体")
            || normalizedQuestion.contains("全局")
            || normalizedQuestion.contains("总结");
        return new QueryProfile(
            new LinkedHashSet<>(relationTypes),
            new LinkedHashSet<>(entityTypes),
            new LinkedHashSet<>(),
            new LinkedHashSet<>(),
            new LinkedHashSet<>(),
            new LinkedHashSet<>(),
            relationQuestion,
            communityQuestion,
            normalizeMaxHops(requestedMaxHops)
        );
    }

    private boolean shouldAskAdvisor(String question,
                                     QueryProfile queryProfile,
                                     List<SuperAgentKgEntity> entities,
                                     List<SuperAgentKgCommunity> communities) {
        if (queryPlanAdvisor == null
            || StrUtil.isBlank(question)
            || (CollUtil.isEmpty(entities) && CollUtil.isEmpty(communities))) {
            return false;
        }
        String normalizedQuestion = normalize(question);
        boolean graphCue = queryProfile.relationQuestion()
            || queryProfile.communityQuestion()
            || !queryProfile.entityTypes().isEmpty()
            || normalizedQuestion.contains("图谱")
            || normalizedQuestion.contains("实体")
            || normalizedQuestion.contains("关系")
            || normalizedQuestion.contains("上下游")
            || normalizedQuestion.contains("链路")
            || normalizedQuestion.contains("路径");
        if (graphCue) {
            return true;
        }
        return CollUtil.isNotEmpty(communities)
            && (normalizedQuestion.contains("主题") || normalizedQuestion.contains("总结"));
    }

    private QueryProfile applyAdvisorProfile(String question,
                                             QueryProfile baseProfile,
                                             List<SuperAgentKgEntity> entities,
                                             List<SuperAgentKgRelation> relations,
                                             List<SuperAgentKgCommunity> communities,
                                             int topK,
                                             int requestedMaxHops) {
        GraphRagQueryCatalog catalog = buildQueryCatalog(entities, relations, communities, topK);
        Optional<GraphRagQueryPlanAdvice> advice;
        try {
            advice = queryPlanAdvisor.advise(question, catalog);
        }
        catch (RuntimeException exception) {
            log.warn("GraphRAG 受控查询计划 advisor 失败，继续使用 Java 规则 profile: question='{}', message={}",
                question,
                exception.getMessage());
            return baseProfile;
        }
        if (advice.isEmpty()) {
            return baseProfile;
        }
        QueryProfile advisorProfile = validateAdvice(advice.get(), entities, relations, communities, requestedMaxHops);
        if (advisorProfile == null) {
            return baseProfile;
        }
        return mergeProfiles(baseProfile, advisorProfile);
    }

    private GraphRagQueryCatalog buildQueryCatalog(List<SuperAgentKgEntity> entities,
                                                   List<SuperAgentKgRelation> relations,
                                                   List<SuperAgentKgCommunity> communities,
                                                   int topK) {
        Map<Long, SuperAgentKgEntity> entityMap = entities.stream()
            .collect(Collectors.toMap(SuperAgentKgEntity::getId, item -> item, (left, right) -> left, LinkedHashMap::new));
        int entityLimit = Math.max(CATALOG_ENTITY_LIMIT, topK * 8);
        int relationLimit = Math.max(CATALOG_RELATION_LIMIT, topK * 12);
        int communityLimit = Math.max(CATALOG_COMMUNITY_LIMIT, topK * 4);
        return GraphRagQueryCatalog.builder()
            .entities(entities.stream()
                .filter(Objects::nonNull)
                .filter(this::isGraphSearchEntityUsable)
                .sorted(Comparator.comparingDouble(this::entityRankBoost).reversed())
                .limit(entityLimit)
                .map(entity -> GraphRagQueryCatalog.EntityItem.builder()
                    .entityId(entity.getId())
                    .name(entity.getName())
                    .normalizedName(entity.getNormalizedName())
                    .entityType(entity.getEntityType())
                    .aliases(entityAliases(entity))
                    .description(entity.getDescription())
                    .build())
                .toList())
            .relations(relations.stream()
                .filter(Objects::nonNull)
                .filter(relation -> {
                    SuperAgentKgEntity source = entityMap.get(relation.getSourceEntityId());
                    SuperAgentKgEntity target = entityMap.get(relation.getTargetEntityId());
                    return isGraphSearchEntityUsable(source) && isGraphSearchEntityUsable(target);
                })
                .sorted(Comparator.comparingDouble((SuperAgentKgRelation relation) -> relationRankBoost(
                    relation,
                    entityMap.get(relation.getSourceEntityId()),
                    entityMap.get(relation.getTargetEntityId())
                )).reversed())
                .limit(relationLimit)
                .map(relation -> {
                    SuperAgentKgEntity source = entityMap.get(relation.getSourceEntityId());
                    SuperAgentKgEntity target = entityMap.get(relation.getTargetEntityId());
                    return GraphRagQueryCatalog.RelationItem.builder()
                        .relationId(relation.getId())
                        .relationType(relation.getRelationType())
                        .sourceEntityId(relation.getSourceEntityId())
                        .sourceEntityName(source == null ? "" : source.getName())
                        .targetEntityId(relation.getTargetEntityId())
                        .targetEntityName(target == null ? "" : target.getName())
                        .description(relation.getDescription())
                        .build();
                })
                .toList())
            .communities(communities.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(this::communityRankBoost).reversed())
                .limit(communityLimit)
                .map(community -> GraphRagQueryCatalog.CommunityItem.builder()
                    .communityId(community.getId())
                    .title(community.getTitle())
                    .summary(community.getSummary())
                    .build())
                .toList())
            .build();
    }

    private QueryProfile validateAdvice(GraphRagQueryPlanAdvice advice,
                                        List<SuperAgentKgEntity> entities,
                                        List<SuperAgentKgRelation> relations,
                                        List<SuperAgentKgCommunity> communities,
                                        int requestedMaxHops) {
        if (advice == null || !Boolean.TRUE.equals(advice.getGraphQuery())) {
            return null;
        }
        double confidence = numberValue(advice.getConfidence(), 0D);
        if (confidence < ADVISOR_CONFIDENCE_THRESHOLD) {
            return null;
        }
        Set<String> allowedEntityTypes = entities.stream()
            .map(SuperAgentKgEntity::getEntityType)
            .filter(StrUtil::isNotBlank)
            .map(item -> item.toUpperCase(Locale.ROOT))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> allowedRelationTypes = relations.stream()
            .map(SuperAgentKgRelation::getRelationType)
            .filter(StrUtil::isNotBlank)
            .map(item -> item.toUpperCase(Locale.ROOT))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> allowedEntityIds = entities.stream()
            .map(SuperAgentKgEntity::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> allowedRelationIds = relations.stream()
            .map(SuperAgentKgRelation::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> allowedCommunityIds = communities.stream()
            .map(SuperAgentKgCommunity::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        LinkedHashSet<String> entityTypes = normalizeAllowedStrings(advice.getEntityTypes(), allowedEntityTypes);
        LinkedHashSet<String> relationTypes = normalizeAllowedStrings(advice.getRelationTypes(), allowedRelationTypes);
        LinkedHashSet<Long> entityIds = normalizeAllowedLongs(advice.getEntityIds(), allowedEntityIds);
        LinkedHashSet<Long> relationIds = normalizeAllowedLongs(advice.getRelationIds(), allowedRelationIds);
        LinkedHashSet<Long> communityIds = normalizeAllowedLongs(advice.getCommunityIds(), allowedCommunityIds);
        LinkedHashSet<String> entityNames = normalizeAllowedEntityNames(advice.getEntityNames(), entities);

        boolean relationQuestion = Boolean.TRUE.equals(advice.getRelationQuestion())
            || !relationTypes.isEmpty()
            || !relationIds.isEmpty();
        boolean communityQuestion = Boolean.TRUE.equals(advice.getCommunityQuestion()) || !communityIds.isEmpty();
        if (entityTypes.isEmpty()
            && relationTypes.isEmpty()
            && entityIds.isEmpty()
            && relationIds.isEmpty()
            && communityIds.isEmpty()
            && entityNames.isEmpty()) {
            return null;
        }
        return new QueryProfile(
            relationTypes,
            entityTypes,
            entityIds,
            relationIds,
            communityIds,
            entityNames,
            relationQuestion,
            communityQuestion,
            normalizeAdvisorMaxHops(advice.getMaxHops(), requestedMaxHops)
        );
    }

    private QueryProfile mergeProfiles(QueryProfile baseProfile, QueryProfile advisorProfile) {
        LinkedHashSet<String> relationTypes = new LinkedHashSet<>(baseProfile.relationTypes());
        relationTypes.addAll(advisorProfile.relationTypes());
        LinkedHashSet<String> entityTypes = new LinkedHashSet<>(baseProfile.entityTypes());
        entityTypes.addAll(advisorProfile.entityTypes());
        LinkedHashSet<Long> entityIds = new LinkedHashSet<>(baseProfile.entityIds());
        entityIds.addAll(advisorProfile.entityIds());
        LinkedHashSet<Long> relationIds = new LinkedHashSet<>(baseProfile.relationIds());
        relationIds.addAll(advisorProfile.relationIds());
        LinkedHashSet<Long> communityIds = new LinkedHashSet<>(baseProfile.communityIds());
        communityIds.addAll(advisorProfile.communityIds());
        LinkedHashSet<String> entityNames = new LinkedHashSet<>(baseProfile.entityNames());
        entityNames.addAll(advisorProfile.entityNames());
        return new QueryProfile(
            relationTypes,
            entityTypes,
            entityIds,
            relationIds,
            communityIds,
            entityNames,
            baseProfile.relationQuestion() || advisorProfile.relationQuestion(),
            baseProfile.communityQuestion() || advisorProfile.communityQuestion(),
            Math.max(baseProfile.maxHops(), advisorProfile.maxHops())
        );
    }

    private LinkedHashSet<String> normalizeAllowedStrings(List<String> values, Set<String> allowedValues) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (CollUtil.isEmpty(values) || CollUtil.isEmpty(allowedValues)) {
            return result;
        }
        for (String value : values) {
            String normalized = StrUtil.blankToDefault(value, "").trim().toUpperCase(Locale.ROOT);
            if (allowedValues.contains(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    private LinkedHashSet<Long> normalizeAllowedLongs(List<Long> values, Set<Long> allowedValues) {
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        if (CollUtil.isEmpty(values) || CollUtil.isEmpty(allowedValues)) {
            return result;
        }
        for (Long value : values) {
            if (value != null && allowedValues.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private LinkedHashSet<String> normalizeAllowedEntityNames(List<String> entityNames,
                                                              List<SuperAgentKgEntity> entities) {
        LinkedHashSet<String> allowedNames = new LinkedHashSet<>();
        for (SuperAgentKgEntity entity : entities) {
            addNormalizedIfNotBlank(allowedNames, entity.getName());
            addNormalizedIfNotBlank(allowedNames, entity.getNormalizedName());
            for (String alias : entityAliases(entity)) {
                addNormalizedIfNotBlank(allowedNames, alias);
            }
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (CollUtil.isEmpty(entityNames)) {
            return result;
        }
        for (String entityName : entityNames) {
            String normalizedName = normalize(entityName);
            if (normalizedName.length() >= 2 && allowedNames.contains(normalizedName)) {
                result.add(normalizedName);
            }
        }
        return result;
    }

    private void addNormalizedIfNotBlank(Set<String> target, String value) {
        String normalized = normalize(value);
        if (normalized.length() >= 2) {
            target.add(normalized);
        }
    }

    private int normalizeAdvisorMaxHops(Integer advisorMaxHops, int requestedMaxHops) {
        if (advisorMaxHops == null) {
            return normalizeMaxHops(requestedMaxHops);
        }
        return Math.min(normalizeMaxHops(requestedMaxHops), normalizeMaxHops(advisorMaxHops));
    }

    private int normalizeMaxHops(int maxHops) {
        return Math.max(1, Math.min(2, maxHops));
    }

    private void addRelationTypeIfPresent(Set<String> relationTypes, String normalizedQuestion, String keyword, String relationType) {
        if (normalizedQuestion.contains(normalize(keyword))) {
            relationTypes.add(relationType);
        }
    }

    private void addEntityTypeIfPresent(Set<String> entityTypes, String normalizedQuestion, String keyword, String entityType) {
        if (normalizedQuestion.contains(normalize(keyword))) {
            entityTypes.add(entityType);
        }
    }

    private String searchableEntityText(SuperAgentKgEntity entity) {
        StringBuilder builder = new StringBuilder();
        builder.append(StrUtil.blankToDefault(entity.getName(), "")).append(' ');
        builder.append(StrUtil.blankToDefault(entity.getNormalizedName(), "")).append(' ');
        builder.append(StrUtil.blankToDefault(entity.getDescription(), "")).append(' ');
        for (String alias : entityAliases(entity)) {
            builder.append(alias).append(' ');
        }
        return normalize(builder.toString());
    }

    private boolean matchesAdvisorEntityName(SuperAgentKgEntity entity, Set<String> entityNames) {
        if (CollUtil.isEmpty(entityNames) || entity == null) {
            return false;
        }
        if (entityNames.contains(normalize(entity.getName())) || entityNames.contains(normalize(entity.getNormalizedName()))) {
            return true;
        }
        for (String alias : entityAliases(entity)) {
            if (entityNames.contains(normalize(alias))) {
                return true;
            }
        }
        return false;
    }

    private List<String> entityAliases(SuperAgentKgEntity entity) {
        Map<String, Object> metadata = readMap(entity.getMetadataJson());
        return readStringList(metadata.get("aliases"));
    }

    private double metadataConfidence(String metadataJson) {
        Map<String, Object> metadata = readMap(metadataJson);
        return numberValue(metadata.get("confidence"), 0D);
    }

    private double numberValue(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return Math.max(0D, Math.min(1D, number.doubleValue()));
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Math.max(0D, Math.min(1D, Double.parseDouble(String.valueOf(value))));
        }
        catch (Exception exception) {
            return defaultValue;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Map<String, Object> readMap(String json) {
        if (StrUtil.isBlank(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        }
        catch (Exception exception) {
            return Map.of();
        }
    }

    private List<String> readStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            List<String> result = new ArrayList<>();
            for (Object item : collection) {
                if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        if (value instanceof String text) {
            if (StrUtil.isBlank(text)) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (String item : text.split("[\\n\\r,，;；、|]+")) {
                if (StrUtil.isNotBlank(item)) {
                    result.add(item.trim());
                }
            }
            return result;
        }
        return List.of(String.valueOf(value));
    }

    private List<String> extractTerms(String question) {
        if (StrUtil.isBlank(question)) {
            return List.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String trimmed = question.trim();
        if (trimmed.length() <= 40) {
            terms.add(trimmed);
        }
        for (String segment : trimmed.split("[\\s、，,；;：:（）()\\-的和及与或]+")) {
            String item = segment.trim();
            if (item.length() >= 2) {
                terms.add(item);
            }
        }
        Matcher matcher = ENGLISH_TERM_PATTERN.matcher(trimmed);
        while (matcher.find()) {
            terms.add(matcher.group());
        }
        return terms.stream().limit(16).toList();
    }

    private String normalize(String text) {
        return StrUtil.blankToDefault(text, "")
            .replaceAll("[\\s>`*#_\\-，,。；;：:（）()“”\"'\\[\\]{}]+", "")
            .toLowerCase(Locale.ROOT);
    }

    private List<Long> readLongList(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, Long.class));
        }
        catch (Exception exception) {
            return List.of();
        }
    }

    private record ScoredEntity(SuperAgentKgEntity entity, double score) {
    }

    private record ScoredRelation(SuperAgentKgRelation relation, double score) {
    }

    private record ScoredCommunity(SuperAgentKgCommunity community, double score) {
    }

    private record CanonicalEntityIndex(Map<Long, CanonicalEntityGroup> groupByEntityId) {

        private static CanonicalEntityIndex empty() {
            return new CanonicalEntityIndex(Map.of());
        }

        private CanonicalEntityGroup groupOf(Long entityId) {
            return entityId == null ? null : groupByEntityId.get(entityId);
        }
    }

    private record CanonicalEntityGroup(String key,
                                        String name,
                                        Set<Long> entityIds,
                                        Set<Long> documentIds) {
    }

    private record RelationGroupIndex(Map<Long, RelationGroup> groupByRelationId) {

        private static RelationGroupIndex empty() {
            return new RelationGroupIndex(Map.of());
        }

        private RelationGroup groupOf(Long relationId) {
            return relationId == null ? null : groupByRelationId.get(relationId);
        }
    }

    private record RelationGroup(String key,
                                 int relationCount,
                                 int evidenceCount,
                                 int documentCount) {
    }

    private static class RelationGroupAccumulator {

        private final String key;

        private final Set<Long> relationIds = new LinkedHashSet<>();

        private final Set<Long> evidenceIds = new LinkedHashSet<>();

        private final Set<Long> documentIds = new LinkedHashSet<>();

        private RelationGroupAccumulator(String key) {
            this.key = key;
        }

        private void addRelation(SuperAgentKgRelation relation) {
            if (relation == null || relation.getId() == null) {
                return;
            }
            relationIds.add(relation.getId());
        }

        private void addEvidenceIds(Collection<Long> values) {
            if (CollUtil.isNotEmpty(values)) {
                evidenceIds.addAll(values);
            }
        }

        private void addDocumentIds(Collection<Long> values) {
            if (CollUtil.isNotEmpty(values)) {
                documentIds.addAll(values);
            }
        }

        private void addDocumentId(Long documentId) {
            if (documentId != null) {
                documentIds.add(documentId);
            }
        }

        private Set<Long> relationIds() {
            return relationIds;
        }

        private RelationGroup toGroup() {
            return new RelationGroup(
                key,
                relationIds.size(),
                evidenceIds.size(),
                documentIds.size()
            );
        }
    }

    private record QueryProfile(Set<String> relationTypes,
                                Set<String> entityTypes,
                                Set<Long> entityIds,
                                Set<Long> relationIds,
                                Set<Long> communityIds,
                                Set<String> entityNames,
                                boolean relationQuestion,
                                boolean communityQuestion,
                                int maxHops) {
    }
}
