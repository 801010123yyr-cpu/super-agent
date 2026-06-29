package org.javaup.ai.manage.service;

import org.javaup.ai.manage.data.SuperAgentDocumentChunk;
import org.javaup.ai.manage.model.raptor.RaptorBuildResult;

import java.util.List;

public interface RaptorBuildService {

    RaptorBuildResult rebuildDocumentTree(Long documentId, Long taskId, List<SuperAgentDocumentChunk> chunks);

    RaptorBuildResult rebuildKnowledgeScopeTree(String knowledgeScopeCode);

    void deleteByTask(Long documentId, Long taskId);

    void deleteByDocumentId(Long documentId);

    void deleteByScope(String scopeType, String scopeKey);
}
