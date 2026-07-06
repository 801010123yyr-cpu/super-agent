package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.AnswerPlan;
import org.javaup.ai.chatagent.rag.model.AnswerHistoryContext;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.EvidenceApplicabilityResult;
import org.javaup.ai.chatagent.rag.model.QueryUnderstandingResult;
import org.javaup.ai.chatagent.rag.model.RagPromptAssemblyResult;
import org.javaup.ai.chatagent.rag.model.RagRetrievalContext;
import org.javaup.ai.chatagent.rag.model.SubQuestionEvidence;
import org.javaup.ai.prompt.PromptTemplateNames;
import org.javaup.ai.prompt.PromptTemplateService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务层
 * @author: 阿星不是程序员
 **/

@Service
public class RagPromptAssemblyService {

    private final ChatRagProperties properties;
    private final PromptTemplateService promptTemplateService;
    private final AnswerPlanService answerPlanService;

    public RagPromptAssemblyService(ChatRagProperties properties,
                                    PromptTemplateService promptTemplateService,
                                    AnswerPlanService answerPlanService) {
        this.properties = properties;
        this.promptTemplateService = promptTemplateService;
        this.answerPlanService = answerPlanService == null ? new AnswerPlanService() : answerPlanService;
    }

    public String buildSystemPrompt() {

        return StrUtil.isNotBlank(properties.getAnswerSystemPrompt())
            ? properties.getAnswerSystemPrompt().trim()
            : promptTemplateService.render(PromptTemplateNames.RAG_ANSWER_SYSTEM, Map.of());
    }

    public String buildUserPrompt(ConversationExecutionPlan plan, RagRetrievalContext context) {
        return assemble(plan, context).getUserPrompt();
    }

    public RagPromptAssemblyResult assemble(ConversationExecutionPlan plan, RagRetrievalContext context) {
        PromptBudget promptBudget = new PromptBudget(
            Math.max(0, properties.getTotalEvidenceMaxChars()),
            Math.max(0, properties.getPerSubQuestionEvidenceMaxChars())
        );
        Set<String> renderedReferenceKeys = new LinkedHashSet<>();
        String evidenceBlocks = buildEvidenceBlocks(context, renderedReferenceKeys, promptBudget);
        String userPrompt = promptTemplateService.render(PromptTemplateNames.RAG_ANSWER_USER, Map.of(
            "currentDate", StrUtil.blankToDefault(plan.getCurrentDateText(), ""),
            "originalQuestion", StrUtil.blankToDefault(plan.getOriginalQuestion(), ""),
            "hasRetrievalQuestion", hasRetrievalQuestion(plan),
            "retrievalQuestion", StrUtil.blankToDefault(plan.getRetrievalQuestion(), ""),
            "hasHistoryContext", hasHistoryContext(plan),
            "historyContext", buildHistoryContext(plan),
            "hasSubQuestions", hasSubQuestions(plan),
            "subQuestions", buildSubQuestions(plan),
            "evidenceBlocks", evidenceBlocks
        ));
        String boundaryInstruction = buildAnswerBoundaryInstruction(plan, context);
        if (StrUtil.isNotBlank(boundaryInstruction)) {
            userPrompt = boundaryInstruction + "\n\n" + userPrompt;
        }
        String roleInstruction = buildAnswerRoleInstruction(plan);
        if (StrUtil.isNotBlank(roleInstruction)) {
            userPrompt = roleInstruction + "\n\n" + userPrompt;
        }
        return new RagPromptAssemblyResult(
            buildSystemPrompt(),
            userPrompt,
            promptBudget.totalBudget,
            promptBudget.perSubQuestionBudget,
            promptBudget.renderedReferenceCount,
            promptBudget.omittedReferenceCount,
            promptBudget.renderedReferenceDetails,
            promptBudget.omittedReferenceDetails
        );
    }

    private boolean hasRetrievalQuestion(ConversationExecutionPlan plan) {
        return StrUtil.isNotBlank(plan.getRetrievalQuestion()) && !plan.getRetrievalQuestion().equals(plan.getOriginalQuestion());
    }

    private boolean hasHistoryContext(ConversationExecutionPlan plan) {
        AnswerHistoryContext answerHistoryContext = plan.getAnswerHistoryContext();
        return answerHistoryContext != null && !answerHistoryContext.isEmpty();
    }

    private String buildHistoryContext(ConversationExecutionPlan plan) {
        return hasHistoryContext(plan) ? plan.getAnswerHistoryContext().getRenderedText().trim() : "";
    }

    private boolean hasSubQuestions(ConversationExecutionPlan plan) {
        return plan.getRetrievalSubQuestions() != null && plan.getRetrievalSubQuestions().size() > 1;
    }

    private String buildSubQuestions(ConversationExecutionPlan plan) {
        if (!hasSubQuestions(plan)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < plan.getRetrievalSubQuestions().size(); index++) {
            builder.append(index + 1).append(". ").append(plan.getRetrievalSubQuestions().get(index)).append("\n");
        }
        return builder.toString().trim();
    }

