package org.javaup.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentProfile;
import org.javaup.ai.manage.data.SuperAgentKnowledgeRouteTrace;
import org.javaup.ai.manage.data.SuperAgentKnowledgeScopeNode;
import org.javaup.ai.manage.data.SuperAgentKnowledgeTopicNode;
import org.javaup.ai.manage.data.SuperAgentTopicDocumentRelation;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentProfileMapper;
import org.javaup.ai.manage.mapper.SuperAgentKnowledgeRouteTraceMapper;
import org.javaup.ai.manage.mapper.SuperAgentKnowledgeScopeNodeMapper;
import org.javaup.ai.manage.mapper.SuperAgentKnowledgeTopicNodeMapper;
import org.javaup.ai.manage.mapper.SuperAgentTopicDocumentRelationMapper;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.ai.manage.model.route.DocumentRouteCandidate;
import org.javaup.ai.manage.model.route.KnowledgeRouteContext;
import org.javaup.ai.manage.model.route.KnowledgeRouteDecision;
import org.javaup.ai.manage.model.route.ScopeRouteCandidate;
import org.javaup.ai.manage.model.route.TopicRouteCandidate;
import org.javaup.ai.manage.service.KnowledgeRouteIndexService;
import org.javaup.ai.manage.service.KnowledgeRouteService;
import org.javaup.enums.BusinessStatus;
import org.javaup.enums.DocumentIndexStatusEnum;
import org.javaup.enums.KnowledgeBaseSelectionMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务实现层
 * @author: 阿星不是程序员
 **/
@Slf4j
@AllArgsConstructor
@Service
public class KnowledgeRouteServiceImpl implements KnowledgeRouteService {

    private static final int ROUTE_STATUS_SUCCESS = 1;
    private static final int ROUTE_STATUS_LOW_CONFIDENCE = 2;
    private static final int ROUTE_STATUS_FAILED = 3;
    private static final int ROUTE_EMBEDDING_BATCH_SIZE = 10;

    private final SuperAgentDocumentMapper documentMapper;
    private final SuperAgentDocumentProfileMapper documentProfileMapper;
    private final SuperAgentKnowledgeScopeNodeMapper scopeNodeMapper;
    private final SuperAgentKnowledgeTopicNodeMapper topicNodeMapper;
    private final SuperAgentTopicDocumentRelationMapper topicDocumentRelationMapper;
    private final SuperAgentKnowledgeRouteTraceMapper knowledgeRouteTraceMapper;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final ObjectProvider<KnowledgeRouteIndexService> knowledgeRouteIndexServiceProvider;
    private final UidGenerator uidGenerator;

    @Override
    public KnowledgeRouteDecision route(KnowledgeRouteContext context) {
        RouteQueryContext queryContext = buildQueryContext(context);
        KnowledgeRouteDecision decision = new KnowledgeRouteDecision();
        if (queryContext.queryTerms().isEmpty()) {
            decision.setRouteStatus("FAILED");
            decision.setReason("问题为空或无法提取有效关键词");
            return decision;
        }
        List<ScopeRouteCandidate> scopeCandidates = rankScopes(queryContext);
        List<TopicRouteCandidate> topicCandidates = rankTopics(queryContext, scopeCandidates);
        List<DocumentRouteCandidate> documentCandidates = rankDocuments(queryContext, scopeCandidates, topicCandidates);
        decision.setScopes(scopeCandidates);
        decision.setTopics(topicCandidates);
        decision.setDocuments(documentCandidates);
        BigDecimal confidence = resolveConfidence(documentCandidates);
        decision.setConfidence(confidence);
        if (documentCandidates.isEmpty()) {
            decision.setRouteStatus("FAILED");
        }
        else if (confidence.compareTo(BigDecimal.valueOf(0.55D)) < 0) {
            decision.setRouteStatus("LOW_CONFIDENCE");
        }
        else {
            decision.setRouteStatus("SUCCESS");
        }
        decision.setReason(documentCandidates.isEmpty()
            ? "没有找到可用候选文档"
            : resolveDecisionReason(documentCandidates, confidence));
        log.info("知识范围路由完成: question='{}', rewriteQuestion='{}', scopeCount={}, topicCount={}, documentCount={}, confidence={}, topDocument='{}'",
            StrUtil.blankToDefault(queryContext.originalQuestion(), ""),
            StrUtil.blankToDefault(queryContext.rewriteQuestion(), ""),
            scopeCandidates.size(),
            topicCandidates.size(),
            documentCandidates.size(),
            confidence,
            documentCandidates.isEmpty() ? "" : documentCandidates.get(0).getDocumentName());
        return decision;
    }

    @Override
    public void recordShadowRoute(String conversationId,
                                  long exchangeId,
                                  Long selectedDocumentId,
                                  KnowledgeRouteContext context) {
        try {
            KnowledgeRouteDecision decision = route(context);
            saveTrace(conversationId, exchangeId, selectedDocumentId, context, "shadow", decision);
        }
        catch (Exception exception) {
            log.warn("记录知识路由影子结果失败: conversationId={}, exchangeId={}", conversationId, exchangeId, exception);
        }
    }

