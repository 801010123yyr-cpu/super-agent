package org.javaup.ai.manage.service;

import org.javaup.ai.manage.model.graph.GraphRagCrossDocumentIndexBuildResult;
import org.javaup.ai.manage.support.GraphRagCrossDocumentIndex;

import java.util.List;

public interface GraphRagCrossDocumentIndexService {

    String GLOBAL_SCOPE_KEY = "global";

    List<GraphRagCrossDocumentIndexBuildResult> rebuildAll();

    GraphRagCrossDocumentIndex loadIndex(List<Long> documentIds, List<Long> taskIds);
}
