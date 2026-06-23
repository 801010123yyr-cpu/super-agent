package org.javaup.ai.manage.service;

import org.javaup.ai.manage.model.graph.GraphRagExtractionAdvice;
import org.javaup.ai.manage.model.graph.GraphRagExtractionContext;

import java.util.Optional;

public interface GraphRagExtractionAdvisor {

    Optional<GraphRagExtractionAdvice> extract(GraphRagExtractionContext context);
}
