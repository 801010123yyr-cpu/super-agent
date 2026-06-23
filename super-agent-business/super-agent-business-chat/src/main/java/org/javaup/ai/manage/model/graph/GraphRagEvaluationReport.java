package org.javaup.ai.manage.model.graph;

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
public class GraphRagEvaluationReport {

    private String suiteId;

    private String name;

    private String scenario;

    private String question;

    private String sourceDocument;

    private Long documentId;

    private Long taskId;

    private Double passThreshold;

    private Boolean passed;

    private String evaluationLevel;

    private Double evaluationScore;

    private String summary;

    private GraphRagQualityReport qualityReport;

    private Long expectedEntityCount;

    private Long matchedEntityCount;

    private Long expectedRelationCount;

    private Long matchedRelationCount;

    private Long expectedEvidenceCount;

    private Long matchedEvidenceCount;

    private Double entityRecall;

    private Double relationRecall;

    private Double evidenceRecall;

    private Double overallRecall;

    @Builder.Default
    private List<EntityResult> entityResults = new ArrayList<>();

    @Builder.Default
    private List<RelationResult> relationResults = new ArrayList<>();

    @Builder.Default
    private List<EvidenceResult> evidenceResults = new ArrayList<>();

    public static GraphRagEvaluationReport empty(Long documentId, Long taskId, GraphRagQualityReport qualityReport) {
        return empty(null, null, documentId, taskId, qualityReport);
    }

    public static GraphRagEvaluationReport empty(String suiteId,
                                                 String name,
                                                 Long documentId,
                                                 Long taskId,
                                                 GraphRagQualityReport qualityReport) {
        return GraphRagEvaluationReport.builder()
            .suiteId(suiteId)
            .name(name)
            .documentId(documentId)
            .taskId(taskId)
            .passThreshold(0.85D)
            .passed(false)
            .evaluationLevel(GraphRagQualityReport.LEVEL_EMPTY)
            .evaluationScore(0D)
            .summary("未配置 GraphRAG 评测期望项。")
            .qualityReport(qualityReport)
            .expectedEntityCount(0L)
            .matchedEntityCount(0L)
            .expectedRelationCount(0L)
            .matchedRelationCount(0L)
            .expectedEvidenceCount(0L)
            .matchedEvidenceCount(0L)
            .entityRecall(0D)
            .relationRecall(0D)
            .evidenceRecall(0D)
            .overallRecall(0D)
            .entityResults(List.of())
            .relationResults(List.of())
            .evidenceResults(List.of())
            .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntityResult {

        private String expectedName;

        private String expectedEntityType;

        private Boolean required;

        private Boolean matched;

        private Long actualEntityId;

        private String actualName;

        @Builder.Default
        private List<String> missingAliases = new ArrayList<>();

        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RelationResult {

        private String expectedSourceName;

        private String expectedTargetName;

        private String expectedRelationType;

        private Boolean required;

        private Boolean matched;

        private Long actualRelationId;

        private Long actualSourceEntityId;

        private String actualSourceName;

        private Long actualTargetEntityId;

        private String actualTargetName;

        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvidenceResult {

        private String expectedQuoteText;

        @Builder.Default
        private List<String> expectedQuoteKeywords = new ArrayList<>();

        private String expectedEntityName;

        private String expectedSourceName;

        private String expectedTargetName;

        private String expectedRelationType;

        private Boolean required;

        private Boolean matched;

        private Long actualEvidenceId;

        private Long actualEntityId;

        private Long actualRelationId;

        private Long actualChunkId;

        private String actualQuoteText;

        private Integer actualPageNo;

        private String actualPageRange;

        private String actualSectionPath;

        private String reason;
    }
}
