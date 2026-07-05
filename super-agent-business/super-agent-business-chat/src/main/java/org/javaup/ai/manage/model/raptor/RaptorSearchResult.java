package org.javaup.ai.manage.model.raptor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RaptorSearchResult {

    private Long documentId;

    private Long taskId;

    private Long raptorNodeId;

    private String raptorNodeTitle;

    private Integer raptorNodeLevel;

    private String raptorSummary;

    private String sourceStatus;

    private Long chunkId;

    private Long parentBlockId;

    private Integer chunkNo;

    private String chunkText;

    private String title;

    private String sectionPath;

    private Integer pageNo;

    private String pageRange;

    private String bboxJson;

    private String sourceBlockIds;

    private Double score;
}
