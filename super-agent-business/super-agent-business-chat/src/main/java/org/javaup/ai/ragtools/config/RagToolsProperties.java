package org.javaup.ai.ragtools.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.rag-tools")
public class RagToolsProperties {

    private String baseUrl = "http://127.0.0.1:18080";

    private int connectTimeoutMs = 3000;

    private int readTimeoutMs = 10000;

    private int documentParseReadTimeoutMs = 600000;

    private int graphExtractReadTimeoutMs = 30000;

    private int raptorBuildReadTimeoutMs = 60000;
}
