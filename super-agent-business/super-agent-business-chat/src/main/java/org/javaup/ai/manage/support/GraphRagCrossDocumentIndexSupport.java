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
        return new GraphRagCrossDocumentIndex(canonicalGroups, relationGroups);
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
        return Math.min(0.32D, boost);
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
            double rankScore = groupEntities.stream()
                .mapToDouble(this::entityRankBoost)
                .max()
                .orElse(0D);
            GraphRagCrossDocumentIndex.CanonicalEntityGroup group = new GraphRagCrossDocumentIndex.CanonicalEntityGroup(
                key,
                name,
                entityType,
                entityIds,
                documentIds,
                taskIds,
                List.copyOf(variants),
                rounded(rankScore)
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
        }

        Map<Long, GraphRagCrossDocumentIndex.RelationGroup> groupByRelationId = new LinkedHashMap<>();
        for (RelationGroupAccumulator accumulator : accumulators.values()) {
            GraphRagCrossDocumentIndex.RelationGroup group = accumulator.toGroup();
            for (Long relationId : accumulator.relationIds()) {
                groupByRelationId.put(relationId, group);
            }
        }
        return groupByRelationId;
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

        private Set<Long> relationIds() {
            return relationIds;
        }

        private GraphRagCrossDocumentIndex.RelationGroup toGroup() {
            return new GraphRagCrossDocumentIndex.RelationGroup(
                key,
                sourceGroupKey,
                targetGroupKey,
                relationType,
                Set.copyOf(relationIds),
                Set.copyOf(evidenceIds),
                Set.copyOf(documentIds),
                Map.copyOf(evidenceCountByRelationId),
                rankScore
            );
        }
    }
}
