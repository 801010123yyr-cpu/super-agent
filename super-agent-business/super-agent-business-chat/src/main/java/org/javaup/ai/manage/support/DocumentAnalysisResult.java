package org.javaup.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 支撑组件
 * @author: 阿星不是程序员
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentAnalysisResult {

    private String parsedText;

    private Integer charCount;

    private Integer tokenCount;

    private Integer structureLevel;

    private Integer contentQualityLevel;

    private Integer headingCount;

    private Integer paragraphCount;

    private Integer maxParagraphLength;

    private String parserProviderName;

    private String parserProviderVersion;

    private List<String> parserCapabilities = new ArrayList<>();

    private Integer parserElapsedMs;

    private List<String> parserWarnings = new ArrayList<>();

    private String parserFailedReason;

    private Map<String, Object> parserTraceMetadata = new LinkedHashMap<>();

    private List<DocumentStructureNodeCandidate> structureNodes = new ArrayList<>();

    private List<DocumentParseArtifactCandidate> parseArtifacts = new ArrayList<>();

    private List<DocumentBlockCandidate> blocks = new ArrayList<>();
}
