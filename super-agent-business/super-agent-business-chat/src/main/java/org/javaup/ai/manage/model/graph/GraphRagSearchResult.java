package org.javaup.ai.manage.model.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphRagSearchResult {

    private Long documentId;

    private Long taskId;

    private Long entityId;

    private String entityName;

    private Long relationId;

    private String relationType;

    private Long relatedEntityId;

    private String relatedEntityName;

    private Long evidenceId;

    private Long communityId;

    private String communityTitle;

    private String communitySummary;

    private Long chunkId;

    private Long parentBlockId;

    private String quoteText;

    private Integer pageNo;

    private String pageRange;

    private String bboxJson;

    private String sectionPath;

    private String graphPath;

    private Integer hopCount;

    private Double rankBoost;

    private double score;
}
