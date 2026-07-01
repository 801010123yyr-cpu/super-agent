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
import org.javaup.ai.manage.data.SuperAgentDocumentParseArtifact;
import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;
import org.javaup.ai.manage.data.SuperAgentDocumentTable;
import org.javaup.ai.manage.data.SuperAgentDocumentTableCell;
import org.javaup.ai.manage.data.SuperAgentDocumentTableColumn;
import org.javaup.ai.manage.data.SuperAgentDocumentTableRow;
import org.javaup.ai.manage.data.SuperAgentDocumentTask;
import org.javaup.ai.manage.data.SuperAgentDocumentTaskLog;
import org.javaup.ai.manage.data.SuperAgentKgCommunity;
import org.javaup.ai.manage.data.SuperAgentKgEntity;
import org.javaup.ai.manage.data.SuperAgentKgEvidence;
import org.javaup.ai.manage.data.SuperAgentKgRelation;
import org.javaup.ai.manage.data.SuperAgentRaptorNode;
import org.javaup.ai.manage.dto.DocumentRagSnapshotQueryDto;
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
import org.javaup.ai.manage.model.graph.GraphRagQualityReport;
import org.javaup.ai.manage.model.raptor.RaptorQualityReport;
import org.javaup.ai.manage.service.GraphRagQualityService;
import org.javaup.ai.manage.service.DocumentRagSnapshotService;
import org.javaup.ai.manage.service.DocumentStorageService;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private static final int ARTIFACT_GRAPH_PARSE_BLOCK_LIMIT = 60;

    private static final int ARTIFACT_GRAPH_PARENT_LIMIT = 60;

    private static final int ARTIFACT_GRAPH_CHUNK_LIMIT = 90;

    private static final int ARTIFACT_GRAPH_TABLE_LIMIT = 30;

    private static final int ARTIFACT_GRAPH_KG_EVIDENCE_LIMIT = 90;

    private static final int ARTIFACT_GRAPH_RAPTOR_LIMIT = 60;

    private static final int KG_GRAPH_ENTITY_LIMIT = 80;

    private static final int KG_GRAPH_RELATION_LIMIT = 120;

    private static final int KG_GRAPH_COMMUNITY_LIMIT = 40;

    private static final int KG_GRAPH_EVIDENCE_LIMIT = 160;

    private static final Set<String> PAGE_OVERLAY_BLOCK_TYPES = Set.of("TITLE", "TEXT", "TABLE", "FIGURE", "IMAGE");

    private static final Pattern PAGE_IMAGE_OBJECT_NAME_PATTERN = Pattern.compile("(?i)(?:^|[./_-])page-(\\d+)\\.png$");

    private final SuperAgentDocumentMapper documentMapper;

    private final SuperAgentDocumentTaskMapper taskMapper;

    private final SuperAgentDocumentTaskLogMapper taskLogMapper;

    private final SuperAgentDocumentBlockMapper blockMapper;

    private final SuperAgentDocumentParseArtifactMapper parseArtifactMapper;

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

    private final SuperAgentKgEvidenceMapper kgEvidenceMapper;

    private final SuperAgentRaptorNodeMapper raptorNodeMapper;

    private final GraphRagQualityService graphRagQualityService;

    private final RaptorQualityService raptorQualityService;

    private final DocumentStorageService storageService;

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
        long tableCount = parseTaskId == null ? 0L : countTables(document.getId(), parseTaskId);
        long kgEntityCount = indexTaskId == null ? 0L : countKgEntities(document.getId(), indexTaskId);
        long kgRelationCount = indexTaskId == null ? 0L : countKgRelations(document.getId(), indexTaskId);
        long kgCommunityCount = indexTaskId == null ? 0L : countKgCommunities(document.getId(), indexTaskId);
        long raptorNodeCount = indexTaskId == null ? 0L : countRaptorNodes(document.getId(), indexTaskId);
        GraphRagQualityReport graphRagQuality = graphRagQualityService.evaluate(document.getId(), indexTaskId);
        RaptorQualityReport raptorQuality = raptorQualityService.evaluate(document.getId(), indexTaskId);
        List<DocumentRagSnapshotVo.ParseBlockItem> parseBlocks = listParseBlocks(document.getId(), parseTaskId);
        List<DocumentRagSnapshotVo.StructureNodeItem> structureNodes = listStructureNodes(document.getId(), parseTaskId);
        List<DocumentRagSnapshotVo.ParentBlockItem> parentBlocks = listParentBlocks(document.getId(), indexTaskId);
        List<DocumentRagSnapshotVo.ChunkItem> chunks = listChunks(document.getId(), indexTaskId);
        List<DocumentRagSnapshotVo.TableItem> tables = listTables(document.getId(), parseTaskId, tableHighlightFocus(dto));
        List<DocumentRagSnapshotVo.PageOverlayItem> pageOverlays = listPageOverlays(document.getId(), parseTaskId);
        List<DocumentRagSnapshotVo.KgEntityItem> kgEntities = listKgEntities(document.getId(), indexTaskId);
        List<DocumentRagSnapshotVo.KgRelationItem> kgRelations = listKgRelations(document.getId(), indexTaskId);
        List<DocumentRagSnapshotVo.KgCommunityItem> kgCommunities = listKgCommunities(document.getId(), indexTaskId);
        List<DocumentRagSnapshotVo.RaptorNodeItem> raptorNodes = listRaptorNodes(document.getId(), indexTaskId);

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
            buildParserTrace(parseTaskId),
            parseBlocks,
            structureNodes,
            parentBlocks,
            chunks,
            tables,
            pageOverlays,
            buildArtifactGraph(document.getId(), parseTaskId, indexTaskId),
            kgEntities,
            kgRelations,
            kgCommunities,
            buildKgGraph(document.getId(), indexTaskId),
            raptorNodes,
            buildRaptorTree(raptorNodes),
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

    private List<DocumentRagSnapshotVo.PageOverlayItem> listPageOverlays(Long documentId, Long parseTaskId) {
        if (parseTaskId == null) {
            return List.of();
        }

        List<SuperAgentDocumentBlock> blocks = blockMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentBlock>()
            .eq(SuperAgentDocumentBlock::getDocumentId, documentId)
            .eq(SuperAgentDocumentBlock::getTaskId, parseTaskId)
            .eq(SuperAgentDocumentBlock::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentDocumentBlock::getPageNo, SuperAgentDocumentBlock::getBlockNo, SuperAgentDocumentBlock::getId));
        List<SuperAgentDocumentTable> tables = tableMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentTable>()
            .eq(SuperAgentDocumentTable::getDocumentId, documentId)
            .eq(SuperAgentDocumentTable::getTaskId, parseTaskId)
            .eq(SuperAgentDocumentTable::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentDocumentTable::getPageNo, SuperAgentDocumentTable::getTableNo, SuperAgentDocumentTable::getId));
        List<SuperAgentDocumentParseArtifact> pageImages = parseArtifactMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentParseArtifact>()
            .eq(SuperAgentDocumentParseArtifact::getDocumentId, documentId)
            .eq(SuperAgentDocumentParseArtifact::getTaskId, parseTaskId)
            .eq(SuperAgentDocumentParseArtifact::getArtifactType, "PAGE_IMAGE")
            .eq(SuperAgentDocumentParseArtifact::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentDocumentParseArtifact::getId));

        Set<Integer> rawPageNumbers = new LinkedHashSet<>();
        blocks.stream().map(SuperAgentDocumentBlock::getPageNo).filter(Objects::nonNull).forEach(rawPageNumbers::add);
        tables.stream().map(SuperAgentDocumentTable::getPageNo).filter(Objects::nonNull).forEach(rawPageNumbers::add);
        boolean zeroBasedPageNo = rawPageNumbers.contains(0);

        Map<Integer, PageOverlayAccumulator> pageMap = new LinkedHashMap<>();
        for (SuperAgentDocumentParseArtifact imageArtifact : pageImages) {
            Integer pageNo = pageNoFromPageImageObject(imageArtifact.getObjectName(), zeroBasedPageNo);
            if (pageNo == null) {
                continue;
            }
            PageOverlayAccumulator page = pageAccumulator(pageMap, pageNo);
            page.pageImageArtifactId = imageArtifact.getId();
            page.pageImageObjectName = imageArtifact.getObjectName();
            PageDimension dimension = readPageImageDimension(imageArtifact.getObjectName());
            if (dimension != null) {
                page.pageWidth = dimension.width();
                page.pageHeight = dimension.height();
            }
        }

        for (SuperAgentDocumentBlock block : blocks) {
            if (block.getPageNo() == null || StrUtil.isBlank(block.getBboxJson())) {
                continue;
            }
            String blockType = StrUtil.blankToDefault(block.getBlockType(), "BLOCK").trim().toUpperCase();
            if (!PAGE_OVERLAY_BLOCK_TYPES.contains(blockType)) {
                continue;
            }
            BBox bbox = readBbox(block.getBboxJson());
            if (bbox == null) {
                continue;
            }
            PageOverlayAccumulator page = pageAccumulator(pageMap, block.getPageNo());
            page.observe(bbox);
            page.regions.add(region(
                "block-" + block.getId(),
                "BLOCK",
                blockType,
                block.getId(),
                block.getBlockNo(),
                "Block #" + valueOrDash(block.getBlockNo()),
                block.getPageNo(),
                block.getBboxJson(),
                bbox,
                block.getSectionPath(),
                preview(firstNotBlank(block.getText(), block.getTableHtml(), block.getImageCaption())),
                "document_block"
            ));
        }

        for (SuperAgentDocumentTable table : tables) {
            if (table.getPageNo() == null || StrUtil.isBlank(table.getBboxJson())) {
                continue;
            }
            BBox bbox = readBbox(table.getBboxJson());
            if (bbox == null) {
                continue;
            }
            PageOverlayAccumulator page = pageAccumulator(pageMap, table.getPageNo());
            page.observe(bbox);
            page.regions.add(region(
                "table-" + table.getId(),
                "TABLE",
                "TABLE",
                table.getId(),
                table.getTableNo(),
                "表格 T#" + valueOrDash(table.getTableNo()),
                table.getPageNo(),
                table.getBboxJson(),
                bbox,
                table.getSectionPath(),
                preview(firstNotBlank(table.getTitle(), table.getTableHtml())),
                "document_table"
            ));
        }

        return pageMap.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getValue().toItem(entry.getKey(), zeroBasedPageNo))
            .toList();
    }

    private PageOverlayAccumulator pageAccumulator(Map<Integer, PageOverlayAccumulator> pageMap, Integer pageNo) {
        return pageMap.computeIfAbsent(pageNo, ignored -> new PageOverlayAccumulator());
    }

    private DocumentRagSnapshotVo.PageOverlayRegionItem region(String overlayId,
                                                               String sourceType,
                                                               String type,
                                                               Long sourceId,
                                                               Integer sourceNo,
                                                               String label,
                                                               Integer pageNo,
                                                               String bboxJson,
                                                               BBox bbox,
                                                               String sectionPath,
                                                               String textPreview,
                                                               String source) {
        return new DocumentRagSnapshotVo.PageOverlayRegionItem(
            overlayId,
            sourceType,
            type,
            sourceId,
            sourceNo,
            label,
            pageNo,
            bboxJson,
            bbox.x(),
            bbox.y(),
            bbox.width(),
            bbox.height(),
            null,
            null,
            null,
            null,
            sectionPath,
            textPreview,
            source
        );
    }

    private DocumentRagSnapshotVo.ArtifactGraphItem buildArtifactGraph(Long documentId,
                                                                       Long parseTaskId,
                                                                       Long indexTaskId) {
        Map<String, DocumentRagSnapshotVo.ArtifactGraphNodeItem> nodeMap = new LinkedHashMap<>();
        Map<String, DocumentRagSnapshotVo.ArtifactGraphEdgeItem> edgeMap = new LinkedHashMap<>();
        nodeMap.put("document-" + documentId, artifactNode(
            "document-" + documentId,
            "DOCUMENT",
            documentId,
            null,
            "Document",
            "文档根节点",
            "",
            "",
            null,
            "",
            "",
            "success"
        ));

        List<SuperAgentDocumentBlock> blocks = parseTaskId == null ? List.of()
            : blockMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentBlock>()
                .eq(SuperAgentDocumentBlock::getDocumentId, documentId)
                .eq(SuperAgentDocumentBlock::getTaskId, parseTaskId)
                .eq(SuperAgentDocumentBlock::getStatus, BusinessStatus.YES.getCode())
                .orderByAsc(SuperAgentDocumentBlock::getBlockNo, SuperAgentDocumentBlock::getId)
                .last("limit " + ARTIFACT_GRAPH_PARSE_BLOCK_LIMIT));
        for (SuperAgentDocumentBlock block : blocks) {
            String nodeId = blockNodeId(block.getId());
            nodeMap.put(nodeId, artifactNode(
                nodeId,
                "PARSE_BLOCK",
                block.getId(),
                block.getBlockNo(),
                "Block #" + valueOrDash(block.getBlockNo()),
                StrUtil.blankToDefault(block.getBlockType(), "block"),
                block.getSectionPath(),
                block.getPageRange(),
                block.getPageNo(),
                block.getBboxJson() == null ? "" : "block-" + block.getId(),
                preview(firstNotBlank(block.getText(), block.getTableHtml(), block.getImageCaption())),
                "neutral"
            ));
            addArtifactEdge(edgeMap, "document-" + documentId, nodeId, "document-block", "解析块", "");
        }

        List<SuperAgentDocumentParentBlock> parents = indexTaskId == null ? List.of()
            : parentBlockMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentParentBlock>()
                .eq(SuperAgentDocumentParentBlock::getDocumentId, documentId)
                .eq(SuperAgentDocumentParentBlock::getTaskId, indexTaskId)
                .eq(SuperAgentDocumentParentBlock::getStatus, BusinessStatus.YES.getCode())
                .orderByAsc(SuperAgentDocumentParentBlock::getParentNo, SuperAgentDocumentParentBlock::getId)
                .last("limit " + ARTIFACT_GRAPH_PARENT_LIMIT));
        for (SuperAgentDocumentParentBlock parent : parents) {
            String nodeId = parentNodeId(parent.getId());
            nodeMap.put(nodeId, artifactNode(
                nodeId,
                "PARENT_BLOCK",
                parent.getId(),
                parent.getParentNo(),
                "Parent P#" + valueOrDash(parent.getParentNo()),
                "回答上下文",
                parent.getSectionPath(),
                parent.getPageRange(),
                null,
                "",
                preview(parent.getParentText()),
                "success"
            ));
            for (Long blockId : readLongList(parent.getSourceBlockIds())) {
                addArtifactEdge(edgeMap, blockNodeId(blockId), nodeId, "block-parent", "组成父块", parent.getSectionPath());
            }
        }

        List<SuperAgentDocumentChunk> chunks = indexTaskId == null ? List.of()
            : chunkMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentChunk>()
                .eq(SuperAgentDocumentChunk::getDocumentId, documentId)
                .eq(SuperAgentDocumentChunk::getTaskId, indexTaskId)
                .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode())
                .orderByAsc(SuperAgentDocumentChunk::getChunkNo, SuperAgentDocumentChunk::getId)
                .last("limit " + ARTIFACT_GRAPH_CHUNK_LIMIT));
        for (SuperAgentDocumentChunk chunk : chunks) {
            String nodeId = chunkNodeId(chunk.getId());
            nodeMap.put(nodeId, artifactNode(
                nodeId,
                "CHILD_CHUNK",
                chunk.getId(),
                chunk.getChunkNo(),
                "Chunk C#" + valueOrDash(chunk.getChunkNo()),
                StrUtil.blankToDefault(chunk.getChunkType(), "召回单元"),
                chunk.getSectionPath(),
                chunk.getPageRange(),
                chunk.getPageNo(),
                "",
                preview(firstNotBlank(chunk.getChunkText(), chunk.getContentWithWeight())),
                Objects.equals(chunk.getVectorStatus(), DocumentVectorStatusEnum.VECTOR_SUCCESS.getCode()) ? "success" : "warning"
            ));
            if (chunk.getParentBlockId() != null) {
                addArtifactEdge(edgeMap, parentNodeId(chunk.getParentBlockId()), nodeId, "parent-chunk", "切成子块", "");
            }
            for (Long blockId : readLongList(chunk.getSourceBlockIds())) {
                addArtifactEdge(edgeMap, blockNodeId(blockId), nodeId, "block-chunk", "来源 block", "");
            }
        }

        List<SuperAgentDocumentTable> tables = parseTaskId == null ? List.of()
            : tableMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentTable>()
                .eq(SuperAgentDocumentTable::getDocumentId, documentId)
                .eq(SuperAgentDocumentTable::getTaskId, parseTaskId)
                .eq(SuperAgentDocumentTable::getStatus, BusinessStatus.YES.getCode())
                .orderByAsc(SuperAgentDocumentTable::getTableNo, SuperAgentDocumentTable::getId)
                .last("limit " + ARTIFACT_GRAPH_TABLE_LIMIT));
        for (SuperAgentDocumentTable table : tables) {
            String nodeId = tableNodeId(table.getId());
            nodeMap.put(nodeId, artifactNode(
                nodeId,
                "TABLE",
                table.getId(),
                table.getTableNo(),
                "Table T#" + valueOrDash(table.getTableNo()),
                table.getTitle(),
                table.getSectionPath(),
                table.getPageRange(),
                table.getPageNo(),
                table.getBboxJson() == null ? "" : "table-" + table.getId(),
                preview(firstNotBlank(table.getTitle(), table.getTableHtml())),
                "success"
            ));
            if (table.getBlockId() != null) {
                addArtifactEdge(edgeMap, blockNodeId(table.getBlockId()), nodeId, "block-table", "抽取表格", "");
            }
        }

        List<SuperAgentKgEvidence> evidences = listKgEvidenceRecords(documentId, indexTaskId, ARTIFACT_GRAPH_KG_EVIDENCE_LIMIT);
        for (SuperAgentKgEvidence evidence : evidences) {
            String nodeId = kgEvidenceNodeId(evidence.getId());
            nodeMap.put(nodeId, artifactNode(
                nodeId,
                "KG_EVIDENCE",
                evidence.getId(),
                null,
                "KG Evidence #" + valueOrDash(evidence.getId()),
                evidence.getEntityId() != null ? "entity evidence" : "relation evidence",
                evidence.getSectionPath(),
                evidence.getPageRange(),
                evidence.getPageNo(),
                "",
                preview(evidence.getQuoteText()),
                evidence.getChunkId() != null ? "success" : "warning"
            ));
            if (evidence.getChunkId() != null) {
                addArtifactEdge(edgeMap, chunkNodeId(evidence.getChunkId()), nodeId, "chunk-kg", "支撑 KG", "");
            }
            if (evidence.getParentBlockId() != null) {
                addArtifactEdge(edgeMap, parentNodeId(evidence.getParentBlockId()), nodeId, "parent-kg", "KG 来源父块", "");
            }
        }

        List<SuperAgentRaptorNode> raptorNodes = indexTaskId == null ? List.of()
            : raptorNodeMapper.selectList(new LambdaQueryWrapper<SuperAgentRaptorNode>()
                .eq(SuperAgentRaptorNode::getDocumentId, documentId)
                .eq(SuperAgentRaptorNode::getTaskId, indexTaskId)
                .eq(SuperAgentRaptorNode::getStatus, BusinessStatus.YES.getCode())
                .orderByDesc(SuperAgentRaptorNode::getNodeLevel)
                .orderByAsc(SuperAgentRaptorNode::getNodeNo, SuperAgentRaptorNode::getId)
                .last("limit " + ARTIFACT_GRAPH_RAPTOR_LIMIT));
        for (SuperAgentRaptorNode node : raptorNodes) {
            String nodeId = raptorNodeId(node.getId());
            nodeMap.put(nodeId, artifactNode(
                nodeId,
                "RAPTOR_NODE",
                node.getId(),
                node.getNodeNo(),
                "RAPTOR N#" + valueOrDash(node.getNodeNo()),
                "L" + valueOrDash(node.getNodeLevel()),
                node.getSectionPath(),
                node.getPageRange(),
                null,
                "",
                preview(node.getSummary()),
                "neutral"
            ));
            if (node.getParentNodeId() != null) {
                addArtifactEdge(edgeMap, raptorNodeId(node.getParentNodeId()), nodeId, "raptor-child", "摘要下钻", "");
            }
            for (Long chunkId : readLongList(node.getSourceChunkIdsJson())) {
                addArtifactEdge(edgeMap, chunkNodeId(chunkId), nodeId, "chunk-raptor", "摘要来源", "");
            }
            for (Long parentId : readLongList(node.getSourceParentBlockIdsJson())) {
                addArtifactEdge(edgeMap, parentNodeId(parentId), nodeId, "parent-raptor", "摘要来源", "");
            }
        }

        return new DocumentRagSnapshotVo.ArtifactGraphItem(
            List.of(
                new DocumentRagSnapshotVo.MetricItem("节点", String.valueOf(nodeMap.size()), "文档侧 RAG 产物节点", tone(nodeMap.size())),
                new DocumentRagSnapshotVo.MetricItem("关系", String.valueOf(edgeMap.size()), "block、parent、chunk、KG、RAPTOR 的来源关系", tone(edgeMap.size())),
                new DocumentRagSnapshotVo.MetricItem("KG evidence", String.valueOf(evidences.size()), "GraphRAG 证据回到 chunk/parent 的样例数", tone(evidences.size())),
                new DocumentRagSnapshotVo.MetricItem("RAPTOR", String.valueOf(raptorNodes.size()), "摘要节点和 source chunk/source parent 的样例数", tone(raptorNodes.size()))
            ),
            new ArrayList<>(nodeMap.values()),
            new ArrayList<>(edgeMap.values())
        );
    }

    private DocumentRagSnapshotVo.ArtifactGraphNodeItem artifactNode(String nodeId,
                                                                     String nodeType,
                                                                     Long sourceId,
                                                                     Integer sourceNo,
                                                                     String label,
                                                                     String subtitle,
                                                                     String sectionPath,
                                                                     String pageRange,
                                                                     Integer pageNo,
                                                                     String overlayId,
                                                                     String textPreview,
                                                                     String tone) {
        return new DocumentRagSnapshotVo.ArtifactGraphNodeItem(
            nodeId,
            nodeType,
            sourceId,
            sourceNo,
            StrUtil.blankToDefault(label, nodeId),
            StrUtil.blankToDefault(subtitle, ""),
            StrUtil.blankToDefault(sectionPath, ""),
            StrUtil.blankToDefault(pageRange, ""),
            pageNo,
            StrUtil.blankToDefault(overlayId, ""),
            StrUtil.blankToDefault(textPreview, ""),
            StrUtil.blankToDefault(tone, "neutral")
        );
    }

    private void addArtifactEdge(Map<String, DocumentRagSnapshotVo.ArtifactGraphEdgeItem> edgeMap,
                                 String sourceNodeId,
                                 String targetNodeId,
                                 String edgeType,
                                 String label,
                                 String detail) {
        if (StrUtil.isBlank(sourceNodeId) || StrUtil.isBlank(targetNodeId) || Objects.equals(sourceNodeId, targetNodeId)) {
            return;
        }
        String edgeId = edgeType + ":" + sourceNodeId + "->" + targetNodeId;
        edgeMap.putIfAbsent(edgeId, new DocumentRagSnapshotVo.ArtifactGraphEdgeItem(
            edgeId,
            sourceNodeId,
            targetNodeId,
            edgeType,
            label,
            StrUtil.blankToDefault(detail, "")
        ));
    }

    private DocumentRagSnapshotVo.KgGraphItem buildKgGraph(Long documentId, Long indexTaskId) {
        if (indexTaskId == null) {
            return new DocumentRagSnapshotVo.KgGraphItem(List.of(), List.of(), List.of(), List.of(), List.of());
        }
        List<SuperAgentKgEntity> entities = kgEntityMapper.selectList(new LambdaQueryWrapper<SuperAgentKgEntity>()
            .eq(SuperAgentKgEntity::getDocumentId, documentId)
            .eq(SuperAgentKgEntity::getTaskId, indexTaskId)
            .eq(SuperAgentKgEntity::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentKgEntity::getEntityType, SuperAgentKgEntity::getName, SuperAgentKgEntity::getId)
            .last("limit " + KG_GRAPH_ENTITY_LIMIT));
        List<SuperAgentKgRelation> relations = kgRelationMapper.selectList(new LambdaQueryWrapper<SuperAgentKgRelation>()
            .eq(SuperAgentKgRelation::getDocumentId, documentId)
            .eq(SuperAgentKgRelation::getTaskId, indexTaskId)
            .eq(SuperAgentKgRelation::getStatus, BusinessStatus.YES.getCode())
            .orderByDesc(SuperAgentKgRelation::getWeight)
            .orderByAsc(SuperAgentKgRelation::getId)
            .last("limit " + KG_GRAPH_RELATION_LIMIT));
        List<SuperAgentKgCommunity> communities = kgCommunityMapper.selectList(new LambdaQueryWrapper<SuperAgentKgCommunity>()
            .eq(SuperAgentKgCommunity::getDocumentId, documentId)
            .eq(SuperAgentKgCommunity::getTaskId, indexTaskId)
            .eq(SuperAgentKgCommunity::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentKgCommunity::getCommunityNo, SuperAgentKgCommunity::getId)
            .last("limit " + KG_GRAPH_COMMUNITY_LIMIT));
        List<SuperAgentKgEvidence> evidences = listKgEvidenceRecords(documentId, indexTaskId, KG_GRAPH_EVIDENCE_LIMIT);
        Map<Long, Integer> evidenceCountByEntityId = evidences.stream()
            .filter(evidence -> evidence.getEntityId() != null)
            .collect(Collectors.groupingBy(SuperAgentKgEvidence::getEntityId, LinkedHashMap::new, Collectors.collectingAndThen(Collectors.counting(), Long::intValue)));
        Map<Long, Integer> evidenceCountByRelationId = evidences.stream()
            .filter(evidence -> evidence.getRelationId() != null)
            .collect(Collectors.groupingBy(SuperAgentKgEvidence::getRelationId, LinkedHashMap::new, Collectors.collectingAndThen(Collectors.counting(), Long::intValue)));
        Map<Long, Integer> chunkCountByEntityId = evidences.stream()
            .filter(evidence -> evidence.getEntityId() != null && evidence.getChunkId() != null)
            .collect(Collectors.groupingBy(SuperAgentKgEvidence::getEntityId, LinkedHashMap::new,
                Collectors.collectingAndThen(Collectors.mapping(SuperAgentKgEvidence::getChunkId, Collectors.toSet()), Set::size)));
        Map<Long, Long> communityIdByEntityId = communities.stream()
            .flatMap(community -> readLongList(community.getEntityIdsJson()).stream()
                .map(entityId -> Map.entry(entityId, community.getId())))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left, LinkedHashMap::new));
        Map<Long, SuperAgentKgEntity> entityMap = entities.stream()
            .collect(Collectors.toMap(SuperAgentKgEntity::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));

        List<DocumentRagSnapshotVo.KgGraphNodeItem> nodes = entities.stream()
            .map(entity -> {
                Map<String, Object> metadata = readMap(entity.getMetadataJson());
                Integer degree = integerValue(metadata.get("degree"));
                return new DocumentRagSnapshotVo.KgGraphNodeItem(
                    kgEntityNodeId(entity.getId()),
                    entity.getId(),
                    StrUtil.blankToDefault(entity.getName(), entity.getEntityKey()),
                    entity.getEntityType(),
                    preview(entity.getDescription()),
                    communityIdByEntityId.get(entity.getId()),
                    doubleValue(metadata.get("pagerank")),
                    integerValue(metadata.get("rankPosition")),
                    degree,
                    integerValue(metadata.get("inDegree")),
                    integerValue(metadata.get("outDegree")),
                    doubleValue(metadata.get("weightedDegree")),
                    doubleValue(metadata.get("confidence")),
                    evidenceCountByEntityId.getOrDefault(entity.getId(), 0),
                    chunkCountByEntityId.getOrDefault(entity.getId(), 0),
                    kgNodeQualityLevel(degree, evidenceCountByEntityId.getOrDefault(entity.getId(), 0))
                );
            })
            .toList();

        List<DocumentRagSnapshotVo.KgGraphEdgeItem> edges = relations.stream()
            .filter(relation -> entityMap.containsKey(relation.getSourceEntityId()) && entityMap.containsKey(relation.getTargetEntityId()))
            .map(relation -> new DocumentRagSnapshotVo.KgGraphEdgeItem(
                kgRelationEdgeId(relation.getId()),
                relation.getId(),
                kgEntityNodeId(relation.getSourceEntityId()),
                kgEntityNodeId(relation.getTargetEntityId()),
                relation.getSourceEntityId(),
                relation.getTargetEntityId(),
                relation.getRelationType(),
                preview(relation.getDescription()),
                decimalText(relation.getWeight()),
                evidenceCountByRelationId.getOrDefault(relation.getId(), 0),
                kgRelationQualityLevel(relation, evidenceCountByRelationId.getOrDefault(relation.getId(), 0))
            ))
            .toList();

        List<DocumentRagSnapshotVo.KgGraphCommunityItem> communityItems = communities.stream()
            .map(community -> {
                Map<String, Object> metadata = readMap(community.getMetadataJson());
                List<Long> communityEntityIds = readLongList(community.getEntityIdsJson());
                List<Long> communityRelationIds = readLongList(community.getRelationIdsJson());
                List<Long> communityEvidenceIds = readLongList(community.getEvidenceIdsJson());
                return new DocumentRagSnapshotVo.KgGraphCommunityItem(
                    community.getId(),
                    community.getCommunityNo(),
                    StrUtil.blankToDefault(community.getTitle(), "社区 #" + valueOrDash(community.getCommunityNo())),
                    preview(community.getSummary()),
                    communityEntityIds.size(),
                    communityRelationIds.size(),
                    communityEvidenceIds.size(),
                    decimalText(bigDecimalValue(firstPresent(metadata.get("rankScore"), metadata.get("globalRankScore"))))
                );
            })
            .toList();

        List<DocumentRagSnapshotVo.KgGraphEvidenceItem> evidenceItems = evidences.stream()
            .map(evidence -> {
                Map<String, Object> metadata = readMap(evidence.getMetadataJson());
                Map<String, Object> sourceMetadata = objectMap(metadata.get("sourceMetadata"));
                return new DocumentRagSnapshotVo.KgGraphEvidenceItem(
                    evidence.getId(),
                    evidence.getEntityId(),
                    evidence.getRelationId(),
                    evidence.getChunkId(),
                    evidence.getParentBlockId(),
                    evidence.getPageNo(),
                    evidence.getPageRange(),
                    evidence.getBboxJson(),
                    evidence.getSectionPath(),
                    preview(evidence.getQuoteText()),
                    stringValue(firstPresent(metadata.get("sourceType"), sourceMetadata.get("sourceType"))),
                    booleanValue(sourceMetadata.get("grounded"))
                );
            })
            .toList();

        return new DocumentRagSnapshotVo.KgGraphItem(
            List.of(
                new DocumentRagSnapshotVo.MetricItem("实体", String.valueOf(nodes.size()), "当前快照载入的 GraphRAG 实体", tone(nodes.size())),
                new DocumentRagSnapshotVo.MetricItem("关系", String.valueOf(edges.size()), "实体之间的关系边；没有关系时不伪造", tone(edges.size())),
                new DocumentRagSnapshotVo.MetricItem("社区", String.valueOf(communityItems.size()), "community report 和实体集合", tone(communityItems.size())),
                new DocumentRagSnapshotVo.MetricItem("证据", String.valueOf(evidenceItems.size()), "可回到 chunk/parent/page 的 KG evidence", tone(evidenceItems.size()))
            ),
            nodes,
            edges,
            communityItems,
            evidenceItems
        );
    }

    private List<SuperAgentKgEvidence> listKgEvidenceRecords(Long documentId, Long indexTaskId, int limit) {
        if (indexTaskId == null) {
            return List.of();
        }
        return kgEvidenceMapper.selectList(new LambdaQueryWrapper<SuperAgentKgEvidence>()
            .eq(SuperAgentKgEvidence::getDocumentId, documentId)
            .eq(SuperAgentKgEvidence::getTaskId, indexTaskId)
            .eq(SuperAgentKgEvidence::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentKgEvidence::getId)
            .last("limit " + limit));
    }

    private String kgNodeQualityLevel(Integer degree, Integer evidenceCount) {
        int safeEvidenceCount = evidenceCount == null ? 0 : evidenceCount;
        int safeDegree = degree == null ? 0 : degree;
        if (safeEvidenceCount <= 0) {
            return "WEAK";
        }
        if (safeDegree <= 0) {
            return "ISOLATED";
        }
        if (safeEvidenceCount >= 2 && safeDegree >= 2) {
            return "STRONG";
        }
        return "WATCH";
    }

    private String kgRelationQualityLevel(SuperAgentKgRelation relation, Integer evidenceCount) {
        int safeEvidenceCount = evidenceCount == null ? 0 : evidenceCount;
        if (safeEvidenceCount <= 0) {
            return "WEAK";
        }
        BigDecimal weight = relation == null ? null : relation.getWeight();
        if (weight != null && weight.compareTo(BigDecimal.valueOf(0.75D)) >= 0 && safeEvidenceCount >= 2) {
            return "STRONG";
        }
        return "WATCH";
    }

    private Integer pageNoFromPageImageObject(String objectName, boolean zeroBasedPageNo) {
        if (StrUtil.isBlank(objectName)) {
            return null;
        }
        Matcher matcher = PAGE_IMAGE_OBJECT_NAME_PATTERN.matcher(objectName);
        if (!matcher.find()) {
            return null;
        }
        try {
            int displayPageNo = Integer.parseInt(matcher.group(1));
            return zeroBasedPageNo ? Math.max(0, displayPageNo - 1) : displayPageNo;
        }
        catch (NumberFormatException exception) {
            return null;
        }
    }

    private PageDimension readPageImageDimension(String objectName) {
        if (StrUtil.isBlank(objectName)) {
            return null;
        }
        try {
            byte[] bytes = storageService.downloadObject(objectName);
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                return null;
            }
            return new PageDimension((double) image.getWidth(), (double) image.getHeight());
        }
        catch (Exception exception) {
            return null;
        }
    }

    private BBox readBbox(String bboxJson) {
        Map<String, Object> values = readMap(bboxJson);
        if (values.isEmpty()) {
            return null;
        }
        Double x0 = firstNumber(values, "x0", "left", "x", "minX");
        Double y0 = firstNumber(values, "y0", "top", "y", "minY");
        Double x1 = firstNumber(values, "x1", "right", "maxX");
        Double y1 = firstNumber(values, "y1", "bottom", "maxY");
        Double width = firstNumber(values, "width", "w");
        Double height = firstNumber(values, "height", "h");
        if (x0 == null || y0 == null) {
            return null;
        }
        if (x1 == null && width != null) {
            x1 = x0 + width;
        }
        if (y1 == null && height != null) {
            y1 = y0 + height;
        }
        if (x1 == null || y1 == null) {
            return null;
        }
        double left = Math.min(x0, x1);
        double top = Math.min(y0, y1);
        double boxWidth = Math.abs(x1 - x0);
        double boxHeight = Math.abs(y1 - y0);
        if (boxWidth <= 0D || boxHeight <= 0D) {
            return null;
        }
        return new BBox(left, top, boxWidth, boxHeight);
    }

    private Double firstNumber(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Double value = doubleValue(values.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
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
            node.getScopeType(),
            node.getScopeKey(),
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

    private DocumentRagSnapshotVo.RaptorTreeItem buildRaptorTree(List<DocumentRagSnapshotVo.RaptorNodeItem> raptorNodes) {
        if (raptorNodes == null || raptorNodes.isEmpty()) {
            return new DocumentRagSnapshotVo.RaptorTreeItem(List.of(), List.of(), List.of(), List.of());
        }
        Map<Long, DocumentRagSnapshotVo.RaptorNodeItem> nodeById = raptorNodes.stream()
            .filter(node -> node.getNodeId() != null)
            .collect(Collectors.toMap(DocumentRagSnapshotVo.RaptorNodeItem::getNodeId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        List<DocumentRagSnapshotVo.RaptorTreeNodeItem> treeNodes = raptorNodes.stream()
            .map(node -> new DocumentRagSnapshotVo.RaptorTreeNodeItem(
                raptorNodeId(node.getNodeId()),
                node.getNodeId(),
                node.getParentNodeId(),
                node.getParentNodeId() == null ? "" : raptorNodeId(node.getParentNodeId()),
                node.getNodeLevel(),
                node.getNodeNo(),
                StrUtil.blankToDefault(node.getTitle(), "RAPTOR N#" + valueOrDash(node.getNodeNo())),
                node.getSummary(),
                node.getQualityScore(),
                node.getQualityLevel(),
                node.getQualityRisk(),
                node.getScopeType(),
                node.getScopeKey(),
                node.getSummaryStrategy(),
                node.getLlmSummaryStatus(),
                node.getTreeDepth(),
                node.getTreePath(),
                node.getSourceChunkCount(),
                node.getSourceParentBlockCount(),
                node.getChildNodeCount(),
                node.getSourceChunks(),
                node.getSourceParentBlocks()
            ))
            .toList();
        List<DocumentRagSnapshotVo.RaptorTreeEdgeItem> edges = raptorNodes.stream()
            .filter(node -> node.getNodeId() != null
                && node.getParentNodeId() != null
                && nodeById.containsKey(node.getParentNodeId()))
            .map(node -> new DocumentRagSnapshotVo.RaptorTreeEdgeItem(
                "raptor:" + node.getParentNodeId() + "->" + node.getNodeId(),
                raptorNodeId(node.getParentNodeId()),
                raptorNodeId(node.getNodeId()),
                "parent-child",
                "摘要下钻"
            ))
            .toList();
        Set<Long> nodeIds = nodeById.keySet();
        List<DocumentRagSnapshotVo.RaptorTreeNodeItem> roots = treeNodes.stream()
            .filter(node -> node.getParentNodeId() == null || !nodeIds.contains(node.getParentNodeId()))
            .sorted(Comparator.comparing(DocumentRagSnapshotVo.RaptorTreeNodeItem::getNodeLevel,
                    Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(DocumentRagSnapshotVo.RaptorTreeNodeItem::getNodeNo,
                    Comparator.nullsLast(Integer::compareTo))
                .thenComparing(DocumentRagSnapshotVo.RaptorTreeNodeItem::getRaptorNodeId,
                    Comparator.nullsLast(Long::compareTo)))
            .toList();
        long documentScopeCount = raptorNodes.stream()
            .filter(node -> "DOCUMENT".equalsIgnoreCase(StrUtil.blankToDefault(node.getScopeType(), "")))
            .count();
        long datasetScopeCount = raptorNodes.stream()
            .filter(node -> "DATASET".equalsIgnoreCase(StrUtil.blankToDefault(node.getScopeType(), "")))
            .count();
        long lowQualityCount = raptorNodes.stream()
            .filter(node -> Set.of("BLOCKED", "LOW", "WATCH").contains(StrUtil.blankToDefault(node.getQualityLevel(), "")))
            .count();
        return new DocumentRagSnapshotVo.RaptorTreeItem(
            List.of(
                new DocumentRagSnapshotVo.MetricItem("节点", String.valueOf(treeNodes.size()), "当前快照载入的 RAPTOR 摘要节点", tone(treeNodes.size())),
                new DocumentRagSnapshotVo.MetricItem("树边", String.valueOf(edges.size()), "真实 parentNodeId 形成的摘要下钻关系", tone(edges.size())),
                new DocumentRagSnapshotVo.MetricItem("文档/知识域", documentScopeCount + "/" + datasetScopeCount, "DOCUMENT scope 和 DATASET scope 节点数量", treeNodes.isEmpty() ? "neutral" : "success"),
                new DocumentRagSnapshotVo.MetricItem("需关注质量", String.valueOf(lowQualityCount), "BLOCKED/LOW/WATCH 节点不会被隐藏", lowQualityCount > 0L ? "warning" : "success")
            ),
            roots,
            treeNodes,
            edges
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

    private DocumentRagSnapshotVo.ParserTraceItem buildParserTrace(Long parseTaskId) {
        Map<String, Object> trace = parserTraceMap(parseTaskId);
        if (trace.isEmpty()) {
            return null;
        }
        return new DocumentRagSnapshotVo.ParserTraceItem(
            stringValue(trace.get("providerName")),
            stringValue(trace.get("providerVersion")),
            stringValue(trace.get("jobId")),
            integerValue(trace.get("pageCount")),
            integerValue(trace.get("ocrPageCount")),
            integerValue(trace.get("blockCount")),
            integerValue(trace.get("rawLayoutCount")),
            integerValue(trace.get("tableCount")),
            integerValue(trace.get("figureCount")),
            integerValue(trace.get("captionCount")),
            integerValue(trace.get("bboxBlockCount")),
            doubleValue(trace.get("bboxBlockCoverage")),
            integerValue(trace.get("tableCellCount")),
            integerValue(trace.get("tableCellBboxCount")),
            doubleValue(trace.get("tableCellBboxCoverage")),
            integerValue(trace.get("warningCount")),
            integerValue(trace.get("pollCount")),
            integerValue(trace.get("submitElapsedMs")),
            integerValue(trace.get("pollElapsedMs")),
            integerValue(trace.get("resultFetchElapsedMs")),
            integerValue(trace.get("standardizeElapsedMs")),
            integerValue(trace.get("elapsedMs")),
            objectMap(trace.get("blockTypeCounts")),
            stringList(trace.get("warnings"))
        );
    }

    private Map<String, Object> parserTraceMap(Long parseTaskId) {
        if (parseTaskId == null) {
            return Map.of();
        }
        SuperAgentDocumentTask task = taskMapper.selectById(parseTaskId);
        Map<String, Object> extJson = task == null ? Map.of() : readMap(task.getExtJson());
        Map<String, Object> trace = objectMap(extJson.get("parserTraceMetadata"));
        if (!trace.isEmpty()) {
            return trace;
        }
        return parserTraceMapFromLogs(parseTaskId);
    }

    private Map<String, Object> parserTraceMapFromLogs(Long parseTaskId) {
        SuperAgentDocumentTaskLog parseCompleteLog = taskLogMapper.selectOne(new LambdaQueryWrapper<SuperAgentDocumentTaskLog>()
            .eq(SuperAgentDocumentTaskLog::getTaskId, parseTaskId)
            .eq(SuperAgentDocumentTaskLog::getStageType, DocumentTaskStageEnum.CONTENT_PARSE.getCode())
            .eq(SuperAgentDocumentTaskLog::getEventType, DocumentTaskEventTypeEnum.COMPLETE.getCode())
            .eq(SuperAgentDocumentTaskLog::getStatus, BusinessStatus.YES.getCode())
            .orderByDesc(SuperAgentDocumentTaskLog::getCreateTime, SuperAgentDocumentTaskLog::getId)
            .last("limit 1"));
        if (parseCompleteLog == null) {
            return Map.of();
        }
        Map<String, Object> detail = readMap(parseCompleteLog.getDetailJson());
        return objectMap(detail.get("parserTraceMetadata"));
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

    private String valueOrDash(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return StrUtil.blankToDefault(text, "-");
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

    private String blockNodeId(Long blockId) {
        return blockId == null ? "" : "block-" + blockId;
    }

    private String parentNodeId(Long parentBlockId) {
        return parentBlockId == null ? "" : "parent-" + parentBlockId;
    }

    private String chunkNodeId(Long chunkId) {
        return chunkId == null ? "" : "chunk-" + chunkId;
    }

    private String tableNodeId(Long tableId) {
        return tableId == null ? "" : "table-" + tableId;
    }

    private String kgEvidenceNodeId(Long evidenceId) {
        return evidenceId == null ? "" : "kg-evidence-" + evidenceId;
    }

    private String raptorNodeId(Long nodeId) {
        return nodeId == null ? "" : "raptor-" + nodeId;
    }

    private String kgEntityNodeId(Long entityId) {
        return entityId == null ? "" : "kg-entity-" + entityId;
    }

    private String kgRelationEdgeId(Long relationId) {
        return relationId == null ? "" : "kg-relation-" + relationId;
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

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .map(this::stringValue)
            .filter(StrUtil::isNotBlank)
            .toList();
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

    private BigDecimal bigDecimalValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String text && StrUtil.isNotBlank(text)) {
            try {
                return new BigDecimal(text.trim());
            }
            catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
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

    private record BBox(double x, double y, double width, double height) {

        private double right() {
            return x + width;
        }

        private double bottom() {
            return y + height;
        }
    }

    private record PageDimension(double width, double height) {
    }

    private static class PageOverlayAccumulator {

        private Long pageImageArtifactId;

        private String pageImageObjectName;

        private Double pageWidth;

        private Double pageHeight;

        private double maxRight;

        private double maxBottom;

        private final List<DocumentRagSnapshotVo.PageOverlayRegionItem> regions = new ArrayList<>();

        private void observe(BBox bbox) {
            if (bbox == null) {
                return;
            }
            maxRight = Math.max(maxRight, bbox.right());
            maxBottom = Math.max(maxBottom, bbox.bottom());
        }

        private DocumentRagSnapshotVo.PageOverlayItem toItem(Integer pageNo, boolean zeroBasedPageNo) {
            double effectiveWidth = positiveOrDefault(pageWidth, Math.max(1D, maxRight));
            double effectiveHeight = positiveOrDefault(pageHeight, Math.max(1D, maxBottom));
            List<DocumentRagSnapshotVo.PageOverlayRegionItem> normalizedRegions = regions.stream()
                .sorted(Comparator.comparing(DocumentRagSnapshotVo.PageOverlayRegionItem::getY,
                        Comparator.nullsLast(Double::compareTo))
                    .thenComparing(DocumentRagSnapshotVo.PageOverlayRegionItem::getX,
                        Comparator.nullsLast(Double::compareTo))
                    .thenComparing(DocumentRagSnapshotVo.PageOverlayRegionItem::getOverlayId,
                        Comparator.nullsLast(String::compareTo)))
                .map(region -> normalizeRegion(region, effectiveWidth, effectiveHeight))
                .toList();
            return new DocumentRagSnapshotVo.PageOverlayItem(
                pageNo,
                displayPageNo(pageNo, zeroBasedPageNo),
                pageImageArtifactId,
                pageImageObjectName,
                effectiveWidth,
                effectiveHeight,
                normalizedRegions
            );
        }

        private static DocumentRagSnapshotVo.PageOverlayRegionItem normalizeRegion(DocumentRagSnapshotVo.PageOverlayRegionItem region,
                                                                                   double pageWidth,
                                                                                   double pageHeight) {
            double x = numberOrZero(region.getX());
            double y = numberOrZero(region.getY());
            double width = numberOrZero(region.getWidth());
            double height = numberOrZero(region.getHeight());
            region.setLeftRatio(clampRatio(x / pageWidth));
            region.setTopRatio(clampRatio(y / pageHeight));
            region.setWidthRatio(clampRatio(width / pageWidth));
            region.setHeightRatio(clampRatio(height / pageHeight));
            return region;
        }

        private static double positiveOrDefault(Double value, double fallback) {
            return value != null && value > 0D ? value : fallback;
        }

        private static double numberOrZero(Double value) {
            return value == null ? 0D : value;
        }

        private static double clampRatio(double value) {
            if (!Double.isFinite(value)) {
                return 0D;
            }
            return Math.max(0D, Math.min(1D, value));
        }

        private static String displayPageNo(Integer pageNo, boolean zeroBasedPageNo) {
            if (pageNo == null) {
                return "";
            }
            return String.valueOf(zeroBasedPageNo ? pageNo + 1 : pageNo);
        }
    }
}
