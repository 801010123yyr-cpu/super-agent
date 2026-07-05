package org.javaup.ai.manage.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentParentBlock;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentParentBlockMapper;
import org.javaup.ai.manage.model.DocumentRetrieveFilters;
import org.javaup.ai.manage.model.DocumentRetrieveRequest;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.ai.manage.service.keyword.DocumentKeywordSearchGateway;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.javaup.ai.manage.support.DocumentPgVectorConstants;
import org.javaup.ai.manage.support.GraphRagTypedChunkMetadataSupport;
import org.javaup.enums.BusinessStatus;
import org.javaup.enums.DocumentIndexStatusEnum;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务实现层
 * @author: 阿星不是程序员
 **/

@Slf4j
@AllArgsConstructor
@Service
public class DocumentKnowledgeServiceImpl implements DocumentKnowledgeService {

    private static final String VECTOR_RETRIEVE_SQL_TEMPLATE = """
        SELECT
            id,
            document_id,
            task_id,
            parent_block_id,
            chunk_no,
            section_path,
            structure_node_id,
            structure_node_type,
            canonical_path,
            item_index,
            chunk_text,
            content_with_weight,
            chunk_type,
            title,
            keywords,
            questions,
            page_no,
            page_range,
            bbox_json,
            source_block_ids,
            1 - (embedding <=> CAST(? AS vector)) AS similarity_score
        FROM %s
        WHERE status = 1
          AND document_id IN (%s)
          AND task_id IN (%s)
        """;

    private final SuperAgentDocumentMapper documentMapper;
    
    private final SuperAgentDocumentParentBlockMapper parentBlockMapper;
    
    @Qualifier("documentManagePgVectorJdbcTemplate")
    private final JdbcTemplate pgVectorJdbcTemplate;
    
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    
    private final ObjectProvider<DocumentKeywordSearchGateway> keywordSearchGatewayProvider;

    private final GraphRagTypedChunkMetadataSupport graphRagTypedChunkMetadataSupport;
    
