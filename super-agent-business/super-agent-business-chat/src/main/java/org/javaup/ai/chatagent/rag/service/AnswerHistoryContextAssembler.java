package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.AnswerHistoryContext;
import org.javaup.ai.chatagent.rag.model.QueryType;
import org.javaup.ai.chatagent.rag.model.QueryUnderstandingResult;
import org.springframework.stereotype.Service;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务层
 * @author: 阿星不是程序员
 **/

@Service
public class AnswerHistoryContextAssembler {

    private final ChatRagProperties properties;

    public AnswerHistoryContextAssembler(ChatRagProperties properties) {
        this.properties = properties;
    }

    public AnswerHistoryContext assemble(String question, String answerRecentTranscript) {
        return assemble(question, answerRecentTranscript, null);
    }

    public AnswerHistoryContext assemble(String question,
                                         String answerRecentTranscript,
                                         QueryUnderstandingResult queryUnderstanding) {
        String normalizedQuestion = safeText(question);
        String recentUserContext = extractRecentUserQuestions(answerRecentTranscript);
        int totalBudget = Math.max(1, properties.getAnswerHistoryMaxChars());
        boolean hasRecentContext = StrUtil.isNotBlank(recentUserContext);
        boolean followUpQuestion = looksLikeFollowUpQuestion(normalizedQuestion, hasRecentContext, queryUnderstanding);

        if (!followUpQuestion || !hasRecentContext) {
            return emptyContext(totalBudget, followUpQuestion);
        }

        String recentPart = renderRecentContext(recentUserContext, totalBudget);
        if (recentPart.isBlank()) {
            return emptyContext(totalBudget, followUpQuestion);
        }
        return AnswerHistoryContext.builder()
            .renderedText(recentPart)
            .structuredContext("")
            .recentContext(recentPart)
            .followUpQuestion(followUpQuestion)
            .totalBudget(totalBudget)
            .recentBudget(totalBudget)
            .structuredBudget(0)
            .build();
    }

    private AnswerHistoryContext emptyContext(int totalBudget, boolean followUpQuestion) {
        return AnswerHistoryContext.builder()
            .renderedText("")
            .structuredContext("")
            .recentContext("")
            .followUpQuestion(followUpQuestion)
            .totalBudget(totalBudget)
            .recentBudget(0)
            .structuredBudget(0)
            .build();
    }

    private String extractRecentUserQuestions(String answerRecentTranscript) {
        String normalized = safeText(answerRecentTranscript);
        if (normalized.startsWith("【最近相关对话】")) {
            normalized = normalized.substring("【最近相关对话】".length()).trim();
        }
        if (normalized.startsWith("最近相关对话：")) {
            normalized = normalized.substring("最近相关对话：".length()).trim();
        }
        StringBuilder builder = new StringBuilder();
        for (String line : normalized.split("\n")) {
            String trimmed = safeText(line);
            if (!trimmed.startsWith("用户：")) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(trimmed);
        }
        return builder.toString().trim();
    }

    private boolean looksLikeFollowUpQuestion(String normalizedQuestion,
                                              boolean hasRecentContext,
                                              QueryUnderstandingResult queryUnderstanding) {
        if (!hasRecentContext || StrUtil.isBlank(normalizedQuestion)) {
            return false;
        }
        QueryType queryType = queryUnderstanding == null || queryUnderstanding.getQueryType() == null
            ? QueryType.DOCUMENT_QA
            : queryUnderstanding.getQueryType();
        if (queryType == QueryType.FOLLOW_UP) {
            return true;
        }
        return false;
    }

    private String renderRecentContext(String recentUserContext, int budget) {
        if (budget <= 0 || StrUtil.isBlank(recentUserContext)) {
            return "";
        }
        String title = "对话承接上下文（仅用于理解指代，不作为事实证据）：\n";
        if (budget <= title.length()) {
            return clipTail(recentUserContext, budget);
        }
        String body = clipTail(recentUserContext, budget - title.length());
        if (body.isBlank()) {
            return "";
        }
        return title + body;
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

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }
}
