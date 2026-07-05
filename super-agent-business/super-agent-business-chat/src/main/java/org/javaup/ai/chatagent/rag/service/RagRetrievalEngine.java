package org.javaup.ai.chatagent.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.model.ChannelExecutionView;
import org.javaup.ai.chatagent.model.RetrievalResultView;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.ConversationStructureAnchor;
import org.javaup.ai.chatagent.rag.model.DocumentNavigationDecision;
import org.javaup.ai.chatagent.rag.model.EvidenceApplicabilityResult;
import org.javaup.ai.chatagent.rag.model.QueryType;
import org.javaup.ai.chatagent.rag.model.QueryUnderstandingResult;
import org.javaup.ai.chatagent.rag.model.RagRuntimeOptions;
import org.javaup.ai.chatagent.rag.model.RagRetrievalContext;
import org.javaup.ai.chatagent.rag.model.RetrievalIntent;
import org.javaup.ai.chatagent.rag.model.SubQuestionChannelTrace;
import org.javaup.ai.chatagent.rag.model.SubQuestionEvidence;
import org.javaup.ai.chatagent.rag.retrieve.channel.RetrievalChannel;
import org.javaup.ai.chatagent.rag.retrieve.channel.RetrievalChannelResult;
import org.javaup.ai.chatagent.rag.support.SearchReferenceMapper;
import org.javaup.ai.chatagent.service.ConversationTraceRecorder;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.ai.manage.model.StructureAnchoredEvidenceRequest;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.javaup.enums.RetrievalChannelEnum;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务层
 * @author: 阿星不是程序员
 **/

@Slf4j
@Service
public class RagRetrievalEngine {

    private static final int RRF_K = 60;
    private static final String FILTERED_BY_VECTOR_GATE = "FILTERED_BY_VECTOR_GATE";
    private static final String FILTERED_BY_KEYWORD_RELATIVE_SCORE = "FILTERED_BY_KEYWORD_RELATIVE_SCORE";
    private static final String FILTERED_BY_CHANNEL_GATE = "FILTERED_BY_CHANNEL_GATE";
    private static final String FILTERED_BY_CANDIDATE_TOP_K = "FILTERED_BY_CANDIDATE_TOP_K";
    private static final String FILTERED_BY_RERANK_CANDIDATE_TOP_K = "FILTERED_BY_RERANK_CANDIDATE_TOP_K";
    private static final String FILTERED_BY_FINAL_TOP_K = "FILTERED_BY_FINAL_TOP_K";
    private static final String FILTERED_NOT_APPLICABLE_TO_TARGET_ENTITY = "FILTERED_NOT_APPLICABLE_TO_TARGET_ENTITY";
    private static final int STRUCTURE_ANCHOR_MAX_PER_ANCHOR = 2;
    private static final int STRUCTURE_ANCHOR_MAX_TOTAL = 4;

    private final List<RetrievalChannel> retrievalChannels;
    private final ChatRagProperties properties;
    private final RagRerankService ragRerankService;
    private final DocumentKnowledgeService documentKnowledgeService;
    private final ExecutorService executorService;
    private final FinalEvidenceSelectionPolicy finalEvidenceSelectionPolicy;
    private final EvidenceApplicabilityService evidenceApplicabilityService;

    public RagRetrievalEngine(List<RetrievalChannel> retrievalChannels,
                              ChatRagProperties properties,
                              RagRerankService ragRerankService,
                              DocumentKnowledgeService documentKnowledgeService,
                              @Qualifier("chatRagExecutorService") ExecutorService executorService) {
        this.retrievalChannels = retrievalChannels;
        this.properties = properties;
        this.ragRerankService = ragRerankService;
        this.documentKnowledgeService = documentKnowledgeService;
        this.executorService = executorService;
        this.finalEvidenceSelectionPolicy = new FinalEvidenceSelectionPolicy(properties);
        this.evidenceApplicabilityService = new EvidenceApplicabilityService();
    }

    public RagRetrievalContext retrieve(ConversationExecutionPlan plan, ConversationTraceRecorder traceRecorder) {
        RagRetrievalContext context = new RagRetrievalContext();
        context.setRetrievalQuestion(plan.getRetrievalQuestion());
        context.setUsedChannels(Collections.synchronizedList(new ArrayList<>()));
        context.setRetrievalNotes(Collections.synchronizedList(new ArrayList<>()));
        List<String> subQuestions = plan.getRetrievalSubQuestions() == null || plan.getRetrievalSubQuestions().isEmpty()
            ? List.of(plan.getRetrievalQuestion())
            : plan.getRetrievalSubQuestions();

        List<CompletableFuture<SubQuestionEvidence>> futures = new ArrayList<>();
        for (int index = 0; index < subQuestions.size(); index++) {
            final int subQuestionIndex = index + 1;
            final String subQuestion = subQuestions.get(index);

            futures.add(CompletableFuture.supplyAsync(
                    () -> retrieveSingleSubQuestion(subQuestionIndex, subQuestion, plan, context.getUsedChannels(), context.getRetrievalNotes(), traceRecorder),
                    executorService
                )
                .orTimeout(Math.max(properties.getSubQuestionTimeoutMs(), 1L), TimeUnit.MILLISECONDS)
                .exceptionally(throwable -> {

                    Throwable rootCause = unwrapThrowable(throwable);
                    log.warn("子问题检索失败: subQuestionIndex={}, subQuestion='{}', exceptionType={}, message={}",
                        subQuestionIndex,
                        subQuestion,
                        rootCause == null ? "" : rootCause.getClass().getName(),
                        rootCause == null ? "" : rootCause.getMessage(),
                        throwable);
                    context.getRetrievalNotes().add("子问题" + subQuestionIndex + "检索失败或超时，已自动忽略。");
                    return new SubQuestionEvidence(subQuestionIndex, subQuestion, List.of(), new ArrayList<>(), List.of(), 0, 0, 0);
                }));
        }

        List<SubQuestionEvidence> evidenceList = futures.stream()
            .map(CompletableFuture::join)
            .toList();
        int acceptedCount = (int) evidenceList.stream().filter(item -> item.getDocuments() != null && !item.getDocuments().isEmpty()).count();
        log.info("RAG 检索完成: retrievalQuestion='{}', originalSubQuestionCount={}, acceptedSubQuestionCount={}, notes={}",
            plan.getRetrievalQuestion(),
            evidenceList.size(),
            acceptedCount,
            context.getRetrievalNotes());
        assignReferenceIds(evidenceList, plan);
        context.setSubQuestionEvidenceList(evidenceList);
        return context;
    }

    private SubQuestionEvidence retrieveSingleSubQuestion(int subQuestionIndex,
                                                          String subQuestion,
                                                          ConversationExecutionPlan plan,
                                                          List<String> usedChannels,
                                                          List<String> notes,
                                                          ConversationTraceRecorder traceRecorder) {

        List<CompletableFuture<RetrievalChannelResult>> futures = retrievalChannels.stream()

            .filter(channel -> channel.supports(plan))
            .map(channel -> CompletableFuture.supplyAsync(() -> channel.retrieve(subQuestion, plan), executorService)
                .orTimeout(Math.max(properties.getChannelTimeoutMs(), 1L), TimeUnit.MILLISECONDS)
                .exceptionally(throwable -> {

                    Throwable rootCause = unwrapThrowable(throwable);
                    log.warn("检索通道失败: subQuestionIndex={}, subQuestion='{}', channel='{}', exceptionType={}, message={}",
                        subQuestionIndex,
                        subQuestion,
                        channel.channelName(),
                        rootCause == null ? "" : rootCause.getClass().getName(),
                        rootCause == null ? "" : rootCause.getMessage(),
                        throwable);
                    notes.add("子问题" + subQuestionIndex + "通道[" + channel.channelName() + "]检索失败或超时，已跳过该通道。");
                    return new RetrievalChannelResult(channel.channelName(), List.of());
                }))
            .toList();
        if (futures.isEmpty()) {
            notes.add("子问题" + subQuestionIndex + "没有可用的检索通道。");
            return new SubQuestionEvidence(subQuestionIndex, subQuestion, List.of(), new ArrayList<>(), List.of(), 0, 0, 0);
        }

        List<RetrievalChannelResult> rawChannelResults = futures.stream()
            .map(CompletableFuture::join)
            .filter(result -> result.getDocuments() != null)
            .toList();
        List<RetrievalChannelResult> channelResults = rawChannelResults.stream()
            .map(result -> applyEvidenceGate(result, plan))
            .toList();
        List<SubQuestionChannelTrace> channelTraces = buildChannelTraces(rawChannelResults, channelResults, plan);

        channelResults.stream()
            .filter(result -> !result.getDocuments().isEmpty())
            .forEach(result -> markUsedChannel(usedChannels, result.getChannelName()));

        List<Document> mergedCandidates = fuseByWeightedHybrid(channelResults, subQuestion, plan);
        List<Document> parentCandidates = documentKnowledgeService.elevateToParentBlocks(
            mergedCandidates,
            properties.getParentEvidenceMaxChars()
        );
        List<Document> structureAnchorCandidates = expandStructureAnchoredEvidence(parentCandidates, plan, notes, subQuestionIndex);
        List<Document> rerankInputCandidates = mergeStructureAnchorCandidates(parentCandidates, structureAnchorCandidates);
        List<Document> rerankedCandidates = applyRerank(subQuestionIndex, subQuestion, rerankInputCandidates, plan, usedChannels, notes);
        List<Document> finalCandidates = mergeStructureAnchorCandidates(rerankedCandidates, structureAnchorCandidates);

        List<Document> finalDocuments = selectFinalDocuments(finalCandidates, plan);
        if (markEvidenceApplicability(finalDocuments, plan)) {
            notes.add("子问题" + subQuestionIndex + "最终证据未明确支持当前目标对象，仅保留为相似但不适用证据。");
        }

        appendGraphRagCanonicalNotes(subQuestionIndex, subQuestion, finalDocuments, notes);

        notes.add("子问题" + subQuestionIndex + "检索完成："
            + summarizeChannelResults(channelResults)
            + "，final=" + finalDocuments.size());

        if (traceRecorder != null) {
            try {
                recordChannelObservations(traceRecorder, subQuestionIndex, subQuestion,
                    rawChannelResults, channelResults, channelTraces, finalDocuments);
                recordRetrievalResultObservations(traceRecorder, subQuestionIndex, subQuestion,
                    rawChannelResults, channelResults, mergedCandidates, finalCandidates, finalDocuments, plan);
            } catch (RuntimeException exception) {
                log.warn("记录检索观测数据失败, subQuestionIndex={}", subQuestionIndex, exception);
            }
        }

        return new SubQuestionEvidence(
            subQuestionIndex,
            subQuestion,
            finalDocuments,
            new ArrayList<>(),
            channelTraces,
            mergedCandidates.size(),
            rerankInputCandidates.size(),
            finalCandidates.size()
        );
    }

    private List<Document> selectFinalDocuments(List<Document> rerankedCandidates, ConversationExecutionPlan plan) {
        return finalEvidenceSelectionPolicy.select(limitReserveCandidates(rerankedCandidates, plan), plan);
    }

