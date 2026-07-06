package org.javaup.ai.chatagent.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.chatagent.rag.model.DocumentNavigationDecision;
import org.javaup.ai.chatagent.rag.model.DocumentNavigationAction;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.model.QueryType;
import org.javaup.ai.chatagent.rag.model.QueryUnderstandingResult;
import org.javaup.ai.chatagent.rag.model.RagRewriteResult;
import org.javaup.ai.chatagent.rag.model.RetrievalIntent;
import org.javaup.ai.chatagent.rag.model.StructureNavigationIntent;
import org.javaup.ai.chatagent.rag.model.StructureNavigationOperation;
import org.javaup.ai.manage.model.graph.GraphItem;
import org.javaup.ai.manage.model.graph.GraphSection;
import org.javaup.ai.manage.service.DocumentNavigationIndexService;
import org.javaup.ai.manage.service.DocumentStructureGraphService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentQuestionRouterTest {

    @Test
    void structureNavigationChildrenIntentKeepsRetrievalAndSetsChildAction() {
        QueryUnderstandingService queryUnderstandingService = new QueryUnderstandingService(null, null, new ObjectMapper()) {
            @Override
            public QueryUnderstandingResult understand(String originalQuestion,
                                                       String rewrittenQuestion,
                                                       List<String> subQuestions,
                                                       String historySummary,
                                                       String answerRecentTranscript) {
                return QueryUnderstandingResult.builder()
                    .queryType(QueryType.STRUCTURE_NAVIGATION)
                    .channels(List.of(RetrievalIntent.GENERAL, RetrievalIntent.STRUCTURE))
                    .structureNavigationIntent(StructureNavigationIntent.builder()
                        .operations(List.of(StructureNavigationOperation.SECTION_WITH_CHILDREN))
                        .sectionAnchors(List.of("机器人策略设计"))
                        .confidence(0.91D)
                        .source("test")
                        .build())
                    .confidence(0.91D)
                    .source("test")
                    .build();
            }
        };
        DocumentQuestionRouter router = new DocumentQuestionRouter(
            new StaticDocumentStructureGraphService(),
            emptyProvider(),
            provider(queryUnderstandingService)
        );
        RagRewriteResult rewrite = new RagRewriteResult(
            "机器人策略设计都包含哪些章节？",
            List.of("机器人策略设计都包含哪些章节？"),
            ""
        );

        DocumentNavigationDecision decision = router.route(1L, "机器人策略设计都包含哪些章节？", rewrite, "", "");

        assertThat(decision.getExecutionMode()).isEqualTo(ExecutionMode.RETRIEVAL);
        assertThat(decision.getRetrievalIntent()).isEqualTo(RetrievalIntent.STRUCTURE);
        assertThat(decision.getNavigationAction()).isEqualTo(DocumentNavigationAction.CHILD_SECTION_DESCEND);
        assertThat(decision.getQueryUnderstanding().getStructureNavigationIntent().getOperations())
            .containsExactly(StructureNavigationOperation.SECTION_WITH_CHILDREN);
    }

    @Test
    void followUpItemReferenceWithoutExplicitSectionUsesRetrievalWithSoftAnchor() {
        QueryUnderstandingService queryUnderstandingService = new QueryUnderstandingService(null, null, new ObjectMapper()) {
            @Override
            public QueryUnderstandingResult understand(String originalQuestion,
                                                       String rewrittenQuestion,
                                                       List<String> subQuestions,
                                                       String historySummary,
                                                       String answerRecentTranscript) {
                return QueryUnderstandingResult.builder()
                    .queryType(QueryType.FOLLOW_UP)
                    .channels(List.of(RetrievalIntent.GENERAL, RetrievalIntent.STRUCTURE))
                    .confidence(0.86D)
                    .source("test")
                    .build();
            }
        };
        DocumentQuestionRouter router = new DocumentQuestionRouter(
            new StaticDocumentStructureGraphService(),
            emptyProvider(),
            provider(queryUnderstandingService)
        );
        RagRewriteResult rewrite = new RagRewriteResult(
            "前一轮问题的检查顺序中，第一项和第三项分别是什么？",
            List.of("前一轮问题的检查顺序中，第一项和第三项分别是什么？"),
            ""
        );

        DocumentNavigationDecision decision = router.route(
            1L,
            "那这个问题里的第一项和第三项分别是什么？",
            rewrite,
            "",
            ""
        );

        assertThat(decision.getExecutionMode()).isEqualTo(ExecutionMode.RETRIEVAL);
        assertThat(decision.getStructureAnchor().getScopeMode()).isIn("SOFT", "NONE");
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }

    private static <T> ObjectProvider<T> emptyProvider() {
        return provider(null);
    }

    private static class StaticDocumentStructureGraphService implements DocumentStructureGraphService {

        @Override
        public GraphSection findSectionById(Long documentId, Long sectionNodeId) {
            return null;
        }

        @Override
        public GraphSection findSectionByCode(Long documentId, String nodeCode) {
            return null;
        }

        @Override
        public GraphSection findSectionByTitle(Long documentId, String title) {
            return null;
        }

        @Override
        public GraphSection findSectionByCanonicalPath(Long documentId, String canonicalPath) {
            return null;
        }

        @Override
        public GraphSection findBestSection(Long documentId, String topic, String facet) {
            return null;
        }

        @Override
        public List<GraphSection> listSections(Long documentId) {
            return List.of();
        }

        @Override
        public List<GraphSection> listChildren(Long documentId, Long sectionNodeId) {
            return List.of();
        }

        @Override
        public GraphSection parentSection(Long documentId, Long sectionNodeId) {
            return null;
        }

        @Override
        public GraphSection previousSibling(Long documentId, Long sectionNodeId) {
            return null;
        }

        @Override
        public GraphSection nextSibling(Long documentId, Long sectionNodeId) {
            return null;
        }

        @Override
        public GraphItem findItemByIndex(Long documentId, Long sectionNodeId, Integer itemIndex) {
            return null;
        }

        @Override
        public List<GraphItem> listItems(Long documentId, Long sectionNodeId) {
            return List.of();
        }

        @Override
        public List<GraphItem> searchItemsInSection(Long documentId, Long sectionNodeId, String keyword) {
            return List.of();
        }
    }
}
