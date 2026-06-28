package org.javaup.ai.manage.service;

import org.javaup.ai.manage.data.SuperAgentRaptorNode;

import java.util.List;

public interface RaptorSummaryIndexService {

    void indexNodes(List<SuperAgentRaptorNode> nodes);

    void deleteByTask(Long documentId, Long taskId);

    void deleteByDocumentId(Long documentId);

    List<RaptorSummaryHit> search(String question, List<Long> documentIds, List<Long> taskIds, int topK);

    record RaptorSummaryHit(Long nodeId, double score) {
    }
}
