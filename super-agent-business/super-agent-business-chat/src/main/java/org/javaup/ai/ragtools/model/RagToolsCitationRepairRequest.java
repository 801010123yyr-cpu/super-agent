package org.javaup.ai.ragtools.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagToolsCitationRepairRequest {

    private String answer;

    private List<Evidence> evidences;

    private Integer maxSegments;

    private Integer maxMatchesPerSegment;

    private Double minScore;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Evidence {

        private String id;

        private String text;

        private Long documentId;

        private String documentName;

        private Long chunkId;

        private Long parentBlockId;

        private Integer pageNo;

        private String pageRange;

        private String bboxJson;

        private String sectionPath;

        private Map<String, Object> metadata = new LinkedHashMap<>();
    }
}
