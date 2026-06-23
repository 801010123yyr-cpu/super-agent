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
import org.javaup.ai.manage.model.graph.GraphRagQueryCatalog;
import org.javaup.ai.manage.model.graph.GraphRagQueryPlanAdvice;
import org.javaup.ai.manage.model.graph.GraphRagSearchResult;
import org.javaup.ai.manage.service.GraphRagQueryPlanAdvisor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GraphRagSearchServiceImplTest {

    @Test
    void communityReportsCanMatchEvenWithoutEntitySeed() {
        SuperAgentKgCommunity community = new SuperAgentKgCommunity();
        community.setId(1001L);
        community.setDocumentId(10L);
        community.setTaskId(20L);
        community.setTitle("发布变更风险");
        community.setSummary("覆盖发布窗口、回滚方案、审批要求和变更风险控制。");
        community.setEvidenceIdsJson("[3001]");
        community.setMetadataJson("{\"rankBoost\":0.8}");

        SuperAgentKgEvidence evidence = new SuperAgentKgEvidence();
        evidence.setId(3001L);
        evidence.setDocumentId(10L);
        evidence.setTaskId(20L);
        evidence.setChunkId(4001L);
        evidence.setParentBlockId(5001L);
        evidence.setQuoteText("高风险发布必须准备回滚方案，并在发布窗口内完成审批。");
        evidence.setPageNo(7);
        evidence.setSectionPath("发布管理/高风险变更");

        AtomicInteger relationSelectCount = new AtomicInteger();
        SuperAgentKgEntityMapper entityMapper = mapper(SuperAgentKgEntityMapper.class, List.<SuperAgentKgEntity>of(), null);
        SuperAgentKgRelationMapper relationMapper = mapper(SuperAgentKgRelationMapper.class, List.<SuperAgentKgRelation>of(), relationSelectCount);
        SuperAgentKgEvidenceMapper evidenceMapper = mapper(SuperAgentKgEvidenceMapper.class, List.of(evidence), null);
        SuperAgentKgCommunityMapper communityMapper = mapper(SuperAgentKgCommunityMapper.class, List.of(community), null);

        GraphRagSearchServiceImpl service = new GraphRagSearchServiceImpl(
            entityMapper,
            relationMapper,
            evidenceMapper,
            communityMapper,
            new ObjectMapper()
        );

        List<GraphRagSearchResult> results = service.search(
            "发布变更风险怎么控制？",
            List.of(10L),
            List.of(20L),
            3,
            2
        );

        assertThat(results).hasSize(1);
        GraphRagSearchResult result = results.get(0);
        assertThat(result.getCommunityId()).isEqualTo(1001L);
        assertThat(result.getCommunityTitle()).isEqualTo("发布变更风险");
        assertThat(result.getCommunitySummary()).contains("回滚方案");
        assertThat(result.getRankBoost()).isEqualTo(0.8D);
        assertThat(result.getEvidenceId()).isEqualTo(3001L);
        assertThat(result.getChunkId()).isEqualTo(4001L);
        assertThat(result.getParentBlockId()).isEqualTo(5001L);
        assertThat(result.getGraphPath()).isEqualTo("社区报告：发布变更风险");
        assertThat(result.getScore()).isGreaterThan(0D);
        assertThat(relationSelectCount).hasValue(0);
    }

    @Test
    void controlledAdvisorCanSeedCommunityReportWithoutEntityCatalog() {
        SuperAgentKgCommunity community = new SuperAgentKgCommunity();
        community.setId(1001L);
        community.setDocumentId(10L);
        community.setTaskId(20L);
        community.setTitle("发布变更风险");
        community.setSummary("覆盖发布窗口、审批、回滚和风险控制。");
        community.setEvidenceIdsJson("[3001]");
        community.setMetadataJson("{\"rankBoost\":0.72}");

        SuperAgentKgEvidence evidence = evidence(3001L, 10L, 20L, 4001L, 5001L, null, null, "高风险发布必须有审批和回滚方案。", 7);
        AtomicInteger advisorCallCount = new AtomicInteger();

        GraphRagQueryPlanAdvisor advisor = (question, catalog) -> {
            advisorCallCount.incrementAndGet();
            assertThat(catalog.getEntities()).isEmpty();
            assertThat(catalog.getCommunities()).extracting(GraphRagQueryCatalog.CommunityItem::getCommunityId)
                .containsExactly(1001L);
            return Optional.of(GraphRagQueryPlanAdvice.builder()
                .graphQuery(true)
                .communityIds(List.of(1001L))
                .communityQuestion(true)
                .confidence(0.86D)
                .reason("用户询问图谱社区总结，Java 只接受已有 communityId")
                .build());
        };

        GraphRagSearchServiceImpl service = new GraphRagSearchServiceImpl(
            mapper(SuperAgentKgEntityMapper.class, List.<SuperAgentKgEntity>of(), null),
            mapper(SuperAgentKgRelationMapper.class, List.<SuperAgentKgRelation>of(), null),
            mapper(SuperAgentKgEvidenceMapper.class, List.of(evidence), null),
            mapper(SuperAgentKgCommunityMapper.class, List.of(community), null),
            new ObjectMapper(),
            advisor
        );

        List<GraphRagSearchResult> results = service.search(
            "这份文档的图谱社区总结是什么？",
            List.of(10L),
            List.of(20L),
            3,
            2
        );

        assertThat(advisorCallCount).hasValue(1);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getCommunityId()).isEqualTo(1001L);
        assertThat(results.get(0).getGraphPath()).isEqualTo("社区报告：发布变更风险");
        assertThat(results.get(0).getRankBoost()).isEqualTo(0.72D);
    }

    @Test
    void relationQuestionsUseAliasAndRelationSeeds() {
        SuperAgentKgEntity source = entity(1001L, 10L, 20L, "SuperAgent", "超级智能体", "SYSTEM", "负责调用编排与图谱构建。");
        SuperAgentKgEntity target = entity(1002L, 10L, 20L, "RagTools", null, "SYSTEM", "图谱抽取与重排工具。");
        SuperAgentKgEntity graph = entity(1003L, 10L, 20L, "GraphRAG", null, "SYSTEM", "知识图谱抽取模块。");

        SuperAgentKgRelation relation = relation(2001L, 10L, 20L, 1001L, 1002L, "CALLS", "超级智能体调用 RagTools 完成图谱抽取。", 0.9D);
        SuperAgentKgRelation supportRelation = relation(2002L, 10L, 20L, 1002L, 1003L, "SUPPORTS", "RagTools 支持 GraphRAG 抽取。", 0.8D);

        SuperAgentKgEvidence relationEvidence = evidence(3001L, 10L, 20L, 4001L, 5001L, 2001L, null, "超级智能体调用 RagTools 服务来完成 GraphRAG 构建。", 5);
        SuperAgentKgEvidence supportEvidence = evidence(3002L, 10L, 20L, 4002L, 5002L, 2002L, null, "RagTools 支持 GraphRAG 抽取与社区报告。", 6);

        GraphRagSearchServiceImpl service = new GraphRagSearchServiceImpl(
            mapper(SuperAgentKgEntityMapper.class, List.of(source, target, graph), null),
            mapper(SuperAgentKgRelationMapper.class, List.of(relation, supportRelation), null),
            mapper(SuperAgentKgEvidenceMapper.class, List.of(relationEvidence, supportEvidence), null),
            mapper(SuperAgentKgCommunityMapper.class, List.<SuperAgentKgCommunity>of(), null),
            new ObjectMapper()
        );

        List<GraphRagSearchResult> results = service.search(
            "超级智能体 调用 RagTools 是什么关系？",
            List.of(10L),
            List.of(20L),
            5,
            2
        );

        assertThat(results).isNotEmpty();
        GraphRagSearchResult result = results.get(0);
        assertThat(result.getRelationType()).isEqualTo("CALLS");
        assertThat(result.getGraphPath()).contains("关系匹配");
        assertThat(result.getEntityName()).isEqualTo("SuperAgent");
        assertThat(result.getRelatedEntityName()).isEqualTo("RagTools");
        assertThat(result.getRankBoost()).isEqualTo(0.75D);
        assertThat(result.getScore()).isGreaterThan(0D);
    }

    @Test
    void controlledAdvisorCanSeedRelationWhenQuestionUsesImplicitGraphIntent() {
        SuperAgentKgEntity source = entity(1001L, 10L, 20L, "SuperAgent", "超级智能体", "SYSTEM", "负责调用编排与图谱构建。");
        SuperAgentKgEntity target = entity(1002L, 10L, 20L, "RagTools", null, "SYSTEM", "图谱抽取与重排工具。");
        SuperAgentKgRelation relation = relation(2001L, 10L, 20L, 1001L, 1002L, "CALLS", "SuperAgent 调用 RagTools。", 0.9D);
        SuperAgentKgEvidence evidence = evidence(3001L, 10L, 20L, 4001L, 5001L, 2001L, null, "SuperAgent 通过 RagTools 完成 GraphRAG 抽取。", 5);
        AtomicInteger advisorCallCount = new AtomicInteger();

        GraphRagQueryPlanAdvisor advisor = (question, catalog) -> {
            advisorCallCount.incrementAndGet();
            assertThat(catalog.getEntities()).extracting(GraphRagQueryCatalog.EntityItem::getEntityId)
                .containsExactly(1001L, 1002L);
            assertThat(catalog.getRelations()).extracting(GraphRagQueryCatalog.RelationItem::getRelationId)
                .containsExactly(2001L);
            return Optional.of(GraphRagQueryPlanAdvice.builder()
                .graphQuery(true)
                .entityNames(List.of("SuperAgent", "RagTools"))
                .relationTypes(List.of("CALLS"))
                .relationIds(List.of(2001L))
                .relationQuestion(true)
                .maxHops(1)
                .confidence(0.88D)
                .reason("用户询问图谱链路，但没有显式调用词")
                .build());
        };

        GraphRagSearchServiceImpl service = new GraphRagSearchServiceImpl(
            mapper(SuperAgentKgEntityMapper.class, List.of(source, target), null),
            mapper(SuperAgentKgRelationMapper.class, List.of(relation), null),
            mapper(SuperAgentKgEvidenceMapper.class, List.of(evidence), null),
            mapper(SuperAgentKgCommunityMapper.class, List.<SuperAgentKgCommunity>of(), null),
            new ObjectMapper(),
            advisor
        );

        List<GraphRagSearchResult> results = service.search(
            "SuperAgent 和 RagTools 的图谱链路是什么？",
            List.of(10L),
            List.of(20L),
            3,
            2
        );

        assertThat(advisorCallCount).hasValue(1);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getRelationId()).isEqualTo(2001L);
        assertThat(results.get(0).getRelationType()).isEqualTo("CALLS");
        assertThat(results.get(0).getGraphPath()).contains("关系匹配");
    }

    @Test
    void controlledAdvisorRejectsUnknownGraphIds() {
        SuperAgentKgEntity source = entity(1001L, 10L, 20L, "SuperAgent", null, "SYSTEM", "负责调用编排。");
        SuperAgentKgEntity target = entity(1002L, 10L, 20L, "RagTools", null, "SYSTEM", "图谱抽取工具。");
        SuperAgentKgRelation relation = relation(2001L, 10L, 20L, 1001L, 1002L, "CALLS", "SuperAgent 调用 RagTools。", 0.9D);
        SuperAgentKgEvidence evidence = evidence(3001L, 10L, 20L, 4001L, 5001L, 2001L, null, "SuperAgent 通过 RagTools 完成 GraphRAG 抽取。", 5);
        AtomicInteger advisorCallCount = new AtomicInteger();

        GraphRagQueryPlanAdvisor advisor = (question, catalog) -> {
            advisorCallCount.incrementAndGet();
            return Optional.of(GraphRagQueryPlanAdvice.builder()
                .graphQuery(true)
                .relationTypes(List.of("MADE_UP_RELATION"))
                .relationIds(List.of(9999L))
                .relationQuestion(true)
                .confidence(0.91D)
                .reason("非法关系类型和 ID 应被 Java 校验拒绝")
                .build());
        };

        GraphRagSearchServiceImpl service = new GraphRagSearchServiceImpl(
            mapper(SuperAgentKgEntityMapper.class, List.of(source, target), null),
            mapper(SuperAgentKgRelationMapper.class, List.of(relation), null),
            mapper(SuperAgentKgEvidenceMapper.class, List.of(evidence), null),
            mapper(SuperAgentKgCommunityMapper.class, List.<SuperAgentKgCommunity>of(), null),
            new ObjectMapper(),
            advisor
        );

        List<GraphRagSearchResult> results = service.search(
            "这条图谱链路是什么？",
            List.of(10L),
            List.of(20L),
            3,
            2
        );

        assertThat(advisorCallCount).hasValue(1);
        assertThat(results).isEmpty();
    }

    private static SuperAgentKgEntity entity(Long id,
                                             Long documentId,
                                             Long taskId,
                                             String name,
                                             String alias,
                                             String entityType,
                                             String description) {
        SuperAgentKgEntity entity = new SuperAgentKgEntity();
        entity.setId(id);
        entity.setDocumentId(documentId);
        entity.setTaskId(taskId);
        entity.setEntityKey("ENT_" + id);
        entity.setName(name);
        entity.setNormalizedName(name.toLowerCase());
        entity.setEntityType(entityType);
        entity.setDescription(description);
        if (alias != null) {
            entity.setMetadataJson("{\"aliases\":[\"" + alias + "\"],\"confidence\":0.92}");
        }
        else {
            entity.setMetadataJson("{\"confidence\":0.88}");
        }
        return entity;
    }

    private static SuperAgentKgRelation relation(Long id,
                                                 Long documentId,
                                                 Long taskId,
                                                 Long sourceEntityId,
                                                 Long targetEntityId,
                                                 String relationType,
                                                 String description,
                                                 Double weight) {
        SuperAgentKgRelation relation = new SuperAgentKgRelation();
        relation.setId(id);
        relation.setDocumentId(documentId);
        relation.setTaskId(taskId);
        relation.setSourceEntityId(sourceEntityId);
        relation.setTargetEntityId(targetEntityId);
        relation.setRelationType(relationType);
        relation.setDescription(description);
        relation.setWeight(java.math.BigDecimal.valueOf(weight));
        relation.setMetadataJson("{\"rankBoost\":0.75}");
        return relation;
    }

    private static SuperAgentKgEvidence evidence(Long id,
                                                 Long documentId,
                                                 Long taskId,
                                                 Long chunkId,
                                                 Long parentBlockId,
                                                 Long relationId,
                                                 Long entityId,
                                                 String quoteText,
                                                 int pageNo) {
        SuperAgentKgEvidence evidence = new SuperAgentKgEvidence();
        evidence.setId(id);
        evidence.setDocumentId(documentId);
        evidence.setTaskId(taskId);
        evidence.setChunkId(chunkId);
        evidence.setParentBlockId(parentBlockId);
        evidence.setRelationId(relationId);
        evidence.setEntityId(entityId);
        evidence.setQuoteText(quoteText);
        evidence.setPageNo(pageNo);
        evidence.setPageRange("P" + pageNo);
        evidence.setSectionPath("架构/GraphRAG");
        evidence.setBboxJson("{}");
        return evidence;
    }

    @SuppressWarnings("unchecked")
    private static <T> T mapper(Class<T> mapperType, List<?> selectListResult, AtomicInteger selectListCount) {
        return (T) Proxy.newProxyInstance(
            mapperType.getClassLoader(),
            new Class<?>[]{mapperType},
            (proxy, method, args) -> {
                if ("selectList".equals(method.getName())) {
                    if (selectListCount != null) {
                        selectListCount.incrementAndGet();
                    }
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
