package org.javaup.ai.manage.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import org.javaup.ai.manage.data.SuperAgentDocumentBlock;
import org.javaup.ai.manage.data.SuperAgentDocumentTable;
import org.javaup.ai.manage.data.SuperAgentDocumentTableCell;
import org.javaup.ai.manage.data.SuperAgentDocumentTableColumn;
import org.javaup.ai.manage.data.SuperAgentDocumentTableRow;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTableCellMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTableColumnMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTableMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTableRowMapper;
import org.javaup.ai.manage.model.table.DocumentTableDescriptor;
import org.javaup.ai.manage.model.table.DocumentTableQuery;
import org.javaup.ai.manage.model.table.DocumentTableQueryResult;
import org.javaup.ai.manage.service.DocumentTableStructureService;
import org.javaup.enums.BusinessStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@AllArgsConstructor
@Service
public class DocumentTableStructureServiceImpl implements DocumentTableStructureService {

    private static final TypeReference<List<List<String>>> TABLE_ROWS_TYPE = new TypeReference<>() {
    };
    private static final int MAX_EVIDENCE_ROWS = 20;
    private static final int MAX_EVIDENCE_COLUMNS = 6;
    private static final int MAX_EVIDENCE_CELLS = 80;

    private final SuperAgentDocumentTableMapper tableMapper;
    private final SuperAgentDocumentTableColumnMapper columnMapper;
    private final SuperAgentDocumentTableRowMapper rowMapper;
    private final SuperAgentDocumentTableCellMapper cellMapper;
    private final ObjectMapper objectMapper;
    private final UidGenerator uidGenerator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceTaskTables(Long documentId, Long taskId, List<SuperAgentDocumentBlock> blockList) {
        deleteByTask(documentId, taskId);
        if (documentId == null || taskId == null || CollUtil.isEmpty(blockList)) {
            return;
        }
        int tableNo = 1;
        for (SuperAgentDocumentBlock block : blockList) {
            TableBlockContent tableContent = readTableBlockContent(block);
            if (tableContent.rows().isEmpty()) {
                continue;
            }
            saveTable(documentId, taskId, block, tableNo++, normalizeRows(tableContent.rows()), tableContent.cellMetadata());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentTableDescriptor> listTables(List<Long> documentIds, List<Long> taskIds) {
        if (CollUtil.isEmpty(documentIds)) {
            return List.of();
        }
        LambdaQueryWrapper<SuperAgentDocumentTable> queryWrapper = new LambdaQueryWrapper<SuperAgentDocumentTable>()
            .in(SuperAgentDocumentTable::getDocumentId, documentIds)
            .eq(SuperAgentDocumentTable::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentDocumentTable::getTaskId)
            .orderByAsc(SuperAgentDocumentTable::getTableNo);
        if (CollUtil.isNotEmpty(taskIds)) {
            queryWrapper.in(SuperAgentDocumentTable::getTaskId, taskIds);
        }
        List<SuperAgentDocumentTable> tables = tableMapper.selectList(queryWrapper);
        if (tables.isEmpty()) {
            return List.of();
        }

        List<Long> tableIds = tables.stream().map(SuperAgentDocumentTable::getId).toList();
        Map<Long, List<SuperAgentDocumentTableColumn>> columnsByTableId = columnMapper.selectList(
                new LambdaQueryWrapper<SuperAgentDocumentTableColumn>()
                    .in(SuperAgentDocumentTableColumn::getTableId, tableIds)
                    .eq(SuperAgentDocumentTableColumn::getStatus, BusinessStatus.YES.getCode())
                    .orderByAsc(SuperAgentDocumentTableColumn::getTableId)
                    .orderByAsc(SuperAgentDocumentTableColumn::getColumnNo)
            ).stream()
            .collect(Collectors.groupingBy(SuperAgentDocumentTableColumn::getTableId, LinkedHashMap::new, Collectors.toList()));

        return tables.stream()
            .map(table -> toDescriptor(table, columnsByTableId.getOrDefault(table.getId(), List.of())))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentTableQueryResult query(DocumentTableQuery query) {
        validateQuery(query);
        SuperAgentDocumentTable table = requiredTable(query.getTableId());
        List<SuperAgentDocumentTableColumn> columns = listColumns(query.getTableId());
        List<SuperAgentDocumentTableRow> rows = listRows(query.getTableId());
        List<SuperAgentDocumentTableCell> cells = listCells(query.getTableId());
        Map<Integer, SuperAgentDocumentTableColumn> columnByNo = columns.stream()
            .collect(Collectors.toMap(SuperAgentDocumentTableColumn::getColumnNo, item -> item, (left, right) -> left, LinkedHashMap::new));
        Map<String, SuperAgentDocumentTableColumn> columnByName = columns.stream()
            .collect(Collectors.toMap(item -> item.getNormalizedName(), item -> item, (left, right) -> left, LinkedHashMap::new));
        Map<Integer, List<SuperAgentDocumentTableCell>> cellsByRowNo = cells.stream()
            .collect(Collectors.groupingBy(SuperAgentDocumentTableCell::getRowNo, LinkedHashMap::new, Collectors.toList()));

        List<RowView> matchedRows = rows.stream()
            .map(row -> toRowView(row, cellsByRowNo.getOrDefault(row.getRowNo(), List.of()), columnByNo))
            .filter(row -> matchesFilters(row, query.getFilters(), columnByName))
            .toList();

        BigDecimal value = BigDecimal.ZERO;
        Map<String, BigDecimal> groupedValues = new LinkedHashMap<>();
        if (query.getOperation() == DocumentTableQuery.Operation.COUNT) {
            value = BigDecimal.valueOf(matchedRows.size());
        }
        else if (query.getOperation() == DocumentTableQuery.Operation.SUM) {
            value = sumRows(matchedRows, requiredColumn(columnByName, query.getMetricColumn()));
        }
        else if (query.getOperation() == DocumentTableQuery.Operation.MAX) {
            value = maxRows(matchedRows, requiredColumn(columnByName, query.getMetricColumn()));
        }
        else if (query.getOperation() == DocumentTableQuery.Operation.MIN) {
            value = minRows(matchedRows, requiredColumn(columnByName, query.getMetricColumn()));
        }
        else if (query.getOperation() == DocumentTableQuery.Operation.GROUP_COUNT) {
            SuperAgentDocumentTableColumn groupColumn = requiredColumn(columnByName, query.getGroupByColumn());
            for (RowView row : matchedRows) {
                String group = StrUtil.blankToDefault(row.value(groupColumn.getColumnNo()), "(空)");
                groupedValues.merge(group, BigDecimal.ONE, BigDecimal::add);
            }
        }
        else if (query.getOperation() == DocumentTableQuery.Operation.GROUP_SUM) {
            SuperAgentDocumentTableColumn groupColumn = requiredColumn(columnByName, query.getGroupByColumn());
            SuperAgentDocumentTableColumn metricColumn = requiredColumn(columnByName, query.getMetricColumn());
            for (RowView row : matchedRows) {
                String group = StrUtil.blankToDefault(row.value(groupColumn.getColumnNo()), "(空)");
                groupedValues.merge(group, row.numeric(metricColumn.getColumnNo()).orElse(BigDecimal.ZERO), BigDecimal::add);
            }
        }
        List<SuperAgentDocumentTableColumn> evidenceColumns = resolveEvidenceColumns(query, columns, columnByName);
        List<RowView> evidenceRows = matchedRows.stream()
            .sorted(Comparator.comparing(RowView::rowNo))
            .limit(MAX_EVIDENCE_ROWS)
            .toList();
        TableEvidenceLocation evidenceLocation = buildEvidenceLocation(evidenceRows, evidenceColumns);

        return DocumentTableQueryResult.builder()
            .tableId(query.getTableId())
            .documentId(table.getDocumentId())
            .taskId(table.getTaskId())
            .blockId(table.getBlockId())
            .tableNo(table.getTableNo())
            .tableTitle(table.getTitle())
            .sectionPath(table.getSectionPath())
            .pageNo(table.getPageNo())
            .pageRange(table.getPageRange())
            .bboxJson(table.getBboxJson())
            .operation(query.getOperation().name())
            .value(value)
            .groupedValues(groupedValues)
            .matchedRowCount(matchedRows.size())
            .evidenceRowIds(evidenceLocation.rowIds())
            .evidenceRowNos(evidenceLocation.rowNos())
            .evidenceColumnIds(evidenceLocation.columnIds())
            .evidenceColumnNos(evidenceLocation.columnNos())
            .evidenceColumnNames(evidenceLocation.columnNames())
            .evidenceCellIds(evidenceLocation.cellIds())
            .evidenceCellCoordinates(evidenceLocation.cellCoordinates())
            .evidenceCellBboxJsons(evidenceLocation.cellBboxJsons())
            .evidenceText(renderEvidenceText(columns, matchedRows, groupedValues, value, query.getOperation()))
            .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByTask(Long documentId, Long taskId) {
        if (documentId == null || taskId == null) {
            return;
        }
        List<Long> tableIds = tableMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentTable>()
                .eq(SuperAgentDocumentTable::getDocumentId, documentId)
                .eq(SuperAgentDocumentTable::getTaskId, taskId))
            .stream()
            .map(SuperAgentDocumentTable::getId)
            .toList();
        deleteByTableIds(tableIds);
        tableMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentTable>()
            .eq(SuperAgentDocumentTable::getDocumentId, documentId)
            .eq(SuperAgentDocumentTable::getTaskId, taskId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        List<Long> tableIds = tableMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentTable>()
                .eq(SuperAgentDocumentTable::getDocumentId, documentId))
            .stream()
            .map(SuperAgentDocumentTable::getId)
            .toList();
        deleteByTableIds(tableIds);
        tableMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentTable>()
            .eq(SuperAgentDocumentTable::getDocumentId, documentId));
    }

    private void saveTable(Long documentId,
                           Long taskId,
                           SuperAgentDocumentBlock block,
                           int tableNo,
                           List<List<String>> rows,
                           List<TableCellMetadata> cellMetadata) {
        int headerRowIndex = resolveHeaderRowIndex(rows);
        if (rows.isEmpty() || maxColumnCount(rows) <= 0) {
            return;
        }
        List<String> header = normalizeHeader(rows.get(headerRowIndex), maxColumnCount(rows));
        List<List<String>> dataRows = new ArrayList<>();
        List<Integer> sourceRowIndexes = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            if (index == headerRowIndex) {
                continue;
            }
            dataRows.add(padRow(rows.get(index), header.size()));
            sourceRowIndexes.add(index + 1);
        }

        Long tableId = uidGenerator.getUid();
        SuperAgentDocumentTable table = new SuperAgentDocumentTable();
        table.setId(tableId);
        table.setDocumentId(documentId);
        table.setTaskId(taskId);
        table.setBlockId(block.getId());
        table.setTableNo(tableNo);
        table.setSectionPath(block.getSectionPath());
        table.setPageNo(block.getPageNo());
        table.setPageRange(block.getPageRange());
        table.setBboxJson(block.getBboxJson());
        table.setTitle(resolveTableTitle(block, tableNo));
        table.setRowCount(dataRows.size());
        table.setColumnCount(header.size());
        table.setTableHtml(block.getTableHtml());
        table.setMetadataJson("{\"source\":\"document_block\"}");
        table.setStatus(BusinessStatus.YES.getCode());
        tableMapper.insert(table);

        Map<Integer, Long> columnIdByNo = saveColumns(documentId, taskId, tableId, header, dataRows);
        saveRowsAndCells(documentId, taskId, tableId, dataRows, sourceRowIndexes, metadataByPosition(cellMetadata), columnIdByNo);
    }

    private Map<Integer, Long> saveColumns(Long documentId,
                                           Long taskId,
                                           Long tableId,
                                           List<String> header,
                                           List<List<String>> dataRows) {
        Map<Integer, Long> columnIdByNo = new LinkedHashMap<>();
        for (int columnIndex = 0; columnIndex < header.size(); columnIndex++) {
            int columnNo = columnIndex + 1;
            Long columnId = uidGenerator.getUid();
            columnIdByNo.put(columnNo, columnId);

            SuperAgentDocumentTableColumn column = new SuperAgentDocumentTableColumn();
            column.setId(columnId);
            column.setDocumentId(documentId);
            column.setTaskId(taskId);
            column.setTableId(tableId);
            column.setColumnNo(columnNo);
            column.setColumnName(header.get(columnIndex));
            column.setNormalizedName(normalizeName(header.get(columnIndex)));
            column.setValueType(resolveValueType(dataRows, columnIndex));
            column.setStatus(BusinessStatus.YES.getCode());
            columnMapper.insert(column);
        }
        return columnIdByNo;
    }

    private void saveRowsAndCells(Long documentId,
                                  Long taskId,
                                  Long tableId,
                                  List<List<String>> dataRows,
                                  List<Integer> sourceRowIndexes,
                                  Map<CellPosition, TableCellMetadata> metadataByPosition,
                                  Map<Integer, Long> columnIdByNo) {
        for (int rowIndex = 0; rowIndex < dataRows.size(); rowIndex++) {
            int rowNo = rowIndex + 1;
            int sourceRowNo = rowIndex < sourceRowIndexes.size() ? sourceRowIndexes.get(rowIndex) : rowNo;
            Long rowId = uidGenerator.getUid();
            List<String> rowValues = dataRows.get(rowIndex);

            SuperAgentDocumentTableRow row = new SuperAgentDocumentTableRow();
            row.setId(rowId);
            row.setDocumentId(documentId);
            row.setTaskId(taskId);
            row.setTableId(tableId);
            row.setRowNo(rowNo);
            row.setRowText(String.join(" | ", rowValues));
            row.setStatus(BusinessStatus.YES.getCode());
            rowMapper.insert(row);

            for (int columnIndex = 0; columnIndex < rowValues.size(); columnIndex++) {
                int columnNo = columnIndex + 1;
                String cellText = rowValues.get(columnIndex);
                TableCellMetadata metadata = metadataByPosition.getOrDefault(new CellPosition(sourceRowNo, columnNo), TableCellMetadata.empty());
                SuperAgentDocumentTableCell cell = new SuperAgentDocumentTableCell();
                cell.setId(uidGenerator.getUid());
                cell.setDocumentId(documentId);
                cell.setTaskId(taskId);
                cell.setTableId(tableId);
                cell.setRowId(rowId);
                cell.setColumnId(columnIdByNo.get(columnNo));
                cell.setRowNo(rowNo);
                cell.setColumnNo(columnNo);
                cell.setCellText(cellText);
                cell.setNumericValue(parseDecimal(cellText).orElse(null));
                cell.setSourceRowNo(firstNonNull(metadata.sourceRowNo(), sourceRowNo));
                cell.setSourceColumnNo(firstNonNull(metadata.sourceColumnNo(), columnNo));
                cell.setSourceCellRef(resolveSourceCellRef(metadata, sourceRowNo, columnNo));
                cell.setBboxJson(StrUtil.isBlank(metadata.bboxJson()) ? null : metadata.bboxJson());
                cell.setMetadataJson(writeMetadataJson(metadata));
                cell.setStatus(BusinessStatus.YES.getCode());
                cellMapper.insert(cell);
            }
        }
    }

    private TableBlockContent readTableBlockContent(SuperAgentDocumentBlock block) {
        if (block == null || !"TABLE".equalsIgnoreCase(StrUtil.blankToDefault(block.getBlockType(), ""))) {
            return TableBlockContent.empty();
        }
        String metadataJson = block.getMetadataJson();
        if (StrUtil.isBlank(metadataJson)) {
            return TableBlockContent.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(metadataJson);
            JsonNode tableRowsNode = root.path("tableRows");
            if (!tableRowsNode.isArray() || tableRowsNode.isEmpty()) {
                return TableBlockContent.empty();
            }
            List<List<String>> rows = objectMapper.convertValue(tableRowsNode, TABLE_ROWS_TYPE);
            JsonNode metadataNode = root.path("tableCellMetadata");
            List<TableCellMetadata> cellMetadata = readTableCellMetadata(metadataNode);
            return new TableBlockContent(rows, cellMetadata);
        }
        catch (RuntimeException exception) {
            throw new IllegalStateException("解析表格 block metadata 失败: blockId=" + block.getId(), exception);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("解析表格 block metadata 失败: blockId=" + block.getId(), exception);
        }
    }

    private List<TableCellMetadata> readTableCellMetadata(JsonNode metadataNode) {
        if (metadataNode == null || !metadataNode.isArray() || metadataNode.isEmpty()) {
            return List.of();
        }
        List<TableCellMetadata> metadataList = new ArrayList<>();
        for (JsonNode item : metadataNode) {
            if (item == null || !item.isObject()) {
                continue;
            }
            TableCellMetadata metadata = new TableCellMetadata(
                readInteger(item, "rowNo"),
                readInteger(item, "columnNo"),
                readInteger(item, "sourceRowNo"),
                readInteger(item, "sourceColumnNo"),
                readText(item, "excelAddress"),
                readText(item, "cellCoordinate"),
                readText(item, "sheetName"),
                readInteger(item, "pageNo"),
                readText(item, "bboxJson"),
                readText(item, "bboxSource"),
                readText(item, "value"),
                readBoolean(item, "mergedCell"),
                readBoolean(item, "mergedCellAnchor"),
                readText(item, "mergedCellRange"),
                readText(item, "mergedCellAnchorAddress"),
                readText(item, "mergedCellValue"),
                readInteger(item, "rowSpan"),
                readInteger(item, "columnSpan"),
                readBoolean(item, "mergedValueFilled"),
                readBoolean(item, "flattenedHeader"),
                readInteger(item, "headerSourceRows")
            );
            if (!metadata.isEmpty()) {
                metadataList.add(metadata);
            }
        }
        return metadataList;
    }

    private Integer readInteger(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isInt() || value.isLong()) {
            return value.intValue();
        }
        if (value.isTextual() && StrUtil.isNotBlank(value.asText())) {
            try {
                return Integer.parseInt(value.asText().trim());
            }
            catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }

    private String readText(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return "";
        }
        return value.asText("");
    }

