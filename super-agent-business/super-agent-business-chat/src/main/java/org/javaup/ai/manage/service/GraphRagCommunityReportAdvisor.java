package org.javaup.ai.manage.service;

import org.javaup.ai.manage.model.graph.GraphRagCommunityReportAdvice;
import org.javaup.ai.manage.model.graph.GraphRagCommunityReportContext;

import java.util.Optional;

public interface GraphRagCommunityReportAdvisor {

    Optional<GraphRagCommunityReportAdvice> generateReport(GraphRagCommunityReportContext context);
}
