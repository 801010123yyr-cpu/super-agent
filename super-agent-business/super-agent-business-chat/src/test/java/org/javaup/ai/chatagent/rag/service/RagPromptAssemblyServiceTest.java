package org.javaup.ai.chatagent.rag.service;

import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.EvidenceRole;
import org.javaup.ai.chatagent.rag.model.QueryUnderstandingResult;
import org.javaup.ai.chatagent.rag.model.RagRetrievalContext;
import org.javaup.ai.chatagent.rag.model.SubQuestionEvidence;
import org.javaup.ai.prompt.PromptTemplateService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagPromptAssemblyServiceTest {

    @Test
    void promptIncludesExpectedEvidenceRoleBoundary() {
        RagPromptAssemblyService service = new RagPromptAssemblyService(
            new ChatRagProperties(),
            new PromptTemplateService(new DefaultResourceLoader()),
            new AnswerPlanService()
        );
        ConversationExecutionPlan plan = ConversationExecutionPlan.builder()
            .currentDateText("2026-07-06")
            .originalQuestion("还有造成这个问题的现象都有什么？")
            .retrievalQuestion("检索命中率突然下降的现象")
            .queryUnderstanding(QueryUnderstandingResult.builder()
                .expectedEvidenceRoles(List.of(EvidenceRole.SYMPTOM))
                .build())
            .build();
        SearchReference reference = new SearchReference();
        reference.setReferenceId("1");
        reference.setSourceType("DOCUMENT");
        reference.setDocumentName("星联智服全渠道客服平台上线与运营管理手册.md");
        reference.setSectionPath("14.1.2 可能原因");
        reference.setSnippet("父子块策略配置错误、索引任务未完成、finalTopK 被下调。");
        reference.setEvidenceRole("CAUSE");
        RagRetrievalContext context = new RagRetrievalContext(
            "检索命中率突然下降的现象",
            List.of(new SubQuestionEvidence(1, "检索命中率突然下降的现象", List.of(), List.of(reference), List.of(), 1, 1, 1)),
            List.of(),
            List.of("vector")
        );

        String prompt = service.buildUserPrompt(plan, context);

        assertThat(prompt).contains("本轮问题期望证据角色：SYMPTOM");
        assertThat(prompt).contains("背景证据不能替代对应角色证据");
        assertThat(prompt).contains("证据角色：CAUSE");
    }
}
