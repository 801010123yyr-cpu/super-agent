package org.javaup.ai.manage.service;

import org.javaup.ai.manage.data.SuperAgentDocumentChunk;
import org.javaup.ai.manage.model.graph.GraphRagBuildResult;

import java.util.List;

public interface GraphRagBuildService {

    GraphRagBuildResult rebuildDocumentGraph(Long documentId, Long taskId, List<SuperAgentDocumentChunk> chunks);

    void deleteByTask(Long documentId, Long taskId);

    void deleteByDocumentId(Long documentId);
}
