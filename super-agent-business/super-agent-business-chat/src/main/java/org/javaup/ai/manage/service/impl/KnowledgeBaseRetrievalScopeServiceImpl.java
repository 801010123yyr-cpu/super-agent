package org.javaup.ai.manage.service.impl;

import cn.hutool.core.collection.CollUtil;
import org.javaup.ai.chatagent.rag.model.RagRuntimeOptions;
import org.javaup.ai.chatagent.rag.service.KnowledgeBaseRuntimeConfigResolver;
import org.javaup.ai.manage.data.SuperAgentKnowledgeBase;
import org.javaup.ai.manage.model.KnowledgeBaseSelectionSnapshot;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.ai.manage.service.KnowledgeBaseManageService;
import org.javaup.ai.manage.service.KnowledgeBaseRetrievalScopeService;
import org.javaup.enums.BaseCode;
import org.javaup.enums.ChatQueryMode;
import org.javaup.enums.KnowledgeBaseSelectionMode;
import org.javaup.exception.SuperAgentFrameException;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class KnowledgeBaseRetrievalScopeServiceImpl implements KnowledgeBaseRetrievalScopeService {

    private final KnowledgeBaseManageService knowledgeBaseManageService;
    private final DocumentKnowledgeService documentKnowledgeService;
    private final KnowledgeBaseRuntimeConfigResolver runtimeConfigResolver;

    public KnowledgeBaseRetrievalScopeServiceImpl(KnowledgeBaseManageService knowledgeBaseManageService,
                                                  DocumentKnowledgeService documentKnowledgeService,
                                                  KnowledgeBaseRuntimeConfigResolver runtimeConfigResolver) {
        this.knowledgeBaseManageService = knowledgeBaseManageService;
        this.documentKnowledgeService = documentKnowledgeService;
        this.runtimeConfigResolver = runtimeConfigResolver;
    }

    @Override
    public KnowledgeBaseSelectionSnapshot resolve(ChatQueryMode chatMode,
                                                  KnowledgeBaseSelectionMode selectionMode,
                                                  Collection<String> selectedKnowledgeBaseIds) {
        KnowledgeBaseSelectionMode resolvedMode = selectionMode == null ? KnowledgeBaseSelectionMode.NONE : selectionMode;
        if (chatMode == ChatQueryMode.OPEN_CHAT || resolvedMode == KnowledgeBaseSelectionMode.NONE) {
            return KnowledgeBaseSelectionSnapshot.none(runtimeConfigResolver.resolve(List.of()));
        }
        List<SuperAgentKnowledgeBase> selectedBases = switch (resolvedMode) {
            case ALL -> selectAllWithRetrievableDocuments();
            case SELECTED -> selectExplicit(selectedKnowledgeBaseIds);
            case NONE -> List.of();
        };
        if (selectedBases.isEmpty()) {
            return KnowledgeBaseSelectionSnapshot.builder()
                .selectionMode(resolvedMode)
                .ragRuntimeOptions(runtimeConfigResolver.resolve(List.of()))
                .build();
        }
        List<Long> selectedBaseIds = selectedBases.stream().map(SuperAgentKnowledgeBase::getId).toList();
        List<KnowledgeDocumentDescriptor> allowedDocuments = documentKnowledgeService.listRetrievableDocumentsByKnowledgeBaseIds(selectedBaseIds);
        RagRuntimeOptions options = runtimeConfigResolver.resolve(selectedBases);
        return KnowledgeBaseSelectionSnapshot.builder()
            .selectionMode(resolvedMode)
            .selectedKnowledgeBases(selectedBases)
            .selectedKnowledgeBaseIds(selectedBaseIds)
            .selectedKnowledgeBaseNames(selectedBases.stream().map(SuperAgentKnowledgeBase::getBaseName).toList())
            .allowedDocuments(allowedDocuments)
            .allowedDocumentIds(allowedDocuments.stream()
                .map(KnowledgeDocumentDescriptor::getDocumentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList())
            .allowedTaskIds(allowedDocuments.stream()
                .map(KnowledgeDocumentDescriptor::getLastIndexTaskId)
                .filter(Objects::nonNull)
                .distinct()
                .toList())
            .ragRuntimeOptions(options)
            .build();
    }

    private List<SuperAgentKnowledgeBase> selectAllWithRetrievableDocuments() {
        List<SuperAgentKnowledgeBase> enabledBases = knowledgeBaseManageService.listAllEnabled();
        if (enabledBases.isEmpty()) {
            return List.of();
        }
        List<Long> baseIds = enabledBases.stream().map(SuperAgentKnowledgeBase::getId).toList();
        LinkedHashSet<Long> nonEmptyBaseIds = documentKnowledgeService.listRetrievableDocumentsByKnowledgeBaseIds(baseIds)
            .stream()
            .map(KnowledgeDocumentDescriptor::getKnowledgeBaseId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return enabledBases.stream()
            .filter(base -> nonEmptyBaseIds.contains(base.getId()))
            .toList();
    }

    private List<SuperAgentKnowledgeBase> selectExplicit(Collection<String> selectedKnowledgeBaseIds) {
        List<Long> ids = selectedKnowledgeBaseIds == null
            ? List.of()
            : selectedKnowledgeBaseIds.stream()
                .map(this::parseKnowledgeBaseId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (CollUtil.isEmpty(ids)) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "请选择至少一个知识库。");
        }
        List<SuperAgentKnowledgeBase> bases = knowledgeBaseManageService.listEnabledByIds(ids);
        Map<Long, SuperAgentKnowledgeBase> byId = bases.stream()
            .collect(Collectors.toMap(SuperAgentKnowledgeBase::getId, Function.identity()));
        for (Long id : ids) {
            if (!byId.containsKey(id)) {
                throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "知识库不存在或已停用: " + id);
            }
        }
        return ids.stream().map(byId::get).toList();
    }

    private Long parseKnowledgeBaseId(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        try {
            Long id = Long.valueOf(rawId.trim());
            if (id <= 0) {
                throw new NumberFormatException("must be positive");
            }
            return id;
        }
        catch (NumberFormatException exception) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "knowledgeBaseId 格式非法。");
        }
    }
}
