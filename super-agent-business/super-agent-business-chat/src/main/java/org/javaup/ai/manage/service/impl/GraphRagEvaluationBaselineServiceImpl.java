package org.javaup.ai.manage.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.model.graph.GraphRagEvaluationBaselineSuites;
import org.javaup.ai.manage.model.graph.GraphRagEvaluationBatchReport;
import org.javaup.ai.manage.model.graph.GraphRagEvaluationReport;
import org.javaup.ai.manage.model.graph.GraphRagEvaluationSourceBinding;
import org.javaup.ai.manage.model.graph.GraphRagEvaluationSuite;
import org.javaup.ai.manage.model.graph.GraphRagQualityReport;
import org.javaup.ai.manage.model.graph.GraphRagSearchResult;
import org.javaup.ai.manage.service.GraphRagEvaluationBaselineService;
import org.javaup.ai.manage.service.GraphRagEvaluationService;
import org.javaup.ai.manage.service.GraphRagSearchService;
import org.javaup.enums.BusinessStatus;
import org.javaup.enums.DocumentIndexStatusEnum;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class GraphRagEvaluationBaselineServiceImpl implements GraphRagEvaluationBaselineService {

    private final SuperAgentDocumentMapper documentMapper;

    private final GraphRagEvaluationService evaluationService;

    private final GraphRagSearchService graphRagSearchService;

    public GraphRagEvaluationBaselineServiceImpl(SuperAgentDocumentMapper documentMapper,
                                                 GraphRagEvaluationService evaluationService,
                                                 GraphRagSearchService graphRagSearchService) {
        this.documentMapper = documentMapper;
        this.evaluationService = evaluationService;
        this.graphRagSearchService = graphRagSearchService;
    }

    @Override
    @Transactional(readOnly = true)
    public GraphRagEvaluationBatchReport evaluateO6LlmNerBaseline() {
        Map<String, GraphRagEvaluationSourceBinding> bindings = o6LlmNerSourceBindings();
        List<GraphRagEvaluationSuite> suites = GraphRagEvaluationBaselineSuites.o6LlmNerBaselineBySource(bindings);
        return evaluationService.evaluateBatch(
            GraphRagEvaluationBaselineSuites.O6_LLM_NER_BATCH_ID,
            GraphRagEvaluationBaselineSuites.O6_LLM_NER_BATCH_NAME,
            suites
        );
    }

    @Override
    @Transactional(readOnly = true)
    public GraphRagEvaluationBatchReport evaluateO6CrossDocumentBaseline() {
        Map<String, GraphRagEvaluationSourceBinding> bindings = sourceBindings(List.of(
            GraphRagEvaluationBaselineSuites.SOURCE_O6_AUDIT_EVIDENCE,
            GraphRagEvaluationBaselineSuites.SOURCE_O6_AUDIT_ALIAS
        ));
        GraphRagEvaluationReport report = evaluateAuditSystemPermissionCrossDocument(bindings);
        return singleReportBatch(
            GraphRagEvaluationBaselineSuites.O6_CROSS_DOCUMENT_BATCH_ID,
            GraphRagEvaluationBaselineSuites.O6_CROSS_DOCUMENT_BATCH_NAME,
            report
        );
    }

    private Map<String, GraphRagEvaluationSourceBinding> o6LlmNerSourceBindings() {
        return sourceBindings(List.of(
            GraphRagEvaluationBaselineSuites.SOURCE_PRODUCTION_RELEASE,
            GraphRagEvaluationBaselineSuites.SOURCE_CUSTOMER_DATA_ACCESS
        ));
    }

    private Map<String, GraphRagEvaluationSourceBinding> sourceBindings(List<String> sourceDocuments) {
        List<SuperAgentDocument> indexedDocuments = activeIndexedDocuments();
        Map<String, GraphRagEvaluationSourceBinding> result = new LinkedHashMap<>();
        for (String sourceDocument : sourceDocuments) {
            result.put(sourceDocument, bindSource(sourceDocument, indexedDocuments));
        }
        return result;
    }

    private GraphRagEvaluationReport evaluateAuditSystemPermissionCrossDocument(
        Map<String, GraphRagEvaluationSourceBinding> bindings
    ) {
        String suiteId = "o6-crossdoc-audit-system-permission-requirements";
        String name = "审计系统跨文档召回权限要求";
        String question = "审计系统有哪些权限相关要求？";
        GraphRagEvaluationSourceBinding evidenceDoc = bindings.get(GraphRagEvaluationBaselineSuites.SOURCE_O6_AUDIT_EVIDENCE);
        GraphRagEvaluationSourceBinding aliasDoc = bindings.get(GraphRagEvaluationBaselineSuites.SOURCE_O6_AUDIT_ALIAS);
        List<GraphRagEvaluationSourceBinding> requiredBindings = List.of(evidenceDoc, aliasDoc);
        List<String> missingSources = requiredBindings.stream()
            .filter(binding -> binding == null || !Boolean.TRUE.equals(binding.getMatched())
                || binding.getDocumentId() == null || binding.getTaskId() == null)
            .map(binding -> binding == null ? "unknown" : binding.getSourceDocument())
            .toList();
        if (!missingSources.isEmpty()) {
            return crossDocumentReport(
                suiteId,
                name,
                question,
                0D,
                "未找到已完成索引构建的跨文档 O6 样例：" + String.join("，", missingSources),
                null,
                evidenceDoc == null ? null : evidenceDoc.getDocumentId(),
                evidenceDoc == null ? null : evidenceDoc.getTaskId()
            );
        }

        List<Long> documentIds = requiredBindings.stream()
            .map(GraphRagEvaluationSourceBinding::getDocumentId)
            .filter(Objects::nonNull)
            .toList();
        List<Long> taskIds = requiredBindings.stream()
            .map(GraphRagEvaluationSourceBinding::getTaskId)
            .filter(Objects::nonNull)
            .toList();
        List<GraphRagSearchResult> searchResults = graphRagSearchService.search(question, documentIds, taskIds, 8, 2);
        GraphRagSearchResult matched = searchResults.stream()
            .filter(result -> matchesAuditPermissionCrossDocumentResult(result, evidenceDoc))
            .findFirst()
            .orElse(null);
        if (matched == null) {
            return crossDocumentReport(
                suiteId,
                name,
                question,
                0D,
                "跨文档 GraphRAG 未从“审计系统”别名扩展到 AuditTrail 权限记录证据。",
                null,
                evidenceDoc.getDocumentId(),
                evidenceDoc.getTaskId()
            );
        }
        String summary = "跨文档 GraphRAG 已通过 canonical 扩展命中权限记录证据："
            + StrUtil.blankToDefault(matched.getGraphPath(), "未记录图谱路径")
            + "；canonical="
            + StrUtil.blankToDefault(matched.getCanonicalEntityName(), "-")
            + " entities=" + matched.getCanonicalEntityCount()
            + " docs=" + matched.getCanonicalDocumentCount();
        return crossDocumentReport(
            suiteId,
            name,
            question,
            1D,
            summary,
            matched,
            evidenceDoc.getDocumentId(),
            evidenceDoc.getTaskId()
        );
    }

    private boolean matchesAuditPermissionCrossDocumentResult(GraphRagSearchResult result,
                                                              GraphRagEvaluationSourceBinding evidenceDoc) {
        if (result == null || evidenceDoc == null || !Objects.equals(result.getDocumentId(), evidenceDoc.getDocumentId())) {
            return false;
        }
        if (!"RECORDS".equalsIgnoreCase(StrUtil.blankToDefault(result.getRelationType(), ""))) {
            return false;
        }
        if (result.getCanonicalEntityCount() == null || result.getCanonicalEntityCount() < 2
            || result.getCanonicalDocumentCount() == null || result.getCanonicalDocumentCount() < 2) {
            return false;
        }
        String anchorText = normalizeName(Stream.of(
                result.getEntityName(),
                result.getCanonicalEntityName()
            )
            .filter(StrUtil::isNotBlank)
            .collect(Collectors.joining(" ")));
        boolean canonicalAnchoredOnAuditSystem = anchorText.contains("audittrail") || anchorText.contains("审计系统");
        if (!canonicalAnchoredOnAuditSystem) {
            return false;
        }
        String quote = normalizeName(result.getQuoteText());
        String graphPath = normalizeName(result.getGraphPath());
        String relatedText = normalizeName(Stream.of(
                result.getRelatedEntityName(),
                result.getGraphPath()
            )
            .filter(StrUtil::isNotBlank)
            .collect(Collectors.joining(" ")));
        boolean hasAuditTrail = quote.contains("audittrail") || graphPath.contains("audittrail") || graphPath.contains("审计系统");
        boolean hasPermissionTarget = relatedText.contains("权限申请") || relatedText.contains("权限审批")
            || relatedText.contains("权限回收") || relatedText.contains("临时权限延长");
        boolean quoteHasPermissionEvidence = quote.contains("权限申请") || quote.contains("权限审批")
            || quote.contains("权限回收") || quote.contains("临时权限延长");
        return hasAuditTrail && hasPermissionTarget && quoteHasPermissionEvidence;
    }

    private GraphRagEvaluationReport crossDocumentReport(String suiteId,
                                                         String name,
                                                         String question,
                                                         double score,
                                                         String summary,
                                                         GraphRagSearchResult matched,
                                                         Long documentId,
                                                         Long taskId) {
        boolean passed = score >= 0.85D;
        GraphRagEvaluationReport.EvidenceResult evidenceResult = GraphRagEvaluationReport.EvidenceResult.builder()
            .expectedQuoteKeywords(List.of("审计系统", "AuditTrail", "权限申请", "审批", "回收"))
            .expectedSourceName("审计系统/AuditTrail")
            .expectedTargetName("权限申请")
            .expectedRelationType("RECORDS")
            .required(true)
            .matched(passed)
            .actualEvidenceId(matched == null ? null : matched.getEvidenceId())
            .actualEntityId(matched == null ? null : matched.getEntityId())
            .actualRelationId(matched == null ? null : matched.getRelationId())
            .actualChunkId(matched == null ? null : matched.getChunkId())
            .actualQuoteText(matched == null ? null : matched.getQuoteText())
            .actualPageNo(matched == null ? null : matched.getPageNo())
            .actualPageRange(matched == null ? null : matched.getPageRange())
            .actualSectionPath(matched == null ? null : matched.getSectionPath())
            .reason(summary)
            .build();
        return GraphRagEvaluationReport.builder()
            .suiteId(suiteId)
            .name(name)
            .scenario("O1-06B 跨文档实体关系 / O6 跨文档 GraphRAG")
            .question(question)
            .sourceDocument(GraphRagEvaluationBaselineSuites.SOURCE_O6_AUDIT_EVIDENCE
                + " + " + GraphRagEvaluationBaselineSuites.SOURCE_O6_AUDIT_ALIAS)
            .documentId(documentId)
            .taskId(taskId)
            .passThreshold(0.85D)
            .passed(passed)
            .evaluationLevel(passed ? GraphRagQualityReport.LEVEL_STRONG : GraphRagQualityReport.LEVEL_WEAK)
            .evaluationScore(score)
            .summary(summary)
            .expectedEntityCount(0L)
            .matchedEntityCount(0L)
            .expectedRelationCount(0L)
            .matchedRelationCount(0L)
            .expectedEvidenceCount(1L)
            .matchedEvidenceCount(passed ? 1L : 0L)
            .entityRecall(0D)
            .relationRecall(0D)
            .evidenceRecall(score)
            .overallRecall(score)
            .entityResults(List.of())
            .relationResults(List.of())
            .evidenceResults(List.of(evidenceResult))
            .build();
    }

    private GraphRagEvaluationBatchReport singleReportBatch(String batchId,
                                                            String name,
                                                            GraphRagEvaluationReport report) {
        if (report == null) {
            return GraphRagEvaluationBatchReport.empty(batchId, name);
        }
        boolean passed = Boolean.TRUE.equals(report.getPassed());
        double recall = report.getOverallRecall() == null ? 0D : report.getOverallRecall();
        return GraphRagEvaluationBatchReport.builder()
            .batchId(batchId)
            .name(name)
            .evaluationLevel(passed ? GraphRagQualityReport.LEVEL_STRONG : GraphRagQualityReport.LEVEL_WEAK)
            .evaluationScore(recall)
            .summary(passed
                ? "O6 跨文档 GraphRAG baseline 通过。"
                : "O6 跨文档 GraphRAG baseline 未通过：" + StrUtil.blankToDefault(report.getSummary(), "无原因"))
            .suiteCount(1L)
            .passedSuiteCount(passed ? 1L : 0L)
            .failedSuiteCount(passed ? 0L : 1L)
            .expectedEntityCount(0L)
            .matchedEntityCount(0L)
            .expectedRelationCount(0L)
            .matchedRelationCount(0L)
            .expectedEvidenceCount(report.getExpectedEvidenceCount())
            .matchedEvidenceCount(report.getMatchedEvidenceCount())
            .entityRecall(0D)
            .relationRecall(0D)
            .evidenceRecall(recall)
            .overallRecall(recall)
            .passRate(passed ? 1D : 0D)
            .minSuiteRecall(recall)
            .maxSuiteRecall(recall)
            .reports(List.of(report))
            .failedSuites(passed ? List.of() : List.of(GraphRagEvaluationBatchReport.FailedSuite.builder()
                .suiteId(report.getSuiteId())
                .name(report.getName())
                .sourceDocument(report.getSourceDocument())
                .documentId(report.getDocumentId())
                .taskId(report.getTaskId())
                .overallRecall(report.getOverallRecall())
                .evaluationLevel(report.getEvaluationLevel())
                .reason(report.getSummary())
                .build()))
            .build();
    }

    private GraphRagEvaluationSourceBinding bindSource(String sourceDocument, List<SuperAgentDocument> indexedDocuments) {
        return indexedDocuments.stream()
            .filter(document -> matchesSource(document, sourceDocument))
            .findFirst()
            .map(document -> GraphRagEvaluationSourceBinding.builder()
                .sourceDocument(sourceDocument)
                .documentId(document.getId())
                .taskId(document.getLastIndexTaskId())
                .documentName(document.getDocumentName())
                .originalFileName(document.getOriginalFileName())
                .matched(true)
                .build())
            .orElseGet(() -> GraphRagEvaluationSourceBinding.missing(
                sourceDocument,
                "未找到已完成索引构建的 O6 样例文档：" + sourceDocument
            ));
    }

    private List<SuperAgentDocument> activeIndexedDocuments() {
        List<SuperAgentDocument> documents = documentMapper.selectList(new LambdaQueryWrapper<SuperAgentDocument>()
            .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode())
            .eq(SuperAgentDocument::getIndexStatus, DocumentIndexStatusEnum.BUILD_SUCCESS.getCode())
            .isNotNull(SuperAgentDocument::getLastIndexTaskId)
            .orderByDesc(SuperAgentDocument::getEditTime)
            .orderByDesc(SuperAgentDocument::getId));
        return safeStream(documents)
            .filter(this::activeIndexed)
            .sorted(Comparator
                .comparing(SuperAgentDocument::getEditTime, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(SuperAgentDocument::getId, Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    private boolean activeIndexed(SuperAgentDocument document) {
        return document != null
            && Objects.equals(document.getStatus(), BusinessStatus.YES.getCode())
            && Objects.equals(document.getIndexStatus(), DocumentIndexStatusEnum.BUILD_SUCCESS.getCode())
            && document.getLastIndexTaskId() != null;
    }

    private boolean matchesSource(SuperAgentDocument document, String sourceDocument) {
        if (document == null || StrUtil.isBlank(sourceDocument)) {
            return false;
        }
        Set<String> sourceAliases = sourceAliases(sourceDocument);
        return Stream.of(document.getDocumentName(), document.getOriginalFileName())
            .map(this::normalizeName)
            .anyMatch(sourceAliases::contains);
    }

    private Set<String> sourceAliases(String sourceDocument) {
        String normalizedSource = normalizeName(sourceDocument);
        String sourceFileName = normalizeName(fileName(sourceDocument));
        String sourceStem = normalizeName(stripExtension(fileName(sourceDocument)));
        return Stream.of(normalizedSource, sourceFileName, sourceStem)
            .filter(StrUtil::isNotBlank)
            .collect(Collectors.toSet());
    }

    private String fileName(String path) {
        if (StrUtil.isBlank(path)) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        int index = normalized.lastIndexOf('/');
        return index < 0 ? normalized : normalized.substring(index + 1);
    }

    private String stripExtension(String name) {
        if (StrUtil.isBlank(name)) {
            return "";
        }
        int index = name.lastIndexOf('.');
        return index <= 0 ? name : name.substring(0, index);
    }

    private String normalizeName(String value) {
        return StrUtil.blankToDefault(value, "")
            .trim()
            .replace('\\', '/')
            .toLowerCase(Locale.ROOT);
    }

    private Stream<SuperAgentDocument> safeStream(List<SuperAgentDocument> documents) {
        return documents == null ? Stream.empty() : documents.stream();
    }
}
