package org.javaup.ai.chatagent.rag.service;

import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.AnswerHistoryContext;
import org.javaup.ai.chatagent.rag.model.EvidenceAnchor;
import org.javaup.ai.chatagent.rag.model.QueryType;
import org.javaup.ai.chatagent.rag.model.QueryUnderstandingResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerHistoryContextAssemblerTest {

    @Test
    void followUpUsesEvidenceAnchorsInsteadOfQuestionKeywords() {
        ChatRagProperties properties = new ChatRagProperties();
        AnswerHistoryContextAssembler assembler = new AnswerHistoryContextAssembler(properties);
        QueryUnderstandingResult understanding = QueryUnderstandingResult.builder()
            .queryType(QueryType.FOLLOW_UP)
            .confidence(0.88D)
            .source("test")
            .build();
        EvidenceAnchor anchor = EvidenceAnchor.builder()
            .documentId(1L)
            .documentName("doc.md")
            .sectionPath("14.3.1")
            .structureNodeId(1431L)
            .parentBlockId(9001L)
            .chunkId(8001L)
            .snippet("上一轮最终证据")
            .build();

        AnswerHistoryContext context = assembler.assemble(
            "第一项和第三项分别是什么？",
            "",
            understanding,
            List.of(anchor)
        );

        assertThat(context.isFollowUpQuestion()).isTrue();
        assertThat(context.getEvidenceAnchors()).hasSize(1);
        assertThat(context.getStructuredContext())
            .contains("14.3.1")
            .contains("9001")
            .contains("8001");
        assertThat(context.isEmpty()).isFalse();
    }
}
