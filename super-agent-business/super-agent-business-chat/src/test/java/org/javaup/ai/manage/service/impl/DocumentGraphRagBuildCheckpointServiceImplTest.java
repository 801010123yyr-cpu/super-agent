package org.javaup.ai.manage.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.manage.data.SuperAgentDocumentTask;
import org.javaup.ai.manage.data.SuperAgentDocumentTaskLog;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTaskMapper;
import org.javaup.ai.manage.model.graph.GraphRagBuildResult;
import org.javaup.ai.manage.service.DocumentIndexBuildProgressCacheService;
import org.javaup.ai.manage.service.DocumentTaskLogService;
import org.javaup.ai.manage.vo.DocumentIndexBuildProgressVo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentGraphRagBuildCheckpointServiceImplTest {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Test
    void markRunningAndSuccessUpdateGraphRagBuildStateInsideExtJson() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SuperAgentDocumentTask task = task("{\"graph_index_status\":\"SUCCESS\"}");
        RecordingDocumentTaskLogService logService = new RecordingDocumentTaskLogService();

        DocumentGraphRagBuildCheckpointServiceImpl service = new DocumentGraphRagBuildCheckpointServiceImpl(
            taskMapper(task),
            documentMapper(),
            logService,
            new NoopProgressCacheService(),
            objectMapper
        );

        service.markRunning(10L, 20L, "EXTRACTING", 1, 2, Map.of(
            "chunkCount", 3,
            "extractorMetadata", Map.of(
                "extractorSourceCounts", Map.of("rule", 12, "ner", 4),
                "extractorLayers", List.of(Map.of("name", "ner.model", "status", "disabled"))
            )
        ));

        Map<String, Object> runningExtJson = objectMapper.readValue(task.getExtJson(), MAP_TYPE);
        assertThat(runningExtJson).containsEntry("graph_index_status", "SUCCESS");
        Map<String, Object> runningState = state(runningExtJson);
        assertThat(runningState)
            .containsEntry("status", "RUNNING")
            .containsEntry("stage", "EXTRACTING")
            .containsEntry("attempt", 1)
            .containsEntry("maxAttempts", 2)
            .containsEntry("chunkCount", 3);

        service.markSuccess(10L, 20L, GraphRagBuildResult.builder()
            .entityCount(4)
            .relationCount(5)
            .evidenceCount(6)
            .communityCount(7)
            .build(), 2, 2);

        Map<String, Object> successExtJson = objectMapper.readValue(task.getExtJson(), MAP_TYPE);
        Map<String, Object> successState = state(successExtJson);
        assertThat(successState)
            .containsEntry("status", "SUCCESS")
            .containsEntry("stage", "SUCCESS")
            .containsEntry("attempt", 2)
            .containsEntry("entityCount", 4)
            .containsEntry("relationCount", 5)
            .containsEntry("evidenceCount", 6)
            .containsEntry("communityCount", 7);
        assertThat(String.valueOf(successState.get("extractorMetadata")))
            .contains("extractorSourceCounts")
            .contains("ner.model");
        assertThat(logService.logs()).hasSize(2);
    }

    @Test
    void markRejectedOnlyWritesLogAndDoesNotOverrideExtJson() {
        SuperAgentDocumentTask task = task("{\"graphRagBuild\":{\"status\":\"RUNNING\",\"stage\":\"EXTRACTING\"}}");
        String originalExtJson = task.getExtJson();
        RecordingDocumentTaskLogService logService = new RecordingDocumentTaskLogService();

        DocumentGraphRagBuildCheckpointServiceImpl service = new DocumentGraphRagBuildCheckpointServiceImpl(
            taskMapper(task),
            documentMapper(),
            logService,
            new NoopProgressCacheService(),
            new ObjectMapper()
        );

        service.markRejected(10L, 20L, "LOCK_CONFLICT", 2, "GraphRAG 构建租约已被占用，请稍后重试。", Map.of(
            "leaseKey", "super-agent:document:graph-rag:build:20"
        ));

        assertThat(task.getExtJson()).isEqualTo(originalExtJson);
        assertThat(logService.logs()).hasSize(1);
        assertThat(logService.logs().get(0).content())
            .contains("租约已被占用");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> state(Map<String, Object> extJson) {
        return (Map<String, Object>) extJson.get("graphRagBuild");
    }

    private static SuperAgentDocumentTask task(String extJson) {
        SuperAgentDocumentTask task = new SuperAgentDocumentTask();
        task.setId(20L);
        task.setDocumentId(10L);
        task.setExtJson(extJson);
        return task;
    }

    @SuppressWarnings("unchecked")
    private static SuperAgentDocumentTaskMapper taskMapper(SuperAgentDocumentTask task) {
        return (SuperAgentDocumentTaskMapper) Proxy.newProxyInstance(
            SuperAgentDocumentTaskMapper.class.getClassLoader(),
            new Class<?>[]{SuperAgentDocumentTaskMapper.class},
            (proxy, method, args) -> {
                if ("selectById".equals(method.getName())) {
                    return task;
                }
                if ("update".equals(method.getName())) {
                    SuperAgentDocumentTask update = (SuperAgentDocumentTask) args[0];
                    task.setExtJson(update.getExtJson());
                    assertThat(args[1]).isInstanceOf(Wrapper.class);
                    return 1;
                }
                if ("toString".equals(method.getName())) {
                    return "SuperAgentDocumentTaskMapperProxy";
                }
                if ("hashCode".equals(method.getName())) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(method.getName())) {
                    return proxy == args[0];
                }
                return defaultValue(method.getReturnType());
            }
        );
    }

    @SuppressWarnings("unchecked")
    private static SuperAgentDocumentMapper documentMapper() {
        return (SuperAgentDocumentMapper) Proxy.newProxyInstance(
            SuperAgentDocumentMapper.class.getClassLoader(),
            new Class<?>[]{SuperAgentDocumentMapper.class},
            (proxy, method, args) -> {
                if ("selectById".equals(method.getName())) {
                    return null;
                }
                if ("toString".equals(method.getName())) {
                    return "SuperAgentDocumentMapperProxy";
                }
                if ("hashCode".equals(method.getName())) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(method.getName())) {
                    return proxy == args[0];
                }
                return defaultValue(method.getReturnType());
            }
        );
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == void.class) {
            return null;
        }
        return 0;
    }

    private static final class RecordingDocumentTaskLogService implements DocumentTaskLogService {

        private final List<LogRecord> logs = new ArrayList<>();

        @Override
        public SuperAgentDocumentTaskLog saveLog(Long taskId,
                                                 Long documentId,
                                                 Integer stageType,
                                                 Integer eventType,
                                                 Integer logLevel,
                                                 Integer operatorType,
                                                 Long operatorId,
                                                 String content,
                                                 Object detail) {
            logs.add(new LogRecord(taskId, documentId, stageType, eventType, logLevel, content, detail));
            SuperAgentDocumentTaskLog log = new SuperAgentDocumentTaskLog();
            log.setTaskId(taskId);
            log.setDocumentId(documentId);
            log.setContent(content);
            return log;
        }

        private List<LogRecord> logs() {
            return logs;
        }
    }

    private static final class NoopProgressCacheService implements DocumentIndexBuildProgressCacheService {

        @Override
        public DocumentIndexBuildProgressVo get(Long taskId) {
            return null;
        }

        @Override
        public void init(org.javaup.ai.manage.data.SuperAgentDocument document, SuperAgentDocumentTask task) {
        }

        @Override
        public void update(org.javaup.ai.manage.data.SuperAgentDocument document,
                           SuperAgentDocumentTask task,
                           SuperAgentDocumentTaskLog latestLog) {
        }

        @Override
        public void update(org.javaup.ai.manage.data.SuperAgentDocument document, SuperAgentDocumentTask task) {
        }
    }

    private record LogRecord(Long taskId,
                             Long documentId,
                             Integer stageType,
                             Integer eventType,
                             Integer logLevel,
                             String content,
                             Object detail) {
    }
}
