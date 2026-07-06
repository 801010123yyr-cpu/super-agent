package org.javaup.ai.chatagent.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.chatagent.rag.model.QueryType;
import org.javaup.ai.chatagent.rag.model.QueryUnderstandingResult;
import org.javaup.ai.chatagent.rag.model.RetrievalIntent;
import org.javaup.ai.chatagent.rag.model.StructureNavigationOperation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryUnderstandingServiceTest {

    @Test
    void deterministicFallbackBuildsSiblingNavigationIntent() {
        QueryUnderstandingService service = new QueryUnderstandingService(null, null, new ObjectMapper());

        QueryUnderstandingResult result = service.understand(
            "刚才说的“观察时长”属于哪个一级章节和哪个小节？同一一级章节里的下一小节是什么？",
            "",
            List.of(),
            "",
            ""
        );

        assertThat(result.getQueryType()).isEqualTo(QueryType.STRUCTURE_NAVIGATION);
        assertThat(result.getChannels()).contains(RetrievalIntent.STRUCTURE);
        assertThat(result.getStructureNavigationIntent()).isNotNull();
        assertThat(result.getStructureNavigationIntent().getOperations())
            .containsExactly(StructureNavigationOperation.SECTION_WITH_SIBLINGS);
        assertThat(result.getStructureNavigationIntent().getSectionAnchors()).contains("观察时长");
    }

    @Test
    void deterministicFallbackBuildsChildrenNavigationIntent() {
        QueryUnderstandingService service = new QueryUnderstandingService(null, null, new ObjectMapper());

        QueryUnderstandingResult result = service.understand(
            "机器人策略设计都包含哪些章节？",
            "",
            List.of(),
            "",
            ""
        );

        assertThat(result.getQueryType()).isEqualTo(QueryType.STRUCTURE_NAVIGATION);
        assertThat(result.getStructureNavigationIntent()).isNotNull();
        assertThat(result.getStructureNavigationIntent().getOperations())
            .containsExactly(StructureNavigationOperation.SECTION_WITH_CHILDREN);
    }

    @Test
    void deterministicFallbackDoesNotInferEvidenceRolesFromQuestionText() {
        QueryUnderstandingService service = new QueryUnderstandingService(null, null, new ObjectMapper());

        QueryUnderstandingResult symptom = service.understand(
            "还有造成这个问题的现象都有什么？",
            "",
            List.of(),
            "",
            ""
        );
        QueryUnderstandingResult cause = service.understand(
            "检索命中率突然下降的可能原因都有哪些？",
            "",
            List.of(),
            "",
            ""
        );

        assertThat(symptom.getExpectedEvidenceRoles()).isEmpty();
        assertThat(cause.getExpectedEvidenceRoles()).isEmpty();
    }
}
