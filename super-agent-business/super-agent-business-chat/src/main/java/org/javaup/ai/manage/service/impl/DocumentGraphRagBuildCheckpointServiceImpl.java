package org.javaup.ai.manage.service.impl;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.data.SuperAgentDocumentTask;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTaskMapper;
import org.javaup.ai.manage.model.graph.GraphRagBuildResult;
import org.javaup.ai.manage.service.DocumentTaskLogService;
import org.javaup.ai.manage.service.GraphRagBuildCheckpointService;
import org.javaup.enums.DocumentLogLevelEnum;
import org.javaup.enums.DocumentOperatorTypeEnum;
import org.javaup.enums.DocumentTaskEventTypeEnum;
import org.javaup.enums.DocumentTaskStageEnum;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@AllArgsConstructor
public class DocumentGraphRagBuildCheckpointServiceImpl implements GraphRagBuildCheckpointService {

    private static final String ROOT_KEY = "graphRagBuild";
    private static final int ERROR_MESSAGE_LIMIT = 1000;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final TypeReference<LinkedHashMap<String, Object>> EXT_JSON_TYPE = new TypeReference<>() {
    };

    private final SuperAgentDocumentTaskMapper taskMapper;

    private final DocumentTaskLogService taskLogService;

    private final ObjectMapper objectMapper;

    @Override
    public void markRunning(Long documentId,
                            Long taskId,
                            String stage,
                            int attempt,
                            int maxAttempts,
                            Map<String, Object> detail) {
        try {
            Map<String, Object> state = baseState("RUNNING", stage, attempt, maxAttempts);
            if (detail != null && !detail.isEmpty()) {
                state.putAll(detail);
            }
            updateTaskExtJson(taskId, state);
            saveLog(documentId, taskId, DocumentTaskEventTypeEnum.START, DocumentLogLevelEnum.INFO,
                "GraphRAG 构建 checkpoint 更新: " + stage, state);
        }
        catch (Exception exception) {
            log.warn("GraphRAG checkpoint RUNNING 更新失败: documentId={}, taskId={}, stage={}",
                documentId, taskId, stage, exception);
        }
    }

    @Override
    public void markSuccess(Long documentId,
                            Long taskId,
                            GraphRagBuildResult result,
                            int attempt,
                            int maxAttempts) {
        try {
            Map<String, Object> state = baseState("SUCCESS", "SUCCESS", attempt, maxAttempts);
            if (result != null) {
                state.put("entityCount", result.getEntityCount());
                state.put("relationCount", result.getRelationCount());
                state.put("evidenceCount", result.getEvidenceCount());
                state.put("communityCount", result.getCommunityCount());
            }
            updateTaskExtJson(taskId, state);
            saveLog(documentId, taskId, DocumentTaskEventTypeEnum.COMPLETE, DocumentLogLevelEnum.INFO,
                "GraphRAG 构建完成。", state);
        }
        catch (Exception exception) {
            log.warn("GraphRAG checkpoint SUCCESS 更新失败: documentId={}, taskId={}", documentId, taskId, exception);
        }
    }

    @Override
    public void markRetry(Long documentId,
                          Long taskId,
                          String stage,
                          int attempt,
                          int maxAttempts,
                          long backoffMillis,
                          Throwable exception) {
        try {
            Map<String, Object> state = baseState("RUNNING", "RETRY_WAIT", attempt, maxAttempts);
            state.put("failedStage", stage);
            state.put("nextAttempt", attempt + 1);
            state.put("backoffMillis", backoffMillis);
            state.put("errorType", exception == null ? null : exception.getClass().getName());
            state.put("errorMessage", limit(exception == null ? null : exception.getMessage(), ERROR_MESSAGE_LIMIT));
            updateTaskExtJson(taskId, state);
            saveLog(documentId, taskId, DocumentTaskEventTypeEnum.FAILED, DocumentLogLevelEnum.WARN,
                "GraphRAG 构建尝试失败，准备重试: " + stage, state);
        }
        catch (Exception checkpointException) {
            log.warn("GraphRAG checkpoint RETRY 更新失败: documentId={}, taskId={}, stage={}",
                documentId, taskId, stage, checkpointException);
        }
    }

    @Override
    public void markRejected(Long documentId,
                             Long taskId,
                             String stage,
                             int maxAttempts,
                             String message,
                             Map<String, Object> detail) {
        try {
            Map<String, Object> state = baseState("REJECTED", stage, 0, maxAttempts);
            state.put("message", message);
            if (detail != null && !detail.isEmpty()) {
                state.putAll(detail);
            }
            saveLog(documentId, taskId, DocumentTaskEventTypeEnum.FAILED, DocumentLogLevelEnum.WARN,
                message, state);
        }
        catch (Exception exception) {
            log.warn("GraphRAG checkpoint REJECTED 记录失败: documentId={}, taskId={}, stage={}",
                documentId, taskId, stage, exception);
        }
    }

    @Override
    public void markFailure(Long documentId,
                            Long taskId,
                            String stage,
                            int attempt,
                            int maxAttempts,
                            Throwable exception) {
        try {
            Map<String, Object> state = baseState("FAILED", stage, attempt, maxAttempts);
            state.put("errorType", exception == null ? null : exception.getClass().getName());
            state.put("errorMessage", limit(exception == null ? null : exception.getMessage(), ERROR_MESSAGE_LIMIT));
            updateTaskExtJson(taskId, state);
            saveLog(documentId, taskId, DocumentTaskEventTypeEnum.FAILED, DocumentLogLevelEnum.ERROR,
                "GraphRAG 构建失败: " + stage, state);
        }
        catch (Exception checkpointException) {
            log.warn("GraphRAG checkpoint FAILED 更新失败: documentId={}, taskId={}, stage={}",
                documentId, taskId, stage, checkpointException);
        }
    }

    private Map<String, Object> baseState(String status, String stage, int attempt, int maxAttempts) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("status", status);
        state.put("stage", stage);
        state.put("attempt", attempt);
        state.put("maxAttempts", maxAttempts);
        state.put("lastCheckpointTime", LocalDateTime.now().format(TIME_FORMATTER));
        return state;
    }

    private void updateTaskExtJson(Long taskId, Map<String, Object> graphRagState) throws JsonProcessingException {
        if (taskId == null) {
            return;
        }
        SuperAgentDocumentTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        Map<String, Object> extJson = readExtJson(task.getExtJson());
        extJson.put(ROOT_KEY, graphRagState);
        SuperAgentDocumentTask update = new SuperAgentDocumentTask();
        update.setExtJson(objectMapper.writeValueAsString(extJson));
        taskMapper.update(update, new LambdaUpdateWrapper<SuperAgentDocumentTask>()
            .eq(SuperAgentDocumentTask::getId, taskId));
    }

    private Map<String, Object> readExtJson(String extJson) {
        if (StrUtil.isBlank(extJson)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(extJson, EXT_JSON_TYPE);
        }
        catch (Exception exception) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("legacyExtJson", extJson);
            return result;
        }
    }

    private void saveLog(Long documentId,
                         Long taskId,
                         DocumentTaskEventTypeEnum eventType,
                         DocumentLogLevelEnum logLevel,
                         String content,
                         Map<String, Object> detail) {
        taskLogService.saveLog(taskId,
            documentId,
            DocumentTaskStageEnum.VECTORIZE.getCode(),
            eventType.getCode(),
            logLevel.getCode(),
            DocumentOperatorTypeEnum.SYSTEM.getCode(),
            null,
            content,
            detail);
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