    @Override
    public void recordAutoRoute(String conversationId,
                                long exchangeId,
                                KnowledgeRouteContext context,
                                KnowledgeRouteDecision decision) {
        try {
            Long selectedDocumentId = decision == null || decision.topDocument() == null || StrUtil.isBlank(decision.topDocument().getDocumentId())
                ? null
                : Long.valueOf(decision.topDocument().getDocumentId());
            saveTrace(conversationId, exchangeId, selectedDocumentId, context, "auto", decision);
        }
        catch (Exception exception) {
            log.warn("记录知识路由 AUTO 结果失败: conversationId={}, exchangeId={}", conversationId, exchangeId, exception);
        }
    }

    private void saveTrace(String conversationId,
                           long exchangeId,
                           Long selectedDocumentId,
                           KnowledgeRouteContext context,
                           String mode,
                           KnowledgeRouteDecision decision) {
        SuperAgentKnowledgeRouteTrace trace = new SuperAgentKnowledgeRouteTrace();
        trace.setId(uidGenerator.getUid());
        trace.setConversationId(conversationId);
        trace.setExchangeId(exchangeId);
        trace.setQuestion(context == null ? "" : context.getQuestion());
        trace.setRewriteQuestion(context == null ? "" : context.getRewriteQuestion());
        trace.setMode(mode);
        trace.setKnowledgeBaseSelectionMode(context == null || context.getKnowledgeBaseSelectionMode() == null ? KnowledgeBaseSelectionMode.NONE.name() : context.getKnowledgeBaseSelectionMode().name());
        trace.setSelectedKnowledgeBaseIdsJson(writeStringJson(context == null ? List.of() : context.getSelectedKnowledgeBaseIds().stream().map(String::valueOf).toList()));
        trace.setSelectedKnowledgeBaseNamesJson(writeStringJson(context == null ? List.of() : context.getSelectedKnowledgeBaseNames()));
        trace.setAllowedDocumentIdsJson(writeStringJson(context == null ? List.of() : context.getAllowedDocumentIds().stream().map(String::valueOf).toList()));
        trace.setTopScopesJson(writeScopeJson(decision == null ? List.of() : decision.getScopes()));
        trace.setTopTopicsJson(writeTopicJson(decision == null ? List.of() : decision.getTopics()));
        trace.setTopDocumentsJson(writeDocumentJson(decision == null ? List.of() : decision.getDocuments()));
        trace.setSelectedDocumentId(selectedDocumentId);
        trace.setHitSelectedDocument(resolveHitSelectedDocument(selectedDocumentId, decision));
        trace.setConfidence(decision == null ? BigDecimal.ZERO : decision.getConfidence());
        trace.setRouteStatus(resolveRouteStatus(decision));
        trace.setErrorMsg(decision == null ? "" : StrUtil.blankToDefault(decision.getReason(), ""));
        trace.setStatus(BusinessStatus.YES.getCode());
        knowledgeRouteTraceMapper.insert(trace);
    }

    private Integer resolveRouteStatus(KnowledgeRouteDecision decision) {
        if (decision == null) {
            return ROUTE_STATUS_FAILED;
        }
        return switch (StrUtil.blankToDefault(decision.getRouteStatus(), "FAILED")) {
            case "SUCCESS" -> ROUTE_STATUS_SUCCESS;
            case "LOW_CONFIDENCE" -> ROUTE_STATUS_LOW_CONFIDENCE;
            default -> ROUTE_STATUS_FAILED;
        };
    }

    private Integer resolveHitSelectedDocument(Long selectedDocumentId, KnowledgeRouteDecision decision) {
        if (selectedDocumentId == null || decision == null || decision.getDocuments() == null || decision.getDocuments().isEmpty()) {
            return null;
        }
        boolean hit = decision.getDocuments().stream()
            .limit(3)
            .anyMatch(item -> Objects.equals(String.valueOf(selectedDocumentId), item.getDocumentId()));
        return hit ? 1 : 0;
    }

    private List<ScopeRouteCandidate> rankScopes(RouteQueryContext queryContext) {
        LambdaQueryWrapper<SuperAgentKnowledgeScopeNode> wrapper = new LambdaQueryWrapper<SuperAgentKnowledgeScopeNode>()
            .eq(SuperAgentKnowledgeScopeNode::getStatus, BusinessStatus.YES.getCode());
        if (queryContext.selectedKnowledgeBaseIds() != null && !queryContext.selectedKnowledgeBaseIds().isEmpty()) {
            wrapper.in(SuperAgentKnowledgeScopeNode::getKnowledgeBaseId, queryContext.selectedKnowledgeBaseIds());
        }
        List<SuperAgentKnowledgeScopeNode> nodes = scopeNodeMapper.selectList(wrapper);
        if (nodes.isEmpty()) {
            return List.of();
        }
        List<String> routeTexts = nodes.stream()
            .map(node -> join(node.getScopeName(), node.getDescription(), node.getAliases(), node.getExamples()))
            .toList();
        List<Double> semanticScores = computeSemanticScores(queryContext, routeTexts);
        Map<Long, Double> lexicalScores = searchLexicalScores(queryContext, "scope", 5).stream()
            .filter(hit -> hit.entityId() != null)
            .collect(Collectors.toMap(KnowledgeRouteIndexService.RouteLexicalHit::entityId, KnowledgeRouteIndexService.RouteLexicalHit::score, (left, right) -> left));
        return buildScopeCandidates(queryContext, nodes, routeTexts, semanticScores, lexicalScores);
    }

