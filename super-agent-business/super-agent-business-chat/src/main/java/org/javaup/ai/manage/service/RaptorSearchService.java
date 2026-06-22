package org.javaup.ai.manage.service;

import org.javaup.ai.manage.model.raptor.RaptorSearchResult;

import java.util.List;

public interface RaptorSearchService {

    List<RaptorSearchResult> search(String question, List<Long> documentIds, List<Long> taskIds, int topK, int sourceChunkTopK);
}
