package org.javaup.ai.manage.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.service.ObservedChatModelService;
import org.javaup.ai.manage.model.graph.GraphRagEntityResolutionAdvice;
import org.javaup.ai.manage.model.graph.GraphRagEntityResolutionContext;
import org.javaup.ai.manage.service.GraphRagEntityResolutionAdvisor;
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
@ConditionalOnProperty(prefix = "app.graph-rag.entity-resolution", name = "enabled", havingValue = "true")
public class LlmGraphRagEntityResolutionAdvisor implements GraphRagEntityResolutionAdvisor {

    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[\\s\\S]*}");

    private final ObservedChatModelService observedChatModelService;
    private final PromptTemplateService promptTemplateService;
    private final ObjectMapper objectMapper;

    public LlmGraphRagEntityResolutionAdvisor(ObservedChatModelService observedChatModelService,
                                              PromptTemplateService promptTemplateService,
                                              ObjectMapper objectMapper) {
        this.observedChatModelService = observedChatModelService;
        this.promptTemplateService = promptTemplateService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<GraphRagEntityResolutionAdvice> advise(GraphRagEntityResolutionContext context) {
        if (context == null || context.getEntities() == null || context.getEntities().size() < 2) {
            return Optional.empty();
        }
        try {
            String prompt = promptTemplateService.render(PromptTemplateNames.DOCUMENT_GRAPH_RAG_ENTITY_RESOLUTION, Map.of(
                "context", renderContext(context)
            ));
            String raw = observedChatModelService.callText(
                "document_graph_rag_entity_resolution",
                null,
                prompt,
                buildCallOptions(),
                null
            );
            if (StrUtil.isBlank(raw)) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(extractJsonObject(raw), GraphRagEntityResolutionAdvice.class));
        }
        catch (Exception exception) {
            log.warn("GraphRAG LLM 实体消歧建议生成失败: message={}", exception.getMessage());
            throw new IllegalStateException("GraphRAG LLM 实体消歧建议生成失败", exception);
        }
    }

    private ChatOptions buildCallOptions() {
        return OpenAiChatOptions.builder()
            .temperature(0.0D)
            .topP(0.1D)
            .extraBody(Map.of("thinking", false))
            .build();
    }

    private String renderContext(GraphRagEntityResolutionContext context) throws com.fasterxml.jackson.core.JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entities", renderEntities(context));
        return objectMapper.writeValueAsString(payload);
    }

    private List<Map<String, Object>> renderEntities(GraphRagEntityResolutionContext context) {
        return (context.getEntities() == null ? List.<GraphRagEntityResolutionContext.EntityItem>of() : context.getEntities()).stream()
            .filter(entity -> entity != null && StrUtil.isNotBlank(entity.getSourceEntityId()))
            .map(entity -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("sourceEntityId", entity.getSourceEntityId());
                item.put("name", StrUtil.blankToDefault(entity.getName(), ""));
                item.put("normalizedName", StrUtil.blankToDefault(entity.getNormalizedName(), ""));
                item.put("entityType", StrUtil.blankToDefault(entity.getEntityType(), ""));
                item.put("aliases", entity.getAliases() == null ? List.of() : entity.getAliases());
                item.put("description", StrUtil.maxLength(StrUtil.blankToDefault(entity.getDescription(), ""), 220));
                item.put("confidence", entity.getConfidence());
                item.put("sourceChunkIds", entity.getSourceChunkIds() == null ? List.of() : entity.getSourceChunkIds());
                item.put("evidenceIds", entity.getEvidenceIds() == null ? List.of() : entity.getEvidenceIds());
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
