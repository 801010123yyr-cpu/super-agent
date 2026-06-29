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
import java.util.Arrays;
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
    private static final int MAX_ENTITIES_PER_CALL = 8;
    private static final int MAX_RELATIONS_PER_CALL = 6;
    private static final int MAX_EVIDENCES_PER_CALL = 8;
    private static final int MAX_QUOTE_CHARS = 180;
    private static final int MAX_REASON_CHARS = 240;
    private static final int MAX_UNIT_TEXT_CHARS = 420;

    @Value("${app.graph-rag.extraction.batch-chunk-limit:" + BATCH_CHUNK_LIMIT + "}")
    private int batchChunkLimit = BATCH_CHUNK_LIMIT;

    @Value("${app.graph-rag.extraction.batch-token-limit:" + BATCH_TOKEN_LIMIT + "}")
    private int batchTokenLimit = BATCH_TOKEN_LIMIT;

    @Value("${app.graph-rag.extraction.max-entities-per-call:" + MAX_ENTITIES_PER_CALL + "}")
    private int maxEntitiesPerCall = MAX_ENTITIES_PER_CALL;

    @Value("${app.graph-rag.extraction.max-relations-per-call:" + MAX_RELATIONS_PER_CALL + "}")
    private int maxRelationsPerCall = MAX_RELATIONS_PER_CALL;

    @Value("${app.graph-rag.extraction.max-evidences-per-call:" + MAX_EVIDENCES_PER_CALL + "}")
    private int maxEvidencesPerCall = MAX_EVIDENCES_PER_CALL;

    @Value("${app.graph-rag.extraction.max-quote-chars:" + MAX_QUOTE_CHARS + "}")
    private int maxQuoteChars = MAX_QUOTE_CHARS;

    @Value("${app.graph-rag.extraction.max-reason-chars:" + MAX_REASON_CHARS + "}")
    private int maxReasonChars = MAX_REASON_CHARS;

    @Value("${app.graph-rag.extraction.max-unit-text-chars:" + MAX_UNIT_TEXT_CHARS + "}")
    private int maxUnitTextChars = MAX_UNIT_TEXT_CHARS;

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
        List<GraphRagExtractionContext.ChunkItem> extractionUnits = buildExtractionUnits(context);
        List<List<GraphRagExtractionContext.ChunkItem>> batches = buildBatches(extractionUnits);
        if (batches.isEmpty()) {
            return Optional.empty();
        }
        List<GraphRagExtractionAdvice> acceptedBatchAdvice = new ArrayList<>();
        List<String> rejectedReasons = new ArrayList<>();
        ExtractionStats stats = new ExtractionStats(batches.size(), extractionUnits.size());
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
                BatchCallResult callResult = callBatch(batchContext);
                stats.add(callResult);
                Optional<GraphRagExtractionAdvice> advice = callResult.advice();
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
                stats.acceptedAdviceCount++;
            }
            catch (Exception exception) {
                stats.retryBatchCount++;
                RetryResult retryResult = retryBatchAsSingleChunks(context, batches.get(i), i + 1, batches.size(), acceptedBatchAdvice, rejectedReasons, stats);
                answeredBatchCount += retryResult.answeredCount();
                emptyBatchCount += retryResult.emptyCount();
                if (retryResult.acceptedCount() > 0) {
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
            return Optional.of(mergeBatchAdvice(acceptedBatchAdvice, stats, failedBatchCount, emptyBatchCount, rejectedReasons));
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
                .reason(statsPrefix(stats, failedBatchCount, emptyBatchCount)
                    + ", result=分批抽取未发现可采纳图谱信息: " + String.join("; ", rejectedReasons))
                .build());
        }
        return Optional.empty();
    }

    private RetryResult retryBatchAsSingleChunks(GraphRagExtractionContext context,
                                                 List<GraphRagExtractionContext.ChunkItem> chunks,
                                                 int batchIndex,
                                                 int batchCount,
                                                 List<GraphRagExtractionAdvice> acceptedBatchAdvice,
                                                 List<String> rejectedReasons,
                                                 ExtractionStats stats) {
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
                BatchCallResult callResult = callBatch(singleChunkContext);
                stats.add(callResult);
                Optional<GraphRagExtractionAdvice> advice = callResult.advice();
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
                stats.acceptedAdviceCount++;
            }
            catch (Exception retryException) {
                failedCount++;
                stats.failedUnitCount++;
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

    private BatchCallResult callBatch(GraphRagExtractionContext context) {
        try {
            String prompt = promptTemplateService.render(PromptTemplateNames.DOCUMENT_GRAPH_RAG_EXTRACTION, Map.of(
                "context", renderContext(context),
                "maxEntitiesPerCall", effectiveMaxEntitiesPerCall(),
                "maxRelationsPerCall", effectiveMaxRelationsPerCall(),
                "maxEvidencesPerCall", effectiveMaxEvidencesPerCall(),
                "maxQuoteChars", effectiveMaxQuoteChars(),
                "maxReasonChars", effectiveMaxReasonChars()
            ));
            String raw = observedChatModelService.callText(
                "document_graph_rag_extraction",
                null,
                prompt,
                buildCallOptions(),
                null
            );
            if (StrUtil.isBlank(raw)) {
                return BatchCallResult.empty();
            }
            try {
                return BatchCallResult.strict(Optional.of(normalizeAdvice(
                    objectMapper.readValue(extractJsonObject(raw), GraphRagExtractionAdvice.class)
                )));
            }
            catch (Exception parseException) {
                Optional<GraphRagExtractionAdvice> salvaged = salvagePartialAdvice(raw);
                if (salvaged.isPresent()) {
                    return BatchCallResult.salvaged(salvaged);
                }
                throw parseException;
            }
        }
        catch (Exception exception) {
            throw new IllegalStateException("GraphRAG LLM 受控抽取增强批次失败", exception);
        }
    }

    private List<GraphRagExtractionContext.ChunkItem> buildExtractionUnits(GraphRagExtractionContext context) {
        List<GraphRagExtractionContext.ChunkItem> units = new ArrayList<>();
        for (GraphRagExtractionContext.ChunkItem chunk : context.getChunks()) {
            if (chunk == null || chunk.getChunkId() == null || StrUtil.isBlank(chunk.getText())) {
                continue;
            }
            units.addAll(splitChunkIntoUnits(chunk));
        }
        return units;
    }

    private List<GraphRagExtractionContext.ChunkItem> splitChunkIntoUnits(GraphRagExtractionContext.ChunkItem chunk) {
        List<String> segments = splitStructuredSegments(chunk.getText());
        if (segments.isEmpty()) {
            return List.of(copyChunkWithText(chunk, StrUtil.maxLength(chunk.getText(), effectiveMaxUnitTextChars())));
        }
        return segments.stream()
            .map(segment -> copyChunkWithText(chunk, segment))
            .toList();
    }

    private List<String> splitStructuredSegments(String text) {
        String normalized = StrUtil.blankToDefault(text, "").replace("\r\n", "\n").replace('\r', '\n').trim();
        if (StrUtil.isBlank(normalized)) {
            return List.of();
        }
        List<String> lines = Arrays.stream(normalized.split("\n"))
            .map(String::trim)
            .filter(StrUtil::isNotBlank)
            .filter(line -> !isMarkdownTableSeparator(line))
            .toList();
        long structuredLineCount = lines.stream().filter(this::isStructuredLine).count();
        if (lines.size() >= 2 && structuredLineCount >= 2) {
            List<String> segments = new ArrayList<>();
            for (String line : lines) {
                segments.addAll(splitLongSegment(line));
            }
            return segments;
        }
        return splitLongSegment(normalized);
    }

    private boolean isStructuredLine(String line) {
        if (StrUtil.isBlank(line)) {
            return false;
        }
        String trimmed = line.trim();
        return trimmed.startsWith("|")
            || trimmed.startsWith("- ")
            || trimmed.startsWith("* ")
            || trimmed.startsWith("> ")
            || trimmed.matches("^\\d+[\\.、\\)]\\s*.+")
            || trimmed.contains("：")
            || trimmed.contains(":");
    }

    private boolean isMarkdownTableSeparator(String line) {
        return StrUtil.isNotBlank(line) && line.trim().matches("^\\|?[-:|\\s]+\\|?$");
    }

    private List<String> splitLongSegment(String segment) {
        String normalized = StrUtil.blankToDefault(segment, "").trim();
        if (StrUtil.isBlank(normalized)) {
            return List.of();
        }
        int limit = effectiveMaxUnitTextChars();
        if (normalized.length() <= limit) {
            return List.of(normalized);
        }
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String part : normalized.split("(?<=[。；;])")) {
            String trimmed = part.trim();
            if (StrUtil.isBlank(trimmed)) {
                continue;
            }
            if (!current.isEmpty() && current.length() + trimmed.length() > limit) {
                result.add(current.toString().trim());
                current.setLength(0);
            }
            if (trimmed.length() > limit) {
                result.add(StrUtil.maxLength(trimmed, limit));
                continue;
            }
            current.append(trimmed);
        }
        if (!current.isEmpty()) {
            result.add(current.toString().trim());
        }
        return result.isEmpty() ? List.of(StrUtil.maxLength(normalized, limit)) : result;
    }

    private GraphRagExtractionContext.ChunkItem copyChunkWithText(GraphRagExtractionContext.ChunkItem chunk, String text) {
        return GraphRagExtractionContext.ChunkItem.builder()
            .chunkId(chunk.getChunkId())
            .parentBlockId(chunk.getParentBlockId())
            .chunkNo(chunk.getChunkNo())
            .chunkType(chunk.getChunkType())
            .title(chunk.getTitle())
            .sectionPath(chunk.getSectionPath())
            .pageNo(chunk.getPageNo())
            .pageRange(chunk.getPageRange())
            .text(StrUtil.maxLength(StrUtil.blankToDefault(text, ""), effectiveMaxUnitTextChars()))
            .build();
    }

    private List<List<GraphRagExtractionContext.ChunkItem>> buildBatches(List<GraphRagExtractionContext.ChunkItem> units) {
        List<List<GraphRagExtractionContext.ChunkItem>> batches = new ArrayList<>();
        List<GraphRagExtractionContext.ChunkItem> current = new ArrayList<>();
        int currentTokens = 0;
        for (GraphRagExtractionContext.ChunkItem chunk : units) {
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

    private int effectiveMaxEntitiesPerCall() {
        return Math.max(1, maxEntitiesPerCall);
    }

    private int effectiveMaxRelationsPerCall() {
        return Math.max(0, maxRelationsPerCall);
    }

    private int effectiveMaxEvidencesPerCall() {
        return Math.max(0, maxEvidencesPerCall);
    }

    private int effectiveMaxQuoteChars() {
        return Math.max(40, maxQuoteChars);
    }

    private int effectiveMaxReasonChars() {
        return Math.max(40, maxReasonChars);
    }

    private int effectiveMaxUnitTextChars() {
        return Math.max(120, maxUnitTextChars);
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
                                                      ExtractionStats stats,
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
        String reason = statsPrefix(stats, failedBatchCount, emptyBatchCount)
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

    private String statsPrefix(ExtractionStats stats, int failedBatchCount, int emptyBatchCount) {
        return "batchedGraphRagExtraction: batchCount=" + stats.batchCount
            + ", unitCount=" + stats.unitCount
            + ", successBatchCount=" + stats.acceptedAdviceCount
            + ", acceptedAdviceCount=" + stats.acceptedAdviceCount
            + ", failedBatchCount=" + failedBatchCount
            + ", retryBatchCount=" + stats.retryBatchCount
            + ", failedUnitCount=" + stats.failedUnitCount
            + ", salvagedJsonCount=" + stats.salvagedJsonCount
            + ", truncatedJsonCount=" + stats.truncatedJsonCount
            + ", emptyBatchCount=" + emptyBatchCount;
    }

    private GraphRagExtractionAdvice normalizeAdvice(GraphRagExtractionAdvice advice) {
        if (advice == null) {
            return null;
        }
        List<GraphRagExtractionAdvice.EntityItem> entities = safeList(advice.getEntities()).stream()
            .filter(entity -> entity != null && StrUtil.isNotBlank(entity.getId()))
            .limit(effectiveMaxEntitiesPerCall())
            .map(this::normalizeEntity)
            .toList();
        List<GraphRagExtractionAdvice.RelationItem> relations = safeList(advice.getRelations()).stream()
            .filter(relation -> relation != null && StrUtil.isNotBlank(relation.getId()))
            .limit(effectiveMaxRelationsPerCall())
            .map(this::normalizeRelation)
            .toList();
        List<GraphRagExtractionAdvice.EvidenceItem> evidences = safeList(advice.getEvidences()).stream()
            .filter(evidence -> evidence != null && StrUtil.isNotBlank(evidence.getId()))
            .limit(effectiveMaxEvidencesPerCall())
            .map(this::normalizeEvidence)
            .toList();
        return GraphRagExtractionAdvice.builder()
            .graphable(advice.getGraphable())
            .entities(new ArrayList<>(entities))
            .relations(new ArrayList<>(relations))
            .evidences(new ArrayList<>(evidences))
            .confidence(advice.getConfidence())
            .reason(limit(advice.getReason(), effectiveMaxReasonChars()))
            .build();
    }

    private GraphRagExtractionAdvice.EntityItem normalizeEntity(GraphRagExtractionAdvice.EntityItem entity) {
        return GraphRagExtractionAdvice.EntityItem.builder()
            .id(entity.getId())
            .name(limit(entity.getName(), 120))
            .normalizedName(limit(entity.getNormalizedName(), 120))
            .entityType(limit(entity.getEntityType(), 64))
            .aliases(copyList(entity.getAliases()).stream().filter(StrUtil::isNotBlank).limit(5).map(alias -> limit(alias, 80)).toList())
            .description(limit(entity.getDescription(), effectiveMaxReasonChars()))
            .confidence(entity.getConfidence())
            .sourceChunkIds(copyList(entity.getSourceChunkIds()))
            .build();
    }

    private GraphRagExtractionAdvice.RelationItem normalizeRelation(GraphRagExtractionAdvice.RelationItem relation) {
        return GraphRagExtractionAdvice.RelationItem.builder()
            .id(relation.getId())
            .sourceEntityId(relation.getSourceEntityId())
            .targetEntityId(relation.getTargetEntityId())
            .relationType(limit(relation.getRelationType(), 64))
            .supportMode(limit(relation.getSupportMode(), 64))
            .predicateQuoteText(limit(relation.getPredicateQuoteText(), effectiveMaxQuoteChars()))
            .relationTypeReason(limit(relation.getRelationTypeReason(), effectiveMaxReasonChars()))
            .description(limit(relation.getDescription(), effectiveMaxReasonChars()))
            .weight(relation.getWeight())
            .confidence(relation.getConfidence())
            .build();
    }

    private GraphRagExtractionAdvice.EvidenceItem normalizeEvidence(GraphRagExtractionAdvice.EvidenceItem evidence) {
        return GraphRagExtractionAdvice.EvidenceItem.builder()
            .id(evidence.getId())
            .entityId(evidence.getEntityId())
            .relationId(evidence.getRelationId())
            .chunkId(evidence.getChunkId())
            .quoteText(limit(evidence.getQuoteText(), effectiveMaxQuoteChars()))
            .confidence(evidence.getConfidence())
            .build();
    }

    private Optional<GraphRagExtractionAdvice> salvagePartialAdvice(String raw) {
        if (StrUtil.isBlank(raw)) {
            return Optional.empty();
        }
        List<GraphRagExtractionAdvice.EntityItem> entities = readCompleteArrayItems(raw, "entities",
            GraphRagExtractionAdvice.EntityItem.class, effectiveMaxEntitiesPerCall());
        List<GraphRagExtractionAdvice.RelationItem> relations = readCompleteArrayItems(raw, "relations",
            GraphRagExtractionAdvice.RelationItem.class, effectiveMaxRelationsPerCall());
        List<GraphRagExtractionAdvice.EvidenceItem> evidences = readCompleteArrayItems(raw, "evidences",
            GraphRagExtractionAdvice.EvidenceItem.class, effectiveMaxEvidencesPerCall());
        if (entities.isEmpty() && relations.isEmpty() && evidences.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(normalizeAdvice(GraphRagExtractionAdvice.builder()
            .graphable(true)
            .entities(new ArrayList<>(entities))
            .relations(new ArrayList<>(relations))
            .evidences(new ArrayList<>(evidences))
            .confidence(0.75D)
            .reason("partialJsonSalvage: recovered complete JSON items from truncated advisor output")
            .build()));
    }

    private <T> List<T> readCompleteArrayItems(String raw, String fieldName, Class<T> itemType, int limit) {
        if (limit <= 0 || StrUtil.isBlank(raw)) {
            return List.of();
        }
        String marker = "\"" + fieldName + "\"";
        int fieldIndex = raw.indexOf(marker);
        if (fieldIndex < 0) {
            return List.of();
        }
        int arrayStart = raw.indexOf('[', fieldIndex + marker.length());
        if (arrayStart < 0) {
            return List.of();
        }
        List<T> result = new ArrayList<>();
        int cursor = arrayStart + 1;
        while (cursor < raw.length() && result.size() < limit) {
            cursor = skipWhitespaceAndComma(raw, cursor);
            if (cursor >= raw.length() || raw.charAt(cursor) == ']') {
                break;
            }
            if (raw.charAt(cursor) != '{') {
                break;
            }
            int objectEnd = findCompleteJsonObjectEnd(raw, cursor);
            if (objectEnd < 0) {
                break;
            }
            try {
                result.add(objectMapper.readValue(raw.substring(cursor, objectEnd + 1), itemType));
            }
            catch (Exception ignored) {
                // Keep scanning. A malformed item should not hide later complete items.
            }
            cursor = objectEnd + 1;
        }
        return result;
    }

    private int skipWhitespaceAndComma(String raw, int cursor) {
        int current = cursor;
        while (current < raw.length()) {
            char value = raw.charAt(current);
            if (!Character.isWhitespace(value) && value != ',') {
                return current;
            }
            current++;
        }
        return current;
    }

    private int findCompleteJsonObjectEnd(String raw, int objectStart) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = objectStart; i < raw.length(); i++) {
            char value = raw.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (value == '\\') {
                escaped = true;
                continue;
            }
            if (value == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (value == '{') {
                depth++;
            }
            else if (value == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
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

    private record BatchCallResult(Optional<GraphRagExtractionAdvice> advice,
                                   boolean salvaged,
                                   int truncatedJsonCount) {

        private static BatchCallResult empty() {
            return new BatchCallResult(Optional.empty(), false, 0);
        }

        private static BatchCallResult strict(Optional<GraphRagExtractionAdvice> advice) {
            return new BatchCallResult(advice, false, 0);
        }

        private static BatchCallResult salvaged(Optional<GraphRagExtractionAdvice> advice) {
            return new BatchCallResult(advice, true, 1);
        }
    }

    private static class ExtractionStats {

        private final int batchCount;
        private final int unitCount;
        private int retryBatchCount;
        private int failedUnitCount;
        private int acceptedAdviceCount;
        private int salvagedJsonCount;
        private int truncatedJsonCount;

        private ExtractionStats(int batchCount, int unitCount) {
            this.batchCount = batchCount;
            this.unitCount = unitCount;
        }

        private void add(BatchCallResult result) {
            if (result == null) {
                return;
            }
            if (result.salvaged()) {
                salvagedJsonCount++;
            }
            truncatedJsonCount += Math.max(0, result.truncatedJsonCount());
        }
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
                item.put("text", StrUtil.maxLength(StrUtil.blankToDefault(chunk.getText(), ""), effectiveMaxUnitTextChars()));
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
