package org.javaup.ai.manage.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.config.DocumentManageProperties;
import org.javaup.ai.manage.data.SuperAgentDocumentChunk;
import org.javaup.ai.manage.data.SuperAgentRaptorNode;
import org.javaup.ai.manage.mapper.SuperAgentRaptorNodeMapper;
import org.javaup.ai.manage.model.raptor.RaptorBuildResult;
import org.javaup.ai.manage.model.raptor.RaptorQualityReport;
import org.javaup.ai.manage.service.RaptorBuildService;
import org.javaup.ai.manage.service.RaptorQualityService;
import org.javaup.ai.manage.service.RaptorSummaryIndexService;
import org.javaup.ai.manage.support.DocumentPgVectorConstants;
import org.javaup.ai.manage.support.MybatisBatchExecutor;
import org.javaup.ai.ragtools.client.RagToolsClient;
import org.javaup.ai.ragtools.model.RagToolsRaptorBuildRequest;
import org.javaup.ai.ragtools.model.RagToolsRaptorBuildResponse;
import org.javaup.enums.BusinessStatus;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@AllArgsConstructor
@Service
public class RaptorBuildServiceImpl implements RaptorBuildService {

    private static final String UPSERT_SQL_TEMPLATE = """
        INSERT INTO %s
        (id, document_id, task_id, node_level, node_no, parent_node_id, title, summary, summary_with_weight,
         source_chunk_ids_json, source_parent_block_ids_json, section_path, page_range, keywords, questions,
         embedding_model, metadata_json, embedding, create_time, edit_time, status)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS vector), NOW(), NOW(), ?)
        ON CONFLICT (id) DO UPDATE SET
            document_id = EXCLUDED.document_id,
            task_id = EXCLUDED.task_id,
            node_level = EXCLUDED.node_level,
            node_no = EXCLUDED.node_no,
            parent_node_id = EXCLUDED.parent_node_id,
            title = EXCLUDED.title,
            summary = EXCLUDED.summary,
            summary_with_weight = EXCLUDED.summary_with_weight,
            source_chunk_ids_json = EXCLUDED.source_chunk_ids_json,
            source_parent_block_ids_json = EXCLUDED.source_parent_block_ids_json,
            section_path = EXCLUDED.section_path,
            page_range = EXCLUDED.page_range,
            keywords = EXCLUDED.keywords,
            questions = EXCLUDED.questions,
            embedding_model = EXCLUDED.embedding_model,
            metadata_json = EXCLUDED.metadata_json,
            embedding = EXCLUDED.embedding,
            edit_time = NOW(),
            status = EXCLUDED.status
        """;

    private static final String DELETE_VECTOR_BY_TASK_SQL_TEMPLATE = "DELETE FROM %s WHERE document_id = ? AND task_id = ?";

    private static final String DELETE_VECTOR_BY_DOCUMENT_SQL_TEMPLATE = "DELETE FROM %s WHERE document_id = ?";

    private static final int EMBEDDING_BATCH_SIZE_LIMIT = 10;

    private final SuperAgentRaptorNodeMapper raptorNodeMapper;

    private final RagToolsClient ragToolsClient;

    private final ObjectMapper objectMapper;

    private final UidGenerator uidGenerator;

    @Qualifier("documentManagePgVectorJdbcTemplate")
    private final JdbcTemplate pgVectorJdbcTemplate;

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    private final DocumentManageProperties properties;

    private final RaptorQualityService raptorQualityService;

    private final ObjectProvider<RaptorSummaryIndexService> raptorSummaryIndexServiceProvider;

    @Value("${spring.ai.openai.embedding.options.model:}")
    private String embeddingModelName;

    @Value("${app.chat.rag.raptor-max-cluster-size:6}")
    private Integer maxClusterSize;

    @Value("${app.chat.rag.raptor-max-levels:3}")
    private Integer maxLevels;

    @Value("${app.chat.rag.raptor-llm-summary-enabled:true}")
    private Boolean llmSummaryEnabled;

