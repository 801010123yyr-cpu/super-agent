package org.javaup.ai.manage.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentBlock;
import org.javaup.ai.manage.data.SuperAgentDocumentChunk;
import org.javaup.ai.manage.data.SuperAgentDocumentParentBlock;
import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;
import org.javaup.ai.manage.data.SuperAgentDocumentTable;
import org.javaup.ai.manage.data.SuperAgentDocumentTableCell;
import org.javaup.ai.manage.data.SuperAgentDocumentTableColumn;
import org.javaup.ai.manage.data.SuperAgentDocumentTableRow;
import org.javaup.ai.manage.data.SuperAgentDocumentTask;
import org.javaup.ai.manage.data.SuperAgentDocumentTaskLog;
import org.javaup.ai.manage.data.SuperAgentKgCommunity;
import org.javaup.ai.manage.data.SuperAgentKgEntity;
import org.javaup.ai.manage.data.SuperAgentKgRelation;
import org.javaup.ai.manage.data.SuperAgentRaptorNode;
import org.javaup.ai.manage.dto.DocumentRagSnapshotQueryDto;
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
import org.javaup.ai.manage.model.graph.GraphRagQualityReport;
import org.javaup.ai.manage.model.raptor.RaptorQualityReport;
import org.javaup.ai.manage.service.GraphRagQualityService;
import org.javaup.ai.manage.service.DocumentRagSnapshotService;
import org.javaup.ai.manage.service.RaptorQualityService;
import org.javaup.ai.manage.vo.DocumentRagSnapshotVo;
import org.javaup.ai.manage.vo.DocumentTaskLogVo;
import org.javaup.enums.BusinessStatus;
import org.javaup.enums.DocumentLogLevelEnum;
import org.javaup.enums.DocumentManageCode;
import org.javaup.enums.DocumentTaskEventTypeEnum;
import org.javaup.enums.DocumentTaskStageEnum;
import org.javaup.enums.DocumentTaskStatusEnum;
import org.javaup.enums.DocumentTaskTypeEnum;
import org.javaup.enums.DocumentVectorStatusEnum;
import org.javaup.exception.SuperAgentFrameException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class DocumentRagSnapshotServiceImpl implements DocumentRagSnapshotService {

    private static final TypeReference<List<Long>> LONG_LIST_TYPE = new TypeReference<>() {
    };

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final int PARSE_BLOCK_SAMPLE_SIZE = 8;

    private static final int STRUCTURE_NODE_SAMPLE_SIZE = 10;

    private static final int PARENT_BLOCK_SAMPLE_SIZE = 8;

    private static final int CHUNK_SAMPLE_SIZE = 10;

    private static final int TABLE_SAMPLE_SIZE = 4;

    private static final int TABLE_ROW_SAMPLE_SIZE = 5;

    private static final int KG_ENTITY_SAMPLE_SIZE = 12;

    private static final int KG_RELATION_SAMPLE_SIZE = 12;

    private static final int KG_COMMUNITY_SAMPLE_SIZE = 8;

    private static final int RAPTOR_NODE_SAMPLE_SIZE = 80;

    private static final int RAPTOR_SOURCE_SAMPLE_SIZE = 5;

    private static final int BUILD_LOG_SAMPLE_SIZE = 12;

    private static final int PREVIEW_LENGTH = 180;

    private final SuperAgentDocumentMapper documentMapper;

    private final SuperAgentDocumentTaskMapper taskMapper;

    private final SuperAgentDocumentTaskLogMapper taskLogMapper;

    private final SuperAgentDocumentBlockMapper blockMapper;

    private final SuperAgentDocumentStructureNodeMapper structureNodeMapper;

    private final SuperAgentDocumentParentBlockMapper parentBlockMapper;

    private final SuperAgentDocumentChunkMapper chunkMapper;

    private final SuperAgentDocumentTableMapper tableMapper;

    private final SuperAgentDocumentTableColumnMapper tableColumnMapper;

    private final SuperAgentDocumentTableRowMapper tableRowMapper;

    private final SuperAgentDocumentTableCellMapper tableCellMapper;

    private final SuperAgentKgEntityMapper kgEntityMapper;

    private final SuperAgentKgRelationMapper kgRelationMapper;

    private final SuperAgentKgCommunityMapper kgCommunityMapper;

    private final SuperAgentRaptorNodeMapper raptorNodeMapper;

    private final GraphRagQualityService graphRagQualityService;

    private final RaptorQualityService raptorQualityService;

    private final ChatRagProperties chatRagProperties;

    private final ObjectMapper objectMapper;

    @Override
    public DocumentRagSnapshotVo querySnapshot(DocumentRagSnapshotQueryDto dto) {
        SuperAgentDocument document = getDocumentOrThrow(dto.getDocumentId());
        Long parseTaskId = resolveTaskId(document, dto.getParseTaskId(), document.getLastParseTaskId(), DocumentTaskTypeEnum.PARSE_ROUTE);
        Long indexTaskId = resolveTaskId(document, dto.getIndexTaskId(), document.getLastIndexTaskId(), DocumentTaskTypeEnum.BUILD_INDEX);
        SuperAgentDocumentTask indexTask = indexTaskId == null ? null : taskMapper.selectById(indexTaskId);

        long parseBlockCount = parseTaskId == null ? 0L : countBlocks(document.getId(), parseTaskId);
        long structureNodeCount = parseTaskId == null ? 0L : countStructureNodes(document.getId(), parseTaskId);
        long parentBlockCount = indexTaskId == null ? 0L : countParentBlocks(document.getId(), indexTaskId);
        long chunkCount = indexTaskId == null ? 0L : countChunks(document.getId(), indexTaskId);
        long vectorReadyCount = indexTaskId == null ? 0L : countVectorReadyChunks(document.getId(), indexTaskId);
        long tableCount = indexTaskId == null ? 0L : countTables(document.getId(), indexTaskId);
        long kgEntityCount = indexTaskId == null ? 0L : countKgEntities(document.getId(), indexTaskId);
        long kgRelationCount = indexTaskId == null ? 0L : countKgRelations(document.getId(), indexTaskId);
        long kgCommunityCount = indexTaskId == null ? 0L : countKgCommunities(document.getId(), indexTaskId);
        long raptorNodeCount = indexTaskId == null ? 0L : countRaptorNodes(document.getId(), indexTaskId);
        GraphRagQualityReport graphRagQuality = graphRagQualityService.evaluate(document.getId(), indexTaskId);
        RaptorQualityReport raptorQuality = raptorQualityService.evaluate(document.getId(), indexTaskId);

        return new DocumentRagSnapshotVo(
            document.getId(),
            document.getDocumentName(),
            parseTaskId,
            indexTaskId,
            indexTask == null ? document.getCurrentPlanId() : indexTask.getPlanId(),
            buildMetrics(parseBlockCount, structureNodeCount, parentBlockCount, chunkCount, vectorReadyCount,
                tableCount, kgEntityCount, kgRelationCount, kgCommunityCount, raptorNodeCount, graphRagQuality),
            buildPipelineStages(parseBlockCount, structureNodeCount, parentBlockCount, chunkCount, tableCount,
                kgEntityCount, kgRelationCount, kgCommunityCount, raptorNodeCount),
            listParseBlocks(document.getId(), parseTaskId),
            listStructureNodes(document.getId(), parseTaskId),
            listParentBlocks(document.getId(), indexTaskId),
            listChunks(document.getId(), indexTaskId),
            listTables(document.getId(), indexTaskId, tableHighlightFocus(dto)),
            listKgEntities(document.getId(), indexTaskId),
            listKgRelations(document.getId(), indexTaskId),
            listKgCommunities(document.getId(), indexTaskId),
            listRaptorNodes(document.getId(), indexTaskId),
            raptorQuality,
            listBuildLogs(indexTaskId),
            "对话运行时的检索通道、RRF 融合、rerank 分数和 citation repair 结果在“对话观测”页面查看；本文档页只展示索引构建后的文档侧 RAG 产物。"
        );
    }

    private SuperAgentDocument getDocumentOrThrow(Long documentId) {
        SuperAgentDocument document = documentMapper.selectById(documentId);
        if (document == null || !Objects.equals(document.getStatus(), BusinessStatus.YES.getCode())) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_NOT_FOUND.getCode(),
                DocumentManageCode.DOCUMENT_NOT_FOUND.getMsg());
        }
        return document;
    }

    private Long resolveTaskId(SuperAgentDocument document,
                               Long requestedTaskId,
                               Long documentTaskId,
                               DocumentTaskTypeEnum taskType) {
        if (requestedTaskId != null) {
            return requestedTaskId;
        }
        if (documentTaskId != null) {
            return documentTaskId;
        }
        SuperAgentDocumentTask latestTask = taskMapper.selectOne(new LambdaQueryWrapper<SuperAgentDocumentTask>()
            .eq(SuperAgentDocumentTask::getDocumentId, document.getId())
            .eq(SuperAgentDocumentTask::getTaskType, taskType.getCode())
            .eq(SuperAgentDocumentTask::getStatus, BusinessStatus.YES.getCode())
            .orderByDesc(SuperAgentDocumentTask::getId)
            .last("limit 1"));
        return latestTask == null ? null : latestTask.getId();
    }

    private List<DocumentRagSnapshotVo.MetricItem> buildMetrics(long parseBlockCount,
                                                                long structureNodeCount,
                                                                long parentBlockCount,
                                                                long chunkCount,
                                                                long vectorReadyCount,
                                                                long tableCount,
                                                                long kgEntityCount,
                                                                long kgRelationCount,
                                                                long kgCommunityCount,
                                                                long raptorNodeCount,
                                                                GraphRagQualityReport graphRagQuality) {
        List<DocumentRagSnapshotVo.MetricItem> metrics = new ArrayList<>();
        metrics.add(new DocumentRagSnapshotVo.MetricItem("解析块", String.valueOf(parseBlockCount), "Python parser 输出的 layout/text/table/image block", tone(parseBlockCount)));
        metrics.add(new DocumentRagSnapshotVo.MetricItem("结构节点", String.valueOf(structureNodeCount), "Document -> Section -> Item 文档结构图", tone(structureNodeCount)));
        metrics.add(new DocumentRagSnapshotVo.MetricItem("父块", String.valueOf(parentBlockCount), "回答上下文使用的 ParentBlock", tone(parentBlockCount)));
        metrics.add(new DocumentRagSnapshotVo.MetricItem("子块", String.valueOf(chunkCount), "召回阶段使用的 ChildChunk", tone(chunkCount)));
        metrics.add(new DocumentRagSnapshotVo.MetricItem("向量成功", vectorReadyCount + "/" + chunkCount, "PGVector 中可参与语义检索的原文 chunk", chunkCount > 0 && vectorReadyCount == chunkCount ? "success" : "warning"));
        metrics.add(new DocumentRagSnapshotVo.MetricItem("表格", String.valueOf(tableCount), "结构化表格问答的表、列、行、单元格", tone(tableCount)));
        metrics.add(new DocumentRagSnapshotVo.MetricItem("知识图谱", kgEntityCount + " 实体 / " + kgRelationCount + " 关系", "GraphRAG 独立 KG，不等同于文档结构图", kgEntityCount > 0 || kgRelationCount > 0 ? "success" : "neutral"));
        metrics.add(new DocumentRagSnapshotVo.MetricItem("图谱社区", String.valueOf(kgCommunityCount), "GraphRAG 社区摘要用于跨实体导航", tone(kgCommunityCount)));
        appendGraphRagQualityMetrics(metrics, graphRagQuality);
        metrics.add(new DocumentRagSnapshotVo.MetricItem("RAPTOR 节点", String.valueOf(raptorNodeCount), "层级摘要节点，只负责召回导航", tone(raptorNodeCount)));
        return metrics;
    }

    private void appendGraphRagQualityMetrics(List<DocumentRagSnapshotVo.MetricItem> metrics,
                                              GraphRagQualityReport report) {
        if (report == null) {
            return;
        }
        metrics.add(new DocumentRagSnapshotVo.MetricItem(
            "图谱质量",
            percentText(report.getQualityScore()),
            report.getSummary(),
            graphQualityTone(report.getQualityLevel())
        ));
        metrics.add(new DocumentRagSnapshotVo.MetricItem(
            "图谱证据追溯",
            report.getTraceableEvidenceCount() + "/" + report.getEvidenceCount(),
            "evidence 需要同时有 chunkId 和 quoteText，覆盖率 " + percentText(report.getEvidenceTraceabilityCoverage()),
            ratioTone(report.getEvidenceTraceabilityCoverage(), report.getEvidenceCount())
        ));
        long controlledCount = safeLong(report.getControlledExtractionItemCount())
            + safeLong(report.getEntityResolutionEnhancedCount())
            + safeLong(report.getCommunityReportEnhancedCount());
        metrics.add(new DocumentRagSnapshotVo.MetricItem(
            "GraphRAG 受控增强",
            String.valueOf(controlledCount),
            "LLM 受控 extraction、entity resolution、community report 通过 Java 校验后的命中数",
            controlledCount > 0L ? "success" : "neutral"
        ));
    }

    private List<DocumentRagSnapshotVo.PipelineStageItem> buildPipelineStages(long parseBlockCount,
                                                                              long structureNodeCount,
                                                                              long parentBlockCount,
                                                                              long chunkCount,
                                                                              long tableCount,
                                                                              long kgEntityCount,
                                                                              long kgRelationCount,
                                                                              long kgCommunityCount,
                                                                              long raptorNodeCount) {
        return List.of(
            new DocumentRagSnapshotVo.PipelineStageItem("parse", "解析产物", statusText(parseBlockCount), "文件经 Python rag-tools 解析为带页码、bbox、章节路径的基础块。", parseBlockCount),
            new DocumentRagSnapshotVo.PipelineStageItem("structure", "文档结构图", statusText(structureNodeCount), "保留 Document -> Section -> Item 层级，用于目录导航和结构问答。", structureNodeCount),
            new DocumentRagSnapshotVo.PipelineStageItem("parent-child", "父子切块", statusText(chunkCount), "Java 策略流水线生成 ParentBlock 和 ChildChunk，child 负责召回，parent 负责回答上下文。", parentBlockCount + chunkCount),
            new DocumentRagSnapshotVo.PipelineStageItem("table", "表格结构化", statusText(tableCount), "表格被拆成表、列、行、单元格，支撑字段过滤和表格问答。", tableCount),
            new DocumentRagSnapshotVo.PipelineStageItem("graph-rag", "GraphRAG", statusText(kgEntityCount + kgRelationCount + kgCommunityCount), "实体、关系、证据、社区摘要是独立知识图谱，不和文档结构图混用。", kgEntityCount + kgRelationCount + kgCommunityCount),
            new DocumentRagSnapshotVo.PipelineStageItem("raptor", "RAPTOR", statusText(raptorNodeCount), "层级摘要树用于长文档导航，最终证据仍回落到原始 chunk。", raptorNodeCount),
            new DocumentRagSnapshotVo.PipelineStageItem("runtime", "运行时观测", "去对话观测查看", "检索通道、融合、rerank、引用修复属于每轮问答运行时数据。", 0L)
        );
    }

    private List<DocumentRagSnapshotVo.ParseBlockItem> listParseBlocks(Long documentId, Long parseTaskId) {
        if (parseTaskId == null) {
            return List.of();
        }
        return blockMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentBlock>()
                .eq(SuperAgentDocumentBlock::getDocumentId, documentId)
                .eq(SuperAgentDocumentBlock::getTaskId, parseTaskId)
                .eq(SuperAgentDocumentBlock::getStatus, BusinessStatus.YES.getCode())
                .orderByAsc(SuperAgentDocumentBlock::getBlockNo, SuperAgentDocumentBlock::getId)
                .last("limit " + PARSE_BLOCK_SAMPLE_SIZE))
            .stream()
            .map(item -> new DocumentRagSnapshotVo.ParseBlockItem(
                item.getId(),
                item.getBlockNo(),
                item.getBlockType(),
                item.getSectionPath(),
                item.getPageNo(),
                item.getPageRange(),
                item.getBboxJson(),
                preview(firstNotBlank(item.getText(), item.getTableHtml(), item.getImageCaption()))
            ))
            .toList();
    }

    private List<DocumentRagSnapshotVo.StructureNodeItem> listStructureNodes(Long documentId, Long parseTaskId) {
        if (parseTaskId == null) {
            return List.of();
        }
        return structureNodeMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentStructureNode>()
                .eq(SuperAgentDocumentStructureNode::getDocumentId, documentId)
                .eq(SuperAgentDocumentStructureNode::getParseTaskId, parseTaskId)
                .eq(SuperAgentDocumentStructureNode::getStatus, BusinessStatus.YES.getCode())
                .orderByAsc(SuperAgentDocumentStructureNode::getNodeNo, SuperAgentDocumentStructureNode::getId)
                .last("limit " + STRUCTURE_NODE_SAMPLE_SIZE))
            .stream()
            .map(item -> new DocumentRagSnapshotVo.StructureNodeItem(
                item.getId(),
                item.getNodeNo(),
                item.getNodeType(),
                item.getDepth(),
                item.getNodeCode(),
                item.getTitle(),
                item.getSectionPath(),
                preview(firstNotBlank(item.getAnchorText(), item.getContentText()))
            ))
            .toList();
    }

    private List<DocumentRagSnapshotVo.ParentBlockItem> listParentBlocks(Long documentId, Long indexTaskId) {
        if (indexTaskId == null) {
            return List.of();
        }
        return parentBlockMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentParentBlock>()
                .eq(SuperAgentDocumentParentBlock::getDocumentId, documentId)
                .eq(SuperAgentDocumentParentBlock::getTaskId, indexTaskId)
                .eq(SuperAgentDocumentParentBlock::getStatus, BusinessStatus.YES.getCode())
                .orderByAsc(SuperAgentDocumentParentBlock::getParentNo, SuperAgentDocumentParentBlock::getId)
                .last("limit " + PARENT_BLOCK_SAMPLE_SIZE))
            .stream()
            .map(item -> new DocumentRagSnapshotVo.ParentBlockItem(
                item.getId(),
                item.getParentNo(),
                item.getSectionPath(),
                item.getChildCount(),
                item.getStartChunkNo(),
                item.getEndChunkNo(),
                item.getPageRange(),
                preview(item.getParentText())
            ))
            .toList();
    }

    private List<DocumentRagSnapshotVo.ChunkItem> listChunks(Long documentId, Long indexTaskId) {
        if (indexTaskId == null) {
            return List.of();
        }
        return chunkMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentChunk>()
                .eq(SuperAgentDocumentChunk::getDocumentId, documentId)
                .eq(SuperAgentDocumentChunk::getTaskId, indexTaskId)
                .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode())
                .orderByAsc(SuperAgentDocumentChunk::getChunkNo, SuperAgentDocumentChunk::getId)
                .last("limit " + CHUNK_SAMPLE_SIZE))
            .stream()
            .map(item -> new DocumentRagSnapshotVo.ChunkItem(
                item.getId(),
                item.getParentBlockId(),
                item.getChunkNo(),
                item.getSectionPath(),
                item.getChunkType(),
                item.getTitle(),
                item.getKeywords(),
                item.getQuestions(),
                item.getVectorStatus(),
                enumMsg(DocumentVectorStatusEnum.getRc(item.getVectorStatus())),
                item.getTokenCount(),
                item.getPageRange(),
                item.getSourceBlockIds(),
                preview(firstNotBlank(item.getChunkText(), item.getContentWithWeight()))
            ))
            .toList();
    }

    private List<DocumentRagSnapshotVo.TableItem> listTables(Long documentId,
                                                             Long indexTaskId,
                                                             TableHighlightFocus highlightFocus) {
        if (indexTaskId == null) {
            return List.of();
        }
        List<SuperAgentDocumentTable> tableList = tableMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentTable>()
            .eq(SuperAgentDocumentTable::getDocumentId, documentId)
            .eq(SuperAgentDocumentTable::getTaskId, indexTaskId)
            .eq(SuperAgentDocumentTable::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentDocumentTable::getTableNo, SuperAgentDocumentTable::getId)
            .last("limit " + TABLE_SAMPLE_SIZE));
        if (tableList.isEmpty()) {
            tableList = new ArrayList<>();
        }
        tableList = includeHighlightedTable(documentId, indexTaskId, tableList, highlightFocus);
        if (tableList.isEmpty()) {
            return List.of();
        }

        Set<Long> tableIds = tableList.stream().map(SuperAgentDocumentTable::getId).collect(Collectors.toSet());
        Map<Long, List<SuperAgentDocumentTableColumn>> columnMap = tableColumnMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentTableColumn>()
                .in(SuperAgentDocumentTableColumn::getTableId, tableIds)
                .eq(SuperAgentDocumentTableColumn::getStatus, BusinessStatus.YES.getCode())
                .orderByAsc(SuperAgentDocumentTableColumn::getColumnNo, SuperAgentDocumentTableColumn::getId))
            .stream()
            .collect(Collectors.groupingBy(SuperAgentDocumentTableColumn::getTableId, LinkedHashMap::new, Collectors.toList()));

        Map<Long, List<SuperAgentDocumentTableRow>> rowMap = tableRowMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentTableRow>()
                .in(SuperAgentDocumentTableRow::getTableId, tableIds)
                .eq(SuperAgentDocumentTableRow::getStatus, BusinessStatus.YES.getCode())
                .orderByAsc(SuperAgentDocumentTableRow::getRowNo, SuperAgentDocumentTableRow::getId))
            .stream()
            .collect(Collectors.groupingBy(SuperAgentDocumentTableRow::getTableId, LinkedHashMap::new, Collectors.toList()));

        Set<Long> sampledRowIds = rowMap.values().stream()
            .flatMap(List::stream)
            .sorted(Comparator.comparing(SuperAgentDocumentTableRow::getTableId)
                .thenComparing(SuperAgentDocumentTableRow::getRowNo)
                .thenComparing(SuperAgentDocumentTableRow::getId))
            .collect(Collectors.groupingBy(SuperAgentDocumentTableRow::getTableId, LinkedHashMap::new, Collectors.toList()))
            .values()
            .stream()
            .flatMap(rows -> rows.stream().limit(TABLE_ROW_SAMPLE_SIZE))
            .map(SuperAgentDocumentTableRow::getId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (highlightFocus.hasRows()) {
            rowMap.values().stream()
                .flatMap(List::stream)
                .filter(row -> highlightFocus.matchesRow(row.getTableId(), row.getRowNo()))
                .map(SuperAgentDocumentTableRow::getId)
                .forEach(sampledRowIds::add);
        }

        Map<Long, List<SuperAgentDocumentTableCell>> cellMap = sampledRowIds.isEmpty()
            ? Map.of()
            : tableCellMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentTableCell>()
                    .in(SuperAgentDocumentTableCell::getRowId, sampledRowIds)
                    .eq(SuperAgentDocumentTableCell::getStatus, BusinessStatus.YES.getCode())
                    .orderByAsc(SuperAgentDocumentTableCell::getColumnNo, SuperAgentDocumentTableCell::getId))
                .stream()
                .collect(Collectors.groupingBy(SuperAgentDocumentTableCell::getRowId, LinkedHashMap::new, Collectors.toList()));

        return tableList.stream()
            .map(table -> toTableItem(table, columnMap.getOrDefault(table.getId(), List.of()),
                sampleRows(table, rowMap.getOrDefault(table.getId(), List.of()), highlightFocus), cellMap))
            .toList();
    }

    private List<SuperAgentDocumentTable> includeHighlightedTable(Long documentId,
                                                                  Long indexTaskId,
                                                                  List<SuperAgentDocumentTable> tableList,
                                                                  TableHighlightFocus highlightFocus) {
        if (!highlightFocus.hasTable()) {
            return tableList;
        }
        Map<Long, SuperAgentDocumentTable> tableById = tableList.stream()
            .collect(Collectors.toMap(SuperAgentDocumentTable::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        SuperAgentDocumentTable highlightedTable = null;
        if (highlightFocus.tableId() != null && !tableById.containsKey(highlightFocus.tableId())) {
            highlightedTable = tableMapper.selectById(highlightFocus.tableId());
        }
        if (highlightedTable == null && highlightFocus.tableNo() != null
            && tableList.stream().noneMatch(table -> Objects.equals(table.getTableNo(), highlightFocus.tableNo()))) {
            highlightedTable = tableMapper.selectOne(new LambdaQueryWrapper<SuperAgentDocumentTable>()
                .eq(SuperAgentDocumentTable::getDocumentId, documentId)
                .eq(SuperAgentDocumentTable::getTaskId, indexTaskId)
                .eq(SuperAgentDocumentTable::getTableNo, highlightFocus.tableNo())
                .eq(SuperAgentDocumentTable::getStatus, BusinessStatus.YES.getCode())
                .last("limit 1"));
        }
        if (highlightedTable == null
            || !Objects.equals(highlightedTable.getDocumentId(), documentId)
            || !Objects.equals(highlightedTable.getTaskId(), indexTaskId)
            || !Objects.equals(highlightedTable.getStatus(), BusinessStatus.YES.getCode())) {
            return tableList;
        }
        tableById.putIfAbsent(highlightedTable.getId(), highlightedTable);
        return new ArrayList<>(tableById.values());
    }

    private List<SuperAgentDocumentTableRow> sampleRows(SuperAgentDocumentTable table,
                                                        List<SuperAgentDocumentTableRow> rows,
                                                        TableHighlightFocus highlightFocus) {
        Map<Long, SuperAgentDocumentTableRow> sampled = new LinkedHashMap<>();
        rows.stream()
            .sorted(Comparator.comparing(SuperAgentDocumentTableRow::getRowNo).thenComparing(SuperAgentDocumentTableRow::getId))
            .limit(TABLE_ROW_SAMPLE_SIZE)
            .forEach(row -> sampled.put(row.getId(), row));
        if (highlightFocus.matchesTable(table)) {
            rows.stream()
                .filter(row -> highlightFocus.matchesRow(row.getTableId(), row.getRowNo()))
                .sorted(Comparator.comparing(SuperAgentDocumentTableRow::getRowNo).thenComparing(SuperAgentDocumentTableRow::getId))
                .forEach(row -> sampled.putIfAbsent(row.getId(), row));
        }
        return new ArrayList<>(sampled.values());
    }

    private DocumentRagSnapshotVo.TableItem toTableItem(SuperAgentDocumentTable table,
                                                        List<SuperAgentDocumentTableColumn> columns,
                                                        List<SuperAgentDocumentTableRow> rows,
                                                        Map<Long, List<SuperAgentDocumentTableCell>> cellMap) {
        List<DocumentRagSnapshotVo.TableColumnItem> columnItems = columns.stream()
            .map(column -> new DocumentRagSnapshotVo.TableColumnItem(
                column.getId(),
                column.getColumnNo(),
                column.getColumnName(),
                column.getNormalizedName(),
                column.getValueType()
            ))
            .toList();

        List<DocumentRagSnapshotVo.TableRowItem> rowItems = rows.stream()
            .map(row -> new DocumentRagSnapshotVo.TableRowItem(
                row.getId(),
                row.getRowNo(),
                preview(row.getRowText()),
                cellMap.getOrDefault(row.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(SuperAgentDocumentTableCell::getColumnNo)
                        .thenComparing(SuperAgentDocumentTableCell::getId))
                    .map(SuperAgentDocumentTableCell::getCellText)
                    .toList(),
                cellMap.getOrDefault(row.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(SuperAgentDocumentTableCell::getColumnNo)
                        .thenComparing(SuperAgentDocumentTableCell::getId))
                    .map(cell -> new DocumentRagSnapshotVo.TableCellItem(
                        cell.getId(),
                        cell.getColumnId(),
                        cell.getRowNo(),
                        cell.getColumnNo(),
                        cell.getCellText(),
                        cell.getSourceRowNo(),
                        cell.getSourceColumnNo(),
                        cell.getSourceCellRef(),
                        cell.getBboxJson()
                    ))
                    .toList()
            ))
            .toList();

        return new DocumentRagSnapshotVo.TableItem(
            table.getId(),
            table.getTableNo(),
            table.getTitle(),
            table.getSectionPath(),
            table.getPageNo(),
            table.getPageRange(),
            table.getRowCount(),
            table.getColumnCount(),
            table.getBboxJson(),
            columnItems,
            rowItems
        );
    }

    private TableHighlightFocus tableHighlightFocus(DocumentRagSnapshotQueryDto dto) {
        return new TableHighlightFocus(
            dto.getHighlightTableId(),
            dto.getHighlightTableNo(),
            dto.getHighlightRowNos() == null ? Set.of() : new HashSet<>(dto.getHighlightRowNos()),
            dto.getHighlightColumnNames() == null ? Set.of() : new HashSet<>(dto.getHighlightColumnNames()),
            dto.getHighlightCellCoordinates() == null ? Set.of() : new HashSet<>(dto.getHighlightCellCoordinates())
        );
    }

    private record TableHighlightFocus(Long tableId,
                                       Integer tableNo,
                                       Set<Integer> rowNos,
                                       Set<String> columnNames,
                                       Set<String> cellCoordinates) {

        private boolean hasTable() {
            return tableId != null || tableNo != null;
        }

        private boolean hasRows() {
            return rowNos != null && !rowNos.isEmpty();
        }

        private boolean matchesTable(SuperAgentDocumentTable table) {
            if (table == null) {
                return false;
            }
            if (tableId != null) {
                return Objects.equals(table.getId(), tableId);
            }
            if (tableNo != null) {
                return Objects.equals(table.getTableNo(), tableNo);
            }
            return !hasTable();
        }

        private boolean matchesRow(Long candidateTableId, Integer rowNo) {
            if (tableId != null && !Objects.equals(candidateTableId, tableId)) {
                return false;
            }
            return rowNo != null && rowNos != null && rowNos.contains(rowNo);
        }
    }

    private List<DocumentRagSnapshotVo.KgEntityItem> listKgEntities(Long documentId, Long indexTaskId) {
        if (indexTaskId == null) {
            return List.of();
        }
        return kgEntityMapper.selectList(new LambdaQueryWrapper<SuperAgentKgEntity>()
                .eq(SuperAgentKgEntity::getDocumentId, documentId)
                .eq(SuperAgentKgEntity::getTaskId, indexTaskId)
                .eq(SuperAgentKgEntity::getStatus, BusinessStatus.YES.getCode())
                .orderByAsc(SuperAgentKgEntity::getEntityType, SuperAgentKgEntity::getName, SuperAgentKgEntity::getId)
                .last("limit " + KG_ENTITY_SAMPLE_SIZE))
            .stream()
            .map(item -> new DocumentRagSnapshotVo.KgEntityItem(
                item.getId(),
                item.getName(),
                item.getEntityType(),
                preview(item.getDescription())
            ))
            .toList();
    }

    private List<DocumentRagSnapshotVo.KgRelationItem> listKgRelations(Long documentId, Long indexTaskId) {
        if (indexTaskId == null) {
            return List.of();
        }
        List<SuperAgentKgRelation> relations = kgRelationMapper.selectList(new LambdaQueryWrapper<SuperAgentKgRelation>()
            .eq(SuperAgentKgRelation::getDocumentId, documentId)
            .eq(SuperAgentKgRelation::getTaskId, indexTaskId)
            .eq(SuperAgentKgRelation::getStatus, BusinessStatus.YES.getCode())
            .orderByDesc(SuperAgentKgRelation::getWeight)
            .orderByAsc(SuperAgentKgRelation::getId)
            .last("limit " + KG_RELATION_SAMPLE_SIZE));
        if (relations.isEmpty()) {
            return List.of();
        }

        Set<Long> entityIds = relations.stream()
            .flatMap(relation -> {
                List<Long> ids = new ArrayList<>();
                if (relation.getSourceEntityId() != null) {
                    ids.add(relation.getSourceEntityId());
                }
                if (relation.getTargetEntityId() != null) {
                    ids.add(relation.getTargetEntityId());
                }
                return ids.stream();
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, SuperAgentKgEntity> entityMap = entityIds.isEmpty()
            ? Map.of()
            : kgEntityMapper.selectList(new LambdaQueryWrapper<SuperAgentKgEntity>()
                    .in(SuperAgentKgEntity::getId, entityIds)
                    .eq(SuperAgentKgEntity::getStatus, BusinessStatus.YES.getCode()))
                .stream()
                .collect(Collectors.toMap(SuperAgentKgEntity::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));

        return relations.stream()
            .map(relation -> new DocumentRagSnapshotVo.KgRelationItem(
                relation.getId(),
                relation.getSourceEntityId(),
                entityName(entityMap.get(relation.getSourceEntityId())),
                relation.getTargetEntityId(),
                entityName(entityMap.get(relation.getTargetEntityId())),
                relation.getRelationType(),
                preview(relation.getDescription()),
                decimalText(relation.getWeight())
            ))
            .toList();
    }

    private List<DocumentRagSnapshotVo.KgCommunityItem> listKgCommunities(Long documentId, Long indexTaskId) {
        if (indexTaskId == null) {
            return List.of();
        }
        return kgCommunityMapper.selectList(new LambdaQueryWrapper<SuperAgentKgCommunity>()
                .eq(SuperAgentKgCommunity::getDocumentId, documentId)
                .eq(SuperAgentKgCommunity::getTaskId, indexTaskId)
                .eq(SuperAgentKgCommunity::getStatus, BusinessStatus.YES.getCode())
                .orderByAsc(SuperAgentKgCommunity::getCommunityNo, SuperAgentKgCommunity::getId)
                .last("limit " + KG_COMMUNITY_SAMPLE_SIZE))
            .stream()
            .map(item -> new DocumentRagSnapshotVo.KgCommunityItem(
                item.getId(),
                item.getCommunityNo(),
                item.getTitle(),
                preview(item.getSummary()),
                item.getEntityIdsJson()
            ))
            .toList();
    }

    private List<DocumentRagSnapshotVo.RaptorNodeItem> listRaptorNodes(Long documentId, Long indexTaskId) {
        if (indexTaskId == null) {
            return List.of();
        }
        List<SuperAgentRaptorNode> nodes = raptorNodeMapper.selectList(new LambdaQueryWrapper<SuperAgentRaptorNode>()
                .eq(SuperAgentRaptorNode::getDocumentId, documentId)
                .eq(SuperAgentRaptorNode::getTaskId, indexTaskId)
                .eq(SuperAgentRaptorNode::getStatus, BusinessStatus.YES.getCode())
                .orderByDesc(SuperAgentRaptorNode::getNodeLevel)
                .orderByAsc(SuperAgentRaptorNode::getNodeNo, SuperAgentRaptorNode::getId)
                .last("limit " + RAPTOR_NODE_SAMPLE_SIZE));
        if (nodes.isEmpty()) {
            return List.of();
        }

        Map<Long, SuperAgentRaptorNode> nodeMap = nodes.stream()
            .collect(Collectors.toMap(SuperAgentRaptorNode::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Map<Long, SuperAgentDocumentChunk> chunkMap = loadRaptorSourceChunks(nodes);
        Map<Long, SuperAgentDocumentParentBlock> parentBlockMap = loadRaptorSourceParentBlocks(nodes);

        return nodes.stream()
            .map(item -> toRaptorNodeItem(item, nodeMap, chunkMap, parentBlockMap))
            .toList();
    }

    private Map<Long, SuperAgentDocumentChunk> loadRaptorSourceChunks(List<SuperAgentRaptorNode> nodes) {
        Set<Long> chunkIds = nodes.stream()
            .flatMap(node -> readLongList(node.getSourceChunkIdsJson()).stream())
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (chunkIds.isEmpty()) {
            return Map.of();
        }
        return chunkMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentChunk>()
                .in(SuperAgentDocumentChunk::getId, chunkIds)
                .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode()))
            .stream()
            .collect(Collectors.toMap(SuperAgentDocumentChunk::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    private Map<Long, SuperAgentDocumentParentBlock> loadRaptorSourceParentBlocks(List<SuperAgentRaptorNode> nodes) {
        Set<Long> parentBlockIds = nodes.stream()
            .flatMap(node -> readLongList(node.getSourceParentBlockIdsJson()).stream())
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (parentBlockIds.isEmpty()) {
            return Map.of();
        }
        return parentBlockMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentParentBlock>()
                .in(SuperAgentDocumentParentBlock::getId, parentBlockIds)
                .eq(SuperAgentDocumentParentBlock::getStatus, BusinessStatus.YES.getCode()))
            .stream()
            .collect(Collectors.toMap(SuperAgentDocumentParentBlock::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    private DocumentRagSnapshotVo.RaptorNodeItem toRaptorNodeItem(SuperAgentRaptorNode node,
                                                                  Map<Long, SuperAgentRaptorNode> nodeMap,
                                                                  Map<Long, SuperAgentDocumentChunk> chunkMap,
                                                                  Map<Long, SuperAgentDocumentParentBlock> parentBlockMap) {
        List<Long> sourceChunkIds = readLongList(node.getSourceChunkIdsJson());
        List<Long> sourceParentBlockIds = readLongList(node.getSourceParentBlockIdsJson());
        List<Long> childNodeIds = readLongList(node.getChildNodeIdsJson());
        Map<String, Object> metadata = readMap(node.getMetadataJson());
        Map<String, Object> sourceMetadata = objectMap(metadata.get("sourceMetadata"));
        Map<String, Object> qualitySignals = objectMap(sourceMetadata.get("summaryQualitySignals"));
        Map<String, Object> clusterSignals = objectMap(sourceMetadata.get("clusterQualitySignals"));
        Double qualityScore = doubleValue(metadata.get("summaryQualityScore"));

        return new DocumentRagSnapshotVo.RaptorNodeItem(
            node.getId(),
            node.getNodeKey(),
            node.getParentNodeId(),
            node.getNodeLevel(),
            node.getNodeNo(),
            node.getTitle(),
            preview(node.getSummary()),
            preview(node.getSummaryWithWeight()),
            node.getSourceChunkIdsJson(),
            node.getSourceParentBlockIdsJson(),
            sourceChunkIds,
            sourceParentBlockIds,
            sourceChunkIds.size(),
            sourceParentBlockIds.size(),
            childNodeIds,
            childNodeIds.size(),
            node.getSectionPath(),
            node.getPageRange(),
            node.getKeywords(),
            node.getQuestions(),
            qualityScore,
            raptorNodeQualityLevel(qualityScore),
            raptorNodeQualityRisk(qualityScore),
            stringValue(firstPresent(sourceMetadata.get("summaryStrategy"), metadata.get("summaryStrategy"))),
            stringValue(firstPresent(sourceMetadata.get("clusterMethod"), metadata.get("clusterMethod"))),
            stringValue(firstPresent(sourceMetadata.get("treeBuilderMethod"), metadata.get("treeBuilderMethod"))),
            doubleValue(clusterSignals.get("avgClusterSize")),
            integerValue(clusterSignals.get("maxClusterSizeObserved")),
            integerValue(clusterSignals.get("singletonClusterCount")),
            doubleValue(clusterSignals.get("levelCompressionRatio")),
            doubleValue(clusterSignals.get("avgIntraClusterSimilarity")),
            doubleValue(clusterSignals.get("treeBalanceScore")),
            booleanValue(qualitySignals.get("abstractive")),
            stringValue(qualitySignals.get("llmSummaryStatus")),
            raptorTreeDepth(node, nodeMap),
            raptorTreePath(node, nodeMap),
            sourceChunkItems(sourceChunkIds, chunkMap),
            sourceParentBlockItems(sourceParentBlockIds, parentBlockMap)
        );
    }

    private List<DocumentRagSnapshotVo.RaptorSourceChunkItem> sourceChunkItems(List<Long> sourceChunkIds,
                                                                               Map<Long, SuperAgentDocumentChunk> chunkMap) {
        return sourceChunkIds.stream()
            .map(chunkMap::get)
            .filter(Objects::nonNull)
            .limit(RAPTOR_SOURCE_SAMPLE_SIZE)
            .map(chunk -> new DocumentRagSnapshotVo.RaptorSourceChunkItem(
                chunk.getId(),
                chunk.getParentBlockId(),
                chunk.getChunkNo(),
                chunk.getSectionPath(),
                chunk.getTitle(),
                chunk.getPageRange(),
                preview(firstNotBlank(chunk.getChunkText(), chunk.getContentWithWeight()))
            ))
            .toList();
    }

    private List<DocumentRagSnapshotVo.RaptorSourceParentBlockItem> sourceParentBlockItems(List<Long> sourceParentBlockIds,
                                                                                           Map<Long, SuperAgentDocumentParentBlock> parentBlockMap) {
        return sourceParentBlockIds.stream()
            .map(parentBlockMap::get)
            .filter(Objects::nonNull)
            .limit(RAPTOR_SOURCE_SAMPLE_SIZE)
            .map(parent -> new DocumentRagSnapshotVo.RaptorSourceParentBlockItem(
                parent.getId(),
                parent.getParentNo(),
                parent.getSectionPath(),
                parent.getChildCount(),
                parent.getPageRange(),
                preview(parent.getParentText())
            ))
            .toList();
    }

    private Integer raptorTreeDepth(SuperAgentRaptorNode node, Map<Long, SuperAgentRaptorNode> nodeMap) {
        return raptorPathNodes(node, nodeMap).size();
    }

    private String raptorTreePath(SuperAgentRaptorNode node, Map<Long, SuperAgentRaptorNode> nodeMap) {
        return raptorPathNodes(node, nodeMap).stream()
            .map(pathNode -> StrUtil.blankToDefault(pathNode.getTitle(), pathNode.getNodeKey()))
            .filter(StrUtil::isNotBlank)
            .collect(Collectors.joining(" > "));
    }

    private List<SuperAgentRaptorNode> raptorPathNodes(SuperAgentRaptorNode node, Map<Long, SuperAgentRaptorNode> nodeMap) {
        List<SuperAgentRaptorNode> path = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        SuperAgentRaptorNode cursor = node;
        while (cursor != null && cursor.getId() != null && visited.add(cursor.getId()) && path.size() < 20) {
            path.add(cursor);
            cursor = cursor.getParentNodeId() == null ? null : nodeMap.get(cursor.getParentNodeId());
        }
        java.util.Collections.reverse(path);
        return path;
    }

    private List<DocumentTaskLogVo> listBuildLogs(Long indexTaskId) {
        if (indexTaskId == null) {
            return List.of();
        }
        return taskLogMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentTaskLog>()
                .eq(SuperAgentDocumentTaskLog::getTaskId, indexTaskId)
                .eq(SuperAgentDocumentTaskLog::getStatus, BusinessStatus.YES.getCode())
                .orderByAsc(SuperAgentDocumentTaskLog::getCreateTime, SuperAgentDocumentTaskLog::getId)
                .last("limit " + BUILD_LOG_SAMPLE_SIZE))
            .stream()
            .map(this::toTaskLogVo)
            .toList();
    }

    private long countBlocks(Long documentId, Long taskId) {
        return blockMapper.selectCount(new LambdaQueryWrapper<SuperAgentDocumentBlock>()
            .eq(SuperAgentDocumentBlock::getDocumentId, documentId)
            .eq(SuperAgentDocumentBlock::getTaskId, taskId)
            .eq(SuperAgentDocumentBlock::getStatus, BusinessStatus.YES.getCode()));
    }

    private long countStructureNodes(Long documentId, Long taskId) {
        return structureNodeMapper.selectCount(new LambdaQueryWrapper<SuperAgentDocumentStructureNode>()
            .eq(SuperAgentDocumentStructureNode::getDocumentId, documentId)
            .eq(SuperAgentDocumentStructureNode::getParseTaskId, taskId)
            .eq(SuperAgentDocumentStructureNode::getStatus, BusinessStatus.YES.getCode()));
    }

    private long countParentBlocks(Long documentId, Long taskId) {
        return parentBlockMapper.selectCount(new LambdaQueryWrapper<SuperAgentDocumentParentBlock>()
            .eq(SuperAgentDocumentParentBlock::getDocumentId, documentId)
            .eq(SuperAgentDocumentParentBlock::getTaskId, taskId)
            .eq(SuperAgentDocumentParentBlock::getStatus, BusinessStatus.YES.getCode()));
    }

    private long countChunks(Long documentId, Long taskId) {
        return chunkMapper.selectCount(new LambdaQueryWrapper<SuperAgentDocumentChunk>()
            .eq(SuperAgentDocumentChunk::getDocumentId, documentId)
            .eq(SuperAgentDocumentChunk::getTaskId, taskId)
            .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode()));
    }

    private long countVectorReadyChunks(Long documentId, Long taskId) {
        return chunkMapper.selectCount(new LambdaQueryWrapper<SuperAgentDocumentChunk>()
            .eq(SuperAgentDocumentChunk::getDocumentId, documentId)
            .eq(SuperAgentDocumentChunk::getTaskId, taskId)
            .eq(SuperAgentDocumentChunk::getVectorStatus, DocumentVectorStatusEnum.VECTOR_SUCCESS.getCode())
            .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode()));
    }

    private long countTables(Long documentId, Long taskId) {
        return tableMapper.selectCount(new LambdaQueryWrapper<SuperAgentDocumentTable>()
            .eq(SuperAgentDocumentTable::getDocumentId, documentId)
            .eq(SuperAgentDocumentTable::getTaskId, taskId)
            .eq(SuperAgentDocumentTable::getStatus, BusinessStatus.YES.getCode()));
    }

    private long countKgEntities(Long documentId, Long taskId) {
        return kgEntityMapper.selectCount(new LambdaQueryWrapper<SuperAgentKgEntity>()
            .eq(SuperAgentKgEntity::getDocumentId, documentId)
            .eq(SuperAgentKgEntity::getTaskId, taskId)
            .eq(SuperAgentKgEntity::getStatus, BusinessStatus.YES.getCode()));
    }

    private long countKgRelations(Long documentId, Long taskId) {
        return kgRelationMapper.selectCount(new LambdaQueryWrapper<SuperAgentKgRelation>()
            .eq(SuperAgentKgRelation::getDocumentId, documentId)
            .eq(SuperAgentKgRelation::getTaskId, taskId)
            .eq(SuperAgentKgRelation::getStatus, BusinessStatus.YES.getCode()));
    }

    private long countKgCommunities(Long documentId, Long taskId) {
        return kgCommunityMapper.selectCount(new LambdaQueryWrapper<SuperAgentKgCommunity>()
            .eq(SuperAgentKgCommunity::getDocumentId, documentId)
            .eq(SuperAgentKgCommunity::getTaskId, taskId)
            .eq(SuperAgentKgCommunity::getStatus, BusinessStatus.YES.getCode()));
    }

    private long countRaptorNodes(Long documentId, Long taskId) {
        return raptorNodeMapper.selectCount(new LambdaQueryWrapper<SuperAgentRaptorNode>()
            .eq(SuperAgentRaptorNode::getDocumentId, documentId)
            .eq(SuperAgentRaptorNode::getTaskId, taskId)
            .eq(SuperAgentRaptorNode::getStatus, BusinessStatus.YES.getCode()));
    }

    private DocumentTaskLogVo toTaskLogVo(SuperAgentDocumentTaskLog logRecord) {
        return new DocumentTaskLogVo(
            logRecord.getId(),
            logRecord.getStageType(),
            enumMsg(DocumentTaskStageEnum.getRc(logRecord.getStageType())),
            logRecord.getEventType(),
            enumMsg(DocumentTaskEventTypeEnum.getRc(logRecord.getEventType())),
            logRecord.getLogLevel(),
            enumMsg(DocumentLogLevelEnum.getRc(logRecord.getLogLevel())),
            logRecord.getContent(),
            logRecord.getDetailJson(),
            logRecord.getCreateTime()
        );
    }

    private String statusText(long count) {
        return count > 0 ? "已生成" : "暂无数据";
    }

    private String tone(long count) {
        return count > 0 ? "success" : "neutral";
    }

    private String graphQualityTone(String level) {
        if (GraphRagQualityReport.LEVEL_STRONG.equals(level)) {
            return "success";
        }
        if (GraphRagQualityReport.LEVEL_WATCH.equals(level)) {
            return "warning";
        }
        if (GraphRagQualityReport.LEVEL_WEAK.equals(level)) {
            return "danger";
        }
        return "neutral";
    }

    private String raptorNodeQualityLevel(Double qualityScore) {
        double score = qualityScore == null ? 0D : qualityScore;
        if (score < qualityFloor()) {
            return "BLOCKED";
        }
        if (score < 0.55D) {
            return "LOW";
        }
        if (score < 0.68D) {
            return "WATCH";
        }
        if (score >= 0.82D) {
            return "HIGH";
        }
        return "OK";
    }

    private String raptorNodeQualityRisk(Double qualityScore) {
        double score = qualityScore == null ? 0D : qualityScore;
        double floor = qualityFloor();
        if (score < floor) {
            return "低于当前入库阈值 " + percentText(floor) + "，正常情况下不应进入检索索引，请复查构建阈值和旧数据。";
        }
        if (score < 0.55D) {
            return "低质量摘要，建议检查 source chunk 覆盖、摘要是否过短或过度摘句。";
        }
        if (score < 0.68D) {
            return "观察区间摘要，真实问答若频繁命中但 citation 弱，应优先调 prompt 或聚类。";
        }
        return "摘要质量处于可用区间，继续结合 RAPTOR 命中和最终引用观察。";
    }

    private double qualityFloor() {
        return Math.max(0D, Math.min(1D, chatRagProperties.getRaptorSummaryQualityFloor()));
    }

    private String ratioTone(Double ratio, Long denominator) {
        if (denominator == null || denominator <= 0L) {
            return "neutral";
        }
        double value = ratio == null ? 0D : ratio;
        if (value >= 0.85D) {
            return "success";
        }
        if (value >= 0.65D) {
            return "warning";
        }
        return "danger";
    }

    private String percentText(Double value) {
        double normalized = value == null ? 0D : Math.max(0D, Math.min(1D, value));
        return Math.round(normalized * 100D) + "%";
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private String preview(String value) {
        String normalized = StrUtil.trimToEmpty(value)
            .replace("\r", " ")
            .replace("\n", " ")
            .replaceAll("\\s+", " ");
        return StrUtil.maxLength(normalized, PREVIEW_LENGTH);
    }

    private String firstNotBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private String entityName(SuperAgentKgEntity entity) {
        return entity == null ? "" : StrUtil.blankToDefault(entity.getName(), entity.getEntityKey());
    }

    private String decimalText(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private List<Long> readLongList(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, LONG_LIST_TYPE).stream()
                .filter(Objects::nonNull)
                .toList();
        }
        catch (Exception exception) {
            return List.of();
        }
    }

    private Map<String, Object> readMap(String json) {
        if (StrUtil.isBlank(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        }
        catch (Exception exception) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key != null) {
                    result.put(String.valueOf(key), item);
                }
            });
            return result;
        }
        return Map.of();
    }

    private Object firstPresent(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value instanceof String text && StrUtil.isBlank(text)) {
                continue;
            }
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && StrUtil.isNotBlank(text)) {
            try {
                return Double.parseDouble(text);
            }
            catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StrUtil.isNotBlank(text)) {
            try {
                return Integer.parseInt(text);
            }
            catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && StrUtil.isNotBlank(text)) {
            return Boolean.parseBoolean(text);
        }
        return null;
    }

    private String enumMsg(Object enumObject) {
        if (enumObject == null) {
            return "";
        }
        if (enumObject instanceof DocumentVectorStatusEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentTaskStageEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentTaskEventTypeEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentLogLevelEnum value) {
            return value.getMsg();
        }
        if (enumObject instanceof DocumentTaskStatusEnum value) {
            return value.getMsg();
        }
        return "";
    }
}
