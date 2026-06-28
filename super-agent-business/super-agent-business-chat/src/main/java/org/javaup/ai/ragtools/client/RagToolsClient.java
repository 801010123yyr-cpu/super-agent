package org.javaup.ai.ragtools.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.support.RestClientFactorySupport;
import org.javaup.ai.ragtools.model.RagToolsCitationRepairRequest;
import org.javaup.ai.ragtools.model.RagToolsCitationRepairResponse;
import org.javaup.ai.ragtools.config.RagToolsProperties;
import org.javaup.ai.ragtools.model.RagToolsDocumentParseRequest;
import org.javaup.ai.ragtools.model.RagToolsDocumentParseResponse;
import org.javaup.ai.ragtools.model.RagToolsGraphExtractRequest;
import org.javaup.ai.ragtools.model.RagToolsGraphExtractResponse;
import org.javaup.ai.ragtools.model.RagToolsHealthResponse;
import org.javaup.ai.ragtools.model.RagToolsRaptorBuildRequest;
import org.javaup.ai.ragtools.model.RagToolsRaptorBuildResponse;
import org.javaup.ai.ragtools.model.RagToolsRerankRequest;
import org.javaup.ai.ragtools.model.RagToolsRerankResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class RagToolsClient {

    private final RestClient restClient;

    private final RestClient graphExtractRestClient;

    private final RestClient raptorBuildRestClient;

    private final ObjectMapper objectMapper;

    public RagToolsClient(RagToolsProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient = RestClientFactorySupport.create(
            properties.getBaseUrl(),
            properties.getConnectTimeoutMs(),
            properties.getReadTimeoutMs()
        );
        this.graphExtractRestClient = RestClientFactorySupport.create(
            properties.getBaseUrl(),
            properties.getConnectTimeoutMs(),
            properties.getGraphExtractReadTimeoutMs()
        );
        this.raptorBuildRestClient = RestClientFactorySupport.create(
            properties.getBaseUrl(),
            properties.getConnectTimeoutMs(),
            properties.getRaptorBuildReadTimeoutMs()
        );
    }

    public RagToolsHealthResponse health() {
        return restClient.get()
            .uri("/health")
            .retrieve()
            .body(RagToolsHealthResponse.class);
    }

    public RagToolsRerankResponse rerank(RagToolsRerankRequest request) {
        return restClient.post()
            .uri("/rerank")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(RagToolsRerankResponse.class);
    }

    public RagToolsDocumentParseResponse parseDocument(RagToolsDocumentParseRequest request) {
        long startedNanos = System.nanoTime();
        try {
            RagToolsDocumentParseResponse response = restClient.post()
                .uri("/document/parse")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(RagToolsDocumentParseResponse.class);
            log.info("Python 文档解析接口调用完成，fileName={}, fileType={}, parsedTextLength={}, blockCount={}, artifactCount={}, structureNodeCount={}, costMillis={}",
                request == null ? null : request.getFileName(),
                request == null ? null : request.getFileType(),
                response == null || response.getParsedText() == null ? 0 : response.getParsedText().length(),
                response == null || response.getBlocks() == null ? 0 : response.getBlocks().size(),
                response == null || response.getArtifacts() == null ? 0 : response.getArtifacts().size(),
                response == null || response.getStructureNodes() == null ? 0 : response.getStructureNodes().size(),
                elapsedMillis(startedNanos));
            return response;
        }
        catch (RestClientException exception) {
            throw new IllegalStateException("调用 Python 文档解析接口失败，耗时 " + elapsedMillis(startedNanos)
                + "ms: " + exception.getMessage(), exception);
        }
    }

    public RagToolsCitationRepairResponse repairCitations(RagToolsCitationRepairRequest request) {
        return restClient.post()
            .uri("/citation/repair")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(RagToolsCitationRepairResponse.class);
    }

    public RagToolsGraphExtractResponse extractGraph(RagToolsGraphExtractRequest request) {
        return graphExtractRestClient.post()
            .uri("/graph/extract")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(RagToolsGraphExtractResponse.class);
    }

    public RagToolsRaptorBuildResponse buildRaptor(RagToolsRaptorBuildRequest request) {
        long startedNanos = System.nanoTime();
        try {
            RagToolsRaptorBuildResponse response = raptorBuildRestClient.post()
                .uri("/raptor/build")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange((httpRequest, httpResponse) -> {
                    String body = StreamUtils.copyToString(httpResponse.getBody(), StandardCharsets.UTF_8);
                    MediaType contentType = httpResponse.getHeaders().getContentType();
                    if (httpResponse.getStatusCode().isError()) {
                        throw new IllegalStateException("Python RAPTOR 构建接口返回错误: status="
                            + httpResponse.getStatusCode().value()
                            + ", contentType=" + contentType
                            + ", body=" + preview(body));
                    }
                    if (body == null || body.isBlank()) {
                        throw new IllegalStateException("Python RAPTOR 构建接口返回空响应: contentType=" + contentType);
                    }
                    try {
                        return objectMapper.readValue(body, RagToolsRaptorBuildResponse.class);
                    }
                    catch (JsonProcessingException exception) {
                        throw new IllegalStateException("Python RAPTOR 构建接口返回非预期 JSON: contentType="
                            + contentType + ", body=" + preview(body), exception);
                    }
                });
            log.info("Python RAPTOR 构建接口调用完成，documentId={}, taskId={}, chunkCount={}, nodeCount={}, llmSummaryEnabled={}, costMillis={}",
                request == null ? null : request.getDocumentId(),
                request == null ? null : request.getTaskId(),
                request == null || request.getChunks() == null ? 0 : request.getChunks().size(),
                response == null || response.getNodes() == null ? 0 : response.getNodes().size(),
                request == null ? null : request.getLlmSummaryEnabled(),
                elapsedMillis(startedNanos));
            return response;
        }
        catch (RuntimeException exception) {
            log.error("Python RAPTOR 构建接口调用失败，documentId={}, taskId={}, chunkCount={}, llmSummaryEnabled={}, costMillis={}, message={}",
                request == null ? null : request.getDocumentId(),
                request == null ? null : request.getTaskId(),
                request == null || request.getChunks() == null ? 0 : request.getChunks().size(),
                request == null ? null : request.getLlmSummaryEnabled(),
                elapsedMillis(startedNanos),
                exception.getMessage(),
                exception);
            if (!(exception instanceof RestClientException)) {
                throw exception;
            }
            throw new IllegalStateException("调用 Python RAPTOR 构建接口失败: " + exception.getMessage(), exception);
        }
    }

    private String preview(String body) {
        if (body == null) {
            return "";
        }
        String normalized = body.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "...";
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
