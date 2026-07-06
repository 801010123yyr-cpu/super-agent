package org.javaup.ai.chatagent.rag.service;

import org.javaup.ai.chatagent.rag.model.ConversationStructureAnchor;
import org.javaup.ai.chatagent.rag.model.StructureNavigationIntent;
import org.javaup.ai.chatagent.rag.model.StructureNavigationOperation;
import org.javaup.ai.chatagent.rag.model.StructureNavigationResult;
import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;
import org.javaup.ai.manage.service.DocumentStructureNodeService;
import org.javaup.ai.manage.support.DocumentStructureNodeCandidate;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class StructureNavigationResolverTest {

    @Test
    void resolvesParentAndNextSiblingFromStructureNodeAnchor() {
        InMemoryStructureNodeService nodeService = new InMemoryStructureNodeService(List.of(
            node(130L, 10L, 20L, 130, "十三、上线观察与值班规则", null, null, null,
                "十三、上线观察与值班规则", "十三、上线观察与值班规则"),
            node(131L, 10L, 20L, 131, "13.1 观察时长", 130L, null, 132L,
                "十三、上线观察与值班规则 > 13.1 观察时长", "十三、上线观察与值班规则/13.1 观察时长"),
            node(132L, 10L, 20L, 132, "13.2 值班安排", 130L, 131L, null,
                "十三、上线观察与值班规则 > 13.2 值班安排", "十三、上线观察与值班规则/13.2 值班安排")
        ));
        StructureNavigationResolver resolver = new StructureNavigationResolver(nodeService);

        StructureNavigationResult result = resolver.resolve(
            10L,
            20L,
            StructureNavigationIntent.builder()
                .anchorStructureNodeId(131L)
                .operations(List.of(StructureNavigationOperation.SECTION_WITH_SIBLINGS))
                .confidence(0.91D)
                .source("test")
                .build(),
            null
        );

        assertThat(result.isDeterministic()).isTrue();
        assertThat(result.getCurrent().getTitle()).isEqualTo("13.1 观察时长");
        assertThat(result.getParent().getTitle()).isEqualTo("十三、上线观察与值班规则");
        assertThat(result.getNextSibling().getTitle()).isEqualTo("13.2 值班安排");
        assertThat(result.getPreviousSibling()).isNull();
    }

    @Test
    void resolvesDirectChildrenFromConversationAnchor() {
        InMemoryStructureNodeService nodeService = new InMemoryStructureNodeService(List.of(
            node(100L, 10L, 20L, 100, "十、机器人策略设计", null, null, null,
                "十、机器人策略设计", "十、机器人策略设计"),
            node(101L, 10L, 20L, 101, "10.1 策略层次", 100L, null, 102L,
                "十、机器人策略设计 > 10.1 策略层次", "十、机器人策略设计/10.1 策略层次"),
            node(102L, 10L, 20L, 102, "10.2 必配策略项", 100L, 101L, 103L,
                "十、机器人策略设计 > 10.2 必配策略项", "十、机器人策略设计/10.2 必配策略项"),
            node(103L, 10L, 20L, 103, "10.3 不建议交给机器人直接回答的主题", 100L, 102L, null,
                "十、机器人策略设计 > 10.3 不建议交给机器人直接回答的主题", "十、机器人策略设计/10.3 不建议交给机器人直接回答的主题")
        ));
        StructureNavigationResolver resolver = new StructureNavigationResolver(nodeService);

        StructureNavigationResult result = resolver.resolve(
            10L,
            20L,
            StructureNavigationIntent.builder()
                .operations(List.of(StructureNavigationOperation.SECTION_WITH_CHILDREN))
                .confidence(0.88D)
                .source("test")
                .build(),
            ConversationStructureAnchor.builder()
                .structureNodeId(100L)
                .canonicalPath("十、机器人策略设计")
                .scopeMode("HARD")
                .build()
        );

        assertThat(result.isDeterministic()).isTrue();
        assertThat(result.getCurrent().getTitle()).isEqualTo("十、机器人策略设计");
        assertThat(result.getDirectChildren())
            .extracting(SuperAgentDocumentStructureNode::getTitle)
            .containsExactly("10.1 策略层次", "10.2 必配策略项", "10.3 不建议交给机器人直接回答的主题");
    }

    private static SuperAgentDocumentStructureNode node(Long id,
                                                        Long documentId,
                                                        Long parseTaskId,
                                                        Integer nodeNo,
                                                        String title,
                                                        Long parentNodeId,
                                                        Long prevSiblingNodeId,
                                                        Long nextSiblingNodeId,
                                                        String sectionPath,
                                                        String canonicalPath) {
        SuperAgentDocumentStructureNode node = new SuperAgentDocumentStructureNode();
        node.setId(id);
        node.setDocumentId(documentId);
        node.setParseTaskId(parseTaskId);
        node.setNodeNo(nodeNo);
        node.setTitle(title);
        node.setParentNodeId(parentNodeId);
        node.setPrevSiblingNodeId(prevSiblingNodeId);
        node.setNextSiblingNodeId(nextSiblingNodeId);
        node.setSectionPath(sectionPath);
        node.setCanonicalPath(canonicalPath);
        return node;
    }

    private static final class InMemoryStructureNodeService implements DocumentStructureNodeService {

        private final Map<Long, SuperAgentDocumentStructureNode> nodes = new LinkedHashMap<>();

        private InMemoryStructureNodeService(List<SuperAgentDocumentStructureNode> nodes) {
            for (SuperAgentDocumentStructureNode node : nodes) {
                this.nodes.put(node.getId(), node);
            }
        }

        @Override
        public List<SuperAgentDocumentStructureNode> replaceDocumentNodes(Long documentId,
                                                                          Long parseTaskId,
                                                                          List<DocumentStructureNodeCandidate> candidates) {
            return List.of();
        }

        @Override
        public List<SuperAgentDocumentStructureNode> listDocumentNodes(Long documentId, Long parseTaskId) {
            return nodes.values().stream()
                .filter(node -> Objects.equals(node.getDocumentId(), documentId))
                .filter(node -> parseTaskId == null || Objects.equals(node.getParseTaskId(), parseTaskId))
                .sorted(Comparator.comparing(SuperAgentDocumentStructureNode::getNodeNo))
                .toList();
        }

        @Override
        public Map<Long, SuperAgentDocumentStructureNode> nodeMap(Long documentId, Long parseTaskId) {
            Map<Long, SuperAgentDocumentStructureNode> result = new LinkedHashMap<>();
            for (SuperAgentDocumentStructureNode node : listDocumentNodes(documentId, parseTaskId)) {
                result.put(node.getId(), node);
            }
            return result;
        }

        @Override
        public List<SuperAgentDocumentStructureNode> listChildren(Long documentId, Long parseTaskId, Long parentNodeId) {
            return listDocumentNodes(documentId, parseTaskId).stream()
                .filter(node -> Objects.equals(node.getParentNodeId(), parentNodeId))
                .sorted(Comparator.comparing(SuperAgentDocumentStructureNode::getNodeNo))
                .toList();
        }

        @Override
        public SuperAgentDocumentStructureNode findById(Long documentId, Long parseTaskId, Long nodeId) {
            SuperAgentDocumentStructureNode node = nodes.get(nodeId);
            if (node == null || !Objects.equals(node.getDocumentId(), documentId)) {
                return null;
            }
            if (parseTaskId != null && !Objects.equals(node.getParseTaskId(), parseTaskId)) {
                return null;
            }
            return node;
        }

        @Override
        public SuperAgentDocumentStructureNode findPreviousSibling(Long documentId, Long parseTaskId, Long nodeId) {
            SuperAgentDocumentStructureNode node = findById(documentId, parseTaskId, nodeId);
            return node == null ? null : findById(documentId, parseTaskId, node.getPrevSiblingNodeId());
        }

        @Override
        public SuperAgentDocumentStructureNode findNextSibling(Long documentId, Long parseTaskId, Long nodeId) {
            SuperAgentDocumentStructureNode node = findById(documentId, parseTaskId, nodeId);
            return node == null ? null : findById(documentId, parseTaskId, node.getNextSiblingNodeId());
        }

        @Override
        public void deleteByDocumentId(Long documentId) {
        }
    }
}
