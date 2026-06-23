package org.javaup.ai.manage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.graph-rag.build")
public class GraphRagBuildProperties {

    private boolean leaseEnabled = true;

    private int leaseTtlSeconds = 120;

    private int maxAttempts = 2;

    private long retryBackoffMillis = 500L;
}