    private Boolean readBoolean(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isTextual() && StrUtil.isNotBlank(value.asText())) {
            return Boolean.parseBoolean(value.asText().trim());
        }
        return null;
    }

    private Map<CellPosition, TableCellMetadata> metadataByPosition(List<TableCellMetadata> metadataList) {
        Map<CellPosition, TableCellMetadata> result = new LinkedHashMap<>();
        if (metadataList == null || metadataList.isEmpty()) {
            return result;
        }
        for (TableCellMetadata metadata : metadataList) {
            if (metadata == null || metadata.rowNo() == null || metadata.columnNo() == null) {
                continue;
            }
            result.put(new CellPosition(metadata.rowNo(), metadata.columnNo()), metadata);
        }
        return result;
    }

    private String resolveSourceCellRef(TableCellMetadata metadata, int sourceRowNo, int columnNo) {
        String ref = StrUtil.isNotBlank(metadata.excelAddress()) ? metadata.excelAddress() : metadata.cellCoordinate();
        if (StrUtil.isNotBlank(ref)) {
            return ref;
        }
        Integer metadataSourceRow = metadata.sourceRowNo();
        Integer metadataSourceColumn = metadata.sourceColumnNo();
        return "R" + firstNonNull(metadataSourceRow, sourceRowNo) + "C" + firstNonNull(metadataSourceColumn, columnNo);
    }

    private String writeMetadataJson(TableCellMetadata metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "";
        }
        Map<String, Object> values = new LinkedHashMap<>();
        putIfPresent(values, "sheetName", metadata.sheetName());
        putIfPresent(values, "pageNo", metadata.pageNo());
        putIfPresent(values, "bboxSource", metadata.bboxSource());
        putIfPresent(values, "value", metadata.value());
        putIfPresent(values, "sourceRowNo", metadata.sourceRowNo());
        putIfPresent(values, "sourceColumnNo", metadata.sourceColumnNo());
        putIfPresent(values, "excelAddress", metadata.excelAddress());
        putIfPresent(values, "cellCoordinate", metadata.cellCoordinate());
        putIfPresent(values, "mergedCell", metadata.mergedCell());
        putIfPresent(values, "mergedCellAnchor", metadata.mergedCellAnchor());
        putIfPresent(values, "mergedCellRange", metadata.mergedCellRange());
        putIfPresent(values, "mergedCellAnchorAddress", metadata.mergedCellAnchorAddress());
        putIfPresent(values, "mergedCellValue", metadata.mergedCellValue());
        putIfPresent(values, "rowSpan", metadata.rowSpan());
        putIfPresent(values, "columnSpan", metadata.columnSpan());
        putIfPresent(values, "mergedValueFilled", metadata.mergedValueFilled());
        putIfPresent(values, "flattenedHeader", metadata.flattenedHeader());
        putIfPresent(values, "headerSourceRows", metadata.headerSourceRows());
        try {
            return objectMapper.writeValueAsString(values);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化表格单元格元数据失败", exception);
        }
    }

    private void putIfPresent(Map<String, Object> values, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && StrUtil.isBlank(text)) {
            return;
        }
        values.put(key, value);
    }

    private <T> T firstNonNull(T first, T fallback) {
        return first == null ? fallback : first;
    }

    private List<List<String>> normalizeRows(List<List<String>> rows) {
        return rows.stream()
            .filter(Objects::nonNull)
            .map(row -> row.stream().map(value -> StrUtil.blankToDefault(value, "").trim()).toList())
            .filter(row -> row.stream().anyMatch(StrUtil::isNotBlank))
            .toList();
    }

    private int resolveHeaderRowIndex(List<List<String>> rows) {
        return rows.isEmpty() ? 0 : 0;
    }

    private List<String> normalizeHeader(List<String> rawHeader, int columnCount) {
        List<String> header = padRow(rawHeader, columnCount);
        for (int index = 0; index < header.size(); index++) {
            if (StrUtil.isBlank(header.get(index))) {
                header.set(index, "列" + (index + 1));
            }
        }
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (int index = 0; index < header.size(); index++) {
            String name = header.get(index);
            int count = seen.merge(name, 1, Integer::sum);
            if (count > 1) {
                header.set(index, name + "_" + count);
            }
        }
        return header;
    }

    private List<String> padRow(List<String> row, int columnCount) {
        List<String> padded = new ArrayList<>(row == null ? List.of() : row);
        while (padded.size() < columnCount) {
            padded.add("");
        }
        if (padded.size() > columnCount) {
            return new ArrayList<>(padded.subList(0, columnCount));
        }
        return padded;
    }

    private int maxColumnCount(List<List<String>> rows) {
        return rows.stream().mapToInt(row -> row == null ? 0 : row.size()).max().orElse(0);
    }

    private String resolveValueType(List<List<String>> rows, int columnIndex) {
        long nonBlankCount = rows.stream()
            .map(row -> columnIndex < row.size() ? row.get(columnIndex) : "")
            .filter(StrUtil::isNotBlank)
            .count();
        long numericCount = rows.stream()
            .map(row -> columnIndex < row.size() ? row.get(columnIndex) : "")
            .filter(StrUtil::isNotBlank)
            .filter(value -> parseDecimal(value).isPresent())
            .count();
        return nonBlankCount > 0 && numericCount == nonBlankCount ? "NUMBER" : "TEXT";
    }

    private Optional<BigDecimal> parseDecimal(String value) {
        String normalized = StrUtil.blankToDefault(value, "")
            .replace(",", "")
            .replace("，", "")
            .replace("%", "")
            .trim();
        if (StrUtil.isBlank(normalized)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(normalized));
        }
        catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private List<SuperAgentDocumentTableColumn> listColumns(Long tableId) {
        return columnMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentTableColumn>()
            .eq(SuperAgentDocumentTableColumn::getTableId, tableId)
            .eq(SuperAgentDocumentTableColumn::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentDocumentTableColumn::getColumnNo));
    }

    private List<SuperAgentDocumentTableRow> listRows(Long tableId) {
        return rowMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentTableRow>()
            .eq(SuperAgentDocumentTableRow::getTableId, tableId)
            .eq(SuperAgentDocumentTableRow::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentDocumentTableRow::getRowNo));
    }

    private List<SuperAgentDocumentTableCell> listCells(Long tableId) {
        return cellMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentTableCell>()
            .eq(SuperAgentDocumentTableCell::getTableId, tableId)
            .eq(SuperAgentDocumentTableCell::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentDocumentTableCell::getRowNo)
            .orderByAsc(SuperAgentDocumentTableCell::getColumnNo));
    }

    private RowView toRowView(SuperAgentDocumentTableRow row,
                              List<SuperAgentDocumentTableCell> cells,
                              Map<Integer, SuperAgentDocumentTableColumn> columnByNo) {
        Map<Integer, CellView> values = new LinkedHashMap<>();
        for (SuperAgentDocumentTableCell cell : cells) {
            SuperAgentDocumentTableColumn column = columnByNo.get(cell.getColumnNo());
            if (column == null) {
                continue;
            }
            values.put(cell.getColumnNo(), new CellView(
                cell.getId(),
                cell.getColumnId(),
                cell.getColumnNo(),
                column.getColumnName(),
                cell.getCellText(),
                cell.getNumericValue(),
                StrUtil.blankToDefault(cell.getSourceCellRef(), ""),
                StrUtil.blankToDefault(cell.getBboxJson(), "")
            ));
        }
        return new RowView(row.getId(), row.getRowNo(), values);
    }

    private boolean matchesFilters(RowView row,
                                   List<DocumentTableQuery.Filter> filters,
                                   Map<String, SuperAgentDocumentTableColumn> columnByName) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        for (DocumentTableQuery.Filter filter : filters) {
            SuperAgentDocumentTableColumn column = requiredColumn(columnByName, filter.getColumn());
            String cellValue = StrUtil.blankToDefault(row.value(column.getColumnNo()), "");
            BigDecimal numericValue = row.numeric(column.getColumnNo()).orElse(null);
            if (!matchesFilter(cellValue, numericValue, filter)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesFilter(String cellValue, BigDecimal numericValue, DocumentTableQuery.Filter filter) {
        DocumentTableQuery.Operator operator = filter.getOperator() == null ? DocumentTableQuery.Operator.EQ : filter.getOperator();
        String filterValue = StrUtil.blankToDefault(filter.getValue(), "");
        return switch (operator) {
            case EQ -> cellValue.equals(filterValue);
            case CONTAINS -> cellValue.contains(filterValue);
            case GT -> numericValue != null && parseDecimal(filterValue).map(value -> numericValue.compareTo(value) > 0).orElse(false);
            case GTE -> numericValue != null && parseDecimal(filterValue).map(value -> numericValue.compareTo(value) >= 0).orElse(false);
            case LT -> numericValue != null && parseDecimal(filterValue).map(value -> numericValue.compareTo(value) < 0).orElse(false);
            case LTE -> numericValue != null && parseDecimal(filterValue).map(value -> numericValue.compareTo(value) <= 0).orElse(false);
        };
    }

    private BigDecimal sumRows(List<RowView> rows, SuperAgentDocumentTableColumn column) {
        return rows.stream()
            .map(row -> row.numeric(column.getColumnNo()).orElse(BigDecimal.ZERO))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal maxRows(List<RowView> rows, SuperAgentDocumentTableColumn column) {
        return rows.stream()
            .map(row -> row.numeric(column.getColumnNo()))
            .flatMap(Optional::stream)
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
    }

    private BigDecimal minRows(List<RowView> rows, SuperAgentDocumentTableColumn column) {
        return rows.stream()
            .map(row -> row.numeric(column.getColumnNo()))
            .flatMap(Optional::stream)
            .min(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
    }

    private SuperAgentDocumentTableColumn requiredColumn(Map<String, SuperAgentDocumentTableColumn> columnByName, String columnName) {
        SuperAgentDocumentTableColumn column = columnByName.get(normalizeName(columnName));
        if (column == null) {
            throw new IllegalArgumentException("未知表格列: " + columnName);
        }
        return column;
    }

    private String normalizeName(String value) {
        return StrUtil.blankToDefault(value, "")
            .replaceAll("[\\s`*_\\-，,。；;：:（）()\\[\\]{}]+", "")
            .toLowerCase(Locale.ROOT);
    }

    private void validateQuery(DocumentTableQuery query) {
        if (query == null || query.getTableId() == null) {
            throw new IllegalArgumentException("tableId 不能为空");
        }
        if (query.getOperation() == null) {
            throw new IllegalArgumentException("operation 不能为空");
        }
        if ((query.getOperation() == DocumentTableQuery.Operation.SUM
            || query.getOperation() == DocumentTableQuery.Operation.MAX
            || query.getOperation() == DocumentTableQuery.Operation.MIN
            || query.getOperation() == DocumentTableQuery.Operation.GROUP_SUM)
            && StrUtil.isBlank(query.getMetricColumn())) {
            throw new IllegalArgumentException("数值聚合查询必须指定 metricColumn");
        }
        if ((query.getOperation() == DocumentTableQuery.Operation.GROUP_COUNT || query.getOperation() == DocumentTableQuery.Operation.GROUP_SUM)
            && StrUtil.isBlank(query.getGroupByColumn())) {
            throw new IllegalArgumentException("group 查询必须指定 groupByColumn");
        }
    }

    private String resolveTableTitle(SuperAgentDocumentBlock block, int tableNo) {
        String parserTableTitle = readParserTableTitle(block.getMetadataJson());
        if (StrUtil.isNotBlank(parserTableTitle)) {
            if (StrUtil.isNotBlank(block.getSectionPath())) {
                return block.getSectionPath() + " / " + parserTableTitle + " 表格" + tableNo;
            }
            return parserTableTitle + " 表格" + tableNo;
        }
        if (StrUtil.isNotBlank(block.getSectionPath())) {
            return block.getSectionPath() + " 表格" + tableNo;
        }
        return "表格" + tableNo;
    }

    private String readParserTableTitle(String metadataJson) {
        if (StrUtil.isBlank(metadataJson)) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(metadataJson);
            JsonNode titleRows = root.path("tableTitleRows");
            if (!titleRows.isArray() || titleRows.isEmpty()) {
                return "";
            }
            List<String> titles = new ArrayList<>();
            for (JsonNode titleRow : titleRows) {
                String text = titleRow.asText("");
                if (StrUtil.isNotBlank(text)) {
                    titles.add(text.trim());
                }
            }
            return String.join(" / ", titles);
        }
        catch (RuntimeException | JsonProcessingException exception) {
            return "";
        }
    }

    private String renderEvidenceText(List<SuperAgentDocumentTableColumn> columns,
                                      List<RowView> rows,
                                      Map<String, BigDecimal> groupedValues,
                                      BigDecimal value,
                                      DocumentTableQuery.Operation operation) {
        StringBuilder builder = new StringBuilder();
        builder.append("表格查询结果：").append(operation.name()).append('\n');
        if (groupedValues != null && !groupedValues.isEmpty()) {
            groupedValues.forEach((group, groupValue) -> builder.append("- ").append(group).append(": ").append(groupValue).append('\n'));
        }
        else {
            builder.append("结果：").append(value == null ? BigDecimal.ZERO : value).append('\n');
        }
        builder.append("命中行数：").append(rows.size()).append('\n');
        builder.append("列：")
            .append(columns.stream().map(SuperAgentDocumentTableColumn::getColumnName).collect(Collectors.joining(" | ")))
            .append('\n');
        rows.stream()
            .sorted(Comparator.comparing(RowView::rowNo))
            .limit(5)
            .forEach(row -> builder.append("行").append(row.rowNo()).append(": ").append(renderRow(columns, row)).append('\n'));
        return builder.toString().trim();
    }

    private String renderRow(List<SuperAgentDocumentTableColumn> columns, RowView row) {
        return columns.stream()
            .map(column -> column.getColumnName() + "=" + StrUtil.blankToDefault(row.value(column.getColumnNo()), ""))
            .collect(Collectors.joining("；"));
    }

    private List<SuperAgentDocumentTableColumn> resolveEvidenceColumns(DocumentTableQuery query,
                                                                       List<SuperAgentDocumentTableColumn> columns,
                                                                       Map<String, SuperAgentDocumentTableColumn> columnByName) {
        Map<Integer, SuperAgentDocumentTableColumn> evidenceColumnMap = new LinkedHashMap<>();
        if (query.getFilters() != null) {
            for (DocumentTableQuery.Filter filter : query.getFilters()) {
                addEvidenceColumn(evidenceColumnMap, columnByName, filter.getColumn());
            }
        }
        addEvidenceColumn(evidenceColumnMap, columnByName, query.getGroupByColumn());
        addEvidenceColumn(evidenceColumnMap, columnByName, query.getMetricColumn());
        if (evidenceColumnMap.isEmpty()) {
            columns.stream()
                .sorted(Comparator.comparing(SuperAgentDocumentTableColumn::getColumnNo))
                .limit(MAX_EVIDENCE_COLUMNS)
                .forEach(column -> evidenceColumnMap.put(column.getColumnNo(), column));
        }
        return evidenceColumnMap.values().stream()
            .sorted(Comparator.comparing(SuperAgentDocumentTableColumn::getColumnNo))
            .limit(MAX_EVIDENCE_COLUMNS)
            .toList();
    }

    private void addEvidenceColumn(Map<Integer, SuperAgentDocumentTableColumn> evidenceColumnMap,
                                   Map<String, SuperAgentDocumentTableColumn> columnByName,
                                   String columnName) {
        if (StrUtil.isBlank(columnName)) {
            return;
        }
        SuperAgentDocumentTableColumn column = columnByName.get(normalizeName(columnName));
        if (column != null) {
            evidenceColumnMap.put(column.getColumnNo(), column);
        }
    }

    private TableEvidenceLocation buildEvidenceLocation(List<RowView> rows, List<SuperAgentDocumentTableColumn> columns) {
        List<Long> rowIds = rows.stream().map(RowView::rowId).filter(Objects::nonNull).toList();
        List<Integer> rowNos = rows.stream().map(RowView::rowNo).filter(Objects::nonNull).toList();
        List<Long> columnIds = columns.stream().map(SuperAgentDocumentTableColumn::getId).filter(Objects::nonNull).toList();
        List<Integer> columnNos = columns.stream().map(SuperAgentDocumentTableColumn::getColumnNo).filter(Objects::nonNull).toList();
        List<String> columnNames = columns.stream().map(SuperAgentDocumentTableColumn::getColumnName).filter(StrUtil::isNotBlank).toList();
        List<Long> cellIds = new ArrayList<>();
        List<String> cellCoordinates = new ArrayList<>();
        List<String> cellBboxJsons = new ArrayList<>();
        for (RowView row : rows) {
            for (SuperAgentDocumentTableColumn column : columns) {
                CellView cell = row.values().get(column.getColumnNo());
                if (cell == null || cell.cellId() == null) {
                    continue;
                }
                if (cellIds.size() >= MAX_EVIDENCE_CELLS) {
                    return new TableEvidenceLocation(rowIds, rowNos, columnIds, columnNos, columnNames, cellIds, cellCoordinates, cellBboxJsons);
                }
                cellIds.add(cell.cellId());
                cellCoordinates.add(StrUtil.blankToDefault(cell.sourceCellRef(), "R" + row.rowNo() + "C" + column.getColumnNo()));
                cellBboxJsons.add(StrUtil.blankToDefault(cell.bboxJson(), ""));
            }
        }
        return new TableEvidenceLocation(rowIds, rowNos, columnIds, columnNos, columnNames, cellIds, cellCoordinates, cellBboxJsons);
    }

    private void deleteByTableIds(List<Long> tableIds) {
        if (CollUtil.isEmpty(tableIds)) {
            return;
        }
        cellMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentTableCell>().in(SuperAgentDocumentTableCell::getTableId, tableIds));
        rowMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentTableRow>().in(SuperAgentDocumentTableRow::getTableId, tableIds));
        columnMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentTableColumn>().in(SuperAgentDocumentTableColumn::getTableId, tableIds));
    }

    private SuperAgentDocumentTable requiredTable(Long tableId) {
        SuperAgentDocumentTable table = tableMapper.selectById(tableId);
        if (table == null || !Objects.equals(table.getStatus(), BusinessStatus.YES.getCode())) {
            throw new IllegalArgumentException("未知表格: " + tableId);
        }
        return table;
    }

    private DocumentTableDescriptor toDescriptor(SuperAgentDocumentTable table,
                                                 List<SuperAgentDocumentTableColumn> columns) {
        return DocumentTableDescriptor.builder()
            .tableId(table.getId())
            .documentId(table.getDocumentId())
            .taskId(table.getTaskId())
            .blockId(table.getBlockId())
            .tableNo(table.getTableNo())
            .title(table.getTitle())
            .sectionPath(table.getSectionPath())
            .pageNo(table.getPageNo())
            .pageRange(table.getPageRange())
            .bboxJson(table.getBboxJson())
            .rowCount(table.getRowCount())
            .columnCount(table.getColumnCount())
            .columns(columns.stream()
                .map(column -> DocumentTableDescriptor.Column.builder()
                    .columnNo(column.getColumnNo())
                    .columnName(column.getColumnName())
                    .normalizedName(column.getNormalizedName())
                    .valueType(column.getValueType())
                    .build())
                .toList())
            .build();
    }

    private record RowView(Long rowId, Integer rowNo, Map<Integer, CellView> values) {
        private String value(Integer columnNo) {
            CellView cell = values.get(columnNo);
            return cell == null ? "" : cell.text();
        }

        private Optional<BigDecimal> numeric(Integer columnNo) {
            CellView cell = values.get(columnNo);
            return cell == null || cell.numericValue() == null ? Optional.empty() : Optional.of(cell.numericValue());
        }
    }

    private record CellView(Long cellId,
                            Long columnId,
                            Integer columnNo,
                            String columnName,
                            String text,
                            BigDecimal numericValue,
                            String sourceCellRef,
                            String bboxJson) {
    }

    private record TableEvidenceLocation(List<Long> rowIds,
                                         List<Integer> rowNos,
                                         List<Long> columnIds,
                                         List<Integer> columnNos,
                                         List<String> columnNames,
                                         List<Long> cellIds,
                                         List<String> cellCoordinates,
                                         List<String> cellBboxJsons) {
    }

    private record TableBlockContent(List<List<String>> rows, List<TableCellMetadata> cellMetadata) {
        private static TableBlockContent empty() {
            return new TableBlockContent(List.of(), List.of());
        }
    }

    private record CellPosition(Integer rowNo, Integer columnNo) {
    }

    private record TableCellMetadata(Integer rowNo,
                                     Integer columnNo,
                                     Integer sourceRowNo,
                                     Integer sourceColumnNo,
                                     String excelAddress,
                                     String cellCoordinate,
                                     String sheetName,
                                     Integer pageNo,
                                     String bboxJson,
                                     String bboxSource,
                                     String value,
                                     Boolean mergedCell,
                                     Boolean mergedCellAnchor,
                                     String mergedCellRange,
                                     String mergedCellAnchorAddress,
                                     String mergedCellValue,
                                     Integer rowSpan,
                                     Integer columnSpan,
                                     Boolean mergedValueFilled,
                                     Boolean flattenedHeader,
                                     Integer headerSourceRows) {
        private static TableCellMetadata empty() {
            return new TableCellMetadata(null, null, null, null, "", "", "", null, "", "", "",
                null, null, "", "", "", null, null, null, null, null);
        }

        private boolean isEmpty() {
            return rowNo == null
                && columnNo == null
                && sourceRowNo == null
                && sourceColumnNo == null
                && StrUtil.isBlank(excelAddress)
                && StrUtil.isBlank(cellCoordinate)
                && StrUtil.isBlank(sheetName)
                && pageNo == null
                && StrUtil.isBlank(bboxJson)
                && StrUtil.isBlank(bboxSource)
                && StrUtil.isBlank(value)
                && mergedCell == null
                && mergedCellAnchor == null
                && StrUtil.isBlank(mergedCellRange)
                && StrUtil.isBlank(mergedCellAnchorAddress)
                && StrUtil.isBlank(mergedCellValue)
                && rowSpan == null
                && columnSpan == null
                && mergedValueFilled == null
                && flattenedHeader == null
                && headerSourceRows == null;
        }
    }
}
