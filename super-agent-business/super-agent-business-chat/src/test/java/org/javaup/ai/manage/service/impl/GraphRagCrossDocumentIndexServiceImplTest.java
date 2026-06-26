package org.javaup.ai.manage.service.impl;

import com.baidu.fsg.uid.UidGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.javaup.ai.manage.support.GraphRagCrossDocumentIndex;
import org.javaup.ai.manage.support.GraphRagCrossDocumentIndexSupport;
import org.javaup.enums.BusinessStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class GraphRagCrossDocumentIndexServiceImplTest {

    @Test
    void rebuildAllPersistsGlobalAndKnowledgeScopeDerivedIndexThenLoadsKnowledgeScope() {
        InMemoryMapper<SuperAgentDocument> documentStore = new InMemoryMapper<>(List.of(
            document(10L, "security"),
            document(11L, "security"),
            document(12L, "release")
        ));
        InMemoryMapper<SuperAgentKgEntity> entityStore = new InMemoryMapper<>(List.of(
            entity(1001L, 10L, 20L, "AuditTrail", null, "SYSTEM", "{\"aliases\":[\"审计系统\"],\"rankBoost\":0.7}"),
            entity(1101L, 11L, 21L, "审计系统", "AuditTrail", "SYSTEM", "{\"rankBoost\":0.6}"),
            entity(1002L, 10L, 20L, "权限申请", null, "PROCESS", "{\"rankBoost\":0.3}"),
            entity(1102L, 11L, 21L, "权限申请", null, "PROCESS", "{\"rankBoost\":0.3}"),
            entity(1201L, 12L, 22L, "发布系统", null, "SYSTEM", "{\"rankBoost\":0.4}")
        ));
        InMemoryMapper<SuperAgentKgRelation> relationStore = new InMemoryMapper<>(List.of(
            relation(2001L, 10L, 20L, 1001L, 1002L, "RECORDS"),
            relation(2101L, 11L, 21L, 1101L, 1102L, "RECORDS"),
            relation(2201L, 12L, 22L, 1201L, 1002L, "ASSOCIATED_WITH")
        ));
        InMemoryMapper<SuperAgentKgEvidence> evidenceStore = new InMemoryMapper<>(List.of(
            evidence(3001L, 10L, 20L, 2001L, "AuditTrail 记录权限申请、审批、回收。"),
            evidence(3101L, 11L, 21L, 2101L, "审计系统也称 AuditTrail，用于统一记录权限申请。"),
            evidence(3201L, 12L, 22L, 2201L, "发布系统和权限申请存在关联。")
        ));
        InMemoryMapper<SuperAgentKgCanonicalEntityGroup> canonicalGroupStore = new InMemoryMapper<>(List.of());
        InMemoryMapper<SuperAgentKgCanonicalEntityMember> canonicalMemberStore = new InMemoryMapper<>(List.of());
        InMemoryMapper<SuperAgentKgRelationGroup> relationGroupStore = new InMemoryMapper<>(List.of());
        InMemoryMapper<SuperAgentKgRelationGroupMember> relationGroupMemberStore = new InMemoryMapper<>(List.of());
        ObjectMapper objectMapper = new ObjectMapper();
        GraphRagCrossDocumentIndexServiceImpl service = new GraphRagCrossDocumentIndexServiceImpl(
            documentStore.proxy(SuperAgentDocumentMapper.class),
            entityStore.proxy(SuperAgentKgEntityMapper.class),
            relationStore.proxy(SuperAgentKgRelationMapper.class),
            evidenceStore.proxy(SuperAgentKgEvidenceMapper.class),
            canonicalGroupStore.proxy(SuperAgentKgCanonicalEntityGroupMapper.class),
            canonicalMemberStore.proxy(SuperAgentKgCanonicalEntityMemberMapper.class),
            relationGroupStore.proxy(SuperAgentKgRelationGroupMapper.class),
            relationGroupMemberStore.proxy(SuperAgentKgRelationGroupMemberMapper.class),
            new GraphRagCrossDocumentIndexSupport(objectMapper),
            uidGenerator(),
            objectMapper
        );

        List<GraphRagCrossDocumentIndexBuildResult> results = service.rebuildAll();

        assertThat(results).extracting(GraphRagCrossDocumentIndexBuildResult::getScopeKey)
            .containsExactly("global", "knowledge:security", "knowledge:release");
        assertThat(canonicalGroupStore.items()).extracting(SuperAgentKgCanonicalEntityGroup::getScopeKey)
            .contains("global", "knowledge:security", "knowledge:release");
        assertThat(canonicalMemberStore.items()).extracting(SuperAgentKgCanonicalEntityMember::getScopeKey)
            .contains("global", "knowledge:security", "knowledge:release");
        assertThat(relationGroupStore.items()).extracting(SuperAgentKgRelationGroup::getScopeKey)
            .contains("global", "knowledge:security");
        assertThat(relationGroupStore.items()).allSatisfy(group -> {
            assertThat(group.getGroupKey()).startsWith("sha256:");
            Map<String, Object> metadata = objectMapper.readValue(group.getMetadataJson(), Map.class);
            assertThat(metadata.get("naturalGroupKey")).asString().contains("->");
            assertThat(metadata).containsKey("qualityScore");
            assertThat((Double) metadata.get("qualityScore")).isGreaterThan(0D);
            assertThat(metadata).containsKey("qualityReasons");
            assertThat(metadata).containsEntry("rankAlgorithm", "java.cross_document_pagerank.v1");
            assertThat((Double) metadata.get("pagerank")).isGreaterThan(0D);
            assertThat((Double) metadata.get("rankBoost")).isGreaterThan(0D);
            assertThat((Integer) metadata.get("rankPosition")).isGreaterThan(0);
            assertThat((Integer) metadata.get("degree")).isGreaterThan(0);
        });
        assertThat(canonicalGroupStore.items()).allSatisfy(group -> {
            Map<String, Object> metadata = objectMapper.readValue(group.getMetadataJson(), Map.class);
            assertThat(metadata).containsKey("qualityScore");
            assertThat((Double) metadata.get("qualityScore")).isGreaterThan(0D);
            if ("global".equals(group.getScopeKey()) && group.getMetadataJson().contains("\"pagerank\"")) {
                assertThat(metadata).containsEntry("rankAlgorithm", "java.cross_document_pagerank.v1");
                assertThat((Double) metadata.get("pagerank")).isGreaterThan(0D);
                assertThat((Double) metadata.get("rankBoost")).isGreaterThan(0D);
                assertThat((Integer) metadata.get("rankPosition")).isGreaterThan(0);
            }
        });
        assertThat(canonicalGroupStore.deleteCount()).isEqualTo(1);
        assertThat(canonicalMemberStore.deleteCount()).isEqualTo(1);
        assertThat(relationGroupStore.deleteCount()).isEqualTo(1);
        assertThat(relationGroupMemberStore.deleteCount()).isEqualTo(1);

        GraphRagCrossDocumentIndexServiceImpl loadService = new GraphRagCrossDocumentIndexServiceImpl(
            new InMemoryMapper<>(List.of(document(10L, "security"), document(11L, "security"))).proxy(SuperAgentDocumentMapper.class),
            new InMemoryMapper<>(entityStore.items()).proxy(SuperAgentKgEntityMapper.class),
            new InMemoryMapper<>(List.of(
                relation(2001L, 10L, 20L, 1001L, 1002L, "RECORDS"),
                relation(2101L, 11L, 21L, 1101L, 1102L, "RECORDS")
            )).proxy(SuperAgentKgRelationMapper.class),
            new InMemoryMapper<>(evidenceStore.items()).proxy(SuperAgentKgEvidenceMapper.class),
            new InMemoryMapper<>(canonicalGroupStore.items().stream()
                .filter(group -> "knowledge:security".equals(group.getScopeKey()))
                .toList()).proxy(SuperAgentKgCanonicalEntityGroupMapper.class),
            new InMemoryMapper<>(canonicalMemberStore.items().stream()
                .filter(member -> "knowledge:security".equals(member.getScopeKey()))
                .toList()).proxy(SuperAgentKgCanonicalEntityMemberMapper.class),
            new InMemoryMapper<>(relationGroupStore.items().stream()
                .filter(group -> "knowledge:security".equals(group.getScopeKey()))
                .toList()).proxy(SuperAgentKgRelationGroupMapper.class),
            new InMemoryMapper<>(relationGroupMemberStore.items().stream()
                .filter(member -> "knowledge:security".equals(member.getScopeKey()))
                .toList()).proxy(SuperAgentKgRelationGroupMemberMapper.class),
            new GraphRagCrossDocumentIndexSupport(objectMapper),
            uidGenerator(),
            objectMapper
        );
        GraphRagCrossDocumentIndex loaded = loadService.loadIndex(List.of(10L, 11L), List.of(20L, 21L));

        GraphRagCrossDocumentIndex.CanonicalEntityGroup auditGroup = loaded.canonicalGroupOf(1001L);
        assertThat(auditGroup).isNotNull();
        assertThat(auditGroup.entityIds()).containsExactlyInAnyOrder(1001L, 1101L);
        assertThat(auditGroup.documentIds()).containsExactlyInAnyOrder(10L, 11L);
        assertThat(auditGroup.qualityProfile().score()).isGreaterThan(0D);
        assertThat(auditGroup.qualityProfile().qualityReasons()).isNotEmpty();
        assertThat(auditGroup.rankProfile().pagerank()).isGreaterThan(0D);
        assertThat(auditGroup.rankProfile().rankBoost()).isGreaterThan(0D);
        assertThat(auditGroup.rankProfile().rankPosition()).isGreaterThan(0);
        assertThat(auditGroup.rankProfile().degree()).isGreaterThan(0);
        assertThat(loaded.canonicalGroupOf(1201L)).isNull();
        GraphRagCrossDocumentIndex.RelationGroup recordsGroup = loaded.relationGroupOf(2001L);
        assertThat(recordsGroup).isNotNull();
        assertThat(recordsGroup.relationCount()).isEqualTo(2);
        assertThat(recordsGroup.evidenceCount()).isEqualTo(2);
        assertThat(recordsGroup.documentCount()).isEqualTo(2);
        assertThat(recordsGroup.qualityProfile().score()).isGreaterThan(0D);
        assertThat(recordsGroup.qualityProfile().qualityReasons()).contains("groundedEvidence");
        assertThat(recordsGroup.rankProfile().pagerank()).isGreaterThan(0D);
        assertThat(recordsGroup.rankProfile().rankBoost()).isGreaterThan(0D);
        assertThat(recordsGroup.rankProfile().rankPosition()).isGreaterThan(0);
        assertThat(loaded.relationGroupOf(2201L)).isNull();
    }

    @Test
    void rebuildAllDoesNotMergeUnrelatedEntitiesByGeneratedCanonicalKeyOnly() {
        InMemoryMapper<SuperAgentDocument> documentStore = new InMemoryMapper<>(List.of(
            document(10L, "security"),
            document(11L, "security")
        ));
        InMemoryMapper<SuperAgentKgEntity> entityStore = new InMemoryMapper<>(List.of(
            entity(1001L, 10L, 20L, "AuditTrail", null, "CONCEPT", "{\"canonicalKey\":\"ENT_SHARED\",\"rankBoost\":0.5}"),
            entity(1101L, 11L, 21L, "GraphRAG", null, "CONCEPT", "{\"canonicalKey\":\"ENT_SHARED\",\"rankBoost\":0.5}")
        ));
        InMemoryMapper<SuperAgentKgRelation> relationStore = new InMemoryMapper<>(List.of());
        InMemoryMapper<SuperAgentKgEvidence> evidenceStore = new InMemoryMapper<>(List.of());
        InMemoryMapper<SuperAgentKgCanonicalEntityGroup> canonicalGroupStore = new InMemoryMapper<>(List.of());
        InMemoryMapper<SuperAgentKgCanonicalEntityMember> canonicalMemberStore = new InMemoryMapper<>(List.of());
        ObjectMapper objectMapper = new ObjectMapper();
        GraphRagCrossDocumentIndexServiceImpl service = new GraphRagCrossDocumentIndexServiceImpl(
            documentStore.proxy(SuperAgentDocumentMapper.class),
            entityStore.proxy(SuperAgentKgEntityMapper.class),
            relationStore.proxy(SuperAgentKgRelationMapper.class),
            evidenceStore.proxy(SuperAgentKgEvidenceMapper.class),
            canonicalGroupStore.proxy(SuperAgentKgCanonicalEntityGroupMapper.class),
            canonicalMemberStore.proxy(SuperAgentKgCanonicalEntityMemberMapper.class),
            new InMemoryMapper<SuperAgentKgRelationGroup>(List.of()).proxy(SuperAgentKgRelationGroupMapper.class),
            new InMemoryMapper<SuperAgentKgRelationGroupMember>(List.of()).proxy(SuperAgentKgRelationGroupMemberMapper.class),
            new GraphRagCrossDocumentIndexSupport(objectMapper),
            uidGenerator(),
            objectMapper
        );

        service.rebuildAll();

        assertThat(canonicalGroupStore.items().stream()
            .filter(group -> "global".equals(group.getScopeKey()))
            .map(SuperAgentKgCanonicalEntityGroup::getCanonicalName)
            .toList()).contains("AuditTrail", "GraphRAG");
        assertThat(canonicalGroupStore.items().stream()
            .filter(group -> "global".equals(group.getScopeKey()))
            .map(SuperAgentKgCanonicalEntityGroup::getCanonicalName)
            .toList()).hasSize(2);
    }

    @Test
    void rebuildAllPersistsEntityQualityNoiseReasonsForSentenceLikeNames() throws Exception {
        InMemoryMapper<SuperAgentDocument> documentStore = new InMemoryMapper<>(List.of(
            document(10L, "security")
        ));
        InMemoryMapper<SuperAgentKgEntity> entityStore = new InMemoryMapper<>(List.of(
            entity(
                1001L,
                10L,
                20L,
                "AuditTrail",
                null,
                "SYSTEM",
                "{\"candidateSources\":[\"englishToken\",\"ner.pattern\"],\"extractorSources\":[\"ner\"],\"evidenceIds\":[\"EV1\"],\"confidence\":0.9}"
            ),
            entity(
                1002L,
                10L,
                20L,
                "审计系统负责接收权限变更事件，并写入统一审计流水",
                null,
                "CONCEPT",
                "{\"candidateSources\":[\"mixedPhrase\"],\"extractorSources\":[\"rule\"],\"evidenceIds\":[\"EV2\"],\"confidence\":0.5}"
            )
        ));
        InMemoryMapper<SuperAgentKgCanonicalEntityGroup> canonicalGroupStore = new InMemoryMapper<>(List.of());
        ObjectMapper objectMapper = new ObjectMapper();
        GraphRagCrossDocumentIndexServiceImpl service = new GraphRagCrossDocumentIndexServiceImpl(
            documentStore.proxy(SuperAgentDocumentMapper.class),
            entityStore.proxy(SuperAgentKgEntityMapper.class),
            new InMemoryMapper<SuperAgentKgRelation>(List.of()).proxy(SuperAgentKgRelationMapper.class),
            new InMemoryMapper<SuperAgentKgEvidence>(List.of()).proxy(SuperAgentKgEvidenceMapper.class),
            canonicalGroupStore.proxy(SuperAgentKgCanonicalEntityGroupMapper.class),
            new InMemoryMapper<SuperAgentKgCanonicalEntityMember>(List.of()).proxy(SuperAgentKgCanonicalEntityMemberMapper.class),
            new InMemoryMapper<SuperAgentKgRelationGroup>(List.of()).proxy(SuperAgentKgRelationGroupMapper.class),
            new InMemoryMapper<SuperAgentKgRelationGroupMember>(List.of()).proxy(SuperAgentKgRelationGroupMemberMapper.class),
            new GraphRagCrossDocumentIndexSupport(objectMapper),
            uidGenerator(),
            objectMapper
        );

        service.rebuildAll();

        SuperAgentKgCanonicalEntityGroup auditGroup = canonicalGroupStore.items().stream()
            .filter(group -> "global".equals(group.getScopeKey()))
            .filter(group -> "AuditTrail".equals(group.getCanonicalName()))
            .findFirst()
            .orElseThrow();
        SuperAgentKgCanonicalEntityGroup sentenceLikeGroup = canonicalGroupStore.items().stream()
            .filter(group -> "global".equals(group.getScopeKey()))
            .filter(group -> group.getCanonicalName().contains("权限变更事件"))
            .findFirst()
            .orElseThrow();
        Map<String, Object> sentenceMetadata = objectMapper.readValue(sentenceLikeGroup.getMetadataJson(), Map.class);

        assertThat(sentenceMetadata.get("noiseReasons")).asList().contains("sentenceLikeName", "weakEntitySource");
        assertThat(sentenceLikeGroup.getRankScore()).isLessThan(auditGroup.getRankScore());
    }

    private static SuperAgentDocument document(Long id, String knowledgeScopeCode) {
        SuperAgentDocument document = new SuperAgentDocument();
        document.setId(id);
        document.setKnowledgeScopeCode(knowledgeScopeCode);
        document.setStatus(BusinessStatus.YES.getCode());
        return document;
    }

    private static SuperAgentKgEntity entity(Long id,
                                             Long documentId,
                                             Long taskId,
                                             String name,
                                             String normalizedName,
                                             String entityType,
                                             String metadataJson) {
        SuperAgentKgEntity entity = new SuperAgentKgEntity();
        entity.setId(id);
        entity.setDocumentId(documentId);
        entity.setTaskId(taskId);
        entity.setName(name);
        entity.setNormalizedName(normalizedName);
        entity.setEntityType(entityType);
        entity.setEntityKey(entityType + ":" + name);
        entity.setDescription(name + " description");
        entity.setMetadataJson(metadataJson);
        entity.setStatus(BusinessStatus.YES.getCode());
        return entity;
    }

    private static SuperAgentKgRelation relation(Long id,
                                                 Long documentId,
                                                 Long taskId,
                                                 Long sourceEntityId,
                                                 Long targetEntityId,
                                                 String relationType) {
        SuperAgentKgRelation relation = new SuperAgentKgRelation();
        relation.setId(id);
        relation.setDocumentId(documentId);
        relation.setTaskId(taskId);
        relation.setSourceEntityId(sourceEntityId);
        relation.setTargetEntityId(targetEntityId);
        relation.setRelationType(relationType);
        relation.setDescription(relationType + " relation");
        relation.setWeight(BigDecimal.valueOf(0.9D));
        relation.setMetadataJson("{\"rankBoost\":0.6}");
        relation.setStatus(BusinessStatus.YES.getCode());
        return relation;
    }

    private static SuperAgentKgEvidence evidence(Long id,
                                                 Long documentId,
                                                 Long taskId,
                                                 Long relationId,
                                                 String quoteText) {
        SuperAgentKgEvidence evidence = new SuperAgentKgEvidence();
        evidence.setId(id);
        evidence.setDocumentId(documentId);
        evidence.setTaskId(taskId);
        evidence.setRelationId(relationId);
        evidence.setQuoteText(quoteText);
        evidence.setStatus(BusinessStatus.YES.getCode());
        return evidence;
    }

    private static UidGenerator uidGenerator() {
        AtomicLong sequence = new AtomicLong(10000L);
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

    private static final class InMemoryMapper<T> {

        private final List<T> items;
        private int deleteCount;

        private InMemoryMapper(List<T> seedItems) {
            this.items = new ArrayList<>(seedItems);
        }

        @SuppressWarnings("unchecked")
        private <M> M proxy(Class<M> mapperType) {
            return (M) Proxy.newProxyInstance(
                mapperType.getClassLoader(),
                new Class<?>[]{mapperType},
                (proxy, method, args) -> {
                    if ("selectList".equals(method.getName())) {
                        return List.copyOf(items);
                    }
                    if ("insert".equals(method.getName())) {
                        items.add((T) args[0]);
                        return 1;
                    }
                    if ("delete".equals(method.getName())) {
                        items.clear();
                        deleteCount++;
                        return 1;
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

        private List<T> items() {
            return items;
        }

        private int deleteCount() {
            return deleteCount;
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
