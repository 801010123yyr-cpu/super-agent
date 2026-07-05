package org.javaup.ai.chatagent.rag.retrieve.channel;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.RagRuntimeOptions;
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
    private static final String SOURCE_STATUS_SOURCE_CHUNK = "SOURCE_CHUNK";
    private static final String SOURCE_STATUS_SOURCE_PARENT_BLOCK = "SOURCE_PARENT_BLOCK";
    private static final String SOURCE_STATUS_SUMMARY_ONLY = "SUMMARY_ONLY";

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
            && RagRuntimeOptions.resolve(plan, properties).isRaptorChannelEnabled()
            && !resolvedDocumentIds(plan).isEmpty();
    }

    @Override
    public RetrievalChannelResult retrieve(String subQuestion, ConversationExecutionPlan plan) {
        RagRuntimeOptions options = RagRuntimeOptions.resolve(plan, properties);
        List<RaptorSearchResult> results = raptorSearchService.search(
            subQuestion,
            resolvedDocumentIds(plan),
            resolvedTaskIds(plan),
            options.getRaptorTopK(),
            options.getRaptorSourceChunkTopK()
        );
        if (results.isEmpty()) {
            return new RetrievalChannelResult(channelName(), List.of());
        }

        Map<Long, KnowledgeDocumentDescriptor> documentDescriptors = resolveDocumentDescriptors(plan);
        List<Document> documents = results.stream()
            .map(result -> toDocument(subQuestion, result, documentDescriptors))
            .toList();
        return new RetrievalChannelResult(channelName(), documents);
    }

    private Document toDocument(String subQuestion,
                                RaptorSearchResult result,
                                Map<Long, KnowledgeDocumentDescriptor> documentDescriptors) {
        KnowledgeDocumentDescriptor descriptor = documentDescriptors.get(result.getDocumentId());
        String documentName = StrUtil.blankToDefault(descriptor == null ? null : descriptor.getDocumentName(), "文档摘要树");
        String text = renderEvidenceText(subQuestion, result);
        String sourceStatus = resolveSourceStatus(result);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, SOURCE_TYPE);
        metadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, channelName());
        metadata.put(DocumentKnowledgeMetadataKeys.SCORE, result.getScore());
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, result.getDocumentId());
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, documentName);
        if (descriptor != null) {
            putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_ID, descriptor.getKnowledgeBaseId());
            metadata.put(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_NAME, StrUtil.blankToDefault(descriptor.getKnowledgeBaseName(), ""));
        }
        metadata.put(DocumentKnowledgeMetadataKeys.TASK_ID, result.getTaskId());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, result.getParentBlockId());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.CHUNK_ID, result.getChunkId());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.CHUNK_NO, result.getChunkNo());
        metadata.put(DocumentKnowledgeMetadataKeys.SECTION_PATH, StrUtil.blankToDefault(result.getSectionPath(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.PAGE_NO, result.getPageNo());
        metadata.put(DocumentKnowledgeMetadataKeys.PAGE_RANGE, StrUtil.blankToDefault(result.getPageRange(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.BBOX_JSON, StrUtil.blankToDefault(result.getBboxJson(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_BLOCK_IDS, StrUtil.blankToDefault(result.getSourceBlockIds(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_TYPE,
            SOURCE_STATUS_SUMMARY_ONLY.equals(sourceStatus) ? "RAPTOR_SUMMARY" : "RAPTOR_SOURCE_CHUNK");
        metadata.put(DocumentKnowledgeMetadataKeys.TITLE, StrUtil.blankToDefault(result.getTitle(), result.getRaptorNodeTitle()));
        metadata.put(DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET,
            StrUtil.blankToDefault(result.getChunkText(), StrUtil.blankToDefault(result.getRaptorSummary(), "")));
        metadata.put(DocumentKnowledgeMetadataKeys.RAPTOR_NODE_ID, result.getRaptorNodeId());
        metadata.put(DocumentKnowledgeMetadataKeys.RAPTOR_NODE_TITLE, StrUtil.blankToDefault(result.getRaptorNodeTitle(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.RAPTOR_NODE_LEVEL, result.getRaptorNodeLevel());
        metadata.put(DocumentKnowledgeMetadataKeys.RAPTOR_SUMMARY, StrUtil.blankToDefault(result.getRaptorSummary(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.RAPTOR_SOURCE_STATUS, sourceStatus);

        return Document.builder()
            .id(raptorDocumentId(result))
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
        String sourceStatus = resolveSourceStatus(result);
        if (SOURCE_STATUS_SUMMARY_ONLY.equals(sourceStatus)) {
            builder.append("下钻状态：未找到可引用 source chunk 或 ParentBlock，本证据仅作为摘要背景。\n");
        }
        else if (SOURCE_STATUS_SOURCE_PARENT_BLOCK.equals(sourceStatus) && StrUtil.isBlank(result.getChunkText())) {
            builder.append("下钻状态：已定位到 ParentBlock，但当前结果未携带 chunk 原文。\n");
        }
        if (StrUtil.isNotBlank(result.getChunkText())) {
            builder.append("下钻原文：").append(result.getChunkText()).append('\n');
        }
        return builder.toString().trim();
    }

    private String resolveSourceStatus(RaptorSearchResult result) {
        String sourceStatus = StrUtil.blankToDefault(result.getSourceStatus(), "");
        if (StrUtil.isNotBlank(sourceStatus)) {
            return sourceStatus;
        }
        if (result.getChunkId() != null) {
            return SOURCE_STATUS_SOURCE_CHUNK;
        }
        if (result.getParentBlockId() != null) {
            return SOURCE_STATUS_SOURCE_PARENT_BLOCK;
        }
        return SOURCE_STATUS_SUMMARY_ONLY;
    }

    private String raptorDocumentId(RaptorSearchResult result) {
        if (result.getChunkId() != null) {
            return "raptor-" + result.getRaptorNodeId() + "-" + result.getChunkId();
        }
        if (result.getParentBlockId() != null) {
            return "raptor-" + result.getRaptorNodeId() + "-parent-" + result.getParentBlockId();
        }
        return "raptor-" + result.getRaptorNodeId() + "-summary";
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

    private Map<Long, KnowledgeDocumentDescriptor> resolveDocumentDescriptors(ConversationExecutionPlan plan) {
        Map<Long, KnowledgeDocumentDescriptor> documentDescriptors = new LinkedHashMap<>();
        List<Long> documentIds = resolvedDocumentIds(plan);
        List<KnowledgeDocumentDescriptor> descriptors = plan == null
            || plan.getSelectedKnowledgeBaseIds() == null
            || plan.getSelectedKnowledgeBaseIds().isEmpty()
            ? documentKnowledgeService.listRetrievableDocuments()
            : documentKnowledgeService.listRetrievableDocumentsByKnowledgeBaseIds(plan.getSelectedKnowledgeBaseIds());
        for (KnowledgeDocumentDescriptor descriptor : descriptors) {
            if (descriptor.getDocumentId() != null) {
                documentDescriptors.put(descriptor.getDocumentId(), descriptor);
            }
        }
        documentDescriptors.keySet().retainAll(documentIds);
        return documentDescriptors;
    }

    private void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }
}
