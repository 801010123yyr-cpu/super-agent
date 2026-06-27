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
import org.javaup.ai.manage.model.graph.GraphRagCrossDocumentIndexBuildResult;
import org.javaup.ai.manage.service.GraphRagCrossDocumentIndexService;
import org.javaup.ai.manage.service.GraphRagQueryPlanAdvisor;
import org.javaup.ai.manage.support.GraphRagCrossDocumentIndex;
import org.javaup.ai.manage.support.GraphRagCrossDocumentIndexSupport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        assertThat(result.getGraphPath()).contains("一跳");
        assertThat(result.getEntityName()).isEqualTo("SuperAgent");
        assertThat(result.getRelatedEntityName()).isEqualTo("RagTools");
        assertThat(result.getRankBoost()).isEqualTo(0.75D);
        assertThat(result.getScore()).isGreaterThan(0D);
    }

    @Test
    void policyRelationQuestionsUseControlledQueryPlanRelationTypeSeeds() {
        SuperAgentKgEntity l4Data = entity(1001L, 10L, 20L, "L4 数据", "高敏感信息", "CONCEPT", "客户高敏感数据。");
        SuperAgentKgEntity securityDept = entity(1002L, 10L, 20L, "信息安全部", null, "ORG", "审批高敏感数据访问。");
        SuperAgentKgEntity auditTrail = entity(1003L, 10L, 20L, "AuditTrail", null, "SYSTEM", "审计系统。");
        SuperAgentKgEntity permissionApply = entity(1004L, 10L, 20L, "权限申请", null, "PROCESS", "权限申请、审批和回收。");
        SuperAgentKgRelation approves = relation(2001L, 10L, 20L, 1001L, 1002L, "APPROVES", "L4 数据需信息安全部审批。", 0.9D);
        SuperAgentKgRelation records = relation(2002L, 10L, 20L, 1003L, 1004L, "RECORDS", "AuditTrail 记录权限申请、审批、回收。", 0.88D);
        SuperAgentKgEvidence approvalEvidence = evidence(3001L, 10L, 20L, 4001L, 5001L, 2001L, null, "L4 权限申请需信息安全部三级审批。", 8);
        SuperAgentKgEvidence recordEvidence = evidence(3002L, 10L, 20L, 4002L, 5002L, 2002L, null, "AuditTrail 需记录权限申请、审批、回收和延长。", 9);
        AtomicInteger advisorCallCount = new AtomicInteger();
        GraphRagQueryPlanAdvisor advisor = (question, catalog) -> {
            advisorCallCount.incrementAndGet();
            if (question.contains("L4")) {
                return Optional.of(GraphRagQueryPlanAdvice.builder()
                    .graphQuery(true)
                    .answerTypeKeywords(List.of("ORG"))
                    .entitiesFromQuery(List.of("L4 数据"))
                    .entityNames(List.of("L4 数据"))
                    .relationTypes(List.of("APPROVES"))
                    .relationQuestion(true)
                    .maxHops(1)
                    .confidence(0.9D)
                    .reason("问题询问 L4 数据审批责任方，Java 只接受 KG 中真实存在的 APPROVES")
                    .build());
            }
            return Optional.of(GraphRagQueryPlanAdvice.builder()
                .graphQuery(true)
                .answerTypeKeywords(List.of("PROCESS"))
                .entitiesFromQuery(List.of("AuditTrail"))
                .entityNames(List.of("AuditTrail"))
                .relationTypes(List.of("RECORDS"))
                .relationQuestion(true)
                .maxHops(1)
                .confidence(0.9D)
                .reason("问题询问 AuditTrail 记录行为，Java 只接受 KG 中真实存在的 RECORDS")
                .build());
        };

        GraphRagSearchServiceImpl service = new GraphRagSearchServiceImpl(
            mapper(SuperAgentKgEntityMapper.class, List.of(l4Data, securityDept, auditTrail, permissionApply), null),
            mapper(SuperAgentKgRelationMapper.class, List.of(approves, records), null),
            mapper(SuperAgentKgEvidenceMapper.class, List.of(approvalEvidence, recordEvidence), null),
            mapper(SuperAgentKgCommunityMapper.class, List.<SuperAgentKgCommunity>of(), null),
            new ObjectMapper(),
            advisor
        );

        List<GraphRagSearchResult> approvalResults = service.search(
            "L4 数据需要谁审批？",
            List.of(10L),
            List.of(20L),
            5,
            2
        );
        List<GraphRagSearchResult> recordResults = service.search(
            "AuditTrail 记录哪些权限相关行为？",
            List.of(10L),
            List.of(20L),
            5,
            2
        );

        assertThat(advisorCallCount).hasValue(2);
        assertThat(approvalResults).isNotEmpty();
        assertThat(approvalResults.get(0).getRelationType()).isEqualTo("APPROVES");
        assertThat(approvalResults.get(0).getGraphPath()).contains("关系匹配");
        assertThat(approvalResults.get(0).getQueryPlanSource()).contains("llm.controlled.query_plan.v1");
        assertThat(approvalResults.get(0).getQueryPlanAnswerTypes()).contains("ORG");
        assertThat(approvalResults.get(0).getQueryPlanEntities()).contains("l4数据");
        assertThat(recordResults).isNotEmpty();
        assertThat(recordResults.get(0).getRelationType()).isEqualTo("RECORDS");
        assertThat(recordResults.get(0).getGraphPath()).contains("关系匹配");
    }

    @Test
    void nHopExpansionUsesKgEdgesAndKeepsSeedPathMetadata() {
        SuperAgentKgEntity auditTrail = entity(1001L, 10L, 20L, "AuditTrail", "审计系统", "SYSTEM", "审计系统。");
        SuperAgentKgEntity permissionApply = entity(1002L, 10L, 20L, "权限申请", null, "PROCESS", "权限申请。");
        SuperAgentKgEntity securityDept = entity(1003L, 10L, 20L, "信息安全部", null, "ORG", "负责权限审批复核。");
        SuperAgentKgRelation records = relation(2001L, 10L, 20L, 1001L, 1002L, "RECORDS",
            "AuditTrail 记录权限申请。", 0.9D);
        SuperAgentKgRelation approves = relation(2002L, 10L, 20L, 1002L, 1003L, "APPROVES",
            "权限申请由信息安全部审批。", 0.9D);
        SuperAgentKgEvidence firstHopEvidence = evidence(3001L, 10L, 20L, 4001L, 5001L, 2001L, null,
            "AuditTrail 需记录权限申请。", 4);
        SuperAgentKgEvidence secondHopEvidence = evidence(3002L, 10L, 20L, 4002L, 5002L, 2002L, null,
            "权限申请需要信息安全部审批。", 5);

        GraphRagQueryPlanAdvisor advisor = (question, catalog) -> Optional.of(GraphRagQueryPlanAdvice.builder()
            .graphQuery(true)
            .answerTypeKeywords(List.of("ORG"))
            .entitiesFromQuery(List.of("审计系统"))
            .entityNames(List.of("AuditTrail"))
            .relationQuestion(true)
            .maxHops(2)
            .confidence(0.9D)
            .reason("从审计系统出发扩展到权限申请和审批部门")
            .build());

        GraphRagSearchServiceImpl service = new GraphRagSearchServiceImpl(
            mapper(SuperAgentKgEntityMapper.class, List.of(auditTrail, permissionApply, securityDept), null),
            mapper(SuperAgentKgRelationMapper.class, List.of(records, approves), null),
            mapper(SuperAgentKgEvidenceMapper.class, List.of(firstHopEvidence, secondHopEvidence), null),
            mapper(SuperAgentKgCommunityMapper.class, List.<SuperAgentKgCommunity>of(), null),
            new ObjectMapper(),
            advisor
        );

        List<GraphRagSearchResult> results = service.search(
            "审计系统相关的权限审批部门是谁？",
            List.of(10L),
            List.of(20L),
            5,
            2
        );

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getRelationId()).isEqualTo(2002L);
        assertThat(results.get(0).getRelationType()).isEqualTo("APPROVES");
        GraphRagSearchResult secondHop = results.stream()
            .filter(item -> Long.valueOf(2002L).equals(item.getRelationId()))
            .findFirst()
            .orElseThrow();
        assertThat(secondHop.getHopCount()).isEqualTo(2);
        assertThat(secondHop.getGraphPath()).contains("二跳：AuditTrail --RECORDS--> 权限申请 --APPROVES--> 信息安全部");
        assertThat(secondHop.getNHopSeedEntityId()).isEqualTo(1001L);
        assertThat(secondHop.getNHopSeedEntityName()).isEqualTo("AuditTrail");
        assertThat(secondHop.getNHopPath()).contains("AuditTrail --RECORDS--> 权限申请 --APPROVES--> 信息安全部");
        assertThat(secondHop.getQueryPlanSource()).contains("llm.controlled.query_plan.v1");
        assertThat(secondHop.getQueryPlanAnswerTypes()).contains("ORG");
        assertThat(secondHop.getQueryPlanEntities()).contains("审计系统");
    }

    @Test
    void queryPlanAnswerTypeAndRelationGroupQualityPromoteGroundedSecondHopOverWeakFirstHop() {
        SuperAgentKgEntity auditTrail = entity(4101L, 41L, 51L, "AuditTrail", "审计系统", "SYSTEM", "审计系统。");
        SuperAgentKgEntity permissionApply = entity(4102L, 41L, 51L, "权限申请", null, "PROCESS", "权限申请。");
        SuperAgentKgEntity securityDept = entity(4103L, 41L, 51L, "信息安全部", null, "ORG", "权限审批部门。");
        SuperAgentKgEntity auditLog = entity(4104L, 41L, 51L, "审计日志", null, "PROCESS", "审计日志。");
        SuperAgentKgRelation weakFirstHop = relation(5101L, 41L, 51L, 4101L, 4104L, "RECORDS",
            "AuditTrail 记录审计日志。", 0.96D);
        SuperAgentKgRelation firstHop = relation(5102L, 41L, 51L, 4101L, 4102L, "RECORDS",
            "AuditTrail 记录权限申请。", 0.80D);
        SuperAgentKgRelation secondHop = relation(5103L, 41L, 51L, 4102L, 4103L, "APPROVES",
            "权限申请由信息安全部审批。", 0.78D);
        SuperAgentKgEvidence weakEvidence = evidence(6101L, 41L, 51L, 7101L, 8101L, 5101L, null,
            "AuditTrail 会记录审计日志流水。", 2);
        SuperAgentKgEvidence firstHopEvidence = evidence(6102L, 41L, 51L, 7102L, 8102L, 5102L, null,
            "AuditTrail 需记录权限申请。", 3);
        SuperAgentKgEvidence secondHopEvidence = evidence(6103L, 41L, 51L, 7103L, 8103L, 5103L, null,
            "权限申请需要信息安全部审批。", 4);

        GraphRagQueryPlanAdvisor advisor = (question, catalog) -> Optional.of(GraphRagQueryPlanAdvice.builder()
            .graphQuery(true)
            .answerTypeKeywords(List.of("ORG"))
            .entitiesFromQuery(List.of("审计系统"))
            .entityNames(List.of("AuditTrail"))
            .relationTypes(List.of("APPROVES"))
            .relationQuestion(true)
            .maxHops(2)
            .confidence(0.91D)
            .reason("从审计系统出发，寻找审批主体")
            .build());

        GraphRagCrossDocumentIndex.CanonicalEntityGroup auditGroup = canonicalGroup(
            "SYSTEM:audittrail",
            "AuditTrail",
            "SYSTEM",
            List.of(4101L),
            List.of(41L),
            0.84D
        );
        GraphRagCrossDocumentIndex.CanonicalEntityGroup processGroup = canonicalGroup(
            "PROCESS:权限申请",
            "权限申请",
            "PROCESS",
            List.of(4102L),
            List.of(41L),
            0.82D
        );
        GraphRagCrossDocumentIndex.CanonicalEntityGroup orgGroup = canonicalGroup(
            "ORG:信息安全部",
            "信息安全部",
            "ORG",
            List.of(4103L),
            List.of(41L),
            0.90D
        );
        GraphRagCrossDocumentIndex.CanonicalEntityGroup logGroup = canonicalGroup(
            "PROCESS:审计日志",
            "审计日志",
            "PROCESS",
            List.of(4104L),
            List.of(41L),
            0.50D
        );
        GraphRagCrossDocumentIndex.RelationGroup weakGroup = relationGroup(
            "SYSTEM:audittrail->RECORDS->PROCESS:审计日志",
            auditGroup.key(),
            logGroup.key(),
            "RECORDS",
            List.of(5101L),
            List.of(6101L),
            List.of(41L),
            0.45D,
            List.of("groundedEvidence"),
            List.of("weakRelation")
        );
        GraphRagCrossDocumentIndex.RelationGroup approveGroup = relationGroup(
            "PROCESS:权限申请->APPROVES->ORG:信息安全部",
            processGroup.key(),
            orgGroup.key(),
            "APPROVES",
            List.of(5103L, 5104L),
            List.of(6103L, 6104L),
            List.of(41L, 42L),
            0.92D,
            List.of("groundedEvidence", "crossDocument", "memberQuality"),
            List.of()
        );
        GraphRagCrossDocumentIndex persistedIndex = new GraphRagCrossDocumentIndex(
            Map.of(
                4101L, auditGroup,
                4102L, processGroup,
                4103L, orgGroup,
                4104L, logGroup
            ),
            Map.of(
                5101L, weakGroup,
                5103L, approveGroup
            )
        );
        GraphRagCrossDocumentIndexService indexService = new GraphRagCrossDocumentIndexService() {

            @Override
            public List<GraphRagCrossDocumentIndexBuildResult> rebuildAll() {
                return List.of();
            }

            @Override
            public GraphRagCrossDocumentIndex loadIndex(List<Long> documentIds, List<Long> taskIds) {
                return persistedIndex;
            }
        };

        GraphRagSearchServiceImpl service = new GraphRagSearchServiceImpl(
            mapper(SuperAgentKgEntityMapper.class, List.of(auditTrail, permissionApply, securityDept, auditLog), null),
            mapper(SuperAgentKgRelationMapper.class, List.of(weakFirstHop, firstHop, secondHop), null),
            mapper(SuperAgentKgEvidenceMapper.class, List.of(weakEvidence, firstHopEvidence, secondHopEvidence), null),
            mapper(SuperAgentKgCommunityMapper.class, List.<SuperAgentKgCommunity>of(), null),
            new ObjectMapper(),
            advisor,
            indexService,
            new GraphRagCrossDocumentIndexSupport(new ObjectMapper())
        );

        List<GraphRagSearchResult> results = service.search(
            "审计系统相关的权限审批部门是谁？",
            List.of(41L, 42L),
            List.of(51L, 52L),
            5,
            2
        );

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getRelationId()).isEqualTo(5103L);
        assertThat(results.get(0).getRelationType()).isEqualTo("APPROVES");
        assertThat(results.get(0).getHopCount()).isEqualTo(2);
        assertThat(results.get(0).getRelationGroupKey()).isEqualTo("PROCESS:权限申请->APPROVES->ORG:信息安全部");
        assertThat(results.get(0).getKgQualityScore()).isEqualTo(0.92D);
        assertThat(results.get(0).getQueryPlanAnswerTypes()).contains("ORG");
        assertThat(results.get(0).getQueryPlanEntities()).contains("审计系统");
        assertThat(results)
            .filteredOn(item -> Long.valueOf(5101L).equals(item.getRelationId()))
            .first()
            .satisfies(item -> assertThat(item.getScore()).isLessThan(results.get(0).getScore()));
    }

    @Test
    void crossDocumentCanonicalEntityExpandsFrontierSeeds() {
        SuperAgentKgEntity auditTrailInPolicy = entity(1001L, 10L, 20L, "AuditTrail", null, "SYSTEM", "审计记录系统。");
        SuperAgentKgEntity permissionApply = entity(1002L, 10L, 20L, "权限申请", null, "PROCESS", "权限申请、审批、回收。");
        SuperAgentKgEntity auditSystemInManual = entity(1101L, 11L, 21L, "审计系统", "AuditTrail", "SYSTEM", "用户手册里的审计系统。");
        SuperAgentKgEntity permissionApplyInManual = entity(1102L, 11L, 21L, "权限申请", null, "PROCESS", "权限申请别名说明。");
        SuperAgentKgRelation records = relation(2001L, 10L, 20L, 1001L, 1002L, "RECORDS", "AuditTrail 记录权限申请、审批、回收。", 0.9D);
        SuperAgentKgRelation aliasRecords = relation(2101L, 11L, 21L, 1101L, 1102L, "RECORDS", "审计系统也叫 AuditTrail，记录权限申请。", 0.7D);
        SuperAgentKgEvidence evidence = evidence(3001L, 10L, 20L, 4001L, 5001L, 2001L, null,
            "AuditTrail 需记录权限申请、审批、回收和延长。", 9);
        SuperAgentKgEvidence aliasEvidence = evidence(3101L, 11L, 21L, 4101L, 5101L, 2101L, null,
            "审计系统也称 AuditTrail，用于统一记录权限申请。", 3);

        GraphRagSearchServiceImpl service = new GraphRagSearchServiceImpl(
            mapper(SuperAgentKgEntityMapper.class, List.of(auditTrailInPolicy, permissionApply, auditSystemInManual, permissionApplyInManual), null),
            mapper(SuperAgentKgRelationMapper.class, List.of(records, aliasRecords), null),
            mapper(SuperAgentKgEvidenceMapper.class, List.of(evidence, aliasEvidence), null),
            mapper(SuperAgentKgCommunityMapper.class, List.<SuperAgentKgCommunity>of(), null),
            new ObjectMapper()
        );

        List<GraphRagSearchResult> results = service.search(
            "审计系统 有哪些要求？",
            List.of(10L, 11L),
            List.of(20L, 21L),
            5,
            2
        );

        assertThat(results).isNotEmpty();
        GraphRagSearchResult result = results.stream()
            .filter(item -> Long.valueOf(10L).equals(item.getDocumentId()))
            .findFirst()
            .orElseThrow();
        assertThat(result.getRelationType()).isEqualTo("RECORDS");
        assertThat(result.getCanonicalEntityName()).isIn("AuditTrail", "审计系统");
        assertThat(result.getCanonicalEntityCount()).isEqualTo(2);
        assertThat(result.getCanonicalDocumentCount()).isEqualTo(2);
        assertThat(result.getRelationGroupKey()).contains("RECORDS");
        assertThat(result.getRelationGroupRelationCount()).isEqualTo(2);
        assertThat(result.getRelationGroupEvidenceCount()).isEqualTo(2);
        assertThat(result.getRelationGroupDocumentCount()).isEqualTo(2);
        assertThat(result.getScore()).isGreaterThan(2.25D);
        assertThat(results.get(0).getRelationGroupDocumentCount()).isEqualTo(2);
    }

    @Test
    void crossDocumentCanonicalPrefersDistinctiveAliasAnchorOverLowDistinctivenessSeed() {
        SuperAgentKgEntity auditTrailInPolicy = entity(1201L, 12L, 22L, "AuditTrail", null, "CONCEPT", "审计记录系统。");
        SuperAgentKgEntity permissionApplyInPolicy = entity(1202L, 12L, 22L, "权限申请", null, "PROCESS", "权限申请、审批、回收。");
        SuperAgentKgEntity scope = entity(1203L, 12L, 22L, "适用范围", null, "CONCEPT", "制度适用范围。");
        SuperAgentKgEntity auditWord = entity(1204L, 12L, 22L, "审计", null, "CONCEPT", "审计泛词。");
        SuperAgentKgEntity titleLabel = entity(1205L, 12L, 22L, "TITLE", null, "CONCEPT", "contentWithWeight 技术标签。");
        SuperAgentKgEntity auditSystemInManual = entity(1301L, 13L, 23L, "审计系统", "AuditTrail", "SYSTEM", "业务别名称呼。");
        SuperAgentKgEntity permissionInManual = entity(1302L, 13L, 23L, "权限", null, "CONCEPT", "权限词说明。");
        SuperAgentKgEntity embeddedQuestion = entity(1303L, 13L, 23L, "审计系统有哪些权", null, "CONCEPT", "样例文档中的手动验证问题片段。");
        embeddedQuestion.setMetadataJson("{\"candidateSources\":[\"metadata.question\"],\"confidence\":0.4}");
        SuperAgentKgRelation records = relation(2201L, 12L, 22L, 1201L, 1202L, "RECORDS",
            "AuditTrail 记录权限申请、审批和回收。", 0.9D);
        SuperAgentKgRelation revokes = relation(2202L, 12L, 22L, 1302L, 1203L, "REVOKES",
            "权限回收适用于制度范围。", 0.8D);
        SuperAgentKgRelation badTitleRecords = relation(2203L, 12L, 22L, 1204L, 1205L, "RECORDS",
            "审计记录要求被错误抽成 TITLE 技术标签。", 1.0D);
        SuperAgentKgEvidence recordEvidence = evidence(3201L, 12L, 22L, 4201L, 5201L, 2201L, null,
            "AuditTrail 需记录权限申请、审批、回收和临时权限延长。", 9);
        SuperAgentKgEvidence revokeEvidence = evidence(3202L, 12L, 22L, 4202L, 5202L, 2202L, null,
            "权限回收要求适用于客户数据权限流转场景。", 10);
        SuperAgentKgEvidence badTitleEvidence = evidence(3203L, 12L, 22L, 4203L, 5203L, 2203L, null,
            "审计记录要求包含 TITLE 技术标签。", 11);
        SuperAgentKgEvidence questionEvidence = evidence(3301L, 13L, 23L, 4301L, 5301L, null, 1303L,
            "用于手动验证时，可以提问：审计系统有哪些权限相关要求？", 3);

        GraphRagSearchServiceImpl service = new GraphRagSearchServiceImpl(
            mapper(SuperAgentKgEntityMapper.class,
                List.of(
                    auditTrailInPolicy,
                    permissionApplyInPolicy,
                    scope,
                    auditWord,
                    titleLabel,
                    auditSystemInManual,
                    permissionInManual,
                    embeddedQuestion
                ),
                null),
            mapper(SuperAgentKgRelationMapper.class, List.of(records, revokes, badTitleRecords), null),
            mapper(SuperAgentKgEvidenceMapper.class, List.of(recordEvidence, revokeEvidence, badTitleEvidence, questionEvidence), null),
            mapper(SuperAgentKgCommunityMapper.class, List.<SuperAgentKgCommunity>of(), null),
            new ObjectMapper()
        );

        List<GraphRagSearchResult> results = service.search(
            "审计系统有哪些权限相关要求？",
            List.of(12L, 13L),
            List.of(22L, 23L),
            5,
            2
        );

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getRelationId()).isEqualTo(2201L);
        GraphRagSearchResult result = results.get(0);
        assertThat(result.getEntityName()).isEqualTo("AuditTrail");
        assertThat(result.getCanonicalEntityName()).isIn("AuditTrail", "审计系统");
        assertThat(result.getCanonicalEntityCount()).isEqualTo(2);
        assertThat(result.getCanonicalDocumentCount()).isEqualTo(2);
        assertThat(results)
            .extracting(GraphRagSearchResult::getRelationId)
            .doesNotContain(2203L);
        assertThat(results)
            .filteredOn(item -> "权限".equals(item.getCanonicalEntityName()))
            .isEmpty();
        assertThat(results)
            .allSatisfy(item -> {
                assertThat(item.getGraphPath()).doesNotContain("TITLE");
                assertThat(item.getEntityName()).doesNotContain("哪些");
            });
    }

    @Test
    void crossDocumentAuditPermissionQuestionSurvivesLiveDataNoiseShape() {
        SuperAgentKgEntity auditTrailInPolicy = entity(1401L, 14L, 24L, "AuditTrail", null, "CONCEPT", "审计记录系统。");
        SuperAgentKgEntity permissionApply = entity(1402L, 14L, 24L, "权限申请", null, "CONCEPT", "权限申请。");
        SuperAgentKgEntity permissionApprove = entity(1403L, 14L, 24L, "权限审批", null, "CONCEPT", "权限审批。");
        SuperAgentKgEntity permissionRevoke = entity(1404L, 14L, 24L, "权限回收", null, "CONCEPT", "权限回收。");
        SuperAgentKgEntity temporaryExtend = entity(1405L, 14L, 24L, "临时权限延长", null, "CONCEPT", "临时权限延长。");
        SuperAgentKgEntity titleLabel = entity(1406L, 14L, 24L, "type: TITLE", null, "CONCEPT", "weighted content 技术标签。");
        SuperAgentKgEntity genericContent = entity(1407L, 14L, 24L, "核心内容是", null, "CONCEPT", "自动问题派生片段。");
        SuperAgentKgEntity partialPermission = entity(1408L, 14L, 24L, "以下权限相", null, "CONCEPT", "截断后的自动关键词。");
        genericContent.setMetadataJson("{\"candidateSources\":[\"metadata.question\"],\"confidence\":0.4}");
        partialPermission.setMetadataJson("{\"candidateSources\":[\"metadata.keyword\"],\"confidence\":0.4}");
        SuperAgentKgEntity auditTrailInAliasDoc = entity(1501L, 15L, 25L, "AuditTrail", null, "CONCEPT", "业务人员口中的审计系统。");
        auditTrailInAliasDoc.setMetadataJson(
            "{\"aliases\":[\"TEXT 审计系统\",\"业务人员在沟通中\",\"审计系统\"],\"confidence\":0.92}"
        );
        SuperAgentKgEntity textAuditSystem = entity(1502L, 15L, 25L, "TEXT 审计系统", null, "SYSTEM", "weighted content 派生标签。");
        textAuditSystem.setMetadataJson("{\"aliases\":[\"AuditTrail\",\"审计系统\"],\"confidence\":0.8}");

        SuperAgentKgRelation recordsApply = relation(2401L, 14L, 24L, 1401L, 1402L, "RECORDS",
            "AuditTrail 记录权限申请。", 0.83D);
        SuperAgentKgRelation recordsApprove = relation(2402L, 14L, 24L, 1401L, 1403L, "RECORDS",
            "AuditTrail 记录权限审批。", 0.83D);
        SuperAgentKgRelation recordsRevoke = relation(2403L, 14L, 24L, 1401L, 1404L, "RECORDS",
            "AuditTrail 记录权限回收。", 0.83D);
        SuperAgentKgRelation recordsExtend = relation(2404L, 14L, 24L, 1401L, 1405L, "RECORDS",
            "AuditTrail 记录临时权限延长。", 0.83D);
        SuperAgentKgRelation badTitle = relation(2405L, 14L, 24L, 1401L, 1406L, "RECORDS",
            "AuditTrail 被错误连到技术标题。", 0.83D);
        SuperAgentKgRelation badContent = relation(2406L, 14L, 24L, 1407L, 1408L, "RECORDS",
            "自动问题派生噪声。", 0.83D);
        SuperAgentKgRelation badCoreContent = relation(2407L, 14L, 24L, 1401L, 1407L, "RECORDS",
            "AuditTrail 被错误连到自动问题核心内容片段。", 0.83D);

        String permissionQuote = "section: `AuditTrail` 需记录以下权限相关行为：type: TEXT - 权限申请。 - 权限审批。 - 权限回收。 - 临时权限延长。";
        List<SuperAgentKgEvidence> evidences = List.of(
            evidence(3401L, 14L, 24L, 4401L, 5401L, 2405L, null,
                "[TITLE] `AuditTrail` 需记录以下权限相关行为：", 3),
            evidence(3402L, 14L, 24L, 4402L, 5401L, 2401L, null, permissionQuote, 4),
            evidence(3403L, 14L, 24L, 4402L, 5401L, 2402L, null, permissionQuote, 4),
            evidence(3404L, 14L, 24L, 4402L, 5401L, 2403L, null, permissionQuote, 4),
            evidence(3405L, 14L, 24L, 4402L, 5401L, 2404L, null, permissionQuote, 4),
            evidence(3406L, 14L, 24L, 4402L, 5401L, 2406L, null,
                "[QUESTIONS] 关于`AuditTrail` 需记录以下权限相关行为：的核心内容是什么？", 4),
            evidence(3407L, 14L, 24L, 4401L, 5401L, 2407L, null,
                "[TITLE] `AuditTrail` 需记录以下权限相关行为： [QUESTIONS] 关于核心内容是什么？", 3),
            evidence(3501L, 15L, 25L, 4501L, 5501L, null, 1501L,
                "业务人员在沟通中把 AuditTrail 称为审计系统。", 2),
            evidence(3502L, 15L, 25L, 4501L, 5501L, null, 1502L,
                "[CONTENT] section: 系统名称 type: TEXT 审计系统也称 AuditTrail。", 2)
        );

        GraphRagSearchServiceImpl service = new GraphRagSearchServiceImpl(
            mapper(SuperAgentKgEntityMapper.class,
                List.of(
                    auditTrailInPolicy,
                    permissionApply,
                    permissionApprove,
                    permissionRevoke,
                    temporaryExtend,
                    titleLabel,
                    genericContent,
                    partialPermission,
                    auditTrailInAliasDoc,
                    textAuditSystem
                ),
                null),
            mapper(SuperAgentKgRelationMapper.class,
                List.of(recordsApply, recordsApprove, recordsRevoke, recordsExtend, badTitle, badContent, badCoreContent),
                null),
            mapper(SuperAgentKgEvidenceMapper.class, evidences, null),
            mapper(SuperAgentKgCommunityMapper.class, List.<SuperAgentKgCommunity>of(), null),
            new ObjectMapper()
        );

        List<GraphRagSearchResult> results = service.search(
            "审计系统有哪些权限相关要求？",
            List.of(14L, 15L),
            List.of(24L, 25L),
            8,
            2
        );

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getRelationType()).isEqualTo("RECORDS");
        assertThat(results.get(0).getEntityName()).isEqualTo("AuditTrail");
        assertThat(results.get(0).getRelatedEntityName())
            .isIn("权限申请", "权限审批", "权限回收", "临时权限延长");
        assertThat(results.get(0).getCanonicalEntityName()).isEqualTo("AuditTrail");
        assertThat(results.get(0).getCanonicalEntityCount()).isEqualTo(2);
        assertThat(results.get(0).getCanonicalDocumentCount()).isEqualTo(2);
        assertThat(results)
            .extracting(GraphRagSearchResult::getRelationId)
            .doesNotContain(2405L, 2406L, 2407L);
        assertThat(results)
            .allSatisfy(result -> {
                assertThat(result.getGraphPath()).doesNotContain("TITLE");
                assertThat(result.getGraphPath()).doesNotContain("核心内容是");
                assertThat(result.getEntityName()).doesNotContain("TEXT");
            });
    }

    @Test
    void persistedCrossDocumentIndexExpandsFrontierAndRelationGroup() {
        SuperAgentKgEntity aliasEntity = entity(1601L, 16L, 26L, "审计系统", "AuditTrail", "SYSTEM", "审计系统别名说明。");
        SuperAgentKgEntity canonicalEntity = entity(1701L, 17L, 27L, "AuditTrail", null, "SYSTEM", "审计记录系统。");
        SuperAgentKgEntity permissionApply = entity(1702L, 17L, 27L, "权限申请", null, "PROCESS", "权限申请。");
        SuperAgentKgRelation relation = relation(2701L, 17L, 27L, 1701L, 1702L, "RECORDS",
            "AuditTrail 记录权限申请。", 0.86D);
        SuperAgentKgEvidence evidence = evidence(3701L, 17L, 27L, 4701L, 5701L, 2701L, null,
            "AuditTrail 记录权限申请、审批和权限回收。", 6);

        GraphRagCrossDocumentIndex.CanonicalEntityGroup canonicalGroup = new GraphRagCrossDocumentIndex.CanonicalEntityGroup(
            "SYSTEM:audittrail",
            "AuditTrail",
            "SYSTEM",
            new LinkedHashSet<>(List.of(1601L, 1701L)),
            new LinkedHashSet<>(List.of(16L, 17L)),
            new LinkedHashSet<>(List.of(26L, 27L)),
            List.of("SYSTEM:audittrail", "SYSTEM:审计系统", "NAME:audittrail"),
            0.75D,
            new GraphRagCrossDocumentIndex.QualityProfile(0.82D, List.of("crossDocument"), List.of()),
            new GraphRagCrossDocumentIndex.RankProfile(0.19D, 0.91D, 1, 4, 2, 2, 3.4D)
        );
        GraphRagCrossDocumentIndex.RelationGroup relationGroup = new GraphRagCrossDocumentIndex.RelationGroup(
            "SYSTEM:audittrail->RECORDS->PROCESS:权限申请",
            "SYSTEM:audittrail",
            "PROCESS:权限申请",
            "RECORDS",
            new LinkedHashSet<>(List.of(2701L, 2702L)),
            new LinkedHashSet<>(List.of(3701L, 3702L)),
            new LinkedHashSet<>(List.of(17L, 18L)),
            Map.of(2701L, 1, 2702L, 1),
            0.75D,
            new GraphRagCrossDocumentIndex.QualityProfile(0.88D, List.of("groundedEvidence", "crossDocument"), List.of()),
            new GraphRagCrossDocumentIndex.RankProfile(0.17D, 0.86D, 2, 3, 1, 2, 2.8D)
        );
        GraphRagCrossDocumentIndex.CrossDocumentCommunity community = new GraphRagCrossDocumentIndex.CrossDocumentCommunity(
            8801L,
            "xdoc-community:audittrail-permission",
            "审计系统权限记录社区",
            "审计系统 AuditTrail 记录权限申请、审批和权限回收，覆盖 2 份文档中的权限记录证据。",
            new LinkedHashSet<>(List.of("SYSTEM:audittrail", "PROCESS:权限申请")),
            new LinkedHashSet<>(List.of(relationGroup.key())),
            new LinkedHashSet<>(List.of(3701L, 3702L)),
            new LinkedHashSet<>(List.of(17L, 18L)),
            0.78D,
            new GraphRagCrossDocumentIndex.QualityProfile(0.88D, List.of("groundedEvidence", "crossDocument"), List.of()),
            new GraphRagCrossDocumentIndex.RankProfile(0.17D, 0.86D, 2, 3, 1, 2, 2.8D)
        );
        GraphRagCrossDocumentIndex persistedIndex = new GraphRagCrossDocumentIndex(
            Map.of(1601L, canonicalGroup, 1701L, canonicalGroup),
            Map.of(2701L, relationGroup),
            Map.of(community.key(), community),
            Map.of(relationGroup.key(), community)
        );
        AtomicInteger indexLoadCount = new AtomicInteger();
        GraphRagCrossDocumentIndexService indexService = new GraphRagCrossDocumentIndexService() {

            @Override
            public List<GraphRagCrossDocumentIndexBuildResult> rebuildAll() {
                return List.of();
            }

            @Override
            public GraphRagCrossDocumentIndex loadIndex(List<Long> documentIds, List<Long> taskIds) {
                indexLoadCount.incrementAndGet();
                return persistedIndex;
            }
        };

        AtomicInteger relationSelectCount = new AtomicInteger();
        SuperAgentKgRelationMapper relationMapper = (SuperAgentKgRelationMapper) Proxy.newProxyInstance(
            SuperAgentKgRelationMapper.class.getClassLoader(),
            new Class<?>[]{SuperAgentKgRelationMapper.class},
            (proxy, method, args) -> {
                if ("selectList".equals(method.getName())) {
                    relationSelectCount.incrementAndGet();
                    if (relationSelectCount.get() == 1) {
                        return List.of(relation);
                    }
                    return List.of();
                }
                if ("toString".equals(method.getName())) {
                    return "SuperAgentKgRelationMapperProxy";
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

        GraphRagSearchServiceImpl service = new GraphRagSearchServiceImpl(
            mapper(SuperAgentKgEntityMapper.class, List.of(aliasEntity, canonicalEntity, permissionApply), null),
            relationMapper,
            mapper(SuperAgentKgEvidenceMapper.class, List.of(evidence), null),
            mapper(SuperAgentKgCommunityMapper.class, List.<SuperAgentKgCommunity>of(), null),
            new ObjectMapper(),
            null,
            indexService,
            new GraphRagCrossDocumentIndexSupport(new ObjectMapper())
        );

        List<GraphRagSearchResult> results = service.search(
            "审计系统有哪些权限要求？",
            List.of(16L, 17L),
            List.of(26L, 27L),
            3,
            1
        );

        assertThat(indexLoadCount).hasValue(1);
        assertThat(results).isNotEmpty();
        GraphRagSearchResult result = results.stream()
            .filter(item -> Long.valueOf(2701L).equals(item.getRelationId()))
            .findFirst()
            .orElseThrow();
        assertThat(result.getRelationId()).isEqualTo(2701L);
        assertThat(result.getEntityName()).isEqualTo("AuditTrail");
        assertThat(result.getCanonicalEntityName()).isEqualTo("AuditTrail");
        assertThat(result.getCanonicalEntityCount()).isEqualTo(2);
        assertThat(result.getCanonicalDocumentCount()).isEqualTo(2);
        assertThat(result.getRelationGroupKey()).isEqualTo("SYSTEM:audittrail->RECORDS->PROCESS:权限申请");
        assertThat(result.getRelationGroupRelationCount()).isEqualTo(2);
        assertThat(result.getRelationGroupEvidenceCount()).isEqualTo(2);
        assertThat(result.getRelationGroupDocumentCount()).isEqualTo(2);
        assertThat(result.getCrossDocumentCommunityKey()).isEqualTo("xdoc-community:audittrail-permission");
        assertThat(result.getCrossDocumentCommunityEntityCount()).isEqualTo(2);
        assertThat(result.getCrossDocumentCommunityRelationGroupCount()).isEqualTo(1);
        assertThat(result.getCrossDocumentCommunityEvidenceCount()).isEqualTo(2);
        assertThat(result.getCrossDocumentCommunityDocumentCount()).isEqualTo(2);
        assertThat(result.getCommunityTitle()).isEqualTo("审计系统权限记录社区");
        assertThat(result.getKgQualityScore()).isEqualTo(0.88D);
        assertThat(result.getKgQualityReasons()).contains("groundedEvidence");
        assertThat(result.getKgPagerank()).isEqualTo(0.17D);
        assertThat(result.getKgRankPosition()).isEqualTo(2);
        assertThat(result.getKgDegree()).isEqualTo(3);
        assertThat(results)
            .filteredOn(item -> "xdoc-community:audittrail-permission".equals(item.getCrossDocumentCommunityKey()))
            .isNotEmpty();
        assertThat(relationSelectCount).hasValue(1);
    }

    @Test
    void persistedRelationGroupMetadataIsExposedEvenForSingleRelationGroup() {
        SuperAgentKgEntity aliasEntity = entity(1801L, 18L, 28L, "审计系统", "AuditTrail", "SYSTEM", "审计系统别名说明。");
        SuperAgentKgEntity canonicalEntity = entity(1901L, 19L, 29L, "AuditTrail", null, "SYSTEM", "审计记录系统。");
        SuperAgentKgEntity temporaryPermissionExtend = entity(1902L, 19L, 29L, "临时权限延长", null, "PROCESS", "临时权限延长。");
        SuperAgentKgRelation relation = relation(2901L, 19L, 29L, 1901L, 1902L, "RECORDS",
            "AuditTrail 记录临时权限延长。", 0.86D);
        SuperAgentKgEvidence evidence = evidence(3901L, 19L, 29L, 4901L, 5901L, 2901L, null,
            "AuditTrail 需记录临时权限延长。", 6);

        GraphRagCrossDocumentIndex.CanonicalEntityGroup canonicalGroup = new GraphRagCrossDocumentIndex.CanonicalEntityGroup(
            "SYSTEM:audittrail",
            "AuditTrail",
            "SYSTEM",
            new LinkedHashSet<>(List.of(1801L, 1901L)),
            new LinkedHashSet<>(List.of(18L, 19L)),
            new LinkedHashSet<>(List.of(28L, 29L)),
            List.of("SYSTEM:audittrail", "SYSTEM:审计系统", "NAME:audittrail"),
            0.75D,
            new GraphRagCrossDocumentIndex.QualityProfile(0.82D, List.of("crossDocument"), List.of())
        );
        GraphRagCrossDocumentIndex.RelationGroup singleRelationGroup = new GraphRagCrossDocumentIndex.RelationGroup(
            "SYSTEM:audittrail->RECORDS->PROCESS:临时权限延长",
            "SYSTEM:audittrail",
            "PROCESS:临时权限延长",
            "RECORDS",
            new LinkedHashSet<>(List.of(2901L)),
            new LinkedHashSet<>(List.of(3901L)),
            new LinkedHashSet<>(List.of(19L)),
            Map.of(2901L, 1),
            0.75D,
            new GraphRagCrossDocumentIndex.QualityProfile(0.76D, List.of("groundedEvidence"), List.of())
        );
        GraphRagCrossDocumentIndex persistedIndex = new GraphRagCrossDocumentIndex(
            Map.of(1801L, canonicalGroup, 1901L, canonicalGroup),
            Map.of(2901L, singleRelationGroup)
        );
        GraphRagCrossDocumentIndexService indexService = new GraphRagCrossDocumentIndexService() {

            @Override
            public List<GraphRagCrossDocumentIndexBuildResult> rebuildAll() {
                return List.of();
            }

            @Override
            public GraphRagCrossDocumentIndex loadIndex(List<Long> documentIds, List<Long> taskIds) {
                return persistedIndex;
            }
        };

        GraphRagSearchServiceImpl service = new GraphRagSearchServiceImpl(
            mapper(SuperAgentKgEntityMapper.class, List.of(aliasEntity, canonicalEntity, temporaryPermissionExtend), null),
            mapper(SuperAgentKgRelationMapper.class, List.of(relation), null),
            mapper(SuperAgentKgEvidenceMapper.class, List.of(evidence), null),
            mapper(SuperAgentKgCommunityMapper.class, List.<SuperAgentKgCommunity>of(), null),
            new ObjectMapper(),
            null,
            indexService,
            new GraphRagCrossDocumentIndexSupport(new ObjectMapper())
        );

        List<GraphRagSearchResult> results = service.search(
            "审计系统记录哪些临时权限要求？",
            List.of(18L, 19L),
            List.of(28L, 29L),
            3,
            1
        );

        assertThat(results).isNotEmpty();
        GraphRagSearchResult result = results.get(0);
        assertThat(result.getRelationId()).isEqualTo(2901L);
        assertThat(result.getRelatedEntityName()).isEqualTo("临时权限延长");
        assertThat(result.getRelationGroupKey()).isEqualTo("SYSTEM:audittrail->RECORDS->PROCESS:临时权限延长");
        assertThat(result.getRelationGroupRelationCount()).isEqualTo(1);
        assertThat(result.getRelationGroupEvidenceCount()).isEqualTo(1);
        assertThat(result.getRelationGroupDocumentCount()).isEqualTo(1);
    }

    @Test
    void crossDocumentCommunitySelectsRepresentativeEvidenceByQueryAndRelationGroupQuality() {
        SuperAgentKgEntity auditSystem = entity(2001L, 20L, 30L, "审计系统", "AuditTrail", "SYSTEM", "审计系统。");
        SuperAgentKgEntity approvalDept = entity(2002L, 20L, 30L, "权限审批部门", null, "ORGANIZATION", "权限审批部门。");
        SuperAgentKgEntity unrelatedProcedure = entity(2003L, 20L, 30L, "发布窗口", null, "PROCESS", "发布窗口。");
        SuperAgentKgRelation lowQualityRelation = relation(3001L, 20L, 30L, 2001L, 2003L, "ASSOCIATED_WITH",
            "审计系统与发布窗口存在弱关联。", 0.55D);
        SuperAgentKgRelation highQualityRelation = relation(3002L, 20L, 30L, 2001L, 2002L, "RESPONSIBLE_FOR",
            "审计系统权限审批部门由制度明确。", 0.92D);
        SuperAgentKgEvidence lowQualityEvidence = evidence(4001L, 20L, 30L, 5001L, 6001L, 3001L, null,
            "审计系统会记录若干操作流水。", 4);
        SuperAgentKgEvidence highQualityEvidence = evidence(4002L, 20L, 30L, 5002L, 6002L, 3002L, null,
            "审计系统相关的权限审批部门需要在记录中明确留痕。", 5);

        GraphRagCrossDocumentIndex.CanonicalEntityGroup auditGroup = new GraphRagCrossDocumentIndex.CanonicalEntityGroup(
            "SYSTEM:audittrail",
            "审计系统",
            "SYSTEM",
            new LinkedHashSet<>(List.of(2001L)),
            new LinkedHashSet<>(List.of(20L, 21L)),
            new LinkedHashSet<>(List.of(30L, 31L)),
            List.of("SYSTEM:审计系统", "NAME:audittrail"),
            0.86D,
            new GraphRagCrossDocumentIndex.QualityProfile(0.86D, List.of("groundedEvidence", "crossDocument"), List.of()),
            new GraphRagCrossDocumentIndex.RankProfile(0.18D, 0.88D, 1, 4, 2, 2, 3.1D)
        );
        GraphRagCrossDocumentIndex.CanonicalEntityGroup approvalGroup = new GraphRagCrossDocumentIndex.CanonicalEntityGroup(
            "ORGANIZATION:权限审批部门",
            "权限审批部门",
            "ORGANIZATION",
            new LinkedHashSet<>(List.of(2002L)),
            new LinkedHashSet<>(List.of(20L, 21L)),
            new LinkedHashSet<>(List.of(30L, 31L)),
            List.of("ORGANIZATION:权限审批部门"),
            0.84D,
            new GraphRagCrossDocumentIndex.QualityProfile(0.84D, List.of("groundedEvidence", "crossDocument"), List.of()),
            new GraphRagCrossDocumentIndex.RankProfile(0.16D, 0.82D, 2, 3, 1, 2, 2.4D)
        );
        GraphRagCrossDocumentIndex.CanonicalEntityGroup procedureGroup = new GraphRagCrossDocumentIndex.CanonicalEntityGroup(
            "PROCESS:发布窗口",
            "发布窗口",
            "PROCESS",
            new LinkedHashSet<>(List.of(2003L)),
            new LinkedHashSet<>(List.of(20L)),
            new LinkedHashSet<>(List.of(30L)),
            List.of("PROCESS:发布窗口"),
            0.35D,
            new GraphRagCrossDocumentIndex.QualityProfile(0.35D, List.of(), List.of("weakRelation")),
            new GraphRagCrossDocumentIndex.RankProfile(0.03D, 0.12D, 9, 1, 1, 0, 0.4D)
        );
        GraphRagCrossDocumentIndex.RelationGroup lowQualityGroup = new GraphRagCrossDocumentIndex.RelationGroup(
            "SYSTEM:audittrail->ASSOCIATED_WITH->PROCESS:发布窗口",
            auditGroup.key(),
            procedureGroup.key(),
            "ASSOCIATED_WITH",
            new LinkedHashSet<>(List.of(3001L)),
            new LinkedHashSet<>(List.of(4001L)),
            new LinkedHashSet<>(List.of(20L)),
            Map.of(3001L, 1),
            0.30D,
            new GraphRagCrossDocumentIndex.QualityProfile(0.30D, List.of("groundedEvidence"), List.of("weakRelation")),
            new GraphRagCrossDocumentIndex.RankProfile(0.03D, 0.12D, 9, 1, 1, 0, 0.4D)
        );
        GraphRagCrossDocumentIndex.RelationGroup highQualityGroup = new GraphRagCrossDocumentIndex.RelationGroup(
            "SYSTEM:audittrail->RESPONSIBLE_FOR->ORGANIZATION:权限审批部门",
            auditGroup.key(),
            approvalGroup.key(),
            "RESPONSIBLE_FOR",
            new LinkedHashSet<>(List.of(3002L, 3003L)),
            new LinkedHashSet<>(List.of(4002L, 4003L)),
            new LinkedHashSet<>(List.of(20L, 21L)),
            Map.of(3002L, 1, 3003L, 1),
            0.90D,
            new GraphRagCrossDocumentIndex.QualityProfile(0.92D, List.of("groundedEvidence", "crossDocument", "memberQuality"), List.of()),
            new GraphRagCrossDocumentIndex.RankProfile(0.21D, 0.91D, 1, 4, 2, 2, 3.6D)
        );
        GraphRagCrossDocumentIndex.CrossDocumentCommunity community = new GraphRagCrossDocumentIndex.CrossDocumentCommunity(
            9901L,
            "xdoc-community:audittrail-approval",
            "审计系统权限审批社区",
            "覆盖审计系统、权限审批部门和相关跨文档证据。",
            new LinkedHashSet<>(List.of(auditGroup.key(), approvalGroup.key(), procedureGroup.key())),
            new LinkedHashSet<>(List.of(lowQualityGroup.key(), highQualityGroup.key())),
            new LinkedHashSet<>(List.of(4001L, 4002L)),
            new LinkedHashSet<>(List.of(20L, 21L)),
            0.91D,
            new GraphRagCrossDocumentIndex.QualityProfile(0.90D, List.of("groundedEvidence", "crossDocument"), List.of()),
            new GraphRagCrossDocumentIndex.RankProfile(0.19D, 0.90D, 1, 5, 2, 3, 4.1D)
        );
        GraphRagCrossDocumentIndex persistedIndex = new GraphRagCrossDocumentIndex(
            Map.of(2001L, auditGroup, 2002L, approvalGroup, 2003L, procedureGroup),
            Map.of(3001L, lowQualityGroup, 3002L, highQualityGroup),
            Map.of(community.key(), community),
            Map.of(lowQualityGroup.key(), community, highQualityGroup.key(), community)
        );
        GraphRagCrossDocumentIndexService indexService = new GraphRagCrossDocumentIndexService() {

            @Override
            public List<GraphRagCrossDocumentIndexBuildResult> rebuildAll() {
                return List.of();
            }

            @Override
            public GraphRagCrossDocumentIndex loadIndex(List<Long> documentIds, List<Long> taskIds) {
                return persistedIndex;
            }
        };

        GraphRagSearchServiceImpl service = new GraphRagSearchServiceImpl(
            mapper(SuperAgentKgEntityMapper.class, List.of(auditSystem, approvalDept, unrelatedProcedure), null),
            mapper(SuperAgentKgRelationMapper.class, List.of(lowQualityRelation, highQualityRelation), null),
            mapper(SuperAgentKgEvidenceMapper.class, List.of(lowQualityEvidence, highQualityEvidence), null),
            mapper(SuperAgentKgCommunityMapper.class, List.<SuperAgentKgCommunity>of(), null),
            new ObjectMapper(),
            null,
            indexService,
            new GraphRagCrossDocumentIndexSupport(new ObjectMapper())
        );

        List<GraphRagSearchResult> results = service.search(
            "审计系统 权限审批部门 社区",
            List.of(20L, 21L),
            List.of(30L, 31L),
            5,
            1
        );

        GraphRagSearchResult communityResult = results.stream()
            .filter(item -> "xdoc-community:audittrail-approval".equals(item.getCrossDocumentCommunityKey()))
            .filter(item -> item.getRelationId() == null)
            .findFirst()
            .orElseThrow();
        assertThat(communityResult.getEvidenceId()).isEqualTo(4002L);
        assertThat(communityResult.getQuoteText()).contains("权限审批部门");
        assertThat(communityResult.getRelationGroupKey())
            .isEqualTo("SYSTEM:audittrail->RESPONSIBLE_FOR->ORGANIZATION:权限审批部门");
        assertThat(communityResult.getRelationGroupDocumentCount()).isEqualTo(2);
        assertThat(communityResult.getRelationGroupEvidenceCount()).isEqualTo(2);
        assertThat(communityResult.getKgQualityScore()).isEqualTo(0.90D);
        assertThat(communityResult.getKgQualityReasons()).contains("crossDocument");
        assertThat(communityResult.getKgRankPosition()).isEqualTo(1);
        assertThat(communityResult.getScore()).isGreaterThan(1.0D);
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

    private static GraphRagCrossDocumentIndex.CanonicalEntityGroup canonicalGroup(String key,
                                                                                  String name,
                                                                                  String entityType,
                                                                                  List<Long> entityIds,
                                                                                  List<Long> documentIds,
                                                                                  double qualityScore) {
        return new GraphRagCrossDocumentIndex.CanonicalEntityGroup(
            key,
            name,
            entityType,
            new LinkedHashSet<>(entityIds),
            new LinkedHashSet<>(documentIds),
            new LinkedHashSet<>(List.of(51L)),
            List.of(key),
            qualityScore,
            new GraphRagCrossDocumentIndex.QualityProfile(qualityScore, List.of("groundedEvidence"), List.of()),
            new GraphRagCrossDocumentIndex.RankProfile(0.12D, qualityScore, 1, 2, 1, 1, 1.8D)
        );
    }

    private static GraphRagCrossDocumentIndex.RelationGroup relationGroup(String key,
                                                                          String sourceGroupKey,
                                                                          String targetGroupKey,
                                                                          String relationType,
                                                                          List<Long> relationIds,
                                                                          List<Long> evidenceIds,
                                                                          List<Long> documentIds,
                                                                          double qualityScore,
                                                                          List<String> qualityReasons,
                                                                          List<String> noiseReasons) {
        Map<Long, Integer> evidenceCountByRelationId = relationIds.stream()
            .collect(java.util.stream.Collectors.toMap(
                relationId -> relationId,
                relationId -> 1,
                (left, right) -> left,
                java.util.LinkedHashMap::new
            ));
        return new GraphRagCrossDocumentIndex.RelationGroup(
            key,
            sourceGroupKey,
            targetGroupKey,
            relationType,
            new LinkedHashSet<>(relationIds),
            new LinkedHashSet<>(evidenceIds),
            new LinkedHashSet<>(documentIds),
            evidenceCountByRelationId,
            qualityScore,
            new GraphRagCrossDocumentIndex.QualityProfile(qualityScore, qualityReasons, noiseReasons),
            new GraphRagCrossDocumentIndex.RankProfile(0.16D, qualityScore, 1, 3, 1, 2, 2.4D)
        );
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
