package org.javaup.ai.manage.service;

import org.javaup.ai.manage.model.graph.GraphRagQualityReport;

public interface GraphRagQualityService {

    GraphRagQualityReport evaluate(Long documentId, Long taskId);
}
