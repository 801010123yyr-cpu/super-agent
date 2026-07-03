package org.javaup.ai.chatagent.rag.retrieve.channel;

import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.service.DocumentTableQueryPlanner;
import org.javaup.ai.chatagent.rag.support.SearchReferenceMapper;
import org.javaup.ai.manage.data.SuperAgentDocumentBlock;
import org.javaup.ai.manage.model.DocumentRetrieveRequest;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.ai.manage.model.table.DocumentTableDescriptor;
import org.javaup.ai.manage.model.table.DocumentTableQuery;
import org.javaup.ai.manage.model.table.DocumentTableQueryResult;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.ai.manage.service.DocumentTableStructureService;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TableRetrievalChannelTest {

    @Test
    void tableEvidenceLocationFlowsIntoSearchReference() {
        TableRetrievalChannel channel = new TableRetrievalChannel(
            new StaticDocumentTableStructureService(),
            new DocumentTableQueryPlanner(),
            new StaticDocumentKnowledgeService(),
            new ChatRagProperties()
        );

        RetrievalChannelResult result = channel.retrieve(
            "部门为研发部的报销金额合计是多少",
            ConversationExecutionPlan.builder().selectedDocumentId(10L).selectedTaskId(20L).build()
        );

        assertThat(result.getDocuments()).hasSize(1);
        Document document = result.getDocuments().get(0);
        assertThat(document.getMetadata())
            .containsEntry(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "DOCUMENT_TABLE")
            .containsEntry(DocumentKnowledgeMetadataKeys.TABLE_ID, 100L)
            .containsEntry(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_ROW_NOS, List.of(1))
            .containsEntry(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_COLUMN_NAMES, List.of("部门", "报销金额"))
            .containsEntry(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_CELL_COORDINATES, List.of("A2", "B2"))
            .containsEntry(DocumentKnowledgeMetadataKeys.TABLE_EVIDENCE_CELL_BBOX_JSONS, List.of(
                "{\"x0\":10,\"y0\":20,\"x1\":40,\"y1\":32}",
                "{\"x0\":42,\"y0\":20,\"x1\":80,\"y1\":32}"
            ));

        SearchReference reference = SearchReferenceMapper.fromDocument(document, 1, "部门为研发部的报销金额合计是多少", 3);

        assertThat(reference.getSourceType()).isEqualTo("DOCUMENT_TABLE");
        assertThat(reference.getTableId()).isEqualTo(100L);
        assertThat(reference.getTableMatchedRowCount()).isEqualTo(1);
        assertThat(reference.getTableEvidenceRowIds()).containsExactly(301L);
        assertThat(reference.getTableEvidenceRowNos()).containsExactly(1);
        assertThat(reference.getTableEvidenceColumnIds()).containsExactly(201L, 202L);
        assertThat(reference.getTableEvidenceColumnNos()).containsExactly(1, 2);
        assertThat(reference.getTableEvidenceColumnNames()).containsExactly("部门", "报销金额");
        assertThat(reference.getTableEvidenceCellIds()).containsExactly(401L, 402L);
        assertThat(reference.getTableEvidenceCellCoordinates()).containsExactly("A2", "B2");
        assertThat(reference.getTableEvidenceCellBboxJsons()).containsExactly(
            "{\"x0\":10,\"y0\":20,\"x1\":40,\"y1\":32}",
            "{\"x0\":42,\"y0\":20,\"x1\":80,\"y1\":32}"
        );
    }

    private static class StaticDocumentTableStructureService implements DocumentTableStructureService {

        @Override
        public void replaceTaskTables(Long documentId, Long taskId, List<SuperAgentDocumentBlock> blockList) {
        }

        @Override
        public List<DocumentTableDescriptor> listTables(List<Long> documentIds, List<Long> taskIds) {
            return List.of(DocumentTableDescriptor.builder()
                .tableId(100L)
                .documentId(10L)
                .taskId(20L)
                .tableNo(1)
                .title("报销明细表")
                .sectionPath("财务制度 / 报销明细")
                .columns(List.of(
                    column(1, "部门", "TEXT"),
                    column(2, "报销金额", "NUMBER"),
                    column(3, "月份", "TEXT")
                ))
                .build());
        }

        @Override
        public DocumentTableQueryResult query(DocumentTableQuery query) {
            assertThat(query.getOperation()).isEqualTo(DocumentTableQuery.Operation.SUM);
            assertThat(query.getMetricColumn()).isEqualTo("报销金额");
            assertThat(query.getFilters()).hasSize(1);
            return DocumentTableQueryResult.builder()
                .tableId(100L)
                .documentId(10L)
                .taskId(20L)
                .blockId(30L)
                .tableNo(1)
                .tableTitle("报销明细表")
                .sectionPath("财务制度 / 报销明细")
                .operation("SUM")
                .value(new BigDecimal("1200"))
                .matchedRowCount(1)
                .evidenceRowIds(List.of(301L))
                .evidenceRowNos(List.of(1))
                .evidenceColumnIds(List.of(201L, 202L))
                .evidenceColumnNos(List.of(1, 2))
                .evidenceColumnNames(List.of("部门", "报销金额"))
                .evidenceCellIds(List.of(401L, 402L))
                .evidenceCellCoordinates(List.of("A2", "B2"))
                .evidenceCellBboxJsons(List.of(
                    "{\"x0\":10,\"y0\":20,\"x1\":40,\"y1\":32}",
                    "{\"x0\":42,\"y0\":20,\"x1\":80,\"y1\":32}"
                ))
                .evidenceText("表格查询结果：SUM\n结果：1200\n命中行数：1\n行1: 部门=研发部；报销金额=1200")
                .build();
        }

        @Override
        public void deleteByTask(Long documentId, Long taskId) {
        }

        @Override
        public void deleteByDocumentId(Long documentId) {
        }

        private static DocumentTableDescriptor.Column column(int columnNo, String name, String valueType) {
            return DocumentTableDescriptor.Column.builder()
                .columnNo(columnNo)
                .columnName(name)
                .normalizedName(name)
                .valueType(valueType)
                .build();
        }
    }

    private static class StaticDocumentKnowledgeService implements DocumentKnowledgeService {

        @Override
        public List<KnowledgeDocumentDescriptor> listRetrievableDocuments() {
            return List.of(new KnowledgeDocumentDescriptor(
                10L,
                "费用报销制度.xlsx",
                20L,
                1L,
                "test-kb",
                "测试知识库"
            ));
        }

        @Override
        public List<KnowledgeDocumentDescriptor> listRetrievableDocumentsByKnowledgeBaseIds(java.util.Collection<Long> knowledgeBaseIds) {
            return listRetrievableDocuments();
        }

        @Override
        public List<Document> vectorSearch(DocumentRetrieveRequest request) {
            return List.of();
        }

        @Override
        public List<Document> keywordSearch(DocumentRetrieveRequest request) {
            return List.of();
        }

        @Override
        public List<Document> elevateToParentBlocks(List<Document> childDocuments, int maxChars) {
            return childDocuments;
        }
    }
}
