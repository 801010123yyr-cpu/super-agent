package org.javaup.ai.manage.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.manage.data.SuperAgentKgEntity;
import org.javaup.ai.manage.data.SuperAgentKgEvidence;
import org.javaup.ai.manage.data.SuperAgentKgRelation;
import org.javaup.ai.manage.mapper.SuperAgentKgEntityMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgEvidenceMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgRelationMapper;
import org.javaup.ai.manage.model.graph.GraphRagEvaluationBatchReport;
import org.javaup.ai.manage.model.graph.GraphRagEvaluationReport;
import org.javaup.ai.manage.model.graph.GraphRagEvaluationSuite;
import org.javaup.ai.manage.model.graph.GraphRagQualityReport;
import org.javaup.ai.manage.service.GraphRagEvaluationService;
import org.javaup.ai.manage.service.GraphRagQualityService;
import org.javaup.enums.BusinessStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GraphRagEvaluationServiceImpl implements GraphRagEvaluationService {

    private static final double ENTITY_WEIGHT = 0.4D;

    private static final double RELATION_WEIGHT = 0.4D;

    private static final double EVIDENCE_WEIGHT = 0.2D;

    private static final double DEFAULT_PASS_THRESHOLD = 0.85D;

    private final SuperAgentKgEntityMapper entityMapper;

    private final SuperAgentKgRelationMapper relationMapper;

    private final SuperAgentKgEvidenceMapper evidenceMapper;

    private final GraphRagQualityService qualityService;

    private final ObjectMapper objectMapper;

    public GraphRagEvaluationServiceImpl(SuperAgentKgEntityMapper entityMapper,
                                         SuperAgentKgRelationMapper relationMapper,
                                         SuperAgentKgEvidenceMapper evidenceMapper,
                                         GraphRagQualityService qualityService,
                                         ObjectMapper objectMapper) {
        this.entityMapper = entityMapper;
        this.relationMapper = relationMapper;
        this.evidenceMapper = evidenceMapper;
        this.qualityService = qualityService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public GraphRagEvaluationReport evaluate(GraphRagEvaluationSuite suite) {
        if (suite == null) {
            return emptyReport(null, null, null, qualityService.evaluate(null, null));
        }
        Long documentId = suite == null ? null : suite.getDocumentId();
        Long taskId = suite == null ? null : suite.getTaskId();
        List<GraphRagEvaluationSuite.ExpectedEntity> expectedEntities = safeList(suite.getExpectedEntities());
        List<GraphRagEvaluationSuite.ExpectedRelation> expectedRelations = safeList(suite.getExpectedRelations());
        List<GraphRagEvaluationSuite.ExpectedEvidence> expectedEvidences = safeList(suite.getExpectedEvidences());
        long expectedEntityCount = requiredCount(expectedEntities);
        long expectedRelationCount = requiredCount(expectedRelations);
        long expectedEvidenceCount = requiredCount(expectedEvidences);
        GraphRagQualityReport qualityReport = qualityService.evaluate(documentId, taskId);
        if (expectedEntityCount + expectedRelationCount + expectedEvidenceCount == 0L) {
            return emptyReport(suite, documentId, taskId, qualityReport);
        }
        if (documentId == null || taskId == null) {
            return missingBindingReport(
                suite,
                documentId,
                taskId,
                qualityReport,
                expectedEntities,
                expectedRelations,
                expectedEvidences,
                expectedEntityCount,
                expectedRelationCount,
                expectedEvidenceCount
            );
        }

        List<SuperAgentKgEntity> entities = activeEntities(documentId, taskId);
        List<SuperAgentKgRelation> relations = activeRelations(documentId, taskId);
        List<SuperAgentKgEvidence> evidences = activeEvidences(documentId, taskId);
        Map<Long, SuperAgentKgEntity> entityById = entities.stream()
            .filter(entity -> entity != null && entity.getId() != null)
            .collect(Collectors.toMap(SuperAgentKgEntity::getId, item -> item, (left, right) -> left, LinkedHashMap::new));
        Map<Long, SuperAgentKgRelation> relationById = relations.stream()
            .filter(relation -> relation != null && relation.getId() != null)
            .collect(Collectors.toMap(SuperAgentKgRelation::getId, item -> item, (left, right) -> left, LinkedHashMap::new));

        List<GraphRagEvaluationReport.EntityResult> entityResults = expectedEntities.stream()
            .map(expected -> evaluateEntity(expected, entities))
            .toList();
        List<GraphRagEvaluationReport.RelationResult> relationResults = expectedRelations.stream()
            .map(expected -> evaluateRelation(expected, entities, relations, entityById))
            .toList();
        List<GraphRagEvaluationReport.EvidenceResult> evidenceResults = expectedEvidences.stream()
            .map(expected -> evaluateEvidence(expected, entities, relations, evidences, entityById, relationById))
            .toList();

        long matchedEntityCount = matchedRequiredCount(entityResults);
        long matchedRelationCount = matchedRequiredCount(relationResults);
        long matchedEvidenceCount = matchedRequiredCount(evidenceResults);
        double entityRecall = ratio(matchedEntityCount, expectedEntityCount);
        double relationRecall = ratio(matchedRelationCount, expectedRelationCount);
        double evidenceRecall = ratio(matchedEvidenceCount, expectedEvidenceCount);
        double overallRecall = overallRecall(entityRecall, expectedEntityCount, relationRecall, expectedRelationCount, evidenceRecall, expectedEvidenceCount);
        String level = evaluationLevel(overallRecall);
        double passThreshold = passThreshold(suite);
        boolean passed = overallRecall >= passThreshold;

        return GraphRagEvaluationReport.builder()
            .suiteId(suite.getSuiteId())
            .name(suite.getName())
            .scenario(suite.getScenario())
            .question(suite.getQuestion())
            .sourceDocument(suite.getSourceDocument())
            .documentId(documentId)
            .taskId(taskId)
            .passThreshold(passThreshold)
            .passed(passed)
            .evaluationLevel(level)
            .evaluationScore(overallRecall)
            .summary(summary(level, overallRecall, entityRecall, relationRecall, evidenceRecall))
            .qualityReport(qualityReport)
            .expectedEntityCount(expectedEntityCount)
            .matchedEntityCount(matchedEntityCount)
            .expectedRelationCount(expectedRelationCount)
            .matchedRelationCount(matchedRelationCount)
            .expectedEvidenceCount(expectedEvidenceCount)
            .matchedEvidenceCount(matchedEvidenceCount)
            .entityRecall(entityRecall)
            .relationRecall(relationRecall)
            .evidenceRecall(evidenceRecall)
            .overallRecall(overallRecall)
            .entityResults(entityResults)
            .relationResults(relationResults)
            .evidenceResults(evidenceResults)
            .build();
    }

    @Override
    @Transactional(readOnly = true)
    public GraphRagEvaluationBatchReport evaluateBatch(String batchId,
                                                       String name,
                                                       List<GraphRagEvaluationSuite> suites) {
        List<GraphRagEvaluationSuite> safeSuites = safeList(suites).stream()
            .filter(Objects::nonNull)
            .toList();
        if (safeSuites.isEmpty()) {
            return GraphRagEvaluationBatchReport.empty(batchId, name);
        }

        List<GraphRagEvaluationReport> reports = safeSuites.stream()
            .map(this::evaluate)
            .toList();
        long expectedEntityCount = reports.stream().mapToLong(report -> longValue(report.getExpectedEntityCount())).sum();
        long matchedEntityCount = reports.stream().mapToLong(report -> longValue(report.getMatchedEntityCount())).sum();
        long expectedRelationCount = reports.stream().mapToLong(report -> longValue(report.getExpectedRelationCount())).sum();
        long matchedRelationCount = reports.stream().mapToLong(report -> longValue(report.getMatchedRelationCount())).sum();
        long expectedEvidenceCount = reports.stream().mapToLong(report -> longValue(report.getExpectedEvidenceCount())).sum();
        long matchedEvidenceCount = reports.stream().mapToLong(report -> longValue(report.getMatchedEvidenceCount())).sum();

        double entityRecall = ratio(matchedEntityCount, expectedEntityCount);
        double relationRecall = ratio(matchedRelationCount, expectedRelationCount);
        double evidenceRecall = ratio(matchedEvidenceCount, expectedEvidenceCount);
        double overallRecall = overallRecall(entityRecall, expectedEntityCount, relationRecall, expectedRelationCount, evidenceRecall, expectedEvidenceCount);
        String level = evaluationLevel(overallRecall);
        long passedSuiteCount = reports.stream().filter(this::passed).count();
        long suiteCount = reports.size();
        long failedSuiteCount = suiteCount - passedSuiteCount;
        double passRate = ratio(passedSuiteCount, suiteCount);
        double minSuiteRecall = reports.stream()
            .map(GraphRagEvaluationReport::getOverallRecall)
            .filter(Objects::nonNull)
            .mapToDouble(Double::doubleValue)
            .min()
            .orElse(0D);
        double maxSuiteRecall = reports.stream()
            .map(GraphRagEvaluationReport::getOverallRecall)
            .filter(Objects::nonNull)
            .mapToDouble(Double::doubleValue)
            .max()
            .orElse(0D);

        return GraphRagEvaluationBatchReport.builder()
            .batchId(batchId)
            .name(name)
            .evaluationLevel(level)
            .evaluationScore(overallRecall)
            .summary(batchSummary(level, overallRecall, passRate, suiteCount, failedSuiteCount))
            .suiteCount(suiteCount)
            .passedSuiteCount(passedSuiteCount)
            .failedSuiteCount(failedSuiteCount)
            .expectedEntityCount(expectedEntityCount)
            .matchedEntityCount(matchedEntityCount)
            .expectedRelationCount(expectedRelationCount)
            .matchedRelationCount(matchedRelationCount)
            .expectedEvidenceCount(expectedEvidenceCount)
            .matchedEvidenceCount(matchedEvidenceCount)
            .entityRecall(entityRecall)
            .relationRecall(relationRecall)
            .evidenceRecall(evidenceRecall)
            .overallRecall(overallRecall)
            .passRate(passRate)
            .minSuiteRecall(round(minSuiteRecall))
            .maxSuiteRecall(round(maxSuiteRecall))
            .reports(reports)
            .failedSuites(failedSuites(reports))
            .build();
    }

    private GraphRagEvaluationReport emptyReport(GraphRagEvaluationSuite suite,
                                                 Long documentId,
                                                 Long taskId,
                                                 GraphRagQualityReport qualityReport) {
        GraphRagEvaluationReport report = GraphRagEvaluationReport.empty(
            suite == null ? null : suite.getSuiteId(),
            suite == null ? null : suite.getName(),
            documentId,
            taskId,
            qualityReport
        );
        if (suite != null) {
            report.setScenario(suite.getScenario());
            report.setQuestion(suite.getQuestion());
            report.setSourceDocument(suite.getSourceDocument());
            report.setPassThreshold(passThreshold(suite));
        }
        return report;
    }

    private GraphRagEvaluationReport missingBindingReport(GraphRagEvaluationSuite suite,
                                                          Long documentId,
                                                          Long taskId,
                                                          GraphRagQualityReport qualityReport,
                                                          List<GraphRagEvaluationSuite.ExpectedEntity> expectedEntities,
                                                          List<GraphRagEvaluationSuite.ExpectedRelation> expectedRelations,
                                                          List<GraphRagEvaluationSuite.ExpectedEvidence> expectedEvidences,
                                                          long expectedEntityCount,
                                                          long expectedRelationCount,
                                                          long expectedEvidenceCount) {
        String reason = "未绑定真实 documentId/taskId，无法评测该 GraphRAG baseline。";
        return GraphRagEvaluationReport.builder()
            .suiteId(suite.getSuiteId())
            .name(suite.getName())
            .scenario(suite.getScenario())
            .question(suite.getQuestion())
            .sourceDocument(suite.getSourceDocument())
            .documentId(documentId)
            .taskId(taskId)
            .passThreshold(passThreshold(suite))
            .passed(false)
            .evaluationLevel(GraphRagQualityReport.LEVEL_WEAK)
            .evaluationScore(0D)
            .summary(reason)
            .qualityReport(qualityReport)
            .expectedEntityCount(expectedEntityCount)
            .matchedEntityCount(0L)
            .expectedRelationCount(expectedRelationCount)
            .matchedRelationCount(0L)
            .expectedEvidenceCount(expectedEvidenceCount)
            .matchedEvidenceCount(0L)
            .entityRecall(0D)
            .relationRecall(0D)
            .evidenceRecall(0D)
            .overallRecall(0D)
            .entityResults(expectedEntities.stream()
                .map(expected -> GraphRagEvaluationReport.EntityResult.builder()
                    .expectedName(expected == null ? null : expected.getName())
                    .expectedEntityType(expected == null ? null : expected.getEntityType())
                    .required(required(expected))
                    .matched(false)
                    .reason(reason)
                    .build())
                .toList())
            .relationResults(expectedRelations.stream()
                .map(expected -> GraphRagEvaluationReport.RelationResult.builder()
                    .expectedSourceName(expected == null ? null : expected.getSourceName())
                    .expectedTargetName(expected == null ? null : expected.getTargetName())
                    .expectedRelationType(expected == null ? null : expected.getRelationType())
                    .required(required(expected))
                    .matched(false)
                    .reason(reason)
                    .build())
                .toList())
            .evidenceResults(expectedEvidences.stream()
                .map(expected -> GraphRagEvaluationReport.EvidenceResult.builder()
                    .expectedQuoteText(expected == null ? null : expected.getQuoteText())
                    .expectedQuoteKeywords(expected == null ? List.of() : expected.getQuoteKeywords())
                    .expectedEntityName(expected == null ? null : expected.getEntityName())
                    .expectedSourceName(expected == null ? null : expected.getSourceName())
                    .expectedTargetName(expected == null ? null : expected.getTargetName())
                    .expectedRelationType(expected == null ? null : expected.getRelationType())
                    .required(required(expected))
                    .matched(false)
                    .reason(reason)
                    .build())
                .toList())
            .build();
    }

    private GraphRagEvaluationReport.EntityResult evaluateEntity(GraphRagEvaluationSuite.ExpectedEntity expected,
                                                                 List<SuperAgentKgEntity> entities) {
        if (expected == null || expectedNames(expected).isEmpty()) {
            return GraphRagEvaluationReport.EntityResult.builder()
                .required(required(expected))
                .matched(false)
                .reason("期望实体缺少 name 或 aliases。")
                .build();
        }
        for (SuperAgentKgEntity entity : entities) {
            if (entity == null || !matchesEntityType(expected.getEntityType(), entity.getEntityType())) {
                continue;
            }
            Set<String> actualNames = entityNames(entity);
            if (intersects(expectedNames(expected), actualNames)) {
                List<String> missingAliases = missingRequiredAliases(expected, actualNames);
                if (!missingAliases.isEmpty()) {
                    return GraphRagEvaluationReport.EntityResult.builder()
                        .expectedName(expected.getName())
                        .expectedEntityType(expected.getEntityType())
                        .required(required(expected))
                        .matched(false)
                        .actualEntityId(entity.getId())
                        .actualName(entity.getName())
                        .missingAliases(missingAliases)
                        .reason("实体名称命中，但缺少必须别名：" + String.join("、", missingAliases))
                        .build();
                }
                return GraphRagEvaluationReport.EntityResult.builder()
                    .expectedName(expected.getName())
                    .expectedEntityType(expected.getEntityType())
                    .required(required(expected))
                    .matched(true)
                    .actualEntityId(entity.getId())
                    .actualName(entity.getName())
                    .missingAliases(List.of())
                    .reason("实体名称、别名和必须别名命中。")
                    .build();
            }
        }
        return GraphRagEvaluationReport.EntityResult.builder()
            .expectedName(expected.getName())
            .expectedEntityType(expected.getEntityType())
            .required(required(expected))
            .matched(false)
            .reason("未在 KG 实体名称、normalizedName 或 aliases 中命中。")
            .build();
    }

    private GraphRagEvaluationReport.RelationResult evaluateRelation(GraphRagEvaluationSuite.ExpectedRelation expected,
                                                                     List<SuperAgentKgEntity> entities,
                                                                     List<SuperAgentKgRelation> relations,
                                                                     Map<Long, SuperAgentKgEntity> entityById) {
        RelationMatch relationMatch = findRelation(expected, entities, relations);
        if (relationMatch.relation() == null) {
            return GraphRagEvaluationReport.RelationResult.builder()
                .expectedSourceName(expected == null ? null : expected.getSourceName())
                .expectedTargetName(expected == null ? null : expected.getTargetName())
                .expectedRelationType(expected == null ? null : expected.getRelationType())
                .required(required(expected))
                .matched(false)
                .reason(relationMatch.reason())
                .build();
        }
        SuperAgentKgRelation relation = relationMatch.relation();
        SuperAgentKgEntity source = entityById.get(relation.getSourceEntityId());
        SuperAgentKgEntity target = entityById.get(relation.getTargetEntityId());
        return GraphRagEvaluationReport.RelationResult.builder()
            .expectedSourceName(expected.getSourceName())
            .expectedTargetName(expected.getTargetName())
            .expectedRelationType(expected.getRelationType())
            .required(required(expected))
            .matched(true)
            .actualRelationId(relation.getId())
            .actualSourceEntityId(relation.getSourceEntityId())
            .actualSourceName(source == null ? null : source.getName())
            .actualTargetEntityId(relation.getTargetEntityId())
            .actualTargetName(target == null ? null : target.getName())
            .reason(relationMatch.reason())
            .build();
    }

    private GraphRagEvaluationReport.EvidenceResult evaluateEvidence(GraphRagEvaluationSuite.ExpectedEvidence expected,
                                                                     List<SuperAgentKgEntity> entities,
                                                                     List<SuperAgentKgRelation> relations,
                                                                     List<SuperAgentKgEvidence> evidences,
                                                                     Map<Long, SuperAgentKgEntity> entityById,
                                                                     Map<Long, SuperAgentKgRelation> relationById) {
        if (expected == null || !hasEvidenceExpectation(expected)) {
            return GraphRagEvaluationReport.EvidenceResult.builder()
                .required(required(expected))
                .matched(false)
                .reason("期望证据缺少 quoteText、entityName 或 relation 约束。")
                .build();
        }
        Set<Long> expectedEntityIds = resolveEntityIds(expected.getEntityName(), entities);
        if (StrUtil.isNotBlank(expected.getEntityName()) && expectedEntityIds.isEmpty()) {
            return unmatchedEvidence(expected, "未找到期望 entityName 对应的 KG 实体。");
        }
        RelationMatch expectedRelation = findRelation(expectedRelation(expected), entities, relations);
        boolean relationConstrained = hasRelationExpectation(expected);
        if (relationConstrained && expectedRelation.relation() == null) {
            return unmatchedEvidence(expected, expectedRelation.reason());
        }
        Set<Long> expectedRelationIds = relationConstrained
            ? matchingRelationIds(expectedRelation(expected), entities, relations)
            : Set.of();

        for (SuperAgentKgEvidence evidence : evidences) {
            if (evidence == null || !matchesQuote(expected, evidence.getQuoteText())) {
                continue;
            }
            if (!matchesEvidenceEntity(evidence, expectedEntityIds, relationById)) {
                continue;
            }
            if (relationConstrained && !expectedRelationIds.contains(evidence.getRelationId())) {
                continue;
            }
            return GraphRagEvaluationReport.EvidenceResult.builder()
                .expectedQuoteText(expected.getQuoteText())
                .expectedQuoteKeywords(safeList(expected.getQuoteKeywords()))
                .expectedEntityName(expected.getEntityName())
                .expectedSourceName(expected.getSourceName())
                .expectedTargetName(expected.getTargetName())
                .expectedRelationType(expected.getRelationType())
                .required(required(expected))
                .matched(true)
                .actualEvidenceId(evidence.getId())
                .actualEntityId(evidence.getEntityId())
                .actualRelationId(evidence.getRelationId())
                .actualChunkId(evidence.getChunkId())
                .actualQuoteText(evidence.getQuoteText())
                .actualPageNo(evidence.getPageNo())
                .actualPageRange(evidence.getPageRange())
                .actualSectionPath(evidence.getSectionPath())
                .reason("证据 quoteText/关键词和约束命中。")
                .build();
        }
        return unmatchedEvidence(expected, "未找到同时满足 quoteText、实体和关系约束的 KG evidence。");
    }

    private GraphRagEvaluationReport.EvidenceResult unmatchedEvidence(GraphRagEvaluationSuite.ExpectedEvidence expected, String reason) {
        return GraphRagEvaluationReport.EvidenceResult.builder()
            .expectedQuoteText(expected == null ? null : expected.getQuoteText())
            .expectedQuoteKeywords(expected == null ? List.of() : safeList(expected.getQuoteKeywords()))
            .expectedEntityName(expected == null ? null : expected.getEntityName())
            .expectedSourceName(expected == null ? null : expected.getSourceName())
            .expectedTargetName(expected == null ? null : expected.getTargetName())
            .expectedRelationType(expected == null ? null : expected.getRelationType())
            .required(required(expected))
            .matched(false)
            .reason(reason)
            .build();
    }

    private boolean passed(GraphRagEvaluationReport report) {
        if (report == null || report.getOverallRecall() == null) {
            return false;
        }
        return Boolean.TRUE.equals(report.getPassed()) || report.getOverallRecall() >= passThreshold(report.getPassThreshold());
    }

    private List<GraphRagEvaluationBatchReport.FailedSuite> failedSuites(List<GraphRagEvaluationReport> reports) {
        return reports.stream()
            .filter(report -> !passed(report))
            .map(report -> GraphRagEvaluationBatchReport.FailedSuite.builder()
                .suiteId(report.getSuiteId())
                .name(report.getName())
                .sourceDocument(report.getSourceDocument())
                .documentId(report.getDocumentId())
                .taskId(report.getTaskId())
                .overallRecall(report.getOverallRecall())
                .evaluationLevel(report.getEvaluationLevel())
                .reason(firstMissReason(report))
                .build())
            .toList();
    }

    private String firstMissReason(GraphRagEvaluationReport report) {
        if (report == null) {
            return "评测报告为空。";
        }
        for (GraphRagEvaluationReport.EntityResult result : safeList(report.getEntityResults())) {
            if (required(result.getRequired()) && !Boolean.TRUE.equals(result.getMatched())) {
                return "实体未命中：" + StrUtil.blankToDefault(result.getExpectedName(), "-") + "，" + result.getReason();
            }
        }
        for (GraphRagEvaluationReport.RelationResult result : safeList(report.getRelationResults())) {
            if (required(result.getRequired()) && !Boolean.TRUE.equals(result.getMatched())) {
                return "关系未命中：" + StrUtil.blankToDefault(result.getExpectedSourceName(), "-")
                    + " -> " + StrUtil.blankToDefault(result.getExpectedTargetName(), "-")
                    + "，" + result.getReason();
            }
        }
        for (GraphRagEvaluationReport.EvidenceResult result : safeList(report.getEvidenceResults())) {
            if (required(result.getRequired()) && !Boolean.TRUE.equals(result.getMatched())) {
                return "证据未命中：" + StrUtil.blankToDefault(result.getExpectedQuoteText(), "-") + "，" + result.getReason();
            }
        }
        return "综合召回低于通过阈值。";
    }

    private String batchSummary(String level,
                                double overallRecall,
                                double passRate,
                                long suiteCount,
                                long failedSuiteCount) {
        String levelText = switch (level) {
            case GraphRagQualityReport.LEVEL_STRONG -> "较强";
            case GraphRagQualityReport.LEVEL_WATCH -> "需观察";
            default -> "偏弱";
        };
        return "GraphRAG 批量评测" + levelText
            + "，样例数 " + suiteCount
            + "，通过率 " + percent(passRate)
            + "，综合召回 " + percent(overallRecall)
            + "，未通过样例 " + failedSuiteCount + " 个。";
    }

    private RelationMatch findRelation(GraphRagEvaluationSuite.ExpectedRelation expected,
                                       List<SuperAgentKgEntity> entities,
                                       List<SuperAgentKgRelation> relations) {
        if (expected == null || !hasRelationExpectation(expected)) {
            return new RelationMatch(null, "期望关系缺少 sourceName、targetName 或 relationType。");
        }
        Set<Long> sourceIds = resolveEntityIds(expected.getSourceName(), entities);
        Set<Long> targetIds = resolveEntityIds(expected.getTargetName(), entities);
        if (StrUtil.isNotBlank(expected.getSourceName()) && sourceIds.isEmpty()) {
            return new RelationMatch(null, "未找到期望起点实体。");
        }
        if (StrUtil.isNotBlank(expected.getTargetName()) && targetIds.isEmpty()) {
            return new RelationMatch(null, "未找到期望终点实体。");
        }
        for (SuperAgentKgRelation relation : relations) {
            if (relation == null || !matchesRelationType(expected, relation.getRelationType())) {
                continue;
            }
            if (matchesDirection(relation, sourceIds, targetIds)) {
                return new RelationMatch(relation, "关系端点和类型命中。");
            }
            if (allowReverse(expected) && matchesReverseDirection(relation, sourceIds, targetIds)) {
                return new RelationMatch(relation, "ASSOCIATED_WITH 或未指定类型的关系端点反向命中。");
            }
        }
        return new RelationMatch(null, "未找到同时满足端点和 relationType 的 KG relation。");
    }

    private Set<Long> matchingRelationIds(GraphRagEvaluationSuite.ExpectedRelation expected,
                                          List<SuperAgentKgEntity> entities,
                                          List<SuperAgentKgRelation> relations) {
        if (expected == null || !hasRelationExpectation(expected)) {
            return Set.of();
        }
        Set<Long> sourceIds = resolveEntityIds(expected.getSourceName(), entities);
        Set<Long> targetIds = resolveEntityIds(expected.getTargetName(), entities);
        if (StrUtil.isNotBlank(expected.getSourceName()) && sourceIds.isEmpty()) {
            return Set.of();
        }
        if (StrUtil.isNotBlank(expected.getTargetName()) && targetIds.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<Long> relationIds = new LinkedHashSet<>();
        for (SuperAgentKgRelation relation : relations) {
            if (relation == null || relation.getId() == null || !matchesRelationType(expected, relation.getRelationType())) {
                continue;
            }
            if (matchesDirection(relation, sourceIds, targetIds)
                || (allowReverse(expected) && matchesReverseDirection(relation, sourceIds, targetIds))) {
                relationIds.add(relation.getId());
            }
        }
        return relationIds;
    }

    private GraphRagEvaluationSuite.ExpectedRelation expectedRelation(GraphRagEvaluationSuite.ExpectedEvidence expected) {
        return GraphRagEvaluationSuite.ExpectedRelation.builder()
            .sourceName(expected.getSourceName())
            .targetName(expected.getTargetName())
            .relationType(expected.getRelationType())
            .relationTypeAliases(safeList(expected.getRelationTypeAliases()))
            .required(expected.getRequired())
            .build();
    }

    private boolean matchesDirection(SuperAgentKgRelation relation, Set<Long> sourceIds, Set<Long> targetIds) {
        return matchesEntitySet(relation.getSourceEntityId(), sourceIds) && matchesEntitySet(relation.getTargetEntityId(), targetIds);
    }

    private boolean matchesReverseDirection(SuperAgentKgRelation relation, Set<Long> sourceIds, Set<Long> targetIds) {
        return matchesEntitySet(relation.getTargetEntityId(), sourceIds) && matchesEntitySet(relation.getSourceEntityId(), targetIds);
    }

    private boolean matchesEntitySet(Long entityId, Set<Long> expectedIds) {
        return expectedIds.isEmpty() || expectedIds.contains(entityId);
    }

    private boolean matchesEvidenceEntity(SuperAgentKgEvidence evidence,
                                          Set<Long> expectedEntityIds,
                                          Map<Long, SuperAgentKgRelation> relationById) {
        if (expectedEntityIds.isEmpty()) {
            return true;
        }
        if (expectedEntityIds.contains(evidence.getEntityId())) {
            return true;
        }
        SuperAgentKgRelation relation = relationById.get(evidence.getRelationId());
        return relation != null
            && (expectedEntityIds.contains(relation.getSourceEntityId()) || expectedEntityIds.contains(relation.getTargetEntityId()));
    }

    private Set<Long> resolveEntityIds(String expectedName, List<SuperAgentKgEntity> entities) {
        if (StrUtil.isBlank(expectedName)) {
            return new LinkedHashSet<>();
        }
        String normalizedExpected = normalize(expectedName);
        return entities.stream()
            .filter(entity -> entity != null && entity.getId() != null && entityNames(entity).contains(normalizedExpected))
            .map(SuperAgentKgEntity::getId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> expectedNames(GraphRagEvaluationSuite.ExpectedEntity expected) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        addNormalized(names, expected.getName());
        for (String alias : safeList(expected.getAliases())) {
            addNormalized(names, alias);
        }
        return names;
    }

    private List<String> missingRequiredAliases(GraphRagEvaluationSuite.ExpectedEntity expected, Set<String> actualNames) {
        List<String> missingAliases = new ArrayList<>();
        if (expected == null) {
            return missingAliases;
        }
        for (String alias : safeList(expected.getMustHaveAliases())) {
            String normalized = normalize(alias);
            if (StrUtil.isNotBlank(normalized) && !actualNames.contains(normalized)) {
                missingAliases.add(alias);
            }
        }
        return missingAliases;
    }

    private Set<String> entityNames(SuperAgentKgEntity entity) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        addNormalized(names, entity.getName());
        addNormalized(names, entity.getNormalizedName());
        Map<String, Object> metadata = readMap(entity.getMetadataJson());
        for (String alias : readStringList(metadata.get("aliases"))) {
            addNormalized(names, alias);
        }
        addNormalized(names, stringValue(metadata.get("entityResolutionCanonicalName")));
        return names;
    }

    private boolean intersects(Set<String> left, Set<String> right) {
        for (String value : left) {
            if (right.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private void addNormalized(Set<String> target, String value) {
        String normalized = normalize(value);
        if (normalized.length() >= 2) {
            target.add(normalized);
        }
    }

    private void addNormalizedType(Set<String> target, String value) {
        String normalized = normalizeType(value);
        if (StrUtil.isNotBlank(normalized)) {
            target.add(normalized);
        }
    }

    private boolean matchesEntityType(String expectedType, String actualType) {
        return StrUtil.isBlank(expectedType) || Objects.equals(normalizeType(expectedType), normalizeType(actualType));
    }

    private boolean matchesRelationType(String expectedType, String actualType) {
        return StrUtil.isBlank(expectedType) || Objects.equals(normalizeType(expectedType), normalizeType(actualType));
    }

    private boolean matchesRelationType(GraphRagEvaluationSuite.ExpectedRelation expected, String actualType) {
        if (expected == null) {
            return false;
        }
        LinkedHashSet<String> expectedTypes = new LinkedHashSet<>();
        addNormalizedType(expectedTypes, expected.getRelationType());
        for (String alias : safeList(expected.getRelationTypeAliases())) {
            addNormalizedType(expectedTypes, alias);
        }
        return expectedTypes.isEmpty() || expectedTypes.contains(normalizeType(actualType));
    }

    private boolean allowReverse(GraphRagEvaluationSuite.ExpectedRelation expected) {
        if (expected == null) {
            return false;
        }
        LinkedHashSet<String> expectedTypes = new LinkedHashSet<>();
        addNormalizedType(expectedTypes, expected.getRelationType());
        for (String alias : safeList(expected.getRelationTypeAliases())) {
            addNormalizedType(expectedTypes, alias);
        }
        return expectedTypes.isEmpty() || expectedTypes.contains("ASSOCIATED_WITH") || expectedTypes.contains("RELATED_TO");
    }

    private boolean matchesQuote(GraphRagEvaluationSuite.ExpectedEvidence expected, String actualQuote) {
        if (expected == null) {
            return false;
        }
        if (StrUtil.isNotBlank(expected.getQuoteText()) && matchesText(expected.getQuoteText(), actualQuote)) {
            return true;
        }
        List<String> keywords = safeList(expected.getQuoteKeywords()).stream()
            .filter(StrUtil::isNotBlank)
            .toList();
        if (keywords.isEmpty()) {
            return StrUtil.isBlank(expected.getQuoteText());
        }
        String actual = normalize(actualQuote);
        return StrUtil.isNotBlank(actual)
            && keywords.stream().allMatch(keyword -> actual.contains(normalize(keyword)));
    }

    private boolean matchesText(String expectedQuote, String actualQuote) {
        String expected = normalize(expectedQuote);
        String actual = normalize(actualQuote);
        return StrUtil.isNotBlank(expected) && StrUtil.isNotBlank(actual)
            && (actual.contains(expected) || expected.contains(actual));
    }

    private boolean hasRelationExpectation(GraphRagEvaluationSuite.ExpectedRelation expected) {
        return expected != null
            && (StrUtil.isNotBlank(expected.getSourceName())
            || StrUtil.isNotBlank(expected.getTargetName())
            || StrUtil.isNotBlank(expected.getRelationType()));
    }

    private boolean hasRelationExpectation(GraphRagEvaluationSuite.ExpectedEvidence expected) {
        return expected != null
            && (StrUtil.isNotBlank(expected.getSourceName())
            || StrUtil.isNotBlank(expected.getTargetName())
            || StrUtil.isNotBlank(expected.getRelationType()));
    }

    private boolean hasEvidenceExpectation(GraphRagEvaluationSuite.ExpectedEvidence expected) {
        return expected != null
            && (StrUtil.isNotBlank(expected.getQuoteText())
            || !safeList(expected.getQuoteKeywords()).isEmpty()
            || StrUtil.isNotBlank(expected.getEntityName())
            || hasRelationExpectation(expected));
    }

    private String evaluationLevel(double score) {
        if (score >= 0.85D) {
            return GraphRagQualityReport.LEVEL_STRONG;
        }
        if (score >= 0.65D) {
            return GraphRagQualityReport.LEVEL_WATCH;
        }
        return GraphRagQualityReport.LEVEL_WEAK;
    }

    private String summary(String level, double overallRecall, double entityRecall, double relationRecall, double evidenceRecall) {
        String levelText = switch (level) {
            case GraphRagQualityReport.LEVEL_STRONG -> "较强";
            case GraphRagQualityReport.LEVEL_WATCH -> "需观察";
            default -> "偏弱";
        };
        return "GraphRAG 批量评测" + levelText
            + "，综合召回 " + percent(overallRecall)
            + "，实体召回 " + percent(entityRecall)
            + "，关系召回 " + percent(relationRecall)
            + "，证据召回 " + percent(evidenceRecall) + "。";
    }

    private double overallRecall(double entityRecall,
                                 long expectedEntityCount,
                                 double relationRecall,
                                 long expectedRelationCount,
                                 double evidenceRecall,
                                 long expectedEvidenceCount) {
        double weighted = 0D;
        double weight = 0D;
        if (expectedEntityCount > 0L) {
            weighted += entityRecall * ENTITY_WEIGHT;
            weight += ENTITY_WEIGHT;
        }
        if (expectedRelationCount > 0L) {
            weighted += relationRecall * RELATION_WEIGHT;
            weight += RELATION_WEIGHT;
        }
        if (expectedEvidenceCount > 0L) {
            weighted += evidenceRecall * EVIDENCE_WEIGHT;
            weight += EVIDENCE_WEIGHT;
        }
        return weight <= 0D ? 0D : round(weighted / weight);
    }

    private double ratio(long numerator, long denominator) {
        if (denominator <= 0L) {
            return 0D;
        }
        return round((double) numerator / denominator);
    }

    private long matchedRequiredCount(List<?> results) {
        return results.stream()
            .filter(this::requiredResult)
            .filter(this::matchedResult)
            .count();
    }

    private boolean requiredResult(Object result) {
        if (result instanceof GraphRagEvaluationReport.EntityResult item) {
            return required(item.getRequired());
        }
        if (result instanceof GraphRagEvaluationReport.RelationResult item) {
            return required(item.getRequired());
        }
        if (result instanceof GraphRagEvaluationReport.EvidenceResult item) {
            return required(item.getRequired());
        }
        return false;
    }

    private long longValue(Long value) {
        return value == null ? 0L : value;
    }

    private boolean matchedResult(Object result) {
        if (result instanceof GraphRagEvaluationReport.EntityResult item) {
            return Boolean.TRUE.equals(item.getMatched());
        }
        if (result instanceof GraphRagEvaluationReport.RelationResult item) {
            return Boolean.TRUE.equals(item.getMatched());
        }
        if (result instanceof GraphRagEvaluationReport.EvidenceResult item) {
            return Boolean.TRUE.equals(item.getMatched());
        }
        return false;
    }

    private long requiredCount(List<?> values) {
        return values.stream()
            .filter(this::requiredExpected)
            .count();
    }

    private boolean requiredExpected(Object value) {
        if (value instanceof GraphRagEvaluationSuite.ExpectedEntity item) {
            return required(item.getRequired());
        }
        if (value instanceof GraphRagEvaluationSuite.ExpectedRelation item) {
            return required(item.getRequired());
        }
        if (value instanceof GraphRagEvaluationSuite.ExpectedEvidence item) {
            return required(item.getRequired());
        }
        return false;
    }

    private boolean required(GraphRagEvaluationSuite.ExpectedEntity expected) {
        return expected == null || required(expected.getRequired());
    }

    private boolean required(GraphRagEvaluationSuite.ExpectedRelation expected) {
        return expected == null || required(expected.getRequired());
    }

    private boolean required(GraphRagEvaluationSuite.ExpectedEvidence expected) {
        return expected == null || required(expected.getRequired());
    }

    private boolean required(Boolean required) {
        return !Boolean.FALSE.equals(required);
    }

    private List<SuperAgentKgEntity> activeEntities(Long documentId, Long taskId) {
        return safeList(entityMapper.selectList(new LambdaQueryWrapper<SuperAgentKgEntity>()
            .eq(SuperAgentKgEntity::getDocumentId, documentId)
            .eq(SuperAgentKgEntity::getTaskId, taskId)
            .eq(SuperAgentKgEntity::getStatus, BusinessStatus.YES.getCode())));
    }

    private List<SuperAgentKgRelation> activeRelations(Long documentId, Long taskId) {
        return safeList(relationMapper.selectList(new LambdaQueryWrapper<SuperAgentKgRelation>()
            .eq(SuperAgentKgRelation::getDocumentId, documentId)
            .eq(SuperAgentKgRelation::getTaskId, taskId)
            .eq(SuperAgentKgRelation::getStatus, BusinessStatus.YES.getCode())));
    }

    private List<SuperAgentKgEvidence> activeEvidences(Long documentId, Long taskId) {
        return safeList(evidenceMapper.selectList(new LambdaQueryWrapper<SuperAgentKgEvidence>()
            .eq(SuperAgentKgEvidence::getDocumentId, documentId)
            .eq(SuperAgentKgEvidence::getTaskId, taskId)
            .eq(SuperAgentKgEvidence::getStatus, BusinessStatus.YES.getCode())));
    }

    private Map<String, Object> readMap(String json) {
        if (StrUtil.isBlank(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        }
        catch (Exception exception) {
            return Map.of();
        }
    }

    private List<String> readStringList(Object value) {
        if (value instanceof List<?> values) {
            List<String> result = new ArrayList<>();
            for (Object item : values) {
                String text = stringValue(item);
                if (StrUtil.isNotBlank(text)) {
                    result.add(text);
                }
            }
            return result;
        }
        String text = stringValue(value);
        return StrUtil.isBlank(text) ? List.of() : List.of(text);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String normalize(String value) {
        return StrUtil.blankToDefault(value, "")
            .replaceAll("[\\s>`*#_\\-，,。；;：:（）()“”\"'\\[\\]{}]+", "")
            .toLowerCase(Locale.ROOT);
    }

    private String normalizeType(String value) {
        return StrUtil.blankToDefault(value, "")
            .trim()
            .toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9_]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
    }

    private String percent(double value) {
        return Math.round(Math.max(0D, Math.min(1D, value)) * 100D) + "%";
    }

    private double passThreshold(GraphRagEvaluationSuite suite) {
        return suite == null ? DEFAULT_PASS_THRESHOLD : passThreshold(suite.getPassThreshold());
    }

    private double passThreshold(Double value) {
        if (value == null) {
            return DEFAULT_PASS_THRESHOLD;
        }
        if (value <= 0D) {
            return 0D;
        }
        if (value > 1D) {
            return 1D;
        }
        return round(value);
    }

    private double round(double value) {
        return Math.round(Math.max(0D, Math.min(1D, value)) * 10000D) / 10000D;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record RelationMatch(SuperAgentKgRelation relation, String reason) {
    }
}
