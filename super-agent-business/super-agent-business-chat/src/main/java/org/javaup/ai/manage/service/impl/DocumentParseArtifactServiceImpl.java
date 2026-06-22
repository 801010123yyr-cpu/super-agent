package org.javaup.ai.manage.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.AllArgsConstructor;
import org.javaup.ai.manage.data.SuperAgentDocumentBlock;
import org.javaup.ai.manage.data.SuperAgentDocumentParseArtifact;
import org.javaup.ai.manage.mapper.SuperAgentDocumentBlockMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentParseArtifactMapper;
import org.javaup.ai.manage.service.DocumentParseArtifactService;
import org.javaup.ai.manage.service.DocumentStorageService;
import org.javaup.ai.manage.service.DocumentTableStructureService;
import org.javaup.enums.BusinessStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务实现层
 * @author: 阿星不是程序员
 **/

@AllArgsConstructor
@Service
public class DocumentParseArtifactServiceImpl implements DocumentParseArtifactService {

    private final SuperAgentDocumentParseArtifactMapper artifactMapper;

    private final SuperAgentDocumentBlockMapper blockMapper;

    private final DocumentStorageService storageService;

    private final DocumentTableStructureService tableStructureService;

    private final UidGenerator uidGenerator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceTaskArtifacts(Long documentId,
                                     Long taskId,
                                     List<SuperAgentDocumentParseArtifact> artifactList,
                                     List<SuperAgentDocumentBlock> blockList) {
        storageService.deleteObjects(listObjectNames(documentId, taskId));
        deleteByTask(documentId, taskId);
        saveArtifacts(documentId, taskId, artifactList);
        saveBlocks(documentId, taskId, blockList);
        tableStructureService.replaceTaskTables(documentId, taskId, blockList);
    }

    @Override
    public void saveArtifacts(Long documentId, Long taskId, List<SuperAgentDocumentParseArtifact> artifactList) {
        if (documentId == null || taskId == null || CollUtil.isEmpty(artifactList)) {
            return;
        }

        for (SuperAgentDocumentParseArtifact artifact : artifactList) {
            if (artifact == null) {
                continue;
            }
            if (artifact.getId() == null) {
                artifact.setId(uidGenerator.getUid());
            }
            artifact.setDocumentId(documentId);
            artifact.setTaskId(taskId);
            if (artifact.getStatus() == null) {
                artifact.setStatus(BusinessStatus.YES.getCode());
            }
            artifactMapper.insert(artifact);
        }
    }

    @Override
    public void saveBlocks(Long documentId, Long taskId, List<SuperAgentDocumentBlock> blockList) {
        if (documentId == null || taskId == null || CollUtil.isEmpty(blockList)) {
            return;
        }

        for (SuperAgentDocumentBlock block : blockList) {
            if (block == null) {
                continue;
            }
            if (block.getId() == null) {
                block.setId(uidGenerator.getUid());
            }
            block.setDocumentId(documentId);
            block.setTaskId(taskId);
            if (block.getStatus() == null) {
                block.setStatus(BusinessStatus.YES.getCode());
            }
            blockMapper.insert(block);
        }
    }

    @Override
    public List<SuperAgentDocumentParseArtifact> listArtifacts(Long documentId, Long taskId) {
        if (documentId == null || taskId == null) {
            return List.of();
        }
        return artifactMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentParseArtifact>()
            .eq(SuperAgentDocumentParseArtifact::getDocumentId, documentId)
            .eq(SuperAgentDocumentParseArtifact::getTaskId, taskId)
            .eq(SuperAgentDocumentParseArtifact::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentDocumentParseArtifact::getId));
    }

    @Override
    public List<SuperAgentDocumentBlock> listBlocks(Long documentId, Long taskId) {
        if (documentId == null || taskId == null) {
            return List.of();
        }
        return blockMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentBlock>()
            .eq(SuperAgentDocumentBlock::getDocumentId, documentId)
            .eq(SuperAgentDocumentBlock::getTaskId, taskId)
            .eq(SuperAgentDocumentBlock::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentDocumentBlock::getBlockNo)
            .orderByAsc(SuperAgentDocumentBlock::getId));
    }

    @Override
    public List<String> listObjectNamesByDocumentId(Long documentId) {
        if (documentId == null) {
            return List.of();
        }
        return artifactMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentParseArtifact>()
                .eq(SuperAgentDocumentParseArtifact::getDocumentId, documentId))
            .stream()
            .map(SuperAgentDocumentParseArtifact::getObjectName)
            .filter(StrUtil::isNotBlank)
            .distinct()
            .toList();
    }

    @Override
    public void deleteByTask(Long documentId, Long taskId) {
        if (documentId == null || taskId == null) {
            return;
        }
        tableStructureService.deleteByTask(documentId, taskId);
        blockMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentBlock>()
            .eq(SuperAgentDocumentBlock::getDocumentId, documentId)
            .eq(SuperAgentDocumentBlock::getTaskId, taskId));
        artifactMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentParseArtifact>()
            .eq(SuperAgentDocumentParseArtifact::getDocumentId, documentId)
            .eq(SuperAgentDocumentParseArtifact::getTaskId, taskId));
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        tableStructureService.deleteByDocumentId(documentId);
        blockMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentBlock>()
            .eq(SuperAgentDocumentBlock::getDocumentId, documentId));
        artifactMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentParseArtifact>()
            .eq(SuperAgentDocumentParseArtifact::getDocumentId, documentId));
    }

    private List<String> listObjectNames(Long documentId, Long taskId) {
        return listArtifacts(documentId, taskId).stream()
            .map(SuperAgentDocumentParseArtifact::getObjectName)
            .filter(StrUtil::isNotBlank)
            .distinct()
            .toList();
    }
}