    private List<TopicRouteCandidate> rankTopics(RouteQueryContext queryContext, List<ScopeRouteCandidate> scopeCandidates) {
        LambdaQueryWrapper<SuperAgentKnowledgeTopicNode> wrapper = new LambdaQueryWrapper<SuperAgentKnowledgeTopicNode>()
            .eq(SuperAgentKnowledgeTopicNode::getStatus, BusinessStatus.YES.getCode());
        if (queryContext.selectedKnowledgeBaseIds() != null && !queryContext.selectedKnowledgeBaseIds().isEmpty()) {
            wrapper.in(SuperAgentKnowledgeTopicNode::getKnowledgeBaseId, queryContext.selectedKnowledgeBaseIds());
        }
        List<SuperAgentKnowledgeTopicNode> nodes = topicNodeMapper.selectList(wrapper);
        Set<Long> preferredScopes = scopeCandidates.stream()
            .map(ScopeRouteCandidate::getScopeId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (nodes.isEmpty()) {
            return deriveTopicsFromProfiles(queryContext, preferredScopes);
        }
        List<String> routeTexts = nodes.stream()
            .map(node -> join(
                node.getTopicName(),
                node.getDescription(),
                node.getAliases(),
                node.getExamples(),
                node.getAnswerShape(),
                node.getExecutionPreference()
            ))
            .toList();
        List<Double> semanticScores = computeSemanticScores(queryContext, routeTexts);
        Map<Long, Double> lexicalScores = searchLexicalScores(queryContext, "topic", 8).stream()
            .filter(hit -> hit.entityId() != null)
            .collect(Collectors.toMap(KnowledgeRouteIndexService.RouteLexicalHit::entityId, KnowledgeRouteIndexService.RouteLexicalHit::score, (left, right) -> left));
        List<TopicRouteCandidate> candidates = new ArrayList<>(nodes.size());
        for (int index = 0; index < nodes.size(); index++) {
            SuperAgentKnowledgeTopicNode node = nodes.get(index);
            String routeText = routeTexts.get(index);
            double score = semanticMainScore(semanticScores.get(index))
                + lexicalAssist(lexicalScores.get(node.getId()))
                + keywordEntityAssist(queryContext.queryTerms(), routeText);
            if (!preferredScopes.isEmpty() && preferredScopes.contains(node.getScopeId())) {
                score += 8D;
            }
            if (score > 0D || queryContext.semanticEnabled()) {
                candidates.add(new TopicRouteCandidate(
                    node.getId(),
                    node.getTopicName(),
                    node.getScopeId(),
                    scoreToBigDecimal(score),
                    buildReason(queryContext.queryTerms(), routeText, semanticScores.get(index))
                ));
            }
        }
        return candidates.stream()
            .sorted((left, right) -> right.getScore().compareTo(left.getScore()))
            .limit(8)
            .toList();
    }

    private List<TopicRouteCandidate> deriveTopicsFromProfiles(RouteQueryContext queryContext, Set<Long> preferredScopes) {
        Map<String, TopicAccumulator> accumulatorMap = new LinkedHashMap<>();
        Map<Long, SuperAgentDocument> documentMap = listRetrievableDocuments(queryContext).stream()
            .collect(Collectors.toMap(SuperAgentDocument::getId, item -> item));
        if (documentMap.isEmpty()) {
            return List.of();
        }
        List<SuperAgentDocumentProfile> profiles = documentProfileMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentProfile>()
            .eq(SuperAgentDocumentProfile::getStatus, BusinessStatus.YES.getCode())
            .eq(SuperAgentDocumentProfile::getProfileStatus, 2)
            .in(SuperAgentDocumentProfile::getDocumentId, documentMap.keySet()));
        for (SuperAgentDocumentProfile profile : profiles) {
            for (String topic : parseJsonArray(profile.getCoreTopics())) {
                String routeText = join(topic, profile.getDocumentSummary(), profile.getExampleQuestions());
                double score = keywordEntityAssist(queryContext.queryTerms(), routeText);
                double semanticScore = semanticScore(queryContext, routeText);
                TopicAccumulator accumulator = accumulatorMap.computeIfAbsent(topic, TopicAccumulator::new);
                double finalScore = score + semanticMainScore(semanticScore);
                if (finalScore > accumulator.maxScore) {
                    accumulator.maxScore = finalScore;
                    accumulator.reason = buildReason(queryContext.queryTerms(), routeText, semanticScore);
                }
            }
        }
        return accumulatorMap.values().stream()
            .filter(item -> item.maxScore > 0D || queryContext.semanticEnabled())
            .map(item -> new TopicRouteCandidate(null, item.topicName, null, scoreToBigDecimal(item.maxScore), item.reason))
            .sorted((left, right) -> right.getScore().compareTo(left.getScore()))
            .limit(8)
            .toList();
    }

