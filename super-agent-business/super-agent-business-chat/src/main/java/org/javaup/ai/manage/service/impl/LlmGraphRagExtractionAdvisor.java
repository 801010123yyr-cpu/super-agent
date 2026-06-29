package org.javaup.ai.manage.service.impl;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.service.ObservedChatModelService;
import org.javaup.ai.manage.model.graph.GraphRagExtractionAdvice;
import org.javaup.ai.manage.model.graph.GraphRagExtractionContext;
import org.javaup.ai.manage.service.GraphRagExtractionAdvisor;
import org.javaup.ai.prompt.PromptTemplateNames;
import org.javaup.ai.prompt.PromptTemplateService;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.graph-rag.extraction", name = "enabled", havingValue = "true")
public class LlmGraphRagExtractionAdvisor implements GraphRagExtractionAdvisor {

    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[\\s\\S]*}");
    private static final int BATCH_CHUNK_LIMIT = 3;
    private static final int BATCH_TOKEN_LIMIT = 2800;
    private static final int CHUNK_METADATA_TOKEN_OVERHEAD = 80;

    @Value("${app.graph-rag.extraction.batch-chunk-limit:" + BATCH_CHUNK_LIMIT + "}")
    private int batchChunkLimit = BATCH_CHUNK_LIMIT;

    @Value("${app.graph-rag.extraction.batch-token-limit:" + BATCH_TOKEN_LIMIT + "}")
    private int batchTokenLimit = BATCH_TOKEN_LIMIT;

    private final ObservedChatModelService observedChatModelService;
    private final PromptTemplateService promptTemplateService;
    private final ObjectMapper objectMapper;

    public LlmGraphRagExtractionAdvisor(ObservedChatModelService observedChatModelService,
                                        PromptTemplateService promptTemplateService,
                                        ObjectMapper objectMapper) {
        this.observedChatModelService = observedChatModelService;
        this.promptTemplateService = promptTemplateService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<GraphRagExtractionAdvice> extract(GraphRagExtractionContext context) {
        if (context == null || context.getChunks() == null || context.getChunks().isEmpty()) {
            return Optional.empty();
        }
        List<List<GraphRagExtractionContext.ChunkItem>> batches = buildBatches(context);
        if (batches.isEmpty()) {
            return Optional.empty();
        }
        List<GraphRagExtractionAdvice> acceptedBatchAdvice = new ArrayList<>();
        List<String> rejectedReasons = new ArrayList<>();
        int failedBatchCount = 0;
        int emptyBatchCount = 0;
        int answeredBatchCount = 0;
        for (int i = 0; i < batches.size(); i++) {
            GraphRagExtractionContext batchContext = GraphRagExtractionContext.builder()
                .documentId(context.getDocumentId())
                .taskId(context.getTaskId())
                .chunks(batches.get(i))
                .build();
            String batchPrefix = "B" + (i + 1) + "_";
            try {
                Optional<GraphRagExtractionAdvice> advice = callBatch(batchContext);
                if (advice.isPresent()) {
                    answeredBatchCount++;
                }
                if (advice.isEmpty()) {
                    emptyBatchCount++;
                    continue;
                }
                GraphRagExtractionAdvice prefixed = prefixBatchAdvice(advice.get(), batchPrefix);
                if (!Boolean.TRUE.equals(prefixed.getGraphable())) {
                    rejectedReasons.add(StrUtil.blankToDefault(prefixed.getReason(), "NOT_GRAPHABLE"));
                    continue;
                }
                if (prefixed.getEntities().isEmpty() && prefixed.getRelations().isEmpty() && prefixed.getEvidences().isEmpty()) {
                    emptyBatchCount++;
                    continue;
                }
                acceptedBatchAdvice.add(prefixed);
            }
            catch (Exception exception) {
                RetryResult retryResult = retryBatchAsSingleChunks(context, batches.get(i), i + 1, batches.size(), acceptedBatchAdvice, rejectedReasons);
                answeredBatchCount += retryResult.answeredCount();
                emptyBatchCount += retryResult.emptyCount();
                if (retryResult.acceptedCount() > 0 || retryResult.answeredCount() > 0) {
                    continue;
                }
                failedBatchCount += Math.max(1, retryResult.failedCount());
                String message = limit(rootCauseMessage(exception), 160);
                rejectedReasons.add("batch=" + (i + 1) + ", reason=" + StrUtil.blankToDefault(message, "UNKNOWN"));
                log.warn("GraphRAG LLM 受控抽取增强批次失败: documentId={}, taskId={}, batch={}/{}, message={}",
                    context.getDocumentId(),
                    context.getTaskId(),
                    i + 1,
                    batches.size(),
                    rootCauseMessage(exception));
            }
        }
        if (!acceptedBatchAdvice.isEmpty()) {
            return Optional.of(mergeBatchAdvice(acceptedBatchAdvice, batches.size(), failedBatchCount, emptyBatchCount, rejectedReasons));
        }
        if (answeredBatchCount == 0 && failedBatchCount > 0) {
            log.warn("GraphRAG LLM 受控抽取增强全部批次无有效响应: documentId={}, taskId={}, batchCount={}, failedBatchCount={}, reasons={}",
                context.getDocumentId(),
                context.getTaskId(),
                batches.size(),
                failedBatchCount,
                rejectedReasons);
            throw new IllegalStateException("GraphRAG LLM 受控抽取增强全部批次无有效响应");
        }
        if (!rejectedReasons.isEmpty()) {
            return Optional.of(GraphRagExtractionAdvice.builder()
                .graphable(false)
                .entities(new ArrayList<>())
                .relations(new ArrayList<>())
                .evidences(new ArrayList<>())
                .confidence(0.0D)
                .reason("分批抽取未发现可采纳图谱信息: " + String.join("; ", rejectedReasons))
                .build());
        }
        return Optional.empty();
    }

    private RetryResult retryBatchAsSingleChunks(GraphRagExtractionContext context,
                                                 List<GraphRagExtractionContext.ChunkItem> chunks,
                                                 int batchIndex,
                                                 int batchCount,
                                                 List<GraphRagExtractionAdvice> acceptedBatchAdvice,
                                                 List<String> rejectedReasons) {
        if (chunks == null || chunks.size() <= 1) {
            return new RetryResult(0, 0, 0, 0);
        }
        int acceptedCount = 0;
        int answeredCount = 0;
        int failedCount = 0;
        int emptyCount = 0;
        for (int i = 0; i < chunks.size(); i++) {
            GraphRagExtractionContext singleChunkContext = GraphRagExtractionContext.builder()
                .documentId(context.getDocumentId())
                .taskId(context.getTaskId())
                .chunks(List.of(chunks.get(i)))
                .build();
            try {
                Optional<GraphRagExtractionAdvice> advice = callBatch(singleChunkContext);
                if (advice.isPresent()) {
                    answeredCount++;
                }
                if (advice.isEmpty()) {
                    emptyCount++;
                    continue;
                }
                GraphRagExtractionAdvice prefixed = prefixBatchAdvice(advice.get(), "B" + batchIndex + "_" + (i + 1) + "_");
                if (!Boolean.TRUE.equals(prefixed.getGraphable())) {
                    rejectedReasons.add("batch=" + batchIndex + ", retryChunk=" + (i + 1)
                        + ", reason=" + StrUtil.blankToDefault(prefixed.getReason(), "NOT_GRAPHABLE"));
                    continue;
                }
                if (prefixed.getEntities().isEmpty() && prefixed.getRelations().isEmpty() && prefixed.getEvidences().isEmpty()) {
                    emptyCount++;
                    continue;
                }
                acceptedBatchAdvice.add(prefixed);
                acceptedCount++;
            }
            catch (Exception retryException) {
                failedCount++;
                rejectedReasons.add("batch=" + batchIndex + ", retryChunk=" + (i + 1)
                    + ", reason=" + limit(rootCauseMessage(retryException), 160));
                log.warn("GraphRAG LLM 受控抽取增强批次拆分重试失败: documentId={}, taskId={}, batch={}/{}, retryChunk={}/{}, message={}",
                    context.getDocumentId(),
                    context.getTaskId(),
                    batchIndex,
                    batchCount,
                    i + 1,
                    chunks.size(),
                    rootCauseMessage(retryException));
            }
        }
        return new RetryResult(acceptedCount, answeredCount, failedCount, emptyCount);
    }

    private Optional<GraphRagExtractionAdvice> callBatch(GraphRagExtractionContext context) {
        try {
            String prompt = promptTemplateService.render(PromptTemplateNames.DOCUMENT_GRAPH_RAG_EXTRACTION, Map.of(
                "context", renderContext(context)
            ));
            String raw = observedChatModelService.callText(
                "document_graph_rag_extraction",
                null,
                prompt,
                buildCallOptions(),
                null
            );
            if (StrUtil.isBlank(raw)) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(extractJsonObject(raw), GraphRagExtractionAdvice.class));
        }
        catch (Exception exception) {
            throw new IllegalStateException("GraphRAG LLM 受控抽取增强批次失败", exception);
        }
    }

    private List<List<GraphRagExtractionContext.ChunkItem>> buildBatches(GraphRagExtractionContext context) {
        List<List<GraphRagExtractionContext.ChunkItem>> batches = new ArrayList<>();
        List<GraphRagExtractionContext.ChunkItem> current = new ArrayList<>();
        int currentTokens = 0;
        for (GraphRagExtractionContext.ChunkItem chunk : context.getChunks()) {
            if (chunk == null || chunk.getChunkId() == null || StrUtil.isBlank(chunk.getText())) {
                continue;
            }
            int chunkTokens = estimateChunkTokens(chunk);
            if (!current.isEmpty()
                && (current.size() >= effectiveBatchChunkLimit() || currentTokens + chunkTokens > effectiveBatchTokenLimit())) {
                batches.add(current);
                current = new ArrayList<>();
                currentTokens = 0;
            }
            current.add(chunk);
            currentTokens += chunkTokens;
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    private int effectiveBatchChunkLimit() {
        return Math.max(1, batchChunkLimit);
    }

    private int effectiveBatchTokenLimit() {
        return Math.max(512, batchTokenLimit);
    }

    private int estimateChunkTokens(GraphRagExtractionContext.ChunkItem chunk) {
        String text = String.join("\n",
            StrUtil.blankToDefault(chunk.getTitle(), ""),
            StrUtil.blankToDefault(chunk.getSectionPath(), ""),
            StrUtil.blankToDefault(chunk.getChunkType(), ""),
            StrUtil.blankToDefault(chunk.getPageRange(), ""),
            StrUtil.blankToDefault(chunk.getText(), "")
        );
        return Math.max(1, (int) Math.ceil(text.trim().length() / 4.0D)) + CHUNK_METADATA_TOKEN_OVERHEAD;
    }

    private GraphRagExtractionAdvice prefixBatchAdvice(GraphRagExtractionAdvice advice, String prefix) {
        if (advice == null) {
            return null;
        }
        Map<String, String> entityIdMap = new LinkedHashMap<>();
        Map<String, String> relationIdMap = new LinkedHashMap<>();
        List<GraphRagExtractionAdvice.EntityItem> entities = new ArrayList<>();
        for (GraphRagExtractionAdvice.EntityItem entity : safeList(advice.getEntities())) {
            if (entity == null || StrUtil.isBlank(entity.getId())) {
                continue;
            }
            String mappedId = prefix + entity.getId();
            entityIdMap.put(entity.getId(), mappedId);
            entities.add(GraphRagExtractionAdvice.EntityItem.builder()
                .id(mappedId)
                .name(entity.getName())
                .normalizedName(entity.getNormalizedName())
                .entityType(entity.getEntityType())
                .aliases(copyList(entity.getAliases()))
                .description(entity.getDescription())
                .confidence(entity.getConfidence())
                .sourceChunkIds(copyList(entity.getSourceChunkIds()))
                .build());
        }

        List<GraphRagExtractionAdvice.RelationItem> relations = new ArrayList<>();
        for (GraphRagExtractionAdvice.RelationItem relation : safeList(advice.getRelations())) {
            if (relation == null || StrUtil.isBlank(relation.getId())) {
                continue;
            }
            String sourceEntityId = entityIdMap.get(relation.getSourceEntityId());
            String targetEntityId = entityIdMap.get(relation.getTargetEntityId());
            if (StrUtil.isBlank(sourceEntityId) || StrUtil.isBlank(targetEntityId)) {
                continue;
            }
            String mappedId = prefix + relation.getId();
            relationIdMap.put(relation.getId(), mappedId);
            relations.add(GraphRagExtractionAdvice.RelationItem.builder()
                .id(mappedId)
                .sourceEntityId(sourceEntityId)
                .targetEntityId(targetEntityId)
                .relationType(relation.getRelationType())
                .supportMode(relation.getSupportMode())
                .predicateQuoteText(relation.getPredicateQuoteText())
                .relationTypeReason(relation.getRelationTypeReason())
                .description(relation.getDescription())
                .weight(relation.getWeight())
                .confidence(relation.getConfidence())
                .build());
        }

        List<GraphRagExtractionAdvice.EvidenceItem> evidences = new ArrayList<>();
        for (GraphRagExtractionAdvice.EvidenceItem evidence : safeList(advice.getEvidences())) {
            if (evidence == null || StrUtil.isBlank(evidence.getId())) {
                continue;
            }
            String entityId = StrUtil.isBlank(evidence.getEntityId()) ? null : entityIdMap.get(evidence.getEntityId());
            String relationId = StrUtil.isBlank(evidence.getRelationId()) ? null : relationIdMap.get(evidence.getRelationId());
            if (StrUtil.isBlank(entityId) && StrUtil.isBlank(relationId)) {
                continue;
            }
            evidences.add(GraphRagExtractionAdvice.EvidenceItem.builder()
                .id(prefix + evidence.getId())
                .entityId(entityId)
                .relationId(relationId)
                .chunkId(evidence.getChunkId())
                .quoteText(evidence.getQuoteText())
                .confidence(evidence.getConfidence())
                .build());
        }

        return GraphRagExtractionAdvice.builder()
            .graphable(advice.getGraphable())
            .entities(entities)
            .relations(relations)
            .evidences(evidences)
            .confidence(advice.getConfidence())
            .reason(advice.getReason())
            .build();
    }

    private GraphRagExtractionAdvice mergeBatchAdvice(List<GraphRagExtractionAdvice> batchAdvice,
                                                      int batchCount,
                                                      int failedBatchCount,
                                                      int emptyBatchCount,
                                                      List<String> rejectedReasons) {
        List<GraphRagExtractionAdvice.EntityItem> entities = new ArrayList<>();
        List<GraphRagExtractionAdvice.RelationItem> relations = new ArrayList<>();
        List<GraphRagExtractionAdvice.EvidenceItem> evidences = new ArrayList<>();
        double confidenceSum = 0D;
        int confidenceCount = 0;
        List<String> reasons = new ArrayList<>();
        for (GraphRagExtractionAdvice advice : batchAdvice) {
            if (advice == null) {
                continue;
            }
            entities.addAll(safeList(advice.getEntities()));
            relations.addAll(safeList(advice.getRelations()));
            evidences.addAll(safeList(advice.getEvidences()));
            if (advice.getConfidence() != null) {
                confidenceSum += bounded(advice.getConfidence());
                confidenceCount++;
            }
            if (StrUtil.isNotBlank(advice.getReason())) {
                reasons.add(advice.getReason());
            }
        }
        if (!rejectedReasons.isEmpty()) {
            reasons.add("batchRejected=" + rejectedReasons);
        }
        String reason = "batchedGraphRagExtraction: batchCount=" + batchCount
            + ", successBatchCount=" + batchAdvice.size()
            + ", failedBatchCount=" + failedBatchCount
            + ", emptyBatchCount=" + emptyBatchCount
            + (reasons.isEmpty() ? "" : ", reasons=" + String.join("; ", reasons));
        return GraphRagExtractionAdvice.builder()
            .graphable(true)
            .entities(entities)
            .relations(relations)
            .evidences(evidences)
            .confidence(confidenceCount == 0 ? 0.0D : rounded(confidenceSum / confidenceCount))
            .reason(limit(reason, 1000))
            .build();
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private <T> List<T> copyList(List<T> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }

    private double bounded(Double value) {
        if (value == null) {
            return 0D;
        }
        return Math.max(0D, Math.min(1D, value));
    }

    private double rounded(double value) {
        return Math.round(value * 1000D) / 1000D;
    }

    private String limit(String value, int maxLength) {
        if (value == null || maxLength <= 0) {
            return value;
        }
        return StrUtil.maxLength(value, maxLength);
    }

    private String rootCauseMessage(Throwable exception) {
        if (exception == null) {
            return "";
        }
        Throwable current = exception;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return StrUtil.blankToDefault(current.getMessage(), exception.getMessage());
    }

    private record RetryResult(int acceptedCount, int answeredCount, int failedCount, int emptyCount) {
    }

    private ChatOptions buildCallOptions() {
        return OpenAiChatOptions.builder()
            .temperature(0.0D)
            .topP(0.1D)
            .extraBody(Map.of("thinking", false))
            .build();
    }

    private String renderContext(GraphRagExtractionContext context) throws com.fasterxml.jackson.core.JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", context.getDocumentId());
        payload.put("taskId", context.getTaskId());
        payload.put("chunks", renderChunks(context));
        return objectMapper.writeValueAsString(payload);
    }

    private List<Map<String, Object>> renderChunks(GraphRagExtractionContext context) {
        return (context.getChunks() == null ? List.<GraphRagExtractionContext.ChunkItem>of() : context.getChunks()).stream()
            .filter(chunk -> chunk != null && chunk.getChunkId() != null && StrUtil.isNotBlank(chunk.getText()))
            .map(chunk -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("chunkId", chunk.getChunkId());
                item.put("parentBlockId", chunk.getParentBlockId());
                item.put("chunkNo", chunk.getChunkNo());
                item.put("chunkType", StrUtil.blankToDefault(chunk.getChunkType(), ""));
                item.put("title", StrUtil.blankToDefault(chunk.getTitle(), ""));
                item.put("sectionPath", StrUtil.blankToDefault(chunk.getSectionPath(), ""));
                item.put("pageNo", chunk.getPageNo());
                item.put("pageRange", StrUtil.blankToDefault(chunk.getPageRange(), ""));
                item.put("text", StrUtil.maxLength(StrUtil.blankToDefault(chunk.getText(), ""), 900));
                return item;
            })
            .toList();
    }

    private String extractJsonObject(String raw) {
        Matcher matcher = JSON_OBJECT_PATTERN.matcher(raw.trim());
        if (matcher.find()) {
            return matcher.group();
        }
        return raw.trim();
    }
}
