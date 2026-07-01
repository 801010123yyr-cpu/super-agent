package org.javaup.ai.manage.support;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.manage.data.SuperAgentDocumentParseArtifact;
import org.javaup.ai.manage.vo.DocumentParseArtifactContentVo;
import org.javaup.ai.manage.vo.DocumentParseArtifactDownloadVo;
import org.javaup.ai.manage.vo.DocumentParseArtifactItemVo;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

public final class DocumentParseArtifactAssembler {

    private static final Set<String> VIEWABLE_TYPES = Set.of(
        "ALIYUN_DOCMIND_JSON",
        "LAYOUT_JSON",
        "JSON",
        "MARKDOWN"
    );

    private DocumentParseArtifactAssembler() {
    }

    public static DocumentParseArtifactItemVo toItem(SuperAgentDocumentParseArtifact artifact,
                                                     StoredObjectMetadata metadata) {
        if (artifact == null) {
            return null;
        }
        String artifactType = normalizeArtifactType(artifact.getArtifactType());
        String contentType = resolveContentType(artifactType, metadata == null ? null : metadata.getContentType());
        return new DocumentParseArtifactItemVo(
            artifact.getId(),
            artifact.getDocumentId(),
            artifact.getTaskId(),
            artifactType,
            artifactTypeName(artifactType),
            artifact.getParserName(),
            artifact.getParserVersion(),
            artifact.getObjectName(),
            resolveFileName(artifactType, artifact.getObjectName()),
            artifact.getContentHash(),
            metadata == null ? null : metadata.getSize(),
            contentType,
            isViewable(artifactType, contentType),
            artifact.getCreateTime()
        );
    }

    public static DocumentParseArtifactContentVo toContent(SuperAgentDocumentParseArtifact artifact, byte[] bytes) {
        byte[] safeBytes = bytes == null ? new byte[0] : bytes;
        String content = new String(safeBytes, StandardCharsets.UTF_8);
        String artifactType = normalizeArtifactType(artifact == null ? null : artifact.getArtifactType());
        DocumentParseArtifactItemVo item = toItem(artifact,
            new StoredObjectMetadata(artifact == null ? null : artifact.getObjectName(), (long) safeBytes.length, null));
        return new DocumentParseArtifactContentVo(
            item,
            content,
            (long) safeBytes.length,
            isJson(artifactType),
            isMarkdown(artifactType, item == null ? null : item.getContentType())
        );
    }

    public static DocumentParseArtifactDownloadVo toDownload(SuperAgentDocumentParseArtifact artifact, byte[] bytes) {
        byte[] safeBytes = bytes == null ? new byte[0] : bytes;
        String artifactType = normalizeArtifactType(artifact == null ? null : artifact.getArtifactType());
        DocumentParseArtifactItemVo item = toItem(artifact,
            new StoredObjectMetadata(artifact == null ? null : artifact.getObjectName(), (long) safeBytes.length, null));
        return new DocumentParseArtifactDownloadVo(
            item == null ? defaultFileName(artifactType) : item.getFileName(),
            item == null ? resolveContentType(artifactType, null) : item.getContentType(),
            (long) safeBytes.length,
            safeBytes
        );
    }

    private static String artifactTypeName(String artifactType) {
        return switch (artifactType) {
            case "ALIYUN_DOCMIND_JSON" -> "Document Mind 原始 JSON";
            case "LAYOUT_JSON" -> "Layout JSON";
            case "JSON" -> "标准 JSON";
            case "MARKDOWN" -> "Markdown";
            default -> artifactType;
        };
    }

    private static String resolveFileName(String artifactType, String objectName) {
        String fileName = StrUtil.subAfter(StrUtil.blankToDefault(objectName, ""), "/", true);
        if (StrUtil.isNotBlank(fileName)) {
            fileName = fileName.replaceFirst("^\\d+-", "");
        }
        return StrUtil.isBlank(fileName) ? defaultFileName(artifactType) : fileName;
    }

    private static String defaultFileName(String artifactType) {
        String baseName = StrUtil.blankToDefault(artifactType, "artifact").toLowerCase(Locale.ROOT).replace("_", "-");
        if (isMarkdown(artifactType, null)) {
            return baseName + ".md";
        }
        if (isJson(artifactType)) {
            return baseName + ".json";
        }
        return baseName + ".bin";
    }

    private static String resolveContentType(String artifactType, String sourceContentType) {
        if (isJson(artifactType)) {
            return "application/json;charset=UTF-8";
        }
        if (isMarkdown(artifactType, sourceContentType)) {
            return "text/markdown;charset=UTF-8";
        }
        if (StrUtil.isNotBlank(sourceContentType)) {
            return sourceContentType;
        }
        return "application/octet-stream";
    }

    private static boolean isViewable(String artifactType, String contentType) {
        return VIEWABLE_TYPES.contains(artifactType)
            || isJson(artifactType)
            || isMarkdown(artifactType, contentType)
            || StrUtil.containsIgnoreCase(contentType, "text/");
    }

    private static boolean isJson(String artifactType) {
        return StrUtil.equals(artifactType, "JSON") || StrUtil.endWithIgnoreCase(artifactType, "_JSON");
    }

    private static boolean isMarkdown(String artifactType, String contentType) {
        return StrUtil.equals(artifactType, "MARKDOWN")
            || StrUtil.containsIgnoreCase(contentType, "markdown");
    }

    private static String normalizeArtifactType(String artifactType) {
        return StrUtil.blankToDefault(artifactType, "").trim().toUpperCase(Locale.ROOT);
    }
}
