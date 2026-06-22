package org.javaup.ai.manage.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.AllArgsConstructor;
import org.javaup.ai.manage.data.SuperAgentKgEntity;
import org.javaup.ai.manage.data.SuperAgentKgEvidence;
import org.javaup.ai.manage.data.SuperAgentKgRelation;
import org.javaup.ai.manage.mapper.SuperAgentKgEntityMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgEvidenceMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgRelationMapper;
import org.javaup.ai.manage.model.graph.GraphRagSearchResult;
import org.javaup.ai.manage.service.GraphRagSearchService;
import org.javaup.enums.BusinessStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@AllArgsConstructor
@Service
public class GraphRagSearchServiceImpl implements GraphRagSearchService {

    private static final Pattern ENGLISH_TERM_PATTERN = Pattern.compile("\\b[A-Za-z][A-Za-z0-9._/-]{1,40}\\b");

    private final SuperAgentKgEntityMapper entityMapper;

    private final SuperAgentKgRelationMapper relationMapper;

    private final SuperAgentKgEvidenceMapper evidenceMapper;

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
        List<SuperAgentKgEntity> allEntities = listEntities(documentIds, taskIds);
        List<ScoredEntity> seedEntities = allEntities.stream()
            .map(entity -> new ScoredEntity(entity, scoreEntity(entity, normalizedQuestion, terms)))
            .filter(item -> item.score() > 0D)
            .sorted(Comparator.comparingDouble(ScoredEntity::score).reversed())
            .limit(Math.max(topK * 4L, 12L))
            .toList();
        if (seedEntities.isEmpty()) {
            return List.of();
        }

        Map<Long, SuperAgentKgEntity> entityMap = allEntities.stream()
            .collect(Collectors.toMap(SuperAgentKgEntity::getId, item -> item, (left, right) -> left, LinkedHashMap::new));
        Map<Long, Double> seedScoreMap = seedEntities.stream()
            .collect(Collectors.toMap(item -> item.entity().getId(), ScoredEntity::score, Math::max, LinkedHashMap::new));
        Set<Long> frontierEntityIds = seedEntities.stream()
            .map(item -> item.entity().getId())
            .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<Long, SuperAgentKgRelation> relationMap = new LinkedHashMap<>();
        Map<Long, Integer> relationHopMap = new LinkedHashMap<>();
        List<SuperAgentKgRelation> oneHopRelations = listRelations(documentIds, taskIds, frontierEntityIds);
        oneHopRelations.forEach(relation -> {
            relationMap.put(relation.getId(), relation);
            relationHopMap.put(relation.getId(), 1);
        });

        if (Math.max(maxHops, 1) >= 2 && !oneHopRelations.isEmpty()) {
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
            return List.of();
        }

        Map<Long, GraphRagSearchResult> resultMap = new LinkedHashMap<>();
        for (SuperAgentKgEvidence evidence : evidences) {
            GraphRagSearchResult result = toResult(evidence, entityMap, relationMap, relationHopMap, seedScoreMap, terms);
            if (result == null) {
                continue;
            }
            resultMap.merge(evidence.getId(), result,
                (left, right) -> left.getScore() >= right.getScore() ? left : right);
        }

        return resultMap.values().stream()
            .sorted(Comparator.comparingDouble(GraphRagSearchResult::getScore).reversed())
            .limit(topK)
            .toList();
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

    private GraphRagSearchResult toResult(SuperAgentKgEvidence evidence,
                                          Map<Long, SuperAgentKgEntity> entityMap,
                                          Map<Long, SuperAgentKgRelation> relationMap,
                                          Map<Long, Integer> relationHopMap,
                                          Map<Long, Double> seedScoreMap,
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
            boolean sourceSeed = seedScoreMap.containsKey(source.getId());
            SuperAgentKgEntity seed = sourceSeed ? source : target;
            SuperAgentKgEntity related = sourceSeed ? target : source;
            double seedScore = Math.max(
                seedScoreMap.getOrDefault(source.getId(), 0.25D),
                seedScoreMap.getOrDefault(target.getId(), 0.25D)
            );
            int hopCount = relationHopMap.getOrDefault(relation.getId(), 1);
            double hopPenalty = hopCount <= 1 ? 0D : 0.18D;
            double score = seedScore + relationWeight(relation.getWeight()) + evidenceBoost(evidence.getQuoteText(), terms) - hopPenalty;
            return baseResult(evidence)
                .entityId(seed.getId())
                .entityName(seed.getName())
                .relationId(relation.getId())
                .relationType(relation.getRelationType())
                .relatedEntityId(related.getId())
                .relatedEntityName(related.getName())
                .graphPath((hopCount <= 1 ? "一跳：" : "二跳：")
                    + source.getName() + " --" + relation.getRelationType() + "--> " + target.getName())
                .hopCount(hopCount)
                .score(score)
                .build();
        }

        if (evidence.getEntityId() != null) {
            SuperAgentKgEntity entity = entityMap.get(evidence.getEntityId());
            if (entity == null) {
                return null;
            }
            double score = seedScoreMap.getOrDefault(entity.getId(), 0.35D) + evidenceBoost(evidence.getQuoteText(), terms);
            return baseResult(evidence)
                .entityId(entity.getId())
                .entityName(entity.getName())
                .graphPath(entity.getName())
                .hopCount(0)
                .score(score)
                .build();
        }
        return null;
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

    private double scoreEntity(SuperAgentKgEntity entity, String normalizedQuestion, List<String> terms) {
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
        return score;
    }

    private double relationWeight(BigDecimal weight) {
        if (weight == null) {
            return 0.35D;
        }
        return Math.min(0.6D, Math.max(0D, weight.doubleValue()) * 0.5D);
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

    private record ScoredEntity(SuperAgentKgEntity entity, double score) {
    }
}
