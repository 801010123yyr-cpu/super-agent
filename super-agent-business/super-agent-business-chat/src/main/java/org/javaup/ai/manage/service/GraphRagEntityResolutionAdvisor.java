package org.javaup.ai.manage.service;

import org.javaup.ai.manage.model.graph.GraphRagEntityResolutionAdvice;
import org.javaup.ai.manage.model.graph.GraphRagEntityResolutionContext;

import java.util.Optional;

public interface GraphRagEntityResolutionAdvisor {

    Optional<GraphRagEntityResolutionAdvice> advise(GraphRagEntityResolutionContext context);
}
