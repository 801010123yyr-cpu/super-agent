package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询理解结果。只承载受控建议，不直接决定最终答案。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryUnderstandingResult {

    @Builder.Default
    private QueryType queryType = QueryType.DOCUMENT_QA;

    @Builder.Default
    private List<RetrievalIntent> channels = new ArrayList<>();

    @Builder.Default
    private List<String> entities = new ArrayList<>();

    @Builder.Default
    private List<String> targetEntities = new ArrayList<>();

    @Builder.Default
    private List<String> excludedEntities = new ArrayList<>();

    @Builder.Default
    private List<String> sectionAnchors = new ArrayList<>();

    @Builder.Default
    private List<String> tableOps = new ArrayList<>();

    private boolean negativeBoundary;

    private String answerExpectation;

    private double confidence;

    @Builder.Default
    private List<String> reasons = new ArrayList<>();

    private String source;
}
