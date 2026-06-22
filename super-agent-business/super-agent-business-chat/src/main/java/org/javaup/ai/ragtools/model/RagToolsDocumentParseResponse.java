package org.javaup.ai.ragtools.model;

import lombok.Data;

import java.util.List;

@Data
public class RagToolsDocumentParseResponse {

    private String parsedText;

    private Integer charCount;

    private Integer tokenCount;

    private Integer structureLevel;

    private Integer contentQualityLevel;

    private Integer headingCount;

    private Integer paragraphCount;

    private Integer maxParagraphLength;

    private List<Artifact> artifacts;

    private List<Block> blocks;

    private List<StructureNode> structureNodes;

    @Data
    public static class Artifact {

        private String artifactType;

        private String fileName;

        private String contentType;

        private String contentBase64;

        private String contentHash;

        private String parserName;

        private String parserVersion;
    }

    @Data
    public static class Block {

        private Integer blockNo;

        private String blockType;

        private Integer parentBlockNo;

        private String sectionPath;

        private String canonicalPath;

        private Integer pageNo;

        private String pageRange;

        private String bboxJson;

        private String text;

        private String contentWithWeight;

        private String tableHtml;

        private String imageFileName;

        private String imageContentBase64;

        private String imageCaption;

        private String metadataJson;
    }

    @Data
    public static class StructureNode {

        private Integer nodeNo;

        private Integer nodeType;

        private Integer parentNodeNo;

        private Integer prevSiblingNodeNo;

        private Integer nextSiblingNodeNo;

        private Integer depth;

        private String nodeCode;

        private String title;

        private String anchorText;

        private String canonicalPath;

        private String sectionPath;

        private String contentText;

        private Integer itemIndex;
    }
}
