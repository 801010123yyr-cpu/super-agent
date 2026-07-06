package org.javaup.ai.chatagent.rag.service;

import org.javaup.ai.chatagent.model.memory.ConversationMemoryContext;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.DocumentNavigationDecision;
import org.javaup.ai.chatagent.rag.model.EvidenceAnchor;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.model.QueryType;
import org.javaup.ai.chatagent.rag.model.QueryUnderstandingResult;
import org.javaup.ai.chatagent.rag.model.RagRewriteResult;
import org.javaup.ai.chatagent.rag.model.RetrievalIntent;
import org.javaup.ai.chatagent.service.ConversationMemoryService;
import org.javaup.ai.chatagent.service.TaskInfo;
import org.javaup.ai.chatagent.support.StreamEventMetadata;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.ai.manage.model.KnowledgeBaseSelectionSnapshot;
import org.javaup.ai.manage.model.route.DocumentRouteCandidate;
import org.javaup.ai.manage.model.route.KnowledgeRouteContext;
import org.javaup.ai.manage.model.route.KnowledgeRouteDecision;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.ai.manage.service.KnowledgeRouteService;
import org.javaup.enums.ChatQueryMode;
import org.javaup.enums.KnowledgeBaseSelectionMode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ChatPreparationOrchestratorTest {

    @Test
    void autoDocumentLowConfidenceMultiDocumentCandidatesEnterRetrieval() {
        ChatRagProperties properties = new ChatRagProperties();
        properties.getAutoRoute().setConfidentDocumentThreshold(0.55D);
        properties.getAutoRoute().setMultiDocumentRetrievalThreshold(0.45D);

        KnowledgeRouteDecision routeDecision = new KnowledgeRouteDecision();
        routeDecision.setConfidence(BigDecimal.valueOf(0.5125D));
        routeDecision.setRouteStatus("LOW_CONFIDENCE");
        routeDecision.setDocuments(List.of(
            candidate("1001", "2001", "O6跨文档图谱-审计系统别名说明B.md", 42D),
            candidate("1002", "2002", "O6跨文档图谱-审计证据规范A.md", 39D)
        ));

        ChatPreparationOrchestrator orchestrator = new ChatPreparationOrchestrator(
            properties,
            new StaticConversationMemoryService(),
            new AnswerHistoryContextAssembler(properties),
            new StaticRewriteService(properties),
            new StaticDocumentQuestionRouter(),
            new StaticKnowledgeRouteService(routeDecision),
            new StaticDocumentKnowledgeService()
        );

        ConversationExecutionPlan plan = orchestrator.prepare(taskInfo("审计系统相关的权限审批部门是谁？"));

        assertThat(plan.getMode()).isEqualTo(ExecutionMode.RETRIEVAL);
        assertThat(plan.getSelectedDocumentId()).isNull();
        assertThat(plan.getSelectedTaskId()).isNull();
        assertThat(plan.getRetrievalDocumentIds()).containsExactly(1001L, 1002L);
        assertThat(plan.getRetrievalTaskIds()).containsExactly(2001L, 2002L);
        assertThat(plan.getClarificationReply()).isNull();
        assertThat(plan.getRetrievalQuestion()).isEqualTo("审计系统中权限审批的部门是哪个？");
    }

    @Test
    void autoDocumentVeryLowConfidenceStillAsksClarification() {
        ChatRagProperties properties = new ChatRagProperties();
        properties.getAutoRoute().setConfidentDocumentThreshold(0.55D);
        properties.getAutoRoute().setMultiDocumentRetrievalThreshold(0.45D);

        KnowledgeRouteDecision routeDecision = new KnowledgeRouteDecision();
        routeDecision.setConfidence(BigDecimal.valueOf(0.30D));
        routeDecision.setRouteStatus("LOW_CONFIDENCE");
        routeDecision.setDocuments(List.of(
            candidate("1001", "2001", "O6跨文档图谱-审计系统别名说明B.md", 42D),
            candidate("1002", "2002", "O6跨文档图谱-审计证据规范A.md", 39D)
        ));

        ChatPreparationOrchestrator orchestrator = new ChatPreparationOrchestrator(
            properties,
            new StaticConversationMemoryService(),
            new AnswerHistoryContextAssembler(properties),
            new StaticRewriteService(properties),
            new StaticDocumentQuestionRouter(),
            new StaticKnowledgeRouteService(routeDecision),
            new StaticDocumentKnowledgeService()
        );

        ConversationExecutionPlan plan = orchestrator.prepare(taskInfo("审计系统相关的权限审批部门是谁？"));

        assertThat(plan.getMode()).isEqualTo(ExecutionMode.CLARIFICATION);
        assertThat(plan.getRetrievalDocumentIds()).containsExactly(1001L, 1002L);
        assertThat(plan.getRetrievalTaskIds()).containsExactly(2001L, 2002L);
        assertThat(plan.getClarificationReply()).contains("这个问题目前存在文档范围歧义");
    }

    @Test
    void followUpPlanCarriesPreviousFinalEvidenceAnchor() {
        ChatRagProperties properties = new ChatRagProperties();
        EvidenceAnchor anchor = EvidenceAnchor.builder()
            .documentId(1001L)
            .documentName("测试文档.md")
            .sectionPath("14.3.1")
            .structureNodeId(1431L)
            .parentBlockId(9001L)
            .chunkId(8001L)
            .snippet("上一轮最终证据")
            .build();

        ChatPreparationOrchestrator orchestrator = new ChatPreparationOrchestrator(
            properties,
            new StaticConversationMemoryService(),
            new AnswerHistoryContextAssembler(properties),
            new StaticRewriteService(properties),
            new FollowUpDocumentQuestionRouter(),
            new StaticKnowledgeRouteService(new KnowledgeRouteDecision()),
            new StaticDocumentKnowledgeService(),
            new StaticConversationEvidenceAnchorService(List.of(anchor))
        );

        ConversationExecutionPlan plan = orchestrator.prepare(documentTaskInfo("第一项和第三项分别是什么？"));

        assertThat(plan.getMode()).isEqualTo(ExecutionMode.RETRIEVAL);
        assertThat(plan.getAnswerHistoryContext()).isNotNull();
        assertThat(plan.getAnswerHistoryContext().getEvidenceAnchors()).hasSize(1);
        assertThat(plan.getAnswerHistoryContext().getStructuredContext())
            .contains("14.3.1")
            .contains("9001")
            .contains("8001");
        assertThat(plan.getNavigationDecision().getExecutionMode()).isEqualTo(ExecutionMode.RETRIEVAL);
    }

    private static DocumentRouteCandidate candidate(String documentId, String taskId, String name, double score) {
        return new DocumentRouteCandidate(
            documentId,
            name,
            taskId,
            BigDecimal.valueOf(score),
            "test"
        );
    }

    private static TaskInfo taskInfo(String question) {
        return new TaskInfo(
            "conversation-a",
            1L,
            question,
            ChatQueryMode.AUTO_DOCUMENT,
            "trace-a",
            null,
            "",
            null,
            KnowledgeBaseSelectionSnapshot.builder()
                .selectionMode(KnowledgeBaseSelectionMode.SELECTED)
                .selectedKnowledgeBaseIds(List.of(1L))
                .selectedKnowledgeBaseNames(List.of("测试知识库"))
                .allowedDocuments(List.of(
                    new KnowledgeDocumentDescriptor(1001L, "O6跨文档图谱-审计系统别名说明B.md", 2001L, 1L, "测试知识库"),
                    new KnowledgeDocumentDescriptor(1002L, "O6跨文档图谱-审计证据规范A.md", 2002L, 1L, "测试知识库")
                ))
                .allowedDocumentIds(List.of(1001L, 1002L))
                .allowedTaskIds(List.of(2001L, 2002L))
                .build(),
            LocalDate.of(2026, 6, 26),
            "2026年6月26日",
            null,
            null,
            null,
            null,
            null,
            new StreamEventMetadata("conversation-a", 1L),
            "",
            "",
            List.of(),
            List.of(),
            Set.of(),
            System.currentTimeMillis()
        );
    }

    private static TaskInfo documentTaskInfo(String question) {
        return new TaskInfo(
            "conversation-a",
            1L,
            question,
            ChatQueryMode.DOCUMENT,
            "trace-a",
            1001L,
            "测试文档.md",
            2001L,
            KnowledgeBaseSelectionSnapshot.builder()
                .selectionMode(KnowledgeBaseSelectionMode.SELECTED)
                .selectedKnowledgeBaseIds(List.of(1L))
                .selectedKnowledgeBaseNames(List.of("测试知识库"))
                .allowedDocuments(List.of(
                    new KnowledgeDocumentDescriptor(1001L, "测试文档.md", 2001L, 1L, "测试知识库")
                ))
                .allowedDocumentIds(List.of(1001L))
                .allowedTaskIds(List.of(2001L))
                .build(),
            LocalDate.of(2026, 6, 26),
            "2026年6月26日",
            null,
            null,
            null,
            null,
            null,
            new StreamEventMetadata("conversation-a", 1L),
            "",
            "",
            List.of(),
            List.of(),
            Set.of(),
            System.currentTimeMillis()
        );
    }

    private static class StaticConversationMemoryService implements ConversationMemoryService {

        @Override
        public ConversationMemoryContext loadMemoryContext(String conversationId) {
            return ConversationMemoryContext.builder()
                .assembledHistory("")
                .longTermSummary("")
                .recentTranscript("")
                .answerRecentTranscript("")
                .coveredExchangeId(0L)
                .coveredExchangeCount(0)
                .compressionCount(0)
                .compressionApplied(false)
                .build();
        }

        @Override
        public void refreshConversationSummaryAsync(String conversationId) {
        }

        @Override
        public org.javaup.ai.chatagent.model.ConversationMemorySummaryView getConversationSummary(String conversationId) {
            return null;
        }

        @Override
        public org.javaup.ai.chatagent.model.ConversationMemorySummaryView rebuildConversationSummary(String conversationId) {
            return null;
        }

        @Override
        public void deleteConversationSummary(String conversationId) {
        }
    }

    private static class StaticRewriteService extends ChatQueryRewriteService {

        StaticRewriteService(ChatRagProperties properties) {
            super(null, null, properties, null);
        }

        @Override
        public RagRewriteResult rewrite(String question,
                                        String historySummary,
                                        org.javaup.ai.chatagent.service.ConversationTraceRecorder traceRecorder) {
            return new RagRewriteResult(
                "审计系统中权限审批的部门是哪个？",
                List.of("审计系统中权限审批的部门是哪个？"),
                ""
            );
        }
    }

    private static class StaticDocumentQuestionRouter extends DocumentQuestionRouter {

        StaticDocumentQuestionRouter() {
            super(null, null, null);
        }

        @Override
        public DocumentNavigationDecision route(Long documentId,
                                                String originalQuestion,
                                                RagRewriteResult rewriteResult) {
            return route(documentId, originalQuestion, rewriteResult, "", "");
        }

        @Override
        public DocumentNavigationDecision route(Long documentId,
                                                String originalQuestion,
                                                RagRewriteResult rewriteResult,
                                                String historySummary,
                                                String answerRecentTranscript) {
            return DocumentNavigationDecision.builder()
                .executionMode(ExecutionMode.RETRIEVAL)
                .retrievalIntent(RetrievalIntent.GRAPH_RAG)
                .retrievalPlan(new org.javaup.ai.chatagent.rag.model.RetrievalQuestionPlan(
                    rewriteResult.getRewrittenQuestion(),
                    rewriteResult.getSubQuestions()
                ))
                .build();
        }
    }

    private static class FollowUpDocumentQuestionRouter extends DocumentQuestionRouter {

        FollowUpDocumentQuestionRouter() {
            super(null, null, null);
        }

        @Override
        public DocumentNavigationDecision route(Long documentId,
                                                String originalQuestion,
                                                RagRewriteResult rewriteResult,
                                                String historySummary,
                                                String answerRecentTranscript) {
            return DocumentNavigationDecision.builder()
                .executionMode(ExecutionMode.RETRIEVAL)
                .retrievalIntent(RetrievalIntent.GENERAL)
                .queryUnderstanding(QueryUnderstandingResult.builder()
                    .queryType(QueryType.FOLLOW_UP)
                    .confidence(0.88D)
                    .source("test")
                    .build())
                .retrievalPlan(new org.javaup.ai.chatagent.rag.model.RetrievalQuestionPlan(
                    rewriteResult.getRewrittenQuestion(),
                    rewriteResult.getSubQuestions()
                ))
                .build();
        }
    }

    private static class StaticConversationEvidenceAnchorService extends ConversationEvidenceAnchorService {

        private final List<EvidenceAnchor> anchors;

        StaticConversationEvidenceAnchorService(List<EvidenceAnchor> anchors) {
            super(null);
            this.anchors = anchors;
        }

        @Override
        public List<EvidenceAnchor> loadRecentEvidenceAnchors(String conversationId, int limit) {
            return anchors.stream().limit(Math.max(0, limit)).toList();
        }
    }

    private static class StaticKnowledgeRouteService implements KnowledgeRouteService {

        private final KnowledgeRouteDecision decision;

        StaticKnowledgeRouteService(KnowledgeRouteDecision decision) {
            this.decision = decision;
        }

        @Override
        public KnowledgeRouteDecision route(KnowledgeRouteContext context) {
            return decision;
        }

        @Override
        public void recordShadowRoute(String conversationId,
                                      long exchangeId,
                                      Long selectedDocumentId,
                                      KnowledgeRouteContext context) {
        }

        @Override
        public void recordAutoRoute(String conversationId,
                                    long exchangeId,
                                    KnowledgeRouteContext context,
                                    KnowledgeRouteDecision decision) {
        }
    }

    private static class StaticDocumentKnowledgeService implements DocumentKnowledgeService {

        @Override
        public List<KnowledgeDocumentDescriptor> listRetrievableDocuments() {
            return List.of();
        }

        @Override
        public List<KnowledgeDocumentDescriptor> listRetrievableDocumentsByKnowledgeBaseIds(java.util.Collection<Long> knowledgeBaseIds) {
            return List.of();
        }

        @Override
        public List<Document> vectorSearch(org.javaup.ai.manage.model.DocumentRetrieveRequest request) {
            return List.of();
        }

        @Override
        public List<Document> keywordSearch(org.javaup.ai.manage.model.DocumentRetrieveRequest request) {
            return List.of();
        }

        @Override
        public List<Document> elevateToParentBlocks(List<Document> childDocuments, int maxChars) {
            return childDocuments;
        }
    }
}
