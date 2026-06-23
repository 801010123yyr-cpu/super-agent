package org.javaup.ai.manage.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.service.ObservedChatModelService;
import org.javaup.ai.manage.model.graph.GraphRagQueryCatalog;
import org.javaup.ai.manage.model.graph.GraphRagQueryPlanAdvice;
import org.javaup.ai.manage.service.GraphRagQueryPlanAdvisor;
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
@ConditionalOnProperty(prefix = "app.chat.rag.graph-rag-query-plan", name = "enabled", havingValue = "true")
public class LlmGraphRagQueryPlanAdvisor implements GraphRagQueryPlanAdvisor {

    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[\\s\\S]*}");

    private final ObservedChatModelService observedChatModelService;
    private final PromptTemplateService promptTemplateService;
    private final ObjectMapper objectMapper;

    public LlmGraphRagQueryPlanAdvisor(ObservedChatModelService observedChatModelService,
                                       PromptTemplateService promptTemplateService,
                                       ObjectMapper objectMapper) {
        this.observedChatModelService = observedChatModelService;
        this.promptTemplateService = promptTemplateService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<GraphRagQueryPlanAdvice> advise(String question, GraphRagQueryCatalog catalog) {
        if (StrUtil.isBlank(question) || catalog == null || catalogEmpty(catalog)) {
            return Optional.empty();
        }
        try {
            String prompt = promptTemplateService.render(PromptTemplateNames.DOCUMENT_GRAPH_RAG_QUERY_PLAN, Map.of(
                "question", StrUtil.blankToDefault(question, ""),
                "catalog", renderCatalog(catalog)
            ));
            String raw = observedChatModelService.callText(
                "document_graph_rag_query_plan",
                null,
                prompt,
                buildCallOptions(),
                null
            );
            if (StrUtil.isBlank(raw)) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(extractJsonObject(raw), GraphRagQueryPlanAdvice.class));
        }
        catch (Exception exception) {
            log.warn("GraphRAG LLM 受控查询计划生成失败: question='{}', message={}",
                question,
                exception.getMessage());
            throw new IllegalStateException("GraphRAG LLM 受控查询计划生成失败", exception);
        }
    }

    private boolean catalogEmpty(GraphRagQueryCatalog catalog) {
        return CollUtil.isEmpty(catalog.getEntities())
            && CollUtil.isEmpty(catalog.getRelations())
            && CollUtil.isEmpty(catalog.getCommunities());
    }

    private ChatOptions buildCallOptions() {
        return OpenAiChatOptions.builder()
            .temperature(0.0D)
            .topP(0.1D)
            .extraBody(Map.of("thinking", false))
            .build();
    }

    private String renderCatalog(GraphRagQueryCatalog catalog) throws com.fasterxml.jackson.core.JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entities", renderEntities(catalog));
        payload.put("relations", renderRelations(catalog));
        payload.put("communities", renderCommunities(catalog));
        return objectMapper.writeValueAsString(payload);
    }

    private List<Map<String, Object>> renderEntities(GraphRagQueryCatalog catalog) {
        return (catalog.getEntities() == null ? List.<GraphRagQueryCatalog.EntityItem>of() : catalog.getEntities()).stream()
            .filter(entity -> entity != null && entity.getEntityId() != null)
            .map(entity -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("entityId", entity.getEntityId());
                item.put("name", StrUtil.blankToDefault(entity.getName(), ""));
                item.put("normalizedName", StrUtil.blankToDefault(entity.getNormalizedName(), ""));
                item.put("entityType", StrUtil.blankToDefault(entity.getEntityType(), ""));
                item.put("aliases", entity.getAliases() == null ? List.of() : entity.getAliases());
                item.put("description", StrUtil.maxLength(StrUtil.blankToDefault(entity.getDescription(), ""), 160));
                return item;
            })
            .toList();
    }

    private List<Map<String, Object>> renderRelations(GraphRagQueryCatalog catalog) {
        return (catalog.getRelations() == null ? List.<GraphRagQueryCatalog.RelationItem>of() : catalog.getRelations()).stream()
            .filter(relation -> relation != null && relation.getRelationId() != null)
            .map(relation -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("relationId", relation.getRelationId());
                item.put("relationType", StrUtil.blankToDefault(relation.getRelationType(), ""));
                item.put("sourceEntityId", relation.getSourceEntityId());
                item.put("sourceEntityName", StrUtil.blankToDefault(relation.getSourceEntityName(), ""));
                item.put("targetEntityId", relation.getTargetEntityId());
                item.put("targetEntityName", StrUtil.blankToDefault(relation.getTargetEntityName(), ""));
                item.put("description", StrUtil.maxLength(StrUtil.blankToDefault(relation.getDescription(), ""), 180));
                return item;
            })
            .toList();
    }

    private List<Map<String, Object>> renderCommunities(GraphRagQueryCatalog catalog) {
        return (catalog.getCommunities() == null ? List.<GraphRagQueryCatalog.CommunityItem>of() : catalog.getCommunities()).stream()
            .filter(community -> community != null && community.getCommunityId() != null)
            .map(community -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("communityId", community.getCommunityId());
                item.put("title", StrUtil.blankToDefault(community.getTitle(), ""));
                item.put("summary", StrUtil.maxLength(StrUtil.blankToDefault(community.getSummary(), ""), 220));
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
