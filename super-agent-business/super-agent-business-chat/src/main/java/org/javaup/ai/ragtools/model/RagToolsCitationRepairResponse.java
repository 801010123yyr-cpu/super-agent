package org.javaup.ai.ragtools.model;

import lombok.Data;

import java.util.List;

@Data
public class RagToolsCitationRepairResponse {

    private List<Result> citations;

    @Data
    public static class Result {

        private String evidenceId;

        private String answerSegment;

        private Integer segmentIndex;

        private String quoteText;

        private Double score;

        private Integer rank;

        private Long documentId;

        private String documentName;

        private Long chunkId;

        private Long parentBlockId;

        private Integer pageNo;

        private String pageRange;

        private String bboxJson;

        private String sectionPath;
    }
}
