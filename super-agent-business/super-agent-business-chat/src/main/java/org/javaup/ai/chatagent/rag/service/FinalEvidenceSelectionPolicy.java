package org.javaup.ai.chatagent.rag.service;

import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.QueryType;
import org.javaup.ai.chatagent.rag.model.QueryUnderstandingResult;
import org.javaup.ai.chatagent.rag.model.RagRuntimeOptions;
import org.javaup.ai.chatagent.rag.model.RetrievalIntent;
import org.javaup.ai.chatagent.rag.support.EvidenceIdentityResolver;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.javaup.enums.RetrievalChannelEnum;
import org.springframework.ai.document.Document;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 最终证据预算策略。只使用结构化 metadata 和受控查询理解结果，不读取业务词。
 */
public class FinalEvidenceSelectionPolicy {

    public static final String RESERVE_TOP_RANK = "TOP_RANK";
    public static final String RESERVE_SAME_SECTION_BODY = "SAME_SECTION_BODY";
    public static final String RESERVE_STRUCTURE_ANCHOR = "STRUCTURE_ANCHOR";
    public static final String RESERVE_STRUCTURE_ANCHOR_BODY = "STRUCTURE_ANCHOR_BODY";
    public static final String RESERVE_STRUCTURE_DESCENDANT_BODY = "STRUCTURE_DESCENDANT_BODY";
    public static final String RESERVE_STRUCTURE_NAVIGATION_CURRENT = "STRUCTURE_NAVIGATION_CURRENT";
    public static final String RESERVE_STRUCTURE_NAVIGATION_PARENT = "STRUCTURE_NAVIGATION_PARENT";
    public static final String RESERVE_STRUCTURE_NAVIGATION_SIBLING = "STRUCTURE_NAVIGATION_SIBLING";
    public static final String RESERVE_STRUCTURE_NAVIGATION_CHILD = "STRUCTURE_NAVIGATION_CHILD";
    public static final String RESERVE_ROUTE_CANDIDATE_SOURCE = "ROUTE_CANDIDATE_SOURCE";
    public static final String RESERVE_MULTI_DOC_DIVERSITY = "MULTI_DOC_DIVERSITY_RESERVE";
    public static final String RESERVE_GRAPH_RAG_QUOTE = "GRAPH_RAG_QUOTE";
    public static final String RESERVE_RAPTOR_SOURCE_CHUNK = "RAPTOR_SOURCE_CHUNK";
    public static final String SELECTED_TOP_RANK = "SELECTED_TOP_RANK";
    public static final String SELECTED_SAME_SECTION_BODY = "SELECTED_SAME_SECTION_BODY";
    public static final String SELECTED_STRUCTURE_ANCHOR = "SELECTED_STRUCTURE_ANCHOR";
    public static final String SELECTED_STRUCTURE_ANCHOR_BODY = "SELECTED_STRUCTURE_ANCHOR_BODY";
    public static final String SELECTED_STRUCTURE_DESCENDANT_BODY = "SELECTED_STRUCTURE_DESCENDANT_BODY";
    public static final String SELECTED_STRUCTURE_NAVIGATION_CURRENT = "SELECTED_STRUCTURE_NAVIGATION_CURRENT";
    public static final String SELECTED_STRUCTURE_NAVIGATION_PARENT = "SELECTED_STRUCTURE_NAVIGATION_PARENT";
    public static final String SELECTED_STRUCTURE_NAVIGATION_SIBLING = "SELECTED_STRUCTURE_NAVIGATION_SIBLING";
    public static final String SELECTED_STRUCTURE_NAVIGATION_CHILD = "SELECTED_STRUCTURE_NAVIGATION_CHILD";
    public static final String SELECTED_ROUTE_CANDIDATE_RESERVE = "SELECTED_ROUTE_CANDIDATE_RESERVE";
    public static final String SELECTED_MULTI_DOC_DIVERSITY_RESERVE = "SELECTED_MULTI_DOC_DIVERSITY_RESERVE";
    public static final String REPLACED_TITLE_ONLY_WITH_BODY = "REPLACED_TITLE_ONLY_WITH_BODY";
    public static final String SELECTED_GRAPH_RAG_QUOTE = "SELECTED_GRAPH_RAG_QUOTE";
    public static final String SELECTED_RAPTOR_SOURCE_CHUNK = "SELECTED_RAPTOR_SOURCE_CHUNK";

