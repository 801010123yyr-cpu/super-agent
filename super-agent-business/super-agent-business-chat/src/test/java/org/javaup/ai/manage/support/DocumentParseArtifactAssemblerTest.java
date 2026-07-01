package org.javaup.ai.manage.support;

import org.javaup.ai.manage.data.SuperAgentDocumentParseArtifact;
import org.javaup.ai.manage.vo.DocumentParseArtifactContentVo;
import org.javaup.ai.manage.vo.DocumentParseArtifactDownloadVo;
import org.javaup.ai.manage.vo.DocumentParseArtifactItemVo;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentParseArtifactAssemblerTest {

    @Test
    void buildsSummaryWithStorageSizeAndJsonViewerMetadata() {
        SuperAgentDocumentParseArtifact artifact = artifact(
            "LAYOUT_JSON",
            "parse-artifacts/10/20/1700000000000-layout.json",
            "sha256-layout"
        );
        artifact.setParserName("rag-tools");
        artifact.setParserVersion("o9");
        artifact.setCreateTime(new Date(1_700_000_000_000L));

        DocumentParseArtifactItemVo item = DocumentParseArtifactAssembler.toItem(
            artifact,
            new StoredObjectMetadata("parse-artifacts/10/20/1700000000000-layout.json", 4096L, "application/json")
        );

        assertThat(item.getArtifactId()).isEqualTo(100L);
        assertThat(item.getDocumentId()).isEqualTo(10L);
        assertThat(item.getTaskId()).isEqualTo(20L);
        assertThat(item.getArtifactType()).isEqualTo("LAYOUT_JSON");
        assertThat(item.getArtifactTypeName()).isEqualTo("Layout JSON");
        assertThat(item.getParserName()).isEqualTo("rag-tools");
        assertThat(item.getParserVersion()).isEqualTo("o9");
        assertThat(item.getObjectName()).isEqualTo("parse-artifacts/10/20/1700000000000-layout.json");
        assertThat(item.getFileName()).isEqualTo("layout.json");
        assertThat(item.getContentHash()).isEqualTo("sha256-layout");
        assertThat(item.getSize()).isEqualTo(4096L);
        assertThat(item.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(item.getViewable()).isTrue();
        assertThat(item.getCreateTime()).isEqualTo(new Date(1_700_000_000_000L));
    }

    @Test
    void buildsUtf8ContentAndDownloadPayloadWithoutObjectUrl() {
        SuperAgentDocumentParseArtifact artifact = artifact(
            "ALIYUN_DOCMIND_JSON",
            "parse-artifacts/10/20/1700000000000-aliyun-docmind.json",
            "sha256-docmind"
        );
        byte[] bytes = "{\"jobId\":\"job-1\",\"layouts\":[{\"pos\":[1,2,3,4]}]}".getBytes(StandardCharsets.UTF_8);

        DocumentParseArtifactContentVo content = DocumentParseArtifactAssembler.toContent(artifact, bytes);
        DocumentParseArtifactDownloadVo download = DocumentParseArtifactAssembler.toDownload(artifact, bytes);

        assertThat(content.getArtifact().getArtifactTypeName()).isEqualTo("Document Mind 原始 JSON");
        assertThat(content.getArtifact().getSize()).isEqualTo((long) bytes.length);
        assertThat(content.getContent()).contains("job-1").contains("layouts").contains("pos");
        assertThat(content.getContentLength()).isEqualTo((long) bytes.length);
        assertThat(content.getJson()).isTrue();
        assertThat(content.getMarkdown()).isFalse();

        assertThat(download.getFileName()).isEqualTo("aliyun-docmind.json");
        assertThat(download.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(download.getSize()).isEqualTo((long) bytes.length);
        assertThat(download.getBytes()).containsExactly(bytes);
    }

    @Test
    void treatsMarkdownAsViewableText() {
        SuperAgentDocumentParseArtifact artifact = artifact(
            "MARKDOWN",
            "parse-artifacts/10/20/1700000000000-document.md",
            "sha256-md"
        );
        byte[] bytes = "# 标题\n\n正文".getBytes(StandardCharsets.UTF_8);

        DocumentParseArtifactContentVo content = DocumentParseArtifactAssembler.toContent(artifact, bytes);

        assertThat(content.getArtifact().getFileName()).isEqualTo("document.md");
        assertThat(content.getArtifact().getContentType()).isEqualTo("text/markdown;charset=UTF-8");
        assertThat(content.getContent()).isEqualTo("# 标题\n\n正文");
        assertThat(content.getJson()).isFalse();
        assertThat(content.getMarkdown()).isTrue();
    }

    @Test
    void treatsImageArtifactsAsViewablePngDownloads() {
        SuperAgentDocumentParseArtifact artifact = artifact(
            "PAGE_IMAGE",
            "parse-artifacts/10/20/1700000000000-document.page-1.png",
            "sha256-page"
        );
        byte[] bytes = new byte[]{1, 2, 3, 4};

        DocumentParseArtifactItemVo item = DocumentParseArtifactAssembler.toItem(
            artifact,
            new StoredObjectMetadata(artifact.getObjectName(), (long) bytes.length, null)
        );
        DocumentParseArtifactContentVo content = DocumentParseArtifactAssembler.toContent(artifact, bytes);
        DocumentParseArtifactDownloadVo download = DocumentParseArtifactAssembler.toDownload(artifact, bytes);

        assertThat(item.getArtifactTypeName()).isEqualTo("页面图片");
        assertThat(item.getFileName()).isEqualTo("document.page-1.png");
        assertThat(item.getContentType()).isEqualTo("image/png");
        assertThat(item.getViewable()).isTrue();
        assertThat(content.getContent()).isEmpty();
        assertThat(content.getImage()).isTrue();
        assertThat(content.getJson()).isFalse();
        assertThat(content.getMarkdown()).isFalse();
        assertThat(content.getImageBase64()).isEqualTo("AQIDBA==");
        assertThat(content.getDataUrl()).isEqualTo("data:image/png;base64,AQIDBA==");
        assertThat(download.getFileName()).isEqualTo("document.page-1.png");
        assertThat(download.getContentType()).isEqualTo("image/png");
        assertThat(download.getBytes()).containsExactly(bytes);
    }

    private static SuperAgentDocumentParseArtifact artifact(String artifactType, String objectName, String contentHash) {
        SuperAgentDocumentParseArtifact artifact = new SuperAgentDocumentParseArtifact();
        artifact.setId(100L);
        artifact.setDocumentId(10L);
        artifact.setTaskId(20L);
        artifact.setArtifactType(artifactType);
        artifact.setObjectName(objectName);
        artifact.setContentHash(contentHash);
        return artifact;
    }
}
