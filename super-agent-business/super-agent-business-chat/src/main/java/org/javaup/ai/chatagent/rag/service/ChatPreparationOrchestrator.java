package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.model.memory.ConversationMemoryContext;
import org.javaup.ai.chatagent.model.memory.ConversationSummaryPayload;
import org.javaup.ai.chatagent.model.trace.ConversationTraceStageCode;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.AnswerHistoryContext;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.DocumentNavigationDecision;
import org.javaup.ai.chatagent.rag.model.EvidenceAnchor;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.model.HistoryPlanningContext;
import org.javaup.ai.chatagent.rag.model.QueryType;
import org.javaup.ai.chatagent.rag.model.QueryUnderstandingResult;
import org.javaup.ai.chatagent.rag.model.RagRewriteResult;
import org.javaup.ai.chatagent.rag.model.RetrievalIntent;
import org.javaup.ai.chatagent.rag.model.StructureNavigationIntent;
import org.javaup.ai.chatagent.rag.model.StructureNavigationResult;
import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;
import org.javaup.ai.chatagent.service.ConversationMemoryService;
import org.javaup.ai.chatagent.service.ConversationTraceRecorder;
import org.javaup.ai.chatagent.service.TaskInfo;
import org.javaup.ai.chatagent.support.TimeSensitiveQueryHelper;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.ai.manage.model.KnowledgeBaseSelectionSnapshot;
import org.javaup.ai.manage.model.route.DocumentRouteCandidate;
import org.javaup.ai.manage.model.route.KnowledgeRouteContext;
import org.javaup.ai.manage.model.route.KnowledgeRouteDecision;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.ai.manage.service.KnowledgeRouteService;
import org.javaup.enums.ChatQueryMode;
import org.javaup.enums.KnowledgeBaseSelectionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务层
 * @author: 阿星不是程序员
 **/

@Slf4j
@Service
public class ChatPreparationOrchestrator {

    private final ChatRagProperties properties;
    private final ConversationMemoryService conversationMemoryService;
    private final AnswerHistoryContextAssembler answerHistoryContextAssembler;
    private final ChatQueryRewriteService chatQueryRewriteService;
    private final DocumentQuestionRouter documentQuestionRouter;
    private final KnowledgeRouteService knowledgeRouteService;
    private final DocumentKnowledgeService documentKnowledgeService;
    private final ConversationEvidenceAnchorService conversationEvidenceAnchorService;
    private final StructureNavigationResolver structureNavigationResolver;

    public ChatPreparationOrchestrator(ChatRagProperties properties,
                                       ConversationMemoryService conversationMemoryService,
                                       AnswerHistoryContextAssembler answerHistoryContextAssembler,
                                       ChatQueryRewriteService chatQueryRewriteService,
                                       DocumentQuestionRouter documentQuestionRouter,
                                       KnowledgeRouteService knowledgeRouteService,
                                       DocumentKnowledgeService documentKnowledgeService) {
        this(properties, conversationMemoryService, answerHistoryContextAssembler, chatQueryRewriteService,
            documentQuestionRouter, knowledgeRouteService, documentKnowledgeService, null, null);
    }

    public ChatPreparationOrchestrator(ChatRagProperties properties,
                                       ConversationMemoryService conversationMemoryService,
                                       AnswerHistoryContextAssembler answerHistoryContextAssembler,
                                       ChatQueryRewriteService chatQueryRewriteService,
                                       DocumentQuestionRouter documentQuestionRouter,
                                       KnowledgeRouteService knowledgeRouteService,
                                       DocumentKnowledgeService documentKnowledgeService,
                                       ConversationEvidenceAnchorService conversationEvidenceAnchorService) {
        this(properties, conversationMemoryService, answerHistoryContextAssembler, chatQueryRewriteService,
            documentQuestionRouter, knowledgeRouteService, documentKnowledgeService, conversationEvidenceAnchorService, null);
    }

    @Autowired
    public ChatPreparationOrchestrator(ChatRagProperties properties,
                                       ConversationMemoryService conversationMemoryService,
                                       AnswerHistoryContextAssembler answerHistoryContextAssembler,
                                       ChatQueryRewriteService chatQueryRewriteService,
                                       DocumentQuestionRouter documentQuestionRouter,
                                       KnowledgeRouteService knowledgeRouteService,
                                       DocumentKnowledgeService documentKnowledgeService,
                                       ConversationEvidenceAnchorService conversationEvidenceAnchorService,
                                       StructureNavigationResolver structureNavigationResolver) {
        this.properties = properties;
        this.conversationMemoryService = conversationMemoryService;
        this.answerHistoryContextAssembler = answerHistoryContextAssembler;
        this.chatQueryRewriteService = chatQueryRewriteService;
        this.documentQuestionRouter = documentQuestionRouter;
        this.knowledgeRouteService = knowledgeRouteService;
        this.documentKnowledgeService = documentKnowledgeService;
        this.conversationEvidenceAnchorService = conversationEvidenceAnchorService;
        this.structureNavigationResolver = structureNavigationResolver;
    }

