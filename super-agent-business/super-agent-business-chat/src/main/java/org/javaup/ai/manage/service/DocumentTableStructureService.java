package org.javaup.ai.manage.service;

import org.javaup.ai.manage.data.SuperAgentDocumentBlock;
import org.javaup.ai.manage.model.table.DocumentTableDescriptor;
import org.javaup.ai.manage.model.table.DocumentTableQuery;
import org.javaup.ai.manage.model.table.DocumentTableQueryResult;

import java.util.List;

public interface DocumentTableStructureService {

    void replaceTaskTables(Long documentId, Long taskId, List<SuperAgentDocumentBlock> blockList);

    List<DocumentTableDescriptor> listTables(List<Long> documentIds, List<Long> taskIds);

    DocumentTableQueryResult query(DocumentTableQuery query);

    void deleteByTask(Long documentId, Long taskId);

    void deleteByDocumentId(Long documentId);
}
