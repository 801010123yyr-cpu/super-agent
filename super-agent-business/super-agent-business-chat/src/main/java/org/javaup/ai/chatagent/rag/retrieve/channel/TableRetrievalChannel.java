package org.javaup.ai.chatagent.rag.retrieve.channel;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.DocumentTableQueryPlan;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.service.DocumentTableQueryPlanner;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.ai.manage.model.table.DocumentTableDescriptor;
import org.javaup.ai.manage.model.table.DocumentTableQuery;
import org.javaup.ai.manage.model.table.DocumentTableQueryResult;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.ai.manage.service.DocumentTableStructureService;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.javaup.enums.RetrievalChannelEnum;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class TableRetrievalChannel implements RetrievalChannel {

    private static final String SOURCE_TYPE = "DOCUMENT_TABLE";
    private static final double TABLE_QUERY_SCORE = 1D;

    private final DocumentTableStructureService tableStructureService;
    private final DocumentTableQueryPlanner tableQueryPlanner;
    private final DocumentKnowledgeService documentKnowledgeService;
    private final ChatRagProperties properties;

    public TableRetrievalChannel(DocumentTableStructureService tableStructureService,
                                 DocumentTableQueryPlanner tableQueryPlanner,
                                 DocumentKnowledgeService documentKnowledgeService,
                                 ChatRagProperties properties) {
        this.tableStructureService = tableStructureService;
        this.tableQueryPlanner = tableQueryPlanner;
        this.documentKnowledgeService = documentKnowledgeService;
        this.properties = properties;
    }

    @Override
    public String channelName() {
        return RetrievalChannelEnum.TABLE.getName();
    }

    @Override
    public boolean supports(ConversationExecutionPlan plan) {
        return plan != null
            && properties.isTableChannelEnabled()
            && !resolvedDocumentIds(plan).isEmpty();
    }

    @Override
    public RetrievalChannelResult retrieve(String subQuestion, ConversationExecutionPlan plan) {
        List<DocumentTableDescriptor> tables = tableStructureService.listTables(
            resolvedDocumentIds(plan),
            List.of()
        );
        Optional<DocumentTableQueryPlan> queryPlan = tableQueryPlanner.plan(subQuestion, tables);
        if (queryPlan.isEmpty()) {
            return new RetrievalChannelResult(channelName(), List.of());
        }

        DocumentTableQueryPlan planned = queryPlan.get();
        DocumentTableQueryResult result = tableStructureService.query(planned.getQuery());
        Document document = buildEvidenceDocument(subQuestion, planned, result, resolveDocumentNames());
        return new RetrievalChannelResult(channelName(), List.of(document));
    }

    private Document buildEvidenceDocument(String subQuestion,
                                           DocumentTableQueryPlan queryPlan,
                                           DocumentTableQueryResult result,
                                           Map<Long, String> documentNames) {
        String documentName = StrUtil.blankToDefault(documentNames.get(result.getDocumentId()), "文档表格");
        String text = renderEvidenceText(subQuestion, queryPlan, result);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, SOURCE_TYPE);
        metadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, channelName());
        metadata.put(DocumentKnowledgeMetadataKeys.SCORE, TABLE_QUERY_SCORE);
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, result.getDocumentId());
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, documentName);
        metadata.put(DocumentKnowledgeMetadataKeys.TASK_ID, result.getTaskId());
        metadata.put(DocumentKnowledgeMetadataKeys.SECTION_PATH, StrUtil.blankToDefault(result.getSectionPath(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.PAGE_NO, result.getPageNo());
        metadata.put(DocumentKnowledgeMetadataKeys.PAGE_RANGE, StrUtil.blankToDefault(result.getPageRange(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.BBOX_JSON, StrUtil.blankToDefault(result.getBboxJson(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_BLOCK_IDS, result.getBlockId() == null ? "" : "[" + result.getBlockId() + "]");
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_TYPE, "TABLE_QUERY");
        metadata.put(DocumentKnowledgeMetadataKeys.TITLE, StrUtil.blankToDefault(result.getTableTitle(), "结构化表格"));
        metadata.put(DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET, text);
        metadata.put(DocumentKnowledgeMetadataKeys.TABLE_ID, result.getTableId());
        metadata.put(DocumentKnowledgeMetadataKeys.TABLE_NO, result.getTableNo());
        metadata.put(DocumentKnowledgeMetadataKeys.TABLE_TITLE, StrUtil.blankToDefault(result.getTableTitle(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.TABLE_OPERATION, StrUtil.blankToDefault(result.getOperation(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.TABLE_METRIC_COLUMN, StrUtil.blankToDefault(queryPlan.getQuery().getMetricColumn(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.TABLE_GROUP_BY_COLUMN, StrUtil.blankToDefault(queryPlan.getQuery().getGroupByColumn(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.TABLE_MATCHED_ROW_COUNT, result.getMatchedRowCount());

        return Document.builder()
            .id("table-" + result.getTableId() + "-" + Math.abs(StrUtil.blankToDefault(subQuestion, "").hashCode()))
            .text(text)
            .metadata(metadata)
            .score(TABLE_QUERY_SCORE)
            .build();
    }

    private String renderEvidenceText(String subQuestion,
                                      DocumentTableQueryPlan queryPlan,
                                      DocumentTableQueryResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append("[结构化表格查询]\n");
        builder.append("用户问题：").append(StrUtil.blankToDefault(subQuestion, "")).append('\n');
        builder.append("匹配原因：").append(StrUtil.blankToDefault(queryPlan.getReason(), "")).append('\n');
        builder.append("表格：").append(StrUtil.blankToDefault(result.getTableTitle(), "表格")).append('\n');
        if (StrUtil.isNotBlank(result.getSectionPath())) {
            builder.append("章节：").append(result.getSectionPath()).append('\n');
        }
        builder.append("查询计划：").append(renderQuery(queryPlan.getQuery())).append("\n\n");
        builder.append(StrUtil.blankToDefault(result.getEvidenceText(), ""));
        return builder.toString().trim();
    }

    private String renderQuery(DocumentTableQuery query) {
        StringBuilder builder = new StringBuilder();
        builder.append(query.getOperation().name());
        if (StrUtil.isNotBlank(query.getMetricColumn())) {
            builder.append(" metric=").append(query.getMetricColumn());
        }
        if (StrUtil.isNotBlank(query.getGroupByColumn())) {
            builder.append(" groupBy=").append(query.getGroupByColumn());
        }
        if (query.getFilters() != null && !query.getFilters().isEmpty()) {
            builder.append(" filters=");
            builder.append(query.getFilters().stream()
                .map(filter -> filter.getColumn() + " " + filter.getOperator().name() + " " + filter.getValue())
                .reduce((left, right) -> left + "; " + right)
                .orElse(""));
        }
        return builder.toString();
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
