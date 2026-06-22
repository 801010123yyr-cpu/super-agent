package org.javaup.ai.manage.service;

import org.javaup.ai.manage.model.graph.GraphRagSearchResult;

import java.util.List;

public interface GraphRagSearchService {

    List<GraphRagSearchResult> search(String question,
                                      List<Long> documentIds,
                                      List<Long> taskIds,
                                      int topK,
                                      int maxHops);
}
