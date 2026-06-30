package org.javaup.enums;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 枚举定义
 * @author: 阿星不是程序员
 **/

public enum DocumentParseArtifactTypeEnum {
    MARKDOWN("MARKDOWN", "解析后的 Markdown"),
    JSON("JSON", "结构化解析 JSON"),
    PAGE_IMAGE("PAGE_IMAGE", "页面图片"),
    TABLE_IMAGE("TABLE_IMAGE", "表格图片"),
    FIGURE_IMAGE("FIGURE_IMAGE", "图示图片"),
    OCR_JSON("OCR_JSON", "OCR 结果 JSON"),
    LAYOUT_JSON("LAYOUT_JSON", "版面识别 JSON"),
    ALIYUN_DOCMIND_JSON("ALIYUN_DOCMIND_JSON", "阿里云文档智能原始解析 JSON"),
    TABLE_HTML("TABLE_HTML", "表格 HTML");

    private final String code;

    private final String msg;

    DocumentParseArtifactTypeEnum(String code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getCode() {
        return code;
    }

    public String getMsg() {
        return msg == null ? "" : msg;
    }

    public static DocumentParseArtifactTypeEnum getRc(String code) {
        if (code == null) {
            return null;
        }
        for (DocumentParseArtifactTypeEnum item : values()) {
            if (item.code.equalsIgnoreCase(code)) {
                return item;
            }
        }
        return null;
    }
}
