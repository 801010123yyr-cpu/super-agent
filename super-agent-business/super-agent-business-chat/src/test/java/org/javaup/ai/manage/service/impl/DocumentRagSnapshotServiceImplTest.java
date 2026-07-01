package org.javaup.ai.manage.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.manage.data.SuperAgentDocumentBlock;
import org.javaup.ai.manage.data.SuperAgentDocumentParseArtifact;
import org.javaup.ai.manage.data.SuperAgentDocumentTable;
import org.javaup.ai.manage.data.SuperAgentDocumentTask;
import org.javaup.ai.manage.data.SuperAgentDocumentTaskLog;
import org.javaup.ai.manage.mapper.SuperAgentDocumentBlockMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentChunkMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentParentBlockMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentParseArtifactMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentStructureNodeMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTableCellMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTableColumnMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTableMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTableRowMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTaskLogMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTaskMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgCommunityMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgEntityMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgEvidenceMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgRelationMapper;
import org.javaup.ai.manage.mapper.SuperAgentRaptorNodeMapper;
import org.javaup.ai.manage.service.GraphRagQualityService;
import org.javaup.ai.manage.service.DocumentStorageService;
import org.javaup.ai.manage.service.RaptorQualityService;
import org.javaup.ai.manage.support.StoredObjectInfo;
import org.javaup.ai.manage.support.StoredObjectMetadata;
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

    @Test
    void pageOverlayCombinesPageImageBlockAndTableBboxes() throws Exception {
        SuperAgentDocumentBlock titleBlock = new SuperAgentDocumentBlock();
        titleBlock.setId(101L);
        titleBlock.setBlockNo(1);
        titleBlock.setBlockType("TITLE");
        titleBlock.setPageNo(0);
        titleBlock.setSectionPath("一、测试");
        titleBlock.setBboxJson("{\"x0\":10,\"y0\":20,\"x1\":110,\"y1\":60}");
        titleBlock.setText("测试标题");
        titleBlock.setStatus(1);

        SuperAgentDocumentBlock tableBlock = new SuperAgentDocumentBlock();
        tableBlock.setId(102L);
        tableBlock.setBlockNo(2);
        tableBlock.setBlockType("TABLE");
        tableBlock.setPageNo(0);
        tableBlock.setBboxJson("{\"x0\":20,\"y0\":80,\"x1\":220,\"y1\":160}");
        tableBlock.setTableHtml("<table><tr><td>P0</td></tr></table>");
        tableBlock.setStatus(1);

        SuperAgentDocumentTable table = new SuperAgentDocumentTable();
        table.setId(201L);
        table.setTableNo(1);
        table.setPageNo(0);
        table.setSectionPath("四、响应等级表");
        table.setBboxJson("{\"x0\":20,\"y0\":80,\"x1\":220,\"y1\":160}");
        table.setTitle("响应等级表");
        table.setStatus(1);

        SuperAgentDocumentParseArtifact pageImage = new SuperAgentDocumentParseArtifact();
        pageImage.setId(301L);
        pageImage.setArtifactType("PAGE_IMAGE");
        pageImage.setObjectName("rag/parse-artifact/10/20/sample.page-1.png");
        pageImage.setStatus(1);

        DocumentRagSnapshotServiceImpl service = service(
            new SuperAgentDocumentTask(),
            null,
            List.of(titleBlock, tableBlock),
            List.of(table),
            List.of(pageImage)
        );

        DocumentRagSnapshotVo.PageOverlayItem page = invokeListPageOverlays(service, 10L, 20L).get(0);

        assertThat(page.getPageNo()).isZero();
        assertThat(page.getDisplayPageNo()).isEqualTo("1");
        assertThat(page.getPageImageArtifactId()).isEqualTo(301L);
        assertThat(page.getPageWidth()).isEqualTo(220D);
        assertThat(page.getPageHeight()).isEqualTo(160D);
        assertThat(page.getOverlays()).hasSize(3);
        assertThat(page.getOverlays())
            .extracting(DocumentRagSnapshotVo.PageOverlayRegionItem::getOverlayId)
            .contains("block-101", "block-102", "table-201");
        DocumentRagSnapshotVo.PageOverlayRegionItem tableRegion = page.getOverlays().stream()
            .filter(item -> "table-201".equals(item.getOverlayId()))
            .findFirst()
            .orElseThrow();
        assertThat(tableRegion.getSourceType()).isEqualTo("TABLE");
        assertThat(tableRegion.getLeftRatio()).isGreaterThan(0D);
        assertThat(tableRegion.getWidthRatio()).isGreaterThan(0D);
    }

    private static DocumentRagSnapshotVo.ParserTraceItem invokeBuildParserTrace(DocumentRagSnapshotServiceImpl service,
                                                                                Long taskId) throws Exception {
        Method method = DocumentRagSnapshotServiceImpl.class.getDeclaredMethod("buildParserTrace", Long.class);
        method.setAccessible(true);
        return (DocumentRagSnapshotVo.ParserTraceItem) method.invoke(service, taskId);
    }

    @SuppressWarnings("unchecked")
    private static List<DocumentRagSnapshotVo.PageOverlayItem> invokeListPageOverlays(DocumentRagSnapshotServiceImpl service,
                                                                                      Long documentId,
                                                                                      Long taskId) throws Exception {
        Method method = DocumentRagSnapshotServiceImpl.class.getDeclaredMethod("listPageOverlays", Long.class, Long.class);
        method.setAccessible(true);
        return (List<DocumentRagSnapshotVo.PageOverlayItem>) method.invoke(service, documentId, taskId);
    }

    private static DocumentRagSnapshotServiceImpl service(SuperAgentDocumentTask task,
                                                          SuperAgentDocumentTaskLog log) {
        return service(task, log, List.of(), List.of(), List.of());
    }

    private static DocumentRagSnapshotServiceImpl service(SuperAgentDocumentTask task,
                                                          SuperAgentDocumentTaskLog log,
                                                          List<SuperAgentDocumentBlock> blocks,
                                                          List<SuperAgentDocumentTable> tables,
                                                          List<SuperAgentDocumentParseArtifact> artifacts) {
        return new DocumentRagSnapshotServiceImpl(
            mapper(SuperAgentDocumentMapper.class),
            taskMapper(task),
            taskLogMapper(log),
            blockMapper(blocks),
            parseArtifactMapper(artifacts),
            mapper(SuperAgentDocumentStructureNodeMapper.class),
            mapper(SuperAgentDocumentParentBlockMapper.class),
            mapper(SuperAgentDocumentChunkMapper.class),
            tableMapper(tables),
            mapper(SuperAgentDocumentTableColumnMapper.class),
            mapper(SuperAgentDocumentTableRowMapper.class),
            mapper(SuperAgentDocumentTableCellMapper.class),
            mapper(SuperAgentKgEntityMapper.class),
            mapper(SuperAgentKgRelationMapper.class),
            mapper(SuperAgentKgCommunityMapper.class),
            mapper(SuperAgentKgEvidenceMapper.class),
            mapper(SuperAgentRaptorNodeMapper.class),
            graphRagQualityService(),
            raptorQualityService(),
            documentStorageService(),
            new ChatRagProperties(),
            new ObjectMapper()
        );
    }

    @SuppressWarnings("unchecked")
    private static SuperAgentDocumentBlockMapper blockMapper(List<SuperAgentDocumentBlock> blocks) {
        return (SuperAgentDocumentBlockMapper) Proxy.newProxyInstance(
            SuperAgentDocumentBlockMapper.class.getClassLoader(),
            new Class<?>[]{SuperAgentDocumentBlockMapper.class},
            (proxy, method, args) -> {
                if ("selectList".equals(method.getName())) {
                    return blocks;
                }
                return proxyDefaultValue(proxy, method.getName(), method.getReturnType());
            }
        );
    }

    @SuppressWarnings("unchecked")
    private static SuperAgentDocumentTableMapper tableMapper(List<SuperAgentDocumentTable> tables) {
        return (SuperAgentDocumentTableMapper) Proxy.newProxyInstance(
            SuperAgentDocumentTableMapper.class.getClassLoader(),
            new Class<?>[]{SuperAgentDocumentTableMapper.class},
            (proxy, method, args) -> {
                if ("selectList".equals(method.getName())) {
                    return tables;
                }
                return proxyDefaultValue(proxy, method.getName(), method.getReturnType());
            }
        );
    }

    @SuppressWarnings("unchecked")
    private static SuperAgentDocumentParseArtifactMapper parseArtifactMapper(List<SuperAgentDocumentParseArtifact> artifacts) {
        return (SuperAgentDocumentParseArtifactMapper) Proxy.newProxyInstance(
            SuperAgentDocumentParseArtifactMapper.class.getClassLoader(),
            new Class<?>[]{SuperAgentDocumentParseArtifactMapper.class},
            (proxy, method, args) -> {
                if ("selectList".equals(method.getName())) {
                    return artifacts;
                }
                return proxyDefaultValue(proxy, method.getName(), method.getReturnType());
            }
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

    private static DocumentStorageService documentStorageService() {
        return new DocumentStorageService() {
            @Override
            public StoredObjectInfo uploadOriginalFile(Long documentId, String originalFileName, byte[] bytes, String contentType) {
                return null;
            }

            @Override
            public String uploadParsedText(Long documentId, String parsedText) {
                return "";
            }

            @Override
            public String uploadParseArtifact(Long documentId, Long taskId, String fileName, byte[] bytes, String contentType) {
                return "";
            }

            @Override
            public byte[] downloadObject(String objectName) {
                return new byte[0];
            }

            @Override
            public String downloadText(String objectName) {
                return "";
            }

            @Override
            public StoredObjectMetadata getObjectMetadata(String objectName) {
                return null;
            }

            @Override
            public void deleteObjects(List<String> objectNameList) {
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
