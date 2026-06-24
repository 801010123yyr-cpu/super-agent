package org.javaup.ai.chatagent.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 统一引用来源模型
 * @author: 阿星不是程序员
 **/

@Data
@NoArgsConstructor
public class SearchReference {

    private String referenceId;

    private String sourceType;

    private String title;

    private String url;

    private String snippet;

    private Long documentId;

    private String documentName;

    private Long chunkId;

    private Long parentBlockId;

    private Integer parentBlockNo;

    private Integer chunkNo;

    private String sectionPath;

    private Long structureNodeId;

    private Integer structureNodeType;

    private String canonicalPath;

    private Integer itemIndex;

    private Double score;

    private Integer subQuestionIndex;

    private String subQuestion;

    private String channel;

    private String toolName;

    private String knowledgeScopeCode;

    private String knowledgeScopeName;

    private Integer pageNo;

    private String pageRange;

    private String bboxJson;

    private String sourceBlockIds;

    private Long tableId;

    private Integer tableNo;

    private String tableTitle;

    private String tableOperation;

    private String tableMetricColumn;

    private String tableGroupByColumn;

    private Integer tableMatchedRowCount;

    private List<Long> tableEvidenceRowIds = List.of();

    private List<Integer> tableEvidenceRowNos = List.of();

    private List<Long> tableEvidenceColumnIds = List.of();

    private List<Integer> tableEvidenceColumnNos = List.of();

    private List<String> tableEvidenceColumnNames = List.of();

    private List<Long> tableEvidenceCellIds = List.of();

    private List<String> tableEvidenceCellCoordinates = List.of();

    private List<String> tableEvidenceCellBboxJsons = List.of();

    private Long kgEntityId;

    private String kgEntityName;

    private String kgCanonicalEntityKey;

    private String kgCanonicalEntityName;

    private Integer kgCanonicalEntityCount;

    private Integer kgCanonicalDocumentCount;

    private Long kgRelatedEntityId;

    private String kgRelatedEntityName;

    private Long kgRelationId;

    private String kgRelationType;

    private String kgRelationGroupKey;

    private Integer kgRelationGroupRelationCount;

    private Integer kgRelationGroupEvidenceCount;

    private Integer kgRelationGroupDocumentCount;

    private Long kgEvidenceId;

    private String kgGraphPath;

    private Integer kgHopCount;

    private Long raptorNodeId;

    private String raptorNodeTitle;

    private Integer raptorNodeLevel;

    private String raptorSummary;

    private String answerSegment;

    private String quoteText;

    private Double citationScore;

    private Integer citationSegmentIndex;

    private Integer citationRank;

    private boolean citationRepaired;

    public SearchReference(String title, String url, String snippet) {
        this.sourceType = "WEB";
        this.title = title;
        this.url = url;
        this.snippet = snippet;
        this.channel = "web-search";
        this.toolName = "tavily_search";
    }

    public String uniqueKey() {
        if (raptorNodeId != null && chunkId != null) {
            return "RAPTOR:" + raptorNodeId + ":" + chunkId;
        }
        if (kgEvidenceId != null) {
            return "GRAPH_RAG:" + kgEvidenceId;
        }
        if (tableId != null) {
            return "TABLE:" + tableId
                + ":" + (tableOperation == null ? "" : tableOperation)
                + ":" + (tableMetricColumn == null ? "" : tableMetricColumn)
                + ":" + (tableGroupByColumn == null ? "" : tableGroupByColumn)
                + ":" + (snippet == null ? 0 : snippet.hashCode());
        }
        if (parentBlockId != null) {
            return "PARENT:" + parentBlockId;
        }
        if (chunkId != null) {
            return "DOCUMENT:" + chunkId;
        }
        if (url != null && !url.isBlank()) {
            return "WEB:" + url;
        }
        return (sourceType == null ? "UNKNOWN" : sourceType)
            + ":" + (title == null ? "" : title)
            + ":" + (snippet == null ? "" : snippet);
    }
}
