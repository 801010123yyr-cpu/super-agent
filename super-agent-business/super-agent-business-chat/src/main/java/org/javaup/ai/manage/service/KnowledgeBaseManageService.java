package org.javaup.ai.manage.service;

import org.javaup.ai.manage.data.SuperAgentKnowledgeBase;
import org.javaup.ai.manage.dto.KnowledgeBaseConfigUpdateDto;
import org.javaup.ai.manage.dto.KnowledgeBaseDeleteDto;
import org.javaup.ai.manage.dto.KnowledgeBaseDetailDto;
import org.javaup.ai.manage.dto.KnowledgeBaseSaveDto;
import org.javaup.ai.manage.vo.KnowledgeBaseItemVo;
import org.javaup.ai.manage.vo.KnowledgeBaseOptionVo;

import java.util.Collection;
import java.util.List;

public interface KnowledgeBaseManageService {

    KnowledgeBaseItemVo save(KnowledgeBaseSaveDto dto);

    boolean delete(KnowledgeBaseDeleteDto dto);

    List<KnowledgeBaseItemVo> list();

    KnowledgeBaseItemVo detail(KnowledgeBaseDetailDto dto);

    KnowledgeBaseItemVo updateConfig(KnowledgeBaseConfigUpdateDto dto);

    List<KnowledgeBaseOptionVo> listOptions();

    List<SuperAgentKnowledgeBase> listEnabledByIds(Collection<Long> ids);

    List<SuperAgentKnowledgeBase> listAllEnabled();

    SuperAgentKnowledgeBase requireEnabled(Long id);
}