    private List<Document> limitReserveCandidates(List<Document> rerankedCandidates, ConversationExecutionPlan plan) {
        if (rerankedCandidates == null || rerankedCandidates.isEmpty()) {
            return List.of();
        }
        RagRuntimeOptions options = runtimeOptions(plan);
        int reserveCandidateTopK = options.getReserveCandidateTopK();
        int finalTopK = Math.max(options.getFinalTopK(), 0);
        if (reserveCandidateTopK <= 0) {
            return rerankedCandidates;
        }
        int limit = Math.max(finalTopK, reserveCandidateTopK);
        if (limit <= 0 || rerankedCandidates.size() <= limit) {
            return rerankedCandidates;
        }
        return rerankedCandidates.stream()
            .limit(limit)
            .collect(java.util.stream.Collectors.collectingAndThen(
                java.util.stream.Collectors.toCollection(ArrayList::new),
                limited -> appendReserveWindowBypassCandidates(limited, rerankedCandidates)
            ));
    }

    private List<Document> appendReserveWindowBypassCandidates(List<Document> limitedCandidates, List<Document> allCandidates) {
        if (allCandidates == null || allCandidates.isEmpty()) {
            return limitedCandidates == null ? List.of() : limitedCandidates;
        }
        List<Document> result = limitedCandidates == null ? new ArrayList<>() : new ArrayList<>(limitedCandidates);
        for (Document candidate : allCandidates) {
            if (!isStructureAnchorReserveCandidate(candidate)) {
                continue;
            }
            if (result.stream().noneMatch(selected -> sameEvidenceIdentity(selected, candidate))) {
                result.add(candidate);
            }
        }
        return result;
    }

    private List<Document> expandStructureAnchoredEvidence(List<Document> parentCandidates,
                                                           ConversationExecutionPlan plan,
                                                           List<String> notes,
                                                           int subQuestionIndex) {
        StructureAnchoredEvidenceRequest request = buildStructureAnchoredEvidenceRequest(parentCandidates, plan);
        if (request == null) {
            return List.of();
        }
        try {
            List<Document> expanded = documentKnowledgeService.expandStructureAnchoredEvidence(request);
            if (expanded == null || expanded.isEmpty()) {
                return List.of();
            }
            notes.add("子问题" + subQuestionIndex + "结构锚点正文扩展命中 " + expanded.size() + " 条。");
            return expanded;
        }
        catch (RuntimeException exception) {
            log.warn("结构锚点正文扩展失败: subQuestionIndex={}, message={}", subQuestionIndex, exception.getMessage(), exception);
            notes.add("子问题" + subQuestionIndex + "结构锚点正文扩展失败，已保留普通检索候选继续回答。");
            return List.of();
        }
    }

    private StructureAnchoredEvidenceRequest buildStructureAnchoredEvidenceRequest(List<Document> parentCandidates,
                                                                                   ConversationExecutionPlan plan) {
        List<Long> documentIds = resolvePlanDocumentIds(plan);
        List<Long> taskIds = resolvePlanTaskIds(plan);
        if (documentIds.isEmpty() || taskIds.isEmpty()) {
            return null;
        }

        LinkedHashSet<Long> structureNodeIds = new LinkedHashSet<>();
        LinkedHashSet<String> canonicalPaths = new LinkedHashSet<>();
        LinkedHashSet<String> sectionAnchors = new LinkedHashSet<>();
        collectPlanStructureAnchors(plan, structureNodeIds, canonicalPaths, sectionAnchors);
        collectCandidateStructureAnchors(parentCandidates, structureNodeIds, canonicalPaths, sectionAnchors);
        if (structureNodeIds.isEmpty() && canonicalPaths.isEmpty() && sectionAnchors.isEmpty()) {
            return null;
        }

        return StructureAnchoredEvidenceRequest.builder()
            .candidateDocuments(parentCandidates == null ? List.of() : parentCandidates)
            .structureNodeIds(new ArrayList<>(structureNodeIds))
            .canonicalPaths(new ArrayList<>(canonicalPaths))
            .sectionAnchors(new ArrayList<>(sectionAnchors))
            .documentIds(documentIds)
            .taskIds(taskIds)
            .knowledgeBaseIds(plan == null || plan.getSelectedKnowledgeBaseIds() == null
                ? List.of()
                : plan.getSelectedKnowledgeBaseIds().stream().filter(Objects::nonNull).distinct().toList())
            .maxPerAnchor(STRUCTURE_ANCHOR_MAX_PER_ANCHOR)
            .maxTotal(STRUCTURE_ANCHOR_MAX_TOTAL)
            .maxChars(properties.getParentEvidenceMaxChars())
            .build();
    }

    private void collectPlanStructureAnchors(ConversationExecutionPlan plan,
                                             LinkedHashSet<Long> structureNodeIds,
                                             LinkedHashSet<String> canonicalPaths,
                                             LinkedHashSet<String> sectionAnchors) {
        if (plan == null) {
            return;
        }
        QueryUnderstandingResult queryUnderstanding = plan.getQueryUnderstanding();
        if (queryUnderstanding != null && queryUnderstanding.getSectionAnchors() != null) {
            queryUnderstanding.getSectionAnchors().stream()
                .map(this::safeText)
                .filter(anchor -> !anchor.isBlank())
                .forEach(sectionAnchors::add);
        }
        DocumentNavigationDecision navigationDecision = plan.getNavigationDecision();
        ConversationStructureAnchor structureAnchor = navigationDecision == null ? null : navigationDecision.getStructureAnchor();
        if (structureAnchor == null || structureAnchor.isEmpty()) {
            return;
        }
        if (structureAnchor.getStructureNodeId() != null) {
            structureNodeIds.add(structureAnchor.getStructureNodeId());
        }
        if (!safeText(structureAnchor.getCanonicalPath()).isBlank()) {
            canonicalPaths.add(structureAnchor.getCanonicalPath());
        }
        if (!safeText(structureAnchor.getTargetSectionHint()).isBlank()) {
            sectionAnchors.add(structureAnchor.getTargetSectionHint());
        }
        if (!safeText(structureAnchor.getRootSectionCode()).isBlank()) {
            sectionAnchors.add(structureAnchor.getRootSectionCode());
        }
    }

