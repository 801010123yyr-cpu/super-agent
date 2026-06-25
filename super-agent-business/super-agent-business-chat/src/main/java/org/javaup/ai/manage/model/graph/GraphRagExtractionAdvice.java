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
public class GraphRagExtractionAdvice {

    private Boolean graphable;

    @Builder.Default
    private List<EntityItem> entities = new ArrayList<>();

    @Builder.Default
    private List<RelationItem> relations = new ArrayList<>();

    @Builder.Default
    private List<EvidenceItem> evidences = new ArrayList<>();

    private Double confidence;

    private String reason;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntityItem {

        private String id;

        private String name;

        private String normalizedName;

        private String entityType;

        @Builder.Default
        private List<String> aliases = new ArrayList<>();

        private String description;

        private Double confidence;

        @Builder.Default
        private List<Long> sourceChunkIds = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RelationItem {

        private String id;

        private String sourceEntityId;

        private String targetEntityId;

        private String relationType;

        private String supportMode;

        private String predicateQuoteText;

        private String relationTypeReason;

        private String description;

        private Double weight;

        private Double confidence;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvidenceItem {

        private String id;

        private String entityId;

        private String relationId;

        private Long chunkId;

        private String quoteText;

        private Double confidence;
    }
}
