package org.javaup.ai.manage.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baidu.fsg.uid.UidGenerator;
import org.javaup.ai.manage.data.SuperAgentDocumentBlock;
import org.javaup.ai.manage.data.SuperAgentDocumentParseArtifact;
import org.javaup.ai.manage.mapper.SuperAgentDocumentBlockMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentParseArtifactMapper;
import org.javaup.ai.manage.service.DocumentStorageService;
import org.javaup.ai.manage.service.DocumentTableStructureService;
import org.javaup.ai.manage.support.StoredObjectInfo;
import org.javaup.enums.BusinessStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentParseArtifactServiceImplTest {

    @Test
    void saveBlocksPreservesLongImageCaptionForOcrFigureBlocks() {
        InsertCollector blockCollector = new InsertCollector();
        DocumentParseArtifactServiceImpl service = new DocumentParseArtifactServiceImpl(
            artifactMapper(),
            blockMapper(blockCollector),
            storageService(),
            tableStructureService(),
            uidGenerator()
        );
        String longCaption = "Document Mind figure OCR ".repeat(120);

        SuperAgentDocumentBlock block = new SuperAgentDocumentBlock();
        block.setBlockNo(1);
        block.setBlockType("FIGURE");
        block.setImageCaption(longCaption);

        service.saveBlocks(10L, 20L, List.of(block));

        assertThat(blockCollector.items()).hasSize(1);
        SuperAgentDocumentBlock savedBlock = (SuperAgentDocumentBlock) blockCollector.items().get(0);
        assertThat(savedBlock.getDocumentId()).isEqualTo(10L);
        assertThat(savedBlock.getTaskId()).isEqualTo(20L);
        assertThat(savedBlock.getStatus()).isEqualTo(BusinessStatus.YES.getCode());
        assertThat(savedBlock.getImageCaption()).isEqualTo(longCaption);
        assertThat(savedBlock.getImageCaption().length()).isGreaterThan(1000);
    }

    @SuppressWarnings("unchecked")
    private static SuperAgentDocumentParseArtifactMapper artifactMapper() {
        return (SuperAgentDocumentParseArtifactMapper) Proxy.newProxyInstance(
            SuperAgentDocumentParseArtifactMapper.class.getClassLoader(),
            new Class<?>[]{SuperAgentDocumentParseArtifactMapper.class},
            (proxy, method, args) -> {
                if ("insert".equals(method.getName())) {
                    return 1;
                }
                if ("selectList".equals(method.getName())) {
                    return List.<SuperAgentDocumentParseArtifact>of();
                }
                if ("delete".equals(method.getName())) {
                    return 1;
                }
                return proxyDefaultValue(proxy, method.getName(), args, method.getReturnType());
            }
        );
    }

    @SuppressWarnings("unchecked")
    private static SuperAgentDocumentBlockMapper blockMapper(InsertCollector insertCollector) {
        return (SuperAgentDocumentBlockMapper) Proxy.newProxyInstance(
            SuperAgentDocumentBlockMapper.class.getClassLoader(),
            new Class<?>[]{SuperAgentDocumentBlockMapper.class},
            (proxy, method, args) -> {
                if ("insert".equals(method.getName())) {
                    insertCollector.add(args[0]);
                    return 1;
                }
                if ("selectList".equals(method.getName())) {
                    return List.<SuperAgentDocumentBlock>of();
                }
                if ("delete".equals(method.getName())) {
                    return 1;
                }
                return proxyDefaultValue(proxy, method.getName(), args, method.getReturnType());
            }
        );
    }

    private static DocumentStorageService storageService() {
        return new DocumentStorageService() {
            @Override
            public StoredObjectInfo uploadOriginalFile(Long documentId, String originalFileName, byte[] bytes, String contentType) {
                return null;
            }

            @Override
            public String uploadParsedText(Long documentId, String parsedText) {
                return null;
            }

            @Override
            public String uploadParseArtifact(Long documentId, Long taskId, String fileName, byte[] bytes, String contentType) {
                return null;
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
            public void deleteObjects(List<String> objectNameList) {
            }
        };
    }

    private static DocumentTableStructureService tableStructureService() {
        return new DocumentTableStructureService() {
            @Override
            public void replaceTaskTables(Long documentId, Long taskId, List<SuperAgentDocumentBlock> blockList) {
            }

            @Override
            public List<org.javaup.ai.manage.model.table.DocumentTableDescriptor> listTables(List<Long> documentIds, List<Long> taskIds) {
                return List.of();
            }

            @Override
            public org.javaup.ai.manage.model.table.DocumentTableQueryResult query(org.javaup.ai.manage.model.table.DocumentTableQuery query) {
                return null;
            }

            @Override
            public void deleteByTask(Long documentId, Long taskId) {
            }

            @Override
            public void deleteByDocumentId(Long documentId) {
            }
        };
    }

    private static UidGenerator uidGenerator() {
        AtomicLong sequence = new AtomicLong(1000L);
        return (UidGenerator) Proxy.newProxyInstance(
            UidGenerator.class.getClassLoader(),
            new Class<?>[]{UidGenerator.class},
            (proxy, method, args) -> {
                if ("getUid".equals(method.getName()) || "getId".equals(method.getName())) {
                    return sequence.incrementAndGet();
                }
                if ("parseUid".equals(method.getName())) {
                    return String.valueOf(args[0]);
                }
                return proxyDefaultValue(proxy, method.getName(), args, method.getReturnType());
            }
        );
    }

    private static Object proxyDefaultValue(Object proxy, String methodName, Object[] args, Class<?> returnType) {
        if ("toString".equals(methodName)) {
            return "Proxy";
        }
        if ("hashCode".equals(methodName)) {
            return System.identityHashCode(proxy);
        }
        if ("equals".equals(methodName)) {
            return proxy == args[0];
        }
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

    private static class InsertCollector {
        private final List<Object> items = new ArrayList<>();

        private void add(Object value) {
            items.add(value);
        }

        private List<Object> items() {
            return items;
        }
    }
}
