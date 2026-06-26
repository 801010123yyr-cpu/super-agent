package org.javaup.ai.manage.support;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.manage.data.SuperAgentKgEntity;
import org.javaup.ai.manage.data.SuperAgentKgEvidence;
import org.javaup.ai.manage.data.SuperAgentKgRelation;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class GraphRagCrossDocumentIndexSupport {

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
    private static final Set<String> STRONG_ENTITY_SOURCES = Set.of(
        "rule.structuredrelation.source", "rule.structuredrelation.target",
        "llm.controlled.extract.v1", "ner", "ner.pattern", "ner.role", "englishtoken"
    );
    private static final Set<String> STRONG_RELATION_SOURCES = Set.of(
        "rule.structuredrelation", "llm.controlled.extract.v1"
    );
    private static final Set<String> WEAK_ENTITY_SOURCES = Set.of(
        "title", "sectionpath", "mixedphrase", "parentheticalalias",
        "metadata.question", "metadata.keyword", "metadata.autoquestion", "metadata.autokeyword"
    );

    private final ObjectMapper objectMapper;

    public GraphRagCrossDocumentIndexSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public GraphRagCrossDocumentIndex build(List<SuperAgentKgEntity> entities,
                                            Collection<SuperAgentKgRelation> relations,
                                            List<SuperAgentKgEvidence> evidences) {
        Map<Long, SuperAgentKgEntity> entityMap = safeList(entities).stream()
            .filter(entity -> entity != null && entity.getId() != null)
            .collect(Collectors.toMap(SuperAgentKgEntity::getId, item -> item, (left, right) -> left, LinkedHashMap::new));
        Map<Long, GraphRagCrossDocumentIndex.CanonicalEntityGroup> canonicalGroups = buildCanonicalEntityGroups(entityMap.values());
        Map<Long, GraphRagCrossDocumentIndex.RelationGroup> relationGroups = buildRelationGroups(relations, evidences, entityMap, canonicalGroups);
        return withGlobalRank(canonicalGroups, relationGroups);
    }

    public boolean isGraphSearchEntityUsable(SuperAgentKgEntity entity) {
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

    public boolean isGraphEvidenceUsable(SuperAgentKgEvidence evidence) {
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

    public String relationGroupKey(SuperAgentKgRelation relation,
                                   SuperAgentKgEntity source,
                                   SuperAgentKgEntity target,
                                   Map<Long, GraphRagCrossDocumentIndex.CanonicalEntityGroup> canonicalGroups) {
        String sourceKey = canonicalEntityGroupKey(source, canonicalGroups);
        String targetKey = canonicalEntityGroupKey(target, canonicalGroups);
        String relationType = StrUtil.blankToDefault(relation == null ? null : relation.getRelationType(), "ASSOCIATED_WITH")
            .trim()
            .toUpperCase(Locale.ROOT);
        return sourceKey + "->" + relationType + "->" + targetKey;
    }

    public String canonicalEntityGroupKey(SuperAgentKgEntity entity,
                                          Map<Long, GraphRagCrossDocumentIndex.CanonicalEntityGroup> canonicalGroups) {
        GraphRagCrossDocumentIndex.CanonicalEntityGroup group = canonicalGroups == null || entity == null
            ? null
            : canonicalGroups.get(entity.getId());
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

    public String canonicalEntityType(SuperAgentKgEntity entity) {
        return StrUtil.blankToDefault(entity == null ? null : entity.getEntityType(), "CONCEPT")
            .trim()
            .toUpperCase(Locale.ROOT);
    }

    public List<String> entityAliases(SuperAgentKgEntity entity) {
        Map<String, Object> metadata = readMap(entity == null ? null : entity.getMetadataJson());
        return readStringList(metadata.get("aliases"));
    }

    public Set<String> entityCandidateSources(SuperAgentKgEntity entity) {
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

    public double entityRankBoost(SuperAgentKgEntity entity) {
        Map<String, Object> metadata = readMap(entity == null ? null : entity.getMetadataJson());
        return numberValue(metadata.get("rankBoost"), 0D);
    }

    public double relationRankBoost(SuperAgentKgRelation relation,
                                    SuperAgentKgEntity source,
                                    SuperAgentKgEntity target) {
        Map<String, Object> metadata = readMap(relation == null ? null : relation.getMetadataJson());
        double relationRankBoost = numberValue(metadata.get("rankBoost"), -1D);
        if (relationRankBoost >= 0D) {
            return relationRankBoost;
        }
        return Math.max(entityRankBoost(source), entityRankBoost(target));
    }

    public String normalizeCanonicalVariant(String value) {
        return normalize(value).replaceAll("[<>《》/\\\\|?？!！]+", "");
    }

    public boolean isDistinctiveCanonicalVariant(String normalized) {
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

    public boolean isCanonicalVariantUsable(String normalized) {
        if (StrUtil.isBlank(normalized) || normalized.length() < 2 || normalized.length() > 80) {
            return false;
        }
        return !isTechnicalEntityName(normalized) && isDistinctiveCanonicalVariant(normalized);
    }

    public double relationGroupBoost(GraphRagCrossDocumentIndex.RelationGroup group) {
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
        double qualityScore = group.qualityProfile() == null ? 0D : group.qualityProfile().score();
        boost += Math.min(0.1D, qualityScore * 0.1D);
        double rankBoost = group.rankProfile() == null ? 0D : group.rankProfile().rankBoost();
        boost += Math.min(0.08D, rankBoost * 0.08D);
        return Math.min(0.42D, boost);
    }

    public Map<String, Object> readMap(String json) {
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

    public List<String> readStringList(Object value) {
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

    public double numberValue(Object value, double defaultValue) {
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

    public String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public String normalize(String text) {
        return StrUtil.blankToDefault(text, "")
            .replaceAll("[\\s>`*#_\\-，,。；;：:（）()“”\"'\\[\\]{}]+", "")
            .toLowerCase(Locale.ROOT);
    }

    private Map<Long, GraphRagCrossDocumentIndex.CanonicalEntityGroup> buildCanonicalEntityGroups(Collection<SuperAgentKgEntity> entities) {
        if (CollUtil.isEmpty(entities)) {
            return Map.of();
        }
        Map<Long, Long> parent = new LinkedHashMap<>();
        Map<Long, SuperAgentKgEntity> entityById = new LinkedHashMap<>();
        for (SuperAgentKgEntity entity : entities) {
            if (entity == null || entity.getId() == null || !isGraphSearchEntityUsable(entity)) {
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
        Map<Long, GraphRagCrossDocumentIndex.CanonicalEntityGroup> groupByEntityId = new LinkedHashMap<>();
        for (List<SuperAgentKgEntity> groupEntities : grouped.values()) {
            if (groupEntities.isEmpty()) {
                continue;
            }
            String name = chooseCanonicalEntityName(groupEntities);
            String entityType = canonicalEntityType(groupEntities.get(0));
            String key = canonicalEntityGroupKey(groupEntities.get(0), name);
            Set<Long> entityIds = groupEntities.stream()
                .map(SuperAgentKgEntity::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<Long> documentIds = groupEntities.stream()
                .map(SuperAgentKgEntity::getDocumentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<Long> taskIds = groupEntities.stream()
                .map(SuperAgentKgEntity::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            LinkedHashSet<String> variants = new LinkedHashSet<>();
            for (SuperAgentKgEntity entity : groupEntities) {
                variants.addAll(canonicalEntityVariantKeys(entity));
            }
            GraphRagCrossDocumentIndex.QualityProfile qualityProfile = canonicalEntityQualityProfile(
                name,
                groupEntities,
                documentIds,
                variants
            );
            double explicitRankBoost = groupEntities.stream()
                .mapToDouble(this::entityRankBoost)
                .max()
                .orElse(0D);
            double rankScore = Math.max(explicitRankBoost, qualityProfile.score());
            GraphRagCrossDocumentIndex.CanonicalEntityGroup group = new GraphRagCrossDocumentIndex.CanonicalEntityGroup(
                key,
                name,
                entityType,
                entityIds,
                documentIds,
                taskIds,
                List.copyOf(variants),
                rounded(rankScore),
                qualityProfile
            );
            for (Long entityId : entityIds) {
                groupByEntityId.put(entityId, group);
            }
        }
        return groupByEntityId;
    }

    private Map<Long, GraphRagCrossDocumentIndex.RelationGroup> buildRelationGroups(Collection<SuperAgentKgRelation> relations,
                                                                                   List<SuperAgentKgEvidence> evidences,
                                                                                   Map<Long, SuperAgentKgEntity> entityMap,
                                                                                   Map<Long, GraphRagCrossDocumentIndex.CanonicalEntityGroup> canonicalGroups) {
        if (CollUtil.isEmpty(relations)) {
            return Map.of();
        }
        Map<Long, Set<Long>> evidenceIdsByRelationId = new LinkedHashMap<>();
        Map<Long, Set<Long>> documentIdsByRelationId = new LinkedHashMap<>();
        for (SuperAgentKgEvidence evidence : safeList(evidences)) {
            if (evidence == null || evidence.getRelationId() == null || !isGraphEvidenceUsable(evidence)) {
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
            if (source == null || target == null || !isGraphSearchEntityUsable(source) || !isGraphSearchEntityUsable(target)) {
                continue;
            }
            String groupKey = relationGroupKey(relation, source, target, canonicalGroups);
            RelationGroupAccumulator accumulator = accumulators.computeIfAbsent(
                groupKey,
                key -> new RelationGroupAccumulator(
                    key,
                    canonicalEntityGroupKey(source, canonicalGroups),
                    canonicalEntityGroupKey(target, canonicalGroups),
                    StrUtil.blankToDefault(relation.getRelationType(), "ASSOCIATED_WITH").trim().toUpperCase(Locale.ROOT)
                )
            );
            accumulator.addRelation(relation);
            Set<Long> relationEvidenceIds = evidenceIdsByRelationId.get(relation.getId());
            accumulator.addEvidenceIds(relationEvidenceIds);
            accumulator.addEvidenceCount(relation.getId(), relationEvidenceIds);
            accumulator.addDocumentIds(documentIdsByRelationId.get(relation.getId()));
            if (relation.getDocumentId() != null) {
                accumulator.addDocumentId(relation.getDocumentId());
            }
            accumulator.addRankScore(relationRankBoost(relation, source, target));
            accumulator.addRelationMetadata(readMap(relation.getMetadataJson()));
        }

        Map<Long, GraphRagCrossDocumentIndex.RelationGroup> groupByRelationId = new LinkedHashMap<>();
        for (RelationGroupAccumulator accumulator : accumulators.values()) {
            GraphRagCrossDocumentIndex.QualityProfile qualityProfile = relationGroupQualityProfile(accumulator);
            GraphRagCrossDocumentIndex.RelationGroup group = accumulator.toGroup(qualityProfile);
            for (Long relationId : accumulator.relationIds()) {
                groupByRelationId.put(relationId, group);
            }
        }
        return groupByRelationId;
    }

    private GraphRagCrossDocumentIndex withGlobalRank(
        Map<Long, GraphRagCrossDocumentIndex.CanonicalEntityGroup> canonicalByEntityId,
        Map<Long, GraphRagCrossDocumentIndex.RelationGroup> relationByRelationId
    ) {
        if (canonicalByEntityId.isEmpty()) {
            return new GraphRagCrossDocumentIndex(canonicalByEntityId, relationByRelationId);
        }
        Map<String, GraphRagCrossDocumentIndex.CanonicalEntityGroup> canonicalByKey = distinctCanonicalGroups(canonicalByEntityId);
        Map<String, GraphRagCrossDocumentIndex.RelationGroup> relationByKey = distinctRelationGroups(relationByRelationId);
        Map<String, GraphRagCrossDocumentIndex.RankProfile> canonicalRankProfiles = calculateCanonicalRankProfiles(canonicalByKey, relationByKey);
        if (canonicalRankProfiles.isEmpty()) {
            return new GraphRagCrossDocumentIndex(canonicalByEntityId, relationByRelationId);
        }

        Map<String, GraphRagCrossDocumentIndex.CanonicalEntityGroup> rankedCanonicalByKey = new LinkedHashMap<>();
        for (GraphRagCrossDocumentIndex.CanonicalEntityGroup group : canonicalByKey.values()) {
            GraphRagCrossDocumentIndex.RankProfile rankProfile = canonicalRankProfiles.getOrDefault(
                group.key(),
                GraphRagCrossDocumentIndex.RankProfile.empty()
            );
            rankedCanonicalByKey.put(group.key(), new GraphRagCrossDocumentIndex.CanonicalEntityGroup(
                group.key(),
                group.name(),
                group.entityType(),
                group.entityIds(),
                group.documentIds(),
                group.taskIds(),
                group.variants(),
                stableCanonicalRankScore(group, rankProfile),
                group.qualityProfile(),
                rankProfile
            ));
        }

        Map<String, GraphRagCrossDocumentIndex.RelationGroup> rankedRelationByKey = new LinkedHashMap<>();
        Map<String, GraphRagCrossDocumentIndex.RankProfile> relationRankProfiles = calculateRelationRankProfiles(
            relationByKey,
            canonicalRankProfiles
        );
        for (GraphRagCrossDocumentIndex.RelationGroup group : relationByKey.values()) {
            GraphRagCrossDocumentIndex.RankProfile rankProfile = relationRankProfiles.getOrDefault(
                group.key(),
                GraphRagCrossDocumentIndex.RankProfile.empty()
            );
            rankedRelationByKey.put(group.key(), new GraphRagCrossDocumentIndex.RelationGroup(
                group.key(),
                group.sourceGroupKey(),
                group.targetGroupKey(),
                group.relationType(),
                group.relationIds(),
                group.evidenceIds(),
                group.documentIds(),
                group.evidenceCountByRelationId(),
                stableRelationRankScore(group, rankProfile),
                group.qualityProfile(),
                rankProfile
            ));
        }

        Map<Long, GraphRagCrossDocumentIndex.CanonicalEntityGroup> rankedCanonicalByEntityId = new LinkedHashMap<>();
        for (Map.Entry<Long, GraphRagCrossDocumentIndex.CanonicalEntityGroup> entry : canonicalByEntityId.entrySet()) {
            rankedCanonicalByEntityId.put(
                entry.getKey(),
                rankedCanonicalByKey.getOrDefault(entry.getValue().key(), entry.getValue())
            );
        }
        Map<Long, GraphRagCrossDocumentIndex.RelationGroup> rankedRelationByRelationId = new LinkedHashMap<>();
        for (Map.Entry<Long, GraphRagCrossDocumentIndex.RelationGroup> entry : relationByRelationId.entrySet()) {
            rankedRelationByRelationId.put(
                entry.getKey(),
                rankedRelationByKey.getOrDefault(entry.getValue().key(), entry.getValue())
            );
        }
        return new GraphRagCrossDocumentIndex(rankedCanonicalByEntityId, rankedRelationByRelationId);
    }

    private Map<String, GraphRagCrossDocumentIndex.CanonicalEntityGroup> distinctCanonicalGroups(
        Map<Long, GraphRagCrossDocumentIndex.CanonicalEntityGroup> canonicalByEntityId
    ) {
        LinkedHashMap<String, GraphRagCrossDocumentIndex.CanonicalEntityGroup> result = new LinkedHashMap<>();
        for (GraphRagCrossDocumentIndex.CanonicalEntityGroup group : canonicalByEntityId.values()) {
            if (group != null) {
                result.putIfAbsent(group.key(), group);
            }
        }
        return result;
    }

    private Map<String, GraphRagCrossDocumentIndex.RelationGroup> distinctRelationGroups(
        Map<Long, GraphRagCrossDocumentIndex.RelationGroup> relationByRelationId
    ) {
        LinkedHashMap<String, GraphRagCrossDocumentIndex.RelationGroup> result = new LinkedHashMap<>();
        for (GraphRagCrossDocumentIndex.RelationGroup group : relationByRelationId.values()) {
            if (group != null) {
                result.putIfAbsent(group.key(), group);
            }
        }
        return result;
    }

    private Map<String, GraphRagCrossDocumentIndex.RankProfile> calculateCanonicalRankProfiles(
        Map<String, GraphRagCrossDocumentIndex.CanonicalEntityGroup> canonicalByKey,
        Map<String, GraphRagCrossDocumentIndex.RelationGroup> relationByKey
    ) {
        if (canonicalByKey.isEmpty() || relationByKey.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Double>> outgoingWeights = new LinkedHashMap<>();
        Map<String, RankAccumulator> accumulators = new LinkedHashMap<>();
        for (String groupKey : canonicalByKey.keySet()) {
            outgoingWeights.put(groupKey, new LinkedHashMap<>());
            accumulators.put(groupKey, new RankAccumulator());
        }

        int edgeCount = 0;
        for (GraphRagCrossDocumentIndex.RelationGroup relationGroup : relationByKey.values()) {
            String sourceKey = relationGroup.sourceGroupKey();
            String targetKey = relationGroup.targetGroupKey();
            if (StrUtil.isBlank(sourceKey)
                || StrUtil.isBlank(targetKey)
                || Objects.equals(sourceKey, targetKey)
                || !canonicalByKey.containsKey(sourceKey)
                || !canonicalByKey.containsKey(targetKey)) {
                continue;
            }
            double edgeWeight = relationGraphWeight(relationGroup);
            addRankEdge(outgoingWeights, sourceKey, targetKey, edgeWeight);
            accumulators.get(sourceKey).addOut(edgeWeight);
            accumulators.get(targetKey).addIn(edgeWeight);
            edgeCount++;
            if (isBidirectionalRankRelation(relationGroup.relationType())) {
                addRankEdge(outgoingWeights, targetKey, sourceKey, edgeWeight);
                accumulators.get(targetKey).addOut(edgeWeight);
                accumulators.get(sourceKey).addIn(edgeWeight);
                edgeCount++;
            }
        }
        if (edgeCount == 0) {
            return Map.of();
        }

        Map<String, Double> pageranks = calculatePagerank(canonicalByKey.keySet(), outgoingWeights);
        double maxPagerank = pageranks.values().stream()
            .mapToDouble(Double::doubleValue)
            .max()
            .orElse(0D);
        List<String> rankedKeys = pageranks.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()))
            .map(Map.Entry::getKey)
            .toList();
        Map<String, Integer> rankPositions = new LinkedHashMap<>();
        for (int index = 0; index < rankedKeys.size(); index++) {
            rankPositions.put(rankedKeys.get(index), index + 1);
        }

        Map<String, GraphRagCrossDocumentIndex.RankProfile> result = new LinkedHashMap<>();
        for (String groupKey : canonicalByKey.keySet()) {
            double pagerank = pageranks.getOrDefault(groupKey, 0D);
            double rankBoost = maxPagerank <= 0D ? 0D : Math.sqrt(pagerank / maxPagerank);
            RankAccumulator accumulator = accumulators.getOrDefault(groupKey, RankAccumulator.empty());
            result.put(groupKey, new GraphRagCrossDocumentIndex.RankProfile(
                rounded(pagerank),
                rounded(clamp(rankBoost)),
                rankPositions.getOrDefault(groupKey, 0),
                accumulator.degree(),
                accumulator.inDegree(),
                accumulator.outDegree(),
                rounded(accumulator.weightedDegree())
            ));
        }
        return result;
    }

    private Map<String, GraphRagCrossDocumentIndex.RankProfile> calculateRelationRankProfiles(
        Map<String, GraphRagCrossDocumentIndex.RelationGroup> relationByKey,
        Map<String, GraphRagCrossDocumentIndex.RankProfile> canonicalRankProfiles
    ) {
        if (relationByKey.isEmpty() || canonicalRankProfiles.isEmpty()) {
            return Map.of();
        }
        double maxRelationGraphWeight = relationByKey.values().stream()
            .mapToDouble(this::relationGraphWeight)
            .max()
            .orElse(0D);
        List<RelationRankCandidate> candidates = new ArrayList<>();
        for (GraphRagCrossDocumentIndex.RelationGroup group : relationByKey.values()) {
            GraphRagCrossDocumentIndex.RankProfile sourceRank = canonicalRankProfiles.get(group.sourceGroupKey());
            GraphRagCrossDocumentIndex.RankProfile targetRank = canonicalRankProfiles.get(group.targetGroupKey());
            if (sourceRank == null || targetRank == null) {
                continue;
            }
            double normalizedRelationWeight = maxRelationGraphWeight <= 0D
                ? 0D
                : relationGraphWeight(group) / maxRelationGraphWeight;
            double endpointRankBoost = Math.max(sourceRank.rankBoost(), targetRank.rankBoost());
            double qualityScore = group.qualityProfile() == null ? 0D : group.qualityProfile().score();
            double rankBoost = clamp(qualityScore * 0.58D + endpointRankBoost * 0.30D + normalizedRelationWeight * 0.12D);
            double pagerank = (sourceRank.pagerank() + targetRank.pagerank()) / 2D;
            candidates.add(new RelationRankCandidate(
                group.key(),
                new GraphRagCrossDocumentIndex.RankProfile(
                    rounded(pagerank),
                    rounded(rankBoost),
                    0,
                    sourceRank.degree() + targetRank.degree(),
                    targetRank.inDegree(),
                    sourceRank.outDegree(),
                    rounded(sourceRank.weightedDegree() + targetRank.weightedDegree())
                )
            ));
        }
        List<RelationRankCandidate> rankedCandidates = candidates.stream()
            .sorted(Comparator.comparingDouble((RelationRankCandidate candidate) -> candidate.rankProfile().rankBoost()).reversed()
                .thenComparing(candidate -> candidate.rankProfile().pagerank(), Comparator.reverseOrder())
                .thenComparing(RelationRankCandidate::groupKey))
            .toList();
        Map<String, GraphRagCrossDocumentIndex.RankProfile> result = new LinkedHashMap<>();
        for (int index = 0; index < rankedCandidates.size(); index++) {
            RelationRankCandidate candidate = rankedCandidates.get(index);
            GraphRagCrossDocumentIndex.RankProfile profile = candidate.rankProfile();
            result.put(candidate.groupKey(), new GraphRagCrossDocumentIndex.RankProfile(
                profile.pagerank(),
                profile.rankBoost(),
                index + 1,
                profile.degree(),
                profile.inDegree(),
                profile.outDegree(),
                profile.weightedDegree()
            ));
        }
        return result;
    }

    private Map<String, Double> calculatePagerank(Set<String> nodeKeys,
                                                  Map<String, Map<String, Double>> outgoingWeights) {
        int nodeCount = nodeKeys.size();
        if (nodeCount == 0) {
            return Map.of();
        }
        double initialScore = 1D / nodeCount;
        Map<String, Double> ranks = new LinkedHashMap<>();
        for (String nodeKey : nodeKeys) {
            ranks.put(nodeKey, initialScore);
        }
        double damping = 0.85D;
        for (int iteration = 0; iteration < 30; iteration++) {
            Map<String, Double> next = new LinkedHashMap<>();
            for (String nodeKey : nodeKeys) {
                next.put(nodeKey, (1D - damping) / nodeCount);
            }
            double danglingMass = 0D;
            for (String sourceKey : nodeKeys) {
                Map<String, Double> outgoing = outgoingWeights.getOrDefault(sourceKey, Map.of());
                double totalWeight = outgoing.values().stream().mapToDouble(Double::doubleValue).sum();
                if (outgoing.isEmpty() || totalWeight <= 0D) {
                    danglingMass += ranks.getOrDefault(sourceKey, 0D);
                    continue;
                }
                double sourceRank = ranks.getOrDefault(sourceKey, 0D);
                for (Map.Entry<String, Double> edge : outgoing.entrySet()) {
                    if (!nodeKeys.contains(edge.getKey())) {
                        continue;
                    }
                    double contribution = damping * sourceRank * (edge.getValue() / totalWeight);
                    next.merge(edge.getKey(), contribution, Double::sum);
                }
            }
            if (danglingMass > 0D) {
                double danglingContribution = damping * danglingMass / nodeCount;
                for (String nodeKey : nodeKeys) {
                    next.merge(nodeKey, danglingContribution, Double::sum);
                }
            }
            ranks = next;
        }
        return ranks;
    }

    private void addRankEdge(Map<String, Map<String, Double>> outgoingWeights,
                             String sourceKey,
                             String targetKey,
                             double weight) {
        outgoingWeights.computeIfAbsent(sourceKey, ignored -> new LinkedHashMap<>())
            .merge(targetKey, Math.max(0.01D, weight), Double::sum);
    }

    private boolean isBidirectionalRankRelation(String relationType) {
        String normalized = StrUtil.blankToDefault(relationType, "").trim().toUpperCase(Locale.ROOT);
        return "ASSOCIATED_WITH".equals(normalized) || "RELATED_TO".equals(normalized);
    }

    private double relationGraphWeight(GraphRagCrossDocumentIndex.RelationGroup group) {
        if (group == null) {
            return 0D;
        }
        double qualityScore = group.qualityProfile() == null ? 0D : group.qualityProfile().score();
        double weight = 0.18D
            + qualityScore * 0.46D
            + Math.min(0.14D, group.evidenceCount() * 0.035D)
            + Math.min(0.12D, group.documentCount() * 0.06D)
            + Math.min(0.10D, group.relationCount() * 0.025D);
        if ("ASSOCIATED_WITH".equalsIgnoreCase(group.relationType())) {
            weight *= 0.88D;
        }
        return rounded(clamp(weight));
    }

    private double stableCanonicalRankScore(GraphRagCrossDocumentIndex.CanonicalEntityGroup group,
                                            GraphRagCrossDocumentIndex.RankProfile rankProfile) {
        double qualityScore = group.qualityProfile() == null ? 0D : group.qualityProfile().score();
        double crossDocumentSupport = group.documentIds() == null || group.documentIds().size() <= 1 ? 0D : 0.05D;
        double degreeSupport = rankProfile == null ? 0D : Math.min(0.04D, rankProfile.degree() * 0.01D);
        double rankBoost = rankProfile == null ? 0D : rankProfile.rankBoost();
        return rounded(clamp(qualityScore * 0.73D + rankBoost * 0.18D + crossDocumentSupport + degreeSupport));
    }

    private double stableRelationRankScore(GraphRagCrossDocumentIndex.RelationGroup group,
                                           GraphRagCrossDocumentIndex.RankProfile rankProfile) {
        double qualityScore = group.qualityProfile() == null ? 0D : group.qualityProfile().score();
        double crossDocumentSupport = group.documentCount() <= 1 ? 0D : 0.06D;
        double evidenceSupport = Math.min(0.05D, group.evidenceCount() * 0.01D);
        double rankBoost = rankProfile == null ? 0D : rankProfile.rankBoost();
        return rounded(clamp(qualityScore * 0.70D + rankBoost * 0.17D + crossDocumentSupport + evidenceSupport));
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

    private boolean isCrossTypeCanonicalVariantUsable(String normalized) {
        return isCanonicalVariantUsable(normalized)
            && normalized.length() >= 3
            && normalized.chars().anyMatch(ch -> (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9'));
    }

    private String chooseCanonicalEntityName(List<SuperAgentKgEntity> entities) {
        for (SuperAgentKgEntity entity : entities) {
            String advisedName = stringValue(readMap(entity.getMetadataJson()).get("entityResolutionCanonicalName"));
            if (StrUtil.isNotBlank(advisedName)) {
                return advisedName;
            }
        }
        return entities.stream()
            .sorted(Comparator.comparingDouble(this::entityRankBoost).reversed())
            .map(SuperAgentKgEntity::getName)
            .filter(StrUtil::isNotBlank)
            .findFirst()
            .orElse("unknown");
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

    private GraphRagCrossDocumentIndex.QualityProfile canonicalEntityQualityProfile(String canonicalName,
                                                                                   List<SuperAgentKgEntity> entities,
                                                                                   Set<Long> documentIds,
                                                                                   Set<String> variants) {
        LinkedHashSet<String> qualityReasons = new LinkedHashSet<>();
        LinkedHashSet<String> noiseReasons = new LinkedHashSet<>();
        double score = 0.36D;

        if (CollUtil.isNotEmpty(entities)) {
            score += Math.min(0.16D, entities.size() * 0.04D);
            qualityReasons.add("entityCount=" + entities.size());
        }
        if (documentIds != null && documentIds.size() > 1) {
            score += 0.16D;
            qualityReasons.add("crossDocument");
        }
        if (variants != null && variants.size() > 1) {
            score += 0.08D;
            qualityReasons.add("aliasOrVariantSupport");
        }

        LinkedHashSet<String> sources = new LinkedHashSet<>();
        int evidenceCount = 0;
        double confidence = 0D;
        for (SuperAgentKgEntity entity : safeList(entities)) {
            sources.addAll(normalizedSources(entityCandidateSources(entity)));
            Map<String, Object> metadata = readMap(entity.getMetadataJson());
            sources.addAll(normalizedSources(readStringList(metadata.get("extractorSources"))));
            evidenceCount += readStringList(metadata.get("evidenceIds")).size();
            confidence = Math.max(confidence, numberValue(metadata.get("confidence"), 0D));
        }
        if (evidenceCount > 0) {
            score += Math.min(0.12D, evidenceCount * 0.03D);
            qualityReasons.add("evidenceLinked");
        }
        if (confidence > 0D) {
            score += Math.min(0.1D, confidence * 0.1D);
            qualityReasons.add("confidence");
        }
        if (!Collections.disjoint(sources, STRONG_ENTITY_SOURCES)) {
            score += 0.1D;
            qualityReasons.add("strongExtractorSource");
        }
        if (!Collections.disjoint(sources, WEAK_ENTITY_SOURCES)) {
            score -= 0.08D;
            noiseReasons.add("weakEntitySource");
        }
        if (looksLikeSentenceFragment(canonicalName)) {
            score -= 0.22D;
            noiseReasons.add("sentenceLikeName");
        }
        if (isLongOrLowSignalName(canonicalName)) {
            score -= 0.12D;
            noiseReasons.add("lowSignalName");
        }
        return new GraphRagCrossDocumentIndex.QualityProfile(
            rounded(clamp(score)),
            List.copyOf(qualityReasons),
            List.copyOf(noiseReasons)
        );
    }

    private GraphRagCrossDocumentIndex.QualityProfile relationGroupQualityProfile(RelationGroupAccumulator accumulator) {
        LinkedHashSet<String> qualityReasons = new LinkedHashSet<>();
        LinkedHashSet<String> noiseReasons = new LinkedHashSet<>();
        double score = 0.28D;

        if (accumulator.relationIds.size() > 1) {
            score += Math.min(0.12D, accumulator.relationIds.size() * 0.04D);
            qualityReasons.add("multiRelationSupport");
        }
        if (accumulator.evidenceIds.size() > 0) {
            score += Math.min(0.2D, accumulator.evidenceIds.size() * 0.06D);
            qualityReasons.add("groundedEvidence");
        }
        else {
            score -= 0.18D;
            noiseReasons.add("missingEvidence");
        }
        if (accumulator.documentIds.size() > 1) {
            score += 0.14D;
            qualityReasons.add("crossDocument");
        }
        if (!Collections.disjoint(accumulator.candidateSources, STRONG_RELATION_SOURCES)
            || !Collections.disjoint(accumulator.extractorSources, STRONG_RELATION_SOURCES)) {
            score += 0.12D;
            qualityReasons.add("strongRelationSource");
        }
        if (accumulator.hasStrongMappingStatus) {
            score += 0.08D;
            qualityReasons.add("validatedLlmMapping");
        }
        if (accumulator.hasDowngradedMappingStatus) {
            score -= 0.08D;
            noiseReasons.add("downgradedLlmMapping");
        }
        if ("ASSOCIATED_WITH".equalsIgnoreCase(accumulator.relationType)) {
            score -= 0.06D;
            noiseReasons.add("genericRelationType");
        }
        if (accumulator.sourceGroupKey.equals(accumulator.targetGroupKey)) {
            score -= 0.16D;
            noiseReasons.add("sameCanonicalEndpoint");
        }
        double explicitRank = clamp(accumulator.rankScore);
        if (explicitRank > 0D) {
            score = Math.max(score, explicitRank);
            qualityReasons.add("explicitRankBoost");
        }
        return new GraphRagCrossDocumentIndex.QualityProfile(
            rounded(clamp(score)),
            List.copyOf(qualityReasons),
            List.copyOf(noiseReasons)
        );
    }

    private Set<String> normalizedSources(Collection<String> values) {
        if (CollUtil.isEmpty(values)) {
            return Set.of();
        }
        return values.stream()
            .filter(StrUtil::isNotBlank)
            .map(value -> value.trim().toLowerCase(Locale.ROOT))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean looksLikeSentenceFragment(String name) {
        String value = StrUtil.blankToDefault(name, "").trim();
        if (StrUtil.isBlank(value)) {
            return true;
        }
        int codePoints = value.codePointCount(0, value.length());
        if (value.contains("，")
            || value.contains("。")
            || value.contains("；")
            || value.contains("：")
            || value.contains("、")
            || value.contains("->")
            || value.contains("=>")) {
            return true;
        }
        if (codePoints >= 36) {
            return true;
        }
        return containsWhitespace(value) && containsCjk(value) && codePoints >= 24;
    }

    private boolean containsWhitespace(String value) {
        return value.chars().anyMatch(Character::isWhitespace);
    }

    private boolean containsCjk(String value) {
        return value.chars().anyMatch(ch -> Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN);
    }

    private boolean isLongOrLowSignalName(String name) {
        String normalized = normalizeCanonicalVariant(name);
        if (StrUtil.isBlank(normalized)) {
            return true;
        }
        int codePoints = normalized.codePointCount(0, normalized.length());
        return codePoints < 2 || codePoints > 30;
    }

    private double clamp(double value) {
        return Math.max(0D, Math.min(1D, value));
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

    private double rounded(double value) {
        return BigDecimal.valueOf(value).setScale(6, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private <T> List<T> safeList(Collection<T> values) {
        return values == null ? List.of() : new ArrayList<>(values);
    }

    private static class RelationGroupAccumulator {

        private final String key;
        private final String sourceGroupKey;
        private final String targetGroupKey;
        private final String relationType;
        private final Set<Long> relationIds = new LinkedHashSet<>();
        private final Set<Long> evidenceIds = new LinkedHashSet<>();
        private final Set<Long> documentIds = new LinkedHashSet<>();
        private final Map<Long, Integer> evidenceCountByRelationId = new LinkedHashMap<>();
        private final Set<String> candidateSources = new LinkedHashSet<>();
        private final Set<String> extractorSources = new LinkedHashSet<>();
        private boolean hasStrongMappingStatus;
        private boolean hasDowngradedMappingStatus;
        private double rankScore;

        private RelationGroupAccumulator(String key, String sourceGroupKey, String targetGroupKey, String relationType) {
            this.key = key;
            this.sourceGroupKey = sourceGroupKey;
            this.targetGroupKey = targetGroupKey;
            this.relationType = relationType;
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

        private void addEvidenceCount(Long relationId, Collection<Long> values) {
            if (relationId == null) {
                return;
            }
            evidenceCountByRelationId.put(relationId, values == null ? 0 : values.size());
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

        private void addRankScore(double value) {
            rankScore = Math.max(rankScore, value);
        }

        private void addRelationMetadata(Map<String, Object> metadata) {
            if (metadata == null || metadata.isEmpty()) {
                return;
            }
            candidateSources.addAll(normalizedMetadataList(metadata.get("candidateSources")));
            extractorSources.addAll(normalizedMetadataList(metadata.get("extractorSources")));
            Object sourceMetadata = metadata.get("sourceMetadata");
            if (sourceMetadata instanceof Collection<?> collection) {
                for (Object item : collection) {
                    if (item instanceof Map<?, ?> map) {
                        addSourceMetadata(map);
                    }
                }
            }
            else if (sourceMetadata instanceof Map<?, ?> map) {
                addSourceMetadata(map);
            }
        }

        private void addSourceMetadata(Map<?, ?> map) {
            candidateSources.addAll(normalizedMetadataList(map.get("candidateSources")));
            extractorSources.addAll(normalizedMetadataList(map.get("extractorSources")));
            String sourceType = stringMetadataValue(map.get("sourceType"));
            if (StrUtil.isNotBlank(sourceType)) {
                candidateSources.add(sourceType.trim().toLowerCase(Locale.ROOT));
                extractorSources.add(sourceType.trim().toLowerCase(Locale.ROOT));
            }
            String mappingStatus = stringMetadataValue(map.get("relationTypeMappingStatus")).toLowerCase(Locale.ROOT);
            if (mappingStatus.contains("accepted_strong")) {
                hasStrongMappingStatus = true;
            }
            if (mappingStatus.contains("downgraded")) {
                hasDowngradedMappingStatus = true;
            }
        }

        private List<String> normalizedMetadataList(Object value) {
            if (value == null) {
                return List.of();
            }
            if (value instanceof Collection<?> collection) {
                return collection.stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .filter(StrUtil::isNotBlank)
                    .map(item -> item.trim().toLowerCase(Locale.ROOT))
                    .toList();
            }
            String text = String.valueOf(value);
            if (StrUtil.isBlank(text)) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (String item : text.split("[\\n\\r,，;；、|]+")) {
                if (StrUtil.isNotBlank(item)) {
                    result.add(item.trim().toLowerCase(Locale.ROOT));
                }
            }
            return result;
        }

        private String stringMetadataValue(Object value) {
            return value == null ? "" : String.valueOf(value);
        }

        private Set<Long> relationIds() {
            return relationIds;
        }

        private GraphRagCrossDocumentIndex.RelationGroup toGroup(GraphRagCrossDocumentIndex.QualityProfile qualityProfile) {
            double effectiveRankScore = Math.max(rankScore, qualityProfile == null ? 0D : qualityProfile.score());
            return new GraphRagCrossDocumentIndex.RelationGroup(
                key,
                sourceGroupKey,
                targetGroupKey,
                relationType,
                Set.copyOf(relationIds),
                Set.copyOf(evidenceIds),
                Set.copyOf(documentIds),
                Map.copyOf(evidenceCountByRelationId),
                effectiveRankScore,
                qualityProfile == null ? GraphRagCrossDocumentIndex.QualityProfile.empty() : qualityProfile
            );
        }
    }

    private static class RankAccumulator {

        private int inDegree;
        private int outDegree;
        private double weightedDegree;

        private static RankAccumulator empty() {
            return new RankAccumulator();
        }

        private void addIn(double weight) {
            inDegree++;
            weightedDegree += Math.max(0D, weight);
        }

        private void addOut(double weight) {
            outDegree++;
            weightedDegree += Math.max(0D, weight);
        }

        private int degree() {
            return inDegree + outDegree;
        }

        private int inDegree() {
            return inDegree;
        }

        private int outDegree() {
            return outDegree;
        }

        private double weightedDegree() {
            return weightedDegree;
        }
    }

    private record RelationRankCandidate(String groupKey,
                                         GraphRagCrossDocumentIndex.RankProfile rankProfile) {
    }
}
