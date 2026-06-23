package org.javaup.ai.manage.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.model.graph.GraphRagEvaluationBaselineSuites;
import org.javaup.ai.manage.model.graph.GraphRagEvaluationBatchReport;
import org.javaup.ai.manage.model.graph.GraphRagEvaluationSourceBinding;
import org.javaup.ai.manage.model.graph.GraphRagEvaluationSuite;
import org.javaup.ai.manage.service.GraphRagEvaluationBaselineService;
import org.javaup.ai.manage.service.GraphRagEvaluationService;
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

    public GraphRagEvaluationBaselineServiceImpl(SuperAgentDocumentMapper documentMapper,
                                                 GraphRagEvaluationService evaluationService) {
        this.documentMapper = documentMapper;
        this.evaluationService = evaluationService;
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

    private Map<String, GraphRagEvaluationSourceBinding> o6LlmNerSourceBindings() {
        List<SuperAgentDocument> indexedDocuments = activeIndexedDocuments();
        Map<String, GraphRagEvaluationSourceBinding> result = new LinkedHashMap<>();
        for (String sourceDocument : List.of(
            GraphRagEvaluationBaselineSuites.SOURCE_PRODUCTION_RELEASE,
            GraphRagEvaluationBaselineSuites.SOURCE_CUSTOMER_DATA_ACCESS
        )) {
            result.put(sourceDocument, bindSource(sourceDocument, indexedDocuments));
        }
        return result;
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