    public ConversationExecutionPlan prepare(TaskInfo taskInfo) {
        String conversationId = taskInfo.conversationId();
        String question = taskInfo.question();
        ChatQueryMode chatMode = taskInfo.chatMode();
        Long selectedDocumentId = taskInfo.selectedDocumentId();
        String selectedDocumentName = taskInfo.selectedDocumentName();
        Long selectedTaskId = taskInfo.selectedTaskId();
        KnowledgeBaseSelectionSnapshot knowledgeBaseSelection = taskInfo.knowledgeBaseSelectionSnapshot();
        LocalDate currentDate = taskInfo.currentDate();
        String currentDateText = taskInfo.currentDateText();
        ConversationTraceRecorder traceRecorder = taskInfo.traceRecorder();

        ConversationTraceRecorder.StageHandle memoryStage = traceRecorder == null
            ? null
            : traceRecorder.startStage(ConversationTraceStageCode.MEMORY, chatMode == null ? "" : chatMode.name(), "正在装载会话记忆与最近窗口。", null);
        ConversationMemoryContext memoryContext;
        try {
            memoryContext = summarizeHistory(conversationId, traceRecorder);
            if (traceRecorder != null) {
                traceRecorder.completeStage(memoryStage, "会话记忆装载完成。", Map.of(
                    "compressionApplied", memoryContext != null && memoryContext.isCompressionApplied(),
                    "coveredExchangeId", memoryContext == null ? 0L : memoryContext.getCoveredExchangeId(),
                    "coveredExchangeCount", memoryContext == null ? 0 : memoryContext.getCoveredExchangeCount(),
                    "compressionCount", memoryContext == null ? 0 : memoryContext.getCompressionCount(),
                    "longTermSummary", memoryContext == null ? "" : safeText(memoryContext.getLongTermSummary()),
                    "recentTranscript", memoryContext == null ? "" : safeText(memoryContext.getRecentTranscript()),
                    "answerRecentTranscript", memoryContext == null ? "" : safeText(memoryContext.getAnswerRecentTranscript())
                ));
            }
        }
        catch (RuntimeException exception) {
            if (traceRecorder != null) {
                traceRecorder.failStage(memoryStage, "会话记忆装载失败。", exception.getMessage(), null);
            }
            throw exception;
        }

        HistoryPlanningContext historyPlanningContext = buildHistoryPlanningContext(memoryContext);
        String historySummary = buildPlanningHistory(memoryContext, historyPlanningContext);
        List<EvidenceAnchor> recentEvidenceAnchors = loadRecentEvidenceAnchors(conversationId);
        AnswerHistoryContext answerHistoryContext = buildAnswerHistoryContext(
            question,
            memoryContext == null ? "" : memoryContext.getAnswerRecentTranscript(),
            null,
            List.of()
        );

        boolean requiresCurrentDateAnchoring = TimeSensitiveQueryHelper.requiresCurrentDateAnchoring(question);
        boolean requiresFreshSearch = TimeSensitiveQueryHelper.requiresFreshSearch(question);
        if (chatMode == null) {
            throw new IllegalArgumentException("chatMode 不能为空");
        }

        if (chatMode == ChatQueryMode.OPEN_CHAT) {
            ConversationExecutionPlan plan = basePlan(question, chatMode, memoryContext, historyPlanningContext, historySummary, answerHistoryContext, currentDate, currentDateText,
                requiresCurrentDateAnchoring, requiresFreshSearch, knowledgeBaseSelection)
                .mode(ExecutionMode.REACT_AGENT)
                .build();
            if (traceRecorder != null) {
                ConversationTraceRecorder.StageHandle routeStage = traceRecorder.startStage(ConversationTraceStageCode.ROUTE, ExecutionMode.REACT_AGENT.name(), "路由到开放式 Agent。", null);
                traceRecorder.completeStage(routeStage, "已判定走开放式 Agent 路径。", Map.of(
                    "chatMode", chatMode.name(),
                    "executionMode", ExecutionMode.REACT_AGENT.name(),
                    "requiresFreshSearch", requiresFreshSearch,
                    "requiresCurrentDateAnchoring", requiresCurrentDateAnchoring
                ));
            }
            return plan;
        }
        if (selectionMode(knowledgeBaseSelection) == KnowledgeBaseSelectionMode.NONE) {
            return basePlan(question, ChatQueryMode.OPEN_CHAT, memoryContext, historyPlanningContext, historySummary, answerHistoryContext, currentDate, currentDateText,
                requiresCurrentDateAnchoring, requiresFreshSearch, knowledgeBaseSelection)
                .mode(ExecutionMode.REACT_AGENT)
                .build();
        }

        if (!properties.isEnabled()) {
            throw new IllegalStateException("当前文档问答模式未启用，请先开启聊天侧 RAG 编排");
        }
        if (chatMode == ChatQueryMode.DOCUMENT && (selectedDocumentId == null || selectedTaskId == null)) {
            throw new IllegalArgumentException("当前文档问答模式缺少有效的文档范围");
        }

        ConversationTraceRecorder.StageHandle rewriteStage = traceRecorder == null
            ? null
            : traceRecorder.startStage(
                ConversationTraceStageCode.REWRITE,
                ExecutionMode.RETRIEVAL.name(),
                "正在生成检索友好的问题表达。",
                buildRewriteStageSnapshot(question, historySummary, null)
            );
        RagRewriteResult rewriteResult;
        try {
            rewriteResult = chatQueryRewriteService.rewrite(question, historySummary, traceRecorder);
            if (traceRecorder != null) {
                traceRecorder.completeStage(rewriteStage, "问题改写完成。", buildRewriteStageSnapshot(question, historySummary, rewriteResult));
            }
        }
        catch (RuntimeException exception) {
            if (traceRecorder != null) {
                traceRecorder.failStage(
                    rewriteStage,
                    "问题改写失败。",
                    exception.getMessage(),
                    buildRewriteStageSnapshot(question, historySummary, null)
                );
            }
            throw exception;
        }

        String rewriteQuestion = rewriteResult == null ? safeText(question) : firstNonBlank(rewriteResult.getRewrittenQuestion(), safeText(question));
        List<String> rewriteSubQuestions = rewriteResult == null || rewriteResult.getSubQuestions() == null || rewriteResult.getSubQuestions().isEmpty()
            ? List.of(rewriteQuestion)
            : rewriteResult.getSubQuestions();

        Long routedDocumentId = selectedDocumentId;
        String routedDocumentName = selectedDocumentName;
        Long routedTaskId = selectedTaskId;
        List<Long> routedDocumentIds = routedDocumentId == null ? List.of() : List.of(routedDocumentId);
        List<Long> routedTaskIds = routedTaskId == null ? List.of() : List.of(routedTaskId);
        if (chatMode == ChatQueryMode.AUTO_DOCUMENT) {
            KnowledgeRouteContext routeContext = buildRouteContext(question, rewriteQuestion, knowledgeBaseSelection);
            KnowledgeRouteDecision routeDecision = knowledgeRouteService.route(routeContext);
            knowledgeRouteService.recordAutoRoute(conversationId, taskInfo.exchangeId(), routeContext, routeDecision);
            List<DocumentRouteCandidate> candidateDocuments = selectAutoCandidates(routeDecision, question, rewriteQuestion, allowedDocuments(knowledgeBaseSelection));
            boolean lowConfidenceMultiDocumentRetrieval = shouldAllowLowConfidenceMultiDocumentRetrieval(routeDecision, candidateDocuments);
            if (lowConfidenceMultiDocumentRetrieval) {
                log.info("自动知识路由低置信多文档候选进入检索: conversationId={}, confidence={}, candidateDocumentCount={}, threshold=[{}, {})",
                    conversationId,
                    routeDecision == null || routeDecision.getConfidence() == null ? "" : routeDecision.getConfidence().toPlainString(),
                    candidateDocuments.size(),
                    multiDocumentRetrievalThreshold(),
                    confidentDocumentThreshold());
            }
            if (shouldAskClarification(routeDecision, candidateDocuments, lowConfidenceMultiDocumentRetrieval)) {
                recordAutoDocumentRouteTrace(traceRecorder, routeDecision, candidateDocuments, true, false, null);
                return basePlan(question, chatMode, memoryContext, historyPlanningContext, historySummary, answerHistoryContext, currentDate, currentDateText,
                    requiresCurrentDateAnchoring, requiresFreshSearch, knowledgeBaseSelection)
                    .mode(ExecutionMode.CLARIFICATION)
                    .rewriteQuestion(rewriteQuestion)
                    .rewriteSubQuestions(rewriteSubQuestions)
                    .retrievalQuestion(rewriteQuestion)
                    .retrievalSubQuestions(rewriteSubQuestions)
                    .retrievalDocumentIds(candidateDocuments.stream()
                        .map(DocumentRouteCandidate::getDocumentId)
                        .filter(StrUtil::isNotBlank)
                        .map(Long::valueOf)
                        .toList())
                    .retrievalTaskIds(candidateDocuments.stream()
                        .map(DocumentRouteCandidate::getLastIndexTaskId)
                        .filter(StrUtil::isNotBlank)
                        .map(Long::valueOf)
                        .toList())
                    .clarificationReply(buildClarificationReply(question, routeDecision, candidateDocuments))
                    .clarificationOptions(buildClarificationOptions(candidateDocuments))
                    .clarificationReason(buildClarificationReason(routeDecision, candidateDocuments))
                    .build();
            }
            boolean confidentTopDocument = routeDecision != null
                && routeDecision.getConfidence() != null
                && routeDecision.getConfidence().doubleValue() >= confidentDocumentThreshold();
            DocumentRouteCandidate topDocument = confidentTopDocument && !candidateDocuments.isEmpty() ? candidateDocuments.get(0) : null;
            if (topDocument != null && StrUtil.isNotBlank(topDocument.getDocumentId()) && StrUtil.isNotBlank(topDocument.getLastIndexTaskId())) {
                routedDocumentId = Long.valueOf(topDocument.getDocumentId());
                routedDocumentName = topDocument.getDocumentName();
                routedTaskId = Long.valueOf(topDocument.getLastIndexTaskId());
            }
            else {
                routedDocumentId = null;
                routedDocumentName = "";
                routedTaskId = null;
            }
            routedDocumentIds = candidateDocuments.stream()
                .map(DocumentRouteCandidate::getDocumentId)
                .filter(StrUtil::isNotBlank)
                .map(Long::valueOf)
                .toList();
            routedTaskIds = candidateDocuments.stream()
                .map(DocumentRouteCandidate::getLastIndexTaskId)
                .filter(StrUtil::isNotBlank)
                .map(Long::valueOf)
                .toList();
            recordAutoDocumentRouteTrace(traceRecorder, routeDecision, candidateDocuments, false, confidentTopDocument, topDocument,
                lowConfidenceMultiDocumentRetrieval);
        }
        else if (chatMode == ChatQueryMode.DOCUMENT) {
            knowledgeRouteService.recordShadowRoute(conversationId, taskInfo.exchangeId(), selectedDocumentId,
                buildRouteContext(question, rewriteQuestion, knowledgeBaseSelection));
        }

        ConversationTraceRecorder.StageHandle routeStage = traceRecorder == null
            ? null
            : traceRecorder.startStage(ConversationTraceStageCode.ROUTE, ExecutionMode.RETRIEVAL.name(), "正在判定图查询还是混合检索。", null);
        DocumentNavigationDecision navigationDecision;
        try {
            navigationDecision = documentQuestionRouter.route(
                routedDocumentId,
                question,
                rewriteResult,
                historySummary,
                memoryContext == null ? "" : memoryContext.getAnswerRecentTranscript()
            );
            QueryUnderstandingResult queryUnderstanding = navigationDecision == null ? null : navigationDecision.getQueryUnderstanding();
            StructureNavigationResult structureNavigationResult = resolveStructureNavigationResult(
                navigationDecision,
                routedDocumentId,
                routedTaskId
            );
            if (traceRecorder != null) {
                traceRecorder.completeStage(routeStage, "执行路由完成。", Map.of(
                    "executionMode", navigationDecision == null || navigationDecision.getExecutionMode() == null ? "" : navigationDecision.getExecutionMode().name(),
                    "targetSectionHint", navigationDecision == null || navigationDecision.getStructureAnchor() == null ? "" : StrUtil.blankToDefault(navigationDecision.getStructureAnchor().getTargetSectionHint(), ""),
                    "targetItemIndex", navigationDecision == null || navigationDecision.getItemAnchor() == null || navigationDecision.getItemAnchor().getItemIndex() == null
                        ? ""
                        : String.valueOf(navigationDecision.getItemAnchor().getItemIndex()),
                    "retrievalIntent", navigationDecision == null || navigationDecision.getRetrievalIntent() == null
                        ? RetrievalIntent.GENERAL.name()
                        : navigationDecision.getRetrievalIntent().name(),
                    "queryUnderstanding", buildQueryUnderstandingTrace(queryUnderstanding),
                    "structureNavigation", buildStructureNavigationTrace(structureNavigationResult),
                    "navigationSummary", navigationDecision == null ? "" : StrUtil.blankToDefault(navigationDecision.getSummaryText(), "")
                ));
            }
        }
        catch (RuntimeException exception) {
            if (traceRecorder != null) {
                traceRecorder.failStage(routeStage, "执行路由失败。", exception.getMessage(), null);
            }
            throw exception;
        }

        ExecutionMode executionMode = navigationDecision == null || navigationDecision.getExecutionMode() == null
            ? ExecutionMode.RETRIEVAL
            : navigationDecision.getExecutionMode();
        String retrievalQuestion = navigationDecision == null || navigationDecision.getRetrievalPlan() == null
            ? rewriteQuestion
            : firstNonBlank(navigationDecision.getRetrievalPlan().getRetrievalQuestion(), rewriteQuestion);
        List<String> retrievalSubQuestions = navigationDecision == null || navigationDecision.getRetrievalPlan() == null
            || navigationDecision.getRetrievalPlan().getSubQuestions() == null || navigationDecision.getRetrievalPlan().getSubQuestions().isEmpty()
            ? rewriteSubQuestions
            : navigationDecision.getRetrievalPlan().getSubQuestions();
        RetrievalIntent retrievalIntent = navigationDecision == null || navigationDecision.getRetrievalIntent() == null
            ? RetrievalIntent.GENERAL
            : navigationDecision.getRetrievalIntent();
        QueryUnderstandingResult queryUnderstanding = navigationDecision == null ? null : navigationDecision.getQueryUnderstanding();
        List<EvidenceAnchor> scopedEvidenceAnchors = filterEvidenceAnchors(
            recentEvidenceAnchors,
            chatMode,
            routedDocumentId,
            knowledgeBaseSelection
        );
        appendAnchorHints(historyPlanningContext, scopedEvidenceAnchors);
        AnswerHistoryContext routedAnswerHistoryContext = buildAnswerHistoryContext(
            question,
            memoryContext == null ? "" : memoryContext.getAnswerRecentTranscript(),
            queryUnderstanding,
            scopedEvidenceAnchors
        );

        log.info("聊天编排完成: conversationId={}, chatMode={}, originalQuestion='{}', rewriteQuestion='{}', retrievalQuestion='{}', executionMode={}, retrievalIntent={}, targetSection='{}'",
            conversationId,
            chatMode,
            safeText(question),
            rewriteQuestion,
            retrievalQuestion,
            executionMode,
            retrievalIntent,
            navigationDecision == null || navigationDecision.getStructureAnchor() == null ? "" : safeText(navigationDecision.getStructureAnchor().getTargetSectionHint()));

        return basePlan(question, chatMode, memoryContext, historyPlanningContext, historySummary, routedAnswerHistoryContext, currentDate, currentDateText,
            requiresCurrentDateAnchoring, requiresFreshSearch, knowledgeBaseSelection)
            .mode(executionMode)
            .navigationDecision(navigationDecision)
            .queryUnderstanding(queryUnderstanding)
            .retrievalIntent(retrievalIntent)
            .rewriteQuestion(rewriteQuestion)
            .rewriteSubQuestions(rewriteSubQuestions)
            .retrievalQuestion(retrievalQuestion)
            .retrievalSubQuestions(retrievalSubQuestions)
            .selectedDocumentId(routedDocumentId)
            .selectedDocumentName(routedDocumentName)
            .selectedTaskId(routedTaskId)
            .retrievalDocumentIds(routedDocumentIds)
            .retrievalTaskIds(routedTaskIds)
            .noEvidenceReply(buildDocumentModeNoEvidenceReply(requiresFreshSearch, queryUnderstanding))
            .build();
    }