    private String buildEvidenceBlocks(RagRetrievalContext context,
                                       Set<String> renderedReferenceKeys,
                                       PromptBudget promptBudget) {
        StringBuilder builder = new StringBuilder();
        for (SubQuestionEvidence evidence : context.getSubQuestionEvidenceList()) {
            StringBuilder referenceBuilder = new StringBuilder();
            appendReferences(referenceBuilder, evidence.getReferences(), renderedReferenceKeys, promptBudget);
            builder.append(promptTemplateService.render(PromptTemplateNames.RAG_ANSWER_SUB_QUESTION_EVIDENCE, Map.of(
                "subQuestionIndex", evidence.getSubQuestionIndex(),
                "subQuestion", StrUtil.blankToDefault(evidence.getSubQuestion(), ""),
                "references", referenceBuilder.toString().trim()
            ))).append("\n\n");
        }
        return builder.toString().trim();
    }

    private void appendReferences(StringBuilder builder,
                                  List<SearchReference> references,
                                  Set<String> renderedReferenceKeys,
                                  PromptBudget promptBudget) {
        if (references == null || references.isEmpty()) {
            builder.append(promptTemplateService.render(PromptTemplateNames.RAG_ANSWER_NO_EVIDENCE, Map.of())).append('\n');
            return;
        }
        promptBudget.resetSubQuestionBudget();
        boolean omitted = false;
        for (SearchReference reference : references) {
            String uniqueKey = reference.uniqueKey();
            if (renderedReferenceKeys.contains(uniqueKey)) {
                String reuseLine = promptTemplateService.render(PromptTemplateNames.RAG_ANSWER_REUSE_REFERENCE, Map.of(
                    "referenceId", StrUtil.blankToDefault(reference.getReferenceId(), "")
                )) + "\n";
                if (promptBudget.tryConsume(reuseLine.length())) {
                    builder.append(reuseLine);
                }
                continue;
            }

            if ("WEB".equalsIgnoreCase(reference.getSourceType())) {
                String block = buildWebReferenceBlock(reference);
                if (promptBudget.tryConsume(block.length())) {
                    builder.append(block);
                    renderedReferenceKeys.add(uniqueKey);
                    promptBudget.markRendered(referenceSummary(reference, "已纳入 Prompt"));
                } else {
                    omitted = true;
                    promptBudget.markOmitted(referenceSummary(reference, "超出上下文预算，已省略"));
                    break;
                }
                continue;
            }
            String block = buildDocumentReferenceBlock(reference);
            if (promptBudget.tryConsume(block.length())) {
                builder.append(block);
                renderedReferenceKeys.add(uniqueKey);
                promptBudget.markRendered(referenceSummary(reference, "已纳入 Prompt"));
            } else {
                omitted = true;
                promptBudget.markOmitted(referenceSummary(reference, "超出上下文预算，已省略"));
                break;
            }
        }
        if (omitted) {
            builder.append(promptTemplateService.render(PromptTemplateNames.RAG_ANSWER_OMITTED_EVIDENCE, Map.of())).append('\n');
        }
    }

    private String buildWebReferenceBlock(SearchReference reference) {
        return promptTemplateService.render(PromptTemplateNames.RAG_ANSWER_WEB_REFERENCE, Map.of(
            "referenceId", StrUtil.blankToDefault(reference.getReferenceId(), ""),
            "title", StrUtil.blankToDefault(reference.getTitle(), "网页来源"),
            "url", StrUtil.blankToDefault(reference.getUrl(), "未知"),
            "snippet", trimSnippet(reference.getSnippet(), 900)
        )) + "\n\n";
    }

    private String buildDocumentReferenceBlock(SearchReference reference) {
        String snippet = trimSnippet(reference.getSnippet(), 1100);
        if (StrUtil.isNotBlank(reference.getEvidenceRole())) {
            snippet = "【证据角色】证据角色：" + reference.getEvidenceRole() + "\n" + snippet;
        }
        if (EvidenceApplicabilityResult.NOT_APPLICABLE.equals(reference.getEvidenceApplicabilityStatus())) {
            snippet = "【证据适用性】这条证据不适用于当前目标对象，只能作为相似但不适用的线索。原因："
                + StrUtil.blankToDefault(reference.getEvidenceApplicabilityReason(), "-")
                + "\n"
                + snippet;
        }
        if ("SUMMARY_ONLY".equalsIgnoreCase(StrUtil.blankToDefault(reference.getRaptorSourceStatus(), ""))) {
            snippet = "【RAPTOR 摘要边界】这条证据只命中层级摘要，未下钻到 source chunk 或 ParentBlock；只能作为背景线索，不能单独支撑具体事实结论。\n"
                + snippet;
        }
        if (reference.isKgCommunitySummaryOnly()) {
            snippet = "【GraphRAG 社区摘要边界】这条证据只命中社区摘要，缺少可回到原文 quote 的 KG evidence；只能作为背景线索，不能单独支撑具体事实结论。\n"
                + snippet;
        }
        return promptTemplateService.render(PromptTemplateNames.RAG_ANSWER_DOCUMENT_REFERENCE, Map.of(
            "referenceId", StrUtil.blankToDefault(reference.getReferenceId(), ""),
            "documentName", StrUtil.blankToDefault(
                StrUtil.blankToDefault(reference.getDocumentName(), reference.getTitle()),
                "文档来源"
            ),
            "sectionPath", StrUtil.blankToDefault(reference.getSectionPath(), "未识别"),
            "snippet", snippet
        )) + "\n\n";
    }

