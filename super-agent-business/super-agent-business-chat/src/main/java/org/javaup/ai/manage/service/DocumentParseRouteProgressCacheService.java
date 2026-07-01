package org.javaup.ai.manage.service;

import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyPlan;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyStep;
import org.javaup.ai.manage.data.SuperAgentDocumentTask;
import org.javaup.ai.manage.data.SuperAgentDocumentTaskLog;
import org.javaup.ai.manage.vo.DocumentParseRouteProgressVo;

import java.util.List;

/**
 * Hot progress snapshot for parse-route polling. MySQL remains the source of truth.
 */
public interface DocumentParseRouteProgressCacheService {

    DocumentParseRouteProgressVo get(Long taskId);

    void init(SuperAgentDocument document, SuperAgentDocumentTask task);

    void update(SuperAgentDocument document, SuperAgentDocumentTask task);

    void update(SuperAgentDocument document, SuperAgentDocumentTask task, SuperAgentDocumentTaskLog latestLog);

    void update(SuperAgentDocument document,
                SuperAgentDocumentTask task,
                SuperAgentDocumentStrategyPlan plan,
                List<SuperAgentDocumentStrategyStep> steps,
                SuperAgentDocumentTaskLog latestLog);
}
