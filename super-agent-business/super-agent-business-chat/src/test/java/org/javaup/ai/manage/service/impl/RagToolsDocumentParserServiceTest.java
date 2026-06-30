package org.javaup.ai.manage.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.ragtools.client.RagToolsClient;
import org.javaup.ai.ragtools.config.RagToolsProperties;
import org.javaup.ai.ragtools.model.RagToolsDocumentParseRequest;
import org.javaup.ai.ragtools.model.RagToolsDocumentParseResponse;
import org.javaup.enums.DocumentFileTypeEnum;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagToolsDocumentParserServiceTest {

    @Test
    void parseSendsDocumentToRagToolsWithoutParserOverride() throws Exception {
        CapturingRagToolsClient ragToolsClient = new CapturingRagToolsClient();
        ObjectMapper objectMapper = new ObjectMapper();
        RagToolsDocumentParserService service = new RagToolsDocumentParserService(
            ragToolsClient,
            objectMapper
        );

        var result = service.parse(
            "hello".getBytes(StandardCharsets.UTF_8),
            "sample.txt",
            "text/plain",
            DocumentFileTypeEnum.TXT
        );

        assertThat(ragToolsClient.lastRequest.getFileName()).isEqualTo("sample.txt");
        assertThat(ragToolsClient.lastRequest.getMimeType()).isEqualTo("text/plain");
        assertThat(ragToolsClient.lastRequest.getFileType()).isEqualTo("TXT");
        assertThat(ragToolsClient.lastRequest.getContentBase64()).isNotBlank();
        assertThat(objectMapper.writeValueAsString(ragToolsClient.lastRequest)).doesNotContain("parserProvider");
        assertThat(result.getParserProviderName()).isEqualTo("native_text");
        assertThat(result.getParserCapabilities()).contains("native-text", "markdown");
    }

    @Test
    void parsePropagatesProviderMetadata() {
        CapturingRagToolsClient ragToolsClient = new CapturingRagToolsClient();
        RagToolsDocumentParserService service = new RagToolsDocumentParserService(
            ragToolsClient,
            new ObjectMapper()
        );

        var result = service.parse(
            "hello".getBytes(StandardCharsets.UTF_8),
            "sample.txt",
            "text/plain",
            DocumentFileTypeEnum.TXT
        );

        assertThat(result.getParserProviderName()).isEqualTo("native_text");
        assertThat(result.getParserProviderVersion()).isEqualTo("0.1.0");
        assertThat(result.getParserElapsedMs()).isEqualTo(1);
        assertThat(result.getParserWarnings()).isEmpty();
    }

    @Test
    void imageFileTypesAreAcceptedAndSentToRagTools() throws Exception {
        assertThat(DocumentFileTypeEnum.fromFileName("scan.png")).isEqualTo(DocumentFileTypeEnum.PNG);
        assertThat(DocumentFileTypeEnum.fromFileName("scan.jpg")).isEqualTo(DocumentFileTypeEnum.JPG);
        assertThat(DocumentFileTypeEnum.fromFileName("scan.jpeg")).isEqualTo(DocumentFileTypeEnum.JPEG);

        CapturingRagToolsClient ragToolsClient = new CapturingRagToolsClient();
        RagToolsDocumentParserService service = new RagToolsDocumentParserService(
            ragToolsClient,
            new ObjectMapper()
        );

        service.parse(
            "png".getBytes(StandardCharsets.UTF_8),
            "scan.png",
            "image/png",
            DocumentFileTypeEnum.PNG
        );

        assertThat(ragToolsClient.lastRequest.getFileName()).isEqualTo("scan.png");
        assertThat(ragToolsClient.lastRequest.getMimeType()).isEqualTo("image/png");
        assertThat(ragToolsClient.lastRequest.getFileType()).isEqualTo("PNG");
    }

    private static class CapturingRagToolsClient extends RagToolsClient {

        private RagToolsDocumentParseRequest lastRequest;

        private CapturingRagToolsClient() {
            super(new RagToolsProperties(), new ObjectMapper());
        }

        @Override
        public RagToolsDocumentParseResponse parseDocument(RagToolsDocumentParseRequest request) {
            this.lastRequest = request;
            RagToolsDocumentParseResponse response = new RagToolsDocumentParseResponse();
            response.setParsedText("hello");
            response.setCharCount(5);
            response.setTokenCount(1);
            response.setStructureLevel(0);
            response.setContentQualityLevel(1);
            response.setHeadingCount(0);
            response.setParagraphCount(1);
            response.setMaxParagraphLength(5);
            response.setArtifacts(List.of());
            response.setBlocks(List.of());
            response.setStructureNodes(List.of());
            response.setProviderName("native_text");
            response.setProviderVersion("0.1.0");
            response.setCapabilities(List.of("native-text", "text", "markdown"));
            response.setElapsedMs(1);
            response.setWarnings(List.of());
            response.setFailedReason("");
            return response;
        }
    }
}
