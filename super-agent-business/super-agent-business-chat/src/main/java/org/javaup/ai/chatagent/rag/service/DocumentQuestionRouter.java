package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.rag.model.ConversationItemAnchor;
import org.javaup.ai.chatagent.rag.model.ConversationStructureAnchor;
import org.javaup.ai.chatagent.rag.model.DocumentNavigationAction;
import org.javaup.ai.chatagent.rag.model.DocumentNavigationDecision;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.model.QueryType;
import org.javaup.ai.chatagent.rag.model.QueryUnderstandingResult;
import org.javaup.ai.chatagent.rag.model.RagRewriteResult;
import org.javaup.ai.chatagent.rag.model.RetrievalQuestionPlan;
import org.javaup.ai.chatagent.rag.model.RetrievalIntent;
import org.javaup.ai.chatagent.rag.model.StructureNavigationIntent;
import org.javaup.ai.chatagent.rag.model.StructureNavigationOperation;
import org.javaup.ai.manage.model.graph.GraphSection;
import org.javaup.ai.manage.service.DocumentNavigationIndexService;
import org.javaup.ai.manage.service.DocumentStructureGraphService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务层
 * @author: 阿星不是程序员
 **/

@Slf4j
@Service
public class DocumentQuestionRouter {

    // 匹配 1.2 / 3.4.5 这类章节编号，用于识别用户给出的结构锚点。
    private static final Pattern SECTION_CODE_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)+)");
    // 匹配“第 3 章 / 第三节 / 第 4 小节”，补齐非小数编号的章节锚点表达。
    private static final Pattern CHINESE_SECTION_REFERENCE_PATTERN = Pattern.compile("第\\s*([0-9一二三四五六七八九十百]+)\\s*(章|节|小节)");
    // 匹配“第几步”，该类问题应交给 GRAPH_THEN_EVIDENCE，而不是 GRAPH_ONLY。
    private static final Pattern STEP_REFERENCE_PATTERN = Pattern.compile("第\\s*([0-9一二三四五六七八九十百]+)\\s*步");
    // 匹配“第几条/点/项/个”，用于保留原有编号项定位能力。
    private static final Pattern ORDINAL_REFERENCE_PATTERN = Pattern.compile("第\\s*([0-9一二三四五六七八九十百]+)\\s*(条|点|项|个)");
    // 匹配用户用引号包住的标题短语，例如“上线观察”，用于结构节点定位。
    private static final Pattern QUOTED_TEXT_PATTERN = Pattern.compile("[“\"']([^”\"']{2,40})[”\"']");

    private final DocumentStructureGraphService graphService;
    private final ObjectProvider<DocumentNavigationIndexService> navigationIndexServiceProvider;
    private final QueryUnderstandingService queryUnderstandingService;

    @Autowired
    public DocumentQuestionRouter(DocumentStructureGraphService graphService,
                                  ObjectProvider<DocumentNavigationIndexService> navigationIndexServiceProvider,
                                  ObjectProvider<QueryUnderstandingService> queryUnderstandingServiceProvider) {
        this.graphService = graphService;
        this.navigationIndexServiceProvider = navigationIndexServiceProvider;
        this.queryUnderstandingService = queryUnderstandingServiceProvider == null ? null : queryUnderstandingServiceProvider.getIfAvailable();
    }

    public DocumentNavigationDecision route(Long documentId,
                                            String originalQuestion,
                                            RagRewriteResult rewriteResult) {
        return route(documentId, originalQuestion, rewriteResult, "", "");
    }

    public DocumentNavigationDecision route(Long documentId,
                                            String originalQuestion,
                                            RagRewriteResult rewriteResult,
                                            String historySummary,
                                            String answerRecentTranscript) {
        String rewrittenQuestion = firstNonBlank(
            rewriteResult == null ? "" : rewriteResult.getRewrittenQuestion(),
            originalQuestion
        );
        List<String> subQuestions = normalizeSubQuestions(rewriteResult, rewrittenQuestion);
        RetrievalQuestionPlan retrievalPlan = new RetrievalQuestionPlan(rewrittenQuestion, subQuestions);
        String routeText = (safeText(originalQuestion) + " " + rewrittenQuestion).trim();
        QueryUnderstandingResult queryUnderstanding = understandQuery(originalQuestion, rewrittenQuestion, subQuestions, historySummary, answerRecentTranscript);
        DocumentQuestionIntentDecision questionIntent = detectQuestionIntent(
            routeText,
            originalQuestion,
            rewrittenQuestion,
            subQuestions,
            queryUnderstanding
        );
        RetrievalIntent retrievalIntent = detectRetrievalIntent(questionIntent, queryUnderstanding);
        GraphOnlyIntentDecision graphOnlyIntent = questionIntent.graphOnlyIntent();
        boolean analyticQuestion = questionIntent.analytic();

        DocumentNavigationAction structureNavigationAction = resolveStructureNavigationAction(queryUnderstanding);
        if (structureNavigationAction != null && subQuestions.size() <= 1) {
            GraphSection section = resolveSection(documentId, originalQuestion, rewrittenQuestion);
            return buildDecision(
                ExecutionMode.RETRIEVAL,
                structureNavigationAction,
                section,
                null,
                retrievalPlan,
                queryUnderstanding,
                RetrievalIntent.STRUCTURE,
                "高置信结构导航走结构树确定性查询，结构结果作为检索上下文和观测信号。"
            );
        }
        
        boolean singleQuestionGraphOnlyMatched = graphOnlyIntent.matched() && subQuestions.size() <= 1;
        if (singleQuestionGraphOnlyMatched) {
            GraphSection section = resolveSection(documentId, originalQuestion, rewrittenQuestion);
            return buildDecision(
                ExecutionMode.GRAPH_ONLY,
                graphOnlyIntent.action(),
                section,
                null,
                retrievalPlan,
                queryUnderstanding,
                retrievalIntent,
                graphOnlyIntent.reason()
            );
        }

        Integer itemIndex = resolveExplicitItemIndex(routeText);
        boolean itemLookupMatched = itemIndex != null || questionIntent.itemLookup();
        boolean shouldUseGraphThenEvidence = itemLookupMatched
            && shouldUseGraphThenEvidence(routeText, itemIndex, queryUnderstanding);
        if (shouldUseGraphThenEvidence) {
            GraphSection section = resolveSection(documentId, originalQuestion, rewrittenQuestion);
            return buildDecision(
                ExecutionMode.GRAPH_THEN_EVIDENCE,
                DocumentNavigationAction.ITEM_REFERENCE,
                section,
                itemIndex,
                retrievalPlan,
                queryUnderstanding,
                retrievalIntent,
                "高置信结构导航编号项问题走图定位取证"
            );
        }

        GraphSection assistedSection = null;
        boolean needsStructureAssistedRetrieval = analyticQuestion
            || questionIntent.outline()
            || itemIndex != null
            || questionIntent.structureHint();
        if (needsStructureAssistedRetrieval) {
            assistedSection = resolveSection(documentId, originalQuestion, rewrittenQuestion);
        }
        return buildDecision(
            ExecutionMode.RETRIEVAL,
            itemIndex != null ? DocumentNavigationAction.ITEM_REFERENCE : DocumentNavigationAction.FRESH_TOPIC,
            assistedSection,
            itemIndex,
            retrievalPlan,
            queryUnderstanding,
            retrievalIntent,
            assistedSection == null
                ? "普通文档问题走混合检索"
                : "结构线索仅作为软提示辅助混合检索"
        );
    }

    private DocumentNavigationDecision buildDecision(ExecutionMode mode,
                                                     DocumentNavigationAction action,
                                                     GraphSection section,
                                                     Integer itemIndex,
                                                     RetrievalQuestionPlan retrievalPlan,
                                                     QueryUnderstandingResult queryUnderstanding,
                                                     RetrievalIntent retrievalIntent,
                                                     String reason) {
        ConversationStructureAnchor structureAnchor = section == null
            ? ConversationStructureAnchor.builder().scopeMode(mode == ExecutionMode.RETRIEVAL ? "NONE" : "GRAPH_UNRESOLVED").build()
            : ConversationStructureAnchor.builder()
                .rootSectionCode(section.getNodeCode())
                .rootSectionTitle(section.getTitle())
                .targetSectionHint(section.displayTitle())
                .structureNodeId(section.getNodeId())
                .canonicalPath(section.getCanonicalPath())
                .scopeMode(mode == ExecutionMode.RETRIEVAL ? "SOFT" : "GRAPH")
                .build();
        ConversationItemAnchor itemAnchor = itemIndex == null
            ? null
            : ConversationItemAnchor.builder().itemIndex(itemIndex).build();
        List<String> queryHints = buildQueryHints(retrievalPlan, queryUnderstanding, section, itemIndex);
        String summaryText = "mode=" + mode.name()
            + "; retrievalIntent=" + (retrievalIntent == null ? RetrievalIntent.GENERAL.name() : retrievalIntent.name())
            + "; queryType=" + (queryUnderstanding == null || queryUnderstanding.getQueryType() == null ? "" : queryUnderstanding.getQueryType().name())
            + "; queryUnderstandingSource=" + (queryUnderstanding == null ? "" : StrUtil.blankToDefault(queryUnderstanding.getSource(), ""))
            + "; reason=" + reason
            + "; section=" + (section == null ? "" : section.displayTitle())
            + "; itemIndex=" + (itemIndex == null ? "" : itemIndex);
        log.info("文档问答路由完成: mode={}, action={}, section='{}', itemIndex={}, reason='{}'",
            mode,
            action,
            section == null ? "" : section.displayTitle(),
            itemIndex,
            reason);
        return DocumentNavigationDecision.builder()
            .navigationAction(action)
            .executionMode(mode)
            .structureAnchor(structureAnchor)
            .itemAnchor(itemAnchor)
            .retrievalPlan(retrievalPlan)
            .queryUnderstanding(queryUnderstanding)
            .retrievalIntent(retrievalIntent == null ? RetrievalIntent.GENERAL : retrievalIntent)
            .summaryText(summaryText)
            .queryContextHints(queryHints)
            .softSectionHints(section == null ? List.of() : List.of(section.displayTitle()))
            .build();
    }

    private QueryUnderstandingResult understandQuery(String originalQuestion,
                                                     String rewrittenQuestion,
                                                     List<String> subQuestions,
                                                     String historySummary,
                                                     String answerRecentTranscript) {
        if (queryUnderstandingService == null) {
            return null;
        }
        return queryUnderstandingService.understand(originalQuestion, rewrittenQuestion, subQuestions, historySummary, answerRecentTranscript);
    }

    private RetrievalIntent detectRetrievalIntent(DocumentQuestionIntentDecision questionIntent,
                                                  QueryUnderstandingResult queryUnderstanding) {
        if (questionIntent != null && questionIntent.graphOnlyIntent() != null && questionIntent.graphOnlyIntent().matched()) {
            return RetrievalIntent.STRUCTURE;
        }
        if (questionIntent != null && questionIntent.outline()) {
            return RetrievalIntent.STRUCTURE;
        }
        return primaryRetrievalIntent(queryUnderstanding);
    }

    private DocumentNavigationAction resolveStructureNavigationAction(QueryUnderstandingResult queryUnderstanding) {
        if (queryUnderstanding == null || queryUnderstanding.getQueryType() != QueryType.STRUCTURE_NAVIGATION) {
            return null;
        }
        if (confidence(queryUnderstanding) < 0.65D) {
            return null;
        }
        StructureNavigationIntent intent = queryUnderstanding.getStructureNavigationIntent();
        if (intent == null || intent.getOperations() == null || intent.getOperations().isEmpty()) {
            return null;
        }
        List<StructureNavigationOperation> operations = intent.getOperations();
        if (operations.contains(StructureNavigationOperation.SECTION_WITH_CHILDREN)
            || operations.contains(StructureNavigationOperation.DIRECT_CHILDREN)) {
            return DocumentNavigationAction.CHILD_SECTION_DESCEND;
        }
        if (operations.contains(StructureNavigationOperation.SECTION_WITH_SIBLINGS)
            || operations.contains(StructureNavigationOperation.PREVIOUS_SIBLING)
            || operations.contains(StructureNavigationOperation.NEXT_SIBLING)) {
            return DocumentNavigationAction.SECTION_ADJACENCY_LOOKUP;
        }
        if (operations.contains(StructureNavigationOperation.PARENT_SECTION)) {
            return DocumentNavigationAction.ANCESTOR_SECTION_RETURN;
        }
        if (operations.contains(StructureNavigationOperation.CURRENT_SECTION)) {
            return DocumentNavigationAction.FRESH_TOPIC;
        }
        return null;
    }

    private RetrievalIntent primaryRetrievalIntent(QueryUnderstandingResult queryUnderstanding) {
        if (queryUnderstanding == null) {
            return RetrievalIntent.GENERAL;
        }
        QueryType queryType = queryUnderstanding.getQueryType() == null ? QueryType.DOCUMENT_QA : queryUnderstanding.getQueryType();
        if (queryType == QueryType.STRUCTURE_NAVIGATION) {
            return RetrievalIntent.STRUCTURE;
        }
        if (queryType == QueryType.TABLE_QUERY) {
            return RetrievalIntent.TABLE;
        }
        if (queryType == QueryType.GRAPH_RELATION) {
            return RetrievalIntent.GRAPH_RAG;
        }
        if (queryType == QueryType.GLOBAL_SUMMARY) {
            return RetrievalIntent.RAPTOR;
        }
        List<RetrievalIntent> channels = queryUnderstanding.getChannels() == null ? List.of() : queryUnderstanding.getChannels();
        if (channels.contains(RetrievalIntent.TABLE)) {
            return RetrievalIntent.TABLE;
        }
        if (channels.contains(RetrievalIntent.GRAPH_RAG)) {
            return RetrievalIntent.GRAPH_RAG;
        }
        if (channels.contains(RetrievalIntent.RAPTOR)) {
            return RetrievalIntent.RAPTOR;
        }
        if (channels.contains(RetrievalIntent.STRUCTURE)) {
            return RetrievalIntent.STRUCTURE;
        }
        return RetrievalIntent.GENERAL;
    }

    /**
     * 统一判断当前问题在 route 阶段需要的多个意图维度，避免 route 同时散落多个关键词函数。
     */
    private DocumentQuestionIntentDecision detectQuestionIntent(String routeText,
                                                                String originalQuestion,
                                                                String rewrittenQuestion,
                                                                List<String> subQuestions,
                                                                QueryUnderstandingResult queryUnderstanding) {
        String normalized = safeText(routeText);
        if (normalized.isBlank()) {
            return noQuestionIntent("问题为空，跳过路由意图判断。");
        }

        boolean hasMultipleSubQuestions = subQuestions != null && subQuestions.size() > 1;
        QueryType queryType = queryUnderstanding == null || queryUnderstanding.getQueryType() == null
            ? QueryType.DOCUMENT_QA
            : queryUnderstanding.getQueryType();
        boolean structureNavigation = queryType == QueryType.STRUCTURE_NAVIGATION;
        boolean itemLookup = !hasMultipleSubQuestions && resolveExplicitItemIndex(normalized) != null;
        boolean outlineQuestion = structureNavigation && asksOutlineByAnchors(queryUnderstanding);
        boolean contentQuestion = queryType == QueryType.DOCUMENT_QA
            || queryType == QueryType.GRAPH_RELATION
            || queryType == QueryType.GLOBAL_SUMMARY
            || queryType == QueryType.TABLE_QUERY;
        boolean analyticQuestion = contentQuestion && queryType != QueryType.TABLE_QUERY;
        boolean structureHint = structureNavigation
            || itemLookup
            || hasExplicitSectionAnchor(normalized)
            || hasSectionAnchor(queryUnderstanding);
        GraphOnlyIntentDecision graphOnlyIntent = noGraphOnlyIntent("本地规则未命中结构图直答意图。");

        if (!hasMultipleSubQuestions && structureNavigation && !itemLookup && !contentQuestion) {
            graphOnlyIntent = detectGraphOnlyIntentByControlledPlan(normalized, queryUnderstanding);
        }
        
        if (graphOnlyIntent.matched()) {
            if (!isStrictGraphOnlyAllowed(graphOnlyIntent, normalized, contentQuestion, analyticQuestion)) {
                return buildQuestionIntentDecision(
                    noGraphOnlyIntent("命中正文问答诉求，结构线索仅作为混合检索软提示。"),
                    analyticQuestion,
                    outlineQuestion,
                    itemLookup,
                    true,
                    contentQuestion,
                    0.72D,
                    "正文问答不走结构图直答。",
                    "local-rules"
                );
            }
            return buildQuestionIntentDecision(
                graphOnlyIntent,
                analyticQuestion,
                outlineQuestion || graphOnlyIntent.action() == DocumentNavigationAction.CHILD_SECTION_DESCEND,
                itemLookup,
                true,
                contentQuestion,
                graphOnlyIntent.confidence(),
                graphOnlyIntent.reason(),
                graphOnlyIntent.source()
            );
        }
        
        DocumentQuestionIntentDecision localDecision = buildQuestionIntentDecision(
            graphOnlyIntent,
            analyticQuestion,
            outlineQuestion,
            itemLookup,
            structureHint,
            contentQuestion,
            0.65D,
            "本地路由意图规则判断完成。",
            "local-rules"
        );
       
        return localDecision;
    }

    private boolean isStrictGraphOnlyAllowed(GraphOnlyIntentDecision graphOnlyIntent,
                                             String question,
                                             boolean contentQuestion,
                                             boolean analyticQuestion) {
        if (graphOnlyIntent == null || !graphOnlyIntent.matched()) {
            return false;
        }
        if (graphOnlyIntent.action() == DocumentNavigationAction.CHILD_SECTION_DESCEND) {
            return !contentQuestion && !analyticQuestion;
        }
        if (graphOnlyIntent.action() == DocumentNavigationAction.SECTION_ADJACENCY_LOOKUP) {
            return !contentQuestion && !analyticQuestion && hasExplicitSectionAnchor(question);
        }
        return false;
    }

    private boolean shouldUseGraphThenEvidence(String routeText,
                                               Integer itemIndex,
                                               QueryUnderstandingResult queryUnderstanding) {
        if (itemIndex == null || queryUnderstanding == null) {
            return false;
        }
        QueryType queryType = queryUnderstanding.getQueryType() == null
            ? QueryType.DOCUMENT_QA
            : queryUnderstanding.getQueryType();
        if (queryType != QueryType.STRUCTURE_NAVIGATION) {
            return false;
        }
        if (confidence(queryUnderstanding) < 0.72D) {
            return false;
        }
        return hasExplicitSectionAnchor(routeText) || hasSectionAnchor(queryUnderstanding);
    }

    private GraphOnlyIntentDecision detectGraphOnlyIntentByControlledPlan(String question,
                                                                         QueryUnderstandingResult queryUnderstanding) {
        if (asksOutlineByAnchors(queryUnderstanding)) {
            return new GraphOnlyIntentDecision(
                true,
                DocumentNavigationAction.CHILD_SECTION_DESCEND,
                "QueryUnderstanding 判定为高置信目录/结构导航。",
                confidence(queryUnderstanding),
                "query-understanding-structure-outline"
            );
        }
        if (hasExplicitSectionAnchor(question) || hasSectionAnchor(queryUnderstanding)) {
            return new GraphOnlyIntentDecision(
                true,
                DocumentNavigationAction.SECTION_ADJACENCY_LOOKUP,
                "QueryUnderstanding 判定为高置信章节导航。",
                confidence(queryUnderstanding),
                "query-understanding-structure-navigation"
            );
        }
        return noGraphOnlyIntent("本地规则未命中结构图直答意图。");
    }
    
    private DocumentQuestionIntentDecision buildQuestionIntentDecision(GraphOnlyIntentDecision graphOnlyIntent,
                                                                       boolean analytic,
                                                                       boolean outline,
                                                                       boolean itemLookup,
                                                                       boolean structureHint,
                                                                       boolean contentQuestion,
                                                                       double confidence,
                                                                       String reason,
                                                                       String source) {
        boolean effectiveStructureHint = structureHint || (graphOnlyIntent != null && graphOnlyIntent.matched());
        boolean graphOnlyOutline = graphOnlyIntent != null
            && graphOnlyIntent.action() == DocumentNavigationAction.CHILD_SECTION_DESCEND;
        return new DocumentQuestionIntentDecision(
            graphOnlyIntent == null ? noGraphOnlyIntent("未提供 GRAPH_ONLY 判断结果。") : graphOnlyIntent,
            analytic,
            outline || graphOnlyOutline,
            itemLookup,
            effectiveStructureHint,
            contentQuestion,
            confidence,
            StrUtil.blankToDefault(reason, ""),
            StrUtil.blankToDefault(source, "")
        );
    }
    
    private boolean hasExplicitSectionAnchor(String question) {
        if (SECTION_CODE_PATTERN.matcher(question).find()) {
            return true;
        }
        if (CHINESE_SECTION_REFERENCE_PATTERN.matcher(question).find()) {
            return true;
        }
        if (QUOTED_TEXT_PATTERN.matcher(question).find()) {
            return true;
        }
        return false;
    }
    
    private double normalizeConfidence(double confidence) {
        // 有些模型可能输出 86 表示 86%，这里统一折算成 0.86。
        if (confidence > 1D) {
            return confidence / 100D;
        }
        return confidence;
    }
    
    private GraphOnlyIntentDecision noGraphOnlyIntent(String reason) {
        return new GraphOnlyIntentDecision(false, null, StrUtil.blankToDefault(reason, ""), 0D, "none");
    }
    
    private DocumentQuestionIntentDecision noQuestionIntent(String reason) {
        return buildQuestionIntentDecision(
            noGraphOnlyIntent(reason),
            false,
            false,
            false,
            false,
            false,
            0D,
            reason,
            "none"
        );
    }

    private GraphSection resolveSection(Long documentId, String originalQuestion, String rewrittenQuestion) {
        if (documentId == null) {
            return null;
        }
        GraphSection byCode = resolveBySectionCode(documentId, originalQuestion, rewrittenQuestion);
        if (byCode != null) {
            return byCode;
        }

        GraphSection byQuotedTitle = resolveByQuotedTitle(documentId, originalQuestion, rewrittenQuestion);
        if (byQuotedTitle != null) {
            return byQuotedTitle;
        }

        GraphSection indexedMatch = resolveByNavigationIndex(documentId, originalQuestion, rewrittenQuestion);
        if (indexedMatch != null) {
            return indexedMatch;
        }
        List<String> phrases = buildSectionPhrases(originalQuestion, rewrittenQuestion);
        GraphSection localMatch = resolveByLocalStructure(documentId, phrases);
        if (localMatch != null) {
            return localMatch;
        }
        return graphService.findBestSection(documentId, rewrittenQuestion, "");
    }

    private GraphSection resolveBySectionCode(Long documentId, String originalQuestion, String rewrittenQuestion) {
        Matcher matcher = SECTION_CODE_PATTERN.matcher((safeText(originalQuestion) + " " + safeText(rewrittenQuestion)).trim());
        while (matcher.find()) {
            GraphSection section = graphService.findSectionByCode(documentId, matcher.group(1));
            if (section != null) {
                return section;
            }
        }
        return null;
    }

    private GraphSection resolveByQuotedTitle(Long documentId, String originalQuestion, String rewrittenQuestion) {
        LinkedHashSet<String> quotedPhrases = new LinkedHashSet<>();
        collectQuotedPhrases(quotedPhrases, originalQuestion);
        collectQuotedPhrases(quotedPhrases, rewrittenQuestion);
        if (quotedPhrases.isEmpty()) {
            return null;
        }
        List<GraphSection> sections = graphService.listSections(documentId);
        if (sections == null || sections.isEmpty()) {
            return null;
        }
        for (String phrase : quotedPhrases) {
            String normalizedPhrase = normalize(phrase);
            if (normalizedPhrase.length() < 2) {
                continue;
            }
            GraphSection exactTitle = sections.stream()
                .filter(section -> normalize(section.getTitle()).equals(normalizedPhrase)
                    || normalize(section.displayTitle()).equals(normalizedPhrase)
                    || normalize(section.getSectionPath()).endsWith(normalizedPhrase))
                .findFirst()
                .orElse(null);
            if (exactTitle != null) {
                return exactTitle;
            }
        }
        return null;
    }

    private GraphSection resolveByLocalStructure(Long documentId, List<String> phrases) {
        if (phrases.isEmpty()) {
            return null;
        }
        List<GraphSection> sections = graphService.listSections(documentId);
        if (sections == null || sections.isEmpty()) {
            return null;
        }
        return sections.stream()
            .map(section -> new SectionScore(section, scoreSection(section, phrases)))
            .filter(score -> score.score() >= 45D)
            .max(Comparator.comparingDouble(SectionScore::score))
            .map(SectionScore::section)
            .orElse(null);
    }

    private GraphSection resolveByNavigationIndex(Long documentId, String originalQuestion, String rewrittenQuestion) {
        DocumentNavigationIndexService navigationIndexService = navigationIndexServiceProvider.getIfAvailable();
        if (navigationIndexService == null) {
            return null;
        }
        String query = firstNonBlank(rewrittenQuestion, originalQuestion);
        List<DocumentNavigationIndexService.NavigationSectionHit> hits = navigationIndexService.searchSections(
            documentId,
            query,
            detectFacet(query),
            "",
            query,
            5
        );
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        return graphService.findSectionById(documentId, hits.get(0).nodeId());
    }

    private double scoreSection(GraphSection section, List<String> phrases) {
        String title = normalize(section.getTitle());
        String path = normalize(section.getSectionPath());
        String anchor = normalize(section.getAnchorText());
        String content = normalize(section.getContentText());
        double best = 0D;
        for (String phrase : phrases) {
            String normalized = normalize(phrase);
            if (normalized.length() < 2) {
                continue;
            }
            if (path.contains(normalized)) {
                best = Math.max(best, 100D + normalized.length());
            }
            if (title.contains(normalized)) {
                best = Math.max(best, 90D + normalized.length());
            }
            if (anchor.contains(normalized)) {
                best = Math.max(best, 80D + normalized.length());
            }
            if (content.contains(normalized)) {
                best = Math.max(best, 45D + Math.min(normalized.length(), 20));
            }
        }
        return best;
    }

    private List<String> buildSectionPhrases(String originalQuestion, String rewrittenQuestion) {
        LinkedHashSet<String> phrases = new LinkedHashSet<>();
        addCleanPhrase(phrases, originalQuestion);
        addCleanPhrase(phrases, rewrittenQuestion);
        addQuotedPhrases(phrases, originalQuestion);
        addQuotedPhrases(phrases, rewrittenQuestion);
        Matcher stepMatcher = STEP_REFERENCE_PATTERN.matcher(safeText(originalQuestion) + " " + safeText(rewrittenQuestion));
        while (stepMatcher.find()) {
            String all = stepMatcher.group();
            addTextBefore(phrases, originalQuestion, all);
            addTextBefore(phrases, rewrittenQuestion, all);
        }
        return new ArrayList<>(phrases).stream()
            .filter(item -> normalize(item).length() >= 2)
            .limit(8)
            .toList();
    }

    private void addTextBefore(LinkedHashSet<String> phrases, String text, String marker) {
        String normalized = safeText(text);
        if (normalized.isBlank() || marker == null || marker.isBlank()) {
            return;
        }
        int index = normalized.indexOf(marker);
        if (index > 0) {
            addCleanPhrase(phrases, normalized.substring(0, index));
        }
    }

    private void addQuotedPhrases(LinkedHashSet<String> phrases, String text) {
        collectQuotedPhrases(phrases, text);
    }

    private void collectQuotedPhrases(LinkedHashSet<String> phrases, String text) {
        Matcher matcher = QUOTED_TEXT_PATTERN.matcher(safeText(text));
        while (matcher.find()) {
            addCleanPhrase(phrases, matcher.group(1));
        }
    }

    private void addCleanPhrase(LinkedHashSet<String> phrases, String text) {
        String cleaned = cleanPhrase(text);
        if (StrUtil.isNotBlank(cleaned)) {
            phrases.add(cleaned);
        }
    }

    private String cleanPhrase(String text) {
        return safeText(text)
            .replace("刚才说的", "")
            .replace("请问", "")
            .replace("帮我", "")
            .replace("这个", "")
            .replace("那个", "")
            .replace("所属的具体章节", "")
            .replace("所属章节", "")
            .replace("具体章节", "")
            .replace("章节", "")
            .replace("小节", "")
            .replace("目录", "")
            .replace("上一节", "")
            .replace("下一节", "")
            .replace("分别是什么", "")
            .replace("是什么", "")
            .replace("有哪些", "")
            .replace("都有哪些", "")
            .replace("包含哪些", "")
            .replace("中的", "")
            .replace("里面的", "")
            .replace("里的", "")
            .replace("中", "")
            .replace("“", "")
            .replace("”", "")
            .replace("?", "")
            .replace("？", "")
            .trim();
    }

    private boolean mentionsStructure(String question) {
        String normalized = safeText(question);
        return QUOTED_TEXT_PATTERN.matcher(normalized).find()
            || SECTION_CODE_PATTERN.matcher(normalized).find();
    }

    private boolean asksOutlineByAnchors(QueryUnderstandingResult queryUnderstanding) {
        if (queryUnderstanding == null || queryUnderstanding.getSectionAnchors() == null) {
            return false;
        }
        return queryUnderstanding.getSectionAnchors().stream()
            .filter(StrUtil::isNotBlank)
            .map(String::trim)
            .anyMatch(anchor -> "目录".equals(anchor) || "章节列表".equals(anchor) || "展开目录".equals(anchor));
    }

    private boolean hasSectionAnchor(QueryUnderstandingResult queryUnderstanding) {
        return queryUnderstanding != null
            && queryUnderstanding.getSectionAnchors() != null
            && queryUnderstanding.getSectionAnchors().stream().anyMatch(StrUtil::isNotBlank);
    }

    private double confidence(QueryUnderstandingResult queryUnderstanding) {
        if (queryUnderstanding == null) {
            return 0D;
        }
        return normalizeConfidence(queryUnderstanding.getConfidence());
    }

    private Integer resolveExplicitItemIndex(String question) {
        Matcher stepMatcher = STEP_REFERENCE_PATTERN.matcher(safeText(question));
        if (stepMatcher.find()) {
            return parseChineseNumber(stepMatcher.group(1));
        }
        Matcher ordinalMatcher = ORDINAL_REFERENCE_PATTERN.matcher(safeText(question));
        if (ordinalMatcher.find()) {
            return parseChineseNumber(ordinalMatcher.group(1));
        }
        return null;
    }

    private Integer parseChineseNumber(String text) {
        String normalized = safeText(text);
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(normalized);
        }
        Map<Character, Integer> digitMap = Map.of(
            '一', 1, '二', 2, '三', 3, '四', 4, '五', 5,
            '六', 6, '七', 7, '八', 8, '九', 9
        );
        if ("十".equals(normalized)) {
            return 10;
        }
        if (normalized.startsWith("十") && normalized.length() == 2) {
            return 10 + digitMap.getOrDefault(normalized.charAt(1), 0);
        }
        if (normalized.endsWith("十") && normalized.length() == 2) {
            return digitMap.getOrDefault(normalized.charAt(0), 0) * 10;
        }
        if (normalized.contains("十") && normalized.length() == 3) {
            return digitMap.getOrDefault(normalized.charAt(0), 0) * 10 + digitMap.getOrDefault(normalized.charAt(2), 0);
        }
        return digitMap.getOrDefault(normalized.charAt(0), null);
    }

    private List<String> normalizeSubQuestions(RagRewriteResult rewriteResult, String fallbackQuestion) {
        if (rewriteResult == null || rewriteResult.getSubQuestions() == null || rewriteResult.getSubQuestions().isEmpty()) {
            return List.of(fallbackQuestion);
        }
        return rewriteResult.getSubQuestions().stream()
            .filter(StrUtil::isNotBlank)
            .map(String::trim)
            .distinct()
            .toList();
    }

    private List<String> extractQueryHints(String question) {
        String normalized = safeText(question);
        if (normalized.isBlank()) {
            return List.of();
        }
        return Arrays.stream(normalized.split("[\\s、，,；;：:（）()\\-的和及与或]+"))
            .map(String::trim)
            .filter(item -> item.length() >= 2)
            .distinct()
            .limit(6)
            .toList();
    }

    private List<String> buildQueryHints(RetrievalQuestionPlan retrievalPlan,
                                         QueryUnderstandingResult queryUnderstanding,
                                         GraphSection section,
                                         Integer itemIndex) {
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        if (retrievalPlan != null) {
            hints.addAll(extractQueryHints(retrievalPlan.getRetrievalQuestion()));
        }
        if (queryUnderstanding != null) {
            if (queryUnderstanding.getEntities() != null) {
                queryUnderstanding.getEntities().forEach(item -> addHint(hints, item));
            }
            if (queryUnderstanding.getSectionAnchors() != null) {
                queryUnderstanding.getSectionAnchors().forEach(item -> addHint(hints, item));
            }
            StructureNavigationIntent structureIntent = queryUnderstanding.getStructureNavigationIntent();
            if (structureIntent != null && structureIntent.getSectionAnchors() != null) {
                structureIntent.getSectionAnchors().forEach(item -> addHint(hints, item));
            }
        }
        if (section != null) {
            addHint(hints, section.displayTitle());
            addHint(hints, section.getTitle());
            addHint(hints, section.getNodeCode());
        }
        if (itemIndex != null) {
            addHint(hints, "第" + itemIndex + "步");
            addHint(hints, "第" + itemIndex + "项");
        }
        return hints.stream()
            .filter(StrUtil::isNotBlank)
            .limit(10)
            .toList();
    }

    private void addHint(LinkedHashSet<String> hints, String hint) {
        String normalized = safeText(hint);
        if (normalized.isBlank()) {
            return;
        }
        hints.add(normalized);
    }

    private String detectFacet(String question) {
        String normalized = safeText(question);
        if (SECTION_CODE_PATTERN.matcher(normalized).find()
            || CHINESE_SECTION_REFERENCE_PATTERN.matcher(normalized).find()
            || QUOTED_TEXT_PATTERN.matcher(normalized).find()) {
            return "章节";
        }
        if (STEP_REFERENCE_PATTERN.matcher(normalized).find()
            || ORDINAL_REFERENCE_PATTERN.matcher(normalized).find()) {
            return "步骤";
        }
        return "";
    }

    private String normalize(String text) {
        return safeText(text).replaceAll("[\\s>`*#_\\-，,。；;：:（）()“”\"']+", "").toLowerCase();
    }

    private String firstNonBlank(String left, String right) {
        if (StrUtil.isNotBlank(left)) {
            return left.trim();
        }
        return StrUtil.blankToDefault(right, "");
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }
}
