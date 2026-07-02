package org.javaup.ai.chatagent.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.model.ChannelExecutionView;
import org.javaup.ai.chatagent.model.RetrievalResultView;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.RagRetrievalContext;
import org.javaup.ai.chatagent.rag.model.RetrievalIntent;
import org.javaup.ai.chatagent.rag.model.SubQuestionChannelTrace;
import org.javaup.ai.chatagent.rag.model.SubQuestionEvidence;
import org.javaup.ai.chatagent.rag.retrieve.channel.RetrievalChannel;
import org.javaup.ai.chatagent.rag.retrieve.channel.RetrievalChannelResult;
import org.javaup.ai.chatagent.rag.support.SearchReferenceMapper;
import org.javaup.ai.chatagent.service.ConversationTraceRecorder;
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
    private static final Set<String> GRAPH_RAG_ACTION_RELATION_TYPES = Set.of(
        "APPROVES",
        "RESPONSIBLE_FOR",
        "EXECUTES",
        "REVOKES",
        "OWNS",
        "MANAGES",
        "OPERATES",
        "MAINTAINS"
    );
    private static final Set<String> GRAPH_RAG_WEAK_RELATION_TYPES = Set.of(
        "RECORDS",
        "ASSOCIATED_WITH",
        "RELATED_TO"
    );

    private final List<RetrievalChannel> retrievalChannels;
    private final ChatRagProperties properties;
    private final RagRerankService ragRerankService;
    private final DocumentKnowledgeService documentKnowledgeService;
    private final ExecutorService executorService;

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
        assignReferenceIds(evidenceList);
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
            .map(this::applyEvidenceGate)
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
        List<Document> rerankedCandidates = applyRerank(subQuestionIndex, subQuestion, parentCandidates, usedChannels, notes);

        List<Document> finalDocuments = selectFinalDocuments(rerankedCandidates, plan);

        appendGraphRagCanonicalNotes(subQuestionIndex, subQuestion, finalDocuments, notes);

        notes.add("子问题" + subQuestionIndex + "检索完成："
            + summarizeChannelResults(channelResults)
            + "，final=" + finalDocuments.size());

        if (traceRecorder != null) {
            try {
                recordChannelObservations(traceRecorder, subQuestionIndex, subQuestion,
                    rawChannelResults, channelResults, channelTraces, finalDocuments);
                recordRetrievalResultObservations(traceRecorder, subQuestionIndex, subQuestion,
                    rawChannelResults, channelResults, mergedCandidates, rerankedCandidates, finalDocuments);
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
            parentCandidates.size(),
            rerankedCandidates.size()
        );
    }

    private List<Document> selectFinalDocuments(List<Document> rerankedCandidates, ConversationExecutionPlan plan) {
        if (rerankedCandidates == null || rerankedCandidates.isEmpty()) {
            return List.of();
        }
        int finalTopK = Math.max(properties.getFinalTopK(), 0);
        if (finalTopK <= 0 || rerankedCandidates.size() <= finalTopK) {
            return rerankedCandidates.stream()
                .limit(finalTopK)
                .toList();
        }
        List<Document> selected = new ArrayList<>(rerankedCandidates.stream()
            .limit(finalTopK)
            .toList());
        boolean preferCrossDocumentCommunity = shouldReserveCrossDocumentCommunityEvidence(plan);
        if (selected.stream().anyMatch(document -> isRequiredGraphRagReserveCandidate(document, plan, preferCrossDocumentCommunity))) {
            return selected;
        }
        Document graphRagReserve = selectGraphRagReserveCandidate(rerankedCandidates, finalTopK, selected, plan, preferCrossDocumentCommunity);
        if (graphRagReserve == null && preferCrossDocumentCommunity) {
            graphRagReserve = selectGraphRagReserveCandidate(rerankedCandidates, finalTopK, selected, plan, false);
        }
        if (graphRagReserve == null) {
            return selected;
        }
        int replaceIndex = weakestNonReservedEvidenceIndex(selected, plan);
        if (replaceIndex >= 0) {
            selected.set(replaceIndex, graphRagReserve);
        }
        return selected;
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

    private RetrievalChannelResult applyEvidenceGate(RetrievalChannelResult result) {
        if (result == null || result.getDocuments() == null || result.getDocuments().isEmpty()) {
            return result;
        }

        List<Document> documents = switch (result.getChannelName()) {
            case "vector" -> filterVectorCandidates(result.getDocuments());
            case "keyword" -> filterKeywordCandidates(result.getDocuments());
            default -> result.getDocuments();
        };
        return new RetrievalChannelResult(result.getChannelName(), documents);
    }

    private List<Document> filterVectorCandidates(List<Document> documents) {
        return documents.stream()

            .filter(document -> {
                Double score = resolveScore(document);
                return score != null && score >= properties.getMinVectorSimilarity();
            })
            .toList();
    }

    private List<Document> filterKeywordCandidates(List<Document> documents) {
        Double topScore = documents.stream()
            .map(this::resolveScore)
            .filter(Objects::nonNull)
            .max(Double::compareTo)
            .orElse(null);
        if (topScore == null || topScore <= 0D) {
            return documents;
        }

        double acceptedFloor = topScore * Math.max(0D, properties.getKeywordRelativeScoreFloor());
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
            .peek(this::finishHybridScore)
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
        int candidateTopK = Math.max(properties.getCandidateTopK(), 0);
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
            holder.rankScore += channelWeight * hybridRankWeight() * normalizedRankScore;
            holder.originalScore += channelWeight * hybridOriginalScoreWeight() * normalizedOriginalScore;
            holder.metadataBoost = Math.max(holder.metadataBoost, calculateMetadataBoost(document, metadataBoostTerms));
            holder.channels.add(channelResult.getChannelName());
            if (RetrievalChannelEnum.VECTOR.getName().equals(channelResult.getChannelName()) && originalScore != null) {
                holder.vectorScore = originalScore;
            }
            if (RetrievalChannelEnum.KEYWORD.getName().equals(channelResult.getChannelName()) && originalScore != null) {
                holder.keywordScore = originalScore;
            }
        }
    }

    private void finishHybridScore(CandidateHolder holder) {
        holder.score = holder.rankScore
            + holder.originalScore
            + hybridMetadataBoostWeight() * Math.min(holder.metadataBoost, hybridMaxMetadataBoost());
    }

    private double normalizeOriginalScore(Double originalScore, double channelMaxScore) {
        if (originalScore == null || originalScore <= 0D || channelMaxScore <= 0D) {
            return 0D;
        }
        return Math.min(1D, originalScore / channelMaxScore);
    }

    private double resolveChannelWeight(String channelName, ConversationExecutionPlan plan) {
        ChatRagProperties.HybridProperties hybrid = properties.getHybrid();
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
        if (intent == RetrievalIntent.TABLE) {
            if (RetrievalChannelEnum.TABLE.getName().equals(channelName)) {
                return 1.45D;
            }
            if (RetrievalChannelEnum.VECTOR.getName().equals(channelName) || RetrievalChannelEnum.KEYWORD.getName().equals(channelName)) {
                return 0.9D;
            }
        }
        if (intent == RetrievalIntent.GRAPH_RAG) {
            if (RetrievalChannelEnum.GRAPH_RAG.getName().equals(channelName)) {
                return 1.4D;
            }
            if (RetrievalChannelEnum.KEYWORD.getName().equals(channelName)) {
                return 1.05D;
            }
        }
        if (intent == RetrievalIntent.RAPTOR) {
            if (RetrievalChannelEnum.RAPTOR.getName().equals(channelName)) {
                return 1.4D;
            }
            if (RetrievalChannelEnum.VECTOR.getName().equals(channelName)) {
                return 1.1D;
            }
        }
        if (intent == RetrievalIntent.STRUCTURE) {
            if (RetrievalChannelEnum.KEYWORD.getName().equals(channelName) || RetrievalChannelEnum.VECTOR.getName().equals(channelName)) {
                return 1.1D;
            }
        }
        return 1D;
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

    private double hybridRankWeight() {
        ChatRagProperties.HybridProperties hybrid = properties.getHybrid();
        return hybrid == null ? 1D : Math.max(0D, hybrid.getRankWeight());
    }

    private double hybridOriginalScoreWeight() {
        ChatRagProperties.HybridProperties hybrid = properties.getHybrid();
        return hybrid == null ? 0.08D : Math.max(0D, hybrid.getOriginalScoreWeight());
    }

    private double hybridMetadataBoostWeight() {
        ChatRagProperties.HybridProperties hybrid = properties.getHybrid();
        return hybrid == null ? 0.04D : Math.max(0D, hybrid.getMetadataBoostWeight());
    }

    private double hybridMaxMetadataBoost() {
        ChatRagProperties.HybridProperties hybrid = properties.getHybrid();
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

    private double calculateMetadataBoost(Document document, List<String> terms) {
        if (document == null || document.getMetadata() == null || terms == null || terms.isEmpty()) {
            return 0D;
        }
        double boost = 0D;
        boost += containsAnyMetadataTerm(document, terms, DocumentKnowledgeMetadataKeys.TITLE) ? 0.30D : 0D;
        boost += containsAnyMetadataTerm(document, terms, DocumentKnowledgeMetadataKeys.SECTION_PATH) ? 0.22D : 0D;
        boost += containsAnyMetadataTerm(document, terms, DocumentKnowledgeMetadataKeys.KEYWORDS) ? 0.18D : 0D;
        boost += containsAnyMetadataTerm(document, terms, DocumentKnowledgeMetadataKeys.QUESTIONS) ? 0.14D : 0D;
        boost += containsAnyMetadataTerm(document, terms, DocumentKnowledgeMetadataKeys.DOCUMENT_NAME) ? 0.10D : 0D;
        boost += containsAnyMetadataTerm(document, terms, DocumentKnowledgeMetadataKeys.KNOWLEDGE_SCOPE_NAME) ? 0.08D : 0D;
        boost += containsAnyMetadataTerm(document, terms, DocumentKnowledgeMetadataKeys.BUSINESS_CATEGORY) ? 0.06D : 0D;
        boost += containsAnyMetadataTerm(document, terms, DocumentKnowledgeMetadataKeys.DOCUMENT_TAGS) ? 0.06D : 0D;
        boost += chunkTypeBoost(document, terms);
        boost += graphRankMetadataBoost(document);
        return Math.min(boost, hybridMaxMetadataBoost());
    }

    private boolean containsAnyMetadataTerm(Document document, List<String> terms, String metadataKey) {
        String metadataText = normalizeBoostText(safeText(document.getMetadata().get(metadataKey)));
        if (metadataText.isBlank()) {
            return false;
        }
        return terms.stream().anyMatch(term -> term.length() >= 2 && metadataText.contains(term));
    }

    private double chunkTypeBoost(Document document, List<String> terms) {
        String chunkType = normalizeBoostText(safeText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.CHUNK_TYPE)));
        if (chunkType.isBlank()) {
            return 0D;
        }
        String queryText = String.join(" ", terms);
        if ("table".equals(chunkType) && (queryText.contains("表") || queryText.contains("字段") || queryText.contains("数据"))) {
            return 0.08D;
        }
        if (("image".equals(chunkType) || "figure".equals(chunkType))
            && (queryText.contains("图") || queryText.contains("图片") || queryText.contains("截图"))) {
            return 0.08D;
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
        if (isGraphRagCommunityReportReserveCandidate(document, plan)) {
            return true;
        }
        boolean hasRelationEvidence = isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_ID))
            && isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID));
        if (!hasRelationEvidence) {
            return false;
        }
        boolean queryPlanOrNhop = isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_SOURCE))
            || isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_NHOP_PATH))
            || isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY));
        if (!queryPlanOrNhop) {
            return false;
        }
        Double qualityScore = numericMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_QUALITY_SCORE));
        boolean qualityAccepted = qualityScore == null || qualityScore >= 0.55D;
        if (!qualityAccepted) {
            return false;
        }
        String relationType = safeText(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_TYPE)).toUpperCase(Locale.ROOT);
        if (GRAPH_RAG_ACTION_RELATION_TYPES.contains(relationType)) {
            return true;
        }
        if (resolveRetrievalIntent(plan) == RetrievalIntent.GRAPH_RAG) {
            return true;
        }
        return GRAPH_RAG_WEAK_RELATION_TYPES.contains(relationType)
            && isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY));
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
        Integer communityDocumentCount = integerMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_DOCUMENT_COUNT));
        if (communityDocumentCount != null && communityDocumentCount < 2) {
            return false;
        }
        boolean grounded = isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID))
            && (isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY))
            || isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_SUMMARY))
            || isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_TITLE)));
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
        String normalized = normalizeBoostText(joinSections(
            plan == null ? "" : plan.getOriginalQuestion(),
            plan == null ? "" : plan.getAgentQuestion(),
            plan == null ? "" : plan.getRewriteQuestion(),
            plan == null ? "" : plan.getRetrievalQuestion(),
            plan == null || plan.getRetrievalSubQuestions() == null ? "" : String.join(" ", plan.getRetrievalSubQuestions()),
            plan == null || plan.getNavigationDecision() == null || plan.getNavigationDecision().getQueryContextHints() == null
                ? "" : String.join(" ", plan.getNavigationDecision().getQueryContextHints()),
            plan == null || plan.getHistoryPlanningContext() == null || plan.getHistoryPlanningContext().getQueryContextHints() == null
                ? "" : String.join(" ", plan.getHistoryPlanningContext().getQueryContextHints())
        ));
        if (normalized.isBlank()) {
            return false;
        }
        boolean hasCommunitySignal = normalized.contains("community")
            || normalized.contains("communities")
            || normalized.contains("社区")
            || normalized.contains("社群")
            || normalized.contains("群组")
            || normalized.contains("cluster")
            || normalized.contains("clusters");
        boolean hasReportSignal = normalized.contains("report")
            || normalized.contains("summary")
            || normalized.contains("overview")
            || normalized.contains("总结")
            || normalized.contains("报告")
            || normalized.contains("概览")
            || normalized.contains("摘要");
        boolean hasGraphScopeSignal = normalized.contains("graphrag")
            || normalized.contains("graph")
            || normalized.contains("图谱")
            || normalized.contains("知识图谱")
            || normalized.contains("跨文档")
            || normalized.contains("全局");
        return hasCommunitySignal && (hasReportSignal || hasGraphScopeSignal);
    }

    private double graphRagEvidenceBudgetPriority(Document document, ConversationExecutionPlan plan) {
        if (document == null || document.getMetadata() == null) {
            return 0D;
        }
        Map<String, Object> metadata = document.getMetadata();
        double priority = finalDocumentScore(document);
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
        String relationType = safeText(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_TYPE)).toUpperCase(Locale.ROOT);
        if (GRAPH_RAG_ACTION_RELATION_TYPES.contains(relationType)) {
            priority += 2.0D;
        }
        else if (GRAPH_RAG_WEAK_RELATION_TYPES.contains(relationType)) {
            priority += 0.55D;
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
        if (resolveRetrievalIntent(plan) == RetrievalIntent.GRAPH_RAG) {
            priority += 0.25D;
        }
        Double qualityScore = numericMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.KG_QUALITY_SCORE));
        if (qualityScore != null) {
            priority += Math.min(0.45D, Math.max(0D, qualityScore) * 0.45D);
        }
        return priority;
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
                                       List<String> usedChannels,
                                       List<String> notes) {
        if (!properties.isRerankEnabled() || candidates.isEmpty()) {
            return candidates;
        }

        try {
            List<Document> rerankedCandidates = ragRerankService.rerank(subQuestion, candidates);
            markUsedChannel(usedChannels, RetrievalChannelEnum.RERANK.getName());
            return rerankedCandidates;
        }
        catch (RuntimeException exception) {
            Throwable rootCause = unwrapThrowable(exception);
            markRerankFailure(candidates, rootCause);
            log.warn("rerank 失败，保留 weighted hybrid 候选继续回答: subQuestionIndex={}, subQuestion='{}', candidateCount={}, exceptionType={}, message={}",
                subQuestionIndex,
                subQuestion,
                candidates.size(),
                rootCause == null ? "" : rootCause.getClass().getName(),
                rootCause == null ? "" : rootCause.getMessage(),
                exception);
            notes.add("子问题" + subQuestionIndex + " rerank 失败或超时，已保留融合候选继续回答。");
            return candidates;
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

    private void assignReferenceIds(List<SubQuestionEvidence> evidenceList) {
        final int[] referenceNumber = {1};
        Map<String, String> assignedIds = new LinkedHashMap<>();
        for (SubQuestionEvidence evidence : evidenceList) {
            List<SearchReference> references = new ArrayList<>();
            for (Document document : evidence.getDocuments()) {

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
                                                   List<Document> finalDocuments) {
        List<RetrievalResultView> results = new ArrayList<>();
        Map<String, Integer> finalRankMap = new LinkedHashMap<>();
        Map<String, Document> mergedCandidateMap = new LinkedHashMap<>();
        Map<String, Document> rerankedCandidateMap = new LinkedHashMap<>();
        if (finalDocuments != null) {
            for (int i = 0; i < finalDocuments.size(); i++) {
                String docId = finalDocuments.get(i).getId();
                if (docId != null) {
                    finalRankMap.put(docId, i + 1);
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
                    Document mergedDoc = doc.getId() == null ? null : mergedCandidateMap.get(doc.getId());
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
                        view.setSelectionReason("已选入最终 Prompt");
                    } else if (!passedGate) {

                        double score = originalScore == null ? 0D : originalScore;
                        if ("vector".equals(channelName)) {
                            view.setSelectionReason(String.format(
                                "向量闸门过滤：分数 %.4f < 阈值 %.4f",
                                score, properties.getMinVectorSimilarity()
                            ));
                        } else if ("keyword".equals(channelName)) {
                            view.setSelectionReason(String.format(
                                "关键词闸门过滤：分数 %.4f 低于相对阈值（floor=%.2f）",
                                score, properties.getKeywordRelativeScoreFloor()
                            ));
                        } else {
                            view.setSelectionReason("闸门过滤：分数 " + String.format("%.4f", score));
                        }
                    } else {
                        view.setSelectionReason("超出 finalTopK 限制（topK=" + properties.getFinalTopK() + "）");
                    }

                    results.add(view);
                }
            }
        }

        traceRecorder.recordRetrievalResults(results);
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
