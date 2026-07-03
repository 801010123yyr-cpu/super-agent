package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.rag.model.QueryType;
import org.javaup.ai.chatagent.rag.model.QueryUnderstandingResult;
import org.javaup.ai.chatagent.rag.model.RetrievalIntent;
import org.javaup.ai.chatagent.service.ObservedChatModelService;
import org.javaup.ai.prompt.PromptTemplateNames;
import org.javaup.ai.prompt.PromptTemplateService;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一查询理解入口：把散落在路由、表格、GraphRAG、RAPTOR 的主链路关键词硬判
 * 收口成受控建议。该服务不生成 SQL、不决定最终答案，只输出 Java 主链路可校验的计划信号。
 */
@Slf4j
@Service
public class QueryUnderstandingService {

    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[\\s\\S]*}");
    private static final Pattern SECTION_CODE_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)+)");
    private static final Pattern CHINESE_SECTION_REFERENCE_PATTERN = Pattern.compile("第\\s*([0-9一二三四五六七八九十百]+)\\s*(章|节|小节)");
    private static final Pattern QUOTED_TEXT_PATTERN = Pattern.compile("[“\"']([^”\"']{2,40})[”\"']");

    private static final double ADVISOR_CONFIDENCE_THRESHOLD = 0.72D;

    private final ObjectProvider<ObservedChatModelService> observedChatModelServiceProvider;
    private final ObjectProvider<PromptTemplateService> promptTemplateServiceProvider;
    private final ObjectMapper objectMapper;

    public QueryUnderstandingService(ObjectProvider<ObservedChatModelService> observedChatModelServiceProvider,
                                     ObjectProvider<PromptTemplateService> promptTemplateServiceProvider,
                                     ObjectMapper objectMapper) {
        this.observedChatModelServiceProvider = observedChatModelServiceProvider;
        this.promptTemplateServiceProvider = promptTemplateServiceProvider;
        this.objectMapper = objectMapper;
    }

    public QueryUnderstandingResult understand(String originalQuestion,
                                               String rewrittenQuestion,
                                               List<String> subQuestions,
                                               String historySummary,
                                               String answerRecentTranscript) {
        String routeText = joinText(originalQuestion, rewrittenQuestion, subQuestions);
        QueryUnderstandingResult fallback = deterministicFallback(routeText, subQuestions);
        QueryUnderstandingResult advised = adviseWithModel(originalQuestion, rewrittenQuestion, subQuestions, historySummary, answerRecentTranscript, fallback);
        return validate(advised == null ? fallback : advised, fallback);
    }

    private QueryUnderstandingResult adviseWithModel(String originalQuestion,
                                                     String rewrittenQuestion,
                                                     List<String> subQuestions,
                                                     String historySummary,
                                                     String answerRecentTranscript,
                                                     QueryUnderstandingResult fallback) {
        ObservedChatModelService observedChatModelService = observedChatModelServiceProvider == null ? null : observedChatModelServiceProvider.getIfAvailable();
        PromptTemplateService promptTemplateService = promptTemplateServiceProvider == null ? null : promptTemplateServiceProvider.getIfAvailable();
        if (observedChatModelService == null || promptTemplateService == null) {
            return fallback;
        }
        try {
            String prompt = promptTemplateService.render(PromptTemplateNames.DOCUMENT_QUERY_UNDERSTANDING, Map.of(
                "originalQuestion", StrUtil.blankToDefault(originalQuestion, ""),
                "rewrittenQuestion", StrUtil.blankToDefault(rewrittenQuestion, ""),
                "subQuestions", subQuestions == null ? List.of() : subQuestions,
                "historySummary", StrUtil.blankToDefault(historySummary, ""),
                "answerRecentTranscript", StrUtil.blankToDefault(answerRecentTranscript, "")
            ));
            String raw = observedChatModelService.callText(
                "document_query_understanding",
                null,
                prompt,
                buildCallOptions(),
                null
            );
            if (StrUtil.isBlank(raw)) {
                return fallback;
            }
            return parseAdvice(raw);
        }
        catch (Exception exception) {
            log.warn("查询理解 advisor 失败，回退确定性结构信号: question='{}', message={}",
                StrUtil.blankToDefault(originalQuestion, ""),
                exception.getMessage());
            return fallback;
        }
    }

    private QueryUnderstandingResult parseAdvice(String raw) throws Exception {
        JsonNode root = objectMapper.readTree(extractJsonObject(raw));
        QueryUnderstandingResult result = QueryUnderstandingResult.builder()
            .queryType(parseQueryType(root.path("queryType").asText("")))
            .channels(parseChannels(root.path("channels")))
            .entities(readStringArray(root.path("entities"), 8))
            .sectionAnchors(readStringArray(root.path("sectionAnchors"), 8))
            .tableOps(readStringArray(root.path("tableOps"), 8))
            .negativeBoundary(root.path("negativeBoundary").asBoolean(false))
            .confidence(normalizeConfidence(root.path("confidence").asDouble(0D)))
            .reasons(readStringArray(root.path("reasons"), 8))
            .source("llm-query-understanding")
            .build();
        if (result.getReasons().isEmpty()) {
            result.setReasons(List.of("LLM 查询理解完成。"));
        }
        return result;
    }

    private QueryUnderstandingResult validate(QueryUnderstandingResult advised, QueryUnderstandingResult fallback) {
        if (advised == null) {
            return fallback;
        }
        double confidence = normalizeConfidence(advised.getConfidence());
        QueryType queryType = advised.getQueryType() == null ? QueryType.DOCUMENT_QA : advised.getQueryType();
        LinkedHashSet<RetrievalIntent> channels = new LinkedHashSet<>();
        if (fallback != null && fallback.getChannels() != null) {
            channels.addAll(fallback.getChannels());
        }
        boolean highConfidence = confidence >= ADVISOR_CONFIDENCE_THRESHOLD;
        if (highConfidence && advised.getChannels() != null) {
            channels.addAll(advised.getChannels());
        }
        if (channels.isEmpty()) {
            channels.add(RetrievalIntent.GENERAL);
        }
        QueryType effectiveType = highConfidence ? queryType : fallback == null ? QueryType.DOCUMENT_QA : fallback.getQueryType();
        List<String> reasons = new ArrayList<>();
        if (advised.getReasons() != null) {
            reasons.addAll(advised.getReasons());
        }
        if (!highConfidence) {
            reasons.add("advisor 置信度不足，保留确定性保守计划。");
        }
        QueryUnderstandingResult.QueryUnderstandingResultBuilder builder = QueryUnderstandingResult.builder()
            .queryType(effectiveType == null ? QueryType.DOCUMENT_QA : effectiveType)
            .channels(new ArrayList<>(channels))
            .entities(limitStrings(advised.getEntities(), 8))
            .sectionAnchors(mergeStrings(fallback == null ? null : fallback.getSectionAnchors(), advised.getSectionAnchors(), 8))
            .tableOps(limitStrings(advised.getTableOps(), 8))
            .negativeBoundary(advised.isNegativeBoundary())
            .confidence(confidence)
            .reasons(limitStrings(reasons, 10))
            .source(StrUtil.blankToDefault(advised.getSource(), "query-understanding"));
        return builder.build();
    }

    private QueryUnderstandingResult deterministicFallback(String routeText, List<String> subQuestions) {
        String normalized = safeText(routeText);
        List<String> anchors = extractSectionAnchors(normalized);
        boolean hasMultipleSubQuestions = subQuestions != null && subQuestions.size() > 1;
        boolean strictStructureNavigation = !hasMultipleSubQuestions && looksStrictStructureNavigation(normalized);
        boolean outline = !hasMultipleSubQuestions && looksOutlineNavigation(normalized);
        boolean explicitTableQuery = !hasMultipleSubQuestions && looksExplicitTableQuery(normalized);
        QueryType queryType = explicitTableQuery
            ? QueryType.TABLE_QUERY
            : strictStructureNavigation || outline
            ? QueryType.STRUCTURE_NAVIGATION
            : QueryType.DOCUMENT_QA;
        LinkedHashSet<RetrievalIntent> channels = new LinkedHashSet<>();
        channels.add(RetrievalIntent.GENERAL);
        if (explicitTableQuery) {
            channels.add(RetrievalIntent.TABLE);
        }
        if (strictStructureNavigation || outline || !anchors.isEmpty()) {
            channels.add(RetrievalIntent.STRUCTURE);
        }
        List<String> reasons = new ArrayList<>();
        reasons.add("确定性 fallback 仅识别明确结构导航、明确表格请求和普通文档问答。");
        return QueryUnderstandingResult.builder()
            .queryType(queryType)
            .channels(new ArrayList<>(channels))
            .sectionAnchors(anchors)
            .confidence(strictStructureNavigation || outline || explicitTableQuery ? 0.86D : 0.55D)
            .reasons(reasons)
            .source("java-deterministic-fallback")
            .build();
    }

    private boolean looksStrictStructureNavigation(String question) {
        String normalized = safeText(question);
        if (normalized.isBlank()) {
            return false;
        }
        boolean asksAdjacentSection = containsAny(normalized, List.of("上一节", "下一节", "前一节", "后一节", "上一章", "下一章"));
        boolean asksSectionLocation = containsAny(normalized, List.of("属于哪个章节", "哪个章节", "哪个小节", "哪一节", "哪一章", "章节位置"));
        boolean hasExplicitAnchor = SECTION_CODE_PATTERN.matcher(normalized).find()
            || CHINESE_SECTION_REFERENCE_PATTERN.matcher(normalized).find()
            || QUOTED_TEXT_PATTERN.matcher(normalized).find();
        return (asksAdjacentSection || asksSectionLocation) && hasExplicitAnchor;
    }

    private boolean looksOutlineNavigation(String question) {
        String normalized = safeText(question);
        if (normalized.isBlank()) {
            return false;
        }
        return containsAny(normalized, List.of("包含哪些章节", "都包含哪些章节", "有哪些章节", "有哪些小节", "包含哪些小节", "章节列表", "展开目录"));
    }

    private boolean looksExplicitTableQuery(String question) {
        String normalized = safeText(question);
        if (normalized.isBlank()) {
            return false;
        }
        return containsAny(normalized, List.of("表格", "数据表", "明细表", "统计表"));
    }

    private List<String> extractSectionAnchors(String text) {
        LinkedHashSet<String> anchors = new LinkedHashSet<>();
        Matcher sectionMatcher = SECTION_CODE_PATTERN.matcher(safeText(text));
        while (sectionMatcher.find()) {
            anchors.add(sectionMatcher.group(1));
        }
        Matcher chineseMatcher = CHINESE_SECTION_REFERENCE_PATTERN.matcher(safeText(text));
        while (chineseMatcher.find()) {
            anchors.add(chineseMatcher.group());
        }
        Matcher quotedMatcher = QUOTED_TEXT_PATTERN.matcher(safeText(text));
        while (quotedMatcher.find()) {
            String phrase = quotedMatcher.group(1);
            if (StrUtil.isNotBlank(phrase)) {
                anchors.add(phrase.trim());
            }
        }
        return anchors.stream().limit(8).toList();
    }

    private QueryType parseQueryType(String raw) {
        String normalized = StrUtil.blankToDefault(raw, "").trim().toUpperCase(Locale.ROOT);
        try {
            return normalized.isBlank() ? QueryType.DOCUMENT_QA : QueryType.valueOf(normalized);
        }
        catch (IllegalArgumentException exception) {
            return QueryType.DOCUMENT_QA;
        }
    }

    private List<RetrievalIntent> parseChannels(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        LinkedHashSet<RetrievalIntent> channels = new LinkedHashSet<>();
        for (JsonNode item : node) {
            String normalized = item.asText("").trim().toUpperCase(Locale.ROOT);
            if ("VECTOR".equals(normalized) || "BM25".equals(normalized) || "KEYWORD".equals(normalized)) {
                channels.add(RetrievalIntent.GENERAL);
                continue;
            }
            try {
                channels.add(RetrievalIntent.valueOf(normalized));
            }
            catch (IllegalArgumentException ignored) {
                // 丢弃未知通道，Java 主链路只接受白名单枚举。
            }
        }
        return new ArrayList<>(channels);
    }

    private List<String> readStringArray(JsonNode node, int limit) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("").trim();
            if (StrUtil.isNotBlank(value) && !values.contains(value)) {
                values.add(value);
            }
            if (values.size() >= limit) {
                break;
            }
        }
        return values;
    }

    private List<String> mergeStrings(List<String> first, List<String> second, int limit) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (first != null) {
            first.stream().filter(StrUtil::isNotBlank).forEach(values::add);
        }
        if (second != null) {
            second.stream().filter(StrUtil::isNotBlank).forEach(values::add);
        }
        return values.stream().limit(limit).toList();
    }

    private List<String> limitStrings(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .filter(StrUtil::isNotBlank)
            .distinct()
            .limit(limit)
            .toList();
    }

    private boolean containsAny(String text, List<String> terms) {
        String normalized = safeText(text);
        if (normalized.isBlank() || terms == null || terms.isEmpty()) {
            return false;
        }
        return terms.stream().filter(StrUtil::isNotBlank).anyMatch(normalized::contains);
    }

    private String joinText(String originalQuestion, String rewrittenQuestion, List<String> subQuestions) {
        StringBuilder builder = new StringBuilder();
        appendText(builder, originalQuestion);
        appendText(builder, rewrittenQuestion);
        if (subQuestions != null) {
            subQuestions.forEach(item -> appendText(builder, item));
        }
        return builder.toString().trim();
    }

    private void appendText(StringBuilder builder, String text) {
        if (StrUtil.isBlank(text)) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(' ');
        }
        builder.append(text.trim());
    }

    private ChatOptions buildCallOptions() {
        return OpenAiChatOptions.builder()
            .temperature(0.0D)
            .topP(0.1D)
            .extraBody(Map.of("thinking", false))
            .build();
    }

    private String extractJsonObject(String raw) {
        Matcher matcher = JSON_OBJECT_PATTERN.matcher(raw.trim());
        if (matcher.find()) {
            return matcher.group();
        }
        return raw.trim();
    }

    private double normalizeConfidence(double confidence) {
        if (confidence > 1D) {
            return confidence / 100D;
        }
        if (Double.isNaN(confidence) || Double.isInfinite(confidence)) {
            return 0D;
        }
        return Math.max(0D, Math.min(1D, confidence));
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }
}
