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
import org.javaup.ai.manage.service.GraphRagCrossDocumentIndexService;
import org.javaup.ai.manage.service.GraphRagQueryPlanAdvisor;
import org.javaup.ai.manage.service.GraphRagSearchService;
import org.javaup.ai.manage.support.GraphRagCrossDocumentIndex;
import org.javaup.ai.manage.support.GraphRagCrossDocumentIndex.CanonicalEntityGroup;
import org.javaup.ai.manage.support.GraphRagCrossDocumentIndex.CrossDocumentCommunity;
import org.javaup.ai.manage.support.GraphRagCrossDocumentIndex.RelationGroup;
import org.javaup.ai.manage.support.GraphRagCrossDocumentIndexSupport;
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
    private static final String JAVA_QUERY_PROFILE_SOURCE = "java.graph_query_profile.v2";
    private static final String ADVISOR_QUERY_PROFILE_SOURCE = "llm.controlled.query_plan.v1";
    private final SuperAgentKgEntityMapper entityMapper;

    private final SuperAgentKgRelationMapper relationMapper;

    private final SuperAgentKgEvidenceMapper evidenceMapper;

    private final SuperAgentKgCommunityMapper communityMapper;

    private final ObjectMapper objectMapper;

    private final GraphRagQueryPlanAdvisor queryPlanAdvisor;

    private final GraphRagCrossDocumentIndexService crossDocumentIndexService;

    private final GraphRagCrossDocumentIndexSupport crossDocumentIndexSupport;

    @Autowired
    public GraphRagSearchServiceImpl(SuperAgentKgEntityMapper entityMapper,
                                     SuperAgentKgRelationMapper relationMapper,
                                     SuperAgentKgEvidenceMapper evidenceMapper,
                                     SuperAgentKgCommunityMapper communityMapper,
                                     ObjectMapper objectMapper,
                                     ObjectProvider<GraphRagQueryPlanAdvisor> queryPlanAdvisorProvider,
                                     ObjectProvider<GraphRagCrossDocumentIndexService> crossDocumentIndexServiceProvider,
                                     GraphRagCrossDocumentIndexSupport crossDocumentIndexSupport) {
        this(
            entityMapper,
            relationMapper,
            evidenceMapper,
            communityMapper,
            objectMapper,
            queryPlanAdvisorProvider == null ? null : queryPlanAdvisorProvider.getIfAvailable(),
            crossDocumentIndexServiceProvider == null ? null : crossDocumentIndexServiceProvider.getIfAvailable(),
            crossDocumentIndexSupport
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
        this(
            entityMapper,
            relationMapper,
            evidenceMapper,
            communityMapper,
            objectMapper,
            queryPlanAdvisor,
            null,
            new GraphRagCrossDocumentIndexSupport(objectMapper)
        );
    }

    GraphRagSearchServiceImpl(SuperAgentKgEntityMapper entityMapper,
                              SuperAgentKgRelationMapper relationMapper,
                              SuperAgentKgEvidenceMapper evidenceMapper,
                              SuperAgentKgCommunityMapper communityMapper,
                              ObjectMapper objectMapper,
                              GraphRagQueryPlanAdvisor queryPlanAdvisor,
                              GraphRagCrossDocumentIndexService crossDocumentIndexService,
                              GraphRagCrossDocumentIndexSupport crossDocumentIndexSupport) {
        this.entityMapper = entityMapper;
        this.relationMapper = relationMapper;
        this.evidenceMapper = evidenceMapper;
        this.communityMapper = communityMapper;
        this.objectMapper = objectMapper;
        this.queryPlanAdvisor = queryPlanAdvisor;
        this.crossDocumentIndexService = crossDocumentIndexService;
        this.crossDocumentIndexSupport = crossDocumentIndexSupport == null
            ? new GraphRagCrossDocumentIndexSupport(objectMapper)
            : crossDocumentIndexSupport;
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
        GraphRagCrossDocumentIndex crossDocumentIndex = loadCrossDocumentIndex(documentIds, taskIds, allEntities, List.of(), List.of());
        List<SuperAgentKgCommunity> allCommunities = listCommunities(documentIds, taskIds);
        List<SuperAgentKgRelation> loadedRelations = List.of();
        if (shouldAskAdvisor(question, queryProfile, allEntities, allCommunities)) {
            loadedRelations = listAllRelations(documentIds, taskIds);
            crossDocumentIndex = ensureCrossDocumentIndex(documentIds, taskIds, allEntities, loadedRelations, List.of(), crossDocumentIndex);
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
        List<GraphRagSearchResult> communityResults = new ArrayList<>(
            searchCommunityReports(allCommunities, topK, normalizedQuestion, terms, effectiveQueryProfile)
        );
        List<ScoredEntity> seedEntities = allEntities.stream()
            .map(entity -> new ScoredEntity(entity, scoreEntity(entity, normalizedQuestion, terms, effectiveQueryProfile)))
            .filter(item -> item.score() > 0D)
            .sorted(Comparator.comparingDouble(ScoredEntity::score).reversed())
            .limit(Math.max(topK * 4L, 12L))
            .toList();
        boolean explicitRelationSeed = CollUtil.isNotEmpty(effectiveQueryProfile.relationTypes())
            || CollUtil.isNotEmpty(effectiveQueryProfile.relationIds());
        List<SuperAgentKgRelation> allRelations = explicitRelationSeed
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
        seedScoreMap = expandCanonicalSeedScores(seedScoreMap, crossDocumentIndex);
        Map<Long, EntityTrace> seedTraceMap = expandCanonicalSeedTraces(seedEntities, crossDocumentIndex);
        Set<Long> frontierEntityIds = seedEntities.stream()
            .map(item -> item.entity().getId())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        frontierEntityIds.addAll(seedRelations.stream()
            .flatMap(item -> relatedEntityIds(List.of(item.relation())).stream())
            .collect(Collectors.toCollection(LinkedHashSet::new)));
        frontierEntityIds.addAll(seedScoreMap.keySet());
        frontierEntityIds = expandCanonicalEntityIds(frontierEntityIds, crossDocumentIndex);

        Map<Long, SuperAgentKgRelation> relationMap = new LinkedHashMap<>();
        Map<Long, Integer> relationHopMap = new LinkedHashMap<>();
        Map<Long, RelationTrace> relationTraceMap = new LinkedHashMap<>();
        seedRelations.forEach(item -> {
            relationMap.put(item.relation().getId(), item.relation());
            relationHopMap.put(item.relation().getId(), 0);
            relationTraceMap.put(item.relation().getId(),
                relationTrace(item.relation(), entityMap, seedTraceMap, 0, effectiveQueryProfile.sourceText()));
        });
        List<SuperAgentKgRelation> oneHopRelations = listRelations(documentIds, taskIds, frontierEntityIds);
        oneHopRelations.forEach(relation -> {
            relationMap.put(relation.getId(), relation);
            relationHopMap.putIfAbsent(relation.getId(), 1);
            relationTraceMap.putIfAbsent(relation.getId(),
                relationTrace(relation, entityMap, seedTraceMap, 1, effectiveQueryProfile.sourceText()));
        });

        if (Math.max(effectiveQueryProfile.maxHops(), 1) >= 2 && !oneHopRelations.isEmpty()) {
            Map<Long, EntityTrace> neighborTraceMap = oneHopEntityTraces(oneHopRelations, entityMap, seedTraceMap);
            Set<Long> neighborEntityIds = new LinkedHashSet<>(neighborTraceMap.keySet());
            neighborEntityIds.removeAll(frontierEntityIds);
            if (!neighborEntityIds.isEmpty()) {
                listRelations(documentIds, taskIds, neighborEntityIds)
                    .forEach(relation -> {
                        relationMap.putIfAbsent(relation.getId(), relation);
                        relationHopMap.putIfAbsent(relation.getId(), 2);
                        relationTraceMap.putIfAbsent(relation.getId(),
                            relationTrace(relation, entityMap, neighborTraceMap, 2, effectiveQueryProfile.sourceText()));
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

        crossDocumentIndex = ensureCrossDocumentIndex(documentIds, taskIds, allEntities, relationMap.values(), evidences, crossDocumentIndex);
        communityResults.addAll(searchCrossDocumentCommunities(crossDocumentIndex, evidences, topK, normalizedQuestion, terms, effectiveQueryProfile));
        Map<Long, GraphRagSearchResult> resultMap = new LinkedHashMap<>();
        for (SuperAgentKgEvidence evidence : evidences) {
            if (!isGraphEvidenceUsable(evidence)) {
                continue;
            }
            GraphRagSearchResult result = toResult(evidence, entityMap, relationMap, relationHopMap,
                relationTraceMap, seedScoreMap, relationSeedScoreMap, crossDocumentIndex, terms, effectiveQueryProfile);
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

    private GraphRagCrossDocumentIndex loadCrossDocumentIndex(List<Long> documentIds,
                                                              List<Long> taskIds,
                                                              List<SuperAgentKgEntity> entities,
                                                              Collection<SuperAgentKgRelation> relations,
                                                              List<SuperAgentKgEvidence> evidences) {
        if (crossDocumentIndexService != null) {
            try {
                GraphRagCrossDocumentIndex index = crossDocumentIndexService.loadIndex(documentIds, taskIds);
                if (index != null && index.hasCanonicalGroups()) {
                    return index;
                }
            }
            catch (RuntimeException exception) {
                log.warn("GraphRAG 跨文档持久化读模型读取失败，改用同源 KG 临时读模型: message={}", exception.getMessage());
            }
        }
        return buildRuntimeCrossDocumentIndex(entities, relations, evidences);
    }

    private GraphRagCrossDocumentIndex ensureCrossDocumentIndex(List<Long> documentIds,
                                                               List<Long> taskIds,
                                                               List<SuperAgentKgEntity> entities,
                                                               Collection<SuperAgentKgRelation> relations,
                                                               List<SuperAgentKgEvidence> evidences,
                                                               GraphRagCrossDocumentIndex currentIndex) {
        if (currentIndex != null && currentIndex.hasCanonicalGroups() && currentIndex.hasRelationGroups()) {
            return currentIndex;
        }
        if (currentIndex != null && currentIndex.hasCanonicalGroups() && CollUtil.isEmpty(relations)) {
            return currentIndex;
        }
        if (crossDocumentIndexService != null && currentIndex != null && currentIndex.hasCanonicalGroups()) {
            return currentIndex;
        }
        GraphRagCrossDocumentIndex runtimeIndex = buildRuntimeCrossDocumentIndex(entities, relations, evidences);
        if (runtimeIndex.hasCanonicalGroups() || runtimeIndex.hasRelationGroups()) {
            return runtimeIndex;
        }
        return currentIndex == null ? GraphRagCrossDocumentIndex.empty() : currentIndex;
    }

    private GraphRagCrossDocumentIndex buildRuntimeCrossDocumentIndex(List<SuperAgentKgEntity> entities,
                                                                      Collection<SuperAgentKgRelation> relations,
                                                                      List<SuperAgentKgEvidence> evidences) {
        return crossDocumentIndexSupport.build(
            entities == null ? List.of() : entities,
            relations == null ? List.of() : relations,
            evidences == null ? List.of() : evidences
        );
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

    private List<GraphRagSearchResult> searchCrossDocumentCommunities(GraphRagCrossDocumentIndex index,
                                                                      List<SuperAgentKgEvidence> loadedEvidences,
                                                                      int topK,
                                                                      String normalizedQuestion,
                                                                      List<String> terms,
                                                                      QueryProfile queryProfile) {
        if (index == null || !index.hasCommunities()) {
            return List.of();
        }
        Map<Long, SuperAgentKgEvidence> evidenceMap = safeEvidenceMap(loadedEvidences);
        List<ScoredCrossDocumentCommunity> scoredCommunities = index.communityByKey().values().stream()
            .map(community -> new ScoredCrossDocumentCommunity(
                community,
                scoreCrossDocumentCommunity(community, normalizedQuestion, terms, queryProfile)
            ))
            .filter(item -> item.score() > 0D)
            .sorted(Comparator.comparingDouble(ScoredCrossDocumentCommunity::score).reversed())
            .limit(Math.max(topK, 3))
            .toList();
        if (scoredCommunities.isEmpty()) {
            return List.of();
        }

        Set<Long> missingEvidenceIds = scoredCommunities.stream()
            .flatMap(item -> item.community().evidenceIds().stream())
            .filter(evidenceId -> !evidenceMap.containsKey(evidenceId))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!missingEvidenceIds.isEmpty()) {
            for (SuperAgentKgEvidence evidence : listEvidencesByIds(missingEvidenceIds)) {
                if (evidence != null && evidence.getId() != null) {
                    evidenceMap.put(evidence.getId(), evidence);
                }
            }
        }

        List<GraphRagSearchResult> results = new ArrayList<>();
        for (ScoredCrossDocumentCommunity scoredCommunity : scoredCommunities) {
            CrossDocumentCommunity community = scoredCommunity.community();
            SuperAgentKgEvidence representativeEvidence = community.evidenceIds().stream()
                .map(evidenceMap::get)
                .filter(Objects::nonNull)
                .filter(this::isGraphEvidenceUsable)
                .findFirst()
                .orElse(null);
            if (representativeEvidence == null) {
                continue;
            }
            double rankBoost = community.rankProfile() == null ? 0D : community.rankProfile().rankBoost();
            results.add(withCrossDocumentCommunity(
                    baseResult(representativeEvidence)
                        .communityId(community.id())
                        .communityTitle(community.title())
                        .communitySummary(community.summary())
                        .evidenceId(representativeEvidence.getId())
                        .quoteText(representativeEvidence.getQuoteText())
                        .graphPath("跨文档社区：" + StrUtil.blankToDefault(community.title(), community.key()))
                        .hopCount(0)
                        .rankBoost(rankBoost)
                        .score(scoredCommunity.score()
                            + graphRankScore(rankBoost)
                            + evidenceBoost(representativeEvidence.getQuoteText(), terms)),
                    community
                ).build());
        }
        return results;
    }

    private Map<Long, SuperAgentKgEvidence> safeEvidenceMap(List<SuperAgentKgEvidence> evidences) {
        if (CollUtil.isEmpty(evidences)) {
            return new LinkedHashMap<>();
        }
        return evidences.stream()
            .filter(evidence -> evidence != null && evidence.getId() != null)
            .collect(Collectors.toMap(SuperAgentKgEvidence::getId, item -> item, (left, right) -> left, LinkedHashMap::new));
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
        return relationMapper.selectList(wrapper).stream()
            .filter(relation -> relation != null
                && (entityIds.contains(relation.getSourceEntityId()) || entityIds.contains(relation.getTargetEntityId())))
            .toList();
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
                                          Map<Long, RelationTrace> relationTraceMap,
                                          Map<Long, Double> seedScoreMap,
                                          Map<Long, Double> relationSeedScoreMap,
                                          GraphRagCrossDocumentIndex crossDocumentIndex,
                                          List<String> terms,
                                          QueryProfile queryProfile) {
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
            RelationTrace relationTrace = relationTraceMap.get(relation.getId());
            SuperAgentKgEntity tracedSeed = relationTrace == null || relationTrace.seedEntityId() == null
                ? null
                : entityMap.get(relationTrace.seedEntityId());
            if (tracedSeed != null
                && (Objects.equals(tracedSeed.getId(), source.getId()) || Objects.equals(tracedSeed.getId(), target.getId()))) {
                seed = tracedSeed;
                if (Objects.equals(tracedSeed.getId(), source.getId())) {
                    related = target;
                }
                else if (Objects.equals(tracedSeed.getId(), target.getId())) {
                    related = source;
                }
            }
            double seedScore = Math.max(
                seedScoreMap.getOrDefault(source.getId(), 0.25D),
                seedScoreMap.getOrDefault(target.getId(), 0.25D)
            );
            seedScore = Math.max(seedScore, seedScoreMap.getOrDefault(seed.getId(), 0.25D));
            seedScore = Math.max(seedScore, relationSeedScoreMap.getOrDefault(relation.getId(), 0D));
            int hopCount = relationHopMap.getOrDefault(relation.getId(), 1);
            double hopPenalty = hopCount <= 1 ? 0D : 0.18D;
            double rankBoost = relationRankBoost(relation, source, target);
            RelationGroup relationGroup = crossDocumentIndex == null ? null : crossDocumentIndex.relationGroupOf(relation.getId());
            double groupBoost = relationGroupBoost(relationGroup);
            double score = seedScore
                + relationWeight(relation.getWeight())
                + graphRankScore(rankBoost)
                + evidenceBoost(evidence.getQuoteText(), terms)
                + groupBoost
                - hopPenalty;
            String graphPath = graphPath(relation, source, target, relationTrace, hopCount);
            GraphRagSearchResult.GraphRagSearchResultBuilder builder = baseResult(evidence)
                .entityId(seed.getId())
                .entityName(seed.getName())
                .relationId(relation.getId())
                .relationType(relation.getRelationType())
                .relatedEntityId(related.getId())
                .relatedEntityName(related.getName())
                .graphPath(graphPath)
                .hopCount(hopCount)
                .nHopSeedEntityId(relationTrace == null ? null : relationTrace.seedEntityId())
                .nHopSeedEntityName(relationTrace == null ? "" : relationTrace.seedEntityName())
                .nHopPath(relationTrace == null ? "" : relationTrace.path())
                .rankBoost(rankBoost)
                .score(score);
            builder = withQueryProfile(builder, queryProfile);
            builder = withCanonicalEntity(builder, seed, crossDocumentIndex);
            return withCrossDocumentCommunity(
                withRelationGroup(builder, relationGroup),
                relationGroup == null ? null : crossDocumentIndex.communityOfRelationGroup(relationGroup.key())
            ).build();
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
            builder = withQueryProfile(builder, queryProfile);
            return withCanonicalEntity(builder, entity, crossDocumentIndex).build();
        }
        return null;
    }

    private String graphPath(SuperAgentKgRelation relation,
                             SuperAgentKgEntity source,
                             SuperAgentKgEntity target,
                             RelationTrace relationTrace,
                             int hopCount) {
        String prefix = hopCount <= 0 ? "关系匹配：" : hopCount <= 1 ? "一跳：" : "二跳：";
        if (relationTrace != null && StrUtil.isNotBlank(relationTrace.path())) {
            return prefix + relationTrace.path();
        }
        return prefix + source.getName() + " --" + relation.getRelationType() + "--> " + target.getName();
    }

    private double relationGroupBoost(RelationGroup group) {
        return crossDocumentIndexSupport.relationGroupBoost(group);
    }

    private GraphRagSearchResult.GraphRagSearchResultBuilder withRelationGroup(
        GraphRagSearchResult.GraphRagSearchResultBuilder builder,
        RelationGroup group
    ) {
        if (group == null) {
            return builder;
        }
        return builder
            .relationGroupKey(group.key())
            .relationGroupRelationCount(group.relationCount())
            .relationGroupEvidenceCount(group.evidenceCount())
            .relationGroupDocumentCount(group.documentCount())
            .kgQualityScore(group.qualityProfile() == null ? null : group.qualityProfile().score())
            .kgQualityReasons(group.qualityProfile() == null ? "" : String.join(",", group.qualityProfile().qualityReasons()))
            .kgNoiseReasons(group.qualityProfile() == null ? "" : String.join(",", group.qualityProfile().noiseReasons()))
            .kgPagerank(group.rankProfile() == null ? null : group.rankProfile().pagerank())
            .kgRankPosition(group.rankProfile() == null ? null : group.rankProfile().rankPosition())
            .kgDegree(group.rankProfile() == null ? null : group.rankProfile().degree());
    }

    private GraphRagSearchResult.GraphRagSearchResultBuilder withCrossDocumentCommunity(
        GraphRagSearchResult.GraphRagSearchResultBuilder builder,
        CrossDocumentCommunity community
    ) {
        if (community == null) {
            return builder;
        }
        return builder
            .communityId(community.id())
            .communityTitle(community.title())
            .communitySummary(community.summary())
            .crossDocumentCommunityKey(community.key())
            .crossDocumentCommunityEntityCount(community.entityCount())
            .crossDocumentCommunityRelationGroupCount(community.relationGroupCount())
            .crossDocumentCommunityEvidenceCount(community.evidenceCount())
            .crossDocumentCommunityDocumentCount(community.documentCount())
            .kgQualityScore(community.qualityProfile() == null ? null : community.qualityProfile().score())
            .kgQualityReasons(community.qualityProfile() == null ? "" : String.join(",", community.qualityProfile().qualityReasons()))
            .kgNoiseReasons(community.qualityProfile() == null ? "" : String.join(",", community.qualityProfile().noiseReasons()))
            .kgPagerank(community.rankProfile() == null ? null : community.rankProfile().pagerank())
            .kgRankPosition(community.rankProfile() == null ? null : community.rankProfile().rankPosition())
            .kgDegree(community.rankProfile() == null ? null : community.rankProfile().degree());
    }

    private GraphRagSearchResult.GraphRagSearchResultBuilder withQueryProfile(
        GraphRagSearchResult.GraphRagSearchResultBuilder builder,
        QueryProfile queryProfile
    ) {
        if (queryProfile == null) {
            return builder;
        }
        return builder
            .queryPlanSource(StrUtil.blankToDefault(queryProfile.sourceText(), ""))
            .queryPlanAnswerTypes(String.join(",", queryProfile.answerTypeKeywords()))
            .queryPlanEntities(String.join(",", queryProfile.entitiesFromQuery()));
    }

    private GraphRagSearchResult.GraphRagSearchResultBuilder withCanonicalEntity(GraphRagSearchResult.GraphRagSearchResultBuilder builder,
                                                                                SuperAgentKgEntity entity,
                                                                                GraphRagCrossDocumentIndex crossDocumentIndex) {
        CanonicalEntityGroup group = crossDocumentIndex == null ? null : crossDocumentIndex.canonicalGroupOf(entity == null ? null : entity.getId());
        if (group == null || group.entityIds().size() <= 1) {
            return builder;
        }
        return builder
            .canonicalEntityKey(group.key())
            .canonicalEntityName(group.name())
            .canonicalEntityCount(group.entityIds().size())
            .canonicalDocumentCount(group.documentIds().size())
            .kgQualityScore(group.qualityProfile() == null ? null : group.qualityProfile().score())
            .kgQualityReasons(group.qualityProfile() == null ? "" : String.join(",", group.qualityProfile().qualityReasons()))
            .kgNoiseReasons(group.qualityProfile() == null ? "" : String.join(",", group.qualityProfile().noiseReasons()))
            .kgPagerank(group.rankProfile() == null ? null : group.rankProfile().pagerank())
            .kgRankPosition(group.rankProfile() == null ? null : group.rankProfile().rankPosition())
            .kgDegree(group.rankProfile() == null ? null : group.rankProfile().degree());
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

    private boolean isDistinctiveCanonicalVariant(String normalized) {
        return crossDocumentIndexSupport.isDistinctiveCanonicalVariant(normalized);
    }

    private String canonicalEntityType(SuperAgentKgEntity entity) {
        return crossDocumentIndexSupport.canonicalEntityType(entity);
    }

    private String normalizeCanonicalVariant(String value) {
        return crossDocumentIndexSupport.normalizeCanonicalVariant(value);
    }

    private Map<Long, Double> expandCanonicalSeedScores(Map<Long, Double> seedScoreMap,
                                                        GraphRagCrossDocumentIndex crossDocumentIndex) {
        if (seedScoreMap.isEmpty() || crossDocumentIndex == null || !crossDocumentIndex.hasCanonicalGroups()) {
            return seedScoreMap;
        }
        Map<Long, Double> expanded = new LinkedHashMap<>(seedScoreMap);
        for (Map.Entry<Long, Double> entry : seedScoreMap.entrySet()) {
            CanonicalEntityGroup group = crossDocumentIndex.canonicalGroupOf(entry.getKey());
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

    private Set<Long> expandCanonicalEntityIds(Set<Long> entityIds, GraphRagCrossDocumentIndex crossDocumentIndex) {
        if (CollUtil.isEmpty(entityIds) || crossDocumentIndex == null || !crossDocumentIndex.hasCanonicalGroups()) {
            return entityIds;
        }
        Set<Long> expanded = new LinkedHashSet<>(entityIds);
        for (Long entityId : entityIds) {
            CanonicalEntityGroup group = crossDocumentIndex.canonicalGroupOf(entityId);
            if (group != null && group.entityIds().size() > 1) {
                expanded.addAll(group.entityIds());
            }
        }
        return expanded;
    }

    private Map<Long, EntityTrace> expandCanonicalSeedTraces(List<ScoredEntity> seedEntities,
                                                             GraphRagCrossDocumentIndex crossDocumentIndex) {
        Map<Long, EntityTrace> traces = new LinkedHashMap<>();
        if (CollUtil.isEmpty(seedEntities)) {
            return traces;
        }
        for (ScoredEntity scoredEntity : seedEntities) {
            SuperAgentKgEntity entity = scoredEntity.entity();
            if (entity == null || entity.getId() == null) {
                continue;
            }
            EntityTrace trace = new EntityTrace(entity.getId(), entity.getName(), entity.getName());
            traces.put(entity.getId(), trace);
            CanonicalEntityGroup group = crossDocumentIndex == null ? null : crossDocumentIndex.canonicalGroupOf(entity.getId());
            if (group == null || group.entityIds().size() <= 1) {
                continue;
            }
            for (Long entityId : group.entityIds()) {
                traces.putIfAbsent(entityId, trace);
            }
        }
        return traces;
    }

    private Map<Long, EntityTrace> oneHopEntityTraces(Collection<SuperAgentKgRelation> relations,
                                                      Map<Long, SuperAgentKgEntity> entityMap,
                                                      Map<Long, EntityTrace> seedTraceMap) {
        Map<Long, EntityTrace> traces = new LinkedHashMap<>();
        if (CollUtil.isEmpty(relations) || seedTraceMap.isEmpty()) {
            return traces;
        }
        for (SuperAgentKgRelation relation : relations) {
            SuperAgentKgEntity source = entityMap.get(relation.getSourceEntityId());
            SuperAgentKgEntity target = entityMap.get(relation.getTargetEntityId());
            if (source == null || target == null) {
                continue;
            }
            EntityTrace sourceSeedTrace = seedTraceMap.get(source.getId());
            EntityTrace targetSeedTrace = seedTraceMap.get(target.getId());
            if (sourceSeedTrace != null && targetSeedTrace == null) {
                traces.putIfAbsent(target.getId(), appendTrace(sourceSeedTrace, relation, source, target));
            }
            if (targetSeedTrace != null && sourceSeedTrace == null) {
                traces.putIfAbsent(source.getId(), appendTrace(targetSeedTrace, relation, target, source));
            }
        }
        return traces;
    }

    private RelationTrace relationTrace(SuperAgentKgRelation relation,
                                        Map<Long, SuperAgentKgEntity> entityMap,
                                        Map<Long, EntityTrace> traceMap,
                                        int hopCount,
                                        String sourceText) {
        if (relation == null || traceMap == null || traceMap.isEmpty()) {
            return null;
        }
        SuperAgentKgEntity source = entityMap.get(relation.getSourceEntityId());
        SuperAgentKgEntity target = entityMap.get(relation.getTargetEntityId());
        if (source == null || target == null) {
            return null;
        }
        EntityTrace sourceTrace = traceMap.get(source.getId());
        if (sourceTrace != null) {
            EntityTrace fullTrace = appendTrace(sourceTrace, relation, source, target);
            return new RelationTrace(sourceTrace.seedEntityId(), sourceTrace.seedEntityName(), fullTrace.path(), sourceText);
        }
        EntityTrace targetTrace = traceMap.get(target.getId());
        if (targetTrace != null) {
            EntityTrace fullTrace = appendTrace(targetTrace, relation, target, source);
            return new RelationTrace(targetTrace.seedEntityId(), targetTrace.seedEntityName(), fullTrace.path(), sourceText);
        }
        return null;
    }

    private EntityTrace appendTrace(EntityTrace trace,
                                    SuperAgentKgRelation relation,
                                    SuperAgentKgEntity from,
                                    SuperAgentKgEntity to) {
        if (trace == null || relation == null || from == null || to == null) {
            return trace;
        }
        String currentPath = StrUtil.blankToDefault(trace.path(), from.getName());
        String relationType = StrUtil.blankToDefault(relation.getRelationType(), "ASSOCIATED_WITH");
        return new EntityTrace(
            trace.seedEntityId(),
            trace.seedEntityName(),
            currentPath + " --" + relationType + "--> " + to.getName()
        );
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
        boolean answerTypeMatched = queryProfile.entityTypes()
            .contains(StrUtil.blankToDefault(entity.getEntityType(), "").toUpperCase(Locale.ROOT));
        if (queryProfile.entityIds().contains(entity.getId())) {
            score += 0.95D;
        }
        if (matchesAdvisorEntityName(entity, queryProfile.entityNames())) {
            score += 0.62D;
        }
        if (matchesQueryEntityPhrase(entity, queryProfile.entitiesFromQuery())) {
            score += 0.58D;
        }
        if (score > 0D && answerTypeMatched) {
            score += 0.22D;
        }
        if (score <= 0D) {
            return 0D;
        }
        if (isLowDistinctivenessEntitySeed(entity)
            && !queryProfile.entityIds().contains(entity.getId())
            && !matchesAdvisorEntityName(entity, queryProfile.entityNames())
            && !matchesQueryEntityPhrase(entity, queryProfile.entitiesFromQuery())) {
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
        return crossDocumentIndexSupport.isGraphSearchEntityUsable(entity);
    }

    private Set<String> entityCandidateSources(SuperAgentKgEntity entity) {
        return crossDocumentIndexSupport.entityCandidateSources(entity);
    }

    private boolean isGraphEvidenceUsable(SuperAgentKgEvidence evidence) {
        return crossDocumentIndexSupport.isGraphEvidenceUsable(evidence);
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
        if (matchesQueryEntityPhrase(source, queryProfile.entitiesFromQuery())
            || matchesQueryEntityPhrase(target, queryProfile.entitiesFromQuery())) {
            score += 0.34D;
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
        return crossDocumentIndexSupport.entityRankBoost(entity);
    }

    private double relationRankBoost(SuperAgentKgRelation relation,
                                     SuperAgentKgEntity source,
                                     SuperAgentKgEntity target) {
        return crossDocumentIndexSupport.relationRankBoost(relation, source, target);
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

    private double scoreCrossDocumentCommunity(CrossDocumentCommunity community,
                                               String normalizedQuestion,
                                               List<String> terms,
                                               QueryProfile queryProfile) {
        if (community == null) {
            return 0D;
        }
        String title = normalize(community.title());
        String summary = normalize(community.summary());
        double score = 0D;
        if (queryProfile.communityQuestion()) {
            score += 0.16D;
        }
        if (StrUtil.isNotBlank(title) && normalizedQuestion.contains(title)) {
            score += 0.52D;
        }
        for (String term : terms) {
            String normalizedTerm = normalize(term);
            if (normalizedTerm.length() < 2) {
                continue;
            }
            if (title.contains(normalizedTerm)) {
                score += 0.22D;
            }
            if (summary.contains(normalizedTerm)) {
                score += 0.18D;
            }
            for (String canonicalKey : community.canonicalGroupKeys()) {
                if (normalize(canonicalKey).contains(normalizedTerm)) {
                    score += 0.12D;
                    break;
                }
            }
            for (String relationGroupKey : community.relationGroupKeys()) {
                if (normalize(relationGroupKey).contains(normalizedTerm)) {
                    score += 0.10D;
                    break;
                }
            }
        }
        if (score <= 0D) {
            return 0D;
        }
        score += Math.min(0.18D, community.qualityProfile().score() * 0.18D);
        score += graphRankScore(community.rankProfile().rankBoost()) * 0.72D;
        if (community.documentCount() > 1) {
            score += 0.08D;
        }
        return Math.min(score, 1.45D);
    }

    private QueryProfile analyzeQuery(String question, List<String> terms, int requestedMaxHops) {
        String normalizedQuestion = normalize(question);
        boolean relationQuestion = normalizedQuestion.contains("关系")
            || normalizedQuestion.contains("关联")
            || normalizedQuestion.contains("上下游")
            || normalizedQuestion.contains("链路")
            || normalizedQuestion.contains("路径")
            || normalizedQuestion.contains("谁")
            || normalizedQuestion.contains("哪个")
            || normalizedQuestion.contains("哪些")
            || normalizedQuestion.contains("要求");
        boolean communityQuestion = normalizedQuestion.contains("社区")
            || normalizedQuestion.contains("主题")
            || normalizedQuestion.contains("整体")
            || normalizedQuestion.contains("全局")
            || normalizedQuestion.contains("总结");
        return new QueryProfile(
            new LinkedHashSet<>(),
            new LinkedHashSet<>(),
            new LinkedHashSet<>(),
            new LinkedHashSet<>(),
            new LinkedHashSet<>(),
            new LinkedHashSet<>(),
            new LinkedHashSet<>(),
            new LinkedHashSet<>(),
            relationQuestion,
            communityQuestion,
            normalizeMaxHops(requestedMaxHops),
            JAVA_QUERY_PROFILE_SOURCE
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
            || normalizedQuestion.contains("路径")
            || hasQuestionEntityAnchor(normalizedQuestion, entities);
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
        QueryProfile advisorProfile = validateAdvice(question, advice.get(), entities, relations, communities, requestedMaxHops);
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

    private QueryProfile validateAdvice(String question,
                                        GraphRagQueryPlanAdvice advice,
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

        LinkedHashSet<String> answerTypeKeywords = normalizeAllowedStrings(advice.getAnswerTypeKeywords(), allowedEntityTypes);
        LinkedHashSet<String> entityTypes = normalizeAllowedStrings(advice.getEntityTypes(), allowedEntityTypes);
        entityTypes.addAll(answerTypeKeywords);
        LinkedHashSet<String> relationTypes = normalizeAllowedStrings(advice.getRelationTypes(), allowedRelationTypes);
        LinkedHashSet<Long> entityIds = normalizeAllowedLongs(advice.getEntityIds(), allowedEntityIds);
        LinkedHashSet<Long> relationIds = normalizeAllowedLongs(advice.getRelationIds(), allowedRelationIds);
        LinkedHashSet<Long> communityIds = normalizeAllowedLongs(advice.getCommunityIds(), allowedCommunityIds);
        LinkedHashSet<String> entityNames = normalizeAllowedEntityNames(advice.getEntityNames(), entities);
        LinkedHashSet<String> entitiesFromQuery = normalizeEntitiesFromQuery(advice.getEntitiesFromQuery(), question);

        boolean relationQuestion = Boolean.TRUE.equals(advice.getRelationQuestion())
            || !relationTypes.isEmpty()
            || !relationIds.isEmpty();
        boolean communityQuestion = Boolean.TRUE.equals(advice.getCommunityQuestion()) || !communityIds.isEmpty();
        if (entityTypes.isEmpty()
            && relationTypes.isEmpty()
            && entityIds.isEmpty()
            && relationIds.isEmpty()
            && communityIds.isEmpty()
            && entityNames.isEmpty()
            && entitiesFromQuery.isEmpty()) {
            return null;
        }
        return new QueryProfile(
            relationTypes,
            entityTypes,
            entityIds,
            relationIds,
            communityIds,
            entityNames,
            answerTypeKeywords,
            entitiesFromQuery,
            relationQuestion,
            communityQuestion,
            normalizeAdvisorMaxHops(advice.getMaxHops(), requestedMaxHops),
            ADVISOR_QUERY_PROFILE_SOURCE
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
        LinkedHashSet<String> answerTypeKeywords = new LinkedHashSet<>(baseProfile.answerTypeKeywords());
        answerTypeKeywords.addAll(advisorProfile.answerTypeKeywords());
        LinkedHashSet<String> entitiesFromQuery = new LinkedHashSet<>(baseProfile.entitiesFromQuery());
        entitiesFromQuery.addAll(advisorProfile.entitiesFromQuery());
        return new QueryProfile(
            relationTypes,
            entityTypes,
            entityIds,
            relationIds,
            communityIds,
            entityNames,
            answerTypeKeywords,
            entitiesFromQuery,
            baseProfile.relationQuestion() || advisorProfile.relationQuestion(),
            baseProfile.communityQuestion() || advisorProfile.communityQuestion(),
            Math.max(baseProfile.maxHops(), advisorProfile.maxHops()),
            mergeSourceText(baseProfile.sourceText(), advisorProfile.sourceText())
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

    private LinkedHashSet<String> normalizeEntitiesFromQuery(List<String> values, String question) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (CollUtil.isEmpty(values) || StrUtil.isBlank(question)) {
            return result;
        }
        String normalizedQuestion = normalize(question);
        for (String value : values) {
            String normalized = normalize(value);
            if (normalized.length() >= 2 && normalizedQuestion.contains(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    private boolean hasQuestionEntityAnchor(String normalizedQuestion, List<SuperAgentKgEntity> entities) {
        if (StrUtil.isBlank(normalizedQuestion) || CollUtil.isEmpty(entities)) {
            return false;
        }
        return entities.stream()
            .filter(Objects::nonNull)
            .filter(this::isGraphSearchEntityUsable)
            .anyMatch(entity -> matchesEntityText(entity, normalizedQuestion));
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

    private boolean matchesQueryEntityPhrase(SuperAgentKgEntity entity, Set<String> queryEntityPhrases) {
        if (CollUtil.isEmpty(queryEntityPhrases) || entity == null) {
            return false;
        }
        String searchableText = searchableEntityText(entity);
        for (String phrase : queryEntityPhrases) {
            String normalizedPhrase = normalize(phrase);
            if (normalizedPhrase.length() < 2) {
                continue;
            }
            if (searchableText.contains(normalizedPhrase)) {
                return true;
            }
            for (String variant : entityNameVariants(entity)) {
                if (variant.contains(normalizedPhrase) || normalizedPhrase.contains(variant)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesEntityText(SuperAgentKgEntity entity, String normalizedText) {
        if (entity == null || StrUtil.isBlank(normalizedText)) {
            return false;
        }
        for (String variant : entityNameVariants(entity)) {
            if (variant.length() >= 2 && normalizedText.contains(variant)) {
                return true;
            }
        }
        return false;
    }

    private List<String> entityNameVariants(SuperAgentKgEntity entity) {
        if (entity == null) {
            return List.of();
        }
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        addNormalizedIfNotBlank(variants, entity.getName());
        addNormalizedIfNotBlank(variants, entity.getNormalizedName());
        for (String alias : entityAliases(entity)) {
            addNormalizedIfNotBlank(variants, alias);
        }
        return variants.stream().filter(StrUtil::isNotBlank).toList();
    }

    private String mergeSourceText(String left, String right) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (StrUtil.isNotBlank(left)) {
            values.addAll(List.of(left.split(",")));
        }
        if (StrUtil.isNotBlank(right)) {
            values.addAll(List.of(right.split(",")));
        }
        values.removeIf(StrUtil::isBlank);
        return String.join(",", values);
    }

    private List<String> entityAliases(SuperAgentKgEntity entity) {
        return crossDocumentIndexSupport.entityAliases(entity);
    }

    private double metadataConfidence(String metadataJson) {
        Map<String, Object> metadata = readMap(metadataJson);
        return numberValue(metadata.get("confidence"), 0D);
    }

    private double numberValue(Object value, double defaultValue) {
        return crossDocumentIndexSupport.numberValue(value, defaultValue);
    }

    private String stringValue(Object value) {
        return crossDocumentIndexSupport.stringValue(value);
    }

    private Map<String, Object> readMap(String json) {
        return crossDocumentIndexSupport.readMap(json);
    }

    private List<String> readStringList(Object value) {
        return crossDocumentIndexSupport.readStringList(value);
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
        return crossDocumentIndexSupport.normalize(text);
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

    private record ScoredCrossDocumentCommunity(CrossDocumentCommunity community, double score) {
    }

    private record EntityTrace(Long seedEntityId, String seedEntityName, String path) {
    }

    private record RelationTrace(Long seedEntityId, String seedEntityName, String path, String sourceText) {
    }

    private record QueryProfile(Set<String> relationTypes,
                                Set<String> entityTypes,
                                Set<Long> entityIds,
                                Set<Long> relationIds,
                                Set<Long> communityIds,
                                Set<String> entityNames,
                                Set<String> answerTypeKeywords,
                                Set<String> entitiesFromQuery,
                                boolean relationQuestion,
                                boolean communityQuestion,
                                int maxHops,
                                String sourceText) {
    }
}