    @Override
    public List<KnowledgeDocumentDescriptor> listRetrievableDocuments() {

        return toDescriptors(documentMapper.selectList(new LambdaQueryWrapper<SuperAgentDocument>()
            .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode())
            .eq(SuperAgentDocument::getIndexStatus, DocumentIndexStatusEnum.BUILD_SUCCESS.getCode())
            .isNotNull(SuperAgentDocument::getLastIndexTaskId)
            .orderByDesc(SuperAgentDocument::getEditTime)
            .orderByDesc(SuperAgentDocument::getId)));
    }

    @Override
    public List<KnowledgeDocumentDescriptor> listRetrievableDocumentsByKnowledgeBaseIds(Collection<Long> knowledgeBaseIds) {
        List<Long> ids = knowledgeBaseIds == null
            ? List.of()
            : knowledgeBaseIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return toDescriptors(documentMapper.selectList(new LambdaQueryWrapper<SuperAgentDocument>()
            .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode())
            .eq(SuperAgentDocument::getIndexStatus, DocumentIndexStatusEnum.BUILD_SUCCESS.getCode())
            .isNotNull(SuperAgentDocument::getLastIndexTaskId)
            .in(SuperAgentDocument::getKnowledgeBaseId, ids)
            .orderByDesc(SuperAgentDocument::getEditTime)
            .orderByDesc(SuperAgentDocument::getId)));
    }

    private List<KnowledgeDocumentDescriptor> toDescriptors(List<SuperAgentDocument> documents) {
        if (CollUtil.isEmpty(documents)) {
            return List.of();
        }

        return documents.stream()
            .map(document -> new KnowledgeDocumentDescriptor(
                document.getId(),
                document.getDocumentName(),
                document.getLastIndexTaskId(),
                document.getKnowledgeBaseId(),
                document.getKnowledgeBaseName()
            ))
            .toList();
    }

    @Override
    public List<Document> vectorSearch(DocumentRetrieveRequest request) {
        if (!isSearchableRequest(request)) {
            return List.of();
        }

        EmbeddingModel embeddingModel = requireEmbeddingModel();

        String questionVector = toVectorLiteral(embeddingModel.embed(request.getRetrievalQuery().trim()));
        List<Long> documentIds = request.resolvedDocumentIds();
        List<Long> taskIds = request.resolvedTaskIds();

        Map<Long, KnowledgeDocumentDescriptor> descriptorMap = listDescriptorMap(documentIds);

        ResolvedMetadataScope resolvedScope = resolveMetadataScope(request);
        if (resolvedScope.documentIds().isEmpty() || resolvedScope.taskIds().isEmpty()) {
            return List.of();
        }

        StringBuilder sqlBuilder = new StringBuilder(VECTOR_RETRIEVE_SQL_TEMPLATE.formatted(
            DocumentPgVectorConstants.EMBEDDING_TABLE_NAME,
            buildPlaceholders(resolvedScope.documentIds().size()),
            buildPlaceholders(resolvedScope.taskIds().size())
        ));
        appendSectionFilters(sqlBuilder, resolvedScope.filters());

        sqlBuilder.append("""

            ORDER BY embedding <=> CAST(? AS vector)
            LIMIT ?
            """);

        List<Object> params = new ArrayList<>();

        params.add(questionVector);
        params.addAll(resolvedScope.documentIds());
        params.addAll(resolvedScope.taskIds());
        appendSectionFilterParams(params, resolvedScope.filters());
        params.add(questionVector);
        params.add(resolveTopK(request.getTopK()));

        return pgVectorJdbcTemplate.query(sqlBuilder.toString(), params.toArray(), (resultSet, rowNum) -> {
            long chunkId = resultSet.getLong("id");
            long documentId = resultSet.getLong("document_id");
            double score = resultSet.getDouble("similarity_score");
            KnowledgeDocumentDescriptor descriptor = descriptorMap.get(documentId);
            return buildRetrievedDocument(
                chunkId,
                resultSet.getString("chunk_text"),
                resultSet.getString("content_with_weight"),
                resultSet.getString("chunk_type"),
                resultSet.getString("title"),
                resultSet.getString("keywords"),
                resultSet.getString("questions"),
                resultSet.getLong("task_id"),
                resultSet.getLong("parent_block_id"),
                resultSet.getInt("chunk_no"),
                resultSet.getString("section_path"),
                getNullableLong(resultSet, "structure_node_id"),
                getNullableInteger(resultSet, "structure_node_type"),
                resultSet.getString("canonical_path"),
                getNullableInteger(resultSet, "item_index"),
                getNullableInteger(resultSet, "page_no"),
                resultSet.getString("page_range"),
                resultSet.getString("bbox_json"),
                resultSet.getString("source_block_ids"),
                descriptor,
                "vector",
                score
            );
        });
    }

    @Override
    public List<Document> keywordSearch(DocumentRetrieveRequest request) {
        if (!isSearchableRequest(request)) {
            return List.of();
        }

        ResolvedMetadataScope resolvedScope = resolveMetadataScope(request);
        if (resolvedScope.documentIds().isEmpty() || resolvedScope.taskIds().isEmpty()) {
            return List.of();
        }

        DocumentRetrieveRequest filteredRequest = new DocumentRetrieveRequest(
            request.getQuestion(),
            request.getRetrievalQuery(),
            resolvedScope.documentIds().isEmpty() ? null : resolvedScope.documentIds().get(0),
            resolvedScope.taskIds().isEmpty() ? null : resolvedScope.taskIds().get(0),
            request.getTopK(),
            resolvedScope.filters(),
            request.getQueryContextHints()
        );
        filteredRequest.setDocumentIds(resolvedScope.documentIds());
        filteredRequest.setTaskIds(resolvedScope.taskIds());

        DocumentKeywordSearchGateway keywordSearchGateway = keywordSearchGatewayProvider.getIfAvailable();
        if (keywordSearchGateway == null) {
            throw new IllegalStateException("当前未找到可用的 Elasticsearch/BM25 关键词检索服务，无法执行关键词检索。");
        }
        return keywordSearchGateway.search(filteredRequest);
    }

    @Override
    public List<Document> elevateToParentBlocks(List<Document> childDocuments, int maxChars) {
        if (CollUtil.isEmpty(childDocuments)) {
            return List.of();
        }

        Map<Long, List<Document>> childGroupsByParent = new LinkedHashMap<>();
        List<Document> directCandidateDocuments = new ArrayList<>();
        for (Document childDocument : childDocuments) {
            if (childDocument == null) {
                continue;
            }
            Long parentBlockId = asLong(childDocument.getMetadata().get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID));
            if (parentBlockId == null) {
                directCandidateDocuments.add(childDocument);
                continue;
            }
            childGroupsByParent.computeIfAbsent(parentBlockId, ignored -> new ArrayList<>()).add(childDocument);
        }

        if (childGroupsByParent.isEmpty()) {
            return directCandidateDocuments;
        }

        List<Long> parentBlockIds = new ArrayList<>(childGroupsByParent.keySet());
        Map<Long, SuperAgentDocumentParentBlock> parentBlockMap = parentBlockMapper.selectList(
                new LambdaQueryWrapper<SuperAgentDocumentParentBlock>()
                    .in(SuperAgentDocumentParentBlock::getId, parentBlockIds)
                    .eq(SuperAgentDocumentParentBlock::getStatus, BusinessStatus.YES.getCode())
                    .orderByAsc(SuperAgentDocumentParentBlock::getParentNo)
            ).stream()
            .collect(Collectors.toMap(
                SuperAgentDocumentParentBlock::getId,
                parent -> parent,
                (left, right) -> left,
                LinkedHashMap::new
            ));

        List<Document> elevatedDocuments = new ArrayList<>(childGroupsByParent.size() + directCandidateDocuments.size());
        for (Map.Entry<Long, List<Document>> entry : childGroupsByParent.entrySet()) {
            SuperAgentDocumentParentBlock parentBlock = parentBlockMap.get(entry.getKey());
            if (parentBlock == null) {
                elevatedDocuments.addAll(entry.getValue());
                continue;
            }
            elevatedDocuments.add(buildParentEvidenceDocument(parentBlock, entry.getValue(), maxChars));
        }
        elevatedDocuments.addAll(directCandidateDocuments);
        elevatedDocuments.sort(this::compareEvidenceDocument);
        return elevatedDocuments;
    }

    private Document buildRetrievedDocument(long chunkId,
                                            String chunkText,
                                            String contentWithWeight,
                                            String chunkType,
                                            String title,
                                            String keywords,
                                            String questions,
                                            long taskId,
                                            long parentBlockId,
                                            int chunkNo,
                                            String sectionPath,
                                            Long structureNodeId,
                                            Integer structureNodeType,
                                            String canonicalPath,
                                            Integer itemIndex,
                                            Integer pageNo,
                                            String pageRange,
                                            String bboxJson,
                                            String sourceBlockIds,
                                            KnowledgeDocumentDescriptor descriptor,
                                            String channel,
                                            double score) {
        Map<String, Object> metadata = new LinkedHashMap<>();

        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "DOCUMENT");
        metadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, channel);
        metadata.put(DocumentKnowledgeMetadataKeys.SCORE, score);
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_ID, chunkId);
        metadata.put(DocumentKnowledgeMetadataKeys.TASK_ID, taskId);
        metadata.put(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, parentBlockId);
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_NO, chunkNo);
        metadata.put(DocumentKnowledgeMetadataKeys.SECTION_PATH, safeText(sectionPath));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_ID, structureNodeId);
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_TYPE, structureNodeType);
        metadata.put(DocumentKnowledgeMetadataKeys.CANONICAL_PATH, safeText(canonicalPath));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.ITEM_INDEX, itemIndex);
        metadata.put(DocumentKnowledgeMetadataKeys.CONTENT_WITH_WEIGHT, safeText(contentWithWeight));
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_TYPE, safeText(chunkType));
        metadata.put(DocumentKnowledgeMetadataKeys.TITLE, safeText(title));
        metadata.put(DocumentKnowledgeMetadataKeys.KEYWORDS, safeText(keywords));
        metadata.put(DocumentKnowledgeMetadataKeys.QUESTIONS, safeText(questions));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.PAGE_NO, pageNo);
        metadata.put(DocumentKnowledgeMetadataKeys.PAGE_RANGE, safeText(pageRange));
        metadata.put(DocumentKnowledgeMetadataKeys.BBOX_JSON, safeText(bboxJson));
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_BLOCK_IDS, safeText(sourceBlockIds));
        metadata.put(DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET, chunkText);
        if (descriptor != null) {

            metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, descriptor.getDocumentId());
            metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, safeText(descriptor.getDocumentName()));
            putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_ID, descriptor.getKnowledgeBaseId());
            metadata.put(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_NAME, safeText(descriptor.getKnowledgeBaseName()));
        }
        graphRagTypedChunkMetadataSupport.enrichMetadata(metadata, chunkType, sourceBlockIds);

        return Document.builder()
            .id(String.valueOf(chunkId))
            .text(chunkText)
            .metadata(metadata)
            .score(score)
            .build();
    }

    private boolean isSearchableRequest(DocumentRetrieveRequest request) {

        if (request == null || StrUtil.isBlank(request.getQuestion()) || StrUtil.isBlank(request.getRetrievalQuery())) {
            return false;
        }
        return !request.resolvedDocumentIds().isEmpty() && !request.resolvedTaskIds().isEmpty();
    }

    private Map<Long, KnowledgeDocumentDescriptor> listDescriptorMap(List<Long> requestedDocumentIds) {
        List<KnowledgeDocumentDescriptor> descriptors = listRetrievableDocuments();
        if (descriptors.isEmpty()) {
            return Map.of();
        }

        return descriptors.stream()
            .filter(descriptor -> requestedDocumentIds.contains(descriptor.getDocumentId()))
            .collect(Collectors.toMap(
                KnowledgeDocumentDescriptor::getDocumentId,
                descriptor -> descriptor,
                (left, right) -> left,
                LinkedHashMap::new
            ));
    }

    private ResolvedMetadataScope resolveMetadataScope(DocumentRetrieveRequest request) {
        List<Long> baseDocumentIds = request.resolvedDocumentIds();
        List<Long> baseTaskIds = request.resolvedTaskIds();
        return new ResolvedMetadataScope(baseDocumentIds, baseTaskIds, request.getFilters());
    }

    private void appendSectionFilters(StringBuilder sqlBuilder, DocumentRetrieveFilters filters) {
        boolean hasSectionHints = filters != null && CollUtil.isNotEmpty(filters.getSectionPathHints());
        if (!hasSectionHints) {
            appendStructureFilters(sqlBuilder, filters);
            return;
        }

        sqlBuilder.append("\n  AND (");
        for (int index = 0; index < filters.getSectionPathHints().size(); index++) {
            if (index > 0) {
                sqlBuilder.append(" OR ");
            }
            sqlBuilder.append("LOWER(COALESCE(section_path, '')) LIKE ?");
        }
        sqlBuilder.append(")");
        appendStructureFilters(sqlBuilder, filters);
    }

    private void appendSectionFilterParams(List<Object> params, DocumentRetrieveFilters filters) {
        if (filters != null && CollUtil.isNotEmpty(filters.getSectionPathHints())) {
            for (String sectionHint : filters.getSectionPathHints()) {
                params.add("%" + sectionHint.toLowerCase(Locale.ROOT) + "%");
            }
        }
        appendStructureFilterParams(params, filters);
    }

    private void appendStructureFilters(StringBuilder sqlBuilder, DocumentRetrieveFilters filters) {
        boolean hasStructureNodeIds = filters != null && CollUtil.isNotEmpty(filters.getStructureNodeIdHints());
        boolean hasCanonicalPathHints = filters != null && CollUtil.isNotEmpty(filters.getCanonicalPathHints());
        boolean hasItemIndexes = filters != null && CollUtil.isNotEmpty(filters.getItemIndexHints());
        if (!hasStructureNodeIds && !hasCanonicalPathHints && !hasItemIndexes) {
            return;
        }
        if (hasStructureNodeIds) {
            sqlBuilder.append("\n  AND structure_node_id IN (")
                .append(buildPlaceholders(filters.getStructureNodeIdHints().size()))
                .append(")");
        }
        if (hasCanonicalPathHints) {
            sqlBuilder.append("\n  AND (");
            for (int index = 0; index < filters.getCanonicalPathHints().size(); index++) {
                if (index > 0) {
                    sqlBuilder.append(" OR ");
                }
                sqlBuilder.append("LOWER(COALESCE(canonical_path, '')) LIKE ?");
            }
            sqlBuilder.append(")");
        }
        if (hasItemIndexes) {
            sqlBuilder.append("\n  AND item_index IN (")
                .append(buildPlaceholders(filters.getItemIndexHints().size()))
                .append(")");
        }
    }

    private void appendStructureFilterParams(List<Object> params, DocumentRetrieveFilters filters) {
        if (filters == null) {
            return;
        }
        if (CollUtil.isNotEmpty(filters.getStructureNodeIdHints())) {
            params.addAll(filters.getStructureNodeIdHints());
        }
        if (CollUtil.isNotEmpty(filters.getCanonicalPathHints())) {
            for (String canonicalPathHint : filters.getCanonicalPathHints()) {
                params.add(canonicalPathHint.toLowerCase(Locale.ROOT) + "%");
            }
        }
        if (CollUtil.isNotEmpty(filters.getItemIndexHints())) {
            params.addAll(filters.getItemIndexHints());
        }
    }

    private Document buildParentEvidenceDocument(SuperAgentDocumentParentBlock parentBlock,
                                                 List<Document> childDocuments,
                                                 int maxChars) {
        Document bestChild = selectBestChildForParentEvidence(childDocuments);

        double parentScore = aggregateParentScore(childDocuments);
        Map<String, Object> metadata = new LinkedHashMap<>(bestChild.getMetadata());
        metadata.put(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, parentBlock.getId());
        metadata.put(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_NO, parentBlock.getParentNo());
        metadata.put(DocumentKnowledgeMetadataKeys.SECTION_PATH, safeText(parentBlock.getSectionPath()));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_ID, parentBlock.getStructureNodeId());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_TYPE, parentBlock.getStructureNodeType());
        metadata.put(DocumentKnowledgeMetadataKeys.CANONICAL_PATH, safeText(parentBlock.getCanonicalPath()));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.ITEM_INDEX, parentBlock.getItemIndex());
        metadata.put(DocumentKnowledgeMetadataKeys.PAGE_RANGE, safeText(parentBlock.getPageRange()));
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_BLOCK_IDS, safeText(parentBlock.getSourceBlockIds()));
        metadata.put(DocumentKnowledgeMetadataKeys.SCORE, parentScore);
        metadata.put(DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET, safeText(parentBlock.getParentText()));
        mergeGraphRagMetadata(metadata, childDocuments);

        LinkedHashSet<String> channels = childDocuments.stream()
            .map(document -> asText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.CHANNEL)))
            .filter(StrUtil::isNotBlank)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        metadata.put(DocumentKnowledgeMetadataKeys.CHANNEL,
            channels.size() > 1 ? "hybrid" : channels.stream().findFirst().orElse("vector"));

        return Document.builder()
            .id("parent-" + parentBlock.getId())
            .text(renderParentEvidenceText(parentBlock, childDocuments, maxChars))
            .metadata(metadata)
            .score(parentScore)
            .build();
    }

    private Document selectBestChildForParentEvidence(List<Document> childDocuments) {
        Document bestByScore = childDocuments.stream()
            .max(Comparator.comparingDouble(document -> {
                Double score = resolveScore(document);
                return score == null ? 0D : score;
            }))
            .orElseThrow();
        Double bestScore = resolveScore(bestByScore);
        if (bestScore == null || bestScore <= 0D) {
            return bestByScore;
        }

        Document bestRelationChild = childDocuments.stream()
            .filter(document -> document != null && document.getMetadata() != null)
            .filter(document -> document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_RELATION_ID) != null)
            .max(Comparator.comparingDouble(document -> {
                Double score = resolveScore(document);
                double relationBonus = 0.22D;
                double groupBonus = document.getMetadata().get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_DOCUMENT_COUNT) instanceof Number number
                    ? Math.min(0.18D, Math.max(0D, number.doubleValue() - 1D) * 0.06D)
                    : 0D;
                return (score == null ? 0D : score) + relationBonus + groupBonus;
            }))
            .orElse(null);
        if (bestRelationChild == null) {
            return bestByScore;
        }

        Double relationScore = resolveScore(bestRelationChild);
        if (relationScore == null) {
            return bestByScore;
        }
        if (relationScore >= bestScore * 0.65D) {
            return bestRelationChild;
        }
        return bestByScore;
    }

    private void mergeGraphRagMetadata(Map<String, Object> metadata, List<Document> childDocuments) {
        Document graphRagChild = selectBestGraphRagChild(childDocuments);
        if (graphRagChild == null || graphRagChild.getMetadata() == null) {
            return;
        }
        Map<String, Object> graphMetadata = graphRagChild.getMetadata();
        for (String key : DocumentKnowledgeMetadataKeys.GRAPH_RAG_METADATA_KEYS) {
            copyIfPresent(graphMetadata, metadata, key);
        }
    }

    private Document selectBestGraphRagChild(List<Document> childDocuments) {
        if (CollUtil.isEmpty(childDocuments)) {
            return null;
        }
        return childDocuments.stream()
            .filter(document -> document != null && document.getMetadata() != null)
            .filter(document -> hasGraphRagMetadata(document.getMetadata()))
            .max(Comparator.comparingDouble(this::graphRagMetadataPriority))
            .orElse(null);
    }

    private boolean hasGraphRagMetadata(Map<String, Object> metadata) {
        String channel = asText(metadata.get(DocumentKnowledgeMetadataKeys.CHANNEL));
        String sourceType = asText(metadata.get(DocumentKnowledgeMetadataKeys.SOURCE_TYPE));
        return "graph-rag".equalsIgnoreCase(channel)
            || "GRAPH_RAG".equalsIgnoreCase(sourceType)
            || metadata.get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID) != null
            || StrUtil.isNotBlank(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_KEY)))
            || StrUtil.isNotBlank(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY)))
            || StrUtil.isNotBlank(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY)))
            || metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_ID) != null
            || metadata.get(DocumentKnowledgeMetadataKeys.KG_ENTITY_ID) != null;
    }

    private double graphRagMetadataPriority(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        double priority = resolveScoreOrZero(document) * 0.01D;
        if (Boolean.parseBoolean(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_SUMMARY_ONLY)))) {
            priority -= 30D;
        }
        String groundingLevel = asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_GROUNDING_LEVEL));
        if ("RELATION_STRONG_QUOTE".equalsIgnoreCase(groundingLevel)) {
            priority += 18D;
        }
        else if ("RELATION_WEAK_QUOTE".equalsIgnoreCase(groundingLevel)) {
            priority += 8D;
        }
        else if ("COMMUNITY_SOURCE_QUOTE".equalsIgnoreCase(groundingLevel)) {
            priority += 6D;
        }
        if (metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_ID) != null) {
            priority += 20D;
        }
        if (metadata.get(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID) != null) {
            priority += 16D;
        }
        if (StrUtil.isNotBlank(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY)))) {
            priority += 24D;
        }
        if (StrUtil.isNotBlank(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY)))) {
            priority += 18D;
        }
        if (StrUtil.isNotBlank(asText(metadata.get(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_NAME)))) {
            priority += 10D;
        }
        Object qualityScore = metadata.get(DocumentKnowledgeMetadataKeys.KG_QUALITY_SCORE);
        if (qualityScore instanceof Number number) {
            priority += Math.min(4D, Math.max(0D, number.doubleValue()) * 4D);
        }
        Object pagerank = metadata.get(DocumentKnowledgeMetadataKeys.KG_PAGERANK);
        if (pagerank instanceof Number number) {
            priority += Math.min(3D, Math.max(0D, number.doubleValue()) * 3D);
        }
        Object communityRankScore = metadata.get(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_RANK_SCORE);
        if (communityRankScore instanceof Number number) {
            priority += Math.min(4D, Math.max(0D, number.doubleValue()) * 2.2D);
        }
        return priority;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private double aggregateParentScore(List<Document> childDocuments) {
        double bestChildScore = childDocuments.stream()
            .map(this::resolveScore)
            .filter(Objects::nonNull)
            .max(Double::compareTo)
            .orElse(0D);
        int supportCount = Math.max(0, childDocuments.size() - 1);
        LinkedHashSet<String> channels = childDocuments.stream()
            .map(document -> asText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.CHANNEL)))
            .filter(StrUtil::isNotBlank)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        double supportWeight = Math.min(0.36D, supportCount * 0.12D);
        double multiChannelWeight = channels.size() > 1 ? 0.10D : 0D;
        return bestChildScore * (1D + supportWeight + multiChannelWeight);
    }

    private void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private int compareEvidenceDocument(Document left, Document right) {
        int scoreCompare = Double.compare(resolveScoreOrZero(right), resolveScoreOrZero(left));
        if (scoreCompare != 0) {
            return scoreCompare;
        }
        Integer leftParentNo = asInteger(left == null ? null : left.getMetadata().get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_NO));
        Integer rightParentNo = asInteger(right == null ? null : right.getMetadata().get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_NO));
        int parentNoCompare = compareNullableInteger(leftParentNo, rightParentNo);
        if (parentNoCompare != 0) {
            return parentNoCompare;
        }
        Integer leftChunkNo = asInteger(left == null ? null : left.getMetadata().get(DocumentKnowledgeMetadataKeys.CHUNK_NO));
        Integer rightChunkNo = asInteger(right == null ? null : right.getMetadata().get(DocumentKnowledgeMetadataKeys.CHUNK_NO));
        return compareNullableInteger(leftChunkNo, rightChunkNo);
    }

    private double resolveScoreOrZero(Document document) {
        Double score = resolveScore(document);
        return score == null ? 0D : score;
    }

    private int compareNullableInteger(Integer left, Integer right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        return Integer.compare(left, right);
    }

    private String renderParentEvidenceText(SuperAgentDocumentParentBlock parentBlock,
                                            List<Document> childDocuments,
                                            int maxChars) {
        String parentText = safeText(parentBlock.getParentText());
        if (StrUtil.isBlank(parentText)) {
            return childDocuments.isEmpty() ? "" : StrUtil.blankToDefault(childDocuments.get(0).getText(), "");
        }

        StringBuilder hitSummaryBuilder = new StringBuilder();
        for (Document childDocument : childDocuments) {
            if (childDocument == null) {
                continue;
            }
            if (!hitSummaryBuilder.isEmpty()) {
                hitSummaryBuilder.append('\n');
            }
            hitSummaryBuilder.append("- child#")
                .append(asInteger(childDocument.getMetadata().get(DocumentKnowledgeMetadataKeys.CHUNK_NO)))
                .append("：")
                .append(trimText(safeText(childDocument.getText()), 140));
        }

        String composed = joinSections(
            "[父块内容]\n" + parentText,
            hitSummaryBuilder.isEmpty() ? "" : "[命中子片段]\n" + hitSummaryBuilder
        );
        return trimText(composed, Math.max(1, maxChars));
    }

    private Double resolveScore(Document document) {
        if (document == null) {
            return null;
        }
        Object metadataScore = document.getMetadata().get(DocumentKnowledgeMetadataKeys.SCORE);
        if (metadataScore instanceof Number number) {
            return number.doubleValue();
        }
        return document.getScore();
    }

    private String joinSections(String... sections) {
        List<String> parts = new ArrayList<>();
        for (String section : sections) {
            if (StrUtil.isNotBlank(section)) {
                parts.add(section.trim());
            }
        }
        return String.join("\n\n", parts);
    }

    private Long getNullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private Integer getNullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private String trimText(String text, int maxChars) {
        if (StrUtil.isBlank(text) || text.length() <= maxChars) {
            return StrUtil.blankToDefault(text, "");
        }
        return text.substring(0, Math.max(0, maxChars - 1)) + "…";
    }

    private String safeText(String text) {
        return text == null ? "" : text;
    }

    private Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private String asText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int resolveTopK(int topK) {

        return topK <= 0 ? 10 : Math.min(topK, 50);
    }

    private EmbeddingModel requireEmbeddingModel() {
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {

            throw new IllegalStateException("当前未找到可用的 EmbeddingModel，无法执行向量检索。");
        }
        return embeddingModel;
    }

    private String toVectorLiteral(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalStateException("问题向量生成失败，无法执行检索。");
        }
        StringBuilder vectorBuilder = new StringBuilder("[");
        for (int index = 0; index < embedding.length; index++) {

            if (index > 0) {
                vectorBuilder.append(',');
            }
            vectorBuilder.append(embedding[index]);
        }
        vectorBuilder.append(']');
        return vectorBuilder.toString();
    }

    private String buildPlaceholders(int size) {
        return IntStream.range(0, size)
            .mapToObj(index -> "?")
            .collect(Collectors.joining(","));
    }

    private int defaultInteger(Integer value) {
        return Objects.requireNonNullElse(value, 0);
    }

    private record ResolvedMetadataScope(
        List<Long> documentIds,
        List<Long> taskIds,
        DocumentRetrieveFilters filters
    ) {
    }
}
