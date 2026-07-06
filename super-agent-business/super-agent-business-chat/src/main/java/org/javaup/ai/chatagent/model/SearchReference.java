package org.javaup.ai.chatagent.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.javaup.ai.chatagent.rag.model.EvidenceIdentity;
import org.javaup.ai.chatagent.rag.support.EvidenceIdentityResolver;

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

    private Long knowledgeBaseId;

    private String knowledgeBaseName;

    private Long chunkId;

    private String chunkType;

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

    private String kgEvidenceGroundingLevel;

    private String kgGraphPath;

    private Integer kgHopCount;

    private String kgQueryPlanSource;

    private String kgQueryPlanAnswerTypes;

    private String kgQueryPlanEntities;

    private Long kgNhopSeedEntityId;

    private String kgNhopSeedEntityName;

    private String kgNhopPath;

    private String kgCrossDocumentCommunityKey;

    private boolean kgCommunitySummaryOnly;

    private Integer kgCrossDocumentCommunityEntityCount;

    private Integer kgCrossDocumentCommunityRelationGroupCount;

    private Integer kgCrossDocumentCommunityEvidenceCount;

    private Integer kgCrossDocumentCommunityDocumentCount;

    private Double kgCommunityRankScore;

    private String kgCommunityRankReasons;

    private Double kgQualityScore;

    private String kgQualityReasons;

    private String kgNoiseReasons;

    private Double kgPagerank;

    private Integer kgRankPosition;

    private Integer kgDegree;

    private Long raptorNodeId;

    private String raptorNodeTitle;

    private Integer raptorNodeLevel;

    private String raptorSummary;

    private String raptorSourceStatus;

    private String answerSegment;

    private String quoteText;

    private Double citationScore;

    private Integer citationSegmentIndex;

    private Integer citationRank;

    private boolean citationRepaired;

    private String finalSelectionReason;

    private String evidenceApplicabilityStatus;

    private String evidenceApplicabilityReason;

    private String contextIdentity;

    private String citationIdentity;

    private String citationEvidenceType;

    private boolean contextOnly;

    private boolean sourceEvidenceResolved;

    public SearchReference(String title, String url, String snippet) {
        this.sourceType = "WEB";
        this.title = title;
        this.url = url;
        this.snippet = snippet;
        this.channel = "web-search";
        this.toolName = "tavily_search";
    }

    public String uniqueKey() {
        EvidenceIdentity citation = EvidenceIdentityResolver.citationIdentity(this);
        if (citation != null && citation.present()) {
            return citation.value();
        }
        EvidenceIdentity context = EvidenceIdentityResolver.contextIdentity(this);
        if (context != null && context.present()) {
            return context.value();
        }
        if (url != null && !url.isBlank()) {
            return "WEB:" + url;
        }
        return (sourceType == null ? "UNKNOWN" : sourceType)
            + ":" + (title == null ? "" : title)
            + ":" + (snippet == null ? "" : snippet);
    }
}
