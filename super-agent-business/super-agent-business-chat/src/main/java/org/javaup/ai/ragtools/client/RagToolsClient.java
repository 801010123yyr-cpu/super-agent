package org.javaup.ai.ragtools.client;

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
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class RagToolsClient {

    private final RestClient restClient;

    public RagToolsClient(RagToolsProperties properties) {
        this.restClient = RestClientFactorySupport.create(
            properties.getBaseUrl(),
            properties.getConnectTimeoutMs(),
            properties.getReadTimeoutMs()
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
        return restClient.post()
            .uri("/document/parse")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(RagToolsDocumentParseResponse.class);
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
        return restClient.post()
            .uri("/graph/extract")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(RagToolsGraphExtractResponse.class);
    }

    public RagToolsRaptorBuildResponse buildRaptor(RagToolsRaptorBuildRequest request) {
        return restClient.post()
            .uri("/raptor/build")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(RagToolsRaptorBuildResponse.class);
    }
}
