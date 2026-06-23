package org.javaup.ai.manage.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.manage.data.SuperAgentKgCommunity;
import org.javaup.ai.manage.data.SuperAgentKgEntity;
import org.javaup.ai.manage.data.SuperAgentKgEvidence;
import org.javaup.ai.manage.data.SuperAgentKgRelation;
import org.javaup.ai.manage.mapper.SuperAgentKgCommunityMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgEntityMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgEvidenceMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgRelationMapper;
import org.javaup.ai.manage.model.graph.GraphRagQualityReport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphRagQualityServiceImplTest {

    @Test
    void evaluatesGroundedEvidenceRankAndControlledEnhancementSignals() {
        SuperAgentKgEntity source = entity(
            1001L,
            "OrderService",
            "{\"rankBoost\":0.9,\"entityResolutionEnhanced\":true,"
                + "\"sourceMetadata\":[{\"sourceType\":\"llm.controlled.extract.v1\"}]}"
        );
        SuperAgentKgEntity target = entity(1002L, "PaymentService", "{\"rankBoost\":0.7}");
        SuperAgentKgRelation relation = relation(
            2001L,
            1001L,
            1002L,
            "{\"rankBoost\":0.8,\"sourceType\":\"llm.controlled.extract.v1\"}"
        );
        SuperAgentKgEvidence entityEvidence = evidence(
            3001L,
            1001L,
            null,
            "{\"sourceType\":\"llm.controlled.extract.v1\"}"
        );
        SuperAgentKgEvidence relationEvidence = evidence(
            3002L,
            null,
            2001L,
            "{\"sourceType\":\"llm.controlled.extract.v1\"}"
        );
        SuperAgentKgCommunity community = community(
            4001L,
            "[3002]",
            "{\"rankBoost\":0.6,\"communityReportEnhanced\":true}"
        );

        GraphRagQualityServiceImpl service = new GraphRagQualityServiceImpl(
            mapper(SuperAgentKgEntityMapper.class, List.of(source, target)),
            mapper(SuperAgentKgRelationMapper.class, List.of(relation)),
            mapper(SuperAgentKgEvidenceMapper.class, List.of(entityEvidence, relationEvidence)),
            mapper(SuperAgentKgCommunityMapper.class, List.of(community)),
            new ObjectMapper()
        );

        GraphRagQualityReport report = service.evaluate(10L, 20L);

        assertThat(report.getQualityLevel()).isEqualTo(GraphRagQualityReport.LEVEL_STRONG);
        assertThat(report.getQualityScore()).isEqualTo(1D);
        assertThat(report.getEntityCount()).isEqualTo(2L);
        assertThat(report.getGroundedEntityCount()).isEqualTo(2L);
        assertThat(report.getGroundedRelationCount()).isEqualTo(1L);
        assertThat(report.getTraceableEvidenceCount()).isEqualTo(2L);
        assertThat(report.getCommunityWithEvidenceCount()).isEqualTo(1L);
        assertThat(report.getRankedGraphItemCount()).isEqualTo(4L);
        assertThat(report.getControlledExtractionItemCount()).isEqualTo(4L);
        assertThat(report.getEntityResolutionEnhancedCount()).isEqualTo(1L);
        assertThat(report.getCommunityReportEnhancedCount()).isEqualTo(1L);
        assertThat(report.getSignals()).extracting(GraphRagQualityReport.SignalItem::getLabel)
            .contains("证据追溯", "实体证据覆盖", "关系证据覆盖", "受控增强命中");
    }

    @Test
    void emptyGraphReturnsEmptyReport() {
        GraphRagQualityServiceImpl service = new GraphRagQualityServiceImpl(
            mapper(SuperAgentKgEntityMapper.class, List.of()),
            mapper(SuperAgentKgRelationMapper.class, List.of()),
            mapper(SuperAgentKgEvidenceMapper.class, List.of()),
            mapper(SuperAgentKgCommunityMapper.class, List.of()),
            new ObjectMapper()
        );

        GraphRagQualityReport report = service.evaluate(10L, 20L);

        assertThat(report.getQualityLevel()).isEqualTo(GraphRagQualityReport.LEVEL_EMPTY);
        assertThat(report.getQualityScore()).isZero();
        assertThat(report.getSignals()).isEmpty();
    }

    private static SuperAgentKgEntity entity(Long id, String name, String metadataJson) {
        SuperAgentKgEntity entity = new SuperAgentKgEntity();
        entity.setId(id);
        entity.setDocumentId(10L);
        entity.setTaskId(20L);
        entity.setEntityKey("ENT_" + id);
        entity.setName(name);
        entity.setNormalizedName(name.toLowerCase());
        entity.setEntityType("SYSTEM");
        entity.setDescription(name + " description");
        entity.setMetadataJson(metadataJson);
        return entity;
    }

    private static SuperAgentKgRelation relation(Long id,
                                                 Long sourceEntityId,
                                                 Long targetEntityId,
                                                 String metadataJson) {
        SuperAgentKgRelation relation = new SuperAgentKgRelation();
        relation.setId(id);
        relation.setDocumentId(10L);
        relation.setTaskId(20L);
        relation.setSourceEntityId(sourceEntityId);
        relation.setTargetEntityId(targetEntityId);
        relation.setRelationType("CALLS");
        relation.setDescription("OrderService calls PaymentService.");
        relation.setWeight(BigDecimal.valueOf(0.9D));
        relation.setMetadataJson(metadataJson);
        return relation;
    }

    private static SuperAgentKgEvidence evidence(Long id,
                                                 Long entityId,
                                                 Long relationId,
                                                 String metadataJson) {
        SuperAgentKgEvidence evidence = new SuperAgentKgEvidence();
        evidence.setId(id);
        evidence.setDocumentId(10L);
        evidence.setTaskId(20L);
        evidence.setEntityId(entityId);
        evidence.setRelationId(relationId);
        evidence.setChunkId(5001L);
        evidence.setParentBlockId(6001L);
        evidence.setQuoteText("OrderService 调用 PaymentService 完成支付。");
        evidence.setPageNo(1);
        evidence.setPageRange("P1");
        evidence.setMetadataJson(metadataJson);
        return evidence;
    }

    private static SuperAgentKgCommunity community(Long id, String evidenceIdsJson, String metadataJson) {
        SuperAgentKgCommunity community = new SuperAgentKgCommunity();
        community.setId(id);
        community.setDocumentId(10L);
        community.setTaskId(20L);
        community.setCommunityNo(1);
        community.setTitle("支付链路");
        community.setSummary("订单服务调用支付服务。");
        community.setEntityIdsJson("[1001,1002]");
        community.setRelationIdsJson("[2001]");
        community.setEvidenceIdsJson(evidenceIdsJson);
        community.setMetadataJson(metadataJson);
        return community;
    }

    @SuppressWarnings("unchecked")
    private static <T> T mapper(Class<T> mapperType, List<?> selectListResult) {
        return (T) Proxy.newProxyInstance(
            mapperType.getClassLoader(),
            new Class<?>[]{mapperType},
            (proxy, method, args) -> {
                if ("selectList".equals(method.getName())) {
                    return selectListResult;
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
