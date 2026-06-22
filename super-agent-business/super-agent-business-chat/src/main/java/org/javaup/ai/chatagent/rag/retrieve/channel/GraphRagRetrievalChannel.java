package org.javaup.ai.chatagent.rag.retrieve.channel;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.ai.manage.model.graph.GraphRagSearchResult;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.ai.manage.service.GraphRagSearchService;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.javaup.enums.RetrievalChannelEnum;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GraphRagRetrievalChannel implements RetrievalChannel {

    private static final String SOURCE_TYPE = "GRAPH_RAG";

    private final GraphRagSearchService graphRagSearchService;
    private final DocumentKnowledgeService documentKnowledgeService;
    private final ChatRagProperties properties;

    public GraphRagRetrievalChannel(GraphRagSearchService graphRagSearchService,
                                    DocumentKnowledgeService documentKnowledgeService,
                                    ChatRagProperties properties) {
        this.graphRagSearchService = graphRagSearchService;
        this.documentKnowledgeService = documentKnowledgeService;
        this.properties = properties;
    }

    @Override
    public String channelName() {
        return RetrievalChannelEnum.GRAPH_RAG.getName();
    }

    @Override
    public boolean supports(ConversationExecutionPlan plan) {
        return plan != null
            && properties.isGraphRagChannelEnabled()
            && !resolvedDocumentIds(plan).isEmpty();
    }

    @Override
    public RetrievalChannelResult retrieve(String subQuestion, ConversationExecutionPlan plan) {
        List<GraphRagSearchResult> results = graphRagSearchService.search(
            subQuestion,
            resolvedDocumentIds(plan),
            resolvedTaskIds(plan),
            properties.getGraphRagTopK(),
            properties.getGraphRagMaxHops()
        );
        if (results.isEmpty()) {
            return new RetrievalChannelResult(channelName(), List.of());
        }

        Map<Long, String> documentNames = resolveDocumentNames();
        List<Document> documents = results.stream()
            .map(result -> toDocument(subQuestion, result, documentNames))
            .toList();
        return new RetrievalChannelResult(channelName(), documents);
    }

    private Document toDocument(String subQuestion, GraphRagSearchResult result, Map<Long, String> documentNames) {
        String documentName = StrUtil.blankToDefault(documentNames.get(result.getDocumentId()), "文档图谱");
        String text = renderEvidenceText(subQuestion, result);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, SOURCE_TYPE);
        metadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, channelName());
        metadata.put(DocumentKnowledgeMetadataKeys.SCORE, result.getScore());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.DOCUMENT_ID, result.getDocumentId());
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, documentName);
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.TASK_ID, result.getTaskId());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, result.getParentBlockId());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.CHUNK_ID, result.getChunkId());
        metadata.put(DocumentKnowledgeMetadataKeys.SECTION_PATH, StrUtil.blankToDefault(result.getSectionPath(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.PAGE_NO, result.getPageNo());
        metadata.put(DocumentKnowledgeMetadataKeys.PAGE_RANGE, StrUtil.blankToDefault(result.getPageRange(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.BBOX_JSON, StrUtil.blankToDefault(result.getBboxJson(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_TYPE, "GRAPH_RAG");
        metadata.put(DocumentKnowledgeMetadataKeys.TITLE, StrUtil.blankToDefault(result.getGraphPath(), "GraphRAG 图谱证据"));
        metadata.put(DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET, StrUtil.blankToDefault(result.getQuoteText(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_ENTITY_ID, result.getEntityId());
        metadata.put(DocumentKnowledgeMetadataKeys.KG_ENTITY_NAME, StrUtil.blankToDefault(result.getEntityName(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_RELATED_ENTITY_ID, result.getRelatedEntityId());
        metadata.put(DocumentKnowledgeMetadataKeys.KG_RELATED_ENTITY_NAME, StrUtil.blankToDefault(result.getRelatedEntityName(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_RELATION_ID, result.getRelationId());
        metadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_TYPE, StrUtil.blankToDefault(result.getRelationType(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID, result.getEvidenceId());
        metadata.put(DocumentKnowledgeMetadataKeys.KG_GRAPH_PATH, StrUtil.blankToDefault(result.getGraphPath(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_HOP_COUNT, result.getHopCount());

        return Document.builder()
            .id(documentId(result))
            .text(text)
            .metadata(metadata)
            .score(result.getScore())
            .build();
    }

    private String renderEvidenceText(String subQuestion, GraphRagSearchResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append("[GraphRAG 图谱检索]\n");
        builder.append("用户问题：").append(StrUtil.blankToDefault(subQuestion, "")).append('\n');
        builder.append("图谱路径：").append(StrUtil.blankToDefault(result.getGraphPath(), result.getEntityName())).append('\n');
        if (StrUtil.isNotBlank(result.getSectionPath())) {
            builder.append("章节：").append(result.getSectionPath()).append('\n');
        }
        if (result.getPageNo() != null) {
            builder.append("页码：").append(result.getPageNo()).append('\n');
        }
        builder.append("原文证据：").append(StrUtil.blankToDefault(result.getQuoteText(), "")).append('\n');
        return builder.toString().trim();
    }

    private List<Long> resolvedDocumentIds(ConversationExecutionPlan plan) {
        if (plan.getRetrievalDocumentIds() != null && !plan.getRetrievalDocumentIds().isEmpty()) {
            return plan.getRetrievalDocumentIds();
        }
        return plan.getSelectedDocumentId() == null ? List.of() : List.of(plan.getSelectedDocumentId());
    }

    private List<Long> resolvedTaskIds(ConversationExecutionPlan plan) {
        if (plan.getRetrievalTaskIds() != null && !plan.getRetrievalTaskIds().isEmpty()) {
            return plan.getRetrievalTaskIds();
        }
        return plan.getSelectedTaskId() == null ? List.of() : List.of(plan.getSelectedTaskId());
    }

    private Map<Long, String> resolveDocumentNames() {
        Map<Long, String> documentNames = new LinkedHashMap<>();
        for (KnowledgeDocumentDescriptor descriptor : documentKnowledgeService.listRetrievableDocuments()) {
            if (descriptor.getDocumentId() != null) {
                documentNames.put(descriptor.getDocumentId(), descriptor.getDocumentName());
            }
        }
        return documentNames;
    }

    private void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private String documentId(GraphRagSearchResult result) {
        if (result.getEvidenceId() != null) {
            return "graphrag-" + result.getEvidenceId();
        }
        if (result.getRelationId() != null) {
            return "graphrag-relation-" + result.getRelationId();
        }
        if (result.getEntityId() != null) {
            return "graphrag-entity-" + result.getEntityId();
        }
        return "graphrag-" + Integer.toHexString(System.identityHashCode(result));
    }
}
