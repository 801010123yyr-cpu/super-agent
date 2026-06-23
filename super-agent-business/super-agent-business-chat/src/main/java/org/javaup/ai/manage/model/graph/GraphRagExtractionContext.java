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
public class GraphRagExtractionContext {

    private Long documentId;

    private Long taskId;

    @Builder.Default
    private List<ChunkItem> chunks = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkItem {

        private Long chunkId;

        private Long parentBlockId;

        private Integer chunkNo;

        private String chunkType;

        private String title;

        private String sectionPath;

        private Integer pageNo;

        private String pageRange;

        private String text;
    }
}