    private String buildAnswerRoleInstruction(ConversationExecutionPlan plan) {
        AnswerPlan answerPlan = answerPlanService.build(plan == null ? null : plan.getQueryUnderstanding());
        return answerPlan == null ? "" : StrUtil.blankToDefault(answerPlan.getInstruction(), "");
    }

    private String buildAnswerBoundaryInstruction(ConversationExecutionPlan plan, RagRetrievalContext context) {
        QueryUnderstandingResult understanding = plan == null ? null : plan.getQueryUnderstanding();
        boolean explicitBoundary = understanding != null
            && (understanding.isNegativeBoundary()
            || "EXPLICIT_EVIDENCE_REQUIRED".equalsIgnoreCase(StrUtil.blankToDefault(understanding.getAnswerExpectation(), "")));
        boolean hasNotApplicableEvidence = hasNotApplicableEvidence(context);
        if (!explicitBoundary && !hasNotApplicableEvidence) {
            return "";
        }
        String targetText = understanding == null || understanding.getTargetEntities() == null || understanding.getTargetEntities().isEmpty()
            ? ""
            : String.join("、", understanding.getTargetEntities());
        String excludedText = understanding == null || understanding.getExcludedEntities() == null || understanding.getExcludedEntities().isEmpty()
            ? ""
            : String.join("、", understanding.getExcludedEntities());
        StringBuilder builder = new StringBuilder();
        builder.append("回答边界要求：如果证据只支持相似对象或被用户排除的对象，而没有支持当前目标对象，必须回答文档没有明确给出，不得把相似对象的步骤、原因或结论套用到当前目标对象。");
        if (StrUtil.isNotBlank(targetText)) {
            builder.append("\n当前目标对象：").append(targetText);
        }
        if (StrUtil.isNotBlank(excludedText)) {
            builder.append("\n用户排除对象：").append(excludedText);
        }
        return builder.toString();
    }

    private boolean hasNotApplicableEvidence(RagRetrievalContext context) {
        if (context == null || context.getSubQuestionEvidenceList() == null) {
            return false;
        }
        return context.getSubQuestionEvidenceList().stream()
            .filter(evidence -> evidence != null && evidence.getReferences() != null)
            .flatMap(evidence -> evidence.getReferences().stream())
            .anyMatch(reference -> reference != null
                && EvidenceApplicabilityResult.NOT_APPLICABLE.equals(reference.getEvidenceApplicabilityStatus()));
    }

    private String trimSnippet(String snippet, int maxChars) {
        if (StrUtil.isBlank(snippet)) {
            return "";
        }

        return snippet.length() <= maxChars ? snippet : snippet.substring(0, maxChars) + "...";
    }

    private String referenceSummary(SearchReference reference, String suffix) {
        if (reference == null) {
            return suffix;
        }
        String title = StrUtil.blankToDefault(reference.getDocumentName(), reference.getTitle());
        String path = StrUtil.blankToDefault(reference.getSectionPath(), reference.getUrl());
        String refId = StrUtil.blankToDefault(reference.getReferenceId(), "-");
        return "[" + refId + "] " + title + (StrUtil.isBlank(path) ? "" : " | " + path) + " | " + suffix;
    }

    private static final class PromptBudget {

        private final int totalBudget;
        private final int perSubQuestionBudget;
        private int remainingTotal;
        private int remainingSubQuestion;
        private int renderedReferenceCount;
        private int omittedReferenceCount;
        private final List<String> renderedReferenceDetails = new ArrayList<>();
        private final List<String> omittedReferenceDetails = new ArrayList<>();

        private PromptBudget(int totalBudget, int perSubQuestionBudget) {
            this.totalBudget = totalBudget;
            this.perSubQuestionBudget = perSubQuestionBudget;
            this.remainingTotal = totalBudget;
            this.remainingSubQuestion = perSubQuestionBudget;
        }

        private void resetSubQuestionBudget() {
            this.remainingSubQuestion = perSubQuestionBudget;
        }

        private boolean tryConsume(int size) {
            if (totalBudget <= 0 || perSubQuestionBudget <= 0) {
                return false;
            }
            if (size > remainingTotal || size > remainingSubQuestion) {
                return false;
            }
            remainingTotal -= size;
            remainingSubQuestion -= size;
            return true;
        }

        private void markRendered(String detail) {
            renderedReferenceCount++;
            if (StrUtil.isNotBlank(detail)) {
                renderedReferenceDetails.add(detail);
            }
        }

        private void markOmitted(String detail) {
            omittedReferenceCount++;
            if (StrUtil.isNotBlank(detail)) {
                omittedReferenceDetails.add(detail);
            }
        }
    }
}
