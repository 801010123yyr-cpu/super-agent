package org.javaup.ai.chatagent.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.model.ChannelExecutionView;
import org.javaup.ai.chatagent.model.RetrievalResultView;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.RagRetrievalContext;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
        List<SubQuestionChannelTrace> channelTraces = buildChannelTraces(rawChannelResults, channelResults);

        channelResults.stream()
            .filter(result -> !result.getDocuments().isEmpty())
            .forEach(result -> markUsedChannel(usedChannels, result.getChannelName()));

        List<Document> mergedCandidates = fuseByWeightedHybrid(channelResults, subQuestion, plan);
        List<Document> parentCandidates = documentKnowledgeService.elevateToParentBlocks(
            mergedCandidates,
            properties.getParentEvidenceMaxChars()
        );
        List<Document> rerankedCandidates = applyRerank(subQuestion, parentCandidates, usedChannels);

        List<Document> finalDocuments = rerankedCandidates.stream()
            .limit(properties.getFinalTopK())
            .toList();

        notes.add("子问题" + subQuestionIndex + "检索完成："
            + summarizeChannelResults(channelResults)
            + "，final=" + finalDocuments.size());

        if (traceRecorder != null) {
            try {
                recordChannelObservations(traceRecorder, subQuestionIndex, subQuestion,
                    rawChannelResults, channelResults, channelTraces);
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
            accumulateWeightedHybrid(retrievalChannelResult, holders, channelMaxScoreMap, metadataBoostTerms);
        }

        return holders.values().stream()
            .peek(this::finishHybridScore)
            .sorted((left, right) -> Double.compare(right.score, left.score))
            .limit(properties.getCandidateTopK())
            .map(holder -> {

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
                return holder.document;
            })
            .toList();
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
                                          List<String> metadataBoostTerms) {
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
            double channelWeight = resolveChannelWeight(channelResult.getChannelName());
            if (RetrievalChannelEnum.VECTOR.getName().equals(channelResult.getChannelName()) && originalScore != null) {
                document.getMetadata().put(DocumentKnowledgeMetadataKeys.VECTOR_SCORE, originalScore);
            }
            if (RetrievalChannelEnum.KEYWORD.getName().equals(channelResult.getChannelName()) && originalScore != null) {
                document.getMetadata().put(DocumentKnowledgeMetadataKeys.KEYWORD_SCORE, originalScore);
            }
            CandidateHolder holder = holders.computeIfAbsent(documentId, ignored -> new CandidateHolder(document));
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

    private double resolveChannelWeight(String channelName) {
        ChatRagProperties.HybridProperties hybrid = properties.getHybrid();
        if (RetrievalChannelEnum.VECTOR.getName().equals(channelName)) {
            return hybrid == null ? 1D : Math.max(0D, hybrid.getVectorWeight());
        }
        if (RetrievalChannelEnum.KEYWORD.getName().equals(channelName)) {
            return hybrid == null ? 1D : Math.max(0D, hybrid.getKeywordWeight());
        }
        if (RetrievalChannelEnum.TABLE.getName().equals(channelName)) {
            return hybrid == null ? 1.2D : Math.max(0D, hybrid.getTableWeight());
        }
        if (RetrievalChannelEnum.GRAPH_RAG.getName().equals(channelName)) {
            return hybrid == null ? 1.1D : Math.max(0D, hybrid.getGraphRagWeight());
        }
        if (RetrievalChannelEnum.RAPTOR.getName().equals(channelName)) {
            return hybrid == null ? 1.05D : Math.max(0D, hybrid.getRaptorWeight());
        }
        return 1D;
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

    private String normalizeBoostText(String value) {
        return safeText(value)
            .replaceAll("[\\s>`*#_\\-，,。；;：:（）()“”\"'\\[\\]{}]+", "")
            .toLowerCase(Locale.ROOT);
    }

    private String safeText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private List<Document> applyRerank(String subQuestion,
                                       List<Document> candidates,
                                       List<String> usedChannels) {
        if (!properties.isRerankEnabled() || candidates.isEmpty()) {
            return candidates;
        }

        markUsedChannel(usedChannels, RetrievalChannelEnum.RERANK.getName());
        return ragRerankService.rerank(subQuestion, candidates);
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
                                                             List<RetrievalChannelResult> filteredResults) {
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
        for (String channelName : channelNames) {
            traces.add(new SubQuestionChannelTrace(
                channelName,
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
                                           List<SubQuestionChannelTrace> channelTraces) {
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

            SubQuestionChannelTrace trace = channelTraces == null ? null :
                channelTraces.stream().filter(t -> channelName.equals(t.getChannelName())).findFirst().orElse(null);
            int finalSelectedCount = trace == null ? 0 : trace.getAcceptedCount();

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

                    Object rerankScoreObj = doc.getMetadata().get("rerankScore");
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
                            fr.getDocuments().stream().anyMatch(d -> Objects.equals(d.getId(), doc.getId())));
                    view.setGatePassed(passedGate);

                    boolean isSelected = doc.getId() != null && finalRankMap.containsKey(doc.getId());
                    view.setSelected(isSelected);

                    if (isSelected) {
                        view.setFinalRank(finalRankMap.get(doc.getId()));
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