    private StructureNavigationResult resolveStructureNavigationResult(DocumentNavigationDecision navigationDecision,
                                                                      Long routedDocumentId,
                                                                      Long routedTaskId) {
        if (structureNavigationResolver == null || navigationDecision == null || routedDocumentId == null) {
            return null;
        }
        QueryUnderstandingResult queryUnderstanding = navigationDecision.getQueryUnderstanding();
        StructureNavigationIntent intent = queryUnderstanding == null ? null : queryUnderstanding.getStructureNavigationIntent();
        if (intent == null) {
            return null;
        }
        StructureNavigationResult result = structureNavigationResolver.resolve(
            routedDocumentId,
            routedTaskId,
            intent,
            navigationDecision.getStructureAnchor()
        );
        navigationDecision.setStructureNavigationResult(result);
        return result;
    }

    private Map<String, Object> buildQueryUnderstandingTrace(QueryUnderstandingResult queryUnderstanding) {
        if (queryUnderstanding == null) {
            return Map.of();
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("queryType", queryUnderstanding.getQueryType() == null ? "" : queryUnderstanding.getQueryType().name());
        snapshot.put("channels", queryUnderstanding.getChannels() == null ? List.of() : queryUnderstanding.getChannels().stream().map(Enum::name).toList());
        snapshot.put("entities", queryUnderstanding.getEntities() == null ? List.of() : queryUnderstanding.getEntities());
        snapshot.put("targetEntities", queryUnderstanding.getTargetEntities() == null ? List.of() : queryUnderstanding.getTargetEntities());
        snapshot.put("excludedEntities", queryUnderstanding.getExcludedEntities() == null ? List.of() : queryUnderstanding.getExcludedEntities());
        snapshot.put("sectionAnchors", queryUnderstanding.getSectionAnchors() == null ? List.of() : queryUnderstanding.getSectionAnchors());
        snapshot.put("structureNavigationIntent", buildStructureNavigationIntentTrace(queryUnderstanding.getStructureNavigationIntent()));
        snapshot.put("tableOps", queryUnderstanding.getTableOps() == null ? List.of() : queryUnderstanding.getTableOps());
        snapshot.put("negativeBoundary", queryUnderstanding.isNegativeBoundary());
        snapshot.put("answerExpectation", StrUtil.blankToDefault(queryUnderstanding.getAnswerExpectation(), ""));
        snapshot.put("confidence", queryUnderstanding.getConfidence());
        snapshot.put("source", StrUtil.blankToDefault(queryUnderstanding.getSource(), ""));
        snapshot.put("reasons", queryUnderstanding.getReasons() == null ? List.of() : queryUnderstanding.getReasons());
        return snapshot;
    }

    private Map<String, Object> buildStructureNavigationIntentTrace(StructureNavigationIntent intent) {
        if (intent == null) {
            return Map.of();
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("operations", intent.getOperations() == null ? List.of() : intent.getOperations().stream().map(Enum::name).toList());
        snapshot.put("anchorStructureNodeId", intent.getAnchorStructureNodeId() == null ? "" : String.valueOf(intent.getAnchorStructureNodeId()));
        snapshot.put("anchorSectionPath", StrUtil.blankToDefault(intent.getAnchorSectionPath(), ""));
        snapshot.put("anchorCanonicalPath", StrUtil.blankToDefault(intent.getAnchorCanonicalPath(), ""));
        snapshot.put("sectionAnchors", intent.getSectionAnchors() == null ? List.of() : intent.getSectionAnchors());
        snapshot.put("confidence", intent.getConfidence());
        snapshot.put("source", StrUtil.blankToDefault(intent.getSource(), ""));
        return snapshot;
    }

    private Map<String, Object> buildStructureNavigationTrace(StructureNavigationResult result) {
        if (result == null) {
            return Map.of();
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("documentId", result.getDocumentId() == null ? "" : String.valueOf(result.getDocumentId()));
        snapshot.put("anchorNodeId", result.getAnchorNodeId() == null ? "" : String.valueOf(result.getAnchorNodeId()));
        snapshot.put("current", buildStructureNodeTrace(result.getCurrent()));
        snapshot.put("parent", buildStructureNodeTrace(result.getParent()));
        snapshot.put("previous", buildStructureNodeTrace(result.getPreviousSibling()));
        snapshot.put("next", buildStructureNodeTrace(result.getNextSibling()));
        snapshot.put("directChildren", result.getDirectChildren() == null
            ? List.of()
            : result.getDirectChildren().stream().map(this::buildStructureNodeTrace).toList());
        snapshot.put("deterministic", result.isDeterministic());
        snapshot.put("missReason", StrUtil.blankToDefault(result.getMissReason(), ""));
        return snapshot;
    }

    private Map<String, Object> buildStructureNodeTrace(SuperAgentDocumentStructureNode node) {
        if (node == null) {
            return Map.of();
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("nodeId", node.getId() == null ? "" : String.valueOf(node.getId()));
        snapshot.put("nodeNo", node.getNodeNo() == null ? "" : String.valueOf(node.getNodeNo()));
        snapshot.put("title", StrUtil.blankToDefault(node.getTitle(), ""));
        snapshot.put("sectionPath", StrUtil.blankToDefault(node.getSectionPath(), ""));
        snapshot.put("canonicalPath", StrUtil.blankToDefault(node.getCanonicalPath(), ""));
        snapshot.put("parentNodeId", node.getParentNodeId() == null ? "" : String.valueOf(node.getParentNodeId()));
        snapshot.put("prevSiblingNodeId", node.getPrevSiblingNodeId() == null ? "" : String.valueOf(node.getPrevSiblingNodeId()));
        snapshot.put("nextSiblingNodeId", node.getNextSiblingNodeId() == null ? "" : String.valueOf(node.getNextSiblingNodeId()));
        return snapshot;
    }

    private ConversationExecutionPlan.ConversationExecutionPlanBuilder basePlan(String question,
                                                                                ChatQueryMode chatMode,
                                                                                ConversationMemoryContext memoryContext,
                                                                                HistoryPlanningContext historyPlanningContext,
                                                                                String historySummary,
                                                                                AnswerHistoryContext answerHistoryContext,
                                                                                LocalDate currentDate,
                                                                                String currentDateText,
                                                                                boolean requiresCurrentDateAnchoring,
                                                                                boolean requiresFreshSearch,
                                                                                KnowledgeBaseSelectionSnapshot knowledgeBaseSelection) {
        return ConversationExecutionPlan.builder()
            .chatMode(chatMode)
            .originalQuestion(question)
            .agentQuestion(question)
            .rewriteQuestion(question)
            .rewriteSubQuestions(List.of(question))
            .retrievalQuestion(question)
            .retrievalSubQuestions(List.of(question))
            .historySummary(historySummary)
            .longTermSummary(memoryContext.getLongTermSummary())
            .historyPlanningContext(historyPlanningContext)
            .recentHistoryTranscript(memoryContext.getRecentTranscript())
            .answerRecentTranscript(memoryContext.getAnswerRecentTranscript())
            .answerHistoryContext(answerHistoryContext)
            .historyCompressionApplied(memoryContext.isCompressionApplied())
            .historyCoveredExchangeId(memoryContext.getCoveredExchangeId())
            .historyCoveredExchangeCount(memoryContext.getCoveredExchangeCount())
            .historyCompressionCount(memoryContext.getCompressionCount())
            .currentDate(currentDate)
            .currentDateText(currentDateText)
            .requiresCurrentDateAnchoring(requiresCurrentDateAnchoring)
            .requiresFreshSearch(requiresFreshSearch)
            .knowledgeBaseSelectionMode(selectionMode(knowledgeBaseSelection))
            .selectedKnowledgeBaseIds(selectedKnowledgeBaseIds(knowledgeBaseSelection))
            .selectedKnowledgeBaseNames(selectedKnowledgeBaseNames(knowledgeBaseSelection))
            .allowedKnowledgeBaseDocumentIds(allowedDocumentIds(knowledgeBaseSelection))
            .ragRuntimeOptions(knowledgeBaseSelection == null ? null : knowledgeBaseSelection.getRagRuntimeOptions())
            .noEvidenceReply(properties.getNoEvidenceReply());
    }

    private void recordAutoDocumentRouteTrace(ConversationTraceRecorder traceRecorder,
                                              KnowledgeRouteDecision routeDecision,
                                              List<DocumentRouteCandidate> candidateDocuments,
                                              boolean clarificationRequired,
                                              boolean confidentTopDocument,
                                              DocumentRouteCandidate topDocument) {
        recordAutoDocumentRouteTrace(traceRecorder, routeDecision, candidateDocuments, clarificationRequired, confidentTopDocument, topDocument, false);
    }

    private void recordAutoDocumentRouteTrace(ConversationTraceRecorder traceRecorder,
                                              KnowledgeRouteDecision routeDecision,
                                              List<DocumentRouteCandidate> candidateDocuments,
                                              boolean clarificationRequired,
                                              boolean confidentTopDocument,
                                              DocumentRouteCandidate topDocument,
                                              boolean lowConfidenceMultiDocumentRetrieval) {
        if (traceRecorder == null) {
            return;
        }
        Map<String, Object> snapshot = buildAutoDocumentRouteSnapshot(
            routeDecision,
            candidateDocuments,
            clarificationRequired,
            confidentTopDocument,
            topDocument,
            lowConfidenceMultiDocumentRetrieval
        );
        ConversationTraceRecorder.StageHandle autoRouteStage = traceRecorder.startStage(
            ConversationTraceStageCode.ROUTE,
            "AUTO_DOCUMENT",
            "正在执行知识范围、主题、候选文档路由。",
            snapshot
        );
        traceRecorder.completeStage(
            autoRouteStage,
            clarificationRequired ? "知识路由存在歧义，已转入澄清。" : "知识范围路由完成。",
            snapshot
        );
    }

    private Map<String, Object> buildAutoDocumentRouteSnapshot(KnowledgeRouteDecision routeDecision,
                                                               List<DocumentRouteCandidate> candidateDocuments,
                                                               boolean clarificationRequired,
                                                               boolean confidentTopDocument,
                                                               DocumentRouteCandidate topDocument) {
        return buildAutoDocumentRouteSnapshot(routeDecision, candidateDocuments, clarificationRequired, confidentTopDocument, topDocument, false);
    }

    private Map<String, Object> buildAutoDocumentRouteSnapshot(KnowledgeRouteDecision routeDecision,
                                                               List<DocumentRouteCandidate> candidateDocuments,
                                                               boolean clarificationRequired,
                                                               boolean confidentTopDocument,
                                                               DocumentRouteCandidate topDocument,
                                                               boolean lowConfidenceMultiDocumentRetrieval) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("routeStatus", routeDecision == null ? "" : StrUtil.blankToDefault(routeDecision.getRouteStatus(), ""));
        snapshot.put("confidence", routeDecision == null || routeDecision.getConfidence() == null ? "" : routeDecision.getConfidence().toPlainString());
        snapshot.put("reason", routeDecision == null ? "" : StrUtil.blankToDefault(routeDecision.getReason(), ""));
        snapshot.put("clarificationRequired", clarificationRequired);
        snapshot.put("confidentTopDocument", confidentTopDocument);
        snapshot.put("lowConfidenceMultiDocumentRetrieval", lowConfidenceMultiDocumentRetrieval);
        snapshot.put("confidentDocumentThreshold", confidentDocumentThreshold());
        snapshot.put("multiDocumentRetrievalThreshold", multiDocumentRetrievalThreshold());
        snapshot.put("topDocumentId", topDocument == null ? "" : StrUtil.blankToDefault(topDocument.getDocumentId(), ""));
        snapshot.put("topDocumentName", topDocument == null ? "" : StrUtil.blankToDefault(topDocument.getDocumentName(), ""));
        snapshot.put("scopeCandidates", buildScopeRouteTrace(routeDecision));
        snapshot.put("topicCandidates", buildTopicRouteTrace(routeDecision));
        snapshot.put("candidateDocuments", buildDocumentRouteTrace(candidateDocuments));
        snapshot.put("candidateDocumentCount", candidateDocuments == null ? 0 : candidateDocuments.size());
        return snapshot;
    }

    private List<Map<String, Object>> buildScopeRouteTrace(KnowledgeRouteDecision routeDecision) {
        if (routeDecision == null || routeDecision.getScopes() == null) {
            return List.of();
        }
        return routeDecision.getScopes().stream()
            .limit(5)
            .map(scope -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("scopeId", scope.getScopeId() == null ? "" : String.valueOf(scope.getScopeId()));
                item.put("scopeName", StrUtil.blankToDefault(scope.getScopeName(), ""));
                item.put("score", scope.getScore() == null ? "" : scope.getScore().toPlainString());
                item.put("reason", StrUtil.blankToDefault(scope.getReason(), ""));
                return item;
            })
            .toList();
    }

    private List<Map<String, Object>> buildTopicRouteTrace(KnowledgeRouteDecision routeDecision) {
        if (routeDecision == null || routeDecision.getTopics() == null) {
            return List.of();
        }
        return routeDecision.getTopics().stream()
            .limit(5)
            .map(topic -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("scopeId", topic.getScopeId() == null ? "" : String.valueOf(topic.getScopeId()));
                item.put("topicId", topic.getTopicId() == null ? "" : String.valueOf(topic.getTopicId()));
                item.put("topicName", StrUtil.blankToDefault(topic.getTopicName(), ""));
                item.put("score", topic.getScore() == null ? "" : topic.getScore().toPlainString());
                item.put("reason", StrUtil.blankToDefault(topic.getReason(), ""));
                return item;
            })
            .toList();
    }

    private List<Map<String, Object>> buildDocumentRouteTrace(List<DocumentRouteCandidate> candidateDocuments) {
        if (candidateDocuments == null || candidateDocuments.isEmpty()) {
            return List.of();
        }
        return candidateDocuments.stream()
            .limit(8)
            .map(document -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("documentId", StrUtil.blankToDefault(document.getDocumentId(), ""));
                item.put("documentName", StrUtil.blankToDefault(document.getDocumentName(), ""));
                item.put("lastIndexTaskId", StrUtil.blankToDefault(document.getLastIndexTaskId(), ""));
                item.put("score", document.getScore() == null ? "" : document.getScore().toPlainString());
                item.put("reason", StrUtil.blankToDefault(document.getReason(), ""));
                return item;
            })
            .toList();
    }

