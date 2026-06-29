package org.javaup.ai.manage.service;

import org.javaup.ai.manage.model.raptor.RaptorQualityReport;
import org.javaup.ai.ragtools.model.RagToolsRaptorBuildResponse;

import java.util.List;

public interface RaptorQualityService {

    RaptorQualityReport evaluate(Long documentId, Long taskId);

    RaptorQualityReport evaluatePythonNodes(List<RagToolsRaptorBuildResponse.Node> nodes, double configuredFloor);
}
