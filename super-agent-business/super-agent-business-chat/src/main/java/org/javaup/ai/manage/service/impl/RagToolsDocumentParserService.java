package org.javaup.ai.manage.service.impl;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import org.javaup.ai.manage.service.DocumentParserService;
import org.javaup.ai.manage.support.DocumentAnalysisResult;
import org.javaup.ai.manage.support.DocumentBlockCandidate;
import org.javaup.ai.manage.support.DocumentParseArtifactCandidate;
import org.javaup.ai.manage.support.DocumentStructureNodeCandidate;
import org.javaup.ai.ragtools.client.RagToolsClient;
import org.javaup.ai.ragtools.model.RagToolsDocumentParseRequest;
import org.javaup.ai.ragtools.model.RagToolsDocumentParseResponse;
import org.javaup.enums.DocumentFileTypeEnum;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务实现层
 * @author: 阿星不是程序员
 **/

@AllArgsConstructor
@Service
public class RagToolsDocumentParserService implements DocumentParserService {

    private final RagToolsClient ragToolsClient;

    private final ObjectMapper objectMapper;

    @Override
    public DocumentAnalysisResult parse(byte[] bytes,
                                        String originalFileName,
                                        String mimeType,
                                        DocumentFileTypeEnum fileType) {
        RagToolsDocumentParseResponse response = ragToolsClient.parseDocument(new RagToolsDocumentParseRequest(
            originalFileName,
            mimeType,
            fileType == null ? "" : fileType.name(),
            Base64.getEncoder().encodeToString(bytes == null ? new byte[0] : bytes)
        ));
        if (response == null || StrUtil.isBlank(response.getParsedText())) {
            throw new IllegalStateException("rag-tools document parse 返回结果为空");
        }

        DocumentAnalysisResult result = new DocumentAnalysisResult();
        result.setParsedText(response.getParsedText());
        result.setCharCount(response.getCharCount() == null ? response.getParsedText().length() : response.getCharCount());
        result.setTokenCount(response.getTokenCount() == null ? 0 : response.getTokenCount());
        result.setStructureLevel(response.getStructureLevel() == null ? 0 : response.getStructureLevel());
        result.setContentQualityLevel(response.getContentQualityLevel() == null ? 0 : response.getContentQualityLevel());
        result.setHeadingCount(response.getHeadingCount() == null ? 0 : response.getHeadingCount());
        result.setParagraphCount(response.getParagraphCount() == null ? 0 : response.getParagraphCount());
        result.setMaxParagraphLength(response.getMaxParagraphLength() == null ? 0 : response.getMaxParagraphLength());
        result.setStructureNodes(toStructureNodes(response.getStructureNodes()));
        result.setParseArtifacts(toArtifacts(response.getArtifacts()));
        result.setBlocks(toBlocks(response.getBlocks()));
        return result;
    }

    private List<DocumentStructureNodeCandidate> toStructureNodes(List<RagToolsDocumentParseResponse.StructureNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        return nodes.stream()
            .map(node -> new DocumentStructureNodeCandidate(
                node.getNodeNo(),
                node.getNodeType(),
                node.getParentNodeNo(),
                node.getPrevSiblingNodeNo(),
                node.getNextSiblingNodeNo(),
                node.getDepth(),
                node.getNodeCode(),
                node.getTitle(),
                node.getAnchorText(),
                node.getCanonicalPath(),
                node.getSectionPath(),
                node.getContentText(),
                node.getItemIndex()
            ))
            .toList();
    }

    private List<DocumentParseArtifactCandidate> toArtifacts(List<RagToolsDocumentParseResponse.Artifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return List.of();
        }
        return artifacts.stream()
            .map(artifact -> new DocumentParseArtifactCandidate(
                artifact.getArtifactType(),
                artifact.getFileName(),
                artifact.getContentType(),
                artifact.getContentBase64(),
                artifact.getContentHash(),
                artifact.getParserName(),
                artifact.getParserVersion()
            ))
            .toList();
    }

    private List<DocumentBlockCandidate> toBlocks(List<RagToolsDocumentParseResponse.Block> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        return blocks.stream()
            .map(block -> new DocumentBlockCandidate(
                block.getBlockNo(),
                block.getBlockType(),
                block.getParentBlockNo(),
                block.getSectionPath(),
                block.getCanonicalPath(),
                block.getPageNo(),
                block.getPageRange(),
                block.getBboxJson(),
                block.getText(),
                block.getContentWithWeight(),
                block.getTableHtml(),
                writeTableRows(block.getTableRows()),
                block.getImageFileName(),
                block.getImageContentBase64(),
                block.getImageCaption(),
                block.getMetadataJson()
            ))
            .toList();
    }

    private String writeTableRows(List<List<String>> tableRows) {
        if (tableRows == null || tableRows.isEmpty()) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(tableRows);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化 rag-tools tableRows 失败", exception);
        }
    }
}