    private Map<String, Object> buildRewriteStageSnapshot(String question,
                                                          String historySummary,
                                                          RagRewriteResult rewriteResult) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("originalQuestion", StrUtil.blankToDefault(question, ""));
        snapshot.put("historyContext", StrUtil.blankToDefault(historySummary, ""));
        snapshot.put("rewriteQuestion", rewriteResult == null ? "" : StrUtil.blankToDefault(rewriteResult.getRewrittenQuestion(), ""));
        snapshot.put("subQuestions", rewriteResult == null || rewriteResult.getSubQuestions() == null ? List.of() : rewriteResult.getSubQuestions());
        snapshot.put("rawModelOutput", rewriteResult == null ? "" : StrUtil.blankToDefault(rewriteResult.getRawModelOutput(), ""));

        ChatRagProperties.RewriteOptionsProperties rewriteOptions = properties == null ? null : properties.getRewriteOptions();
        boolean overrideEnabled = rewriteOptions != null && rewriteOptions.isEnabled();
        snapshot.put("rewriteOverrideEnabled", overrideEnabled);
        snapshot.put("rewriteTemperature", rewriteOptions == null ? null : rewriteOptions.getTemperature());
        snapshot.put("rewriteTopP", rewriteOptions == null ? null : rewriteOptions.getTopP());
        snapshot.put("rewriteThinking", rewriteOptions == null ? null : rewriteOptions.getThinking());
        return snapshot;
    }

    private ConversationMemoryContext summarizeHistory(String conversationId, ConversationTraceRecorder traceRecorder) {
        return conversationMemoryService.loadMemoryContext(conversationId, traceRecorder);
    }

    private HistoryPlanningContext buildHistoryPlanningContext(ConversationMemoryContext memoryContext) {
        ConversationSummaryPayload payload = memoryContext == null ? null : memoryContext.getSummaryPayload();
        if (payload == null) {
            return HistoryPlanningContext.builder().build();
        }
        return HistoryPlanningContext.builder()
            .conversationGoal(payload.getConversationGoal())
            .stableFacts(payload.getStableFacts() == null ? List.of() : new ArrayList<>(payload.getStableFacts()))
            .pendingQuestions(payload.getPendingQuestions() == null ? List.of() : new ArrayList<>(payload.getPendingQuestions()))
            .retrievalHints(payload.getRetrievalHints() == null ? List.of() : new ArrayList<>(payload.getRetrievalHints()))
            .queryContextHints(payload.getRetrievalHints() == null ? List.of() : new ArrayList<>(payload.getRetrievalHints()))
            .build();
    }

    private String buildPlanningHistory(ConversationMemoryContext memoryContext,
                                        HistoryPlanningContext historyPlanningContext) {
        String structuredHistory = buildStructuredPlanningHistory(historyPlanningContext);
        String recentTranscript = memoryContext == null ? "" : safeText(memoryContext.getRecentTranscript());
        int maxChars = Math.max(1, properties.getPlanningHistoryMaxChars());
        if (recentTranscript.isBlank()) {
            return clipHead(structuredHistory, maxChars);
        }
        int recentBudget = Math.min(Math.max(maxChars / 2, (int) Math.round(maxChars * 0.65D)), maxChars);
        String recentPart = clipTail(recentTranscript, recentBudget);
        int structuredBudget = Math.max(0, maxChars - recentPart.length() - (recentPart.isBlank() ? 0 : 2));
        String structuredPart = clipHead(structuredHistory, structuredBudget);
        return joinNonBlank(structuredPart, recentPart);
    }

    private AnswerHistoryContext buildAnswerHistoryContext(String question,
                                                           String answerRecentTranscript,
                                                           QueryUnderstandingResult queryUnderstanding) {
        return buildAnswerHistoryContext(question, answerRecentTranscript, queryUnderstanding, List.of());
    }

    private AnswerHistoryContext buildAnswerHistoryContext(String question,
                                                           String answerRecentTranscript,
                                                           QueryUnderstandingResult queryUnderstanding,
                                                           List<EvidenceAnchor> recentEvidenceAnchors) {
        return answerHistoryContextAssembler.assemble(question, answerRecentTranscript, queryUnderstanding, recentEvidenceAnchors);
    }

    private List<EvidenceAnchor> loadRecentEvidenceAnchors(String conversationId) {
        if (conversationEvidenceAnchorService == null || StrUtil.isBlank(conversationId)) {
            return List.of();
        }
        try {
            return conversationEvidenceAnchorService.loadRecentEvidenceAnchors(conversationId, 5);
        }
        catch (RuntimeException exception) {
            log.warn("加载上一轮 evidence anchor 失败: conversationId={}, message={}",
                conversationId,
                exception.getMessage(),
                exception);
            return List.of();
        }
    }

    private List<EvidenceAnchor> filterEvidenceAnchors(List<EvidenceAnchor> anchors,
                                                       ChatQueryMode chatMode,
                                                       Long selectedDocumentId,
                                                       KnowledgeBaseSelectionSnapshot knowledgeBaseSelection) {
        if (anchors == null || anchors.isEmpty()) {
            return List.of();
        }
        if (chatMode == ChatQueryMode.DOCUMENT && selectedDocumentId != null) {
            return anchors.stream()
                .filter(anchor -> anchor != null && Objects.equals(anchor.getDocumentId(), selectedDocumentId))
                .toList();
        }
        if (chatMode == ChatQueryMode.AUTO_DOCUMENT
            && knowledgeBaseSelection != null
            && knowledgeBaseSelection.getAllowedDocumentIds() != null
            && !knowledgeBaseSelection.getAllowedDocumentIds().isEmpty()) {
            return anchors.stream()
                .filter(anchor -> anchor != null && anchor.getDocumentId() != null)
                .filter(anchor -> knowledgeBaseSelection.getAllowedDocumentIds().contains(anchor.getDocumentId()))
                .toList();
        }
        return anchors;
    }

    private void appendAnchorHints(HistoryPlanningContext historyPlanningContext, List<EvidenceAnchor> anchors) {
        if (historyPlanningContext == null || anchors == null || anchors.isEmpty()) {
            return;
        }
        List<String> hints = new ArrayList<>(historyPlanningContext.getQueryContextHints() == null
            ? List.of()
            : historyPlanningContext.getQueryContextHints());
        anchors.stream()
            .map(this::anchorHint)
            .filter(StrUtil::isNotBlank)
            .limit(5)
            .forEach(hints::add);
        historyPlanningContext.setQueryContextHints(hints);
    }

    private String anchorHint(EvidenceAnchor anchor) {
        if (anchor == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        appendHintPart(builder, "documentId", anchor.getDocumentId());
        appendHintPart(builder, "sectionPath", anchor.getSectionPath());
        appendHintPart(builder, "structureNodeId", anchor.getStructureNodeId());
        appendHintPart(builder, "parentBlockId", anchor.getParentBlockId());
        appendHintPart(builder, "chunkId", anchor.getChunkId());
        return builder.toString().trim();
    }

    private void appendHintPart(StringBuilder builder, String name, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("; ");
        }
        builder.append(name).append('=').append(text);
    }

    private String buildStructuredPlanningHistory(HistoryPlanningContext historyPlanningContext) {
        StringBuilder builder = new StringBuilder();
        if (historyPlanningContext == null) {
            return "";
        }
        appendSection(builder, "会话目标", historyPlanningContext.getConversationGoal());
        appendBulletSection(builder, "已确认事实", historyPlanningContext.getStableFacts());
        appendBulletSection(builder, "待跟进问题", historyPlanningContext.getPendingQuestions());
        appendBulletSection(builder, "检索提示", historyPlanningContext.getRetrievalHints());
        return builder.toString().trim();
    }

    private void appendSection(StringBuilder builder, String title, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append("【").append(title).append("】\n").append(content.trim()).append('\n');
    }

    private void appendBulletSection(StringBuilder builder, String title, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append("【").append(title).append("】\n");
        values.stream()
            .filter(item -> item != null && !item.isBlank())
            .limit(5)
            .forEach(item -> builder.append("- ").append(item.trim()).append('\n'));
    }

    private String clipHead(String text, int maxChars) {
        String normalized = safeText(text);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        if (maxChars <= 1) {
            return "";
        }
        return normalized.substring(0, maxChars - 1) + "…";
    }

    private String clipTail(String text, int maxChars) {
        String normalized = safeText(text);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        if (maxChars <= 1) {
            return "";
        }
        int start = Math.max(0, normalized.length() - (maxChars - 1));
        return "…" + normalized.substring(start);
    }

    private String joinNonBlank(String left, String right) {
        if (left == null || left.isBlank()) {
            return safeText(right);
        }
        if (right == null || right.isBlank()) {
            return safeText(left);
        }
        return left.trim() + "\n\n" + right.trim();
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }

    private String firstNonBlank(String left, String right) {
        if (StrUtil.isNotBlank(left)) {
            return left.trim();
        }
        return safeText(right);
    }

    private List<DocumentRouteCandidate> selectAutoCandidates(KnowledgeRouteDecision routeDecision,
                                                              String question,
                                                              String rewriteQuestion,
                                                              List<KnowledgeDocumentDescriptor> allowedDocuments) {
        if (routeDecision == null || routeDecision.getDocuments() == null || routeDecision.getDocuments().isEmpty()) {
            return expandCandidatesByDocumentProfile(question, rewriteQuestion, allowedDocuments, 5);
        }
        int candidateLimit = routeDecision.getConfidence() != null && routeDecision.getConfidence().doubleValue() >= 0.80D ? 3 : 5;
        List<Long> allowedDocumentIds = allowedDocuments == null
            ? List.of()
            : allowedDocuments.stream().map(KnowledgeDocumentDescriptor::getDocumentId).filter(Objects::nonNull).toList();
        List<DocumentRouteCandidate> candidates = routeDecision.getDocuments().stream()
            .filter(item -> StrUtil.isNotBlank(item.getDocumentId()) && StrUtil.isNotBlank(item.getLastIndexTaskId()))
            .filter(item -> allowedDocumentIds.isEmpty() || allowedDocumentIds.contains(Long.valueOf(item.getDocumentId())))
            .limit(candidateLimit)
            .toList();
        if (candidates.isEmpty()) {
            return expandCandidatesByDocumentProfile(question, rewriteQuestion, allowedDocuments, candidateLimit);
        }
        if (routeDecision.getConfidence() != null && routeDecision.getConfidence().doubleValue() < confidentDocumentThreshold()) {
            return mergeCandidates(candidates, expandCandidatesByDocumentProfile(question, rewriteQuestion, allowedDocuments, candidateLimit), candidateLimit);
        }
        return candidates;
    }

    private List<DocumentRouteCandidate> expandCandidatesByDocumentProfile(String question,
                                                                           String rewriteQuestion,
                                                                           List<KnowledgeDocumentDescriptor> allowedDocuments,
                                                                           int limit) {
        List<KnowledgeDocumentDescriptor> descriptors = allowedDocuments == null || allowedDocuments.isEmpty()
            ? List.of()
            : allowedDocuments;
        if (descriptors == null || descriptors.isEmpty()) {
            return List.of();
        }
        List<String> queryTerms = extractRouteExpansionTerms(question, rewriteQuestion);
        return descriptors.stream()
            .sorted((left, right) -> Double.compare(
                descriptorRouteScore(right, queryTerms),
                descriptorRouteScore(left, queryTerms)
            ))
            .limit(Math.max(1, limit))
            .map(item -> new DocumentRouteCandidate(
                String.valueOf(item.getDocumentId()),
                item.getDocumentName(),
                item.getLastIndexTaskId() == null ? "" : String.valueOf(item.getLastIndexTaskId()),
                BigDecimal.valueOf(descriptorRouteScore(item, queryTerms)).setScale(4, RoundingMode.HALF_UP),
                "低置信度时基于文档画像扩展候选范围"
            ))
            .toList();
    }

    private KnowledgeRouteContext buildRouteContext(String question,
                                                    String rewriteQuestion,
                                                    KnowledgeBaseSelectionSnapshot knowledgeBaseSelection) {
        return KnowledgeRouteContext.builder()
            .question(question)
            .rewriteQuestion(rewriteQuestion)
            .knowledgeBaseSelectionMode(selectionMode(knowledgeBaseSelection))
            .selectedKnowledgeBaseIds(selectedKnowledgeBaseIds(knowledgeBaseSelection))
            .selectedKnowledgeBaseNames(selectedKnowledgeBaseNames(knowledgeBaseSelection))
            .allowedDocuments(allowedDocuments(knowledgeBaseSelection))
            .allowedDocumentIds(allowedDocumentIds(knowledgeBaseSelection))
            .build();
    }

    private KnowledgeBaseSelectionMode selectionMode(KnowledgeBaseSelectionSnapshot knowledgeBaseSelection) {
        return knowledgeBaseSelection == null || knowledgeBaseSelection.getSelectionMode() == null
            ? KnowledgeBaseSelectionMode.NONE
            : knowledgeBaseSelection.getSelectionMode();
    }

    private List<Long> selectedKnowledgeBaseIds(KnowledgeBaseSelectionSnapshot knowledgeBaseSelection) {
        return knowledgeBaseSelection == null || knowledgeBaseSelection.getSelectedKnowledgeBaseIds() == null
            ? List.of()
            : knowledgeBaseSelection.getSelectedKnowledgeBaseIds();
    }

    private List<String> selectedKnowledgeBaseNames(KnowledgeBaseSelectionSnapshot knowledgeBaseSelection) {
        return knowledgeBaseSelection == null || knowledgeBaseSelection.getSelectedKnowledgeBaseNames() == null
            ? List.of()
            : knowledgeBaseSelection.getSelectedKnowledgeBaseNames();
    }

    private List<KnowledgeDocumentDescriptor> allowedDocuments(KnowledgeBaseSelectionSnapshot knowledgeBaseSelection) {
        return knowledgeBaseSelection == null || knowledgeBaseSelection.getAllowedDocuments() == null
            ? List.of()
            : knowledgeBaseSelection.getAllowedDocuments();
    }

    private List<Long> allowedDocumentIds(KnowledgeBaseSelectionSnapshot knowledgeBaseSelection) {
        return knowledgeBaseSelection == null || knowledgeBaseSelection.getAllowedDocumentIds() == null
            ? List.of()
            : knowledgeBaseSelection.getAllowedDocumentIds();
    }

    private List<DocumentRouteCandidate> mergeCandidates(List<DocumentRouteCandidate> primary,
                                                         List<DocumentRouteCandidate> secondary,
                                                         int limit) {
        LinkedHashMap<String, DocumentRouteCandidate> merged = new LinkedHashMap<>();
        primary.forEach(item -> merged.put(item.getDocumentId(), item));
        secondary.forEach(item -> merged.putIfAbsent(item.getDocumentId(), item));
        return merged.values().stream().limit(Math.max(1, limit)).toList();
    }

    private boolean shouldAskClarification(KnowledgeRouteDecision routeDecision,
                                           List<DocumentRouteCandidate> candidateDocuments,
                                           boolean lowConfidenceMultiDocumentRetrieval) {
        if (candidateDocuments == null || candidateDocuments.isEmpty()) {
            return true;
        }
        if (routeDecision == null || routeDecision.getDocuments() == null || routeDecision.getDocuments().isEmpty()) {
            return true;
        }
        if (routeDecision.getConfidence() == null) {
            return true;
        }
        if (routeDecision.getConfidence().doubleValue() < confidentDocumentThreshold()) {
            return !lowConfidenceMultiDocumentRetrieval;
        }
        if (candidateDocuments.size() < 2) {
            return false;
        }
        BigDecimal topScore = candidateDocuments.get(0).getScore();
        BigDecimal secondScore = candidateDocuments.get(1).getScore();
        if (topScore == null || secondScore == null) {
            return false;
        }
        return topScore.subtract(secondScore).doubleValue() <= 3D;
    }

    private boolean shouldAllowLowConfidenceMultiDocumentRetrieval(KnowledgeRouteDecision routeDecision,
                                                                   List<DocumentRouteCandidate> candidateDocuments) {
        if (routeDecision == null || routeDecision.getConfidence() == null || candidateDocuments == null || candidateDocuments.size() < 2) {
            return false;
        }
        double confidence = routeDecision.getConfidence().doubleValue();
        if (confidence < multiDocumentRetrievalThreshold() || confidence >= confidentDocumentThreshold()) {
            return false;
        }
        long validCandidateCount = candidateDocuments.stream()
            .filter(candidate -> candidate != null
                && StrUtil.isNotBlank(candidate.getDocumentId())
                && StrUtil.isNotBlank(candidate.getLastIndexTaskId())
                && candidate.getScore() != null
                && candidate.getScore().doubleValue() > 0D)
            .count();
        return validCandidateCount >= 2;
    }

    private double confidentDocumentThreshold() {
        ChatRagProperties.AutoRouteProperties autoRoute = properties == null ? null : properties.getAutoRoute();
        return autoRoute == null ? 0.55D : clampThreshold(autoRoute.getConfidentDocumentThreshold(), 0.55D);
    }

    private double multiDocumentRetrievalThreshold() {
        ChatRagProperties.AutoRouteProperties autoRoute = properties == null ? null : properties.getAutoRoute();
        double threshold = autoRoute == null ? 0.45D : clampThreshold(autoRoute.getMultiDocumentRetrievalThreshold(), 0.45D);
        return Math.min(threshold, confidentDocumentThreshold());
    }

    private double clampThreshold(double threshold, double fallback) {
        if (Double.isNaN(threshold) || Double.isInfinite(threshold)) {
            return fallback;
        }
        return Math.max(0D, Math.min(1D, threshold));
    }

    private String buildClarificationReply(String originalQuestion,
                                           KnowledgeRouteDecision routeDecision,
                                           List<DocumentRouteCandidate> candidateDocuments) {
        List<DocumentRouteCandidate> topCandidates = candidateDocuments == null ? List.of() : candidateDocuments.stream().limit(3).toList();
        if (topCandidates.isEmpty()) {
            return "当前我还不能稳定判断你想问哪份知识文档。请补充更具体的文档名、主题词，或者直接切换到“当前文档问答”后指定文档。";
        }
        StringBuilder builder = new StringBuilder("这个问题目前存在文档范围歧义，我先确认你想问哪一份：\n");
        for (int index = 0; index < topCandidates.size(); index++) {
            DocumentRouteCandidate item = topCandidates.get(index);
            builder.append(index + 1)
                .append(". 《")
                .append(StrUtil.blankToDefault(item.getDocumentName(), item.getDocumentId()))
                .append("》");
            builder.append('\n');
        }
        builder.append("你可以直接回复文档名，或者改用“当前文档问答”模式明确指定文档。");
        return builder.toString();
    }

    private List<String> buildClarificationOptions(List<DocumentRouteCandidate> candidateDocuments) {
        if (candidateDocuments == null || candidateDocuments.isEmpty()) {
            return List.of();
        }
        return candidateDocuments.stream()
            .limit(3)
            .map(item -> "我想问《" + StrUtil.blankToDefault(item.getDocumentName(), item.getDocumentId()) + "》")
            .toList();
    }

    private String buildClarificationReason(KnowledgeRouteDecision routeDecision,
                                            List<DocumentRouteCandidate> candidateDocuments) {
        if (routeDecision == null || routeDecision.getDocuments() == null || routeDecision.getDocuments().isEmpty()) {
            return "当前自动知识路由没有形成稳定候选，已改为先向用户确认文档范围。";
        }
        String confidenceText = routeDecision.getConfidence() == null ? "-" : routeDecision.getConfidence().toPlainString();
        int candidateCount = candidateDocuments == null ? 0 : candidateDocuments.size();
        return "当前自动知识路由置信度为 " + confidenceText + "，候选文档数为 " + candidateCount + "，为避免误选文档，先返回澄清问题。";
    }

    private List<String> extractRouteExpansionTerms(String question, String rewriteQuestion) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String routingText = (safeText(question) + " " + safeText(rewriteQuestion)).trim();
        for (String segment : routingText.split("[\\s、，,；;：:（）()\\-的和及与或]+")) {
            String trimmed = segment.trim();
            if (trimmed.length() >= 2) {
                terms.add(trimmed);
                if (trimmed.length() >= 4) {
                    int maxGram = Math.min(6, trimmed.length());
                    for (int gram = 2; gram <= maxGram; gram++) {
                        for (int start = 0; start + gram <= trimmed.length(); start++) {
                            terms.add(trimmed.substring(start, start + gram));
                        }
                    }
                }
            }
        }
        return terms.stream().limit(40).toList();
    }

    private double descriptorRouteScore(KnowledgeDocumentDescriptor descriptor, List<String> queryTerms) {
        String content = normalizeRouteExpansionText(String.join(" ",
            StrUtil.blankToDefault(descriptor.getDocumentName(), ""),
            StrUtil.blankToDefault(descriptor.getKnowledgeBaseName(), "")
        ));
        if (queryTerms == null || queryTerms.isEmpty() || content.isBlank()) {
            return 0D;
        }
        double score = 0D;
        List<String> sortedTerms = queryTerms.stream()
            .map(this::normalizeRouteExpansionText)
            .filter(StrUtil::isNotBlank)
            .distinct()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();
        List<String> matched = new ArrayList<>();
        for (String term : sortedTerms) {
            if (term.length() < 2) {
                continue;
            }
            boolean covered = matched.stream().anyMatch(existing -> existing.contains(term));
            if (covered) {
                continue;
            }
            if (content.contains(term)) {
                matched.add(term);
                if (term.length() >= 8) {
                    score += 12D;
                }
                else if (term.length() >= 5) {
                    score += 8D;
                }
                else if (term.length() >= 3) {
                    score += 4D;
                }
                else {
                    score += 2D;
                }
            }
        }
        return score;
    }

    private String normalizeRouteExpansionText(String value) {
        return StrUtil.blankToDefault(value, "")
            .replaceAll("[\\s>`*#_\\-，,。；;：:（）()“”\"'\\[\\]]+", "")
            .toLowerCase(Locale.ROOT);
    }

    private String buildDocumentModeNoEvidenceReply(boolean requiresFreshSearch,
                                                    QueryUnderstandingResult queryUnderstanding) {
        QueryType queryType = queryUnderstanding == null || queryUnderstanding.getQueryType() == null
            ? QueryType.DOCUMENT_QA
            : queryUnderstanding.getQueryType();
        if (queryType == QueryType.CAPABILITY_QUERY) {
            return "当前你正在使用“当前文档问答”模式，我会优先基于所选文档回答。这个问题更像是在询问助手能力，而不是当前文档内容。如果你想了解我能做什么，请切换到“开放式提问”模式。";
        }
        if (queryType == QueryType.OPEN_CHAT || requiresFreshSearch) {
            return "当前你正在使用“当前文档问答”模式，我只能基于所选文档回答。这个问题更像开放式提问，例如天气、最新信息或一般交流。如果你想继续问这类问题，请切换到“开放式提问”模式。";
        }
        return StrUtil.blankToDefault(
            properties.getNoEvidenceReply(),
            "当前没有从当前文档中检索到足够证据，暂时不能给出可靠结论。你可以补充更具体的标题、术语或关键词后再试。"
        );
    }
}
