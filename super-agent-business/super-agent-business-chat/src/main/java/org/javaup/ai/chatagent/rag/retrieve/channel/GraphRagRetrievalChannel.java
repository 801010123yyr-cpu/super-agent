package org.javaup.ai.chatagent.rag.retrieve.channel;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.service.DocumentRetrieveRequestFactory;
import org.javaup.ai.manage.model.DocumentRetrieveRequest;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.ai.manage.model.graph.GraphRagSearchResult;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.ai.manage.service.GraphRagSearchService;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.javaup.enums.RetrievalChannelEnum;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GraphRagRetrievalChannel implements RetrievalChannel {

    private static final String SOURCE_TYPE = "GRAPH_RAG";

    private static final Pattern GRAPH_PATH_RELATION_PATTERN = Pattern.compile("(?:--|\\-\\[)([A-Z][A-Z0-9_]{1,63})(?:-->|\\]->)");

    private final GraphRagSearchService graphRagSearchService;
    private final DocumentKnowledgeService documentKnowledgeService;
    private final ChatRagProperties properties;
    private final DocumentRetrieveRequestFactory documentRetrieveRequestFactory;

    public GraphRagRetrievalChannel(GraphRagSearchService graphRagSearchService,
                                    DocumentKnowledgeService documentKnowledgeService,
                                    ChatRagProperties properties,
                                    DocumentRetrieveRequestFactory documentRetrieveRequestFactory) {
        this.graphRagSearchService = graphRagSearchService;
        this.documentKnowledgeService = documentKnowledgeService;
        this.properties = properties;
        this.documentRetrieveRequestFactory = documentRetrieveRequestFactory;
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
        DocumentRetrieveRequest request = documentRetrieveRequestFactory.build(subQuestion, plan, properties.getGraphRagTopK());
        List<GraphRagSearchResult> results = graphRagSearchService.search(
            StrUtil.blankToDefault(request.getRetrievalQuery(), subQuestion),
            request.resolvedDocumentIds(),
            request.resolvedTaskIds(),
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
        metadata.put(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_KEY, StrUtil.blankToDefault(result.getCanonicalEntityKey(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_NAME, StrUtil.blankToDefault(result.getCanonicalEntityName(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_COUNT, result.getCanonicalEntityCount());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_CANONICAL_DOCUMENT_COUNT, result.getCanonicalDocumentCount());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_RELATED_ENTITY_ID, result.getRelatedEntityId());
        metadata.put(DocumentKnowledgeMetadataKeys.KG_RELATED_ENTITY_NAME, StrUtil.blankToDefault(result.getRelatedEntityName(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_RELATION_ID, result.getRelationId());
        metadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_TYPE, StrUtil.blankToDefault(result.getRelationType(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY, StrUtil.blankToDefault(result.getRelationGroupKey(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_RELATION_COUNT, result.getRelationGroupRelationCount());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_EVIDENCE_COUNT, result.getRelationGroupEvidenceCount());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_DOCUMENT_COUNT, result.getRelationGroupDocumentCount());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID, result.getEvidenceId());
        metadata.put(DocumentKnowledgeMetadataKeys.KG_GRAPH_PATH, StrUtil.blankToDefault(result.getGraphPath(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_HOP_COUNT, result.getHopCount());
        metadata.put(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_SOURCE, StrUtil.blankToDefault(result.getQueryPlanSource(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_ANSWER_TYPES, StrUtil.blankToDefault(result.getQueryPlanAnswerTypes(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_ENTITIES, StrUtil.blankToDefault(result.getQueryPlanEntities(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_NHOP_SEED_ENTITY_ID, result.getNHopSeedEntityId());
        metadata.put(DocumentKnowledgeMetadataKeys.KG_NHOP_SEED_ENTITY_NAME, StrUtil.blankToDefault(result.getNHopSeedEntityName(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.KG_NHOP_PATH, StrUtil.blankToDefault(result.getNHopPath(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_COMMUNITY_ID, result.getCommunityId());
        metadata.put(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_TITLE, StrUtil.blankToDefault(result.getCommunityTitle(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_SUMMARY, StrUtil.blankToDefault(result.getCommunitySummary(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY, StrUtil.blankToDefault(result.getCrossDocumentCommunityKey(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_ENTITY_COUNT, result.getCrossDocumentCommunityEntityCount());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_RELATION_GROUP_COUNT, result.getCrossDocumentCommunityRelationGroupCount());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_EVIDENCE_COUNT, result.getCrossDocumentCommunityEvidenceCount());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_DOCUMENT_COUNT, result.getCrossDocumentCommunityDocumentCount());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_RANK_BOOST, result.getRankBoost());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_QUALITY_SCORE, result.getKgQualityScore());
        metadata.put(DocumentKnowledgeMetadataKeys.KG_QUALITY_REASONS, StrUtil.blankToDefault(result.getKgQualityReasons(), ""));
        metadata.put(DocumentKnowledgeMetadataKeys.KG_NOISE_REASONS, StrUtil.blankToDefault(result.getKgNoiseReasons(), ""));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_PAGERANK, result.getKgPagerank());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_RANK_POSITION, result.getKgRankPosition());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KG_DEGREE, result.getKgDegree());

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
        Set<String> relationTypes = resolveRelationTypes(result);
        if (!relationTypes.isEmpty()) {
            builder.append("关系类型：").append(String.join(", ", relationTypes)).append('\n');
            for (String relationType : relationTypes) {
                builder.append("关系语义边界[").append(relationType).append("]：")
                    .append(relationSemanticBoundary(relationType)).append('\n');
            }
        }
        if (StrUtil.isNotBlank(result.getCommunitySummary())) {
            builder.append("社区报告：").append(result.getCommunitySummary()).append('\n');
        }
        if (StrUtil.isNotBlank(result.getNHopPath())) {
            builder.append("n-hop路径：").append(result.getNHopPath()).append('\n');
        }
        if (StrUtil.isNotBlank(result.getSectionPath())) {
            builder.append("章节：").append(result.getSectionPath()).append('\n');
        }
        if (result.getPageNo() != null) {
            builder.append("页码：").append(result.getPageNo()).append('\n');
        }
        if (result.getRelationGroupDocumentCount() != null && result.getRelationGroupDocumentCount() > 1) {
            builder.append("跨文档关系组：")
                .append(result.getRelationGroupDocumentCount()).append(" 份文档 / ")
                .append(result.getRelationGroupEvidenceCount() == null ? "-" : result.getRelationGroupEvidenceCount())
                .append(" 条证据支撑\n");
        }
        if (result.getCrossDocumentCommunityDocumentCount() != null && result.getCrossDocumentCommunityDocumentCount() > 1) {
            builder.append("跨文档社区：")
                .append(result.getCrossDocumentCommunityDocumentCount()).append(" 份文档 / ")
                .append(result.getCrossDocumentCommunityRelationGroupCount() == null ? "-" : result.getCrossDocumentCommunityRelationGroupCount())
                .append(" 个关系组 / ")
                .append(result.getCrossDocumentCommunityEvidenceCount() == null ? "-" : result.getCrossDocumentCommunityEvidenceCount())
                .append(" 条证据支撑\n");
        }
        builder.append("原文证据：").append(StrUtil.blankToDefault(result.getQuoteText(), "")).append('\n');
        return builder.toString().trim();
    }

    private Set<String> resolveRelationTypes(GraphRagSearchResult result) {
        LinkedHashSet<String> relationTypes = new LinkedHashSet<>();
        addRelationType(relationTypes, result.getRelationType());
        addRelationTypesFromPath(relationTypes, result.getGraphPath());
        addRelationTypesFromPath(relationTypes, result.getNHopPath());
        return relationTypes;
    }

    private void addRelationTypesFromPath(Set<String> relationTypes, String path) {
        if (StrUtil.isBlank(path)) {
            return;
        }
        Matcher matcher = GRAPH_PATH_RELATION_PATTERN.matcher(path);
        while (matcher.find()) {
            addRelationType(relationTypes, matcher.group(1));
        }
    }

    private void addRelationType(Set<String> relationTypes, String relationType) {
        String normalized = StrUtil.blankToDefault(relationType, "")
            .trim()
            .toUpperCase();
        if (StrUtil.isNotBlank(normalized)) {
            relationTypes.add(normalized);
        }
    }

    private String relationSemanticBoundary(String relationType) {
        String normalized = StrUtil.blankToDefault(relationType, "")
            .trim()
            .toUpperCase();
        return switch (normalized) {
            case "RECORDS" -> "表示记录、留痕或审计覆盖，只能证明源实体记录了目标事项；不能单独证明目标事项的执行者、审批者、负责人或决策主体。";
            case "APPROVES" -> "表示审批、批准或授权关系，可用于支持审批主体判断，但仍必须由原文证据支撑。";
            case "RESPONSIBLE_FOR" -> "表示负责、归口或责任边界关系，可用于支持责任主体判断，但仍必须由原文证据支撑。";
            case "EXECUTES" -> "表示执行或操作关系，可用于支持执行主体判断，但不能自动推出审批或最终负责主体。";
            case "REVOKES" -> "表示回收、撤销或取消关系，可用于支持回收主体判断，但不能自动推出审批主体。";
            case "STORES" -> "表示存放、保存或落库关系，可用于支持数据存储位置判断。";
            case "TRIGGERS" -> "表示触发或引发关系，可用于支持事件因果或流程触发判断。";
            case "DEPENDS_ON" -> "表示依赖关系，可用于支持系统、流程或能力依赖判断。";
            case "ASSOCIATED_WITH", "RELATED_TO" -> "表示弱关联，只能作为线索，不能单独支持职责、审批、执行或因果结论。";
            default -> "表示图谱中的受控关系类型；结论仍必须以原文证据为准，不能超出关系类型本身的语义。";
        };
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
