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
public class GraphRagEvaluationBatchReport {

    private String batchId;

    private String name;

    private String evaluationLevel;

    private Double evaluationScore;

    private String summary;

    private Long suiteCount;

    private Long passedSuiteCount;

    private Long failedSuiteCount;

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

    private Double passRate;

    private Double minSuiteRecall;

    private Double maxSuiteRecall;

    @Builder.Default
    private List<GraphRagEvaluationReport> reports = new ArrayList<>();

    @Builder.Default
    private List<FailedSuite> failedSuites = new ArrayList<>();

    public static GraphRagEvaluationBatchReport empty(String batchId, String name) {
        return GraphRagEvaluationBatchReport.builder()
            .batchId(batchId)
            .name(name)
            .evaluationLevel(GraphRagQualityReport.LEVEL_EMPTY)
            .evaluationScore(0D)
            .summary("未配置 GraphRAG 批量评测样例。")
            .suiteCount(0L)
            .passedSuiteCount(0L)
            .failedSuiteCount(0L)
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
            .passRate(0D)
            .minSuiteRecall(0D)
            .maxSuiteRecall(0D)
            .reports(List.of())
            .failedSuites(List.of())
            .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailedSuite {

        private String suiteId;

        private String name;

        private String sourceDocument;

        private Long documentId;

        private Long taskId;

        private Double overallRecall;

        private String evaluationLevel;

        private String reason;
    }
}