    private final ChatRagProperties properties;

    public FinalEvidenceSelectionPolicy(ChatRagProperties properties) {
        this.properties = properties;
    }

    public List<Document> select(List<Document> rerankedCandidates, ConversationExecutionPlan plan) {
        if (rerankedCandidates == null || rerankedCandidates.isEmpty()) {
            return List.of();
        }
        int finalTopK = Math.max(runtimeOptions(plan).getFinalTopK(), 0);
        if (finalTopK <= 0) {
            return List.of();
        }

        List<Document> selected = new ArrayList<>(rerankedCandidates.stream()
            .limit(finalTopK)
            .toList());
        selected.forEach(this::markInitialSelection);
        reserveStructureAnchorBodyCandidates(rerankedCandidates, selected);
        if (rerankedCandidates.size() <= finalTopK) {
            return selected;
        }
        reserveSameSectionBody(rerankedCandidates, selected);
        reserveStructureAnchor(rerankedCandidates, selected, plan);
        reserveRouteCandidateSourceEvidence(rerankedCandidates, selected, plan);
        reserveRaptorSourceEvidence(rerankedCandidates, finalTopK, selected, plan);
        reserveGraphRagEvidence(rerankedCandidates, finalTopK, selected, plan);
        return selected;
    }

    private void reserveStructureAnchorBodyCandidates(List<Document> candidates, List<Document> selected) {
        List<Document> bodyCandidates = candidates.stream()
            .filter(this::isStructureAnchorBodyCandidate)
            .filter(this::isBodyEvidence)
            .sorted(Comparator.comparingDouble(this::structureAnchorBodyPriority).reversed())
            .toList();
        if (bodyCandidates.isEmpty()) {
            return;
        }
        for (Document bodyCandidate : bodyCandidates) {
            Document alreadySelected = findSameCitationEvidence(selected, bodyCandidate);
            String reserveType = structureAnchorReserveType(bodyCandidate);
            if (alreadySelected != null) {
                markReserve(alreadySelected, reserveType);
                continue;
            }
            int replaceIndex = firstReplaceableTitleOrSummaryOnlyIndex(selected);
            boolean replacesTitleOrSummary = replaceIndex >= 0;
            if (replaceIndex < 0) {
                replaceIndex = weakestReplaceableEvidenceIndex(selected);
            }
            if (replaceIndex < 0) {
                return;
            }
            String reason = replacesTitleOrSummary ? REPLACED_TITLE_ONLY_WITH_BODY : selectedReasonCode(reserveType);
            markReserve(bodyCandidate, reserveType, reason);
            selected.set(replaceIndex, bodyCandidate);
        }
    }

    private void reserveSameSectionBody(List<Document> candidates, List<Document> selected) {
        List<Document> selectedTitles = selected.stream()
            .filter(this::isTitleEvidence)
            .toList();
        if (selectedTitles.isEmpty()) {
            return;
        }
        for (Document title : selectedTitles) {
            Document body = candidates.stream()
                .filter(candidate -> !containsSameCitationEvidence(selected, candidate))
                .filter(this::isBodyEvidence)
                .filter(candidate -> sameStructureAnchor(title, candidate))
                .max(Comparator.comparingDouble(this::finalDocumentScore))
                .orElse(null);
            if (body == null) {
                continue;
            }
            replaceWeakestEvidence(selected, body, RESERVE_SAME_SECTION_BODY);
        }
    }

    private void reserveStructureAnchor(List<Document> candidates,
                                        List<Document> selected,
                                        ConversationExecutionPlan plan) {
        List<String> anchors = sectionAnchors(plan);
        if (anchors.isEmpty()) {
            return;
        }
        Document anchored = candidates.stream()
            .filter(candidate -> !containsSameCitationEvidence(selected, candidate))
            .filter(this::isBodyEvidence)
            .filter(candidate -> matchesAnySectionAnchor(candidate, anchors))
            .max(Comparator.comparingDouble(this::finalDocumentScore))
            .orElse(null);
        if (anchored != null) {
            replaceWeakestEvidence(selected, anchored, RESERVE_STRUCTURE_ANCHOR);
        }
    }

