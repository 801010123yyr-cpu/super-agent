package org.javaup.ai.manage.model.es;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: Elasticsearch 关键词索引文档
 * @author: 阿星不是程序员
 **/

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentKeywordIndexRecord {

    private String chunkId;

    private Long documentId;

    private Long taskId;

    private Long parentBlockId;

    private Integer chunkNo;

    private String documentName;

    private String sectionPath;

    private Long structureNodeId;

    private Integer structureNodeType;

    private String canonicalPath;

    private Integer itemIndex;

    private Integer pageNo;

    private String pageRange;

    private String bboxJson;

    private String sourceBlockIds;

    private String knowledgeScopeCode;

    private String knowledgeScopeName;

    private String businessCategory;

    @Builder.Default
    private List<String> documentTags = new ArrayList<>();

    private String contentWithWeight;

    private String chunkType;

    private String title;

    @Builder.Default
    private List<String> keywords = new ArrayList<>();

    @Builder.Default
    private List<String> questions = new ArrayList<>();

    private String chunkText;
}
