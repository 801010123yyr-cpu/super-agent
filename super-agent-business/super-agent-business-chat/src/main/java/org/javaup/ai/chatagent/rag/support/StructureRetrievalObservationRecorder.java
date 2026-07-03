package org.javaup.ai.chatagent.rag.support;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.model.ChannelExecutionView;
import org.javaup.ai.chatagent.model.RetrievalResultView;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.service.ConversationTraceRecorder;
import org.javaup.ai.manage.model.graph.GraphItem;
import org.javaup.ai.manage.model.graph.GraphQueryResult;
import org.javaup.ai.manage.model.graph.GraphSection;
import org.javaup.enums.RetrievalChannelEnum;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 结构图直答不经过 RagRetrievalEngine，这里补齐与检索页一致的观测快照。
 */
@Component
public class StructureRetrievalObservationRecorder {

    private static final int PREVIEW_LIMIT = 800;

    public void record(ConversationTraceRecorder traceRecorder,
                       ConversationExecutionPlan plan,
                       GraphQueryResult graphResult,
                       String renderedAnswer,
                       long startTimeMs) {
        if (traceRecorder == null || plan == null || graphResult == null) {
            return;
        }
        Instant startTime = Instant.ofEpochMilli(Math.max(0L, startTimeMs));
        Instant endTime = Instant.now();
        List<RetrievalResultView> results = buildResults(traceRecorder, plan, graphResult, renderedAnswer);
        traceRecorder.recordChannelExecutions(List.of(buildExecution(
            traceRecorder,
            plan,
            startTime,
            endTime,
            results.size(),
            StrUtil.isBlank(renderedAnswer) ? 0 : results.size()
        )));
        traceRecorder.recordRetrievalResults(results);
    }

    private ChannelExecutionView buildExecution(ConversationTraceRecorder traceRecorder,
                                                ConversationExecutionPlan plan,
                                                Instant startTime,
                                                Instant endTime,
                                                int recalledCount,
                                                int selectedCount) {
        ChannelExecutionView execution = new ChannelExecutionView();
        execution.setTraceId(traceRecorder.traceId());
        execution.setSubQuestionIndex(1);
        execution.setSubQuestion(resolveQuestion(plan));
        execution.setChannelType(RetrievalChannelEnum.STRUCTURE.getName());
        execution.setExecutionState(1);
        execution.setStartTime(startTime);
        execution.setEndTime(endTime);
        execution.setDurationMs(Math.max(0L, endTime.toEpochMilli() - startTime.toEpochMilli()));
        execution.setRecalledCount(recalledCount);
        execution.setAcceptedCount(recalledCount);
        execution.setFinalSelectedCount(selectedCount);
        execution.setAvgScore(BigDecimal.ONE);
        execution.setMaxScore(BigDecimal.ONE);
        execution.setMinScore(recalledCount == 0 ? BigDecimal.ZERO : BigDecimal.ONE);
        execution.setErrorMessage("");
        return execution;
    }

    private List<RetrievalResultView> buildResults(ConversationTraceRecorder traceRecorder,
                                                   ConversationExecutionPlan plan,
                                                   GraphQueryResult graphResult,
                                                   String renderedAnswer) {
        List<RetrievalResultView> results = new ArrayList<>();
        GraphSection target = graphResult.getTargetSection();
        if (target != null) {
            results.add(buildSectionResult(traceRecorder, plan, target, renderedAnswer, 1));
        }
        int rank = 2;
        for (GraphItem item : graphResult.getMatchedItems() == null ? List.<GraphItem>of() : graphResult.getMatchedItems()) {
            results.add(buildItemResult(traceRecorder, plan, target, item, rank++));
        }
        if (graphResult.getTargetItem() != null) {
            results.add(buildItemResult(traceRecorder, plan, target, graphResult.getTargetItem(), rank));
        }
        return results;
    }

    private RetrievalResultView buildSectionResult(ConversationTraceRecorder traceRecorder,
                                                   ConversationExecutionPlan plan,
                                                   GraphSection section,
                                                   String renderedAnswer,
                                                   int rank) {
        RetrievalResultView result = baseResult(traceRecorder, plan, rank);
        result.setDocumentId(section.getDocumentId() == null ? plan.getSelectedDocumentId() : section.getDocumentId());
        result.setDocumentName(StrUtil.blankToDefault(plan.getSelectedDocumentName(), ""));
        result.setSectionPath(StrUtil.blankToDefault(section.displayTitle(), ""));
        String preview = firstNonBlank(section.getContentText(), renderedAnswer, section.displayTitle());
        result.setChunkTextPreview(truncate(preview));
        result.setChunkCharCount(StrUtil.blankToDefault(preview, "").length());
        return result;
    }

    private RetrievalResultView buildItemResult(ConversationTraceRecorder traceRecorder,
                                                ConversationExecutionPlan plan,
                                                GraphSection section,
                                                GraphItem item,
                                                int rank) {
        RetrievalResultView result = baseResult(traceRecorder, plan, rank);
        result.setDocumentId(section == null || section.getDocumentId() == null ? plan.getSelectedDocumentId() : section.getDocumentId());
        result.setDocumentName(StrUtil.blankToDefault(plan.getSelectedDocumentName(), ""));
        result.setSectionPath(section == null ? "" : StrUtil.blankToDefault(section.displayTitle(), ""));
        String preview = item == null ? "" : item.displayText();
        result.setChunkTextPreview(truncate(preview));
        result.setChunkCharCount(StrUtil.blankToDefault(preview, "").length());
        return result;
    }

    private RetrievalResultView baseResult(ConversationTraceRecorder traceRecorder,
                                           ConversationExecutionPlan plan,
                                           int rank) {
        RetrievalResultView result = new RetrievalResultView();
        result.setTraceId(traceRecorder.traceId());
        result.setSubQuestionIndex(1);
        result.setSubQuestion(resolveQuestion(plan));
        result.setChannelType(RetrievalChannelEnum.STRUCTURE.getName());
        result.setChannelRank(rank);
        result.setRrfRank(rank);
        result.setFinalRank(rank);
        result.setOriginalScore(BigDecimal.ONE);
        result.setRrfScore(BigDecimal.ONE);
        result.setHybridScore(BigDecimal.ONE);
        result.setMetadataBoost(BigDecimal.ZERO);
        result.setGatePassed(true);
        result.setElevated(false);
        result.setSelected(rank == 1);
        result.setSelectionReason(rank == 1 ? "结构图定位结果作为直接回答证据" : "结构图命中项作为补充证据");
        return result;
    }

    private String resolveQuestion(ConversationExecutionPlan plan) {
        return firstNonBlank(plan.getRetrievalQuestion(), plan.getRewriteQuestion(), plan.getOriginalQuestion());
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String truncate(String value) {
        String normalized = StrUtil.blankToDefault(value, "").trim();
        if (normalized.length() <= PREVIEW_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, PREVIEW_LIMIT);
    }
}
