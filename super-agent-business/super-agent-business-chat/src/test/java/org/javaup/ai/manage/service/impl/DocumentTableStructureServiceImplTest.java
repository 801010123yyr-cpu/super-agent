package org.javaup.ai.manage.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baidu.fsg.uid.UidGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.manage.data.SuperAgentDocumentBlock;
import org.javaup.ai.manage.data.SuperAgentDocumentTable;
import org.javaup.ai.manage.data.SuperAgentDocumentTableCell;
import org.javaup.ai.manage.data.SuperAgentDocumentTableColumn;
import org.javaup.ai.manage.data.SuperAgentDocumentTableRow;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTableCellMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTableColumnMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTableMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTableRowMapper;
import org.javaup.ai.manage.model.table.DocumentTableQuery;
import org.javaup.ai.manage.model.table.DocumentTableQueryResult;
import org.javaup.enums.BusinessStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTableStructureServiceImplTest {

    @Test
    void queryReturnsRowColumnAndCellEvidenceLocation() {
        SuperAgentDocumentTableMapper tableMapper = mapper(SuperAgentDocumentTableMapper.class, table(), List.of());
        SuperAgentDocumentTableColumnMapper columnMapper = mapper(SuperAgentDocumentTableColumnMapper.class, null, columns());
        SuperAgentDocumentTableRowMapper rowMapper = mapper(SuperAgentDocumentTableRowMapper.class, null, rows());
        SuperAgentDocumentTableCellMapper cellMapper = mapper(SuperAgentDocumentTableCellMapper.class, null, cells());

        DocumentTableStructureServiceImpl service = new DocumentTableStructureServiceImpl(
            tableMapper,
            columnMapper,
            rowMapper,
            cellMapper,
            new ObjectMapper(),
            null
        );

        DocumentTableQuery query = DocumentTableQuery.builder()
            .tableId(100L)
            .operation(DocumentTableQuery.Operation.SUM)
            .metricColumn("报销金额")
            .filters(List.of(DocumentTableQuery.Filter.builder()
                .column("部门")
                .operator(DocumentTableQuery.Operator.EQ)
                .value("研发部")
                .build()))
            .build();

        DocumentTableQueryResult result = service.query(query);

        assertThat(result.getValue()).isEqualByComparingTo("1200");
        assertThat(result.getMatchedRowCount()).isEqualTo(1);
        assertThat(result.getEvidenceRowIds()).containsExactly(301L);
        assertThat(result.getEvidenceRowNos()).containsExactly(1);
        assertThat(result.getEvidenceColumnIds()).containsExactly(201L, 202L);
        assertThat(result.getEvidenceColumnNos()).containsExactly(1, 2);
        assertThat(result.getEvidenceColumnNames()).containsExactly("部门", "报销金额");
        assertThat(result.getEvidenceCellIds()).containsExactly(401L, 402L);
        assertThat(result.getEvidenceCellCoordinates()).containsExactly("A2", "B2");
        assertThat(result.getEvidenceCellBboxJsons()).containsExactly(
            "{\"x0\":10,\"y0\":20,\"x1\":40,\"y1\":32}",
            "{\"x0\":42,\"y0\":20,\"x1\":80,\"y1\":32}"
        );
        assertThat(result.getEvidenceText()).contains("行1: 部门=研发部；报销金额=1200");
    }

    @Test
    void replaceTaskTablesPersistsParserCellMetadata() {
        InsertCollector tableCollector = new InsertCollector();
        InsertCollector columnCollector = new InsertCollector();
        InsertCollector rowCollector = new InsertCollector();
        InsertCollector cellCollector = new InsertCollector();
        DocumentTableStructureServiceImpl service = new DocumentTableStructureServiceImpl(
            mapper(SuperAgentDocumentTableMapper.class, null, List.of(), tableCollector),
            mapper(SuperAgentDocumentTableColumnMapper.class, null, List.of(), columnCollector),
            mapper(SuperAgentDocumentTableRowMapper.class, null, List.of(), rowCollector),
            mapper(SuperAgentDocumentTableCellMapper.class, null, List.of(), cellCollector),
            new ObjectMapper(),
            uidGenerator()
        );

        service.replaceTaskTables(10L, 20L, List.of(tableBlock()));

        assertThat(tableCollector.items()).hasSize(1);
        assertThat(columnCollector.items()).hasSize(2);
        assertThat(rowCollector.items()).hasSize(1);
        assertThat(cellCollector.items()).hasSize(2);

        SuperAgentDocumentTable savedTable = (SuperAgentDocumentTable) tableCollector.items().get(0);
        assertThat(savedTable.getTitle()).isEqualTo("财务制度 / 报销明细 / 季度报销 表格1");

        SuperAgentDocumentTableCell departmentCell = (SuperAgentDocumentTableCell) cellCollector.items().get(0);
        SuperAgentDocumentTableCell amountCell = (SuperAgentDocumentTableCell) cellCollector.items().get(1);
        assertThat(departmentCell.getRowNo()).isEqualTo(1);
        assertThat(departmentCell.getColumnNo()).isEqualTo(1);
        assertThat(departmentCell.getSourceRowNo()).isEqualTo(2);
        assertThat(departmentCell.getSourceColumnNo()).isEqualTo(1);
        assertThat(departmentCell.getSourceCellRef()).isEqualTo("A2");
        assertThat(departmentCell.getBboxJson()).contains("\"x0\":10");
        assertThat(departmentCell.getMetadataJson()).contains("\"sheetName\":\"报销\"");
        assertThat(departmentCell.getMetadataJson()).contains("\"mergedCellRange\":\"A2:A3\"");
        assertThat(departmentCell.getMetadataJson()).contains("\"rowSpan\":2");
        assertThat(departmentCell.getMetadataJson()).contains("\"mergedValueFilled\":true");
        assertThat(amountCell.getSourceCellRef()).isEqualTo("B2");
        assertThat(amountCell.getNumericValue()).isEqualByComparingTo("1200");
    }

    private SuperAgentDocumentTable table() {
        SuperAgentDocumentTable table = new SuperAgentDocumentTable();
        table.setId(100L);
        table.setDocumentId(10L);
        table.setTaskId(20L);
        table.setBlockId(30L);
        table.setTableNo(1);
        table.setTitle("报销明细表");
        table.setSectionPath("财务制度 / 报销明细");
        table.setStatus(BusinessStatus.YES.getCode());
        return table;
    }

    private List<SuperAgentDocumentTableColumn> columns() {
        return List.of(
            column(201L, 1, "部门", "TEXT"),
            column(202L, 2, "报销金额", "NUMBER"),
            column(203L, 3, "月份", "TEXT")
        );
    }

    private SuperAgentDocumentTableColumn column(Long id, int columnNo, String name, String valueType) {
        SuperAgentDocumentTableColumn column = new SuperAgentDocumentTableColumn();
        column.setId(id);
        column.setTableId(100L);
        column.setColumnNo(columnNo);
        column.setColumnName(name);
        column.setNormalizedName(name);
        column.setValueType(valueType);
        column.setStatus(BusinessStatus.YES.getCode());
        return column;
    }

    private List<SuperAgentDocumentTableRow> rows() {
        return List.of(
            row(301L, 1),
            row(302L, 2)
        );
    }

    private SuperAgentDocumentTableRow row(Long id, int rowNo) {
        SuperAgentDocumentTableRow row = new SuperAgentDocumentTableRow();
        row.setId(id);
        row.setTableId(100L);
        row.setRowNo(rowNo);
        row.setStatus(BusinessStatus.YES.getCode());
        return row;
    }

    private List<SuperAgentDocumentTableCell> cells() {
        return List.of(
            cell(401L, 301L, 201L, 1, 1, "研发部", null),
            cell(402L, 301L, 202L, 1, 2, "1200", new BigDecimal("1200")),
            cell(403L, 301L, 203L, 1, 3, "2026-06", null),
            cell(404L, 302L, 201L, 2, 1, "市场部", null),
            cell(405L, 302L, 202L, 2, 2, "800", new BigDecimal("800")),
            cell(406L, 302L, 203L, 2, 3, "2026-06", null)
        );
    }

    private SuperAgentDocumentTableCell cell(Long id,
                                             Long rowId,
                                             Long columnId,
                                             int rowNo,
                                             int columnNo,
                                             String text,
                                             BigDecimal numericValue) {
        SuperAgentDocumentTableCell cell = new SuperAgentDocumentTableCell();
        cell.setId(id);
        cell.setTableId(100L);
        cell.setRowId(rowId);
        cell.setColumnId(columnId);
        cell.setRowNo(rowNo);
        cell.setColumnNo(columnNo);
        cell.setCellText(text);
        cell.setNumericValue(numericValue);
        if (rowNo == 1 && columnNo == 1) {
            cell.setSourceRowNo(2);
            cell.setSourceColumnNo(1);
            cell.setSourceCellRef("A2");
            cell.setBboxJson("{\"x0\":10,\"y0\":20,\"x1\":40,\"y1\":32}");
        }
        else if (rowNo == 1 && columnNo == 2) {
            cell.setSourceRowNo(2);
            cell.setSourceColumnNo(2);
            cell.setSourceCellRef("B2");
            cell.setBboxJson("{\"x0\":42,\"y0\":20,\"x1\":80,\"y1\":32}");
        }
        cell.setStatus(BusinessStatus.YES.getCode());
        return cell;
    }

    private SuperAgentDocumentBlock tableBlock() {
        SuperAgentDocumentBlock block = new SuperAgentDocumentBlock();
        block.setId(30L);
        block.setDocumentId(10L);
        block.setTaskId(20L);
        block.setBlockType("TABLE");
        block.setSectionPath("财务制度 / 报销明细");
        block.setMetadataJson("""
            {
              "tableRows":[["部门","报销金额"],["研发部","1200"]],
              "tableTitleRows":["季度报销"],
              "tableTitleCellMetadata":[
                {"rowNo":1,"columnNo":1,"sourceRowNo":1,"sourceColumnNo":1,"excelAddress":"A1","sheetName":"报销","mergedCell":true,"mergedCellAnchor":true,"mergedCellRange":"A1:B1","mergedCellAnchorAddress":"A1","mergedCellValue":"季度报销","rowSpan":1,"columnSpan":2,"value":"季度报销"}
              ],
              "tableCellMetadata":[
                {"rowNo":1,"columnNo":1,"sourceRowNo":1,"sourceColumnNo":1,"excelAddress":"A1","sheetName":"报销","value":"部门"},
                {"rowNo":1,"columnNo":2,"sourceRowNo":1,"sourceColumnNo":2,"excelAddress":"B1","sheetName":"报销","value":"报销金额"},
                {"rowNo":2,"columnNo":1,"sourceRowNo":2,"sourceColumnNo":1,"excelAddress":"A2","sheetName":"报销","bboxJson":"{\\"x0\\":10,\\"y0\\":20,\\"x1\\":40,\\"y1\\":32}","mergedCell":true,"mergedCellAnchor":true,"mergedCellRange":"A2:A3","mergedCellAnchorAddress":"A2","mergedCellValue":"研发部","rowSpan":2,"columnSpan":1,"mergedValueFilled":true,"value":"研发部"},
                {"rowNo":2,"columnNo":2,"sourceRowNo":2,"sourceColumnNo":2,"excelAddress":"B2","sheetName":"报销","bboxJson":"{\\"x0\\":42,\\"y0\\":20,\\"x1\\":80,\\"y1\\":32}","value":"1200"}
              ]
            }
            """);
        return block;
    }

    private UidGenerator uidGenerator() {
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
                if ("toString".equals(method.getName())) {
                    return "UidGeneratorProxy";
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
    private static <T> T mapper(Class<T> mapperType, Object selectByIdResult, List<?> selectListResult) {
        return mapper(mapperType, selectByIdResult, selectListResult, null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T mapper(Class<T> mapperType, Object selectByIdResult, List<?> selectListResult, InsertCollector insertCollector) {
        return (T) Proxy.newProxyInstance(
            mapperType.getClassLoader(),
            new Class<?>[]{mapperType},
            (proxy, method, args) -> {
                if ("selectById".equals(method.getName())) {
                    return selectByIdResult;
                }
                if ("selectList".equals(method.getName())) {
                    return selectListResult;
                }
                if ("insert".equals(method.getName())) {
                    if (insertCollector != null && args != null && args.length > 0) {
                        insertCollector.add(args[0]);
                    }
                    return 1;
                }
                if ("delete".equals(method.getName())) {
                    return 1;
                }
                if ("toString".equals(method.getName())) {
                    return mapperType.getSimpleName() + "Proxy";
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
