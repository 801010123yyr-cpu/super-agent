package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import org.javaup.ai.chatagent.rag.model.ConversationStructureAnchor;
import org.javaup.ai.chatagent.rag.model.StructureNavigationIntent;
import org.javaup.ai.chatagent.rag.model.StructureNavigationOperation;
import org.javaup.ai.chatagent.rag.model.StructureNavigationResult;
import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;
import org.javaup.ai.manage.service.DocumentStructureNodeService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
@AllArgsConstructor
public class StructureNavigationResolver {

    private final DocumentStructureNodeService structureNodeService;

    public StructureNavigationResult resolve(Long documentId,
                                             Long parseTaskId,
                                             StructureNavigationIntent intent,
                                             ConversationStructureAnchor conversationAnchor) {
        if (documentId == null) {
            return missed(null, null, "DOCUMENT_ID_EMPTY");
        }
        SuperAgentDocumentStructureNode anchor = resolveAnchor(documentId, parseTaskId, intent, conversationAnchor);
        if (anchor == null) {
            return missed(documentId, null, "ANCHOR_NOT_FOUND");
        }
        List<StructureNavigationOperation> operations = intent == null || intent.getOperations() == null
            ? List.of()
            : intent.getOperations();
        SuperAgentDocumentStructureNode parent = shouldResolveParent(operations)
            ? structureNodeService.findById(documentId, parseTaskId, anchor.getParentNodeId())
            : null;
        SuperAgentDocumentStructureNode previous = shouldResolvePrevious(operations)
            ? structureNodeService.findPreviousSibling(documentId, parseTaskId, anchor.getId())
            : null;
        SuperAgentDocumentStructureNode next = shouldResolveNext(operations)
            ? structureNodeService.findNextSibling(documentId, parseTaskId, anchor.getId())
            : null;
        List<SuperAgentDocumentStructureNode> children = shouldResolveChildren(operations)
            ? structureNodeService.listChildren(documentId, parseTaskId, anchor.getId())
            : List.of();
        return StructureNavigationResult.builder()
            .documentId(documentId)
            .anchorNodeId(anchor.getId())
            .current(anchor)
            .parent(parent)
            .previousSibling(previous)
            .nextSibling(next)
            .directChildren(children)
            .deterministic(true)
            .build();
    }

    private SuperAgentDocumentStructureNode resolveAnchor(Long documentId,
                                                          Long parseTaskId,
                                                          StructureNavigationIntent intent,
                                                          ConversationStructureAnchor conversationAnchor) {
        Long intentNodeId = intent == null ? null : intent.getAnchorStructureNodeId();
        SuperAgentDocumentStructureNode anchor = structureNodeService.findById(documentId, parseTaskId, intentNodeId);
        if (anchor != null) {
            return anchor;
        }
        Long conversationNodeId = conversationAnchor == null ? null : conversationAnchor.getStructureNodeId();
        anchor = structureNodeService.findById(documentId, parseTaskId, conversationNodeId);
        if (anchor != null) {
            return anchor;
        }
        String canonicalPath = firstNonBlank(
            intent == null ? null : intent.getAnchorCanonicalPath(),
            conversationAnchor == null ? null : conversationAnchor.getCanonicalPath()
        );
        if (StrUtil.isNotBlank(canonicalPath)) {
            anchor = findByCanonicalPath(documentId, parseTaskId, canonicalPath);
            if (anchor != null) {
                return anchor;
            }
        }
        String sectionPath = intent == null ? null : intent.getAnchorSectionPath();
        return StrUtil.isBlank(sectionPath) ? null : findBySectionPath(documentId, parseTaskId, sectionPath);
    }

    private SuperAgentDocumentStructureNode findByCanonicalPath(Long documentId, Long parseTaskId, String canonicalPath) {
        return structureNodeService.listDocumentNodes(documentId, parseTaskId).stream()
            .filter(node -> Objects.equals(StrUtil.trim(node.getCanonicalPath()), StrUtil.trim(canonicalPath)))
            .findFirst()
            .orElse(null);
    }

    private SuperAgentDocumentStructureNode findBySectionPath(Long documentId, Long parseTaskId, String sectionPath) {
        return structureNodeService.listDocumentNodes(documentId, parseTaskId).stream()
            .filter(node -> Objects.equals(StrUtil.trim(node.getSectionPath()), StrUtil.trim(sectionPath)))
            .findFirst()
            .orElse(null);
    }

    private boolean shouldResolveParent(List<StructureNavigationOperation> operations) {
        return operations.isEmpty()
            || operations.contains(StructureNavigationOperation.PARENT_SECTION)
            || operations.contains(StructureNavigationOperation.SECTION_WITH_SIBLINGS);
    }

    private boolean shouldResolvePrevious(List<StructureNavigationOperation> operations) {
        return operations.isEmpty()
            || operations.contains(StructureNavigationOperation.PREVIOUS_SIBLING)
            || operations.contains(StructureNavigationOperation.SECTION_WITH_SIBLINGS);
    }

    private boolean shouldResolveNext(List<StructureNavigationOperation> operations) {
        return operations.isEmpty()
            || operations.contains(StructureNavigationOperation.NEXT_SIBLING)
            || operations.contains(StructureNavigationOperation.SECTION_WITH_SIBLINGS);
    }

    private boolean shouldResolveChildren(List<StructureNavigationOperation> operations) {
        return operations.contains(StructureNavigationOperation.DIRECT_CHILDREN)
            || operations.contains(StructureNavigationOperation.SECTION_WITH_CHILDREN);
    }

    private StructureNavigationResult missed(Long documentId, Long anchorNodeId, String reason) {
        return StructureNavigationResult.builder()
            .documentId(documentId)
            .anchorNodeId(anchorNodeId)
            .deterministic(false)
            .missReason(reason)
            .build();
    }

    private String firstNonBlank(String first, String second) {
        return StrUtil.isNotBlank(first) ? first : second;
    }
}
