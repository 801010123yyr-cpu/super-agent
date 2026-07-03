package org.javaup.ai.manage.service;

import org.javaup.ai.manage.model.KnowledgeBaseSelectionSnapshot;
import org.javaup.enums.ChatQueryMode;
import org.javaup.enums.KnowledgeBaseSelectionMode;

import java.util.Collection;

public interface KnowledgeBaseRetrievalScopeService {

    KnowledgeBaseSelectionSnapshot resolve(ChatQueryMode chatMode,
                                           KnowledgeBaseSelectionMode selectionMode,
                                           Collection<String> selectedKnowledgeBaseIds);
}
