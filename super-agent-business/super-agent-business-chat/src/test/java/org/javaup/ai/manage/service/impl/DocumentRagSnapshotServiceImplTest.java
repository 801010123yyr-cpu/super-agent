package org.javaup.ai.manage.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.manage.data.SuperAgentDocumentTask;
import org.javaup.ai.manage.data.SuperAgentDocumentTaskLog;
import org.javaup.ai.manage.mapper.SuperAgentDocumentBlockMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentChunkMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentParentBlockMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentStructureNodeMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTableCellMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTableColumnMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTableMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTableRowMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTaskLogMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTaskMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgCommunityMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgEntityMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgRelationMapper;
import org.javaup.ai.manage.mapper.SuperAgentRaptorNodeMapper;
import org.javaup.ai.manage.service.GraphRagQualityService;
import org.javaup.ai.manage.service.RaptorQualityService;
import org.javaup.ai.manage.vo.DocumentRagSnapshotVo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentRagSnapshotServiceImplTest {

    @Test
    void parserTraceReadsTaskExtJsonBeforeTaskLogs() throws Exception {
        SuperAgentDocumentTask task = new SuperAgentDocumentTask();
        task.setId(20L);
        task.setExtJson("""
            {
              "parserTraceMetadata": {
                "providerName": "aliyun_docmind",
                "jobId": "job-from-ext",
                "pageCount": 2,
                "blockCount": 5,
                "rawLayoutCount": 6,
                "bboxBlockCoverage": 0.8,
                "tableCellBboxCoverage": 0.5,
                "blockTypeCounts": {"TEXT": 3, "TABLE": 2},
                "warnings": ["bbox missing"]
              }
            }
            """);
        SuperAgentDocumentTaskLog log = new SuperAgentDocumentTaskLog();
        log.setDetailJson("""
            {
              "parserTraceMetadata": {
                "jobId": "job-from-log",
                "pageCount": 9
              }
            }
            """);

        DocumentRagSnapshotVo.ParserTraceItem trace = invokeBuildParserTrace(
            service(task, log),
            20L
        );

        assertThat(trace).isNotNull();
        assertThat(trace.getProviderName()).isEqualTo("aliyun_docmind");
        assertThat(trace.getJobId()).isEqualTo("job-from-ext");
        assertThat(trace.getPageCount()).isEqualTo(2);
        assertThat(trace.getRawLayoutCount()).isEqualTo(6);
        assertThat(trace.getBboxBlockCoverage()).isEqualTo(0.8D);
        assertThat(trace.getTableCellBboxCoverage()).isEqualTo(0.5D);
        assertThat(trace.getBlockTypeCounts()).containsEntry("TABLE", 2);
        assertThat(trace.getWarnings()).containsExactly("bbox missing");
    }

    @Test
    void parserTraceFallsBackToParseCompleteLogForOldTasks() throws Exception {
        SuperAgentDocumentTask task = new SuperAgentDocumentTask();
        task.setId(21L);
        task.setExtJson("{}");
        SuperAgentDocumentTaskLog log = new SuperAgentDocumentTaskLog();
        log.setDetailJson("""
            {
              "parserTraceMetadata": {
                "providerName": "aliyun_docmind",
                "jobId": "job-from-log",
                "pageCount": 1,
                "blockCount": 3,
                "warningCount": 1
              }
            }
            """);

        DocumentRagSnapshotVo.ParserTraceItem trace = invokeBuildParserTrace(
            service(task, log),
            21L
        );

        assertThat(trace).isNotNull();
        assertThat(trace.getJobId()).isEqualTo("job-from-log");
        assertThat(trace.getPageCount()).isEqualTo(1);
        assertThat(trace.getBlockCount()).isEqualTo(3);
        assertThat(trace.getWarningCount()).isEqualTo(1);
    }

    private static DocumentRagSnapshotVo.ParserTraceItem invokeBuildParserTrace(DocumentRagSnapshotServiceImpl service,
                                                                                Long taskId) throws Exception {
        Method method = DocumentRagSnapshotServiceImpl.class.getDeclaredMethod("buildParserTrace", Long.class);
        method.setAccessible(true);
        return (DocumentRagSnapshotVo.ParserTraceItem) method.invoke(service, taskId);
    }

    private static DocumentRagSnapshotServiceImpl service(SuperAgentDocumentTask task,
                                                          SuperAgentDocumentTaskLog log) {
        return new DocumentRagSnapshotServiceImpl(
            mapper(SuperAgentDocumentMapper.class),
            taskMapper(task),
            taskLogMapper(log),
            mapper(SuperAgentDocumentBlockMapper.class),
            mapper(SuperAgentDocumentStructureNodeMapper.class),
            mapper(SuperAgentDocumentParentBlockMapper.class),
            mapper(SuperAgentDocumentChunkMapper.class),
            mapper(SuperAgentDocumentTableMapper.class),
            mapper(SuperAgentDocumentTableColumnMapper.class),
            mapper(SuperAgentDocumentTableRowMapper.class),
            mapper(SuperAgentDocumentTableCellMapper.class),
            mapper(SuperAgentKgEntityMapper.class),
            mapper(SuperAgentKgRelationMapper.class),
            mapper(SuperAgentKgCommunityMapper.class),
            mapper(SuperAgentRaptorNodeMapper.class),
            graphRagQualityService(),
            raptorQualityService(),
            new ChatRagProperties(),
            new ObjectMapper()
        );
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
                return proxyDefaultValue(proxy, method.getName(), method.getReturnType());
            }
        );
    }

    @SuppressWarnings("unchecked")
    private static SuperAgentDocumentTaskLogMapper taskLogMapper(SuperAgentDocumentTaskLog log) {
        return (SuperAgentDocumentTaskLogMapper) Proxy.newProxyInstance(
            SuperAgentDocumentTaskLogMapper.class.getClassLoader(),
            new Class<?>[]{SuperAgentDocumentTaskLogMapper.class},
            (proxy, method, args) -> {
                if ("selectOne".equals(method.getName())) {
                    return log;
                }
                return proxyDefaultValue(proxy, method.getName(), method.getReturnType());
            }
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> T mapper(Class<T> mapperType) {
        return (T) Proxy.newProxyInstance(
            mapperType.getClassLoader(),
            new Class<?>[]{mapperType},
            (proxy, method, args) -> proxyDefaultValue(proxy, method.getName(), method.getReturnType())
        );
    }

    private static GraphRagQualityService graphRagQualityService() {
        return (documentId, taskId) -> null;
    }

    private static RaptorQualityService raptorQualityService() {
        return new RaptorQualityService() {
            @Override
            public org.javaup.ai.manage.model.raptor.RaptorQualityReport evaluate(Long documentId, Long taskId) {
                return null;
            }

            @Override
            public org.javaup.ai.manage.model.raptor.RaptorQualityReport evaluate(
                List<org.javaup.ai.manage.data.SuperAgentRaptorNode> nodes,
                double configuredFloor) {
                return null;
            }

            @Override
            public org.javaup.ai.manage.model.raptor.RaptorQualityReport evaluatePythonNodes(
                List<org.javaup.ai.ragtools.model.RagToolsRaptorBuildResponse.Node> nodes,
                double configuredFloor) {
                return null;
            }
        };
    }

    private static Object proxyDefaultValue(Object proxy, String methodName, Class<?> returnType) {
        if ("toString".equals(methodName)) {
            return "test-proxy";
        }
        if ("hashCode".equals(methodName)) {
            return System.identityHashCode(proxy);
        }
        if ("equals".equals(methodName)) {
            return false;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class || returnType == long.class || returnType == short.class || returnType == byte.class) {
            return 0;
        }
        if (returnType == double.class || returnType == float.class) {
            return 0D;
        }
        return null;
    }
}
