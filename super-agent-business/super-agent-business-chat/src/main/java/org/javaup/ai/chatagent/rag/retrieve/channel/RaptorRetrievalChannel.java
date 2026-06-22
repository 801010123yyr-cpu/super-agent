package org.javaup.ai.chatagent.rag.retrieve.channel;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.ai.manage.model.raptor.RaptorSearchResult;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.ai.manage.service.RaptorSearchService;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.javaup.enums.RetrievalChannelEnum;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RaptorRetrievalChannel implements RetrievalChannel {

    private static final String SOURCE_TYPE = "RAPTOR";

    private final RaptorSearchService raptorSearchService;
    private final DocumentKnowledgeService documentKnowledgeService;
    private final ChatRagProperties properties;

    public RaptorRetrievalChannel(RaptorSearchService raptorSearchService,
                                  DocumentKnowledgeService documentKnowledgeService,
                                  ChatRagProperties properties) {
        this.raptorSearchService = raptorSearchService;
        this.documentKnowledgeService = documentKnowledgeService;
        this.properties = properties;
    }

    @Override
    public String channelName() {
        return RetrievalChannelEnum.RAPTOR.getName();
    }

    @Override
    public boolean supports(ConversationExecutionPlan plan) {
        return plan != null
            && properties.isRaptorChannelEnabled()
            && !resolvedDocumentIds(plan).isEmpty();
    }

    @Override
    public RetrievalChannelResult retrieve(String subQuestion, ConversationExecutionPlan plan) {
        List<RaptorSearchResult> results = raptorSearchService.search(
            subQuestion,
            resolvedDocumentIds(plan),
            resolvedTaskIds(plan),
            properties.getRaptorTopK(),
            properties.getRaptorSourceChunkTopK()
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

    private Document toDocument(String subQuestion, RaptorSearchResult result, Map<Long, String> documentNames) {
        String documentName = StrUtil.blankToDefault(documentNames.get(result.getDocumentId()), "文档摘要树");
        String text = renderEvidenceText(subQuestion, result);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, SOURCE_TYPE);
        metadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, channelName());
        metadata.put(DocumentKnowledgeMetadataKeys.SCORE, result.getScore());
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, result.getDocumentId());
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, documentName);
        metadata.put(DocumentKnowledgeMetadataKeys.TASK_ID, result.getTaskId());
        metadata.put(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, result.getParentBlockId());
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_ID, result.getChunkId());
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_NO, result.getChunkNo());
        metadata.put(DocumentKnowledgeMetadataKeys.SECTION_PATH, StrUtil.blankToDefault(result.getSectionPath(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.PAGE_NO, result.getPageNo());
        metadata.put(DocumentKnowledgeMetadataKeys.PAGE_RANGE, StrUtil.blankToDefault(result.getPageRange(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.BBOX_JSON, StrUtil.blankToDefault(result.getBboxJson(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_BLOCK_IDS, StrUtil.blankToDefault(result.getSourceBlockIds(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_TYPE, "RAPTOR_SOURCE_CHUNK");
        metadata.put(DocumentKnowledgeMetadataKeys.TITLE, StrUtil.blankToDefault(result.getTitle(), result.getRaptorNodeTitle()));
        metadata.put(DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET, StrUtil.blankToDefault(result.getChunkText(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.RAPTOR_NODE_ID, result.getRaptorNodeId());
        metadata.put(DocumentKnowledgeMetadataKeys.RAPTOR_NODE_TITLE, StrUtil.blankToDefault(result.getRaptorNodeTitle(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.RAPTOR_NODE_LEVEL, result.getRaptorNodeLevel());
        metadata.put(DocumentKnowledgeMetadataKeys.RAPTOR_SUMMARY, StrUtil.blankToDefault(result.getRaptorSummary(), ""));

        return Document.builder()
            .id("raptor-" + result.getRaptorNodeId() + "-" + result.getChunkId())
            .text(text)
            .metadata(metadata)
            .score(result.getScore())
            .build();
    }

    private String renderEvidenceText(String subQuestion, RaptorSearchResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append("[RAPTOR 层级摘要检索]\n");
        builder.append("用户问题：").append(StrUtil.blankToDefault(subQuestion, "")).append('\n');
        builder.append("命中摘要：").append(StrUtil.blankToDefault(result.getRaptorNodeTitle(), "层级摘要")).append('\n');
        builder.append("摘要层级：").append(result.getRaptorNodeLevel() == null ? "" : result.getRaptorNodeLevel()).append('\n');
        builder.append("摘要内容：").append(StrUtil.blankToDefault(result.getRaptorSummary(), "")).append('\n');
        if (StrUtil.isNotBlank(result.getSectionPath())) {
            builder.append("原文章节：").append(result.getSectionPath()).append('\n');
        }
        if (result.getPageNo() != null) {
            builder.append("原文页码：").append(result.getPageNo()).append('\n');
        }
        builder.append("下钻原文：").append(StrUtil.blankToDefault(result.getChunkText(), "")).append('\n');
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
}
