package org.javaup.ai.manage.service.impl;

import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyPlan;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyStep;
import org.javaup.ai.manage.data.SuperAgentDocumentTask;
import org.javaup.ai.manage.vo.DocumentParseRouteProgressVo;
import org.javaup.ai.manage.vo.DocumentTaskLogVo;
import org.javaup.enums.DocumentFileTypeEnum;
import org.javaup.enums.DocumentParseStatusEnum;
import org.javaup.enums.DocumentStrategyPipelineTypeEnum;
import org.javaup.enums.DocumentStrategyStatusEnum;
import org.javaup.enums.DocumentTaskEventTypeEnum;
import org.javaup.enums.DocumentTaskStageEnum;
import org.javaup.enums.DocumentTaskStatusEnum;
import org.javaup.enums.DocumentTaskTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentParseRouteProgressAssemblerTest {

    @Test
    void buildsParseRouteProgressFromTaskTraceAndStrategyPlan() {
        SuperAgentDocument document = new SuperAgentDocument();
        document.setId(100L);
        document.setFileType(DocumentFileTypeEnum.PDF.getCode());
        document.setParseStatus(DocumentParseStatusEnum.PARSE_SUCCESS.getCode());
        document.setStrategyStatus(DocumentStrategyStatusEnum.RECOMMENDED.getCode());
        document.setCurrentPlanId(300L);

        SuperAgentDocumentTask task = new SuperAgentDocumentTask();
        task.setId(200L);
        task.setDocumentId(100L);
        task.setTaskType(DocumentTaskTypeEnum.PARSE_ROUTE.getCode());
        task.setTaskStatus(DocumentTaskStatusEnum.SUCCESS.getCode());
        task.setCurrentStage(DocumentTaskStageEnum.STRATEGY_ROUTE.getCode());
        task.setStartTime(new Date(1000L));
        task.setFinishTime(new Date(2600L));
        task.setCostMillis(1600L);
        task.setExtJson("""
            {
              "parserTraceMetadata": {
                "providerName": "aliyun_docmind",
                "providerVersion": "2026-06",
                "jobId": "job-o9",
                "pageCount": 7,
                "blockCount": 28,
                "tableCount": 2,
                "figureCount": 1,
                "captionCount": 3
              }
            }
            """);

        SuperAgentDocumentStrategyPlan plan = new SuperAgentDocumentStrategyPlan();
        plan.setId(300L);
        plan.setDocumentId(100L);
        plan.setRecommendReason("结构清晰，优先父子双流水线。");

        DocumentParseRouteProgressVo progress = DocumentParseRouteProgressAssembler.build(
            document,
            task,
            plan,
            List.of(step(DocumentStrategyPipelineTypeEnum.PARENT.getCode()), step(DocumentStrategyPipelineTypeEnum.CHILD.getCode())),
            List.of(log(10L, DocumentTaskStageEnum.CONTENT_PARSE.getCode())),
            1L,
            new com.fasterxml.jackson.databind.ObjectMapper()
        );

        assertThat(progress.getDocumentId()).isEqualTo(100L);
        assertThat(progress.getTaskId()).isEqualTo(200L);
        assertThat(progress.getTaskType()).isEqualTo(DocumentTaskTypeEnum.PARSE_ROUTE.getCode());
        assertThat(progress.getTaskStatusName()).isEqualTo("成功");
        assertThat(progress.getCurrentStageName()).isEqualTo("策略路由");
        assertThat(progress.getStageCode()).isEqualTo("STRATEGY_ROUTE");
        assertThat(progress.getStageLabel()).isEqualTo("策略推荐");
        assertThat(progress.getParseStatusName()).isEqualTo("解析成功");
        assertThat(progress.getStrategyStatusName()).isEqualTo("已推荐");
        assertThat(progress.getParseMode()).isEqualTo("ALIYUN_DOCMIND");
        assertThat(progress.getParserName()).isEqualTo("aliyun_docmind");
        assertThat(progress.getParserVersion()).isEqualTo("2026-06");
        assertThat(progress.getJobId()).isEqualTo("job-o9");
        assertThat(progress.getPageCount()).isEqualTo(7);
        assertThat(progress.getBlockCount()).isEqualTo(28);
        assertThat(progress.getTableCount()).isEqualTo(2);
        assertThat(progress.getFigureCount()).isEqualTo(1);
        assertThat(progress.getCaptionCount()).isEqualTo(3);
        assertThat(progress.getPlanReady()).isTrue();
        assertThat(progress.getPlanId()).isEqualTo(300L);
        assertThat(progress.getRecommendReason()).isEqualTo("结构清晰，优先父子双流水线。");
        assertThat(progress.getParentStepCount()).isEqualTo(1);
        assertThat(progress.getChildStepCount()).isEqualTo(1);
        assertThat(progress.getElapsedMillis()).isEqualTo(1600L);
        assertThat(progress.getLatestLogId()).isEqualTo(10L);
        assertThat(progress.getTotalLogCount()).isEqualTo(1L);
        assertThat(progress.getLogs()).hasSize(1);
    }

    @Test
    void filtersCachedLogsBySinceLogIdAndLimit() {
        List<DocumentTaskLogVo> logs = List.of(
            log(1L, DocumentTaskStageEnum.FILE_UPLOAD.getCode()),
            log(2L, DocumentTaskStageEnum.CONTENT_PARSE.getCode()),
            log(3L, DocumentTaskStageEnum.STRATEGY_ROUTE.getCode())
        );

        List<DocumentTaskLogVo> filtered = DocumentParseRouteProgressAssembler.filterLogs(logs, 1L, 1);

        assertThat(filtered).extracting(DocumentTaskLogVo::getId).containsExactly(3L);
    }

    @Test
    void keepsParserTraceWhenLaterContentParseLogOnlyContainsPersistSummary() {
        SuperAgentDocument document = new SuperAgentDocument();
        document.setId(100L);
        document.setFileType(DocumentFileTypeEnum.PDF.getCode());

        SuperAgentDocumentTask task = new SuperAgentDocumentTask();
        task.setId(200L);
        task.setDocumentId(100L);
        task.setTaskType(DocumentTaskTypeEnum.PARSE_ROUTE.getCode());
        task.setTaskStatus(DocumentTaskStatusEnum.RUNNING.getCode());
        task.setCurrentStage(DocumentTaskStageEnum.CONTENT_PARSE.getCode());

        List<DocumentTaskLogVo> logs = List.of(
            logWithDetail(10L, """
                {
                  "parserProviderName": "aliyun_docmind",
                  "parserProviderVersion": "2026-06",
                  "parserTraceMetadata": {
                    "providerName": "aliyun_docmind",
                    "providerVersion": "2026-06",
                    "jobId": "job-from-log",
                    "pageCount": 3,
                    "blockCount": 18,
                    "tableCount": 1
                  }
                }
                """),
            logWithDetail(11L, """
                {
                  "artifactCount": 4,
                  "blockCount": 18,
                  "structureNodeCount": 5
                }
                """)
        );

        DocumentParseRouteProgressVo progress = DocumentParseRouteProgressAssembler.build(
            document,
            task,
            null,
            List.of(),
            logs,
            2L,
            new com.fasterxml.jackson.databind.ObjectMapper()
        );

        assertThat(progress.getParserName()).isEqualTo("aliyun_docmind");
        assertThat(progress.getParserVersion()).isEqualTo("2026-06");
        assertThat(progress.getJobId()).isEqualTo("job-from-log");
        assertThat(progress.getPageCount()).isEqualTo(3);
        assertThat(progress.getBlockCount()).isEqualTo(18);
        assertThat(progress.getTableCount()).isEqualTo(1);
        assertThat(progress.getLatestLogId()).isEqualTo(11L);
    }

    private static SuperAgentDocumentStrategyStep step(String pipelineType) {
        SuperAgentDocumentStrategyStep step = new SuperAgentDocumentStrategyStep();
        step.setPipelineType(pipelineType);
        return step;
    }

    private static DocumentTaskLogVo log(Long id, Integer stageType) {
        return new DocumentTaskLogVo(
            id,
            stageType,
            "",
            DocumentTaskEventTypeEnum.COMPLETE.getCode(),
            "",
            1,
            "",
            "log-" + id,
            null,
            new Date(id * 1000L)
        );
    }

    private static DocumentTaskLogVo logWithDetail(Long id, String detailJson) {
        return new DocumentTaskLogVo(
            id,
            DocumentTaskStageEnum.CONTENT_PARSE.getCode(),
            "",
            DocumentTaskEventTypeEnum.COMPLETE.getCode(),
            "",
            1,
            "",
            "log-" + id,
            detailJson,
            new Date(id * 1000L)
        );
    }
}
