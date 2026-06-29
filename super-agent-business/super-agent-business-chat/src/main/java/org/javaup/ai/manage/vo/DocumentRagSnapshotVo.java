package org.javaup.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.javaup.ai.manage.model.raptor.RaptorQualityReport;

import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 文档 RAG 学习快照
 * @author: 阿星不是程序员
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRagSnapshotVo {

    private Long documentId;

    private String documentName;

    private Long parseTaskId;

    private Long indexTaskId;

    private Long planId;

    private List<MetricItem> metrics;

    private List<PipelineStageItem> pipelineStages;

    private List<ParseBlockItem> parseBlocks;

    private List<StructureNodeItem> structureNodes;

    private List<ParentBlockItem> parentBlocks;

    private List<ChunkItem> chunks;

    private List<TableItem> tables;

    private List<KgEntityItem> kgEntities;

    private List<KgRelationItem> kgRelations;

    private List<KgCommunityItem> kgCommunities;

    private List<RaptorNodeItem> raptorNodes;

    private RaptorQualityReport raptorQuality;

    private List<DocumentTaskLogVo> buildLogs;

    private String runtimeObservationNote;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetricItem {

        private String label;

        private String value;

        private String hint;

        private String tone;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PipelineStageItem {

        private String code;

        private String title;

        private String statusText;

        private String description;

        private Long count;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParseBlockItem {

        private Long blockId;

        private Integer blockNo;

        private String blockType;

        private String sectionPath;

        private Integer pageNo;

        private String pageRange;

        private String bboxJson;

        private String textPreview;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StructureNodeItem {

        private Long nodeId;

        private Integer nodeNo;

        private Integer nodeType;

        private Integer depth;

        private String nodeCode;

        private String title;

        private String sectionPath;

        private String anchorText;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParentBlockItem {

        private Long parentBlockId;

        private Integer parentNo;

        private String sectionPath;

        private Integer childCount;

        private Integer startChunkNo;

        private Integer endChunkNo;

        private String pageRange;

        private String textPreview;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkItem {

        private Long chunkId;

        private Long parentBlockId;

        private Integer chunkNo;

        private String sectionPath;

        private String chunkType;

        private String title;

        private String keywords;

        private String questions;

        private Integer vectorStatus;

        private String vectorStatusName;

        private Integer tokenCount;

        private String pageRange;

        private String sourceBlockIds;

        private String textPreview;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TableItem {

        private Long tableId;

        private Integer tableNo;

        private String title;

        private String sectionPath;

        private Integer pageNo;

        private String pageRange;

        private Integer rowCount;

        private Integer columnCount;

        private String bboxJson;

        private List<TableColumnItem> columns;

        private List<TableRowItem> rows;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TableColumnItem {

        private Long columnId;

        private Integer columnNo;

        private String columnName;

        private String normalizedName;

        private String valueType;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TableRowItem {

        private Long rowId;

        private Integer rowNo;

        private String rowText;

        private List<String> cells;

        private List<TableCellItem> cellItems;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TableCellItem {

        private Long cellId;

        private Long columnId;

        private Integer rowNo;

        private Integer columnNo;

        private String cellText;

        private Integer sourceRowNo;

        private Integer sourceColumnNo;

        private String sourceCellRef;

        private String bboxJson;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KgEntityItem {

        private Long entityId;

        private String name;

        private String entityType;

        private String description;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KgRelationItem {

        private Long relationId;

        private Long sourceEntityId;

        private String sourceName;

        private Long targetEntityId;

        private String targetName;

        private String relationType;

        private String description;

        private String weight;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KgCommunityItem {

        private Long communityId;

        private Integer communityNo;

        private String title;

        private String summary;

        private String entityIdsJson;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RaptorNodeItem {

        private Long nodeId;

        private String nodeKey;

        private Long parentNodeId;

        private Integer nodeLevel;

        private Integer nodeNo;

        private String title;

        private String summary;

        private String summaryWithWeight;

        private String sourceChunkIdsJson;

        private String sourceParentBlockIdsJson;

        private List<Long> sourceChunkIds;

        private List<Long> sourceParentBlockIds;

        private Integer sourceChunkCount;

        private Integer sourceParentBlockCount;

        private List<Long> childNodeIds;

        private Integer childNodeCount;

        private String sectionPath;

        private String pageRange;

        private String keywords;

        private String questions;

        private Double qualityScore;

        private String qualityLevel;

        private String qualityRisk;

        private String summaryStrategy;

        private String clusterMethod;

        private Boolean abstractive;

        private String llmSummaryStatus;

        private Integer treeDepth;

        private String treePath;

        private List<RaptorSourceChunkItem> sourceChunks;

        private List<RaptorSourceParentBlockItem> sourceParentBlocks;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RaptorSourceChunkItem {

        private Long chunkId;

        private Long parentBlockId;

        private Integer chunkNo;

        private String sectionPath;

        private String title;

        private String pageRange;

        private String textPreview;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RaptorSourceParentBlockItem {

        private Long parentBlockId;

        private Integer parentNo;

        private String sectionPath;

        private Integer childCount;

        private String pageRange;

        private String textPreview;
    }
}