    private void reserveGraphRagEvidence(List<Document> rerankedCandidates,
                                         int finalTopK,
                                         List<Document> selected,
                                         ConversationExecutionPlan plan) {
        boolean preferCrossDocumentCommunity = shouldReserveCrossDocumentCommunityEvidence(plan);
        if (selected.stream().anyMatch(document -> isRequiredGraphRagReserveCandidate(document, plan, preferCrossDocumentCommunity))) {
            return;
        }
        Document graphRagReserve = selectGraphRagReserveCandidate(
            rerankedCandidates,
            finalTopK,
            selected,
            plan,
            preferCrossDocumentCommunity
        );
        if (graphRagReserve == null && preferCrossDocumentCommunity) {
            graphRagReserve = selectGraphRagReserveCandidate(rerankedCandidates, finalTopK, selected, plan, false);
        }
        if (graphRagReserve != null) {
            replaceGraphRagSummaryOnlyOrWeakestEvidence(selected, graphRagReserve, RESERVE_GRAPH_RAG_QUOTE);
        }
    }

    private void reserveRouteCandidateSourceEvidence(List<Document> candidates,
                                                     List<Document> selected,
                                                     ConversationExecutionPlan plan) {
        if (!isMultiDocumentAutoRetrieval(plan) || candidates == null || candidates.isEmpty()) {
            return;
        }
        List<Document> reserves = candidates.stream()
            .filter(this::isRouteCandidateSourceReserve)
            .filter(EvidenceIdentityResolver::isCitationCapable)
            .filter(candidate -> !containsSameCitationEvidence(selected, candidate))
            .sorted(Comparator.comparingDouble(this::finalDocumentScore).reversed())
            .toList();
        if (reserves.isEmpty()) {
            return;
        }
        for (Document reserve : reserves) {
            if (containsSameCitationEvidence(selected, reserve)) {
                continue;
            }
            int replaceIndex = firstReplaceableContextOnlyOrSummaryIndex(selected);
            if (replaceIndex < 0) {
                replaceIndex = weakestReplaceableEvidenceIndex(selected);
            }
            if (replaceIndex < 0) {
                return;
            }
            markReserve(reserve, RESERVE_ROUTE_CANDIDATE_SOURCE);
            selected.set(replaceIndex, reserve);
        }
    }

    private void reserveRaptorSourceEvidence(List<Document> rerankedCandidates,
                                             int finalTopK,
                                             List<Document> selected,
                                             ConversationExecutionPlan plan) {
        if (selected.stream().anyMatch(this::isRaptorSourceCandidate)) {
            return;
        }
        Document raptorReserve = rerankedCandidates.stream()
            .skip(finalTopK)
            .filter(this::isRaptorSourceCandidate)
            .filter(candidate -> !containsSameCitationEvidence(selected, candidate))
            .max(Comparator.comparingDouble(document -> raptorEvidenceBudgetPriority(document, plan)))
            .orElse(null);
        if (raptorReserve != null) {
            replaceSummaryOnlyOrWeakestEvidence(selected, raptorReserve, RESERVE_RAPTOR_SOURCE_CHUNK);
        }
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
            .filter(candidate -> !containsSameCitationEvidence(selected, candidate))
            .max(Comparator.comparingDouble(document -> graphRagEvidenceBudgetPriority(document, plan)))
            .orElse(null);
    }

    private void replaceWeakestEvidence(List<Document> selected,
                                        Document reserve,
                                        String reserveType) {
        if (selected == null || selected.isEmpty() || reserve == null) {
            return;
        }
        if (containsSameCitationEvidence(selected, reserve)) {
            return;
        }
        int replaceIndex = weakestReplaceableEvidenceIndex(selected);
        if (replaceIndex < 0) {
            return;
        }
        markReserve(reserve, reserveType);
        selected.set(replaceIndex, reserve);
    }

    private void replaceSummaryOnlyOrWeakestEvidence(List<Document> selected,
                                                     Document reserve,
                                                     String reserveType) {
        if (selected == null || selected.isEmpty() || reserve == null || containsSameCitationEvidence(selected, reserve)) {
            return;
        }
        int replaceIndex = firstReplaceableRaptorSummaryOnlyIndex(selected);
        if (replaceIndex < 0) {
            replaceIndex = weakestReplaceableEvidenceIndex(selected);
        }
        if (replaceIndex < 0) {
            return;
        }
        markReserve(reserve, reserveType);
        selected.set(replaceIndex, reserve);
    }

