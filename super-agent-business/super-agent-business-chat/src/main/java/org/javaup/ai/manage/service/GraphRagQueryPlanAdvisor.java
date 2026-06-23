package org.javaup.ai.manage.service;

import org.javaup.ai.manage.model.graph.GraphRagQueryCatalog;
import org.javaup.ai.manage.model.graph.GraphRagQueryPlanAdvice;

import java.util.Optional;

public interface GraphRagQueryPlanAdvisor {

    Optional<GraphRagQueryPlanAdvice> advise(String question, GraphRagQueryCatalog catalog);
}
