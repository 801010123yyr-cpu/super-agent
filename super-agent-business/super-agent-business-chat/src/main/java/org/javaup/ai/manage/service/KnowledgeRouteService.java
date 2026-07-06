package org.javaup.ai.manage.service;

import org.javaup.ai.manage.model.route.KnowledgeRouteContext;
import org.javaup.ai.manage.model.route.KnowledgeRouteDecision;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务层
 * @author: 阿星不是程序员
 **/
public interface KnowledgeRouteService {

    KnowledgeRouteDecision route(KnowledgeRouteContext context);

    void recordShadowRoute(String conversationId,
                           long exchangeId,
                           Long selectedDocumentId,
                           KnowledgeRouteContext context);

    void recordAutoRoute(String conversationId,
                         long exchangeId,
                         KnowledgeRouteContext context,
                         KnowledgeRouteDecision decision);
}