    @Value("${app.chat.rag.raptor-summary-quality-floor:0.42}")
    private Double summaryQualityFloor;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RaptorBuildResult rebuildDocumentTree(Long documentId, Long taskId, List<SuperAgentDocumentChunk> chunks) {
        deleteByTask(documentId, taskId);
        if (documentId == null || taskId == null || CollUtil.isEmpty(chunks)) {
            return RaptorBuildResult.builder().build();
        }

        long buildStartedNanos = System.nanoTime();
        log.info("开始构建 RAPTOR 摘要树，documentId={}, taskId={}, chunkCount={}", documentId, taskId, chunks.size());
        long pythonStartedNanos = System.nanoTime();
        RagToolsRaptorBuildResponse response = ragToolsClient.buildRaptor(buildRequest(documentId, taskId, chunks));
        log.info("Python RAPTOR 构建调用完成，documentId={}, taskId={}, costMillis={}",
            documentId, taskId, elapsedMillis(pythonStartedNanos));
        if (response == null) {
            throw new IllegalStateException("Python RAPTOR 构建接口返回为空。");
        }
        logLlmSummaryFallbacks(documentId, taskId, response.getNodes());
        RaptorQualityReport sourceQualityReport = raptorQualityService.evaluatePythonNodes(response.getNodes(), qualityFloor());
        log.info("Python RAPTOR 原始摘要质量评测完成，documentId={}, taskId={}, nodeCount={}, avgQuality={}, minQuality={}, p10Quality={}, medianQuality={}, recommendedFloor={}, lowQualityCount={}",
            documentId,
            taskId,
            sourceQualityReport.getNodeCount(),
            sourceQualityReport.getAverageQualityScore(),
            sourceQualityReport.getMinQualityScore(),
            sourceQualityReport.getP10QualityScore(),
            sourceQualityReport.getMedianQualityScore(),
            sourceQualityReport.getRecommendedQualityFloor(),
            sourceQualityReport.getLowQualityNodeCount());

        Map<String, Long> idMap = allocateNodeIds(response.getNodes());
        List<SuperAgentRaptorNode> nodes = buildNodeEntities(documentId, taskId, response.getNodes(), idMap);
        if (CollUtil.isEmpty(nodes)) {
            log.info("RAPTOR 构建结果没有达到质量阈值的摘要节点，documentId={}, taskId={}, qualityFloor={}",
                documentId, taskId, qualityFloor());
            return RaptorBuildResult.builder()
                .sourceQualityReport(sourceQualityReport)
                .savedQualityReport(raptorQualityService.evaluatePythonNodes(List.of(), qualityFloor()))
                .build();
        }
        long insertStartedNanos = System.nanoTime();
        MybatisBatchExecutor.insertBatch(SuperAgentRaptorNode.class, nodes);
        log.info("RAPTOR 节点入库完成，documentId={}, taskId={}, nodeCount={}, costMillis={}",
            documentId, taskId, nodes.size(), elapsedMillis(insertStartedNanos));
        vectorize(nodes);
        indexSummaries(nodes);

        int levelCount = nodes.stream()
            .map(SuperAgentRaptorNode::getNodeLevel)
            .filter(Objects::nonNull)
            .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll)
            .size();
        int sourceChunkCount = nodes.stream()
            .flatMap(node -> readLongList(node.getSourceChunkIdsJson()).stream())
            .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll)
            .size();

        RaptorBuildResult result = RaptorBuildResult.builder()
            .nodeCount(nodes.size())
            .levelCount(levelCount)
            .sourceChunkCount(sourceChunkCount)
            .sourceQualityReport(sourceQualityReport)
            .savedQualityReport(raptorQualityService.evaluate(documentId, taskId))
            .build();
        log.info("RAPTOR 层级摘要树构建完成: documentId={}, taskId={}, result={}, costMillis={}",
            documentId, taskId, result, elapsedMillis(buildStartedNanos));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByTask(Long documentId, Long taskId) {
        if (documentId == null || taskId == null) {
            return;
        }
        raptorNodeMapper.delete(new LambdaQueryWrapper<SuperAgentRaptorNode>()
            .eq(SuperAgentRaptorNode::getDocumentId, documentId)
            .eq(SuperAgentRaptorNode::getTaskId, taskId));
        pgVectorJdbcTemplate.update(DELETE_VECTOR_BY_TASK_SQL_TEMPLATE.formatted(DocumentPgVectorConstants.RAPTOR_EMBEDDING_TABLE_NAME),
            documentId, taskId);
        RaptorSummaryIndexService indexService = raptorSummaryIndexServiceProvider.getIfAvailable();
        if (indexService != null) {
            indexService.deleteByTask(documentId, taskId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        raptorNodeMapper.delete(new LambdaQueryWrapper<SuperAgentRaptorNode>()
            .eq(SuperAgentRaptorNode::getDocumentId, documentId));
        pgVectorJdbcTemplate.update(DELETE_VECTOR_BY_DOCUMENT_SQL_TEMPLATE.formatted(DocumentPgVectorConstants.RAPTOR_EMBEDDING_TABLE_NAME),
            documentId);
        RaptorSummaryIndexService indexService = raptorSummaryIndexServiceProvider.getIfAvailable();
        if (indexService != null) {
            indexService.deleteByDocumentId(documentId);
        }
    }

    private RagToolsRaptorBuildRequest buildRequest(Long documentId, Long taskId, List<SuperAgentDocumentChunk> chunks) {
        RagToolsRaptorBuildRequest request = new RagToolsRaptorBuildRequest();
        request.setDocumentId(documentId);
        request.setTaskId(taskId);
        request.setMaxClusterSize(maxClusterSize);
        request.setMaxLevels(maxLevels);
        request.setLlmSummaryEnabled(Boolean.TRUE.equals(llmSummaryEnabled));

        List<RagToolsRaptorBuildRequest.Chunk> requestChunks = new ArrayList<>();
        for (SuperAgentDocumentChunk chunk : chunks) {
            if (chunk == null || chunk.getId() == null || StrUtil.isBlank(chunk.getChunkText())) {
                continue;
            }
            RagToolsRaptorBuildRequest.Chunk requestChunk = new RagToolsRaptorBuildRequest.Chunk();
            requestChunk.setChunkId(chunk.getId());
            requestChunk.setParentBlockId(chunk.getParentBlockId());
            requestChunk.setChunkNo(chunk.getChunkNo());
            requestChunk.setChunkType(chunk.getChunkType());
            requestChunk.setTitle(chunk.getTitle());
            requestChunk.setSectionPath(chunk.getSectionPath());
            requestChunk.setPageNo(chunk.getPageNo());
            requestChunk.setPageRange(chunk.getPageRange());
            requestChunk.setBboxJson(chunk.getBboxJson());
            requestChunk.setText(chunk.getChunkText());
            requestChunk.setContentWithWeight(StrUtil.blankToDefault(chunk.getContentWithWeight(), chunk.getChunkText()));
            requestChunk.setSourceBlockIds(chunk.getSourceBlockIds());
            requestChunk.setMetadata(chunkMetadata(chunk));
            requestChunks.add(requestChunk);
        }
        request.setChunks(requestChunks);
        return request;
    }

    private Map<String, Object> chunkMetadata(SuperAgentDocumentChunk chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("planId", chunk.getPlanId());
        metadata.put("sourceType", chunk.getSourceType());
        metadata.put("structureNodeId", chunk.getStructureNodeId());
        metadata.put("structureNodeType", chunk.getStructureNodeType());
        metadata.put("canonicalPath", chunk.getCanonicalPath());
        metadata.put("itemIndex", chunk.getItemIndex());
        metadata.put("keywords", chunk.getKeywords());
        metadata.put("questions", chunk.getQuestions());
        return metadata;
    }

    private Map<String, Long> allocateNodeIds(List<RagToolsRaptorBuildResponse.Node> extractedNodes) {
        Map<String, Long> idMap = new LinkedHashMap<>();
        if (CollUtil.isEmpty(extractedNodes)) {
            return idMap;
        }
        for (RagToolsRaptorBuildResponse.Node node : extractedNodes) {
            if (node != null && StrUtil.isNotBlank(node.getId()) && summaryQualityScore(node) >= qualityFloor()) {
                idMap.put(node.getId(), uidGenerator.getUid());
            }
        }
        return idMap;
    }

    private List<SuperAgentRaptorNode> buildNodeEntities(Long documentId,
                                                         Long taskId,
                                                         List<RagToolsRaptorBuildResponse.Node> extractedNodes,
                                                         Map<String, Long> idMap) {
        if (CollUtil.isEmpty(extractedNodes)) {
            return List.of();
        }
        List<SuperAgentRaptorNode> nodes = new ArrayList<>();
        for (RagToolsRaptorBuildResponse.Node extracted : extractedNodes) {
            if (extracted == null || StrUtil.isBlank(extracted.getId())) {
                continue;
            }
            Long nodeId = idMap.get(extracted.getId());
            if (nodeId == null) {
                continue;
            }

            SuperAgentRaptorNode node = new SuperAgentRaptorNode();
            node.setId(nodeId);
            node.setDocumentId(documentId);
            node.setTaskId(taskId);
            node.setNodeKey(limit(extracted.getId(), 255));
            node.setParentNodeId(StrUtil.isBlank(extracted.getParentId()) ? null : idMap.get(extracted.getParentId()));
            node.setNodeLevel(defaultInteger(extracted.getLevel(), 1));
            node.setNodeNo(defaultInteger(extracted.getNodeNo(), nodes.size() + 1));
            node.setTitle(limit(extracted.getTitle(), 500));
            node.setSummary(extracted.getSummary());
            node.setSummaryWithWeight(StrUtil.blankToDefault(extracted.getSummaryWithWeight(), extracted.getSummary()));
            node.setChildNodeIdsJson(writeJson(remapIds(extracted.getChildNodeIds(), idMap)));
            node.setSourceChunkIdsJson(writeJson(extracted.getSourceChunkIds()));
            node.setSourceParentBlockIdsJson(writeJson(extracted.getSourceParentBlockIds()));
            node.setSectionPath(limit(extracted.getSectionPath(), 1000));
            node.setPageRange(limit(extracted.getPageRange(), 64));
            node.setKeywords(writeJson(extracted.getKeywords()));
            node.setQuestions(writeJson(extracted.getQuestions()));
            node.setMetadataJson(writeJson(metadata(
                "sourceNodeId", extracted.getId(),
                "sourceParentId", extracted.getParentId(),
                "sourceChildNodeIds", extracted.getChildNodeIds(),
                "summaryQualityScore", summaryQualityScore(extracted),
                "sourceMetadata", extracted.getMetadata()
            )));
            if (summaryQualityScore(extracted) < qualityFloor()) {
                log.info("跳过低质量 RAPTOR 摘要节点: documentId={}, taskId={}, sourceNodeId={}, qualityScore={}, floor={}",
                    documentId, taskId, extracted.getId(), summaryQualityScore(extracted), qualityFloor());
                continue;
            }
            node.setStatus(BusinessStatus.YES.getCode());
            nodes.add(node);
        }
        return nodes;
    }

    private List<Long> remapIds(List<String> sourceIds, Map<String, Long> idMap) {
        if (CollUtil.isEmpty(sourceIds)) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (String sourceId : sourceIds) {
            Long id = idMap.get(sourceId);
            if (id != null) {
                result.add(id);
            }
        }
        return result;
    }

    private void vectorize(List<SuperAgentRaptorNode> nodes) {
        if (CollUtil.isEmpty(nodes)) {
            return;
        }
        EmbeddingModel embeddingModel = requireEmbeddingModel();
        String embeddingModelName = resolveEmbeddingModelName();
        String upsertSql = UPSERT_SQL_TEMPLATE.formatted(DocumentPgVectorConstants.RAPTOR_EMBEDDING_TABLE_NAME);
        int batchSize = embeddingBatchSize();
        int totalBatchCount = (nodes.size() + batchSize - 1) / batchSize;
        long vectorizeStartedNanos = System.nanoTime();
        log.info("开始执行 RAPTOR 摘要向量化，nodeCount={}, batchSize={}, batchCount={}, embeddingModel={}",
            nodes.size(), batchSize, totalBatchCount, embeddingModelName);
        for (int startIndex = 0; startIndex < nodes.size(); startIndex += batchSize) {
            int endIndex = Math.min(startIndex + batchSize, nodes.size());
            List<SuperAgentRaptorNode> currentBatch = nodes.subList(startIndex, endIndex);
            int currentBatchIndex = (startIndex / batchSize) + 1;
            long batchStartedNanos = System.nanoTime();
            long embeddingStartedNanos = System.nanoTime();
            List<float[]> embeddings = embeddingModel.embed(currentBatch.stream()
                .map(this::embeddingInput)
                .toList());
            long embeddingCostMillis = elapsedMillis(embeddingStartedNanos);
            if (embeddings.size() != currentBatch.size()) {
                throw new IllegalStateException("EmbeddingModel 返回的 RAPTOR 向量数量与节点数量不一致。");
            }
            long upsertStartedNanos = System.nanoTime();
            batchUpsert(upsertSql, currentBatch, embeddings, embeddingModelName);
            log.info("RAPTOR 摘要向量化批次完成，batchIndex={}/{}, currentBatchSize={}, embeddingCostMillis={}, pgVectorCostMillis={}, batchCostMillis={}",
                currentBatchIndex, totalBatchCount, currentBatch.size(), embeddingCostMillis,
                elapsedMillis(upsertStartedNanos), elapsedMillis(batchStartedNanos));
        }
        log.info("RAPTOR 摘要向量化完成，nodeCount={}, batchSize={}, batchCount={}, embeddingModel={}, costMillis={}",
            nodes.size(), batchSize, totalBatchCount, embeddingModelName, elapsedMillis(vectorizeStartedNanos));
    }

    private void indexSummaries(List<SuperAgentRaptorNode> nodes) {
        if (CollUtil.isEmpty(nodes)) {
            return;
        }
        RaptorSummaryIndexService indexService = raptorSummaryIndexServiceProvider.getIfAvailable();
        if (indexService == null) {
            log.info("当前未启用 RAPTOR 摘要 Elasticsearch/BM25 索引服务，跳过摘要索引写入。");
            return;
        }
        indexService.indexNodes(nodes);
    }

    private void batchUpsert(String upsertSql,
                             List<SuperAgentRaptorNode> nodeBatch,
                             List<float[]> embeddingBatch,
                             String embeddingModelName) {
        pgVectorJdbcTemplate.batchUpdate(upsertSql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int index) throws SQLException {
                SuperAgentRaptorNode node = nodeBatch.get(index);
                ps.setLong(1, node.getId());
                ps.setLong(2, node.getDocumentId());
                ps.setLong(3, node.getTaskId());
                ps.setInt(4, defaultInteger(node.getNodeLevel(), 1));
                ps.setInt(5, defaultInteger(node.getNodeNo(), 0));
                if (node.getParentNodeId() == null) {
                    ps.setNull(6, Types.BIGINT);
                }
                else {
                    ps.setLong(6, node.getParentNodeId());
                }
                ps.setString(7, node.getTitle());
                ps.setString(8, node.getSummary());
                ps.setString(9, embeddingInput(node));
                ps.setString(10, node.getSourceChunkIdsJson());
                ps.setString(11, node.getSourceParentBlockIdsJson());
                ps.setString(12, node.getSectionPath());
                ps.setString(13, node.getPageRange());
                ps.setString(14, node.getKeywords());
                ps.setString(15, node.getQuestions());
                ps.setString(16, embeddingModelName);
                ps.setString(17, buildMetadataJson(node, embeddingModelName));
                ps.setString(18, toVectorLiteral(embeddingBatch.get(index)));
                ps.setInt(19, BusinessStatus.YES.getCode());
            }

            @Override
            public int getBatchSize() {
                return nodeBatch.size();
            }
        });
    }

    private String buildMetadataJson(SuperAgentRaptorNode node, String embeddingModelName) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("documentId", node.getDocumentId());
        metadata.put("taskId", node.getTaskId());
        metadata.put("raptorNodeId", node.getId());
        metadata.put("nodeLevel", node.getNodeLevel());
        metadata.put("nodeNo", node.getNodeNo());
        metadata.put("parentNodeId", node.getParentNodeId());
        metadata.put("title", node.getTitle());
        metadata.put("sectionPath", node.getSectionPath());
        metadata.put("pageRange", node.getPageRange());
        metadata.put("keywords", node.getKeywords());
        metadata.put("questions", node.getQuestions());
        metadata.put("sourceChunkIds", readLongList(node.getSourceChunkIdsJson()));
        metadata.put("sourceParentBlockIds", readLongList(node.getSourceParentBlockIdsJson()));
        metadata.put("summaryQualityScore", metadataValue(node.getMetadataJson(), "summaryQualityScore"));
        metadata.put("embeddingModel", embeddingModelName);
        return writeJson(metadata);
    }

    private String embeddingInput(SuperAgentRaptorNode node) {
        return StrUtil.blankToDefault(node.getSummaryWithWeight(), node.getSummary()).trim();
    }

    private EmbeddingModel requireEmbeddingModel() {
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            throw new IllegalStateException("当前未找到可用的 EmbeddingModel，无法执行 RAPTOR 摘要向量化。");
        }
        return embeddingModel;
    }

    private String resolveEmbeddingModelName() {
        return StrUtil.isNotBlank(embeddingModelName) ? embeddingModelName : "default";
    }

    private int embeddingBatchSize() {
        Integer configured = properties.getIndexBuild().getEmbeddingBatchSize();
        if (configured == null || configured <= 0) {
            return EMBEDDING_BATCH_SIZE_LIMIT;
        }
        return Math.min(configured, EMBEDDING_BATCH_SIZE_LIMIT);
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private String toVectorLiteral(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalStateException("EmbeddingModel 返回了空 RAPTOR 向量。");
        }
        StringBuilder vectorBuilder = new StringBuilder("[");
        for (int index = 0; index < embedding.length; index++) {
            if (index > 0) {
                vectorBuilder.append(",");
            }
            vectorBuilder.append(embedding[index]);
        }
        vectorBuilder.append("]");
        return vectorBuilder.toString();
    }

    private List<Long> readLongList(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, Long.class));
        }
        catch (Exception exception) {
            return List.of();
        }
    }

    private Map<String, Object> metadata(Object... keyValues) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            Object value = keyValues[index + 1];
            if (value != null) {
                metadata.put(String.valueOf(keyValues[index]), value);
            }
        }
        return metadata;
    }

    private void logLlmSummaryFallbacks(Long documentId, Long taskId, List<RagToolsRaptorBuildResponse.Node> extractedNodes) {
        if (!Boolean.TRUE.equals(llmSummaryEnabled) || CollUtil.isEmpty(extractedNodes)) {
            return;
        }
        for (RagToolsRaptorBuildResponse.Node node : extractedNodes) {
            Map<String, Object> sourceMetadata = node == null || node.getMetadata() == null ? Map.of() : node.getMetadata();
            Map<String, Object> signals = objectMap(sourceMetadata.get("summaryQualitySignals"));
            boolean requested = booleanValue(signals.get("llmSummaryRequested"));
            String status = stringValue(signals.get("llmSummaryStatus"));
            if (!requested || "success".equals(status)) {
                continue;
            }
            log.warn("RAPTOR LLM 摘要未生效，已使用非 LLM 摘要策略: documentId={}, taskId={}, sourceNodeId={}, summaryStrategy={}, llmSummaryStatus={}, llmSummaryError={}",
                documentId,
                taskId,
                node == null ? null : node.getId(),
                stringValue(sourceMetadata.get("summaryStrategy")),
                status,
                stringValue(signals.get("llmSummaryError")));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(stringValue(value));
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private double summaryQualityScore(RagToolsRaptorBuildResponse.Node extracted) {
        if (extracted == null || extracted.getQualityScore() == null) {
            return 0D;
        }
        return Math.max(0D, Math.min(1D, extracted.getQualityScore()));
    }

    private double qualityFloor() {
        if (summaryQualityFloor == null) {
            return 0.42D;
        }
        return Math.max(0D, Math.min(1D, summaryQualityFloor));
    }

    private Object metadataValue(String json, String key) {
        if (StrUtil.isBlank(json) || StrUtil.isBlank(key)) {
            return null;
        }
        try {
            Map<String, Object> metadata = objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
            return metadata.get(key);
        }
        catch (Exception exception) {
            return null;
        }
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("RAPTOR 元数据 JSON 序列化失败", exception);
        }
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private int defaultInteger(Integer value, int defaultValue) {
        return Objects.requireNonNullElse(value, defaultValue);
    }
}
