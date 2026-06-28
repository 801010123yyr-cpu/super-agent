package org.javaup.ai.manage.model.es;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RaptorSummaryIndexRecord {

    private Long nodeId;

    private Long documentId;

    private Long taskId;

    private Long parentNodeId;

    private Integer nodeLevel;

    private Integer nodeNo;

    private String title;

    private String summary;

    private String summaryWithWeight;

    private String sectionPath;

    private String pageRange;

    @Builder.Default
    private List<String> keywords = new ArrayList<>();

    @Builder.Default
    private List<String> questions = new ArrayList<>();

    @Builder.Default
    private List<Long> sourceChunkIds = new ArrayList<>();

    @Builder.Default
    private List<Long> sourceParentBlockIds = new ArrayList<>();

    private Double qualityScore;

    private String summaryStrategy;
}
