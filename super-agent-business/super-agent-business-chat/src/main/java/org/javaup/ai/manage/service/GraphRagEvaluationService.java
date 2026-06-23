package org.javaup.ai.manage.service;

import org.javaup.ai.manage.model.graph.GraphRagEvaluationReport;
import org.javaup.ai.manage.model.graph.GraphRagEvaluationBatchReport;
import org.javaup.ai.manage.model.graph.GraphRagEvaluationSuite;

import java.util.List;

public interface GraphRagEvaluationService {

    GraphRagEvaluationReport evaluate(GraphRagEvaluationSuite suite);

    GraphRagEvaluationBatchReport evaluateBatch(String batchId, String name, List<GraphRagEvaluationSuite> suites);
}