    private void replaceGraphRagSummaryOnlyOrWeakestEvidence(List<Document> selected,
                                                             Document reserve,
                                                             String reserveType) {
        if (selected == null || selected.isEmpty() || reserve == null || containsSameCitationEvidence(selected, reserve)) {
            return;
        }
        int replaceIndex = firstReplaceableGraphRagCommunitySummaryOnlyIndex(selected);
        if (replaceIndex < 0) {
            replaceIndex = weakestReplaceableEvidenceIndex(selected);
        }
        if (replaceIndex < 0) {
            return;
        }
        markReserve(reserve, reserveType);
        selected.set(replaceIndex, reserve);
    }

    private int firstReplaceableRaptorSummaryOnlyIndex(List<Document> selected) {
        for (int index = 0; index < selected.size(); index++) {
            Document document = selected.get(index);
            if (!isProtectedReserve(document) && isRaptorSummaryOnly(document)) {
                return index;
            }
        }
        return -1;
    }

    private int firstReplaceableGraphRagCommunitySummaryOnlyIndex(List<Document> selected) {
        for (int index = 0; index < selected.size(); index++) {
            Document document = selected.get(index);
            if (!isProtectedReserve(document) && isGraphRagCommunitySummaryOnly(document)) {
                return index;
            }
        }
        return -1;
    }

    private int firstReplaceableTitleOrSummaryOnlyIndex(List<Document> selected) {
        for (int index = 0; index < selected.size(); index++) {
            Document document = selected.get(index);
            if (isProtectedReserve(document)) {
                continue;
            }
            if (isTitleEvidence(document) || isRaptorSummaryOnly(document) || isGraphRagCommunitySummaryOnly(document)) {
                return index;
            }
        }
        return -1;
    }

    private int firstReplaceableContextOnlyOrSummaryIndex(List<Document> selected) {
        for (int index = 0; index < selected.size(); index++) {
            Document document = selected.get(index);
            if (isProtectedReserve(document)) {
                continue;
            }
            if (EvidenceIdentityResolver.isContextOnly(document)
                || isTitleEvidence(document)
                || isRaptorSummaryOnly(document)
                || isGraphRagCommunitySummaryOnly(document)) {
                return index;
            }
        }
        return -1;
    }

