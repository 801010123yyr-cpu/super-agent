package org.javaup.ai.manage.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.service.ObservedChatModelService;
import org.javaup.ai.manage.model.graph.GraphRagCommunityReportAdvice;
import org.javaup.ai.manage.model.graph.GraphRagCommunityReportContext;
import org.javaup.ai.manage.service.GraphRagCommunityReportAdvisor;
import org.javaup.ai.prompt.PromptTemplateNames;
import org.javaup.ai.prompt.PromptTemplateService;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.graph-rag.community-report", name = "enabled", havingValue = "true")
public class LlmGraphRagCommunityReportAdvisor implements GraphRagCommunityReportAdvisor {

    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[\\s\\S]*}");

    private final ObservedChatModelService observedChatModelService;
    private final PromptTemplateService promptTemplateService;
    private final ObjectMapper objectMapper;

    public LlmGraphRagCommunityReportAdvisor(ObservedChatModelService observedChatModelService,
                                             PromptTemplateService promptTemplateService,
                                             ObjectMapper objectMapper) {
        this.observedChatModelService = observedChatModelService;
        this.promptTemplateService = promptTemplateService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<GraphRagCommunityReportAdvice> generateReport(GraphRagCommunityReportContext context) {
        if (context == null || CollUtil.isEmpty(context.getEntities()) || CollUtil.isEmpty(context.getEvidences())) {
            return Optional.empty();
        }
        try {
            String prompt = promptTemplateService.render(PromptTemplateNames.DOCUMENT_GRAPH_RAG_COMMUNITY_REPORT, Map.of(
                "context", renderContext(context)
            ));
            String raw = observedChatModelService.callText(
                "document_graph_rag_community_report",
                null,
                prompt,
                buildCallOptions(),
                null
            );
            if (StrUtil.isBlank(raw)) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(extractJsonObject(raw), GraphRagCommunityReportAdvice.class));
        }
        catch (Exception exception) {
            log.warn("GraphRAG LLM 社区报告生成失败: communityId={}, message={}",
                context.getCommunityId(),
                exception.getMessage());
            throw new IllegalStateException("GraphRAG LLM 社区报告生成失败", exception);
        }
    }

    private ChatOptions buildCallOptions() {
        return OpenAiChatOptions.builder()
            .temperature(0.1D)
            .topP(0.2D)
            .extraBody(Map.of("thinking", false))
            .build();
    }

    private String renderContext(GraphRagCommunityReportContext context) throws com.fasterxml.jackson.core.JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("communityId", context.getCommunityId());
        payload.put("communityNo", context.getCommunityNo());
        payload.put("originalTitle", StrUtil.blankToDefault(context.getOriginalTitle(), ""));
        payload.put("originalSummary", StrUtil.blankToDefault(context.getOriginalSummary(), ""));
        payload.put("rankBoost", context.getRankBoost());
        payload.put("entities", renderEntities(context));
        payload.put("relations", renderRelations(context));
        payload.put("evidences", renderEvidences(context));
        return objectMapper.writeValueAsString(payload);
    }

    private List<Map<String, Object>> renderEntities(GraphRagCommunityReportContext context) {
        return (context.getEntities() == null ? List.<GraphRagCommunityReportContext.EntityItem>of() : context.getEntities()).stream()
            .filter(entity -> entity != null && entity.getEntityId() != null)
            .map(entity -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("entityId", entity.getEntityId());
                item.put("name", StrUtil.blankToDefault(entity.getName(), ""));
                item.put("entityType", StrUtil.blankToDefault(entity.getEntityType(), ""));
                item.put("description", StrUtil.maxLength(StrUtil.blankToDefault(entity.getDescription(), ""), 220));
                item.put("rankBoost", entity.getRankBoost());
                return item;
            })
            .toList();
    }

    private List<Map<String, Object>> renderRelations(GraphRagCommunityReportContext context) {
        return (context.getRelations() == null ? List.<GraphRagCommunityReportContext.RelationItem>of() : context.getRelations()).stream()
            .filter(relation -> relation != null && relation.getRelationId() != null)
            .map(relation -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("relationId", relation.getRelationId());
                item.put("sourceEntityId", relation.getSourceEntityId());
                item.put("sourceEntityName", StrUtil.blankToDefault(relation.getSourceEntityName(), ""));
                item.put("targetEntityId", relation.getTargetEntityId());
                item.put("targetEntityName", StrUtil.blankToDefault(relation.getTargetEntityName(), ""));
                item.put("relationType", StrUtil.blankToDefault(relation.getRelationType(), ""));
                item.put("description", StrUtil.maxLength(StrUtil.blankToDefault(relation.getDescription(), ""), 260));
                return item;
            })
            .toList();
    }

    private List<Map<String, Object>> renderEvidences(GraphRagCommunityReportContext context) {
        return (context.getEvidences() == null ? List.<GraphRagCommunityReportContext.EvidenceItem>of() : context.getEvidences()).stream()
            .filter(evidence -> evidence != null && evidence.getEvidenceId() != null)
            .map(evidence -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("evidenceId", evidence.getEvidenceId());
                item.put("entityId", evidence.getEntityId());
                item.put("relationId", evidence.getRelationId());
                item.put("chunkId", evidence.getChunkId());
                item.put("quoteText", StrUtil.maxLength(StrUtil.blankToDefault(evidence.getQuoteText(), ""), 360));
                item.put("sectionPath", StrUtil.blankToDefault(evidence.getSectionPath(), ""));
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
