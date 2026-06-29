package org.javaup.ai.manage.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.chatagent.service.ObservedChatModelService;
import org.javaup.ai.manage.model.graph.GraphRagExtractionAdvice;
import org.javaup.ai.manage.model.graph.GraphRagExtractionContext;
import org.javaup.ai.prompt.PromptTemplateNames;
import org.javaup.ai.prompt.PromptTemplateService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmGraphRagExtractionAdvisorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractMergesSuccessfulBatchesAndIsolatesFailedBatch() throws Exception {
        ObservedChatModelService chatModelService = mock(ObservedChatModelService.class);
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        LlmGraphRagExtractionAdvisor advisor = new LlmGraphRagExtractionAdvisor(
            chatModelService,
            promptTemplateService,
            objectMapper
        );

        when(promptTemplateService.render(eq(PromptTemplateNames.DOCUMENT_GRAPH_RAG_EXTRACTION), any(Map.class)))
            .thenAnswer(invocation -> String.valueOf(((Map<?, ?>) invocation.getArgument(1)).get("context")));
        when(chatModelService.callText(eq("document_graph_rag_extraction"), any(), anyString(), any(ChatOptions.class), any()))
            .thenReturn("{\"graphable\":true,\"entities\":[")
            .thenReturn("{\"graphable\":false,\"entities\":[],\"relations\":[],\"evidences\":[],\"confidence\":0.0,\"reason\":\"无可靠图谱信息\"}")
            .thenReturn("{\"graphable\":false,\"entities\":[],\"relations\":[],\"evidences\":[],\"confidence\":0.0,\"reason\":\"无可靠图谱信息\"}")
            .thenReturn("{\"graphable\":false,\"entities\":[],\"relations\":[],\"evidences\":[],\"confidence\":0.0,\"reason\":\"无可靠图谱信息\"}")
            .thenReturn("""
                {
                  "graphable": true,
                  "entities": [
                    {
                      "id": "E1",
                      "name": "AuditTrail",
                      "normalizedName": "audittrail",
                      "entityType": "SYSTEM",
                      "aliases": ["审计系统"],
                      "description": "审计留痕系统",
                      "confidence": 0.91,
                      "sourceChunkIds": [4]
                    },
                    {
                      "id": "E2",
                      "name": "临时权限延长",
                      "normalizedName": "临时权限延长",
                      "entityType": "POLICY_OBJECT",
                      "aliases": [],
                      "description": "需要被记录的权限行为",
                      "confidence": 0.9,
                      "sourceChunkIds": [4]
                    }
                  ],
                  "relations": [
                    {
                      "id": "R1",
                      "sourceEntityId": "E1",
                      "targetEntityId": "E2",
                      "relationType": "RECORDS",
                      "supportMode": "EXPLICIT_ACTION",
                      "predicateQuoteText": "需记录",
                      "relationTypeReason": "原文明确说明 AuditTrail 需记录临时权限延长。",
                      "description": "AuditTrail 记录临时权限延长。",
                      "weight": 0.9,
                      "confidence": 0.92
                    }
                  ],
                  "evidences": [
                    {
                      "id": "EV1",
                      "relationId": "R1",
                      "chunkId": 4,
                      "quoteText": "AuditTrail 需记录临时权限延长。",
                      "confidence": 0.93
                    }
                  ],
                  "confidence": 0.91,
                  "reason": "chunk 中存在明确记录关系"
                }
                """);

        Optional<GraphRagExtractionAdvice> result = advisor.extract(GraphRagExtractionContext.builder()
            .documentId(10L)
            .taskId(20L)
            .chunks(List.of(
                chunk(1L, "第一段没有必要的内容。"),
                chunk(2L, "第二段没有必要的内容。"),
                chunk(3L, "第三段没有必要的内容。"),
                chunk(4L, "AuditTrail 需记录临时权限延长。")
            ))
            .build());

        assertThat(result).isPresent();
        GraphRagExtractionAdvice advice = result.get();
        assertThat(advice.getGraphable()).isTrue();
        assertThat(advice.getEntities()).extracting(GraphRagExtractionAdvice.EntityItem::getId)
            .containsExactly("B2_E1", "B2_E2");
        assertThat(advice.getRelations()).singleElement().satisfies(relation -> {
            assertThat(relation.getId()).isEqualTo("B2_R1");
            assertThat(relation.getSourceEntityId()).isEqualTo("B2_E1");
            assertThat(relation.getTargetEntityId()).isEqualTo("B2_E2");
        });
        assertThat(advice.getEvidences()).singleElement().satisfies(evidence -> {
            assertThat(evidence.getId()).isEqualTo("B2_EV1");
            assertThat(evidence.getRelationId()).isEqualTo("B2_R1");
            assertThat(evidence.getChunkId()).isEqualTo(4L);
        });
        assertThat(advice.getReason())
            .contains("batchCount=2")
            .contains("successBatchCount=1")
            .contains("failedBatchCount=0");

        verify(chatModelService, times(5))
            .callText(eq("document_graph_rag_extraction"), any(), anyString(), any(ChatOptions.class), any());
    }

    @Test
    void extractBatchesByTokenBudgetAndChunkLimit() throws Exception {
        ObservedChatModelService chatModelService = mock(ObservedChatModelService.class);
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        List<Integer> batchSizes = new ArrayList<>();
        LlmGraphRagExtractionAdvisor advisor = new LlmGraphRagExtractionAdvisor(
            chatModelService,
            promptTemplateService,
            objectMapper
        );

        when(promptTemplateService.render(eq(PromptTemplateNames.DOCUMENT_GRAPH_RAG_EXTRACTION), any(Map.class)))
            .thenAnswer(invocation -> {
                String context = String.valueOf(((Map<?, ?>) invocation.getArgument(1)).get("context"));
                JsonNode root = objectMapper.readTree(context);
                batchSizes.add(root.path("chunks").size());
                return context;
            });
        when(chatModelService.callText(eq("document_graph_rag_extraction"), any(), anyString(), any(ChatOptions.class), any()))
            .thenReturn("{\"graphable\":false,\"entities\":[],\"relations\":[],\"evidences\":[],\"confidence\":0.0,\"reason\":\"无可靠图谱信息\"}");

        Optional<GraphRagExtractionAdvice> result = advisor.extract(GraphRagExtractionContext.builder()
            .documentId(10L)
            .taskId(20L)
            .chunks(List.of(
                chunk(1L, "A".repeat(200)),
                chunk(2L, "B".repeat(200)),
                chunk(3L, "C".repeat(200)),
                chunk(4L, "D".repeat(200)),
                chunk(5L, "E".repeat(200)),
                chunk(6L, "F".repeat(200)),
                chunk(7L, "G".repeat(12000))
            ))
            .build());

        assertThat(result).isPresent();
        assertThat(result.get().getGraphable()).isFalse();
        assertThat(batchSizes).containsExactly(3, 3, 1);
    }

    private GraphRagExtractionContext.ChunkItem chunk(Long chunkId, String text) {
        return GraphRagExtractionContext.ChunkItem.builder()
            .chunkId(chunkId)
            .parentBlockId(1000L + chunkId)
            .chunkNo(chunkId.intValue())
            .chunkType("TEXT")
            .title("测试章节")
            .sectionPath("测试章节")
            .pageNo(1)
            .pageRange("1")
            .text(text)
            .build();
    }
}
