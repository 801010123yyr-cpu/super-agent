package org.javaup.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 支撑组件
 * @author: 阿星不是程序员
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentBlockCandidate {

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

    private String tableRowsJson;

    private String imageFileName;

    private String imageContentBase64;

    private String imageCaption;

    private String metadataJson;
}