    private void collectCandidateStructureAnchors(List<Document> candidates,
                                                  LinkedHashSet<Long> structureNodeIds,
                                                  LinkedHashSet<String> canonicalPaths,
                                                  LinkedHashSet<String> sectionAnchors) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        for (Document candidate : candidates) {
            if (candidate == null || candidate.getMetadata() == null) {
                continue;
            }
            Long structureNodeId = metadataLong(candidate.getMetadata(), DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_ID);
            if (structureNodeId != null) {
                structureNodeIds.add(structureNodeId);
            }
            String canonicalPath = safeText(candidate.getMetadata().get(DocumentKnowledgeMetadataKeys.CANONICAL_PATH));
            if (!canonicalPath.isBlank()) {
                canonicalPaths.add(canonicalPath);
            }
            String sectionPath = safeText(candidate.getMetadata().get(DocumentKnowledgeMetadataKeys.SECTION_PATH));
            if (!sectionPath.isBlank()) {
                sectionAnchors.add(sectionPath);
            }
        }
    }

    private List<Long> resolvePlanDocumentIds(ConversationExecutionPlan plan) {
        if (plan == null) {
            return List.of();
        }
        if (plan.getRetrievalDocumentIds() != null && !plan.getRetrievalDocumentIds().isEmpty()) {
            return plan.getRetrievalDocumentIds().stream().filter(Objects::nonNull).distinct().toList();
        }
        if (plan.getSelectedDocumentId() != null) {
            return List.of(plan.getSelectedDocumentId());
        }
        if (plan.getAllowedKnowledgeBaseDocumentIds() != null && !plan.getAllowedKnowledgeBaseDocumentIds().isEmpty()) {
            return plan.getAllowedKnowledgeBaseDocumentIds().stream().filter(Objects::nonNull).distinct().toList();
        }
        return List.of();
    }

    private List<Long> resolvePlanTaskIds(ConversationExecutionPlan plan) {
        if (plan == null) {
            return List.of();
        }
        if (plan.getRetrievalTaskIds() != null && !plan.getRetrievalTaskIds().isEmpty()) {
            return plan.getRetrievalTaskIds().stream().filter(Objects::nonNull).distinct().toList();
        }
        if (plan.getSelectedTaskId() != null) {
            return List.of(plan.getSelectedTaskId());
        }
        return List.of();
    }

    private List<Document> mergeStructureAnchorCandidates(List<Document> primaryCandidates, List<Document> structureCandidates) {
        if ((structureCandidates == null || structureCandidates.isEmpty())) {
            return primaryCandidates == null ? List.of() : primaryCandidates;
        }
        List<Document> merged = new ArrayList<>();
        if (primaryCandidates != null) {
            merged.addAll(primaryCandidates);
        }
        for (Document structureCandidate : structureCandidates) {
            if (structureCandidate == null) {
                continue;
            }
            if (merged.stream().noneMatch(candidate -> sameEvidenceIdentity(candidate, structureCandidate))) {
                merged.add(structureCandidate);
            }
        }
        return merged;
    }

    private boolean isStructureAnchorReserveCandidate(Document document) {
        if (document == null || document.getMetadata() == null) {
            return false;
        }
        Object bypass = document.getMetadata().get(DocumentKnowledgeMetadataKeys.STRUCTURE_ANCHOR_BYPASS_RESERVE_WINDOW);
        return Boolean.TRUE.equals(bypass) || Boolean.parseBoolean(String.valueOf(bypass));
    }

    private boolean markEvidenceApplicability(List<Document> finalDocuments, ConversationExecutionPlan plan) {
        QueryUnderstandingResult queryUnderstanding = plan == null ? null : plan.getQueryUnderstanding();
        if (finalDocuments == null || finalDocuments.isEmpty() || queryUnderstanding == null) {
            return false;
        }
        boolean hasEvaluated = false;
        boolean allNotApplicable = true;
        for (Document document : finalDocuments) {
            EvidenceApplicabilityResult result = evidenceApplicabilityService.evaluate(queryUnderstanding, document);
            if (result == null || document == null || document.getMetadata() == null) {
                continue;
            }
            hasEvaluated = true;
            document.getMetadata().put(DocumentKnowledgeMetadataKeys.EVIDENCE_APPLICABILITY_STATUS, result.getStatus());
            document.getMetadata().put(DocumentKnowledgeMetadataKeys.EVIDENCE_APPLICABILITY_REASON, result.getReason());
            if (!result.isApplicable()) {
                document.getMetadata().put(DocumentKnowledgeMetadataKeys.FINAL_SELECTION_REASON, FILTERED_NOT_APPLICABLE_TO_TARGET_ENTITY);
            }
            else {
                allNotApplicable = false;
            }
        }
        return hasEvaluated && allNotApplicable;
    }

    private Document selectGraphRagReserveCandidate(List<Document> rerankedCandidates,
                                                    int finalTopK,
                                                    List<Document> selected,
                                                    ConversationExecutionPlan plan,
                                                    boolean crossDocumentCommunityOnly) {
        return rerankedCandidates.stream()
            .skip(finalTopK)
            .filter(document -> isGraphRagReserveCandidate(document, plan))
            .filter(document -> !crossDocumentCommunityOnly || isGraphRagCrossDocumentCommunityReserveCandidate(document, plan))
            .filter(candidate -> selected.stream().noneMatch(selectedDocument -> sameDocument(selectedDocument, candidate)))
            .max(Comparator.comparingDouble(document -> graphRagEvidenceBudgetPriority(document, plan)))
            .orElse(null);
    }

    private boolean isRequiredGraphRagReserveCandidate(Document document,
                                                       ConversationExecutionPlan plan,
                                                       boolean preferCrossDocumentCommunity) {
        if (!isGraphRagReserveCandidate(document, plan)) {
            return false;
        }
        return !preferCrossDocumentCommunity || isGraphRagCrossDocumentCommunityReserveCandidate(document, plan);
    }

    private boolean sameDocument(Document left, Document right) {
        if (left == null || right == null) {
            return false;
        }
        return Objects.equals(left.getId(), right.getId());
    }

    private int weakestNonReservedEvidenceIndex(List<Document> selected, ConversationExecutionPlan plan) {
        int replaceIndex = -1;
        double weakestScore = Double.MAX_VALUE;
        for (int index = 0; index < selected.size(); index++) {
            Document document = selected.get(index);
            if (isGraphRagReserveCandidate(document, plan)) {
                continue;
            }
            double score = finalDocumentScore(document);
            if (score < weakestScore) {
                weakestScore = score;
                replaceIndex = index;
            }
        }
        return replaceIndex >= 0 ? replaceIndex : selected.size() - 1;
    }

    private RetrievalChannelResult applyEvidenceGate(RetrievalChannelResult result, ConversationExecutionPlan plan) {
        if (result == null || result.getDocuments() == null || result.getDocuments().isEmpty()) {
            return result;
        }

        List<Document> documents = switch (result.getChannelName()) {
            case "vector" -> filterVectorCandidates(result.getDocuments(), plan);
            case "keyword" -> filterKeywordCandidates(result.getDocuments(), plan);
            default -> result.getDocuments();
        };
        return new RetrievalChannelResult(result.getChannelName(), documents);
    }

    private List<Document> filterVectorCandidates(List<Document> documents, ConversationExecutionPlan plan) {
        double minSimilarity = runtimeOptions(plan).getMinVectorSimilarity();
        return documents.stream()

            .filter(document -> {
                Double score = resolveScore(document);
                return score != null && score >= minSimilarity;
            })
            .toList();
    }

    private List<Document> filterKeywordCandidates(List<Document> documents, ConversationExecutionPlan plan) {
        Double topScore = documents.stream()
            .map(this::resolveScore)
            .filter(Objects::nonNull)
            .max(Double::compareTo)
            .orElse(null);
        if (topScore == null || topScore <= 0D) {
            return documents;
        }

        double acceptedFloor = topScore * Math.max(0D, runtimeOptions(plan).getKeywordRelativeScoreFloor());
        return documents.stream()
            .filter(document -> {
                Double score = resolveScore(document);
                return score != null && score >= acceptedFloor;
            })
            .toList();
    }

    private List<Document> fuseByWeightedHybrid(List<RetrievalChannelResult> channelResults,
                                                String subQuestion,
                                                ConversationExecutionPlan plan) {
        Map<String, CandidateHolder> holders = new LinkedHashMap<>();
        Map<String, Double> channelMaxScoreMap = resolveChannelMaxScoreMap(channelResults);
        List<String> metadataBoostTerms = buildMetadataBoostTerms(subQuestion, plan);

        for (RetrievalChannelResult retrievalChannelResult : channelResults) {
            accumulateWeightedHybrid(retrievalChannelResult, holders, channelMaxScoreMap, metadataBoostTerms, plan);
        }

        List<CandidateHolder> sortedHolders = holders.values().stream()
            .peek(holder -> finishHybridScore(holder, plan))
            .peek(this::writeHybridMetadata)
            .sorted((left, right) -> Double.compare(right.score, left.score))
            .toList();
        return selectHybridCandidates(sortedHolders, plan).stream()
            .map(holder -> holder.document)
            .toList();
    }

    private List<CandidateHolder> selectHybridCandidates(List<CandidateHolder> sortedHolders,
                                                         ConversationExecutionPlan plan) {
        if (sortedHolders == null || sortedHolders.isEmpty()) {
            return List.of();
        }
        int candidateTopK = Math.max(runtimeOptions(plan).getCandidateTopK(), 0);
        if (candidateTopK <= 0 || sortedHolders.size() <= candidateTopK) {
            return sortedHolders.stream()
                .limit(candidateTopK)
                .toList();
        }

        List<CandidateHolder> selected = new ArrayList<>(sortedHolders.stream()
            .limit(candidateTopK)
            .toList());
        boolean preferCrossDocumentCommunity = shouldReserveCrossDocumentCommunityEvidence(plan);
        if (selected.stream().anyMatch(holder ->
            isRequiredGraphRagReserveCandidate(holder.document, plan, preferCrossDocumentCommunity))) {
            return selected;
        }

        CandidateHolder graphRagReserve = selectGraphRagReserveHolder(
            sortedHolders,
            candidateTopK,
            selected,
            plan,
            preferCrossDocumentCommunity
        );
        if (graphRagReserve == null && preferCrossDocumentCommunity) {
            graphRagReserve = selectGraphRagReserveHolder(sortedHolders, candidateTopK, selected, plan, false);
        }
        if (graphRagReserve == null) {
            return selected;
        }
        int replaceIndex = weakestNonReservedEvidenceIndex(
            selected.stream().map(holder -> holder.document).toList(),
            plan
        );
        if (replaceIndex >= 0) {
            selected.set(replaceIndex, graphRagReserve);
        }
        return selected;
    }

    private CandidateHolder selectGraphRagReserveHolder(List<CandidateHolder> sortedHolders,
                                                       int candidateTopK,
                                                       List<CandidateHolder> selected,
                                                       ConversationExecutionPlan plan,
                                                       boolean crossDocumentCommunityOnly) {
        return sortedHolders.stream()
            .skip(candidateTopK)
            .filter(holder -> holder != null && holder.document != null)
            .filter(holder -> isGraphRagReserveCandidate(holder.document, plan))
            .filter(holder -> !crossDocumentCommunityOnly || isGraphRagCrossDocumentCommunityReserveCandidate(holder.document, plan))
            .filter(candidate -> selected.stream().noneMatch(selectedHolder -> sameDocument(selectedHolder.document, candidate.document)))
            .max(Comparator.comparingDouble(holder -> graphRagEvidenceBudgetPriority(holder.document, plan)))
            .orElse(null);
    }

    private void writeHybridMetadata(CandidateHolder holder) {
        holder.document.getMetadata().put(DocumentKnowledgeMetadataKeys.SCORE, holder.score);
        holder.document.getMetadata().put(DocumentKnowledgeMetadataKeys.RRF_SCORE, holder.rrfScore);
        holder.document.getMetadata().put(DocumentKnowledgeMetadataKeys.HYBRID_SCORE, holder.score);
        holder.document.getMetadata().put(DocumentKnowledgeMetadataKeys.METADATA_BOOST, holder.metadataBoost);
        if (holder.vectorScore != null) {
            holder.document.getMetadata().put(DocumentKnowledgeMetadataKeys.VECTOR_SCORE, holder.vectorScore);
        }
        if (holder.keywordScore != null) {
            holder.document.getMetadata().put(DocumentKnowledgeMetadataKeys.KEYWORD_SCORE, holder.keywordScore);
        }
        holder.document.getMetadata().put(DocumentKnowledgeMetadataKeys.CHANNEL,
            holder.channels.size() > 1 ? "hybrid" : holder.channels.iterator().next());
    }

    private Map<String, Double> resolveChannelMaxScoreMap(List<RetrievalChannelResult> channelResults) {
        Map<String, Double> maxScoreMap = new LinkedHashMap<>();
        for (RetrievalChannelResult channelResult : channelResults == null ? List.<RetrievalChannelResult>of() : channelResults) {
            if (channelResult == null || channelResult.getDocuments() == null) {
                continue;
            }
            double maxScore = channelResult.getDocuments().stream()
                .map(this::resolveScore)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0D);
            maxScoreMap.put(channelResult.getChannelName(), Math.max(maxScore, 0D));
        }
        return maxScoreMap;
    }

    private void accumulateWeightedHybrid(RetrievalChannelResult channelResult,
                                          Map<String, CandidateHolder> holders,
                                          Map<String, Double> channelMaxScoreMap,
                                          List<String> metadataBoostTerms,
                                          ConversationExecutionPlan plan) {
        if (channelResult == null || channelResult.getDocuments() == null) {
            return;
        }
        List<Document> documents = channelResult.getDocuments();
        for (int rank = 0; rank < documents.size(); rank++) {
            Document document = documents.get(rank);
            String documentId = document.getId();

            double rrfScore = 1D / (RRF_K + rank + 1);
            double normalizedRankScore = (RRF_K + 1D) * rrfScore;
            Double originalScore = resolveScore(document);
            double normalizedOriginalScore = normalizeOriginalScore(
                originalScore,
                channelMaxScoreMap.getOrDefault(channelResult.getChannelName(), 0D)
            );
            double channelWeight = resolveChannelWeight(channelResult.getChannelName(), plan);
            document.getMetadata().put(DocumentKnowledgeMetadataKeys.RETRIEVAL_INTENT, resolveRetrievalIntent(plan).name());
            document.getMetadata().put(DocumentKnowledgeMetadataKeys.CHANNEL_WEIGHT, channelWeight);
            if (RetrievalChannelEnum.VECTOR.getName().equals(channelResult.getChannelName()) && originalScore != null) {
                document.getMetadata().put(DocumentKnowledgeMetadataKeys.VECTOR_SCORE, originalScore);
            }
            if (RetrievalChannelEnum.KEYWORD.getName().equals(channelResult.getChannelName()) && originalScore != null) {
                document.getMetadata().put(DocumentKnowledgeMetadataKeys.KEYWORD_SCORE, originalScore);
            }
            CandidateHolder holder = holders.computeIfAbsent(documentId, ignored -> new CandidateHolder(document));
            mergeGraphRagMetadata(holder, document);
            holder.rrfScore += rrfScore;
            holder.rankScore += channelWeight * hybridRankWeight(plan) * normalizedRankScore;
            holder.originalScore += channelWeight * hybridOriginalScoreWeight(plan) * normalizedOriginalScore;
            holder.metadataBoost = Math.max(holder.metadataBoost, calculateMetadataBoost(document, metadataBoostTerms, plan));
            holder.channels.add(channelResult.getChannelName());
            if (RetrievalChannelEnum.VECTOR.getName().equals(channelResult.getChannelName()) && originalScore != null) {
                holder.vectorScore = originalScore;
            }
            if (RetrievalChannelEnum.KEYWORD.getName().equals(channelResult.getChannelName()) && originalScore != null) {
                holder.keywordScore = originalScore;
            }
        }
    }

    private void finishHybridScore(CandidateHolder holder, ConversationExecutionPlan plan) {
        holder.score = holder.rankScore
            + holder.originalScore
            + hybridMetadataBoostWeight(plan) * Math.min(holder.metadataBoost, hybridMaxMetadataBoost(plan));
    }

    private double normalizeOriginalScore(Double originalScore, double channelMaxScore) {
        if (originalScore == null || originalScore <= 0D || channelMaxScore <= 0D) {
            return 0D;
        }
        return Math.min(1D, originalScore / channelMaxScore);
    }

    private double resolveChannelWeight(String channelName, ConversationExecutionPlan plan) {
        RagRuntimeOptions.HybridOptions hybrid = runtimeOptions(plan).getHybrid();
        double baseWeight;
        if (RetrievalChannelEnum.VECTOR.getName().equals(channelName)) {
            baseWeight = hybrid == null ? 1D : Math.max(0D, hybrid.getVectorWeight());
            return baseWeight * resolveIntentMultiplier(channelName, plan);
        }
        if (RetrievalChannelEnum.KEYWORD.getName().equals(channelName)) {
            baseWeight = hybrid == null ? 1D : Math.max(0D, hybrid.getKeywordWeight());
            return baseWeight * resolveIntentMultiplier(channelName, plan);
        }
        if (RetrievalChannelEnum.TABLE.getName().equals(channelName)) {
            baseWeight = hybrid == null ? 1.2D : Math.max(0D, hybrid.getTableWeight());
            return baseWeight * resolveIntentMultiplier(channelName, plan);
        }
        if (RetrievalChannelEnum.GRAPH_RAG.getName().equals(channelName)) {
            baseWeight = hybrid == null ? 1.1D : Math.max(0D, hybrid.getGraphRagWeight());
            return baseWeight * resolveIntentMultiplier(channelName, plan);
        }
        if (RetrievalChannelEnum.RAPTOR.getName().equals(channelName)) {
            baseWeight = hybrid == null ? 1.05D : Math.max(0D, hybrid.getRaptorWeight());
            return baseWeight * resolveIntentMultiplier(channelName, plan);
        }
        return 1D;
    }

    private double resolveIntentMultiplier(String channelName, ConversationExecutionPlan plan) {
        RetrievalIntent intent = resolveRetrievalIntent(plan);
        boolean suggested = isSuggestedChannel(channelName, plan);
        if (intent == RetrievalIntent.TABLE) {
            if (RetrievalChannelEnum.TABLE.getName().equals(channelName)) {
                return suggested ? 1.16D : 1.08D;
            }
        }
        if (intent == RetrievalIntent.GRAPH_RAG) {
            if (RetrievalChannelEnum.GRAPH_RAG.getName().equals(channelName)) {
                return suggested ? 1.16D : 1.08D;
            }
        }
        if (intent == RetrievalIntent.RAPTOR) {
            if (RetrievalChannelEnum.RAPTOR.getName().equals(channelName)) {
                return suggested ? 1.16D : 1.08D;
            }
        }
        if (intent == RetrievalIntent.STRUCTURE) {
            if (RetrievalChannelEnum.KEYWORD.getName().equals(channelName) || RetrievalChannelEnum.VECTOR.getName().equals(channelName)) {
                return 1.05D;
            }
        }
        if (suggested) {
            return 1.04D;
        }
        return 1D;
    }

    private boolean isSuggestedChannel(String channelName, ConversationExecutionPlan plan) {
        QueryUnderstandingResult queryUnderstanding = plan == null ? null : plan.getQueryUnderstanding();
        if (queryUnderstanding == null || queryUnderstanding.getChannels() == null || queryUnderstanding.getChannels().isEmpty()) {
            return false;
        }
        RetrievalIntent channelIntent = channelIntent(channelName);
        return channelIntent != null && queryUnderstanding.getChannels().contains(channelIntent);
    }

    private RetrievalIntent channelIntent(String channelName) {
        if (RetrievalChannelEnum.TABLE.getName().equals(channelName)) {
            return RetrievalIntent.TABLE;
        }
        if (RetrievalChannelEnum.GRAPH_RAG.getName().equals(channelName)) {
            return RetrievalIntent.GRAPH_RAG;
        }
        if (RetrievalChannelEnum.RAPTOR.getName().equals(channelName)) {
            return RetrievalIntent.RAPTOR;
        }
        if (RetrievalChannelEnum.VECTOR.getName().equals(channelName)
            || RetrievalChannelEnum.KEYWORD.getName().equals(channelName)) {
            return RetrievalIntent.GENERAL;
        }
        return null;
    }

    private void mergeGraphRagMetadata(CandidateHolder holder, Document candidate) {
        if (holder == null || holder.document == null || holder.document.getMetadata() == null
            || candidate == null || candidate.getMetadata() == null
            || !isGraphRagMetadata(candidate.getMetadata())) {
            return;
        }
        Map<String, Object> holderMetadata = holder.document.getMetadata();
        Map<String, Object> candidateMetadata = candidate.getMetadata();
        if (graphRagMetadataPriority(candidate) > graphRagMetadataPriority(holder.document)) {
            copyGraphRagMetadata(candidateMetadata, holderMetadata);
            return;
        }
        for (String key : DocumentKnowledgeMetadataKeys.GRAPH_RAG_METADATA_KEYS) {
            Object existing = holderMetadata.get(key);
            Object incoming = candidateMetadata.get(key);
            if (isMeaningfulMetadataValue(incoming) && !isMeaningfulMetadataValue(existing)) {
                holderMetadata.put(key, incoming);
            }
        }
    }

    private void copyGraphRagMetadata(Map<String, Object> source, Map<String, Object> target) {
        for (String key : DocumentKnowledgeMetadataKeys.GRAPH_RAG_METADATA_KEYS) {
            Object value = source.get(key);
            if (isMeaningfulMetadataValue(value)) {
                target.put(key, value);
            }
        }
    }

    private boolean isMeaningfulMetadataValue(Object value) {
        if (value == null) {
            return false;
        }
        return !(value instanceof String text) || !text.isBlank();
    }

    private double graphRagMetadataPriority(Document document) {
        if (document == null || document.getMetadata() == null || !isGraphRagMetadata(document.getMetadata())) {
            return 0D;
        }
        Map<String, Object> metadata = document.getMetadata();
        double priority = finalDocumentScore(document) * 0.01D;
        if (isGraphRagCommunitySummaryOnly(document)) {
            priority -= 30D;
        }
        String groundingLevel = safeText(metadata.get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_GROUNDING_LEVEL));
        if ("RELATION_STRONG_QUOTE".equalsIgnoreCase(groundingLevel)) {
            priority += 18D;
        }
        else if ("RELATION_WEAK_QUOTE".equalsIgnoreCase(groundingLevel)) {
            priority += 8D;
        }
        else if ("COMMUNITY_SOURCE_QUOTE".equalsIgnoreCase(groundingLevel)) {
            priority += 6D;
        }
        if (isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_ID))) {
            priority += 20D;
        }
        if (isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID))) {
            priority += 16D;
        }
        if (isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY))) {
            priority += 24D;
        }
        if (isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY))) {
            priority += 18D;
        }
        if (isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_NAME))) {
            priority += 10D;
        }
        Double qualityScore = numericMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_QUALITY_SCORE));
        if (qualityScore != null) {
            priority += Math.min(4D, Math.max(0D, qualityScore) * 4D);
        }
        Double pagerank = numericMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_PAGERANK));
        if (pagerank != null) {
            priority += Math.min(3D, Math.max(0D, pagerank) * 3D);
        }
        return priority;
    }

    private RetrievalIntent resolveRetrievalIntent(ConversationExecutionPlan plan) {
        return plan == null || plan.getRetrievalIntent() == null ? RetrievalIntent.GENERAL : plan.getRetrievalIntent();
    }

    private RagRuntimeOptions runtimeOptions(ConversationExecutionPlan plan) {
        return RagRuntimeOptions.resolve(plan, properties);
    }

    private double hybridRankWeight(ConversationExecutionPlan plan) {
        RagRuntimeOptions.HybridOptions hybrid = runtimeOptions(plan).getHybrid();
        return hybrid == null ? 1D : Math.max(0D, hybrid.getRankWeight());
    }

    private double hybridOriginalScoreWeight(ConversationExecutionPlan plan) {
        RagRuntimeOptions.HybridOptions hybrid = runtimeOptions(plan).getHybrid();
        return hybrid == null ? 0.08D : Math.max(0D, hybrid.getOriginalScoreWeight());
    }

    private double hybridMetadataBoostWeight(ConversationExecutionPlan plan) {
        RagRuntimeOptions.HybridOptions hybrid = runtimeOptions(plan).getHybrid();
        return hybrid == null ? 0.04D : Math.max(0D, hybrid.getMetadataBoostWeight());
    }

    private double hybridMaxMetadataBoost(ConversationExecutionPlan plan) {
        RagRuntimeOptions.HybridOptions hybrid = runtimeOptions(plan).getHybrid();
        return hybrid == null ? 1D : Math.max(0D, hybrid.getMaxMetadataBoost());
    }

    private List<String> buildMetadataBoostTerms(String subQuestion, ConversationExecutionPlan plan) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        appendBoostTerms(terms, subQuestion);
        if (plan != null) {
            appendBoostTerms(terms, plan.getRetrievalQuestion());
            appendBoostTerms(terms, plan.getRewriteQuestion());
            if (plan.getNavigationDecision() != null) {
                appendBoostTerms(terms, plan.getNavigationDecision().getQueryContextHints());
                appendBoostTerms(terms, plan.getNavigationDecision().getSoftSectionHints());
            }
            if (plan.getHistoryPlanningContext() != null) {
                appendBoostTerms(terms, plan.getHistoryPlanningContext().getQueryContextHints());
            }
        }
        return terms.stream()
            .map(this::normalizeBoostText)
            .filter(term -> term.length() >= 2)
            .distinct()
            .limit(30)
            .toList();
    }

    private void appendBoostTerms(LinkedHashSet<String> terms, List<String> values) {
        if (values == null) {
            return;
        }
        values.forEach(value -> appendBoostTerms(terms, value));
    }

    private void appendBoostTerms(LinkedHashSet<String> terms, String text) {
        String normalized = safeText(text);
        if (normalized.isBlank()) {
            return;
        }
        if (normalized.length() <= 24) {
            terms.add(normalized);
        }
        for (String segment : normalized.split("[\\s、，,；;：:（）()\\-的和及与或]+")) {
            String trimmed = segment.trim();
            if (trimmed.length() >= 2) {
                terms.add(trimmed);
            }
        }
    }

    private double calculateMetadataBoost(Document document, List<String> terms, ConversationExecutionPlan plan) {
        if (document == null || document.getMetadata() == null || terms == null || terms.isEmpty()) {
            return 0D;
        }
        double boost = 0D;
        boost += containsAnyMetadataTerm(document, terms, DocumentKnowledgeMetadataKeys.TITLE) ? 0.30D : 0D;
        boost += containsAnyMetadataTerm(document, terms, DocumentKnowledgeMetadataKeys.SECTION_PATH) ? 0.22D : 0D;
        boost += containsAnyMetadataTerm(document, terms, DocumentKnowledgeMetadataKeys.KEYWORDS) ? 0.18D : 0D;
        boost += containsAnyMetadataTerm(document, terms, DocumentKnowledgeMetadataKeys.QUESTIONS) ? 0.14D : 0D;
        boost += containsAnyMetadataTerm(document, terms, DocumentKnowledgeMetadataKeys.DOCUMENT_NAME) ? 0.10D : 0D;
        boost += chunkTypeBoost(document);
        boost += graphRankMetadataBoost(document);
        return Math.min(boost, hybridMaxMetadataBoost(plan));
    }

    private boolean containsAnyMetadataTerm(Document document, List<String> terms, String metadataKey) {
        String metadataText = normalizeBoostText(safeText(document.getMetadata().get(metadataKey)));
        if (metadataText.isBlank()) {
            return false;
        }
        return terms.stream().anyMatch(term -> term.length() >= 2 && metadataText.contains(term));
    }

    private double chunkTypeBoost(Document document) {
        String chunkType = normalizeBoostText(safeText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.CHUNK_TYPE)));
        if (chunkType.isBlank()) {
            return 0D;
        }
        if ("table".equals(chunkType)) {
            return 0.03D;
        }
        if ("image".equals(chunkType) || "figure".equals(chunkType)) {
            return 0.02D;
        }
        return 0D;
    }

    private double graphRankMetadataBoost(Document document) {
        if (document == null || document.getMetadata() == null || !isGraphRagMetadata(document.getMetadata())) {
            return 0D;
        }
        Map<String, Object> metadata = document.getMetadata();
        double boost = 0D;
        if (isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_SOURCE))) {
            boost += 0.08D;
        }
        if (isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_NHOP_PATH))) {
            boost += 0.06D;
        }
        if (isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY))) {
            boost += 0.05D;
        }
        if (isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY))) {
            boost += 0.08D;
        }
        Double qualityScore = numericMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_QUALITY_SCORE));
        if (qualityScore != null) {
            boost += Math.min(0.06D, Math.max(0D, qualityScore) * 0.06D);
        }
        Double rankBoost = numericMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_RANK_BOOST));
        if (rankBoost != null && rankBoost > 0D) {
            boost += Math.min(0.06D, rankBoost * 0.06D);
        }
        return Math.min(0.22D, boost);
    }

    private boolean isGraphRagReserveCandidate(Document document, ConversationExecutionPlan plan) {
        if (document == null || document.getMetadata() == null || !isGraphRagMetadata(document.getMetadata())) {
            return false;
        }
        Map<String, Object> metadata = document.getMetadata();
        if (isGraphRagCommunitySummaryOnly(document)) {
            return false;
        }
        if (isGraphRagCommunityReportReserveCandidate(document, plan)) {
            return true;
        }
        boolean hasRelationEvidence = isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_ID))
            && isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID))
            && hasGraphRagSourceQuote(document);
        if (!hasRelationEvidence) {
            return false;
        }
        if (!hasGraphRagRelationGroundingContext(metadata)) {
            return false;
        }
        Double qualityScore = numericMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_QUALITY_SCORE));
        return qualityScore == null || qualityScore >= 0.55D;
    }

    private boolean isGraphRagCrossDocumentCommunityReserveCandidate(Document document, ConversationExecutionPlan plan) {
        if (!isGraphRagCommunityReportReserveCandidate(document, plan)) {
            return false;
        }
        Map<String, Object> metadata = document.getMetadata();
        return isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY));
    }

    private boolean isGraphRagCommunityReportReserveCandidate(Document document, ConversationExecutionPlan plan) {
        if (document == null || document.getMetadata() == null || !isGraphRagMetadata(document.getMetadata())
            || !shouldReserveCrossDocumentCommunityEvidence(plan)) {
            return false;
        }
        Map<String, Object> metadata = document.getMetadata();
        if (!isGraphRagCommunityReportCandidate(metadata)) {
            return false;
        }
        if (isGraphRagCommunitySummaryOnly(document)) {
            return false;
        }
        Integer communityDocumentCount = integerMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_DOCUMENT_COUNT));
        if (communityDocumentCount != null && communityDocumentCount < 2) {
            return false;
        }
        boolean grounded = isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID))
            && hasGraphRagSourceQuote(document)
            && isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY));
        if (!grounded) {
            return false;
        }
        Double qualityScore = numericMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_QUALITY_SCORE));
        return qualityScore == null || qualityScore >= 0.55D;
    }

    private boolean isGraphRagCommunityReportCandidate(Map<String, Object> metadata) {
        if (metadata == null) {
            return false;
        }
        boolean hasCommunityIdentity = isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_ID))
            || isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY));
        if (!hasCommunityIdentity) {
            return false;
        }
        if (isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_ID))
            || isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_ENTITY_ID))) {
            return false;
        }
        return isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_TITLE))
            || isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_SUMMARY));
    }

    private boolean shouldReserveCrossDocumentCommunityEvidence(ConversationExecutionPlan plan) {
        if (plan == null) {
            return false;
        }
        RetrievalIntent intent = resolveRetrievalIntent(plan);
        if (intent == RetrievalIntent.GRAPH_RAG || intent == RetrievalIntent.RAPTOR) {
            return true;
        }
        QueryUnderstandingResult queryUnderstanding = plan.getQueryUnderstanding();
        if (queryUnderstanding == null) {
            return false;
        }
        QueryType queryType = queryUnderstanding.getQueryType();
        if (queryType == QueryType.GRAPH_RELATION || queryType == QueryType.GLOBAL_SUMMARY) {
            return true;
        }
        List<RetrievalIntent> channels = queryUnderstanding.getChannels();
        return channels != null
            && (channels.contains(RetrievalIntent.GRAPH_RAG) || channels.contains(RetrievalIntent.RAPTOR));
    }

    private double graphRagEvidenceBudgetPriority(Document document, ConversationExecutionPlan plan) {
        if (document == null || document.getMetadata() == null) {
            return 0D;
        }
        Map<String, Object> metadata = document.getMetadata();
        double priority = finalDocumentScore(document);
        if (isGraphRagCommunitySummaryOnly(document)) {
            priority -= 2.0D;
        }
        if (hasGraphRagSourceQuote(document)) {
            priority += 0.75D;
        }
        if (isGraphRagCommunityReportReserveCandidate(document, plan)) {
            priority += 1.35D;
            Integer communityDocumentCount = integerMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_DOCUMENT_COUNT));
            Integer communityEvidenceCount = integerMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_EVIDENCE_COUNT));
            Integer communityRelationGroupCount = integerMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_RELATION_GROUP_COUNT));
            if (communityDocumentCount != null && communityDocumentCount > 1) {
                priority += Math.min(0.36D, communityDocumentCount * 0.08D);
            }
            if (communityEvidenceCount != null && communityEvidenceCount > 1) {
                priority += Math.min(0.30D, communityEvidenceCount * 0.04D);
            }
            if (communityRelationGroupCount != null && communityRelationGroupCount > 1) {
                priority += Math.min(0.24D, communityRelationGroupCount * 0.04D);
            }
        }
        if (isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_ID))
            && isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID))
            && hasGraphRagSourceQuote(document)) {
            priority += 1.10D;
        }
        String groundingLevel = safeText(metadata.get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_GROUNDING_LEVEL));
        if ("RELATION_STRONG_QUOTE".equalsIgnoreCase(groundingLevel)) {
            priority += 0.55D;
        }
        else if ("RELATION_WEAK_QUOTE".equalsIgnoreCase(groundingLevel)) {
            priority += 0.25D;
        }
        else if (groundingLevel.toUpperCase(Locale.ROOT).startsWith("RELATION_")) {
            priority += 0.15D;
        }
        else if ("COMMUNITY_SOURCE_QUOTE".equalsIgnoreCase(groundingLevel)) {
            priority += 0.12D;
        }
        if (isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_NHOP_PATH))) {
            priority += 0.55D;
        }
        if (isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_SOURCE))) {
            priority += 0.45D;
        }
        if (isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_ANSWER_TYPES))) {
            priority += 0.20D;
        }
        if (isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_ENTITIES))) {
            priority += 0.16D;
        }
        if (isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY))) {
            priority += 0.35D;
        }
        if (isSuggestedChannel(RetrievalChannelEnum.GRAPH_RAG.getName(), plan)) {
            priority += 0.08D;
        }
        Double qualityScore = numericMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_QUALITY_SCORE));
        if (qualityScore != null) {
            priority += Math.min(0.45D, Math.max(0D, qualityScore) * 0.45D);
        }
        return priority;
    }

    private boolean hasGraphRagRelationGroundingContext(Map<String, Object> metadata) {
        if (metadata == null) {
            return false;
        }
        String groundingLevel = safeText(metadata.get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_GROUNDING_LEVEL));
        return isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_SOURCE))
            || isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_NHOP_PATH))
            || isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY))
            || groundingLevel.toUpperCase(Locale.ROOT).startsWith("RELATION_");
    }

    private boolean isGraphRagCommunitySummaryOnly(Document document) {
        if (document == null || document.getMetadata() == null || !isGraphRagMetadata(document.getMetadata())) {
            return false;
        }
        Map<String, Object> metadata = document.getMetadata();
        Object summaryOnly = metadata.get(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_SUMMARY_ONLY);
        if (summaryOnly instanceof Boolean bool) {
            return bool;
        }
        if (summaryOnly != null && Boolean.parseBoolean(String.valueOf(summaryOnly))) {
            return true;
        }
        String groundingLevel = safeText(metadata.get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_GROUNDING_LEVEL));
        if ("COMMUNITY_SUMMARY_ONLY".equalsIgnoreCase(groundingLevel)) {
            return true;
        }
        return isGraphRagCommunityReportCandidate(metadata) && !hasGraphRagSourceQuote(document);
    }

    private boolean hasGraphRagSourceQuote(Document document) {
        if (document == null || document.getMetadata() == null) {
            return false;
        }
        return isMeaningfulMetadataValue(document.getMetadata().get(DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET))
            || (isMeaningfulMetadataValue(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID))
            && isMeaningfulMetadataValue(document.getText()));
    }

    private Double numericMetadataValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        }
        catch (NumberFormatException exception) {
            return null;
        }
    }

    private String normalizeBoostText(String value) {
        return safeText(value)
            .replaceAll("[\\s>`*#_\\-，,。；;：:（）()“”\"'\\[\\]{}]+", "")
            .toLowerCase(Locale.ROOT);
    }

    private String safeText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private List<Document> applyRerank(int subQuestionIndex,
                                       String subQuestion,
                                       List<Document> candidates,
                                       ConversationExecutionPlan plan,
                                       List<String> usedChannels,
                                       List<String> notes) {
        if (!properties.isRerankEnabled() || candidates.isEmpty()) {
            return candidates;
        }

        int rerankCandidateTopK = resolveRerankCandidateTopK(candidates, plan);
        List<Document> rerankInput = candidates.stream()
            .limit(rerankCandidateTopK)
            .toList();
        markRerankWindow(candidates, rerankInput.size());
        try {
            List<Document> rerankedCandidates = ragRerankService.rerank(subQuestion, rerankInput);
            markUsedChannel(usedChannels, RetrievalChannelEnum.RERANK.getName());
            return rerankedCandidates;
        }
        catch (RuntimeException exception) {
            Throwable rootCause = unwrapThrowable(exception);
            markRerankFailure(rerankInput, rootCause);
            log.warn("rerank 失败，保留 weighted hybrid 候选继续回答: subQuestionIndex={}, subQuestion='{}', candidateCount={}, exceptionType={}, message={}",
                subQuestionIndex,
                subQuestion,
                rerankInput.size(),
                rootCause == null ? "" : rootCause.getClass().getName(),
                rootCause == null ? "" : rootCause.getMessage(),
                exception);
            notes.add("子问题" + subQuestionIndex + " rerank 失败或超时，已保留融合候选继续回答。");
            return rerankInput;
        }
    }

    private int resolveRerankCandidateTopK(List<Document> candidates, ConversationExecutionPlan plan) {
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        int configured = runtimeOptions(plan).getRerankCandidateTopK();
        if (configured <= 0) {
            return candidates.size();
        }
        return Math.min(configured, candidates.size());
    }

    private void markRerankWindow(List<Document> candidates, int acceptedCount) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        for (int index = 0; index < candidates.size(); index++) {
            Document candidate = candidates.get(index);
            if (candidate == null || candidate.getMetadata() == null) {
                continue;
            }
            candidate.getMetadata().put(DocumentKnowledgeMetadataKeys.RERANK_CANDIDATE_COUNT, acceptedCount);
            candidate.getMetadata().put(DocumentKnowledgeMetadataKeys.RERANK_TOP_K, acceptedCount);
            if (index >= acceptedCount) {
                candidate.getMetadata().put(DocumentKnowledgeMetadataKeys.RERANK_STATUS, "SKIPPED_BY_RERANK_CANDIDATE_TOP_K");
            }
        }
    }

    private void markRerankFailure(List<Document> candidates, Throwable rootCause) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        String error = rootCause == null
            ? "UNKNOWN"
            : rootCause.getClass().getSimpleName() + ": " + safeText(rootCause.getMessage());
        for (Document candidate : candidates) {
            if (candidate == null || candidate.getMetadata() == null) {
                continue;
            }
            candidate.getMetadata().put(DocumentKnowledgeMetadataKeys.RERANK_STATUS, "FAILED");
            candidate.getMetadata().put(DocumentKnowledgeMetadataKeys.RERANK_ERROR, error);
            candidate.getMetadata().put(DocumentKnowledgeMetadataKeys.RERANK_CANDIDATE_COUNT, candidates.size());
            candidate.getMetadata().put(DocumentKnowledgeMetadataKeys.RERANK_TOP_K, candidates.size());
        }
    }

    private void appendGraphRagCanonicalNotes(int subQuestionIndex,
                                              String subQuestion,
                                              List<Document> finalDocuments,
                                              List<String> notes) {
        if (finalDocuments == null || finalDocuments.isEmpty()) {
            return;
        }
        LinkedHashMap<String, GraphRagCanonicalObservation> observationMap = new LinkedHashMap<>();
        for (int index = 0; index < finalDocuments.size(); index++) {
            Document document = finalDocuments.get(index);
            if (document == null || document.getMetadata() == null || !isGraphRagMetadata(document.getMetadata())) {
                continue;
            }
            String entityName = safeText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_ENTITY_NAME));
            String canonicalName = firstNonBlank(
                safeText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_NAME)),
                entityName
            );
            String canonicalKey = safeText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_KEY));
            Integer entityCount = integerMetadataValue(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_COUNT));
            Integer documentCount = integerMetadataValue(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_CANONICAL_DOCUMENT_COUNT));
            Integer relationGroupEvidenceCount = integerMetadataValue(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_EVIDENCE_COUNT));
            Integer relationGroupDocumentCount = integerMetadataValue(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_DOCUMENT_COUNT));
            String crossDocumentCommunityKey = safeText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY));
            String communityTitle = safeText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_TITLE));
            Integer communityEntityCount = integerMetadataValue(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_ENTITY_COUNT));
            Integer communityRelationGroupCount = integerMetadataValue(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_RELATION_GROUP_COUNT));
            Integer communityEvidenceCount = integerMetadataValue(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_EVIDENCE_COUNT));
            Integer communityDocumentCount = integerMetadataValue(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_DOCUMENT_COUNT));
            String graphPath = safeText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_GRAPH_PATH));
            String documentName = safeText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME));

            if (canonicalName.isBlank() && canonicalKey.isBlank() && entityCount == null && documentCount == null
                && crossDocumentCommunityKey.isBlank()) {
                continue;
            }

            StringBuilder builder = new StringBuilder();
            if (!crossDocumentCommunityKey.isBlank()) {
                builder.append("community=").append(firstNonBlank(communityTitle, crossDocumentCommunityKey));
                builder.append("(entities=").append(communityEntityCount == null ? "-" : communityEntityCount)
                    .append(", relationGroups=").append(communityRelationGroupCount == null ? "-" : communityRelationGroupCount)
                    .append(", evidence=").append(communityEvidenceCount == null ? "-" : communityEvidenceCount)
                    .append(", docs=").append(communityDocumentCount == null ? "-" : communityDocumentCount)
                    .append(')');
                if (!canonicalName.isBlank() || !canonicalKey.isBlank()) {
                    builder.append(" canonical=").append(firstNonBlank(canonicalName, canonicalKey));
                }
            }
            else {
                builder.append(firstNonBlank(canonicalName, canonicalKey, "unknown"));
            }
            if (!entityName.isBlank() && !entityName.equals(canonicalName)) {
                builder.append("(命中实体=").append(entityName).append(')');
            }
            if (entityCount != null || documentCount != null) {
                builder.append("(entities=").append(entityCount == null ? "-" : entityCount)
                    .append(", docs=").append(documentCount == null ? "-" : documentCount).append(')');
            }
            if (relationGroupEvidenceCount != null || relationGroupDocumentCount != null) {
                builder.append("(relationGroup evidence=")
                    .append(relationGroupEvidenceCount == null ? "-" : relationGroupEvidenceCount)
                    .append(", docs=")
                    .append(relationGroupDocumentCount == null ? "-" : relationGroupDocumentCount)
                    .append(')');
            }
            if (!graphPath.isBlank()) {
                builder.append(" path=").append(graphPath);
            }
            if (!documentName.isBlank()) {
                builder.append(" doc=").append(documentName);
            }
            String text = builder.toString();
            observationMap.putIfAbsent(text, new GraphRagCanonicalObservation(
                text,
                graphRagObservationPriority(
                    document,
                    index,
                    entityCount,
                    documentCount,
                    relationGroupEvidenceCount,
                    relationGroupDocumentCount,
                    communityEvidenceCount,
                    communityDocumentCount
                ),
                index
            ));
        }
        if (observationMap.isEmpty()) {
            return;
        }
        List<String> limited = observationMap.values().stream()
            .sorted(Comparator.comparingDouble(GraphRagCanonicalObservation::priority).reversed()
                .thenComparingInt(GraphRagCanonicalObservation::originalIndex))
            .map(GraphRagCanonicalObservation::text)
            .limit(4)
            .toList();
        String summary = String.join("；", limited);
        notes.add("子问题" + subQuestionIndex + " GraphRAG canonical 观测：" + summary);
        log.info("GraphRAG canonical 观测: subQuestionIndex={}, subQuestion='{}', observations={}",
            subQuestionIndex,
            subQuestion,
            summary);
    }

    private double graphRagObservationPriority(Document document,
                                               int originalIndex,
                                               Integer entityCount,
                                               Integer documentCount,
                                               Integer relationGroupEvidenceCount,
                                               Integer relationGroupDocumentCount,
                                               Integer communityEvidenceCount,
                                               Integer communityDocumentCount) {
        double priority = Math.max(0D, finalDocumentScore(document));
        if (communityDocumentCount != null && communityDocumentCount > 1) {
            priority += 0.80D + Math.min(0.50D, communityDocumentCount * 0.10D);
        }
        if (communityEvidenceCount != null && communityEvidenceCount > 1) {
            priority += 0.35D + Math.min(0.35D, communityEvidenceCount * 0.04D);
        }
        if (relationGroupDocumentCount != null && relationGroupDocumentCount > 1) {
            priority += 0.60D + Math.min(0.40D, relationGroupDocumentCount * 0.08D);
        }
        if (relationGroupEvidenceCount != null && relationGroupEvidenceCount > 1) {
            priority += 0.30D + Math.min(0.30D, relationGroupEvidenceCount * 0.04D);
        }
        if (documentCount != null && documentCount > 1) {
            priority += 0.25D + Math.min(0.25D, documentCount * 0.04D);
        }
        if (entityCount != null && entityCount > 1) {
            priority += 0.10D + Math.min(0.15D, entityCount * 0.02D);
        }
        if (document != null && document.getMetadata() != null) {
            if (document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_RELATION_ID) != null) {
                priority += 0.08D;
            }
            if (document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID) != null) {
                priority += 0.06D;
            }
        }
        return priority - originalIndex * 0.0001D;
    }

    private double finalDocumentScore(Document document) {
        if (document == null) {
            return 0D;
        }
        Double score = resolveScore(document);
        if (score != null) {
            return score;
        }
        return document.getScore() == null ? 0D : document.getScore();
    }

    private boolean isGraphRagMetadata(Map<String, Object> metadata) {
        String channel = safeText(metadata.get(DocumentKnowledgeMetadataKeys.CHANNEL));
        String sourceType = safeText(metadata.get(DocumentKnowledgeMetadataKeys.SOURCE_TYPE));
        return RetrievalChannelEnum.GRAPH_RAG.getName().equals(channel)
            || "GRAPH_RAG".equalsIgnoreCase(sourceType)
            || metadata.get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID) != null
            || isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_KEY))
            || isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY))
            || isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY))
            || metadata.get(DocumentKnowledgeMetadataKeys.KG_ENTITY_ID) != null
            || metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_ID) != null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String joinSections(String... sections) {
        if (sections == null || sections.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String section : sections) {
            if (section == null || section.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(section);
        }
        return builder.toString();
    }

    private record GraphRagCanonicalObservation(String text, double priority, int originalIndex) {
    }

    private Integer integerMetadataValue(Object value) {
        Double number = numericMetadataValue(value);
        return number == null ? null : number.intValue();
    }

    private void assignReferenceIds(List<SubQuestionEvidence> evidenceList, ConversationExecutionPlan plan) {
        final int[] referenceNumber = {1};
        Map<String, String> assignedIds = new LinkedHashMap<>();
        Map<Long, KnowledgeDocumentDescriptor> descriptorMap = knowledgeBaseReferenceDescriptorMap(evidenceList, plan);
        for (SubQuestionEvidence evidence : evidenceList) {
            List<SearchReference> references = new ArrayList<>();
            for (Document document : evidence.getDocuments()) {
                enrichKnowledgeBaseReferenceMetadata(document, descriptorMap);

                SearchReference reference = SearchReferenceMapper.fromDocument(
                    document,
                    evidence.getSubQuestionIndex(),
                    evidence.getSubQuestion(),
                    0
                );
                String uniqueKey = reference.uniqueKey();

                String assignedId = assignedIds.computeIfAbsent(uniqueKey, ignored -> String.valueOf(referenceNumber[0]++));
                reference.setReferenceId(assignedId);
                references.add(reference);
            }
            evidence.setReferences(references);
        }
    }

    private Map<Long, KnowledgeDocumentDescriptor> knowledgeBaseReferenceDescriptorMap(List<SubQuestionEvidence> evidenceList,
                                                                                       ConversationExecutionPlan plan) {
        Set<Long> documentIds = new LinkedHashSet<>();
        if (evidenceList != null) {
            for (SubQuestionEvidence evidence : evidenceList) {
                if (evidence == null || evidence.getDocuments() == null) {
                    continue;
                }
                for (Document document : evidence.getDocuments()) {
                    Long documentId = metadataLong(document, DocumentKnowledgeMetadataKeys.DOCUMENT_ID);
                    if (documentId != null && needsKnowledgeBaseReferenceFallback(document)) {
                        documentIds.add(documentId);
                    }
                }
            }
        }
        if (documentIds.isEmpty()) {
            return Map.of();
        }

        List<KnowledgeDocumentDescriptor> descriptors = plan != null
            && plan.getSelectedKnowledgeBaseIds() != null
            && !plan.getSelectedKnowledgeBaseIds().isEmpty()
            ? documentKnowledgeService.listRetrievableDocumentsByKnowledgeBaseIds(plan.getSelectedKnowledgeBaseIds())
            : documentKnowledgeService.listRetrievableDocuments();
        if (descriptors == null || descriptors.isEmpty()) {
            return Map.of();
        }

        return descriptors.stream()
            .filter(descriptor -> descriptor.getDocumentId() != null && documentIds.contains(descriptor.getDocumentId()))
            .collect(java.util.stream.Collectors.toMap(
                KnowledgeDocumentDescriptor::getDocumentId,
                descriptor -> descriptor,
                (left, right) -> left,
                LinkedHashMap::new
            ));
    }

    private boolean needsKnowledgeBaseReferenceFallback(Document document) {
        if (document == null || document.getMetadata() == null) {
            return false;
        }
        return !isMeaningfulMetadataValue(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_ID))
            || !isMeaningfulMetadataValue(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_NAME));
    }

    private void enrichKnowledgeBaseReferenceMetadata(Document document,
                                                      Map<Long, KnowledgeDocumentDescriptor> descriptorMap) {
        if (document == null || document.getMetadata() == null || descriptorMap == null || descriptorMap.isEmpty()) {
            return;
        }
        Long documentId = metadataLong(document, DocumentKnowledgeMetadataKeys.DOCUMENT_ID);
        if (documentId == null) {
            return;
        }
        KnowledgeDocumentDescriptor descriptor = descriptorMap.get(documentId);
        if (descriptor == null) {
            return;
        }
        Map<String, Object> metadata = document.getMetadata();
        if (!isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME))
            && isMeaningfulMetadataValue(descriptor.getDocumentName())) {
            metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, descriptor.getDocumentName());
        }
        if (!isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_ID))
            && descriptor.getKnowledgeBaseId() != null) {
            metadata.put(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_ID, descriptor.getKnowledgeBaseId());
        }
        if (!isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_NAME))
            && isMeaningfulMetadataValue(descriptor.getKnowledgeBaseName())) {
            metadata.put(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_NAME, descriptor.getKnowledgeBaseName());
        }
    }

    private Long metadataLong(Document document, String key) {
        if (document == null || document.getMetadata() == null) {
            return null;
        }
        return asLong(document.getMetadata().get(key));
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Double resolveScore(Document document) {
        if (document == null) {
            return null;
        }

        Object metadataScore = document.getMetadata().get(DocumentKnowledgeMetadataKeys.SCORE);
        if (metadataScore instanceof Number number) {
            return number.doubleValue();
        }
        return document.getScore();
    }

    private void markUsedChannel(List<String> usedChannels, String channel) {

        if (!usedChannels.contains(channel)) {
            usedChannels.add(channel);
        }
    }

    private String summarizeChannelResults(List<RetrievalChannelResult> channelResults) {
        if (channelResults.isEmpty()) {
            return "没有启用任何检索通道";
        }
        return channelResults.stream()
            .map(result -> result.getChannelName() + "=" + result.getDocuments().size())
            .reduce((left, right) -> left + "，" + right)
            .orElse("没有检索结果");
    }

    private List<SubQuestionChannelTrace> buildChannelTraces(List<RetrievalChannelResult> rawResults,
                                                             List<RetrievalChannelResult> filteredResults,
                                                             ConversationExecutionPlan plan) {
        if ((rawResults == null || rawResults.isEmpty()) && (filteredResults == null || filteredResults.isEmpty())) {
            return List.of();
        }
        Map<String, Integer> rawMap = new LinkedHashMap<>();
        Map<String, Integer> filteredMap = new LinkedHashMap<>();
        if (rawResults != null) {
            rawResults.forEach(result -> rawMap.put(result.getChannelName(), result.getDocuments() == null ? 0 : result.getDocuments().size()));
        }
        if (filteredResults != null) {
            filteredResults.forEach(result -> filteredMap.put(result.getChannelName(), result.getDocuments() == null ? 0 : result.getDocuments().size()));
        }
        LinkedHashSet<String> channelNames = new LinkedHashSet<>();
        channelNames.addAll(rawMap.keySet());
        channelNames.addAll(filteredMap.keySet());
        List<SubQuestionChannelTrace> traces = new ArrayList<>(channelNames.size());
        RetrievalIntent retrievalIntent = resolveRetrievalIntent(plan);
        for (String channelName : channelNames) {
            traces.add(new SubQuestionChannelTrace(
                channelName,
                retrievalIntent.name(),
                resolveChannelWeight(channelName, plan),
                rawMap.getOrDefault(channelName, 0),
                filteredMap.getOrDefault(channelName, 0)
            ));
        }
        return traces;
    }

    private void recordChannelObservations(ConversationTraceRecorder traceRecorder,
                                           int subQuestionIndex,
                                           String subQuestion,
                                           List<RetrievalChannelResult> rawResults,
                                           List<RetrievalChannelResult> filteredResults,
                                           List<SubQuestionChannelTrace> channelTraces,
                                           List<Document> finalDocuments) {
        if (rawResults == null || rawResults.isEmpty()) {
            return;
        }

        List<ChannelExecutionView> executions = new ArrayList<>();
        for (RetrievalChannelResult rawResult : rawResults) {
            String channelName = rawResult.getChannelName();
            int recalledCount = rawResult.getDocuments() == null ? 0 : rawResult.getDocuments().size();

            RetrievalChannelResult filteredResult = filteredResults == null ? null :
                filteredResults.stream().filter(r -> channelName.equals(r.getChannelName())).findFirst().orElse(null);
            int acceptedCount = filteredResult == null || filteredResult.getDocuments() == null ? 0 : filteredResult.getDocuments().size();
            int finalSelectedCount = countSelectedChannelDocuments(filteredResult, finalDocuments);

            ChannelExecutionView execution = new ChannelExecutionView();
            execution.setId(traceRecorder.exchangeId());
            execution.setTraceId(traceRecorder.traceId());
            execution.setSubQuestionIndex(subQuestionIndex);
            execution.setSubQuestion(subQuestion);
            execution.setChannelType(channelName);
            execution.setExecutionState(1);
            execution.setRecalledCount(recalledCount);
            execution.setAcceptedCount(acceptedCount);
            execution.setFinalSelectedCount(finalSelectedCount);

            if (rawResult.getDocuments() != null && !rawResult.getDocuments().isEmpty()) {
                List<Double> scores = rawResult.getDocuments().stream()
                    .map(doc -> {
                        Object scoreObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.SCORE);
                        if (scoreObj instanceof Number) {
                            return ((Number) scoreObj).doubleValue();
                        }
                        return 0.0;
                    })
                    .filter(score -> score > 0)
                    .toList();

                if (!scores.isEmpty()) {
                    execution.setAvgScore(BigDecimal.valueOf(scores.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
                    execution.setMaxScore(BigDecimal.valueOf(scores.stream().mapToDouble(Double::doubleValue).max().orElse(0)));
                    execution.setMinScore(BigDecimal.valueOf(scores.stream().mapToDouble(Double::doubleValue).min().orElse(0)));
                }
            }

            executions.add(execution);
        }

        traceRecorder.recordChannelExecutions(executions);
    }

    private void recordRetrievalResultObservations(ConversationTraceRecorder traceRecorder,
                                                   int subQuestionIndex,
                                                   String subQuestion,
                                                   List<RetrievalChannelResult> rawResults,
                                                   List<RetrievalChannelResult> filteredResults,
                                                   List<Document> mergedCandidates,
                                                   List<Document> rerankedCandidates,
                                                   List<Document> finalDocuments,
                                                   ConversationExecutionPlan plan) {
        List<RetrievalResultView> results = new ArrayList<>();
        Map<String, Integer> finalRankMap = new LinkedHashMap<>();
        Map<String, Document> finalDocumentMap = new LinkedHashMap<>();
        Map<String, Document> mergedCandidateMap = new LinkedHashMap<>();
        Map<String, Document> rerankedCandidateMap = new LinkedHashMap<>();
        if (finalDocuments != null) {
            for (int i = 0; i < finalDocuments.size(); i++) {
                Document finalDocument = finalDocuments.get(i);
                String docId = finalDocument.getId();
                if (docId != null) {
                    finalRankMap.put(docId, i + 1);
                    finalDocumentMap.put(docId, finalDocument);
                }
            }
        }
        if (mergedCandidates != null) {
            for (Document document : mergedCandidates) {
                if (document.getId() != null) {
                    mergedCandidateMap.put(document.getId(), document);
                }
            }
        }
        if (rerankedCandidates != null) {
            for (Document document : rerankedCandidates) {
                if (document.getId() != null) {
                    rerankedCandidateMap.put(document.getId(), document);
                }
            }
        }

        if (rawResults != null) {
            for (RetrievalChannelResult rawResult : rawResults) {
                String channelName = rawResult.getChannelName();
                List<Document> rawDocs = rawResult.getDocuments();
                if (rawDocs == null || rawDocs.isEmpty()) {
                    continue;
                }

                for (int i = 0; i < rawDocs.size(); i++) {
                    Document doc = rawDocs.get(i);
                    Document mergedDoc = findMatchingDocument(doc, mergedCandidates, mergedCandidateMap);
                    Map<String, Object> scoreMetadata = mergedDoc == null ? doc.getMetadata() : mergedDoc.getMetadata();
                    Document rerankedDoc = findMatchingDocument(doc, rerankedCandidates, rerankedCandidateMap);
                    Map<String, Object> rerankMetadata = rerankedDoc == null ? scoreMetadata : rerankedDoc.getMetadata();
                    RetrievalResultView view = new RetrievalResultView();
                    view.setId(traceRecorder.exchangeId());
                    view.setTraceId(traceRecorder.traceId());
                    view.setSubQuestionIndex(subQuestionIndex);
                    view.setSubQuestion(subQuestion);
                    view.setChannelType(channelName);
                    view.setChannelRank(i + 1);

                    Double originalScore = resolveTraceOriginalScore(doc, channelName);
                    if (originalScore != null) {
                        view.setOriginalScore(BigDecimal.valueOf(originalScore));
                    }

                    Object rrfScoreObj = scoreMetadata.get(DocumentKnowledgeMetadataKeys.RRF_SCORE);
                    if (rrfScoreObj instanceof Number) {
                        view.setRrfScore(BigDecimal.valueOf(((Number) rrfScoreObj).doubleValue()));
                    }

                    Object hybridScoreObj = scoreMetadata.get(DocumentKnowledgeMetadataKeys.HYBRID_SCORE);
                    if (hybridScoreObj instanceof Number) {
                        view.setHybridScore(BigDecimal.valueOf(((Number) hybridScoreObj).doubleValue()));
                    }

                    Object metadataBoostObj = scoreMetadata.get(DocumentKnowledgeMetadataKeys.METADATA_BOOST);
                    if (metadataBoostObj instanceof Number) {
                        view.setMetadataBoost(BigDecimal.valueOf(((Number) metadataBoostObj).doubleValue()));
                    }

                    Object vectorScoreObj = scoreMetadata.get(DocumentKnowledgeMetadataKeys.VECTOR_SCORE);
                    if (vectorScoreObj instanceof Number) {
                        view.setVectorScore(BigDecimal.valueOf(((Number) vectorScoreObj).doubleValue()));
                    }

                    Object keywordScoreObj = scoreMetadata.get(DocumentKnowledgeMetadataKeys.KEYWORD_SCORE);
                    if (keywordScoreObj instanceof Number) {
                        view.setKeywordScore(BigDecimal.valueOf(((Number) keywordScoreObj).doubleValue()));
                    }

                    Object rerankScoreObj = rerankMetadata.get(DocumentKnowledgeMetadataKeys.RERANK_SCORE);
                    if (rerankScoreObj instanceof Number) {
                        view.setRerankScore(BigDecimal.valueOf(((Number) rerankScoreObj).doubleValue()));
                    }

                    Object docIdObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.DOCUMENT_ID);
                    if (docIdObj != null) {
                        view.setDocumentId(Long.parseLong(String.valueOf(docIdObj)));
                    }

                    Object docNameObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME);
                    if (docNameObj != null) {
                        view.setDocumentName(String.valueOf(docNameObj));
                    }

                    Object chunkIdObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.CHUNK_ID);
                    if (chunkIdObj != null) {
                        view.setChunkId(Long.parseLong(String.valueOf(chunkIdObj)));
                    }

                    Object chunkNoObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.CHUNK_NO);
                    if (chunkNoObj != null) {
                        view.setChunkNo(Integer.parseInt(String.valueOf(chunkNoObj)));
                    }

                    Object parentBlockIdObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID);
                    if (parentBlockIdObj != null) {
                        view.setParentBlockId(Long.parseLong(String.valueOf(parentBlockIdObj)));
                    }

                    Object parentBlockNoObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_NO);
                    if (parentBlockNoObj != null) {
                        view.setParentBlockNo(Integer.parseInt(String.valueOf(parentBlockNoObj)));
                    }

                    Object sectionPathObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.SECTION_PATH);
                    if (sectionPathObj != null) {
                        view.setSectionPath(String.valueOf(sectionPathObj));
                    }

                    String content = doc.getText();
                    if (content != null && !content.isEmpty()) {
                        view.setChunkTextPreview(content.length() > 500 ? content.substring(0, 500) : content);
                        view.setChunkCharCount(content.length());
                    }

                    boolean passedGate = filteredResults != null && filteredResults.stream()
                        .anyMatch(fr -> channelName.equals(fr.getChannelName()) &&
                            fr.getDocuments() != null &&
                            fr.getDocuments().stream().anyMatch(d -> sameEvidenceIdentity(d, doc)));
                    view.setGatePassed(passedGate);

                    Integer finalRank = resolveFinalRank(doc, finalDocuments, finalRankMap);
                    boolean isSelected = finalRank != null;
                    view.setSelected(isSelected);

                    if (isSelected) {
                        view.setFinalRank(finalRank);
                        Document finalDocument = findMatchingDocument(doc, finalDocuments, finalDocumentMap);
                        view.setSelectionReason(resolveSelectedReason(finalDocument));
                    } else if (!passedGate) {
                        view.setSelectionReason(resolveGateFilteredReason(channelName));
                    } else {
                        view.setSelectionReason(resolveFilteredReason(mergedDoc, rerankedDoc));
                    }

                    results.add(view);
                }
            }
        }

        traceRecorder.recordRetrievalResults(results);
    }

    private String resolveSelectedReason(Document finalDocument) {
        if (finalDocument == null || finalDocument.getMetadata() == null) {
            return FinalEvidenceSelectionPolicy.SELECTED_TOP_RANK;
        }
        String reason = safeText(finalDocument.getMetadata().get(DocumentKnowledgeMetadataKeys.FINAL_SELECTION_REASON));
        return reason.isBlank() ? FinalEvidenceSelectionPolicy.SELECTED_TOP_RANK : reason;
    }

    private String resolveGateFilteredReason(String channelName) {
        if (RetrievalChannelEnum.VECTOR.getName().equals(channelName)) {
            return FILTERED_BY_VECTOR_GATE;
        }
        if (RetrievalChannelEnum.KEYWORD.getName().equals(channelName)) {
            return FILTERED_BY_KEYWORD_RELATIVE_SCORE;
        }
        return FILTERED_BY_CHANNEL_GATE;
    }

    private String resolveFilteredReason(Document mergedDoc, Document rerankedDoc) {
        if (mergedDoc == null) {
            return FILTERED_BY_CANDIDATE_TOP_K;
        }
        if (rerankedDoc == null) {
            return FILTERED_BY_RERANK_CANDIDATE_TOP_K;
        }
        return FILTERED_BY_FINAL_TOP_K;
    }

    private Document findMatchingDocument(Document candidate,
                                          List<Document> documents,
                                          Map<String, Document> byId) {
        if (candidate == null || documents == null || documents.isEmpty()) {
            return null;
        }
        if (candidate.getId() != null && byId != null && byId.containsKey(candidate.getId())) {
            return byId.get(candidate.getId());
        }
        return documents.stream()
            .filter(document -> sameEvidenceIdentity(candidate, document))
            .findFirst()
            .orElse(null);
    }

    private int countSelectedChannelDocuments(RetrievalChannelResult filteredResult, List<Document> finalDocuments) {
        if (filteredResult == null || filteredResult.getDocuments() == null || filteredResult.getDocuments().isEmpty()
            || finalDocuments == null || finalDocuments.isEmpty()) {
            return 0;
        }
        return (int) filteredResult.getDocuments().stream()
            .filter(candidate -> resolveFinalRank(candidate, finalDocuments, Map.of()) != null)
            .count();
    }

    private Integer resolveFinalRank(Document candidate,
                                     List<Document> finalDocuments,
                                     Map<String, Integer> finalRankMap) {
        if (candidate == null || finalDocuments == null || finalDocuments.isEmpty()) {
            return null;
        }
        if (candidate.getId() != null && finalRankMap != null && finalRankMap.containsKey(candidate.getId())) {
            return finalRankMap.get(candidate.getId());
        }
        for (int index = 0; index < finalDocuments.size(); index++) {
            if (sameEvidenceIdentity(candidate, finalDocuments.get(index))) {
                return index + 1;
            }
        }
        return null;
    }

    private boolean sameEvidenceIdentity(Document left, Document right) {
        if (left == null || right == null) {
            return false;
        }
        if (Objects.equals(left.getId(), right.getId())) {
            return true;
        }
        Map<String, Object> leftMetadata = left.getMetadata();
        Map<String, Object> rightMetadata = right.getMetadata();
        Long leftDocumentId = metadataLong(leftMetadata, DocumentKnowledgeMetadataKeys.DOCUMENT_ID);
        Long rightDocumentId = metadataLong(rightMetadata, DocumentKnowledgeMetadataKeys.DOCUMENT_ID);
        if (leftDocumentId != null && rightDocumentId != null && !Objects.equals(leftDocumentId, rightDocumentId)) {
            return false;
        }
        Long leftChunkId = metadataLong(leftMetadata, DocumentKnowledgeMetadataKeys.CHUNK_ID);
        Long rightChunkId = metadataLong(rightMetadata, DocumentKnowledgeMetadataKeys.CHUNK_ID);
        if (leftChunkId != null && rightChunkId != null && Objects.equals(leftChunkId, rightChunkId)) {
            return true;
        }
        Long leftParentBlockId = metadataLong(leftMetadata, DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID);
        Long rightParentBlockId = metadataLong(rightMetadata, DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID);
        return leftChunkId == null && rightChunkId == null
            && leftParentBlockId != null
            && rightParentBlockId != null
            && Objects.equals(leftParentBlockId, rightParentBlockId);
    }

    private Long metadataLong(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Double resolveTraceOriginalScore(Document document, String channelName) {
        if (document == null || document.getMetadata() == null) {
            return null;
        }
        Object scoreObj = null;
        if (RetrievalChannelEnum.VECTOR.getName().equals(channelName)) {
            scoreObj = document.getMetadata().get(DocumentKnowledgeMetadataKeys.VECTOR_SCORE);
        }
        if (RetrievalChannelEnum.KEYWORD.getName().equals(channelName)) {
            scoreObj = document.getMetadata().get(DocumentKnowledgeMetadataKeys.KEYWORD_SCORE);
        }
        if (RetrievalChannelEnum.TABLE.getName().equals(channelName)) {
            scoreObj = document.getMetadata().get(DocumentKnowledgeMetadataKeys.SCORE);
        }
        if (!(scoreObj instanceof Number)) {
            scoreObj = document.getMetadata().get(DocumentKnowledgeMetadataKeys.SCORE);
        }
        return scoreObj instanceof Number number ? number.doubleValue() : null;
    }

    private static class CandidateHolder {

        private final Document document;
        private final LinkedHashSet<String> channels = new LinkedHashSet<>();
        private double rrfScore;
        private double rankScore;
        private double originalScore;
        private double metadataBoost;
        private double score;
        private Double vectorScore;
        private Double keywordScore;

        private CandidateHolder(Document document) {
            this.document = document;
        }
    }

    private Throwable unwrapThrowable(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        Throwable current = throwable;
        while (current.getCause() != null
            && current.getCause() != current
            && (current instanceof CompletionException
            || current instanceof ExecutionException
            || current instanceof TimeoutException)) {
            current = current.getCause();
        }
        return current;
    }
}
