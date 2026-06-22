package org.javaup.ai.manage.service;

import org.javaup.ai.manage.data.SuperAgentDocumentBlock;
import org.javaup.ai.manage.data.SuperAgentDocumentParseArtifact;

import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务层
 * @author: 阿星不是程序员
 **/

public interface DocumentParseArtifactService {

    void replaceTaskArtifacts(Long documentId,
                              Long taskId,
                              List<SuperAgentDocumentParseArtifact> artifactList,
                              List<SuperAgentDocumentBlock> blockList);

    void saveArtifacts(Long documentId, Long taskId, List<SuperAgentDocumentParseArtifact> artifactList);

    void saveBlocks(Long documentId, Long taskId, List<SuperAgentDocumentBlock> blockList);

    List<SuperAgentDocumentParseArtifact> listArtifacts(Long documentId, Long taskId);

    List<SuperAgentDocumentBlock> listBlocks(Long documentId, Long taskId);

    List<String> listObjectNamesByDocumentId(Long documentId);

    void deleteByTask(Long documentId, Long taskId);

    void deleteByDocumentId(Long documentId);
}
