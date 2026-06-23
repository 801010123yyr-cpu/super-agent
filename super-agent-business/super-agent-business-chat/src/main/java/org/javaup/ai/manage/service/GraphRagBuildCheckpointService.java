package org.javaup.ai.manage.service;

import org.javaup.ai.manage.model.graph.GraphRagBuildResult;

import java.util.Map;

public interface GraphRagBuildCheckpointService {

    void markRunning(Long documentId,
                     Long taskId,
                     String stage,
                     int attempt,
                     int maxAttempts,
                     Map<String, Object> detail);

    void markSuccess(Long documentId,
                     Long taskId,
                     GraphRagBuildResult result,
                     int attempt,
                     int maxAttempts);

    void markRetry(Long documentId,
                   Long taskId,
                   String stage,
                   int attempt,
                   int maxAttempts,
                   long backoffMillis,
                   Throwable exception);

    void markRejected(Long documentId,
                      Long taskId,
                      String stage,
                      int maxAttempts,
                      String message,
                      Map<String, Object> detail);

    void markFailure(Long documentId,
                     Long taskId,
                     String stage,
                     int attempt,
                     int maxAttempts,
                     Throwable exception);
}