    private int weakestReplaceableEvidenceIndex(List<Document> selected) {
        int replaceIndex = -1;
        double weakestScore = Double.MAX_VALUE;
        for (int index = 0; index < selected.size(); index++) {
            Document document = selected.get(index);
            if (isProtectedReserve(document)) {
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

    private boolean isProtectedReserve(Document document) {
        if (document == null || document.getMetadata() == null) {
            return false;
        }
        String reserveType = safeText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.FINAL_SELECTION_RESERVE_TYPE));
        return RESERVE_SAME_SECTION_BODY.equals(reserveType)
            || RESERVE_STRUCTURE_ANCHOR.equals(reserveType)
            || RESERVE_STRUCTURE_ANCHOR_BODY.equals(reserveType)
            || RESERVE_STRUCTURE_DESCENDANT_BODY.equals(reserveType)
            || isStructureNavigationReserveType(reserveType)
            || RESERVE_ROUTE_CANDIDATE_SOURCE.equals(reserveType)
            || RESERVE_MULTI_DOC_DIVERSITY.equals(reserveType)
            || RESERVE_GRAPH_RAG_QUOTE.equals(reserveType)
            || RESERVE_RAPTOR_SOURCE_CHUNK.equals(reserveType);
    }

    private void markReserve(Document document, String reserveType) {
        if (document == null || document.getMetadata() == null) {
            return;
        }
        document.getMetadata().put(DocumentKnowledgeMetadataKeys.FINAL_SELECTION_REASON, selectedReasonCode(reserveType));
        document.getMetadata().putIfAbsent(DocumentKnowledgeMetadataKeys.FINAL_SELECTION_RESERVE_TYPE, reserveType);
    }

    private void markInitialSelection(Document document) {
        String reserveType = safeText(document == null || document.getMetadata() == null
            ? null
            : document.getMetadata().get(DocumentKnowledgeMetadataKeys.FINAL_SELECTION_RESERVE_TYPE));
        if (isStructureNavigationReserveType(reserveType)) {
            markReserve(document, reserveType, selectedReasonCode(reserveType));
            return;
        }
        if (RESERVE_ROUTE_CANDIDATE_SOURCE.equals(reserveType) || RESERVE_MULTI_DOC_DIVERSITY.equals(reserveType)) {
            markReserve(document, reserveType, selectedReasonCode(reserveType));
            return;
        }
        markReserve(document, RESERVE_TOP_RANK);
    }

    private void markReserve(Document document, String reserveType, String reasonCode) {
        if (document == null || document.getMetadata() == null) {
            return;
        }
        document.getMetadata().put(DocumentKnowledgeMetadataKeys.FINAL_SELECTION_REASON, reasonCode);
        document.getMetadata().put(DocumentKnowledgeMetadataKeys.FINAL_SELECTION_RESERVE_TYPE, reserveType);
    }

    private String selectedReasonCode(String reserveType) {
        return switch (reserveType) {
            case RESERVE_SAME_SECTION_BODY -> SELECTED_SAME_SECTION_BODY;
            case RESERVE_STRUCTURE_ANCHOR -> SELECTED_STRUCTURE_ANCHOR;
            case RESERVE_STRUCTURE_ANCHOR_BODY -> SELECTED_STRUCTURE_ANCHOR_BODY;
            case RESERVE_STRUCTURE_DESCENDANT_BODY -> SELECTED_STRUCTURE_DESCENDANT_BODY;
            case RESERVE_STRUCTURE_NAVIGATION_CURRENT -> SELECTED_STRUCTURE_NAVIGATION_CURRENT;
            case RESERVE_STRUCTURE_NAVIGATION_PARENT -> SELECTED_STRUCTURE_NAVIGATION_PARENT;
            case RESERVE_STRUCTURE_NAVIGATION_SIBLING -> SELECTED_STRUCTURE_NAVIGATION_SIBLING;
            case RESERVE_STRUCTURE_NAVIGATION_CHILD -> SELECTED_STRUCTURE_NAVIGATION_CHILD;
            case RESERVE_ROUTE_CANDIDATE_SOURCE -> SELECTED_ROUTE_CANDIDATE_RESERVE;
            case RESERVE_MULTI_DOC_DIVERSITY -> SELECTED_MULTI_DOC_DIVERSITY_RESERVE;
            case RESERVE_GRAPH_RAG_QUOTE -> SELECTED_GRAPH_RAG_QUOTE;
            case RESERVE_RAPTOR_SOURCE_CHUNK -> SELECTED_RAPTOR_SOURCE_CHUNK;
            default -> SELECTED_TOP_RANK;
        };
    }

    private boolean isStructureNavigationReserveType(String reserveType) {
        return RESERVE_STRUCTURE_NAVIGATION_CURRENT.equals(reserveType)
            || RESERVE_STRUCTURE_NAVIGATION_PARENT.equals(reserveType)
            || RESERVE_STRUCTURE_NAVIGATION_SIBLING.equals(reserveType)
            || RESERVE_STRUCTURE_NAVIGATION_CHILD.equals(reserveType);
    }

    private boolean isTitleEvidence(Document document) {
        if (document == null || document.getMetadata() == null) {
            return false;
        }
        String chunkType = normalizeSimple(safeText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.CHUNK_TYPE)));
        if ("title".equals(chunkType) || "heading".equals(chunkType)) {
            return true;
        }
        String nodeType = normalizeSimple(safeText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_TYPE)));
        if ("title".equals(nodeType) || "heading".equals(nodeType)) {
            return true;
        }
        String text = safeText(document.getText());
        return text.length() <= 120 && (text.startsWith("#") || text.matches("^\\d+(\\.\\d+){1,5}\\s+\\S.*$"));
    }

    private boolean isBodyEvidence(Document document) {
        if (document == null) {
            return false;
        }
        if (isTechnicalWrapperEvidence(document)) {
            return false;
        }
        if (isTitleEvidence(document)) {
            return false;
        }
        return safeText(document.getText()).length() >= 12;
    }

    private boolean isStructureAnchorBodyCandidate(Document document) {
        if (document == null || document.getMetadata() == null) {
            return false;
        }
        Map<String, Object> metadata = document.getMetadata();
        if (!booleanMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.STRUCTURE_ANCHOR_RAW_BODY))) {
            return false;
        }
        if (!EvidenceIdentityResolver.isCitationCapable(document)) {
            return false;
        }
        String reserveType = safeText(metadata.get(DocumentKnowledgeMetadataKeys.FINAL_SELECTION_RESERVE_TYPE));
        String channel = safeText(metadata.get(DocumentKnowledgeMetadataKeys.CHANNEL));
        Object bypass = metadata.get(DocumentKnowledgeMetadataKeys.STRUCTURE_ANCHOR_BYPASS_RESERVE_WINDOW);
        return "STRUCTURE_ANCHOR_BODY_CANDIDATE".equalsIgnoreCase(reserveType)
            || "structure-anchor".equalsIgnoreCase(channel)
            || Boolean.TRUE.equals(bypass)
            || Boolean.parseBoolean(String.valueOf(bypass));
    }

    private boolean isRouteCandidateSourceReserve(Document document) {
        if (document == null || document.getMetadata() == null) {
            return false;
        }
        String reserveType = safeText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.FINAL_SELECTION_RESERVE_TYPE));
        return RESERVE_ROUTE_CANDIDATE_SOURCE.equals(reserveType);
    }

    private boolean isMultiDocumentAutoRetrieval(ConversationExecutionPlan plan) {
        return plan != null
            && plan.getRetrievalDocumentIds() != null
            && plan.getRetrievalDocumentIds().stream().filter(Objects::nonNull).distinct().limit(2).count() > 1;
    }

    private boolean isTechnicalWrapperEvidence(Document document) {
        if (document == null || document.getMetadata() == null) {
            return false;
        }
        Map<String, Object> metadata = document.getMetadata();
        String chunkType = safeText(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_TYPE));
        if ("RAPTOR_SUMMARY".equalsIgnoreCase(chunkType)) {
            return true;
        }
        if (isRaptorSummaryOnly(document) || isGraphRagCommunitySummaryOnly(document)) {
            return true;
        }
        String channel = safeText(metadata.get(DocumentKnowledgeMetadataKeys.CHANNEL));
        String sourceType = safeText(metadata.get(DocumentKnowledgeMetadataKeys.SOURCE_TYPE));
        if ("graph-rag".equalsIgnoreCase(channel) || "GRAPH_RAG".equalsIgnoreCase(sourceType)) {
            return !hasGraphRagSourceQuote(document);
        }
        String text = safeText(document.getText()).trim();
        return text.startsWith("[GraphRAG") || text.startsWith("[RAPTOR");
    }

    private String structureAnchorReserveType(Document document) {
        if (document == null || document.getMetadata() == null) {
            return RESERVE_STRUCTURE_ANCHOR_BODY;
        }
        String matchType = safeText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.STRUCTURE_ANCHOR_MATCH_TYPE));
        return "CANONICAL_DESCENDANT".equalsIgnoreCase(matchType)
            ? RESERVE_STRUCTURE_DESCENDANT_BODY
            : RESERVE_STRUCTURE_ANCHOR_BODY;
    }

    private double structureAnchorBodyPriority(Document document) {
        if (document == null || document.getMetadata() == null) {
            return 0D;
        }
        double priority = finalDocumentScore(document) + 2D;
        String matchType = safeText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.STRUCTURE_ANCHOR_MATCH_TYPE));
        if ("NODE_ID".equalsIgnoreCase(matchType)) {
            priority += 0.6D;
        }
        else if ("CANONICAL_EXACT".equalsIgnoreCase(matchType)) {
            priority += 0.45D;
        }
        else if ("CANONICAL_DESCENDANT".equalsIgnoreCase(matchType)) {
            priority += 0.25D;
        }
        if (isMeaningfulMetadataValue(document.getMetadata().get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID))) {
            priority += 0.3D;
        }
        return priority;
    }

    private boolean sameStructureAnchor(Document left, Document right) {
        if (left == null || right == null || left.getMetadata() == null || right.getMetadata() == null) {
            return false;
        }
        Long leftDocumentId = longMetadataValue(left.getMetadata().get(DocumentKnowledgeMetadataKeys.DOCUMENT_ID));
        Long rightDocumentId = longMetadataValue(right.getMetadata().get(DocumentKnowledgeMetadataKeys.DOCUMENT_ID));
        if (leftDocumentId != null && rightDocumentId != null && !Objects.equals(leftDocumentId, rightDocumentId)) {
            return false;
        }
        Long leftNodeId = longMetadataValue(left.getMetadata().get(DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_ID));
        Long rightNodeId = longMetadataValue(right.getMetadata().get(DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_ID));
        if (leftNodeId != null && rightNodeId != null && Objects.equals(leftNodeId, rightNodeId)) {
            return true;
        }
        String leftCanonical = normalizedAnchor(left.getMetadata().get(DocumentKnowledgeMetadataKeys.CANONICAL_PATH));
        String rightCanonical = normalizedAnchor(right.getMetadata().get(DocumentKnowledgeMetadataKeys.CANONICAL_PATH));
        if (!leftCanonical.isBlank() && leftCanonical.equals(rightCanonical)) {
            return true;
        }
        String leftSection = normalizedAnchor(left.getMetadata().get(DocumentKnowledgeMetadataKeys.SECTION_PATH));
        String rightSection = normalizedAnchor(right.getMetadata().get(DocumentKnowledgeMetadataKeys.SECTION_PATH));
        return !leftSection.isBlank() && leftSection.equals(rightSection);
    }

    private boolean matchesAnySectionAnchor(Document document, List<String> anchors) {
        if (document == null || document.getMetadata() == null || anchors == null || anchors.isEmpty()) {
            return false;
        }
        String section = normalizedAnchor(document.getMetadata().get(DocumentKnowledgeMetadataKeys.SECTION_PATH));
        String canonical = normalizedAnchor(document.getMetadata().get(DocumentKnowledgeMetadataKeys.CANONICAL_PATH));
        String title = normalizedAnchor(document.getMetadata().get(DocumentKnowledgeMetadataKeys.TITLE));
        return anchors.stream()
            .map(this::normalizedAnchor)
            .filter(anchor -> !anchor.isBlank())
            .anyMatch(anchor -> equalsOrContains(section, anchor)
                || equalsOrContains(canonical, anchor)
                || equalsOrContains(title, anchor));
    }

    private boolean equalsOrContains(String value, String anchor) {
        if (value == null || value.isBlank() || anchor == null || anchor.isBlank()) {
            return false;
        }
        return value.equals(anchor) || value.contains(anchor) || anchor.contains(value);
    }

    private List<String> sectionAnchors(ConversationExecutionPlan plan) {
        QueryUnderstandingResult queryUnderstanding = plan == null ? null : plan.getQueryUnderstanding();
        if (queryUnderstanding == null || queryUnderstanding.getSectionAnchors() == null) {
            return List.of();
        }
        return queryUnderstanding.getSectionAnchors();
    }

    private boolean containsSameCitationEvidence(List<Document> documents, Document candidate) {
        return documents != null
            && documents.stream().anyMatch(document -> sameCitationEvidence(document, candidate));
    }

    private Document findSameCitationEvidence(List<Document> documents, Document candidate) {
        if (documents == null || candidate == null) {
            return null;
        }
        return documents.stream()
            .filter(document -> sameCitationEvidence(document, candidate))
            .findFirst()
            .orElse(null);
    }

    private boolean sameCitationEvidence(Document left, Document right) {
        if (left == null || right == null) {
            return false;
        }
        if (Objects.equals(left.getId(), right.getId())) {
            return true;
        }
        return EvidenceIdentityResolver.sameCitationEvidence(left, right);
    }

    private boolean isRequiredGraphRagReserveCandidate(Document document,
                                                       ConversationExecutionPlan plan,
                                                       boolean preferCrossDocumentCommunity) {
        if (!isGraphRagReserveCandidate(document, plan)) {
            return false;
        }
        return !preferCrossDocumentCommunity || isGraphRagCrossDocumentCommunityReserveCandidate(document, plan);
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

    private boolean isRaptorSourceCandidate(Document document) {
        if (document == null || document.getMetadata() == null || !isRaptorMetadata(document.getMetadata())) {
            return false;
        }
        if (isRaptorSummaryOnly(document)) {
            return false;
        }
        Map<String, Object> metadata = document.getMetadata();
        return isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_ID))
            || isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID));
    }

    private boolean isRaptorSummaryOnly(Document document) {
        if (document == null || document.getMetadata() == null || !isRaptorMetadata(document.getMetadata())) {
            return false;
        }
        Map<String, Object> metadata = document.getMetadata();
        String sourceStatus = safeText(metadata.get(DocumentKnowledgeMetadataKeys.RAPTOR_SOURCE_STATUS));
        if ("SUMMARY_ONLY".equalsIgnoreCase(sourceStatus)) {
            return true;
        }
        String chunkType = safeText(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_TYPE));
        if ("RAPTOR_SUMMARY".equalsIgnoreCase(chunkType)) {
            return true;
        }
        return !isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_ID))
            && !isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID));
    }

    private double raptorEvidenceBudgetPriority(Document document, ConversationExecutionPlan plan) {
        if (document == null || document.getMetadata() == null) {
            return 0D;
        }
        Map<String, Object> metadata = document.getMetadata();
        double priority = finalDocumentScore(document);
        if (isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_ID))) {
            priority += 1.0D;
        }
        if (isMeaningfulMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID))) {
            priority += 0.55D;
        }
        if (isSuggestedChannel(RetrievalChannelEnum.RAPTOR.getName(), plan)
            || resolveRetrievalIntent(plan) == RetrievalIntent.RAPTOR) {
            priority += 0.25D;
        }
        Integer nodeLevel = integerMetadataValue(metadata.get(DocumentKnowledgeMetadataKeys.RAPTOR_NODE_LEVEL));
        if (nodeLevel != null && nodeLevel <= 1) {
            priority += 0.08D;
        }
        return priority;
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
        Object originalSnippet = document.getMetadata().get(DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET);
        return isMeaningfulMetadataValue(originalSnippet)
            || (isMeaningfulMetadataValue(document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID))
            && isMeaningfulMetadataValue(document.getText()));
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

    private RetrievalIntent resolveRetrievalIntent(ConversationExecutionPlan plan) {
        return plan == null || plan.getRetrievalIntent() == null ? RetrievalIntent.GENERAL : plan.getRetrievalIntent();
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

    private boolean isRaptorMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return false;
        }
        String channel = safeText(metadata.get(DocumentKnowledgeMetadataKeys.CHANNEL));
        String sourceType = safeText(metadata.get(DocumentKnowledgeMetadataKeys.SOURCE_TYPE));
        return RetrievalChannelEnum.RAPTOR.getName().equals(channel)
            || "RAPTOR".equalsIgnoreCase(sourceType)
            || metadata.get(DocumentKnowledgeMetadataKeys.RAPTOR_NODE_ID) != null;
    }

    private boolean isMeaningfulMetadataValue(Object value) {
        if (value == null) {
            return false;
        }
        return !(value instanceof String text) || !text.isBlank();
    }

    private boolean booleanMetadataValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private double finalDocumentScore(Document document) {
        if (document == null) {
            return 0D;
        }
        Double score = numericMetadataValue(document.getMetadata().get(DocumentKnowledgeMetadataKeys.SCORE));
        if (score != null) {
            return score;
        }
        return document.getScore() == null ? 0D : document.getScore();
    }

    private RagRuntimeOptions runtimeOptions(ConversationExecutionPlan plan) {
        return RagRuntimeOptions.resolve(plan, properties);
    }

    private Integer integerMetadataValue(Object value) {
        Double number = numericMetadataValue(value);
        return number == null ? null : number.intValue();
    }

    private Long longMetadataValue(Object value) {
        Double number = numericMetadataValue(value);
        return number == null ? null : number.longValue();
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

    private String normalizedAnchor(Object value) {
        return normalizeSimple(safeText(value)
            .replaceAll("[\\s>`*#_\\-，,。；;：:（）()“”\"'\\[\\]{}]+", ""));
    }

    private String normalizeSimple(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .trim()
            .toLowerCase(Locale.ROOT);
    }

    private String safeText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
