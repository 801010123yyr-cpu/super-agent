package org.javaup.ai.manage.support;

import org.javaup.ai.manage.data.SuperAgentKgEntity;
import org.javaup.ai.manage.data.SuperAgentKgRelation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record GraphRagCrossDocumentIndex(Map<Long, CanonicalEntityGroup> canonicalGroupByEntityId,
                                         Map<Long, RelationGroup> relationGroupByRelationId) {

    public static GraphRagCrossDocumentIndex empty() {
        return new GraphRagCrossDocumentIndex(Map.of(), Map.of());
    }

    public CanonicalEntityGroup canonicalGroupOf(Long entityId) {
        return entityId == null ? null : canonicalGroupByEntityId.get(entityId);
    }

    public RelationGroup relationGroupOf(Long relationId) {
        return relationId == null ? null : relationGroupByRelationId.get(relationId);
    }

    public boolean hasCanonicalGroups() {
        return canonicalGroupByEntityId != null && !canonicalGroupByEntityId.isEmpty();
    }

    public boolean hasRelationGroups() {
        return relationGroupByRelationId != null && !relationGroupByRelationId.isEmpty();
    }

    public Map<String, CanonicalEntityGroup> distinctCanonicalGroups() {
        LinkedHashMap<String, CanonicalEntityGroup> result = new LinkedHashMap<>();
        if (canonicalGroupByEntityId == null) {
            return result;
        }
        for (CanonicalEntityGroup group : canonicalGroupByEntityId.values()) {
            if (group != null) {
                result.putIfAbsent(group.key(), group);
            }
        }
        return result;
    }

    public Map<String, RelationGroup> distinctRelationGroups() {
        LinkedHashMap<String, RelationGroup> result = new LinkedHashMap<>();
        if (relationGroupByRelationId == null) {
            return result;
        }
        for (RelationGroup group : relationGroupByRelationId.values()) {
            if (group != null) {
                result.putIfAbsent(group.key(), group);
            }
        }
        return result;
    }

    public record CanonicalEntityGroup(String key,
                                       String name,
                                       String entityType,
                                       Set<Long> entityIds,
                                       Set<Long> documentIds,
                                       Set<Long> taskIds,
                                       List<String> variants,
                                       double rankScore) {
    }

    public record RelationGroup(String key,
                                String sourceGroupKey,
                                String targetGroupKey,
                                String relationType,
                                Set<Long> relationIds,
                                Set<Long> evidenceIds,
                                Set<Long> documentIds,
                                Map<Long, Integer> evidenceCountByRelationId,
                                double rankScore) {

        public int relationCount() {
            return relationIds == null ? 0 : relationIds.size();
        }

        public int evidenceCount() {
            if (evidenceCountByRelationId != null && !evidenceCountByRelationId.isEmpty()) {
                return evidenceCountByRelationId.values().stream()
                    .mapToInt(value -> value == null ? 0 : value)
                    .sum();
            }
            return evidenceIds == null ? 0 : evidenceIds.size();
        }

        public int documentCount() {
            return documentIds == null ? 0 : documentIds.size();
        }
    }

    public record RelationEndpoint(SuperAgentKgRelation relation,
                                   SuperAgentKgEntity source,
                                   SuperAgentKgEntity target) {
    }
}