    private List<DocumentRouteCandidate> rankDocuments(RouteQueryContext queryContext,
                                                       List<ScopeRouteCandidate> scopeCandidates,
                                                       List<TopicRouteCandidate> topicCandidates) {
        List<SuperAgentDocument> documents = listRetrievableDocuments(queryContext);
        if (documents.isEmpty()) {
            return List.of();
        }
        Map<Long, SuperAgentDocumentProfile> profileMap = documentProfileMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentProfile>()
                .eq(SuperAgentDocumentProfile::getStatus, BusinessStatus.YES.getCode())
                .eq(SuperAgentDocumentProfile::getProfileStatus, 2))
            .stream()
            .collect(Collectors.toMap(SuperAgentDocumentProfile::getDocumentId, item -> item, (left, right) -> right));
        LambdaQueryWrapper<SuperAgentTopicDocumentRelation> relationWrapper = new LambdaQueryWrapper<SuperAgentTopicDocumentRelation>()
            .eq(SuperAgentTopicDocumentRelation::getStatus, BusinessStatus.YES.getCode());
        if (queryContext.selectedKnowledgeBaseIds() != null && !queryContext.selectedKnowledgeBaseIds().isEmpty()) {
            relationWrapper.in(SuperAgentTopicDocumentRelation::getKnowledgeBaseId, queryContext.selectedKnowledgeBaseIds());
        }
        if (queryContext.allowedDocumentIds() != null && !queryContext.allowedDocumentIds().isEmpty()) {
            relationWrapper.in(SuperAgentTopicDocumentRelation::getDocumentId, queryContext.allowedDocumentIds());
        }
        Map<Long, Map<Long, SuperAgentTopicDocumentRelation>> topicRelationMap = topicDocumentRelationMapper.selectList(relationWrapper)
            .stream()
            .filter(relation -> relation.getTopicId() != null)
            .collect(Collectors.groupingBy(SuperAgentTopicDocumentRelation::getTopicId,
                Collectors.toMap(SuperAgentTopicDocumentRelation::getDocumentId, item -> item, (left, right) -> right)));
        Long topTopicId = topicCandidates.isEmpty() ? null : topicCandidates.get(0).getTopicId();
        List<DocumentRouteMaterial> materials = documents.stream()
            .map(document -> buildDocumentRouteMaterial(document, profileMap.get(document.getId())))
            .toList();
        List<Double> semanticScores = computeSemanticScores(queryContext, materials.stream().map(DocumentRouteMaterial::routeText).toList());
        Map<Long, Double> lexicalScores = searchLexicalScores(queryContext, "document", 5).stream()
            .filter(hit -> hit.documentId() != null)
            .collect(Collectors.toMap(KnowledgeRouteIndexService.RouteLexicalHit::documentId, KnowledgeRouteIndexService.RouteLexicalHit::score, (left, right) -> left));
        return documents.stream()
            .map(document -> buildDocumentCandidate(
                queryContext,
                document,
                profileMap.get(document.getId()),
                topTopicId,
                topicRelationMap,
                materials,
                semanticScores,
                lexicalScores
            ))
            .filter(candidate -> candidate.getScore().compareTo(BigDecimal.ZERO) > 0 || queryContext.semanticEnabled())
            .sorted((left, right) -> right.getScore().compareTo(left.getScore()))
            .limit(5)
            .toList();
    }

    private DocumentRouteCandidate buildDocumentCandidate(RouteQueryContext queryContext,
                                                          SuperAgentDocument document,
                                                          SuperAgentDocumentProfile profile,
                                                          Long topTopicId,
                                                          Map<Long, Map<Long, SuperAgentTopicDocumentRelation>> topicRelationMap,
                                                          List<DocumentRouteMaterial> materials,
                                                          List<Double> semanticScores,
                                                          Map<Long, Double> lexicalScores) {
        int materialIndex = findMaterialIndex(materials, document.getId());
        String routeText = materialIndex >= 0 ? materials.get(materialIndex).routeText() : document.getDocumentName();
        double semanticScore = materialIndex >= 0 && materialIndex < semanticScores.size() ? semanticScores.get(materialIndex) : 0D;
        double score = semanticMainScore(semanticScore)
            + lexicalAssist(lexicalScores.get(document.getId()))
            + keywordEntityAssist(queryContext.queryTerms(), routeText);
        if (topTopicId != null) {
            Map<Long, SuperAgentTopicDocumentRelation> relationMap = topicRelationMap.get(topTopicId);
            if (relationMap != null) {
                SuperAgentTopicDocumentRelation relation = relationMap.get(document.getId());
                if (relation != null && relation.getRelationScore() != null) {
                    score += relation.getRelationScore().doubleValue() * 20D;
                }
            }
        }
        if (score <= 0D && !queryContext.semanticEnabled()) {
            return new DocumentRouteCandidate(
                String.valueOf(document.getId()),
                document.getDocumentName(),
                document.getLastIndexTaskId() == null ? "" : String.valueOf(document.getLastIndexTaskId()),
                BigDecimal.ZERO,
                "未命中路由关键词"
            );
        }
        return new DocumentRouteCandidate(
            String.valueOf(document.getId()),
            document.getDocumentName(),
            document.getLastIndexTaskId() == null ? "" : String.valueOf(document.getLastIndexTaskId()),
            scoreToBigDecimal(score),
            buildReason(queryContext.queryTerms(), routeText, semanticScore)
        );
    }

    private RouteQueryContext buildQueryContext(KnowledgeRouteContext context) {
        String question = context == null ? "" : context.getQuestion();
        String rewriteQuestion = context == null ? "" : context.getRewriteQuestion();
        String routingText = buildRoutingText(question, rewriteQuestion);
        List<String> queryTerms = tokenize(routingText);
        float[] queryEmbedding = embedSingle(routingText);
        List<Long> selectedKnowledgeBaseIds = context == null || context.getSelectedKnowledgeBaseIds() == null
            ? List.of()
            : context.getSelectedKnowledgeBaseIds().stream().filter(Objects::nonNull).distinct().toList();
        List<Long> allowedDocumentIds = context == null || context.getAllowedDocumentIds() == null
            ? List.of()
            : context.getAllowedDocumentIds().stream().filter(Objects::nonNull).distinct().toList();
        List<KnowledgeDocumentDescriptor> allowedDocuments = context == null || context.getAllowedDocuments() == null
            ? List.of()
            : context.getAllowedDocuments();
        return new RouteQueryContext(
            StrUtil.blankToDefault(question, ""),
            StrUtil.blankToDefault(rewriteQuestion, ""),
            routingText,
            queryTerms,
            queryEmbedding,
            selectedKnowledgeBaseIds,
            allowedDocumentIds,
            allowedDocuments
        );
    }

    private String buildRoutingText(String question, String rewriteQuestion) {
        String original = StrUtil.blankToDefault(question, "").trim();
        String rewritten = StrUtil.blankToDefault(rewriteQuestion, "").trim();
        if (StrUtil.isBlank(original)) {
            return rewritten;
        }
        if (StrUtil.isBlank(rewritten) || Objects.equals(original, rewritten)) {
            return original;
        }
        return original + " " + rewritten;
    }

    private List<ScopeRouteCandidate> buildScopeCandidates(RouteQueryContext queryContext,
                                                           List<SuperAgentKnowledgeScopeNode> nodes,
                                                           List<String> routeTexts,
                                                           List<Double> semanticScores,
                                                           Map<Long, Double> lexicalScores) {
        List<ScopeRouteCandidate> candidates = new ArrayList<>(nodes.size());
        for (int index = 0; index < nodes.size(); index++) {
            SuperAgentKnowledgeScopeNode node = nodes.get(index);
            String routeText = routeTexts.get(index);
            double finalScore = semanticMainScore(semanticScores.get(index))
                + lexicalAssist(lexicalScores.get(node.getId()))
                + keywordEntityAssist(queryContext.queryTerms(), routeText);
            if (finalScore > 0D || semanticScores.get(index) > 0D) {
                candidates.add(new ScopeRouteCandidate(
                    node.getId(),
                    node.getScopeName(),
                    scoreToBigDecimal(finalScore),
                    buildReason(queryContext.queryTerms(), routeText, semanticScores.get(index))
                ));
            }
        }
        return candidates.stream()
            .sorted((left, right) -> right.getScore().compareTo(left.getScore()))
            .limit(5)
            .toList();
    }

    private DocumentRouteMaterial buildDocumentRouteMaterial(SuperAgentDocument document, SuperAgentDocumentProfile profile) {
        return new DocumentRouteMaterial(
            document.getId(),
            join(
                document.getDocumentName(),
                profile == null ? "" : profile.getDocumentSummary(),
                profile == null ? "" : profile.getCoreTopics(),
                profile == null ? "" : profile.getExampleQuestions(),
                profile == null ? "" : profile.getDocumentType()
            )
        );
    }

    private int findMaterialIndex(List<DocumentRouteMaterial> materials, Long documentId) {
        for (int index = 0; index < materials.size(); index++) {
            if (Objects.equals(materials.get(index).documentId(), documentId)) {
                return index;
            }
        }
        return -1;
    }

    private BigDecimal resolveConfidence(List<DocumentRouteCandidate> documents) {
        if (documents == null || documents.isEmpty()) {
            return BigDecimal.ZERO;
        }
        double top = documents.get(0).getScore().doubleValue();
        double second = documents.size() > 1 ? documents.get(1).getScore().doubleValue() : 0D;
        double normalized = top / Math.max(10D, top + second + 5D);
        return scoreToBigDecimal(normalized);
    }

    private List<SuperAgentDocument> listRetrievableDocuments() {
        return listRetrievableDocuments(null);
    }

    private List<SuperAgentDocument> listRetrievableDocuments(RouteQueryContext queryContext) {
        LambdaQueryWrapper<SuperAgentDocument> wrapper = new LambdaQueryWrapper<SuperAgentDocument>()
            .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode())
            .eq(SuperAgentDocument::getIndexStatus, DocumentIndexStatusEnum.BUILD_SUCCESS.getCode())
            .isNotNull(SuperAgentDocument::getLastIndexTaskId)
            .orderByAsc(SuperAgentDocument::getId);
        if (queryContext != null && queryContext.allowedDocumentIds() != null && !queryContext.allowedDocumentIds().isEmpty()) {
            wrapper.in(SuperAgentDocument::getId, queryContext.allowedDocumentIds());
        }
        else if (queryContext != null && queryContext.selectedKnowledgeBaseIds() != null && !queryContext.selectedKnowledgeBaseIds().isEmpty()) {
            wrapper.in(SuperAgentDocument::getKnowledgeBaseId, queryContext.selectedKnowledgeBaseIds());
        }
        return documentMapper.selectList(wrapper);
    }

    private double lexicalScore(List<String> queryTerms, String content) {
        String normalizedContent = normalize(content);
        if (queryTerms.isEmpty() || normalizedContent.isBlank()) {
            return 0D;
        }
        double score = 0D;
        List<String> sortedTerms = queryTerms.stream()
            .map(this::normalize)
            .filter(StrUtil::isNotBlank)
            .distinct()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();
        List<String> matchedTerms = new ArrayList<>();
        for (String term : sortedTerms) {
            String normalizedTerm = normalize(term);
            if (normalizedTerm.length() < 2) {
                continue;
            }
            boolean coveredByLongerTerm = matchedTerms.stream().anyMatch(existing -> existing.contains(normalizedTerm));
            if (coveredByLongerTerm) {
                continue;
            }
            if (normalizedContent.contains(normalizedTerm)) {
                matchedTerms.add(normalizedTerm);
                score += lexicalWeight(normalizedTerm.length());
            }
        }
        return score;
    }

    private List<KnowledgeRouteIndexService.RouteLexicalHit> searchLexicalScores(RouteQueryContext queryContext, String entityType, int size) {
        KnowledgeRouteIndexService routeIndexService = knowledgeRouteIndexServiceProvider.getIfAvailable();
        if (routeIndexService == null) {
            return List.of();
        }
        List<KnowledgeRouteIndexService.RouteLexicalHit> hits = routeIndexService.search(
            queryContext.routingText(),
            entityType,
            size,
            queryContext.selectedKnowledgeBaseIds()
        );
        if (hits == null || hits.isEmpty()) {
            return hits == null ? List.of() : hits;
        }
        if (queryContext.selectedKnowledgeBaseIds() != null && !queryContext.selectedKnowledgeBaseIds().isEmpty()) {
            hits = hits.stream()
                .filter(hit -> hit.knowledgeBaseId() == null || queryContext.selectedKnowledgeBaseIds().contains(hit.knowledgeBaseId()))
                .toList();
        }
        if (queryContext.allowedDocumentIds() == null || queryContext.allowedDocumentIds().isEmpty()) {
            return hits == null ? List.of() : hits;
        }
        return hits.stream()
            .filter(hit -> hit.documentId() == null || queryContext.allowedDocumentIds().contains(hit.documentId()))
            .toList();
    }

    private List<String> tokenize(String text) {
        String normalized = StrUtil.blankToDefault(text, "").trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String segment : normalized.split("[\\s、，,；;：:（）()\\-的和及与或]+")) {
            String trimmed = segment.trim();
            if (trimmed.length() >= 2) {
                terms.add(trimmed);
                expandChineseNgrams(terms, trimmed);
            }
        }
        return new ArrayList<>(terms).stream().limit(40).toList();
    }

    private List<String> parseJsonArray(String raw) {
        String normalized = StrUtil.blankToDefault(raw, "").trim();
        if (normalized.isBlank() || "[]".equals(normalized)) {
            return List.of();
        }
        String body = normalized.replace("[", "").replace("]", "");
        if (body.isBlank()) {
            return List.of();
        }
        return List.of(body.split(",")).stream()
            .map(item -> item.replace("\"", "").trim())
            .filter(StrUtil::isNotBlank)
            .toList();
    }

    private BigDecimal scoreToBigDecimal(double score) {
        return BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP);
    }

    private String buildReason(List<String> queryTerms, String content, double semanticScore) {
        List<String> matched = queryTerms.stream()
            .filter(term -> normalize(content).contains(normalize(term)))
            .limit(3)
            .toList();
        if (!matched.isEmpty()) {
            return "命中关键词：" + String.join("、", matched);
        }
        if (semanticScore >= 0.55D) {
            return "语义相似度高，基于文档画像与元数据召回";
        }
        if (semanticScore >= 0.35D) {
            return "语义相近，采用保守扩范围召回";
        }
        return "基于文档画像与元数据综合召回";
    }

    private String join(String... values) {
        return Arrays.stream(values)
            .filter(StrUtil::isNotBlank)
            .collect(Collectors.joining(" "));
    }

    private String normalize(String value) {
        return StrUtil.blankToDefault(value, "")
            .replaceAll("[\\s>`*#_\\-，,。；;：:（）()“”\"'\\[\\]]+", "")
            .toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String primary, String fallback) {
        if (StrUtil.isNotBlank(primary)) {
            return primary.trim();
        }
        return StrUtil.blankToDefault(fallback, "");
    }

    private float[] embedSingle(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            return null;
        }
        try {
            return embeddingModel.embed(text.trim());
        }
        catch (Exception exception) {
            log.warn("知识路由生成问题向量失败，退回词面匹配: text='{}'", StrUtil.maxLength(text, 120), exception);
            return null;
        }
    }

    private List<Double> computeSemanticScores(RouteQueryContext queryContext, List<String> routeTexts) {
        if (!queryContext.semanticEnabled() || routeTexts == null || routeTexts.isEmpty()) {
            return routeTexts == null ? List.of() : routeTexts.stream().map(item -> 0D).toList();
        }
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            return routeTexts.stream().map(item -> 0D).toList();
        }
        try {
            List<String> normalizedRouteTexts = routeTexts.stream()
                .map(item -> StrUtil.blankToDefault(item, ""))
                .toList();
            List<Double> scores = new ArrayList<>(normalizedRouteTexts.size());
            for (int index = 0; index < normalizedRouteTexts.size(); index++) {
                scores.add(0D);
            }
            int totalBatchCount = (normalizedRouteTexts.size() + ROUTE_EMBEDDING_BATCH_SIZE - 1) / ROUTE_EMBEDDING_BATCH_SIZE;
            for (int startIndex = 0; startIndex < normalizedRouteTexts.size(); startIndex += ROUTE_EMBEDDING_BATCH_SIZE) {
                int endIndex = Math.min(startIndex + ROUTE_EMBEDDING_BATCH_SIZE, normalizedRouteTexts.size());
                List<String> currentBatch = normalizedRouteTexts.subList(startIndex, endIndex);
                int currentBatchIndex = (startIndex / ROUTE_EMBEDDING_BATCH_SIZE) + 1;
                log.debug("知识路由候选向量分批计算: batchIndex={}/{}, candidateRange=[{}, {}], batchSize={}",
                    currentBatchIndex, totalBatchCount, startIndex + 1, endIndex, currentBatch.size());
                List<float[]> embeddings = embeddingModel.embed(currentBatch);
                if (embeddings == null || embeddings.size() != currentBatch.size()) {
                    return routeTexts.stream().map(item -> 0D).toList();
                }
                for (int batchIndex = 0; batchIndex < embeddings.size(); batchIndex++) {
                    scores.set(startIndex + batchIndex, cosineSimilarity(queryContext.queryEmbedding(), embeddings.get(batchIndex)));
                }
            }
            return scores;
        }
        catch (Exception exception) {
            log.warn("知识路由生成候选向量失败，退回词面匹配: candidateCount={}", routeTexts.size(), exception);
            return routeTexts.stream().map(item -> 0D).toList();
        }
    }

    private double semanticScore(RouteQueryContext queryContext, String routeText) {
        if (!queryContext.semanticEnabled()) {
            return 0D;
        }
        float[] routeEmbedding = embedSingle(routeText);
        if (routeEmbedding == null) {
            return 0D;
        }
        return cosineSimilarity(queryContext.queryEmbedding(), routeEmbedding);
    }

    private double semanticMainScore(double semanticScore) {
        if (semanticScore <= 0.20D) {
            return 0D;
        }
        return (semanticScore - 0.20D) * 50D;
    }

    private double lexicalAssist(Double lexicalScore) {
        if (lexicalScore == null || lexicalScore <= 0D) {
            return 0D;
        }
        return Math.min(10D, lexicalScore * 1.6D);
    }

    private double keywordEntityAssist(List<String> queryTerms, String routeText) {
        if (queryTerms == null || queryTerms.isEmpty()) {
            return 0D;
        }
        double score = 0D;
        String normalizedContent = normalize(routeText);
        for (String term : queryTerms) {
            if (!looksLikeEntityTerm(term)) {
                continue;
            }
            String normalizedTerm = normalize(term);
            if (StrUtil.isBlank(normalizedTerm) || normalizedTerm.length() < 2) {
                continue;
            }
            if (normalizedContent.contains(normalizedTerm)) {
                score += 6D;
            }
        }
        return score;
    }

    private boolean looksLikeEntityTerm(String term) {
        if (StrUtil.isBlank(term)) {
            return false;
        }
        String trimmed = term.trim();
        return trimmed.matches(".*[A-Za-z].*")
            || trimmed.matches(".*\\d.*")
            || trimmed.length() <= 4;
    }

    private double cosineSimilarity(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || right.length == 0 || left.length != right.length) {
            return 0D;
        }
        double dot = 0D;
        double leftNorm = 0D;
        double rightNorm = 0D;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        if (leftNorm <= 0D || rightNorm <= 0D) {
            return 0D;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private double lexicalWeight(int termLength) {
        if (termLength >= 8) {
            return 12D;
        }
        if (termLength >= 5) {
            return 8D;
        }
        if (termLength >= 3) {
            return 4D;
        }
        return 2D;
    }

    private void expandChineseNgrams(Set<String> terms, String segment) {
        String normalized = segment.trim();
        if (normalized.length() < 4) {
            return;
        }
        int maxGramLength = Math.min(6, normalized.length());
        for (int gramLength = 2; gramLength <= maxGramLength; gramLength++) {
            for (int start = 0; start + gramLength <= normalized.length(); start++) {
                String gram = normalized.substring(start, start + gramLength);
                if (gram.length() >= 2) {
                    terms.add(gram);
                }
            }
        }
    }

    private String resolveDecisionReason(List<DocumentRouteCandidate> documentCandidates, BigDecimal confidence) {
        if (documentCandidates == null || documentCandidates.isEmpty()) {
            return "没有找到可用候选文档";
        }
        String topReason = StrUtil.blankToDefault(documentCandidates.get(0).getReason(), "");
        if (confidence == null) {
            return topReason;
        }
        if (confidence.compareTo(BigDecimal.valueOf(0.55D)) < 0) {
            return StrUtil.blankToDefault(topReason, "低置信度，已进入保守扩范围候选");
        }
        return topReason;
    }

    private String writeScopeJson(List<ScopeRouteCandidate> candidates) {
        return candidates == null || candidates.isEmpty() ? "[]" : candidates.stream()
            .map(item -> "{\"scopeId\":\"" + nullToEmpty(item.getScopeId()) + "\",\"scopeName\":\"" + escapeJson(item.getScopeName()) + "\",\"score\":\"" + item.getScore() + "\",\"reason\":\"" + escapeJson(item.getReason()) + "\"}")
            .collect(Collectors.joining(",", "[", "]"));
    }

    private String writeTopicJson(List<TopicRouteCandidate> candidates) {
        return candidates == null || candidates.isEmpty() ? "[]" : candidates.stream()
            .map(item -> "{\"topicId\":\"" + nullToEmpty(item.getTopicId()) + "\",\"topicName\":\"" + escapeJson(item.getTopicName()) + "\",\"scopeId\":\"" + nullToEmpty(item.getScopeId()) + "\",\"score\":\"" + item.getScore() + "\",\"reason\":\"" + escapeJson(item.getReason()) + "\"}")
            .collect(Collectors.joining(",", "[", "]"));
    }

    private String writeDocumentJson(List<DocumentRouteCandidate> candidates) {
        return candidates == null || candidates.isEmpty() ? "[]" : candidates.stream()
            .map(item -> "{\"documentId\":\"" + item.getDocumentId() + "\",\"documentName\":\"" + escapeJson(item.getDocumentName()) + "\",\"lastIndexTaskId\":\"" + item.getLastIndexTaskId() + "\",\"score\":\"" + item.getScore() + "\",\"reason\":\"" + escapeJson(item.getReason()) + "\"}")
            .collect(Collectors.joining(",", "[", "]"));
    }

    private String writeStringJson(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream()
            .filter(Objects::nonNull)
            .map(value -> "\"" + escapeJson(value) + "\"")
            .collect(Collectors.joining(",", "[", "]"));
    }

    private String escapeJson(String text) {
        return StrUtil.blankToDefault(text, "").replace("\"", "\\\"");
    }

    private static final class TopicAccumulator {
        private final String topicName;
        private double maxScore;
        private String reason = "";

        private TopicAccumulator(String topicName) {
            this.topicName = topicName;
        }
    }

    private String nullToEmpty(Long value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record RouteQueryContext(String originalQuestion,
                                     String rewriteQuestion,
                                     String routingText,
                                     List<String> queryTerms,
                                     float[] queryEmbedding,
                                     List<Long> selectedKnowledgeBaseIds,
                                     List<Long> allowedDocumentIds,
                                     List<KnowledgeDocumentDescriptor> allowedDocuments) {
        private boolean semanticEnabled() {
            return queryEmbedding != null && queryEmbedding.length > 0;
        }
    }

    private record DocumentRouteMaterial(Long documentId, String routeText) {
    }
}
