package org.javaup.ai.manage.support;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.config.DocumentManageProperties;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentKnowledgeBase;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.model.KnowledgeBaseIndexingOptions;
import org.javaup.ai.manage.service.KnowledgeBaseManageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class KnowledgeBaseIndexingConfigResolver {

    private final DocumentManageProperties properties;
    private final SuperAgentDocumentMapper documentMapper;
    private final KnowledgeBaseManageService knowledgeBaseManageService;
    private final ObjectMapper objectMapper;

    @Value("${app.chat.rag.raptor-max-cluster-size:6}")
    private Integer defaultRaptorMaxClusterSize = 6;

    @Value("${app.chat.rag.raptor-max-levels:3}")
    private Integer defaultRaptorMaxLevels = 3;

    @Value("${app.chat.rag.raptor-llm-summary-enabled:true}")
    private Boolean defaultRaptorLlmSummaryEnabled = Boolean.TRUE;

    @Value("${app.chat.rag.raptor-summary-quality-floor:0.42}")
    private Double defaultRaptorSummaryQualityFloor = 0.42D;

    public KnowledgeBaseIndexingConfigResolver(DocumentManageProperties properties) {
        this(properties, null, null);
    }

    @Autowired
    public KnowledgeBaseIndexingConfigResolver(DocumentManageProperties properties,
                                               SuperAgentDocumentMapper documentMapper,
                                               KnowledgeBaseManageService knowledgeBaseManageService) {
        this.properties = properties == null ? new DocumentManageProperties() : properties;
        this.documentMapper = documentMapper;
        this.knowledgeBaseManageService = knowledgeBaseManageService;
        this.objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public KnowledgeBaseIndexingOptions resolve(SuperAgentDocument document) {
        if (document == null || document.getKnowledgeBaseId() == null) {
            return defaults();
        }
        return resolveByKnowledgeBaseId(document.getKnowledgeBaseId());
    }

    public KnowledgeBaseIndexingOptions resolveByDocumentId(Long documentId) {
        if (documentId == null || documentId <= 0 || documentMapper == null) {
            return defaults();
        }
        SuperAgentDocument document = documentMapper.selectById(documentId);
        return resolve(document);
    }

    public KnowledgeBaseIndexingOptions resolveByKnowledgeBaseId(Long knowledgeBaseId) {
        if (knowledgeBaseId == null || knowledgeBaseId <= 0 || knowledgeBaseManageService == null) {
            return defaults();
        }
        try {
            return resolve(knowledgeBaseManageService.requireEnabled(knowledgeBaseId));
        }
        catch (RuntimeException exception) {
            log.warn("知识库解析/索引构建配置读取失败，使用全局默认值: knowledgeBaseId={}, message={}",
                knowledgeBaseId, exception.getMessage());
            return defaults();
        }
    }

    public KnowledgeBaseIndexingOptions resolve(SuperAgentKnowledgeBase knowledgeBase) {
        KnowledgeBaseIndexingOptions options = defaults();
        if (knowledgeBase == null) {
            return options;
        }
        RetrievalConfig retrievalConfig = parseJson(knowledgeBase.getRetrievalConfigJson(), RetrievalConfig.class, knowledgeBase);
        GraphRagConfig graphRagConfig = parseJson(knowledgeBase.getGraphRagConfigJson(), GraphRagConfig.class, knowledgeBase);
        RaptorConfig raptorConfig = parseJson(knowledgeBase.getRaptorConfigJson(), RaptorConfig.class, knowledgeBase);
        applyIndexing(options.getChunk(), retrievalConfig == null ? null : retrievalConfig.getIndexing());
        applyGraphRagBuild(options.getGraphRag(), graphRagConfig == null ? null : graphRagConfig.getBuild());
        applyRaptorBuild(options.getRaptor(), raptorConfig == null ? null : raptorConfig.getBuild());
        normalize(options);
        return options;
    }

    private KnowledgeBaseIndexingOptions defaults() {
        KnowledgeBaseIndexingOptions options = KnowledgeBaseIndexingOptions.fromDefaults(
            properties,
            defaultRaptorMaxClusterSize,
            defaultRaptorMaxLevels,
            defaultRaptorLlmSummaryEnabled,
            defaultRaptorSummaryQualityFloor
        );
        normalize(options);
        return options;
    }

    private void applyIndexing(KnowledgeBaseIndexingOptions.ChunkOptions target, IndexingConfig source) {
        if (target == null || source == null) {
            return;
        }
        copyIfPresent(source.getChildRecursiveMaxChars(), target::setChildRecursiveMaxChars);
        copyIfPresent(source.getChildRecursiveOverlapChars(), target::setChildRecursiveOverlapChars);
        copyIfPresent(source.getChildSemanticMaxChars(), target::setChildSemanticMaxChars);
        copyIfPresent(source.getChildSemanticMinChars(), target::setChildSemanticMinChars);
        copyIfPresent(source.getChildSemanticSimilarityThreshold(), target::setChildSemanticSimilarityThreshold);
        copyIfPresent(source.getParentBlockMaxChars(), target::setParentBlockMaxChars);
        copyIfPresent(source.getParentBlockOverlapChars(), target::setParentBlockOverlapChars);
        copyIfPresent(source.getParentSemanticMaxChars(), target::setParentSemanticMaxChars);
        copyIfPresent(source.getParentSemanticMinChars(), target::setParentSemanticMinChars);
    }

    private void applyGraphRagBuild(KnowledgeBaseIndexingOptions.GraphRagBuildOptions target, GraphRagBuildConfig source) {
        if (target == null || source == null) {
            return;
        }
        copyIfPresent(source.getGraphRagBuildEnabled(), target::setGraphRagBuildEnabled);
    }

    private void applyRaptorBuild(KnowledgeBaseIndexingOptions.RaptorBuildOptions target, RaptorBuildConfig source) {
        if (target == null || source == null) {
            return;
        }
        copyIfPresent(source.getRaptorBuildEnabled(), target::setRaptorBuildEnabled);
        copyIfPresent(source.getRaptorMaxClusterSize(), target::setRaptorMaxClusterSize);
        copyIfPresent(source.getRaptorMaxLevels(), target::setRaptorMaxLevels);
        copyIfPresent(source.getRaptorLlmSummaryEnabled(), target::setRaptorLlmSummaryEnabled);
        copyIfPresent(source.getRaptorSummaryQualityFloor(), target::setRaptorSummaryQualityFloor);
    }

    private void normalize(KnowledgeBaseIndexingOptions options) {
        KnowledgeBaseIndexingOptions.ChunkOptions chunk = options.getChunk();
        chunk.setChildRecursiveMaxChars(clampInt(chunk.getChildRecursiveMaxChars(), 100, 8000, 800));
        chunk.setChildRecursiveOverlapChars(clampInt(chunk.getChildRecursiveOverlapChars(), 0, chunk.getChildRecursiveMaxChars() - 1, 120));
        chunk.setChildSemanticMaxChars(clampInt(chunk.getChildSemanticMaxChars(), 100, 8000, 700));
        chunk.setChildSemanticMinChars(clampInt(chunk.getChildSemanticMinChars(), 80, chunk.getChildSemanticMaxChars(), 240));
        chunk.setChildSemanticSimilarityThreshold(clampDouble(chunk.getChildSemanticSimilarityThreshold(), 0D, 1D, 0.18D));
        chunk.setParentBlockMaxChars(clampInt(chunk.getParentBlockMaxChars(), 300, 20000, 2200));
        chunk.setParentBlockOverlapChars(clampInt(chunk.getParentBlockOverlapChars(), 0, chunk.getParentBlockMaxChars() - 1, 180));
        chunk.setParentSemanticMaxChars(clampInt(chunk.getParentSemanticMaxChars(), 300, 20000, 1600));
        chunk.setParentSemanticMinChars(clampInt(chunk.getParentSemanticMinChars(), 120, chunk.getParentSemanticMaxChars(), 480));

        KnowledgeBaseIndexingOptions.GraphRagBuildOptions graphRag = options.getGraphRag();
        graphRag.setGraphRagBuildEnabled(Boolean.TRUE.equals(graphRag.getGraphRagBuildEnabled()));

        KnowledgeBaseIndexingOptions.RaptorBuildOptions raptor = options.getRaptor();
        raptor.setRaptorBuildEnabled(Boolean.TRUE.equals(raptor.getRaptorBuildEnabled()));
        raptor.setRaptorMaxClusterSize(clampInt(raptor.getRaptorMaxClusterSize(), 2, 50, 6));
        raptor.setRaptorMaxLevels(clampInt(raptor.getRaptorMaxLevels(), 1, 8, 3));
        raptor.setRaptorLlmSummaryEnabled(Boolean.TRUE.equals(raptor.getRaptorLlmSummaryEnabled()));
        raptor.setRaptorSummaryQualityFloor(clampDouble(raptor.getRaptorSummaryQualityFloor(), 0D, 1D, 0.42D));
    }

    private <T> T parseJson(String rawJson, Class<T> targetClass, SuperAgentKnowledgeBase knowledgeBase) {
        if (StrUtil.isBlank(rawJson)) {
            return null;
        }
        try {
            return objectMapper.readValue(rawJson, targetClass);
        }
        catch (JsonProcessingException | RuntimeException exception) {
            log.warn("知识库解析/索引构建配置 JSON 解析失败，将忽略该段配置: knowledgeBaseId={}, knowledgeBaseName={}, targetClass={}",
                knowledgeBase == null ? null : knowledgeBase.getId(),
                knowledgeBase == null ? "" : knowledgeBase.getBaseName(),
                targetClass == null ? "" : targetClass.getSimpleName(),
                exception);
            return null;
        }
    }

    private <T> void copyIfPresent(T value, java.util.function.Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    private int clampInt(Integer value, int min, int max, int defaultValue) {
        int candidate = value == null ? defaultValue : value;
        int effectiveMax = Math.max(min, max);
        return Math.min(Math.max(candidate, min), effectiveMax);
    }

    private double clampDouble(Double value, double min, double max, double defaultValue) {
        double candidate = value == null ? defaultValue : value;
        if (!Double.isFinite(candidate)) {
            candidate = defaultValue;
        }
        return Math.min(Math.max(candidate, min), max);
    }

    @Data
    private static class RetrievalConfig {

        private IndexingConfig indexing;
    }

    @Data
    private static class IndexingConfig {

        private Integer childRecursiveMaxChars;

        private Integer childRecursiveOverlapChars;

        private Integer childSemanticMaxChars;

        private Integer childSemanticMinChars;

        private Double childSemanticSimilarityThreshold;

        private Integer parentBlockMaxChars;

        private Integer parentBlockOverlapChars;

        private Integer parentSemanticMaxChars;

        private Integer parentSemanticMinChars;
    }

    @Data
    private static class GraphRagConfig {

        private GraphRagBuildConfig build;
    }

    @Data
    private static class GraphRagBuildConfig {

        private Boolean graphRagBuildEnabled;
    }

    @Data
    private static class RaptorConfig {

        private RaptorBuildConfig build;
    }

    @Data
    private static class RaptorBuildConfig {

        private Boolean raptorBuildEnabled;

        private Integer raptorMaxClusterSize;

        private Integer raptorMaxLevels;

        private Boolean raptorLlmSummaryEnabled;

        private Double raptorSummaryQualityFloor;
    }
}
