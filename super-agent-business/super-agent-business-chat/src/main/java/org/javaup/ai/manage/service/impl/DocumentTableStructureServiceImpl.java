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
            List<List<String>> tableRows = readTableRows(block);
            if (tableRows.isEmpty()) {
                continue;
            }
            saveTable(documentId, taskId, block, tableNo++, normalizeRows(tableRows));
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
                           List<List<String>> rows) {
        int headerRowIndex = resolveHeaderRowIndex(rows);
        if (rows.isEmpty() || maxColumnCount(rows) <= 0) {
            return;
        }
        List<String> header = normalizeHeader(rows.get(headerRowIndex), maxColumnCount(rows));
        List<List<String>> dataRows = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            if (index == headerRowIndex) {
                continue;
            }
            dataRows.add(padRow(rows.get(index), header.size()));
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
        saveRowsAndCells(documentId, taskId, tableId, dataRows, columnIdByNo);
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
                                  Map<Integer, Long> columnIdByNo) {
        for (int rowIndex = 0; rowIndex < dataRows.size(); rowIndex++) {
            int rowNo = rowIndex + 1;
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
                cell.setStatus(BusinessStatus.YES.getCode());
                cellMapper.insert(cell);
            }
        }
    }

    private List<List<String>> readTableRows(SuperAgentDocumentBlock block) {
        if (block == null || !"TABLE".equalsIgnoreCase(StrUtil.blankToDefault(block.getBlockType(), ""))) {
            return List.of();
        }
        String metadataJson = block.getMetadataJson();
        if (StrUtil.isBlank(metadataJson)) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(metadataJson);
            JsonNode tableRowsNode = root.path("tableRows");
            if (!tableRowsNode.isArray() || tableRowsNode.isEmpty()) {
                return List.of();
            }
            return objectMapper.convertValue(tableRowsNode, TABLE_ROWS_TYPE);
        }
        catch (RuntimeException exception) {
            throw new IllegalStateException("解析表格 block metadata.tableRows 失败: blockId=" + block.getId(), exception);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("解析表格 block metadata.tableRows 失败: blockId=" + block.getId(), exception);
        }
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
            if (!columnByNo.containsKey(cell.getColumnNo())) {
                continue;
            }
            values.put(cell.getColumnNo(), new CellView(cell.getCellText(), cell.getNumericValue()));
        }
        return new RowView(row.getRowNo(), values);
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
        if (StrUtil.isNotBlank(block.getSectionPath())) {
            return block.getSectionPath() + " 表格" + tableNo;
        }
        return "表格" + tableNo;
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

    private record RowView(Integer rowNo, Map<Integer, CellView> values) {
        private String value(Integer columnNo) {
            CellView cell = values.get(columnNo);
            return cell == null ? "" : cell.text();
        }

        private Optional<BigDecimal> numeric(Integer columnNo) {
            CellView cell = values.get(columnNo);
            return cell == null || cell.numericValue() == null ? Optional.empty() : Optional.of(cell.numericValue());
        }
    }

    private record CellView(String text, BigDecimal numericValue) {
    }
}
