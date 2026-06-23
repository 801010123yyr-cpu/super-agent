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
@ConditionalOnProperty(prefix = "app.graph-rag.extraction", name = "enabled", havingValue = "true")
public class LlmGraphRagExtractionAdvisor implements GraphRagExtractionAdvisor {

    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[\\s\\S]*}");

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
            log.warn("GraphRAG LLM 受控抽取增强失败: documentId={}, taskId={}, message={}",
                context.getDocumentId(),
                context.getTaskId(),
                exception.getMessage());
            throw new IllegalStateException("GraphRAG LLM 受控抽取增强失败", exception);
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
