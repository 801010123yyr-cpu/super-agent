package org.javaup.ai.manage.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.manage.config.GraphRagBuildProperties;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.data.SuperAgentDocumentChunk;
import org.javaup.ai.manage.data.SuperAgentKgCommunity;
import org.javaup.ai.manage.data.SuperAgentKgEntity;
import org.javaup.ai.manage.data.SuperAgentKgEvidence;
import org.javaup.ai.manage.data.SuperAgentKgRelation;
import org.javaup.ai.manage.mapper.SuperAgentKgCommunityMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgEntityMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgEvidenceMapper;
import org.javaup.ai.manage.mapper.SuperAgentKgRelationMapper;
import org.javaup.ai.manage.model.graph.GraphRagBuildResult;
import org.javaup.ai.manage.model.graph.GraphRagCommunityReportAdvice;
import org.javaup.ai.manage.model.graph.GraphRagCommunityReportContext;
import org.javaup.ai.manage.model.graph.GraphRagEntityResolutionAdvice;
import org.javaup.ai.manage.model.graph.GraphRagEntityResolutionContext;
import org.javaup.ai.manage.model.graph.GraphRagExtractionAdvice;
import org.javaup.ai.manage.model.graph.GraphRagExtractionContext;
import org.javaup.ai.manage.service.GraphRagBuildService;
import org.javaup.ai.manage.service.GraphRagBuildCheckpointService;
import org.javaup.ai.manage.service.GraphRagCommunityReportAdvisor;
import org.javaup.ai.manage.service.GraphRagCrossDocumentIndexService;
import org.javaup.ai.manage.service.GraphRagEntityResolutionAdvisor;
import org.javaup.ai.manage.service.GraphRagExtractionAdvisor;
import org.javaup.ai.ragtools.client.RagToolsClient;
import org.javaup.ai.ragtools.model.RagToolsGraphExtractRequest;
import org.javaup.ai.ragtools.model.RagToolsGraphExtractResponse;
import org.javaup.enums.BusinessStatus;
import org.javaup.lease.RedisLeaseManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class GraphRagBuildServiceImpl implements GraphRagBuildService {

    private static final String RANK_ALGORITHM = "java.pagerank.v1";
    private static final String GRAPH_EXTRACTION_STRATEGY_LLM = "llm.controlled.extract.v1";
    private static final String GRAPH_EXTRACTION_ADVISOR_METADATA_KEY = "llmExtractionAdvisor";
    private static final String RELATION_TYPE_ASSOCIATED_WITH = "ASSOCIATED_WITH";
    private static final String SUPPORT_MODE_EXPLICIT_ACTION = "EXPLICIT_ACTION";
    private static final String COMMUNITY_REPORT_STRATEGY_EXTRACTIVE = "extractive.template.v1";
    private static final String COMMUNITY_REPORT_STRATEGY_LLM = "llm.controlled.v1";
    private static final int RANK_ITERATIONS = 30;
    private static final double RANK_DAMPING = 0.85D;
    private static final double GRAPH_EXTRACTION_CONFIDENCE_THRESHOLD = 0.70D;
    private static final double COMMUNITY_REPORT_CONFIDENCE_THRESHOLD = 0.60D;
    private static final double ENTITY_RESOLUTION_CONFIDENCE_THRESHOLD = 0.78D;
    private static final int GRAPH_EXTRACTION_CONTEXT_CHUNK_LIMIT = 12;
    private static final int GRAPH_EXTRACTION_CONTEXT_TEXT_LIMIT = 1200;
    private static final int GRAPH_EXTRACTION_ENTITY_LIMIT = 36;
    private static final int GRAPH_EXTRACTION_RELATION_LIMIT = 36;
    private static final int GRAPH_EXTRACTION_EVIDENCE_LIMIT = 72;
    private static final int ENTITY_RESOLUTION_CONTEXT_LIMIT = 80;
    private static final int COMMUNITY_CONTEXT_ENTITY_LIMIT = 30;
    private static final int COMMUNITY_CONTEXT_RELATION_LIMIT = 60;
    private static final int COMMUNITY_CONTEXT_EVIDENCE_LIMIT = 30;
    private static final int COMMUNITY_REPORT_FINDING_LIMIT = 5;

    private final SuperAgentKgEntityMapper entityMapper;

    private final SuperAgentKgRelationMapper relationMapper;

    private final SuperAgentKgEvidenceMapper evidenceMapper;

    private final SuperAgentKgCommunityMapper communityMapper;

    private final RagToolsClient ragToolsClient;

    private final ObjectMapper objectMapper;

    private final UidGenerator uidGenerator;

    private final GraphRagBuildProperties buildProperties;

    private final RedisLeaseManager redisLeaseManager;

    private final GraphRagBuildCheckpointService checkpointService;

    private final TransactionTemplate transactionTemplate;

    private final GraphRagExtractionAdvisor extractionAdvisor;

    private final GraphRagCommunityReportAdvisor communityReportAdvisor;

    private final GraphRagEntityResolutionAdvisor entityResolutionAdvisor;

    private final GraphRagCrossDocumentIndexService crossDocumentIndexService;

    @Autowired
    public GraphRagBuildServiceImpl(SuperAgentKgEntityMapper entityMapper,
                                    SuperAgentKgRelationMapper relationMapper,
                                    SuperAgentKgEvidenceMapper evidenceMapper,
                                    SuperAgentKgCommunityMapper communityMapper,
                                    RagToolsClient ragToolsClient,
                                    ObjectMapper objectMapper,
                                    UidGenerator uidGenerator,
                                    GraphRagBuildProperties buildProperties,
                                    RedisLeaseManager redisLeaseManager,
                                    GraphRagBuildCheckpointService checkpointService,
                                    TransactionTemplate transactionTemplate,
                                    ObjectProvider<GraphRagExtractionAdvisor> extractionAdvisorProvider,
                                    ObjectProvider<GraphRagCommunityReportAdvisor> communityReportAdvisorProvider,
                                    ObjectProvider<GraphRagEntityResolutionAdvisor> entityResolutionAdvisorProvider,
                                    ObjectProvider<GraphRagCrossDocumentIndexService> crossDocumentIndexServiceProvider) {
        this(
            entityMapper,
            relationMapper,
            evidenceMapper,
            communityMapper,
            ragToolsClient,
            objectMapper,
            uidGenerator,
            buildProperties,
            redisLeaseManager,
            checkpointService,
            transactionTemplate,
            extractionAdvisorProvider == null ? null : (GraphRagExtractionAdvisor) extractionAdvisorProvider.getIfAvailable(),
            communityReportAdvisorProvider == null ? null : (GraphRagCommunityReportAdvisor) communityReportAdvisorProvider.getIfAvailable(),
            entityResolutionAdvisorProvider == null ? null : (GraphRagEntityResolutionAdvisor) entityResolutionAdvisorProvider.getIfAvailable(),
            crossDocumentIndexServiceProvider == null ? null : crossDocumentIndexServiceProvider.getIfAvailable()
        );
    }

    public GraphRagBuildServiceImpl(SuperAgentKgEntityMapper entityMapper,
                                    SuperAgentKgRelationMapper relationMapper,
                                    SuperAgentKgEvidenceMapper evidenceMapper,
                                    SuperAgentKgCommunityMapper communityMapper,
                                    RagToolsClient ragToolsClient,
                                    ObjectMapper objectMapper,
                                    UidGenerator uidGenerator,
                                    GraphRagBuildProperties buildProperties,
                                    RedisLeaseManager redisLeaseManager,
                                    GraphRagBuildCheckpointService checkpointService,
                                    TransactionTemplate transactionTemplate) {
        this(
            entityMapper,
            relationMapper,
            evidenceMapper,
            communityMapper,
            ragToolsClient,
            objectMapper,
            uidGenerator,
            buildProperties,
            redisLeaseManager,
            checkpointService,
            transactionTemplate,
            (GraphRagExtractionAdvisor) null,
            (GraphRagCommunityReportAdvisor) null,
            (GraphRagEntityResolutionAdvisor) null,
            null
        );
    }

    GraphRagBuildServiceImpl(SuperAgentKgEntityMapper entityMapper,
                             SuperAgentKgRelationMapper relationMapper,
                             SuperAgentKgEvidenceMapper evidenceMapper,
                             SuperAgentKgCommunityMapper communityMapper,
                             RagToolsClient ragToolsClient,
                             ObjectMapper objectMapper,
                             UidGenerator uidGenerator,
                             GraphRagBuildProperties buildProperties,
                             RedisLeaseManager redisLeaseManager,
                             GraphRagBuildCheckpointService checkpointService,
                             TransactionTemplate transactionTemplate,
                             GraphRagExtractionAdvisor extractionAdvisor,
                             GraphRagCommunityReportAdvisor communityReportAdvisor,
                             GraphRagEntityResolutionAdvisor entityResolutionAdvisor,
                             GraphRagCrossDocumentIndexService crossDocumentIndexService) {
        this.entityMapper = entityMapper;
        this.relationMapper = relationMapper;
        this.evidenceMapper = evidenceMapper;
        this.communityMapper = communityMapper;
        this.ragToolsClient = ragToolsClient;
        this.objectMapper = objectMapper;
        this.uidGenerator = uidGenerator;
        this.buildProperties = buildProperties;
        this.redisLeaseManager = redisLeaseManager;
        this.checkpointService = checkpointService;
        this.transactionTemplate = transactionTemplate;
        this.extractionAdvisor = extractionAdvisor;
        this.communityReportAdvisor = communityReportAdvisor;
        this.entityResolutionAdvisor = entityResolutionAdvisor;
        this.crossDocumentIndexService = crossDocumentIndexService;
    }

    @Override
    public GraphRagBuildResult rebuildDocumentGraph(Long documentId, Long taskId, List<SuperAgentDocumentChunk> chunks) {
        if (documentId == null || taskId == null) {
            return GraphRagBuildResult.builder().build();
        }
        if (CollUtil.isEmpty(chunks)) {
            GraphRagBuildResult result = replaceGraph(documentId, taskId, new RagToolsGraphExtractResponse());
            refreshCrossDocumentIndex(documentId, taskId);
            return result;
        }

        int maxAttempts = maxAttempts();
        String leaseKey = leaseKey(taskId);
        String ownerToken = UUID.randomUUID().toString();
        Duration leaseTtl = leaseTtl();
        boolean leaseAcquired = acquireLease(documentId, taskId, leaseKey, ownerToken, leaseTtl, maxAttempts);

        try {
            return rebuildWithRetry(documentId, taskId, chunks, leaseKey, ownerToken, leaseTtl, maxAttempts);
        }
        finally {
            releaseLease(leaseAcquired, leaseKey, ownerToken, documentId, taskId);
        }
    }

    private GraphRagBuildResult rebuildWithRetry(Long documentId,
                                                 Long taskId,
                                                 List<SuperAgentDocumentChunk> chunks,
                                                 String leaseKey,
                                                 String ownerToken,
                                                 Duration leaseTtl,
                                                 int maxAttempts) {
        RagToolsGraphExtractRequest request = buildRequest(documentId, taskId, chunks);
        if (CollUtil.isEmpty(request.getChunks())) {
            checkpointService.markRunning(documentId, taskId, "EMPTY_INPUT", 0, maxAttempts, metadata(
                "chunkCount", 0
            ));
            GraphRagBuildResult result = replaceGraph(documentId, taskId, new RagToolsGraphExtractResponse());
            refreshCrossDocumentIndex(documentId, taskId);
            checkpointService.markSuccess(documentId, taskId, result, 0, maxAttempts);
            return result;
        }
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String stage = "EXTRACTING";
            try {
                checkpointService.markRunning(documentId, taskId, stage, attempt, maxAttempts, metadata(
                    "chunkCount", size(request.getChunks()),
                    "leaseKey", leaseKey
                ));

                RagToolsGraphExtractResponse response = ragToolsClient.extractGraph(request);
                if (response == null) {
                    throw new IllegalStateException("Python GraphRAG 抽取接口返回为空。");
                }
                response = applyGraphExtractionAdvice(documentId, taskId, request, response);

                stage = "EXTRACTED";
                checkpointService.markRunning(documentId, taskId, stage, attempt, maxAttempts, extractionCheckpointMetadata(response));

                renewLeaseOrFail(leaseKey, ownerToken, leaseTtl);

                stage = "PERSISTING";
                checkpointService.markRunning(documentId, taskId, stage, attempt, maxAttempts, extractionCheckpointMetadata(response));

                GraphRagBuildResult result = replaceGraph(documentId, taskId, response);
                refreshCrossDocumentIndex(documentId, taskId);
                checkpointService.markSuccess(documentId, taskId, result, attempt, maxAttempts);
                log.info("GraphRAG 实体关系图谱构建完成: documentId={}, taskId={}, attempt={}, result={}",
                    documentId, taskId, attempt, result);
                return result;
            }
            catch (GraphRagBuildLeaseLostException exception) {
                checkpointService.markFailure(documentId, taskId, stage, attempt, maxAttempts, exception);
                throw exception;
            }
            catch (RuntimeException exception) {
                lastException = exception;
                if (attempt >= maxAttempts) {
                    checkpointService.markFailure(documentId, taskId, stage, attempt, maxAttempts, exception);
                    throw exception;
                }
                long backoffMillis = retryBackoffMillis(attempt);
                checkpointService.markRetry(documentId, taskId, stage, attempt, maxAttempts, backoffMillis, exception);
                sleepBeforeRetry(documentId, taskId, stage, attempt, maxAttempts, backoffMillis);
            }
        }
        throw lastException == null ? new IllegalStateException("GraphRAG 构建未执行。") : lastException;
    }

    private void refreshCrossDocumentIndex(Long documentId, Long taskId) {
        if (crossDocumentIndexService == null) {
            return;
        }
        try {
            crossDocumentIndexService.rebuildAll();
        }
        catch (RuntimeException exception) {
            log.warn("GraphRAG 跨文档派生索引刷新失败，保留本次文档 KG 入库结果: documentId={}, taskId={}, message={}",
                documentId, taskId, exception.getMessage());
        }
    }

    private GraphRagBuildResult replaceGraph(Long documentId,
                                             Long taskId,
                                             RagToolsGraphExtractResponse response) {
        PreparedGraph preparedGraph = prepareGraph(documentId, taskId, response);
        return transactionTemplate.execute(status -> {
            deleteByTaskInternal(documentId, taskId);
            insertEntities(preparedGraph.entities().entitiesById().values());
            insertRelations(preparedGraph.relations().relationsById().values());
            insertEvidences(preparedGraph.evidences().evidencesById().values());
            insertCommunities(preparedGraph.communities());
            return GraphRagBuildResult.builder()
                .entityCount(preparedGraph.entities().entitiesById().size())
                .relationCount(preparedGraph.relations().relationsById().size())
                .evidenceCount(preparedGraph.evidences().evidencesById().size())
                .communityCount(preparedGraph.communities().size())
                .build();
        });
    }

    private Map<String, Object> extractionCheckpointMetadata(RagToolsGraphExtractResponse response) {
        return metadata(
            "entityCount", size(response == null ? null : response.getEntities()),
            "relationCount", size(response == null ? null : response.getRelations()),
            "evidenceCount", size(response == null ? null : response.getEvidences()),
            "communityCount", size(response == null ? null : response.getCommunities()),
            "extractorMetadata", response == null ? null : response.getMetadata()
        );
    }

    private PreparedGraph prepareGraph(Long documentId,
                                       Long taskId,
                                       RagToolsGraphExtractResponse response) {
        SavedEntities savedEntities = prepareEntities(documentId, taskId, response.getEntities());
        SavedRelations savedRelations = prepareRelations(documentId, taskId, response.getRelations(), savedEntities.sourceIdToEntityId());
        GraphRankSnapshot rankSnapshot = enrichGraphRankMetadata(savedEntities.entitiesById(), savedRelations.relationsById());
        SavedEvidences savedEvidences = prepareEvidences(documentId, taskId, response.getEvidences(),
            savedEntities.sourceIdToEntityId(), savedRelations.sourceIdToRelationId());
        List<SuperAgentKgCommunity> communities = prepareCommunities(documentId, taskId, response.getCommunities(),
            savedEntities.sourceIdToEntityId(),
            savedRelations.sourceIdToRelationId(),
            savedEvidences.sourceIdToEvidenceId(),
            rankSnapshot,
            savedEntities.entitiesById(),
            savedRelations.relationsById(),
            savedEvidences.evidencesById());
        return new PreparedGraph(savedEntities, savedRelations, savedEvidences, communities);
    }

    private RagToolsGraphExtractResponse applyGraphExtractionAdvice(Long documentId,
                                                                    Long taskId,
                                                                    RagToolsGraphExtractRequest request,
                                                                    RagToolsGraphExtractResponse response) {
        if (response == null) {
            return response;
        }
        if (extractionAdvisor == null) {
            return attachGraphExtractionAdvisorObservation(response, graphExtractionAdvisorObservation(
                false,
                false,
                "disabled",
                "ADVISOR_DISABLED",
                null,
                null,
                null,
                0
            ));
        }
        if (request == null || CollUtil.isEmpty(request.getChunks())) {
            return attachGraphExtractionAdvisorObservation(response, graphExtractionAdvisorObservation(
                true,
                false,
                "skipped",
                "NO_CHUNK_CONTEXT",
                null,
                null,
                null,
                0
            ));
        }
        GraphRagExtractionContext context = buildGraphExtractionContext(documentId, taskId, request);
        if (CollUtil.isEmpty(context.getChunks())) {
            return attachGraphExtractionAdvisorObservation(response, graphExtractionAdvisorObservation(
                true,
                false,
                "skipped",
                "NO_VALID_CHUNK_CONTEXT",
                null,
                null,
                null,
                0
            ));
        }

        Optional<GraphRagExtractionAdvice> advice;
        try {
            advice = extractionAdvisor.extract(context);
        }
        catch (RuntimeException exception) {
            log.warn("GraphRAG LLM 受控抽取增强失败，继续使用 Python 抽取结果: documentId={}, taskId={}, message={}",
                documentId, taskId, exception.getMessage());
            return attachGraphExtractionAdvisorObservation(response, graphExtractionAdvisorObservation(
                true,
                true,
                "failed",
                "ADVISOR_FAILED",
                null,
                null,
                exception.getMessage(),
                size(context.getChunks())
            ));
        }
        if (advice.isEmpty()) {
            return attachGraphExtractionAdvisorObservation(response, graphExtractionAdvisorObservation(
                true,
                true,
                "empty",
                "EMPTY_ADVICE",
                null,
                null,
                null,
                size(context.getChunks())
            ));
        }
        GraphRagExtractionAdvice extractionAdvice = advice.get();
        if (!Boolean.TRUE.equals(extractionAdvice.getGraphable())) {
            return attachGraphExtractionAdvisorObservation(response, graphExtractionAdvisorObservation(
                true,
                true,
                "not_graphable",
                StrUtil.blankToDefault(extractionAdvice.getReason(), "NOT_GRAPHABLE"),
                extractionAdvice,
                null,
                null,
                size(context.getChunks())
            ));
        }

        GraphExtractionValidation validation = validateGraphExtractionAdvice(extractionAdvice, context);
        if (!validation.enhanced()) {
            log.info("GraphRAG 受控抽取增强被拒绝: documentId={}, taskId={}, reason={}",
                documentId, taskId, validation.rejectedReason());
            return attachGraphExtractionAdvisorObservation(response, graphExtractionAdvisorObservation(
                true,
                true,
                "rejected",
                validation.rejectedReason(),
                extractionAdvice,
                validation,
                null,
                size(context.getChunks())
            ));
        }
        RagToolsGraphExtractResponse merged = mergeGraphExtractionResponse(response, validation);
        return attachGraphExtractionAdvisorObservation(merged, graphExtractionAdvisorObservation(
            true,
            true,
            "accepted",
            validation.reason(),
            extractionAdvice,
            validation,
            null,
            size(context.getChunks())
        ));
    }

    private RagToolsGraphExtractResponse attachGraphExtractionAdvisorObservation(RagToolsGraphExtractResponse response,
                                                                                Map<String, Object> observation) {
        if (response == null || observation == null || observation.isEmpty()) {
            return response;
        }
        Map<String, Object> metadata = copyMetadata(response.getMetadata());
        if (metadata == null) {
            metadata = new LinkedHashMap<>();
        }
        metadata.put(GRAPH_EXTRACTION_ADVISOR_METADATA_KEY, observation);
        response.setMetadata(metadata);
        return response;
    }

    private Map<String, Object> graphExtractionAdvisorObservation(boolean enabled,
                                                                  boolean called,
                                                                  String status,
                                                                  String reason,
                                                                  GraphRagExtractionAdvice advice,
                                                                  GraphExtractionValidation validation,
                                                                  String errorMessage,
                                                                  int contextChunkCount) {
        Map<String, Object> observation = metadata(
            "strategy", GRAPH_EXTRACTION_STRATEGY_LLM,
            "enabled", enabled,
            "called", called,
            "status", status,
            "reason", limit(reason, 300),
            "contextChunkCount", contextChunkCount
        );
        if (StrUtil.isNotBlank(errorMessage)) {
            observation.put("errorMessage", limit(errorMessage, 300));
        }
        if (advice != null) {
            observation.put("graphable", Boolean.TRUE.equals(advice.getGraphable()));
            observation.put("adviceConfidence", rounded(bounded(toDouble(advice.getConfidence(), 0D))));
            observation.put("adviceEntityCount", size(advice.getEntities()));
            observation.put("adviceRelationCount", size(advice.getRelations()));
            observation.put("adviceEvidenceCount", size(advice.getEvidences()));
        }
        if (validation != null) {
            observation.put("acceptedEntityCount", size(validation.entities()));
            observation.put("acceptedRelationCount", size(validation.relations()));
            observation.put("acceptedEvidenceCount", size(validation.evidences()));
            Map<String, Long> mappingStatusCounts = relationTypeMappingStatusCounts(validation.relations());
            if (!mappingStatusCounts.isEmpty()) {
                observation.put("relationTypeMappingStatusCounts", mappingStatusCounts);
                observation.put("downgradedRelationCount", mappingStatusCounts.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && entry.getKey().startsWith("downgraded"))
                    .mapToLong(Map.Entry::getValue)
                    .sum());
            }
            observation.put("accepted", validation.enhanced());
            if (StrUtil.isNotBlank(validation.rejectedReason())) {
                observation.put("rejectedReason", validation.rejectedReason());
            }
            if (validation.confidence() > 0D) {
                observation.put("acceptedConfidence", rounded(validation.confidence()));
            }
        }
        return observation;
    }

    private Map<String, Long> relationTypeMappingStatusCounts(List<RagToolsGraphExtractResponse.Relation> relations) {
        LinkedHashMap<String, Long> counts = new LinkedHashMap<>();
        for (RagToolsGraphExtractResponse.Relation relation : relations) {
            if (relation == null || relation.getMetadata() == null) {
                continue;
            }
            String status = String.valueOf(relation.getMetadata().getOrDefault("relationTypeMappingStatus", ""));
            if (StrUtil.isBlank(status)) {
                continue;
            }
            counts.merge(status, 1L, Long::sum);
        }
        return counts;
    }

    private GraphRagExtractionContext buildGraphExtractionContext(Long documentId,
                                                                 Long taskId,
                                                                 RagToolsGraphExtractRequest request) {
        List<GraphRagExtractionContext.ChunkItem> chunks = new ArrayList<>();
        for (RagToolsGraphExtractRequest.Chunk chunk : request.getChunks()) {
            if (chunk == null || chunk.getChunkId() == null || StrUtil.isBlank(chunk.getText())) {
                continue;
            }
            chunks.add(GraphRagExtractionContext.ChunkItem.builder()
                .chunkId(chunk.getChunkId())
                .parentBlockId(chunk.getParentBlockId())
                .chunkNo(chunk.getChunkNo())
                .chunkType(chunk.getChunkType())
                .title(chunk.getTitle())
                .sectionPath(chunk.getSectionPath())
                .pageNo(chunk.getPageNo())
                .pageRange(chunk.getPageRange())
                .text(limit(chunk.getText(), GRAPH_EXTRACTION_CONTEXT_TEXT_LIMIT))
                .build());
            if (chunks.size() >= GRAPH_EXTRACTION_CONTEXT_CHUNK_LIMIT) {
                break;
            }
        }
        return GraphRagExtractionContext.builder()
            .documentId(documentId)
            .taskId(taskId)
            .chunks(chunks)
            .build();
    }

    private GraphExtractionValidation validateGraphExtractionAdvice(GraphRagExtractionAdvice advice,
                                                                    GraphRagExtractionContext context) {
        if (advice == null) {
            return GraphExtractionValidation.rejected("EMPTY_ADVICE");
        }
        double adviceConfidence = bounded(toDouble(advice.getConfidence(), 0D));
        if (adviceConfidence < GRAPH_EXTRACTION_CONFIDENCE_THRESHOLD || CollUtil.isEmpty(advice.getEntities())) {
            return GraphExtractionValidation.rejected("LOW_CONFIDENCE_OR_NO_ENTITIES");
        }

        Map<Long, GraphRagExtractionContext.ChunkItem> chunkById = new LinkedHashMap<>();
        for (GraphRagExtractionContext.ChunkItem chunk : context.getChunks()) {
            if (chunk != null && chunk.getChunkId() != null && StrUtil.isNotBlank(chunk.getText())) {
                chunkById.put(chunk.getChunkId(), chunk);
            }
        }

        Map<String, EntityExtractionCandidate> entityCandidates = new LinkedHashMap<>();
        for (GraphRagExtractionAdvice.EntityItem entity : advice.getEntities()) {
            EntityExtractionCandidate candidate = validateGraphExtractionEntity(entity, chunkById);
            if (candidate != null) {
                entityCandidates.put(candidate.localEntityId(), candidate);
            }
            if (entityCandidates.size() >= GRAPH_EXTRACTION_ENTITY_LIMIT) {
                break;
            }
        }
        if (entityCandidates.isEmpty()) {
            return GraphExtractionValidation.rejected("NO_VALID_ENTITY");
        }

        Map<String, RelationExtractionCandidate> relationCandidates = new LinkedHashMap<>();
        if (CollUtil.isNotEmpty(advice.getRelations())) {
            for (GraphRagExtractionAdvice.RelationItem relation : advice.getRelations()) {
                RelationExtractionCandidate candidate = validateGraphExtractionRelation(relation, entityCandidates);
                if (candidate != null) {
                    relationCandidates.put(candidate.localRelationId(), candidate);
                }
                if (relationCandidates.size() >= GRAPH_EXTRACTION_RELATION_LIMIT) {
                    break;
                }
            }
        }

        Map<String, List<GraphRagExtractionAdvice.EvidenceItem>> entityEvidenceMap = new LinkedHashMap<>();
        Map<String, List<GraphRagExtractionAdvice.EvidenceItem>> relationEvidenceMap = new LinkedHashMap<>();
        int acceptedEvidenceCount = 0;
        if (CollUtil.isNotEmpty(advice.getEvidences())) {
            for (GraphRagExtractionAdvice.EvidenceItem evidence : advice.getEvidences()) {
                if (acceptedEvidenceCount >= GRAPH_EXTRACTION_EVIDENCE_LIMIT) {
                    break;
                }
                if (!validGraphExtractionEvidence(evidence, chunkById)) {
                    continue;
                }
                boolean accepted = false;
                String entityId = StrUtil.blankToDefault(evidence.getEntityId(), "");
                String relationId = StrUtil.blankToDefault(evidence.getRelationId(), "");
                if (entityCandidates.containsKey(entityId)) {
                    entityEvidenceMap.computeIfAbsent(entityId, ignored -> new ArrayList<>()).add(evidence);
                    accepted = true;
                }
                if (relationCandidates.containsKey(relationId)
                    && isRelationEvidenceGrounded(evidence, relationCandidates.get(relationId), entityCandidates)) {
                    relationEvidenceMap.computeIfAbsent(relationId, ignored -> new ArrayList<>()).add(evidence);
                    accepted = true;
                }
                if (accepted) {
                    acceptedEvidenceCount++;
                }
            }
        }

        Set<String> relationGroundedEntityIds = new LinkedHashSet<>();
        for (String localRelationId : relationEvidenceMap.keySet()) {
            RelationExtractionCandidate relation = relationCandidates.get(localRelationId);
            if (relation != null) {
                relationGroundedEntityIds.add(relation.sourceEntityId());
                relationGroundedEntityIds.add(relation.targetEntityId());
            }
        }
        entityCandidates.values().removeIf(candidate -> CollUtil.isEmpty(entityEvidenceMap.get(candidate.localEntityId()))
            && !relationGroundedEntityIds.contains(candidate.localEntityId()));
        relationCandidates.values().removeIf(candidate -> CollUtil.isEmpty(relationEvidenceMap.get(candidate.localRelationId()))
            || !entityCandidates.containsKey(candidate.sourceEntityId())
            || !entityCandidates.containsKey(candidate.targetEntityId()));
        if (entityCandidates.isEmpty()) {
            return GraphExtractionValidation.rejected("NO_GROUNDED_ENTITY");
        }

        Map<String, String> localEntityIdToGlobalId = new LinkedHashMap<>();
        List<RagToolsGraphExtractResponse.Entity> entities = new ArrayList<>();
        int entityIndex = 1;
        for (EntityExtractionCandidate candidate : entityCandidates.values()) {
            String sourceEntityId = generatedGraphExtractionId("LLM_ENT", candidate.localEntityId(), localEntityIdToGlobalId, entityIndex++);
            localEntityIdToGlobalId.put(candidate.localEntityId(), sourceEntityId);
            RagToolsGraphExtractResponse.Entity entity = new RagToolsGraphExtractResponse.Entity();
            entity.setId(sourceEntityId);
            entity.setName(candidate.name());
            entity.setNormalizedName(candidate.normalizedName());
            entity.setAliases(new ArrayList<>(candidate.aliases()));
            entity.setType(candidate.entityType());
            entity.setDescription(candidate.description());
            entity.setConfidence(candidate.confidence());
            entity.setSourceChunkIds(new ArrayList<>(candidate.sourceChunkIds()));
            entity.setEvidenceIds(new ArrayList<>());
            entity.setMetadata(metadata(
                "sourceType", GRAPH_EXTRACTION_STRATEGY_LLM,
                "sourceEntityId", candidate.localEntityId(),
                "sourceChunkIds", candidate.sourceChunkIds(),
                "confidence", candidate.confidence(),
                "reason", candidate.reason()
            ));
            entities.add(entity);
        }

        Map<String, String> localRelationIdToGlobalId = new LinkedHashMap<>();
        List<RagToolsGraphExtractResponse.Relation> relations = new ArrayList<>();
        int relationIndex = 1;
        for (RelationExtractionCandidate candidate : relationCandidates.values()) {
            String sourceEntityId = localEntityIdToGlobalId.get(candidate.sourceEntityId());
            String targetEntityId = localEntityIdToGlobalId.get(candidate.targetEntityId());
            if (StrUtil.isBlank(sourceEntityId) || StrUtil.isBlank(targetEntityId) || Objects.equals(sourceEntityId, targetEntityId)) {
                continue;
            }
            String relationId = generatedGraphExtractionId("LLM_REL", candidate.localRelationId(), localRelationIdToGlobalId, relationIndex++);
            localRelationIdToGlobalId.put(candidate.localRelationId(), relationId);
            RagToolsGraphExtractResponse.Relation relation = new RagToolsGraphExtractResponse.Relation();
            relation.setId(relationId);
            relation.setSourceEntityId(sourceEntityId);
            relation.setTargetEntityId(targetEntityId);
            relation.setRelationType(candidate.relationType());
            relation.setDescription(candidate.description());
            relation.setWeight(candidate.weight());
            relation.setConfidence(candidate.confidence());
            relation.setEvidenceIds(new ArrayList<>());
            relation.setMetadata(metadata(
                "sourceType", GRAPH_EXTRACTION_STRATEGY_LLM,
                "sourceRelationId", candidate.localRelationId(),
                "sourceEntityIds", List.of(candidate.sourceEntityId(), candidate.targetEntityId()),
                "requestedRelationType", candidate.requestedRelationType(),
                "effectiveRelationType", candidate.relationType(),
                "supportMode", candidate.supportMode(),
                "predicateQuoteText", candidate.predicateQuoteText(),
                "relationTypeReason", candidate.relationTypeReason(),
                "relationTypeMappingStatus", candidate.relationTypeMappingStatus(),
                "relationTypeMappingReason", candidate.relationTypeMappingReason(),
                "confidence", candidate.confidence()
            ));
            relations.add(relation);
        }

        List<RagToolsGraphExtractResponse.Evidence> evidences = new ArrayList<>();
        int evidenceIndex = 1;
        for (Map.Entry<String, List<GraphRagExtractionAdvice.EvidenceItem>> entry : entityEvidenceMap.entrySet()) {
            String globalEntityId = localEntityIdToGlobalId.get(entry.getKey());
            if (StrUtil.isBlank(globalEntityId)) {
                continue;
            }
            for (GraphRagExtractionAdvice.EvidenceItem evidenceItem : entry.getValue()) {
                RagToolsGraphExtractResponse.Evidence evidence = buildGraphExtractionEvidence(evidenceItem, globalEntityId, null, null, chunkById, evidenceIndex++);
                if (evidence != null) {
                    evidences.add(evidence);
                    addEvidenceIdToEntity(entities, globalEntityId, evidence.getId());
                }
            }
        }
        for (Map.Entry<String, List<GraphRagExtractionAdvice.EvidenceItem>> entry : relationEvidenceMap.entrySet()) {
            String globalRelationId = localRelationIdToGlobalId.get(entry.getKey());
            if (StrUtil.isBlank(globalRelationId)) {
                continue;
            }
            RelationExtractionCandidate relationCandidate = relationCandidates.get(entry.getKey());
            for (GraphRagExtractionAdvice.EvidenceItem evidenceItem : entry.getValue()) {
                RagToolsGraphExtractResponse.Evidence evidence = buildGraphExtractionEvidence(evidenceItem, null, globalRelationId, relationCandidate, chunkById, evidenceIndex++);
                if (evidence != null) {
                    evidences.add(evidence);
                    addEvidenceIdToRelation(relations, globalRelationId, evidence.getId());
                }
            }
        }

        if (entities.isEmpty() || evidences.isEmpty()) {
            return GraphExtractionValidation.rejected("NO_PERSISTABLE_GRAPH_ITEMS");
        }
        return GraphExtractionValidation.accepted(entities, relations, evidences, adviceConfidence, advice.getReason());
    }

    private EntityExtractionCandidate validateGraphExtractionEntity(GraphRagExtractionAdvice.EntityItem entity,
                                                                    Map<Long, GraphRagExtractionContext.ChunkItem> chunkById) {
        if (entity == null || StrUtil.isBlank(entity.getId()) || StrUtil.isBlank(entity.getName()) || entity.getConfidence() == null) {
            return null;
        }
        double confidence = bounded(toDouble(entity.getConfidence(), 0D));
        if (confidence < GRAPH_EXTRACTION_CONFIDENCE_THRESHOLD) {
            return null;
        }
        List<Long> sourceChunkIds = new ArrayList<>();
        if (CollUtil.isNotEmpty(entity.getSourceChunkIds())) {
            for (Long chunkId : entity.getSourceChunkIds()) {
                GraphRagExtractionContext.ChunkItem chunk = chunkById.get(chunkId);
                if (chunk != null && isEntityGrounded(entity, chunk.getText())) {
                    sourceChunkIds.add(chunkId);
                }
            }
        }
        if (sourceChunkIds.isEmpty()) {
            return null;
        }
        String entityType = normalizeGraphType(entity.getEntityType());
        if (StrUtil.isBlank(entityType)) {
            entityType = "CONCEPT";
        }
        String normalizedName = limit(StrUtil.blankToDefault(entity.getNormalizedName(), normalizeKey(entity.getName())), 500);
        return new EntityExtractionCandidate(
            entity.getId(),
            limit(entity.getName(), 500),
            normalizedName,
            entityType,
            new ArrayList<>(limitAliases(entity.getAliases(), entity.getName())),
            limit(StrUtil.blankToDefault(entity.getDescription(), ""), 1000),
            rounded(confidence),
            sourceChunkIds,
            limit(entity.getId(), 64),
            "llm.controlled.extract.v1"
        );
    }

    private RelationExtractionCandidate validateGraphExtractionRelation(GraphRagExtractionAdvice.RelationItem relation,
                                                                        Map<String, EntityExtractionCandidate> entityCandidates) {
        if (relation == null || StrUtil.isBlank(relation.getId()) || StrUtil.isBlank(relation.getSourceEntityId())
            || StrUtil.isBlank(relation.getTargetEntityId()) || StrUtil.isBlank(relation.getRelationType())
            || relation.getConfidence() == null) {
            return null;
        }
        if (!entityCandidates.containsKey(relation.getSourceEntityId())
            || !entityCandidates.containsKey(relation.getTargetEntityId())
            || Objects.equals(relation.getSourceEntityId(), relation.getTargetEntityId())) {
            return null;
        }
        double confidence = bounded(toDouble(relation.getConfidence(), 0D));
        if (confidence < GRAPH_EXTRACTION_CONFIDENCE_THRESHOLD) {
            return null;
        }
        String relationType = normalizeGraphType(relation.getRelationType());
        if (StrUtil.isBlank(relationType)) {
            return null;
        }
        String supportMode = normalizeGraphType(relation.getSupportMode());
        if (StrUtil.isBlank(supportMode)) {
            supportMode = "UNKNOWN";
        }
        String predicateQuoteText = limit(StrUtil.blankToDefault(relation.getPredicateQuoteText(), ""), 360);
        String relationTypeReason = limit(StrUtil.blankToDefault(relation.getRelationTypeReason(), ""), 600);
        String effectiveRelationType = relationType;
        String mappingStatus = isStrongRelationType(relationType) ? "accepted_strong" : "accepted_weak";
        String mappingReason = isStrongRelationType(relationType)
            ? "explicit action support accepted"
            : "weak relation type accepted";
        if (isStrongRelationType(relationType)) {
            EntityExtractionCandidate source = entityCandidates.get(relation.getSourceEntityId());
            EntityExtractionCandidate target = entityCandidates.get(relation.getTargetEntityId());
            if (!SUPPORT_MODE_EXPLICIT_ACTION.equals(supportMode)) {
                effectiveRelationType = RELATION_TYPE_ASSOCIATED_WITH;
                mappingStatus = "downgraded_weak_support";
                mappingReason = "strong relation requires EXPLICIT_ACTION supportMode";
            }
            else if (!isPredicateQuoteTextMeaningful(predicateQuoteText, source, target)) {
                effectiveRelationType = RELATION_TYPE_ASSOCIATED_WITH;
                mappingStatus = "downgraded_missing_predicate";
                mappingReason = "strong relation requires predicateQuoteText beyond entity mentions";
            }
        }
        double weight = bounded(toDouble(relation.getWeight(), Math.max(0.70D, confidence)));
        return new RelationExtractionCandidate(
            relation.getId(),
            relation.getSourceEntityId(),
            relation.getTargetEntityId(),
            effectiveRelationType,
            relationType,
            supportMode,
            predicateQuoteText,
            relationTypeReason,
            mappingStatus,
            mappingReason,
            limit(StrUtil.blankToDefault(relation.getDescription(), ""), 1000),
            rounded(weight),
            rounded(confidence)
        );
    }

    private boolean validGraphExtractionEvidence(GraphRagExtractionAdvice.EvidenceItem evidence,
                                                 Map<Long, GraphRagExtractionContext.ChunkItem> chunkById) {
        if (evidence == null || StrUtil.isBlank(evidence.getId()) || evidence.getChunkId() == null || StrUtil.isBlank(evidence.getQuoteText())) {
            return false;
        }
        GraphRagExtractionContext.ChunkItem chunk = chunkById.get(evidence.getChunkId());
        if (chunk == null || !isQuoteGrounded(evidence.getQuoteText(), chunk.getText())) {
            return false;
        }
        double confidence = bounded(toDouble(evidence.getConfidence(), 0D));
        return confidence >= GRAPH_EXTRACTION_CONFIDENCE_THRESHOLD;
    }

    private RagToolsGraphExtractResponse.Evidence buildGraphExtractionEvidence(GraphRagExtractionAdvice.EvidenceItem evidenceItem,
                                                                               String entityId,
                                                                               String relationId,
                                                                               RelationExtractionCandidate relation,
                                                                               Map<Long, GraphRagExtractionContext.ChunkItem> chunkById,
                                                                               int index) {
        if (evidenceItem == null || StrUtil.isBlank(evidenceItem.getId()) || evidenceItem.getChunkId() == null || StrUtil.isBlank(evidenceItem.getQuoteText())) {
            return null;
        }
        GraphRagExtractionContext.ChunkItem chunk = chunkById.get(evidenceItem.getChunkId());
        if (chunk == null || !isQuoteGrounded(evidenceItem.getQuoteText(), chunk.getText())) {
            return null;
        }
        if (StrUtil.isBlank(entityId) && StrUtil.isBlank(relationId)) {
            return null;
        }
        RagToolsGraphExtractResponse.Evidence evidence = new RagToolsGraphExtractResponse.Evidence();
        evidence.setId(generatedGraphExtractionEvidenceId(evidenceItem.getId(), index));
        evidence.setEntityId(entityId);
        evidence.setRelationId(relationId);
        evidence.setChunkId(chunk.getChunkId());
        evidence.setParentBlockId(chunk.getParentBlockId());
        evidence.setQuoteText(limit(evidenceItem.getQuoteText(), 360));
        evidence.setPageNo(chunk.getPageNo());
        evidence.setPageRange(limit(chunk.getPageRange(), 64));
        evidence.setSectionPath(limit(chunk.getSectionPath(), 1000));
        evidence.setMetadata(metadata(
            "sourceType", GRAPH_EXTRACTION_STRATEGY_LLM,
            "sourceEvidenceId", evidenceItem.getId(),
            "sourceChunkId", chunk.getChunkId(),
            "requestedRelationType", relation == null ? null : relation.requestedRelationType(),
            "effectiveRelationType", relation == null ? null : relation.relationType(),
            "supportMode", relation == null ? null : relation.supportMode(),
            "predicateQuoteText", relation == null ? null : relation.predicateQuoteText(),
            "relationTypeReason", relation == null ? null : relation.relationTypeReason(),
            "relationTypeMappingStatus", relation == null ? null : relation.relationTypeMappingStatus(),
            "relationTypeMappingReason", relation == null ? null : relation.relationTypeMappingReason(),
            "confidence", rounded(bounded(toDouble(evidenceItem.getConfidence(), 0D)))
        ));
        return evidence;
    }

    private void addEvidenceIdToEntity(List<RagToolsGraphExtractResponse.Entity> entities, String entityId, String evidenceId) {
        for (RagToolsGraphExtractResponse.Entity entity : entities) {
            if (entity != null && Objects.equals(entity.getId(), entityId)) {
                if (entity.getEvidenceIds() == null) {
                    entity.setEvidenceIds(new ArrayList<>());
                }
                entity.getEvidenceIds().add(evidenceId);
                return;
            }
        }
    }

    private void addEvidenceIdToRelation(List<RagToolsGraphExtractResponse.Relation> relations, String relationId, String evidenceId) {
        for (RagToolsGraphExtractResponse.Relation relation : relations) {
            if (relation != null && Objects.equals(relation.getId(), relationId)) {
                if (relation.getEvidenceIds() == null) {
                    relation.setEvidenceIds(new ArrayList<>());
                }
                relation.getEvidenceIds().add(evidenceId);
                return;
            }
        }
    }

    private RagToolsGraphExtractResponse mergeGraphExtractionResponse(RagToolsGraphExtractResponse baseResponse,
                                                                      GraphExtractionValidation validation) {
        RagToolsGraphExtractResponse merged = new RagToolsGraphExtractResponse();
        merged.setEntities(mergeList(baseResponse.getEntities(), validation.entities()));
        merged.setRelations(mergeList(baseResponse.getRelations(), validation.relations()));
        merged.setEvidences(mergeList(baseResponse.getEvidences(), validation.evidences()));
        merged.setCommunities(baseResponse.getCommunities() == null ? new ArrayList<>() : new ArrayList<>(baseResponse.getCommunities()));
        merged.setMetadata(baseResponse.getMetadata());
        return merged;
    }

    private <T> List<T> mergeList(List<T> baseValues, List<T> addedValues) {
        List<T> result = new ArrayList<>();
        if (CollUtil.isNotEmpty(baseValues)) {
            result.addAll(baseValues);
        }
        if (CollUtil.isNotEmpty(addedValues)) {
            result.addAll(addedValues);
        }
        return result;
    }

    private boolean isEntityGrounded(GraphRagExtractionAdvice.EntityItem entity, String chunkText) {
        if (entity == null || StrUtil.isBlank(chunkText)) {
            return false;
        }
        String normalizedText = normalizeKey(chunkText);
        if (normalizedText.contains(normalizeKey(entity.getName()))) {
            return true;
        }
        if (StrUtil.isNotBlank(entity.getNormalizedName()) && normalizedText.contains(normalizeKey(entity.getNormalizedName()))) {
            return true;
        }
        if (CollUtil.isNotEmpty(entity.getAliases())) {
            for (String alias : entity.getAliases()) {
                if (StrUtil.isNotBlank(alias) && normalizedText.contains(normalizeKey(alias))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isQuoteGrounded(String quoteText, String chunkText) {
        if (StrUtil.isBlank(quoteText) || StrUtil.isBlank(chunkText)) {
            return false;
        }
        return normalizeKey(chunkText).contains(normalizeKey(quoteText));
    }

    private boolean isRelationEvidenceGrounded(GraphRagExtractionAdvice.EvidenceItem evidence,
                                               RelationExtractionCandidate relation,
                                               Map<String, EntityExtractionCandidate> entityCandidates) {
        if (evidence == null || relation == null || StrUtil.isBlank(evidence.getQuoteText())) {
            return false;
        }
        EntityExtractionCandidate source = entityCandidates.get(relation.sourceEntityId());
        EntityExtractionCandidate target = entityCandidates.get(relation.targetEntityId());
        if (source == null || target == null) {
            return false;
        }
        String normalizedQuote = normalizeKey(evidence.getQuoteText());
        if (!candidateMentionedInText(source, normalizedQuote) || !candidateMentionedInText(target, normalizedQuote)) {
            return false;
        }
        return !isStrongRelationType(relation.relationType())
            || isPredicateQuoteGrounded(relation.predicateQuoteText(), normalizedQuote, source, target);
    }

    private boolean candidateMentionedInText(EntityExtractionCandidate candidate, String normalizedText) {
        if (candidate == null || StrUtil.isBlank(normalizedText)) {
            return false;
        }
        if (normalizedText.contains(normalizeKey(candidate.name()))) {
            return true;
        }
        if (normalizedText.contains(normalizeKey(candidate.normalizedName()))) {
            return true;
        }
        if (CollUtil.isNotEmpty(candidate.aliases())) {
            for (String alias : candidate.aliases()) {
                if (StrUtil.isNotBlank(alias) && normalizedText.contains(normalizeKey(alias))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isStrongRelationType(String relationType) {
        String normalized = normalizeGraphType(relationType);
        return StrUtil.isNotBlank(normalized)
            && !RELATION_TYPE_ASSOCIATED_WITH.equals(normalized)
            && !"RELATED_TO".equals(normalized);
    }

    private boolean isPredicateQuoteGrounded(String predicateQuoteText,
                                             String normalizedQuote,
                                             EntityExtractionCandidate source,
                                             EntityExtractionCandidate target) {
        if (!isPredicateQuoteTextMeaningful(predicateQuoteText, source, target)) {
            return false;
        }
        return normalizedQuote.contains(normalizeKey(predicateQuoteText));
    }

    private boolean isPredicateQuoteTextMeaningful(String predicateQuoteText,
                                                   EntityExtractionCandidate source,
                                                   EntityExtractionCandidate target) {
        String normalized = normalizeKey(predicateQuoteText);
        if (normalized.length() < 2) {
            return false;
        }
        String withoutEntityMentions = normalized;
        withoutEntityMentions = removeCandidateMentions(withoutEntityMentions, source);
        withoutEntityMentions = removeCandidateMentions(withoutEntityMentions, target);
        return withoutEntityMentions.length() >= 2;
    }

    private String removeCandidateMentions(String text, EntityExtractionCandidate candidate) {
        if (StrUtil.isBlank(text) || candidate == null) {
            return StrUtil.blankToDefault(text, "");
        }
        String result = removeNormalizedTerm(text, candidate.name());
        result = removeNormalizedTerm(result, candidate.normalizedName());
        if (CollUtil.isNotEmpty(candidate.aliases())) {
            for (String alias : candidate.aliases()) {
                result = removeNormalizedTerm(result, alias);
            }
        }
        return result;
    }

    private String removeNormalizedTerm(String text, String term) {
        String normalizedTerm = normalizeKey(term);
        if (StrUtil.isBlank(text) || StrUtil.isBlank(normalizedTerm)) {
            return StrUtil.blankToDefault(text, "");
        }
        return text.replace(normalizedTerm, "");
    }

    private String normalizeGraphType(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        String normalized = value.trim().toUpperCase()
            .replaceAll("[^A-Z0-9_]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
        return limit(normalized, 64);
    }

    private String generatedGraphExtractionId(String prefix,
                                              String localId,
                                              Map<String, String> existingIds,
                                              int index) {
        String candidate = prefix + "_" + limit(sanitizeGraphExtractionId(localId), 32);
        if (!existingIds.containsValue(candidate)) {
            return candidate;
        }
        return candidate + "_" + index;
    }

    private String generatedGraphExtractionEvidenceId(String localId, int index) {
        return "LLM_EV_" + index + "_" + limit(sanitizeGraphExtractionId(localId), 28);
    }

    private String sanitizeGraphExtractionId(String value) {
        if (StrUtil.isBlank(value)) {
            return "UNKNOWN";
        }
        return value.replaceAll("[^A-Za-z0-9_\\-]+", "_").replaceAll("_+", "_");
    }

    private boolean acquireLease(Long documentId,
                                 Long taskId,
                                 String leaseKey,
                                 String ownerToken,
                                 Duration leaseTtl,
                                 int maxAttempts) {
        if (!buildProperties.isLeaseEnabled()) {
            checkpointService.markRunning(documentId, taskId, "LOCK_SKIPPED", 0, maxAttempts, metadata(
                "leaseEnabled", false
            ));
            return false;
        }
        boolean acquired = redisLeaseManager.acquire(leaseKey, ownerToken, leaseTtl);
        if (!acquired) {
            IllegalStateException exception = new IllegalStateException("GraphRAG 构建租约已被占用，请稍后重试。");
            checkpointService.markRejected(documentId, taskId, "LOCK_CONFLICT", maxAttempts, exception.getMessage(), metadata(
                "leaseKey", leaseKey
            ));
            throw exception;
        }
        checkpointService.markRunning(documentId, taskId, "LOCK_ACQUIRED", 0, maxAttempts, metadata(
            "leaseKey", leaseKey,
            "leaseTtlSeconds", leaseTtl.getSeconds()
        ));
        return true;
    }

    private void renewLeaseOrFail(String leaseKey, String ownerToken, Duration leaseTtl) {
        if (!buildProperties.isLeaseEnabled()) {
            return;
        }
        boolean renewed = redisLeaseManager.renew(leaseKey, ownerToken, leaseTtl);
        if (!renewed) {
            throw new GraphRagBuildLeaseLostException("GraphRAG 构建租约已过期，停止持久化。");
        }
    }

    private void releaseLease(boolean leaseAcquired,
                              String leaseKey,
                              String ownerToken,
                              Long documentId,
                              Long taskId) {
        if (!leaseAcquired || !buildProperties.isLeaseEnabled()) {
            return;
        }
        try {
            boolean released = redisLeaseManager.release(leaseKey, ownerToken);
            if (!released) {
                log.warn("GraphRAG 构建租约释放失败或已过期: documentId={}, taskId={}, leaseKey={}",
                    documentId, taskId, leaseKey);
            }
        }
        catch (RuntimeException exception) {
            log.warn("GraphRAG 构建租约释放异常: documentId={}, taskId={}, leaseKey={}",
                documentId, taskId, leaseKey, exception);
        }
    }

    private int maxAttempts() {
        return Math.max(1, buildProperties.getMaxAttempts());
    }

    private Duration leaseTtl() {
        return Duration.ofSeconds(Math.max(1, buildProperties.getLeaseTtlSeconds()));
    }

    private long retryBackoffMillis(int attempt) {
        long baseBackoff = Math.max(0L, buildProperties.getRetryBackoffMillis());
        return baseBackoff * Math.max(1, attempt);
    }

    private void sleepBeforeRetry(Long documentId,
                                  Long taskId,
                                  String stage,
                                  int attempt,
                                  int maxAttempts,
                                  long backoffMillis) {
        if (backoffMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(backoffMillis);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            IllegalStateException interrupted = new IllegalStateException("GraphRAG 构建重试等待被中断。", exception);
            checkpointService.markFailure(documentId, taskId, "RETRY_INTERRUPTED", attempt, maxAttempts, interrupted);
            throw interrupted;
        }
    }

    private String leaseKey(Long taskId) {
        return "super-agent:document:graph-rag:build:" + taskId;
    }

    private int size(Collection<?> values) {
        return values == null ? 0 : values.size();
    }

    private static final class GraphRagBuildLeaseLostException extends RuntimeException {

        private GraphRagBuildLeaseLostException(String message) {
            super(message);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByTask(Long documentId, Long taskId) {
        if (documentId == null || taskId == null) {
            return;
        }
        deleteByTaskInternal(documentId, taskId);
        refreshCrossDocumentIndexAfterCommit(documentId, taskId);
    }

    private void deleteByTaskInternal(Long documentId, Long taskId) {
        if (documentId == null || taskId == null) {
            return;
        }
        communityMapper.delete(new LambdaQueryWrapper<SuperAgentKgCommunity>()
            .eq(SuperAgentKgCommunity::getDocumentId, documentId)
            .eq(SuperAgentKgCommunity::getTaskId, taskId));
        evidenceMapper.delete(new LambdaQueryWrapper<SuperAgentKgEvidence>()
            .eq(SuperAgentKgEvidence::getDocumentId, documentId)
            .eq(SuperAgentKgEvidence::getTaskId, taskId));
        relationMapper.delete(new LambdaQueryWrapper<SuperAgentKgRelation>()
            .eq(SuperAgentKgRelation::getDocumentId, documentId)
            .eq(SuperAgentKgRelation::getTaskId, taskId));
        entityMapper.delete(new LambdaQueryWrapper<SuperAgentKgEntity>()
            .eq(SuperAgentKgEntity::getDocumentId, documentId)
            .eq(SuperAgentKgEntity::getTaskId, taskId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        communityMapper.delete(new LambdaQueryWrapper<SuperAgentKgCommunity>()
            .eq(SuperAgentKgCommunity::getDocumentId, documentId));
        evidenceMapper.delete(new LambdaQueryWrapper<SuperAgentKgEvidence>()
            .eq(SuperAgentKgEvidence::getDocumentId, documentId));
        relationMapper.delete(new LambdaQueryWrapper<SuperAgentKgRelation>()
            .eq(SuperAgentKgRelation::getDocumentId, documentId));
        entityMapper.delete(new LambdaQueryWrapper<SuperAgentKgEntity>()
            .eq(SuperAgentKgEntity::getDocumentId, documentId));
        refreshCrossDocumentIndexAfterCommit(documentId);
    }

    private void refreshCrossDocumentIndexAfterCommit(Long documentId) {
        refreshCrossDocumentIndexAfterCommit(documentId, null);
    }

    private void refreshCrossDocumentIndexAfterCommit(Long documentId, Long taskId) {
        if (crossDocumentIndexService == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

                @Override
                public void afterCommit() {
                    rebuildCrossDocumentIndexAfterDelete(documentId, taskId);
                }
            });
            return;
        }
        rebuildCrossDocumentIndexAfterDelete(documentId, taskId);
    }

    private void rebuildCrossDocumentIndexAfterDelete(Long documentId, Long taskId) {
        try {
            crossDocumentIndexService.rebuildAll();
        }
        catch (RuntimeException exception) {
            log.warn("GraphRAG 删除后跨文档派生索引刷新失败，源 KG 删除已完成: documentId={}, taskId={}, message={}",
                documentId, taskId, exception.getMessage());
        }
    }

    private RagToolsGraphExtractRequest buildRequest(Long documentId, Long taskId, List<SuperAgentDocumentChunk> chunks) {
        RagToolsGraphExtractRequest request = new RagToolsGraphExtractRequest();
        request.setDocumentId(documentId);
        request.setTaskId(taskId);

        List<RagToolsGraphExtractRequest.Chunk> requestChunks = new ArrayList<>();
        for (SuperAgentDocumentChunk chunk : chunks) {
            if (chunk == null || chunk.getId() == null || StrUtil.isBlank(chunk.getChunkText())) {
                continue;
            }
            RagToolsGraphExtractRequest.Chunk requestChunk = new RagToolsGraphExtractRequest.Chunk();
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

    private SavedEntities prepareEntities(Long documentId,
                                          Long taskId,
                                          List<RagToolsGraphExtractResponse.Entity> entities) {
        Map<String, Long> entityIdMap = new LinkedHashMap<>();
        if (CollUtil.isEmpty(entities)) {
            return new SavedEntities(entityIdMap, new LinkedHashMap<>());
        }
        Map<String, EntityAccumulator> accumulators = new LinkedHashMap<>();
        Map<String, String> aliasIndex = new LinkedHashMap<>();
        Map<String, EntityResolutionDecision> entityResolutionDecisions = resolveEntityCanonicalKeyAdvice(entities);

        for (RagToolsGraphExtractResponse.Entity extracted : entities) {
            if (extracted == null || StrUtil.isBlank(extracted.getId()) || StrUtil.isBlank(extracted.getName())) {
                continue;
            }
            String sourceKey = canonicalEntityKey(extracted);
            EntityResolutionDecision resolutionDecision = entityResolutionDecisions.get(extracted.getId());
            String canonicalKey = resolutionDecision == null
                ? resolveEntityCanonicalKey(extracted, aliasIndex, accumulators.keySet(), sourceKey)
                : resolutionDecision.canonicalKey();
            EntityAccumulator accumulator = accumulators.computeIfAbsent(canonicalKey, EntityAccumulator::new);
            accumulator.merge(extracted);
            accumulator.applyResolutionAdvice(resolutionDecision);
            indexEntityAliases(aliasIndex, canonicalKey, accumulator);
        }

        Map<Long, SuperAgentKgEntity> entitiesById = new LinkedHashMap<>();
        for (EntityAccumulator accumulator : accumulators.values()) {
            Long entityId = uidGenerator.getUid();
            SuperAgentKgEntity entity = new SuperAgentKgEntity();
            entity.setId(entityId);
            entity.setDocumentId(documentId);
            entity.setTaskId(taskId);
            entity.setEntityKey(limit(accumulator.canonicalKey, 255));
            entity.setName(limit(accumulator.name, 500));
            entity.setNormalizedName(limit(accumulator.normalizedName, 500));
            entity.setEntityType(limit(accumulator.entityType, 64));
            entity.setDescription(limit(accumulator.description, 1000));
            entity.setMetadataJson(writeJson(metadata(
                "canonicalKey", accumulator.canonicalKey,
                "sourceEntityIds", accumulator.sourceEntityIds,
                "sourceChunkIds", accumulator.sourceChunkIds,
                "evidenceIds", accumulator.evidenceIds,
                "aliases", accumulator.aliases,
                "sourceMetadata", accumulator.sourceMetadata,
                "candidateSources", accumulator.candidateSources,
                "extractorSources", accumulator.extractorSources,
                "mentionCount", accumulator.mentionCount,
                "candidateScore", accumulator.candidateScore,
                "confidence", accumulator.confidence,
                "entityResolutionStrategy", accumulator.entityResolutionEnhanced ? "llm.controlled.v1" : "java.alias.v1",
                "entityResolutionEnhanced", accumulator.entityResolutionEnhanced,
                "entityResolutionConfidence", accumulator.entityResolutionConfidence,
                "entityResolutionReason", accumulator.entityResolutionReason,
                "entityResolutionCanonicalName", accumulator.entityResolutionCanonicalName,
                "entityResolutionSourceEntityIds", accumulator.entityResolutionSourceEntityIds
            )));
            entity.setStatus(BusinessStatus.YES.getCode());
            entitiesById.put(entityId, entity);
            for (String sourceEntityId : accumulator.sourceEntityIds) {
                entityIdMap.put(sourceEntityId, entityId);
            }
        }
        return new SavedEntities(entityIdMap, entitiesById);
    }

    private SavedRelations prepareRelations(Long documentId,
                                            Long taskId,
                                            List<RagToolsGraphExtractResponse.Relation> relations,
                                            Map<String, Long> entityIdMap) {
        Map<String, Long> relationIdMap = new LinkedHashMap<>();
        if (CollUtil.isEmpty(relations)) {
            return new SavedRelations(relationIdMap, new LinkedHashMap<>());
        }
        Map<String, RelationAccumulator> accumulators = new LinkedHashMap<>();
        Map<String, String> sourceRelationKeyMap = new LinkedHashMap<>();

        for (RagToolsGraphExtractResponse.Relation extracted : relations) {
            if (extracted == null || StrUtil.isBlank(extracted.getId())) {
                continue;
            }
            Long sourceEntityId = entityIdMap.get(extracted.getSourceEntityId());
            Long targetEntityId = entityIdMap.get(extracted.getTargetEntityId());
            if (sourceEntityId == null || targetEntityId == null) {
                log.warn("跳过缺少端点实体的 GraphRAG 关系: documentId={}, taskId={}, relationId={}, source={}, target={}",
                    documentId, taskId, extracted.getId(), extracted.getSourceEntityId(), extracted.getTargetEntityId());
                continue;
            }
            if (Objects.equals(sourceEntityId, targetEntityId)) {
                continue;
            }
            String relationType = limit(StrUtil.blankToDefault(extracted.getRelationType(), "ASSOCIATED_WITH"), 64);
            String canonicalKey = canonicalRelationKey(sourceEntityId, targetEntityId, relationType);
            RelationAccumulator accumulator = accumulators.computeIfAbsent(
                canonicalKey,
                key -> new RelationAccumulator(key, sourceEntityId, targetEntityId, relationType)
            );
            accumulator.merge(extracted);
            sourceRelationKeyMap.put(extracted.getId(), canonicalKey);
        }

        Map<Long, SuperAgentKgRelation> relationsById = new LinkedHashMap<>();
        for (RelationAccumulator accumulator : accumulators.values()) {
            Long relationId = uidGenerator.getUid();
            SuperAgentKgRelation relation = new SuperAgentKgRelation();
            relation.setId(relationId);
            relation.setDocumentId(documentId);
            relation.setTaskId(taskId);
            relation.setSourceEntityId(accumulator.sourceEntityId);
            relation.setTargetEntityId(accumulator.targetEntityId);
            relation.setRelationType(limit(accumulator.relationType, 64));
            relation.setDescription(limit(accumulator.description, 1000));
            relation.setWeight(weight(accumulator.weight));
            relation.setMetadataJson(writeJson(metadata(
                "canonicalKey", accumulator.canonicalKey,
                "sourceRelationIds", accumulator.sourceRelationIds,
                "sourceEntityIds", accumulator.sourceEntityIds,
                "targetEntityIds", accumulator.targetEntityIds,
                "evidenceIds", accumulator.evidenceIds,
                "sourceMetadata", accumulator.sourceMetadata,
                "candidateSources", accumulator.candidateSources,
                "extractorSources", accumulator.extractorSources,
                "confidence", accumulator.confidence
            )));
            relation.setStatus(BusinessStatus.YES.getCode());
            relationsById.put(relationId, relation);
            for (String sourceRelationId : accumulator.sourceRelationIds) {
                relationIdMap.put(sourceRelationId, relationId);
            }
        }
        return new SavedRelations(relationIdMap, relationsById);
    }

    private void insertEntities(Collection<SuperAgentKgEntity> entities) {
        if (CollUtil.isEmpty(entities)) {
            return;
        }
        for (SuperAgentKgEntity entity : entities) {
            entityMapper.insert(entity);
        }
    }

    private void insertRelations(Collection<SuperAgentKgRelation> relations) {
        if (CollUtil.isEmpty(relations)) {
            return;
        }
        for (SuperAgentKgRelation relation : relations) {
            relationMapper.insert(relation);
        }
    }

    private GraphRankSnapshot enrichGraphRankMetadata(Map<Long, SuperAgentKgEntity> entitiesById,
                                                      Map<Long, SuperAgentKgRelation> relationsById) {
        Map<Long, NodeRank> entityRanks = calculateEntityRanks(entitiesById, relationsById);
        double maxRelationWeight = relationsById.values().stream()
            .mapToDouble(this::relationWeightValue)
            .max()
            .orElse(0D);
        Map<Long, RelationRank> relationRanks = new LinkedHashMap<>();

        for (SuperAgentKgEntity entity : entitiesById.values()) {
            NodeRank rank = entityRanks.getOrDefault(entity.getId(), NodeRank.empty());
            Map<String, Object> metadata = readMetadata(entity.getMetadataJson());
            metadata.put("rankAlgorithm", RANK_ALGORITHM);
            metadata.put("pagerank", rounded(rank.pagerank()));
            metadata.put("rankBoost", rounded(rank.rankBoost()));
            metadata.put("rankPosition", rank.rankPosition());
            metadata.put("degree", rank.degree());
            metadata.put("inDegree", rank.inDegree());
            metadata.put("outDegree", rank.outDegree());
            metadata.put("weightedDegree", rounded(rank.weightedDegree()));
            entity.setMetadataJson(writeJson(metadata));
        }

        for (SuperAgentKgRelation relation : relationsById.values()) {
            NodeRank sourceRank = entityRanks.getOrDefault(relation.getSourceEntityId(), NodeRank.empty());
            NodeRank targetRank = entityRanks.getOrDefault(relation.getTargetEntityId(), NodeRank.empty());
            double relationWeightBoost = maxRelationWeight <= 0D ? 0D : relationWeightValue(relation) / maxRelationWeight;
            double relationRankBoost = bounded(
                Math.max(sourceRank.rankBoost(), targetRank.rankBoost()) * 0.55D
                    + ((sourceRank.rankBoost() + targetRank.rankBoost()) / 2D) * 0.30D
                    + relationWeightBoost * 0.15D
            );
            RelationRank relationRank = new RelationRank(
                relationRankBoost,
                sourceRank.rankBoost(),
                targetRank.rankBoost(),
                relationWeightBoost
            );
            relationRanks.put(relation.getId(), relationRank);

            Map<String, Object> metadata = readMetadata(relation.getMetadataJson());
            metadata.put("rankAlgorithm", RANK_ALGORITHM);
            metadata.put("rankBoost", rounded(relationRank.rankBoost()));
            metadata.put("sourceEntityRankBoost", rounded(relationRank.sourceEntityRankBoost()));
            metadata.put("targetEntityRankBoost", rounded(relationRank.targetEntityRankBoost()));
            metadata.put("relationWeightBoost", rounded(relationRank.relationWeightBoost()));
            relation.setMetadataJson(writeJson(metadata));
        }
        return new GraphRankSnapshot(entityRanks, relationRanks);
    }

    private Map<Long, NodeRank> calculateEntityRanks(Map<Long, SuperAgentKgEntity> entitiesById,
                                                     Map<Long, SuperAgentKgRelation> relationsById) {
        if (entitiesById.isEmpty()) {
            return Map.of();
        }

        Map<Long, Map<Long, Double>> outgoingWeights = new LinkedHashMap<>();
        Map<Long, Integer> inDegreeMap = new LinkedHashMap<>();
        Map<Long, Integer> outDegreeMap = new LinkedHashMap<>();
        Map<Long, Integer> degreeMap = new LinkedHashMap<>();
        Map<Long, Double> weightedDegreeMap = new LinkedHashMap<>();
        for (Long entityId : entitiesById.keySet()) {
            outgoingWeights.put(entityId, new LinkedHashMap<>());
            inDegreeMap.put(entityId, 0);
            outDegreeMap.put(entityId, 0);
            degreeMap.put(entityId, 0);
            weightedDegreeMap.put(entityId, 0D);
        }

        for (SuperAgentKgRelation relation : relationsById.values()) {
            Long sourceEntityId = relation.getSourceEntityId();
            Long targetEntityId = relation.getTargetEntityId();
            if (!entitiesById.containsKey(sourceEntityId)
                || !entitiesById.containsKey(targetEntityId)
                || Objects.equals(sourceEntityId, targetEntityId)) {
                continue;
            }
            double weight = relationWeightValue(relation);
            addRankEdge(outgoingWeights, sourceEntityId, targetEntityId, weight);
            if ("ASSOCIATED_WITH".equalsIgnoreCase(relation.getRelationType())) {
                addRankEdge(outgoingWeights, targetEntityId, sourceEntityId, weight);
            }
            outDegreeMap.merge(sourceEntityId, 1, Integer::sum);
            inDegreeMap.merge(targetEntityId, 1, Integer::sum);
            degreeMap.merge(sourceEntityId, 1, Integer::sum);
            degreeMap.merge(targetEntityId, 1, Integer::sum);
            weightedDegreeMap.merge(sourceEntityId, weight, Double::sum);
            weightedDegreeMap.merge(targetEntityId, weight, Double::sum);
        }

        Map<Long, Double> pagerankMap = calculatePagerank(entitiesById.keySet(), outgoingWeights, relationsById.isEmpty());
        double maxPagerank = pagerankMap.values().stream().mapToDouble(Double::doubleValue).max().orElse(0D);
        List<Long> rankedEntityIds = pagerankMap.entrySet().stream()
            .sorted(Map.Entry.<Long, Double>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()))
            .map(Map.Entry::getKey)
            .toList();
        Map<Long, Integer> rankPositionMap = new LinkedHashMap<>();
        for (int index = 0; index < rankedEntityIds.size(); index++) {
            rankPositionMap.put(rankedEntityIds.get(index), index + 1);
        }

        Map<Long, NodeRank> result = new LinkedHashMap<>();
        for (Long entityId : entitiesById.keySet()) {
            double pagerank = pagerankMap.getOrDefault(entityId, 0D);
            double rankBoost = relationsById.isEmpty() || maxPagerank <= 0D ? 0D : Math.sqrt(pagerank / maxPagerank);
            result.put(entityId, new NodeRank(
                pagerank,
                bounded(rankBoost),
                degreeMap.getOrDefault(entityId, 0),
                inDegreeMap.getOrDefault(entityId, 0),
                outDegreeMap.getOrDefault(entityId, 0),
                weightedDegreeMap.getOrDefault(entityId, 0D),
                rankPositionMap.getOrDefault(entityId, 0)
            ));
        }
        return result;
    }

    private Map<Long, Double> calculatePagerank(Collection<Long> entityIds,
                                                Map<Long, Map<Long, Double>> outgoingWeights,
                                                boolean noRelations) {
        int nodeCount = entityIds.size();
        if (nodeCount == 0) {
            return Map.of();
        }
        double initialScore = 1D / nodeCount;
        Map<Long, Double> ranks = new LinkedHashMap<>();
        for (Long entityId : entityIds) {
            ranks.put(entityId, initialScore);
        }
        if (noRelations) {
            return ranks;
        }

        for (int iteration = 0; iteration < RANK_ITERATIONS; iteration++) {
            Map<Long, Double> nextRanks = new LinkedHashMap<>();
            double baseScore = (1D - RANK_DAMPING) / nodeCount;
            for (Long entityId : entityIds) {
                nextRanks.put(entityId, baseScore);
            }

            double sinkScore = 0D;
            for (Long sourceEntityId : entityIds) {
                Map<Long, Double> outgoing = outgoingWeights.getOrDefault(sourceEntityId, Map.of());
                double totalWeight = outgoing.values().stream().mapToDouble(Double::doubleValue).sum();
                if (totalWeight <= 0D) {
                    sinkScore += ranks.getOrDefault(sourceEntityId, 0D);
                    continue;
                }
                double sourceContribution = RANK_DAMPING * ranks.getOrDefault(sourceEntityId, 0D);
                for (Map.Entry<Long, Double> edge : outgoing.entrySet()) {
                    nextRanks.merge(edge.getKey(), sourceContribution * edge.getValue() / totalWeight, Double::sum);
                }
            }

            if (sinkScore > 0D) {
                double sinkContribution = RANK_DAMPING * sinkScore / nodeCount;
                for (Long entityId : entityIds) {
                    nextRanks.merge(entityId, sinkContribution, Double::sum);
                }
            }
            ranks = nextRanks;
        }
        return ranks;
    }

    private void addRankEdge(Map<Long, Map<Long, Double>> outgoingWeights,
                             Long sourceEntityId,
                             Long targetEntityId,
                             double weight) {
        outgoingWeights.computeIfAbsent(sourceEntityId, ignored -> new LinkedHashMap<>())
            .merge(targetEntityId, Math.max(0.05D, weight), Double::sum);
    }

    private CommunityRank resolveCommunityRank(List<Long> entityIds,
                                               List<Long> relationIds,
                                               GraphRankSnapshot rankSnapshot) {
        if (rankSnapshot == null || CollUtil.isEmpty(entityIds)) {
            return new CommunityRank(relationIds, 0D, 0D, 0D, List.of());
        }
        List<NodeRankWithId> entityRanks = entityIds.stream()
            .map(entityId -> new NodeRankWithId(entityId, rankSnapshot.entityRanks().getOrDefault(entityId, NodeRank.empty())))
            .sorted(Comparator.comparingDouble((NodeRankWithId item) -> item.rank().rankBoost()).reversed()
                .thenComparing(NodeRankWithId::entityId))
            .toList();
        double maxEntityRankBoost = entityRanks.stream()
            .mapToDouble(item -> item.rank().rankBoost())
            .max()
            .orElse(0D);
        double avgEntityRankBoost = entityRanks.stream()
            .mapToDouble(item -> item.rank().rankBoost())
            .average()
            .orElse(0D);
        double relationRankBoost = relationIds.stream()
            .map(rankSnapshot.relationRanks()::get)
            .filter(Objects::nonNull)
            .mapToDouble(RelationRank::rankBoost)
            .max()
            .orElse(0D);
        double rankBoost = bounded(maxEntityRankBoost * 0.50D + avgEntityRankBoost * 0.25D + relationRankBoost * 0.25D);
        List<Long> topRankedEntityIds = entityRanks.stream()
            .filter(item -> item.rank().rankBoost() > 0D)
            .map(NodeRankWithId::entityId)
            .limit(8)
            .toList();
        return new CommunityRank(
            relationIds,
            rankBoost,
            maxEntityRankBoost,
            avgEntityRankBoost,
            topRankedEntityIds
        );
    }

    private SavedEvidences prepareEvidences(Long documentId,
                                            Long taskId,
                                            List<RagToolsGraphExtractResponse.Evidence> evidences,
                                            Map<String, Long> entityIdMap,
                                            Map<String, Long> relationIdMap) {
        Map<String, Long> evidenceIdMap = new LinkedHashMap<>();
        Map<Long, SuperAgentKgEvidence> evidencesById = new LinkedHashMap<>();
        if (CollUtil.isEmpty(evidences)) {
            return new SavedEvidences(evidenceIdMap, evidencesById);
        }
        for (RagToolsGraphExtractResponse.Evidence extracted : evidences) {
            if (extracted == null || StrUtil.isBlank(extracted.getId())) {
                continue;
            }
            Long entityId = StrUtil.isBlank(extracted.getEntityId()) ? null : entityIdMap.get(extracted.getEntityId());
            Long relationId = StrUtil.isBlank(extracted.getRelationId()) ? null : relationIdMap.get(extracted.getRelationId());
            if (entityId == null && relationId == null) {
                continue;
            }

            Long evidenceId = uidGenerator.getUid();
            SuperAgentKgEvidence evidence = new SuperAgentKgEvidence();
            evidence.setId(evidenceId);
            evidence.setDocumentId(documentId);
            evidence.setTaskId(taskId);
            evidence.setEntityId(entityId);
            evidence.setRelationId(relationId);
            evidence.setChunkId(extracted.getChunkId());
            evidence.setParentBlockId(extracted.getParentBlockId());
            evidence.setQuoteText(extracted.getQuoteText());
            evidence.setPageNo(extracted.getPageNo());
            evidence.setPageRange(limit(extracted.getPageRange(), 64));
            evidence.setBboxJson(extracted.getBboxJson());
            evidence.setSectionPath(limit(extracted.getSectionPath(), 1000));
            evidence.setMetadataJson(writeJson(metadata(
                "sourceEvidenceId", extracted.getId(),
                "sourceEntityId", extracted.getEntityId(),
                "sourceRelationId", extracted.getRelationId(),
                "sourceMetadata", extracted.getMetadata(),
                "extractorSources", toStringList(extracted.getMetadata() == null ? null : extracted.getMetadata().get("extractorSources")),
                "sourceType", extracted.getMetadata() == null ? null : extracted.getMetadata().get("sourceType")
            )));
            evidence.setStatus(BusinessStatus.YES.getCode());
            evidencesById.put(evidenceId, evidence);
            evidenceIdMap.put(extracted.getId(), evidenceId);
        }
        return new SavedEvidences(evidenceIdMap, evidencesById);
    }

    private void insertEvidences(Collection<SuperAgentKgEvidence> evidences) {
        if (CollUtil.isEmpty(evidences)) {
            return;
        }
        for (SuperAgentKgEvidence evidence : evidences) {
            evidenceMapper.insert(evidence);
        }
    }

    private List<SuperAgentKgCommunity> prepareCommunities(Long documentId,
                                                           Long taskId,
                                                           List<RagToolsGraphExtractResponse.Community> communities,
                                                           Map<String, Long> entityIdMap,
                                                           Map<String, Long> relationIdMap,
                                                           Map<String, Long> evidenceIdMap,
                                                           GraphRankSnapshot rankSnapshot,
                                                           Map<Long, SuperAgentKgEntity> entitiesById,
                                                           Map<Long, SuperAgentKgRelation> relationsById,
                                                           Map<Long, SuperAgentKgEvidence> evidencesById) {
        if (CollUtil.isEmpty(communities)) {
            return List.of();
        }
        List<SuperAgentKgCommunity> result = new ArrayList<>();
        int communityNo = 1;
        for (RagToolsGraphExtractResponse.Community extracted : communities) {
            if (extracted == null || StrUtil.isBlank(extracted.getId())) {
                continue;
            }
            List<Long> entityIds = remapIds(extracted.getEntityIds(), entityIdMap);
            if (entityIds.isEmpty()) {
                continue;
            }
            CommunityRank communityRank = resolveCommunityRank(entityIds, remapIds(extracted.getRelationIds(), relationIdMap), rankSnapshot);
            List<Long> evidenceIds = remapIds(extracted.getEvidenceIds(), evidenceIdMap);

            SuperAgentKgCommunity community = new SuperAgentKgCommunity();
            community.setId(uidGenerator.getUid());
            community.setDocumentId(documentId);
            community.setTaskId(taskId);
            community.setCommunityNo(communityNo++);
            community.setTitle(limit(StrUtil.blankToDefault(extracted.getTitle(), "未命名图谱社区"), 500));
            community.setSummary(extracted.getSummary());
            community.setEntityIdsJson(writeJson(entityIds));
            community.setRelationIdsJson(writeJson(communityRank.relationIds()));
            community.setEvidenceIdsJson(writeJson(evidenceIds));
            Map<String, Object> metadata = metadata(
                "sourceCommunityId", extracted.getId(),
                "sourceEntityIds", extracted.getEntityIds(),
                "sourceRelationIds", extracted.getRelationIds(),
                "sourceEvidenceIds", extracted.getEvidenceIds(),
                "sourceMetadata", extracted.getMetadata(),
                "communityReportStrategy", COMMUNITY_REPORT_STRATEGY_EXTRACTIVE,
                "communityReportEnhanced", false,
                "rankAlgorithm", RANK_ALGORITHM,
                "rankBoost", communityRank.rankBoost(),
                "maxEntityRankBoost", communityRank.maxEntityRankBoost(),
                "avgEntityRankBoost", communityRank.avgEntityRankBoost(),
                "topRankedEntityIds", communityRank.topRankedEntityIds()
            );
            applyCommunityReportAdvice(
                community,
                metadata,
                entityIds,
                communityRank.relationIds(),
                evidenceIds,
                entitiesById,
                relationsById,
                evidencesById
            );
            community.setMetadataJson(writeJson(metadata));
            community.setStatus(BusinessStatus.YES.getCode());
            result.add(community);
        }
        return result;
    }

    private void insertCommunities(Collection<SuperAgentKgCommunity> communities) {
        if (CollUtil.isEmpty(communities)) {
            return;
        }
        for (SuperAgentKgCommunity community : communities) {
            communityMapper.insert(community);
        }
    }

    private void applyCommunityReportAdvice(SuperAgentKgCommunity community,
                                            Map<String, Object> metadata,
                                            List<Long> entityIds,
                                            List<Long> relationIds,
                                            List<Long> evidenceIds,
                                            Map<Long, SuperAgentKgEntity> entitiesById,
                                            Map<Long, SuperAgentKgRelation> relationsById,
                                            Map<Long, SuperAgentKgEvidence> evidencesById) {
        if (communityReportAdvisor == null || community == null || CollUtil.isEmpty(entityIds) || CollUtil.isEmpty(evidenceIds)) {
            return;
        }
        GraphRagCommunityReportContext context = buildCommunityReportContext(
            community,
            entityIds,
            relationIds,
            evidenceIds,
            metadata,
            entitiesById,
            relationsById,
            evidencesById
        );
        if (CollUtil.isEmpty(context.getEvidences())) {
            metadata.put("communityReportRejectedReason", "NO_EVIDENCE_CONTEXT");
            return;
        }

        Optional<GraphRagCommunityReportAdvice> advice;
        try {
            advice = communityReportAdvisor.generateReport(context);
        }
        catch (RuntimeException exception) {
            metadata.put("communityReportRejectedReason", "ADVISOR_FAILED");
            metadata.put("communityReportRejectedMessage", limit(exception.getMessage(), 300));
            log.warn("GraphRAG 社区报告 advisor 失败，保留抽取式 community report: communityId={}, message={}",
                community.getId(),
                exception.getMessage());
            return;
        }
        if (advice.isEmpty()) {
            metadata.put("communityReportRejectedReason", "EMPTY_ADVICE");
            return;
        }

        CommunityReportValidation validation = validateCommunityReportAdvice(advice.get(), evidenceIds);
        if (validation.report() == null) {
            metadata.put("communityReportRejectedReason", validation.rejectedReason());
            return;
        }

        ValidatedCommunityReport report = validation.report();
        community.setTitle(limit(report.title(), 500));
        community.setSummary(report.text());
        metadata.put("communityReportStrategy", COMMUNITY_REPORT_STRATEGY_LLM);
        metadata.put("communityReportEnhanced", true);
        metadata.put("communityReportConfidence", report.confidence());
        metadata.put("communityReportRating", report.rating());
        metadata.put("communityReportRatingExplanation", report.ratingExplanation());
        metadata.put("communityReportEvidenceIds", report.evidenceIds());
        metadata.put("communityReportFindingCount", report.findings().size());
        metadata.put("communityReportReason", report.reason());
    }

    private GraphRagCommunityReportContext buildCommunityReportContext(SuperAgentKgCommunity community,
                                                                       List<Long> entityIds,
                                                                       List<Long> relationIds,
                                                                       List<Long> evidenceIds,
                                                                       Map<String, Object> metadata,
                                                                       Map<Long, SuperAgentKgEntity> entitiesById,
                                                                       Map<Long, SuperAgentKgRelation> relationsById,
                                                                       Map<Long, SuperAgentKgEvidence> evidencesById) {
        return GraphRagCommunityReportContext.builder()
            .communityId(community.getId())
            .communityNo(community.getCommunityNo())
            .originalTitle(community.getTitle())
            .originalSummary(community.getSummary())
            .rankBoost(toDouble(metadata.get("rankBoost"), 0D))
            .entities(entityIds.stream()
                .map(entitiesById::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble((SuperAgentKgEntity entity) -> toDouble(readMetadata(entity.getMetadataJson()).get("rankBoost"), 0D)).reversed()
                    .thenComparing(SuperAgentKgEntity::getId))
                .limit(COMMUNITY_CONTEXT_ENTITY_LIMIT)
                .map(entity -> GraphRagCommunityReportContext.EntityItem.builder()
                    .entityId(entity.getId())
                    .name(entity.getName())
                    .entityType(entity.getEntityType())
                    .description(entity.getDescription())
                    .rankBoost(toDouble(readMetadata(entity.getMetadataJson()).get("rankBoost"), 0D))
                    .build())
                .toList())
            .relations(relationIds.stream()
                .map(relationsById::get)
                .filter(Objects::nonNull)
                .limit(COMMUNITY_CONTEXT_RELATION_LIMIT)
                .map(relation -> {
                    SuperAgentKgEntity source = entitiesById.get(relation.getSourceEntityId());
                    SuperAgentKgEntity target = entitiesById.get(relation.getTargetEntityId());
                    return GraphRagCommunityReportContext.RelationItem.builder()
                        .relationId(relation.getId())
                        .sourceEntityId(relation.getSourceEntityId())
                        .sourceEntityName(source == null ? "" : source.getName())
                        .targetEntityId(relation.getTargetEntityId())
                        .targetEntityName(target == null ? "" : target.getName())
                        .relationType(relation.getRelationType())
                        .description(relation.getDescription())
                        .build();
                })
                .toList())
            .evidences(evidenceIds.stream()
                .map(evidencesById::get)
                .filter(evidence -> evidence != null && StrUtil.isNotBlank(evidence.getQuoteText()))
                .limit(COMMUNITY_CONTEXT_EVIDENCE_LIMIT)
                .map(evidence -> GraphRagCommunityReportContext.EvidenceItem.builder()
                    .evidenceId(evidence.getId())
                    .entityId(evidence.getEntityId())
                    .relationId(evidence.getRelationId())
                    .chunkId(evidence.getChunkId())
                    .quoteText(evidence.getQuoteText())
                    .sectionPath(evidence.getSectionPath())
                    .build())
                .toList())
            .build();
    }

    private CommunityReportValidation validateCommunityReportAdvice(GraphRagCommunityReportAdvice advice,
                                                                    List<Long> allowedEvidenceIds) {
        if (advice == null || !Boolean.TRUE.equals(advice.getReportable())) {
            return CommunityReportValidation.rejected("NOT_REPORTABLE");
        }
        double confidence = bounded(toDouble(advice.getConfidence(), 0D));
        if (confidence < COMMUNITY_REPORT_CONFIDENCE_THRESHOLD) {
            return CommunityReportValidation.rejected("LOW_CONFIDENCE");
        }
        if (StrUtil.isBlank(advice.getTitle()) || StrUtil.isBlank(advice.getSummary())) {
            return CommunityReportValidation.rejected("BLANK_TITLE_OR_SUMMARY");
        }

        Set<Long> allowed = new LinkedHashSet<>(allowedEvidenceIds);
        EvidenceValidation topLevelEvidence = validateAdviceEvidenceIds(advice.getEvidenceIds(), allowed, false);
        if (!topLevelEvidence.valid()) {
            return CommunityReportValidation.rejected("UNKNOWN_EVIDENCE_ID");
        }
        LinkedHashSet<Long> reportEvidenceIds = new LinkedHashSet<>(topLevelEvidence.evidenceIds());
        List<ValidatedFinding> findings = new ArrayList<>();
        if (CollUtil.isNotEmpty(advice.getFindings())) {
            for (GraphRagCommunityReportAdvice.Finding finding : advice.getFindings()) {
                if (finding == null || StrUtil.isBlank(finding.getSummary()) || StrUtil.isBlank(finding.getExplanation())) {
                    continue;
                }
                EvidenceValidation findingEvidence = validateAdviceEvidenceIds(finding.getEvidenceIds(), allowed, false);
                if (!findingEvidence.valid()) {
                    return CommunityReportValidation.rejected("UNKNOWN_EVIDENCE_ID");
                }
                if (findingEvidence.evidenceIds().isEmpty()) {
                    continue;
                }
                reportEvidenceIds.addAll(findingEvidence.evidenceIds());
                findings.add(new ValidatedFinding(
                    limit(finding.getSummary(), 220),
                    limit(finding.getExplanation(), 1200),
                    findingEvidence.evidenceIds()
                ));
                if (findings.size() >= COMMUNITY_REPORT_FINDING_LIMIT) {
                    break;
                }
            }
        }
        if (reportEvidenceIds.isEmpty()) {
            return CommunityReportValidation.rejected("NO_GROUNDED_EVIDENCE");
        }

        double rating = Math.max(0D, Math.min(10D, toDouble(advice.getRating(), 0D)));
        String summary = limit(advice.getSummary(), 3000);
        String text = buildCommunityReportText(summary, findings, rating, advice.getRatingExplanation());
        return CommunityReportValidation.accepted(new ValidatedCommunityReport(
            limit(advice.getTitle(), 500),
            text,
            new ArrayList<>(reportEvidenceIds),
            findings,
            rounded(rating),
            limit(advice.getRatingExplanation(), 1000),
            rounded(confidence),
            limit(advice.getReason(), 500)
        ));
    }

    private EvidenceValidation validateAdviceEvidenceIds(List<Long> candidateIds,
                                                         Set<Long> allowedEvidenceIds,
                                                         boolean requireEvidence) {
        if (CollUtil.isEmpty(candidateIds)) {
            return requireEvidence ? EvidenceValidation.invalid() : EvidenceValidation.valid(List.of());
        }
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        for (Long candidateId : candidateIds) {
            if (candidateId == null) {
                continue;
            }
            if (!allowedEvidenceIds.contains(candidateId)) {
                return EvidenceValidation.invalid();
            }
            result.add(candidateId);
        }
        if (requireEvidence && result.isEmpty()) {
            return EvidenceValidation.invalid();
        }
        return EvidenceValidation.valid(new ArrayList<>(result));
    }

    private String buildCommunityReportText(String summary,
                                            List<ValidatedFinding> findings,
                                            double rating,
                                            String ratingExplanation) {
        StringBuilder builder = new StringBuilder(StrUtil.blankToDefault(summary, ""));
        if (CollUtil.isNotEmpty(findings)) {
            builder.append("\n\n关键发现：");
            for (int index = 0; index < findings.size(); index++) {
                ValidatedFinding finding = findings.get(index);
                builder.append("\n")
                    .append(index + 1)
                    .append(". ")
                    .append(finding.summary())
                    .append("：")
                    .append(finding.explanation())
                    .append(" [evidenceIds=")
                    .append(finding.evidenceIds())
                    .append("]");
            }
        }
        if (StrUtil.isNotBlank(ratingExplanation)) {
            builder.append("\n\n重要性评分：")
                .append(BigDecimal.valueOf(rating).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString())
                .append("/10。")
                .append(ratingExplanation);
        }
        return builder.toString();
    }

    private Map<String, EntityResolutionDecision> resolveEntityCanonicalKeyAdvice(List<RagToolsGraphExtractResponse.Entity> entities) {
        if (entityResolutionAdvisor == null || size(entities) < 2) {
            return Map.of();
        }

        GraphRagEntityResolutionContext context = buildEntityResolutionContext(entities);
        if (size(context.getEntities()) < 2) {
            return Map.of();
        }

        Optional<GraphRagEntityResolutionAdvice> advice;
        try {
            advice = entityResolutionAdvisor.advise(context);
        }
        catch (RuntimeException exception) {
            log.warn("GraphRAG 实体消歧 advisor 失败，继续使用 Java alias canonical 合并: message={}",
                exception.getMessage());
            return Map.of();
        }
        if (advice.isEmpty() || !Boolean.TRUE.equals(advice.get().getResolvable())) {
            return Map.of();
        }

        double topConfidence = bounded(toDouble(advice.get().getConfidence(), 0D));
        if (topConfidence < ENTITY_RESOLUTION_CONFIDENCE_THRESHOLD || CollUtil.isEmpty(advice.get().getMergeGroups())) {
            return Map.of();
        }
        return validateEntityResolutionAdvice(advice.get(), entities, topConfidence);
    }

    private GraphRagEntityResolutionContext buildEntityResolutionContext(List<RagToolsGraphExtractResponse.Entity> entities) {
        return GraphRagEntityResolutionContext.builder()
            .entities(entities.stream()
                .filter(entity -> entity != null && StrUtil.isNotBlank(entity.getId()) && StrUtil.isNotBlank(entity.getName()))
                .limit(ENTITY_RESOLUTION_CONTEXT_LIMIT)
                .map(entity -> GraphRagEntityResolutionContext.EntityItem.builder()
                    .sourceEntityId(entity.getId())
                    .name(entity.getName())
                    .normalizedName(entity.getNormalizedName())
                    .entityType(limit(StrUtil.blankToDefault(entity.getType(), "CONCEPT"), 64))
                    .aliases(entity.getAliases() == null ? List.of() : entity.getAliases())
                    .description(entity.getDescription())
                    .confidence(entity.getConfidence())
                    .sourceChunkIds(entity.getSourceChunkIds() == null ? List.of() : entity.getSourceChunkIds())
                    .evidenceIds(entity.getEvidenceIds() == null ? List.of() : entity.getEvidenceIds())
                    .build())
                .toList())
            .build();
    }

    private Map<String, EntityResolutionDecision> validateEntityResolutionAdvice(GraphRagEntityResolutionAdvice advice,
                                                                                 List<RagToolsGraphExtractResponse.Entity> entities,
                                                                                 double topConfidence) {
        Map<String, RagToolsGraphExtractResponse.Entity> entityById = new LinkedHashMap<>();
        for (RagToolsGraphExtractResponse.Entity entity : entities) {
            if (entity != null && StrUtil.isNotBlank(entity.getId()) && StrUtil.isNotBlank(entity.getName())) {
                entityById.put(entity.getId(), entity);
            }
        }
        Map<String, EntityResolutionDecision> result = new LinkedHashMap<>();
        Set<String> claimedEntityIds = new LinkedHashSet<>();
        int groupNo = 1;
        for (GraphRagEntityResolutionAdvice.MergeGroup group : advice.getMergeGroups()) {
            EntityResolutionGroupValidation validation = validateEntityResolutionGroup(
                group,
                entityById,
                claimedEntityIds,
                Math.min(topConfidence, bounded(toDouble(group == null ? null : group.getConfidence(), topConfidence))),
                groupNo
            );
            if (validation.accepted()) {
                result.putAll(validation.decisions());
                claimedEntityIds.addAll(validation.decisions().keySet());
                groupNo++;
            }
        }
        return result;
    }

    private EntityResolutionGroupValidation validateEntityResolutionGroup(GraphRagEntityResolutionAdvice.MergeGroup group,
                                                                          Map<String, RagToolsGraphExtractResponse.Entity> entityById,
                                                                          Set<String> claimedEntityIds,
                                                                          double confidence,
                                                                          int groupNo) {
        if (group == null || size(group.getEntityIds()) < 2 || confidence < ENTITY_RESOLUTION_CONFIDENCE_THRESHOLD) {
            return EntityResolutionGroupValidation.rejected();
        }
        LinkedHashSet<String> sourceEntityIds = new LinkedHashSet<>();
        for (String sourceEntityId : group.getEntityIds()) {
            if (StrUtil.isBlank(sourceEntityId) || !entityById.containsKey(sourceEntityId) || claimedEntityIds.contains(sourceEntityId)) {
                return EntityResolutionGroupValidation.rejected();
            }
            sourceEntityIds.add(sourceEntityId);
        }
        if (sourceEntityIds.size() < 2) {
            return EntityResolutionGroupValidation.rejected();
        }

        String entityType = null;
        Set<String> allowedNameVariants = new LinkedHashSet<>();
        Map<String, Set<String>> variantsByEntityId = new LinkedHashMap<>();
        for (String sourceEntityId : sourceEntityIds) {
            RagToolsGraphExtractResponse.Entity entity = entityById.get(sourceEntityId);
            String currentType = limit(StrUtil.blankToDefault(entity.getType(), "CONCEPT"), 64);
            if (entityType == null) {
                entityType = currentType;
            }
            else if (!entityType.equals(currentType)) {
                return EntityResolutionGroupValidation.rejected();
            }
            Set<String> variants = entityResolutionVariants(entity);
            if (variants.isEmpty()) {
                return EntityResolutionGroupValidation.rejected();
            }
            allowedNameVariants.addAll(variants);
            variantsByEntityId.put(sourceEntityId, variants);
        }
        if (!canonicalNameAllowed(group.getCanonicalName(), group.getAliases(), allowedNameVariants)) {
            return EntityResolutionGroupValidation.rejected();
        }
        if (!entityResolutionGroupHasAliasOverlap(variantsByEntityId)) {
            return EntityResolutionGroupValidation.rejected();
        }

        String canonicalName = chooseResolutionCanonicalName(group, sourceEntityIds, entityById);
        String canonicalKey = canonicalEntityKey(entityType, canonicalName);
        Map<String, EntityResolutionDecision> decisions = new LinkedHashMap<>();
        for (String sourceEntityId : sourceEntityIds) {
            decisions.put(sourceEntityId, new EntityResolutionDecision(
                canonicalKey,
                sourceEntityIds.stream().toList(),
                canonicalName,
                rounded(confidence),
                limit(group.getReason(), 500),
                groupNo
            ));
        }
        return EntityResolutionGroupValidation.accepted(decisions);
    }

    private Set<String> entityResolutionVariants(RagToolsGraphExtractResponse.Entity entity) {
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        for (String variant : entityVariants(entity)) {
            if (isEntityResolutionVariantUsable(variant)) {
                variants.add(variant);
            }
        }
        return variants;
    }

    private boolean isEntityResolutionVariantUsable(String normalizedVariant) {
        if (StrUtil.isBlank(normalizedVariant) || normalizedVariant.length() > 80) {
            return false;
        }
        boolean hasLatinOrDigit = normalizedVariant.chars()
            .anyMatch(ch -> (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9'));
        if (hasLatinOrDigit) {
            return normalizedVariant.length() >= 2;
        }
        return normalizedVariant.codePointCount(0, normalizedVariant.length()) >= 2;
    }

    private boolean entityResolutionGroupHasAliasOverlap(Map<String, Set<String>> variantsByEntityId) {
        if (variantsByEntityId == null || variantsByEntityId.size() < 2) {
            return false;
        }
        List<String> entityIds = new ArrayList<>(variantsByEntityId.keySet());
        Set<String> connectedEntityIds = new LinkedHashSet<>();
        connectedEntityIds.add(entityIds.get(0));
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String candidateEntityId : entityIds) {
                if (connectedEntityIds.contains(candidateEntityId)) {
                    continue;
                }
                if (hasOverlapWithConnectedEntities(candidateEntityId, connectedEntityIds, variantsByEntityId)) {
                    connectedEntityIds.add(candidateEntityId);
                    changed = true;
                }
            }
        }
        return connectedEntityIds.size() == variantsByEntityId.size();
    }

    private boolean hasOverlapWithConnectedEntities(String candidateEntityId,
                                                    Set<String> connectedEntityIds,
                                                    Map<String, Set<String>> variantsByEntityId) {
        Set<String> candidateVariants = variantsByEntityId.get(candidateEntityId);
        if (CollUtil.isEmpty(candidateVariants)) {
            return false;
        }
        for (String connectedEntityId : connectedEntityIds) {
            if (variantsIntersect(candidateVariants, variantsByEntityId.get(connectedEntityId))) {
                return true;
            }
        }
        return false;
    }

    private boolean variantsIntersect(Set<String> left, Set<String> right) {
        if (CollUtil.isEmpty(left) || CollUtil.isEmpty(right)) {
            return false;
        }
        Set<String> smaller = left.size() <= right.size() ? left : right;
        Set<String> larger = left.size() <= right.size() ? right : left;
        for (String variant : smaller) {
            if (larger.contains(variant)) {
                return true;
            }
        }
        return false;
    }

    private boolean canonicalNameAllowed(String canonicalName,
                                         List<String> aliases,
                                         Set<String> allowedNameVariants) {
        if (CollUtil.isEmpty(allowedNameVariants)) {
            return false;
        }
        if (StrUtil.isNotBlank(canonicalName) && !allowedNameVariants.contains(normalizeKey(canonicalName))) {
            return false;
        }
        if (CollUtil.isNotEmpty(aliases)) {
            for (String alias : aliases) {
                if (StrUtil.isNotBlank(alias) && !allowedNameVariants.contains(normalizeKey(alias))) {
                    return false;
                }
            }
        }
        return true;
    }

    private String chooseResolutionCanonicalName(GraphRagEntityResolutionAdvice.MergeGroup group,
                                                 Set<String> sourceEntityIds,
                                                 Map<String, RagToolsGraphExtractResponse.Entity> entityById) {
        if (StrUtil.isNotBlank(group.getCanonicalName())) {
            return limit(group.getCanonicalName(), 500);
        }
        String bestName = "";
        for (String sourceEntityId : sourceEntityIds) {
            RagToolsGraphExtractResponse.Entity entity = entityById.get(sourceEntityId);
            if (entity != null && shouldReplaceName(entity.getName(), bestName)) {
                bestName = entity.getName();
            }
        }
        return limit(StrUtil.blankToDefault(bestName, entityById.get(sourceEntityIds.iterator().next()).getName()), 500);
    }

    private String canonicalEntityKey(RagToolsGraphExtractResponse.Entity extracted) {
        return canonicalEntityKey(
            limit(StrUtil.blankToDefault(extracted.getType(), "CONCEPT"), 64),
            firstNonBlank(extracted.getNormalizedName(), extracted.getName())
        );
    }

    private String resolveEntityCanonicalKey(RagToolsGraphExtractResponse.Entity extracted,
                                             Map<String, String> aliasIndex,
                                             Set<String> existingCanonicalKeys,
                                             String sourceKey) {
        String entityType = limit(StrUtil.blankToDefault(extracted.getType(), "CONCEPT"), 64);
        for (String variant : entityVariants(extracted)) {
            String aliasKey = entityAliasKey(entityType, variant);
            String matchedKey = aliasIndex.get(aliasKey);
            if (StrUtil.isNotBlank(matchedKey)) {
                return matchedKey;
            }
        }
        return sourceKey;
    }

    private void indexEntityAliases(Map<String, String> aliasIndex,
                                    String canonicalKey,
                                    EntityAccumulator accumulator) {
        for (String variant : accumulator.variants) {
            aliasIndex.put(entityAliasKey(accumulator.entityType, variant), canonicalKey);
        }
    }

    private String canonicalRelationKey(Long sourceEntityId, Long targetEntityId, String relationType) {
        if ("ASSOCIATED_WITH".equalsIgnoreCase(relationType)) {
            long left = Math.min(sourceEntityId, targetEntityId);
            long right = Math.max(sourceEntityId, targetEntityId);
            return relationType + ":" + left + ":" + right;
        }
        return relationType + ":" + sourceEntityId + ":" + targetEntityId;
    }

    private List<String> entityVariants(RagToolsGraphExtractResponse.Entity extracted) {
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        addVariant(variants, extracted.getName());
        addVariant(variants, extracted.getNormalizedName());
        if (CollUtil.isNotEmpty(extracted.getAliases())) {
            for (String alias : extracted.getAliases()) {
                addVariant(variants, alias);
            }
        }
        return new ArrayList<>(variants);
    }

    private void addVariant(Set<String> variants, String candidate) {
        String normalized = normalizeKey(candidate);
        if (StrUtil.isNotBlank(normalized)) {
            variants.add(normalized);
        }
    }

    private String entityAliasKey(String entityType, String normalizedVariant) {
        return limit(StrUtil.blankToDefault(entityType, "CONCEPT"), 64) + ":" + normalizedVariant;
    }

    private String canonicalEntityKey(String entityType, String primaryName) {
        String normalized = normalizeKey(primaryName);
        if (StrUtil.isBlank(normalized)) {
            normalized = "unknown";
        }
        return "ENT_" + sha1Hex(limit(StrUtil.blankToDefault(entityType, "CONCEPT"), 64) + ":" + normalized);
    }

    private String normalizeKey(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        return value
            .replaceAll("[\\s>`*#_\\-，,。；;：:（）()“”\"'\\[\\]{}<>《》/\\\\|]+", "")
            .toLowerCase();
    }

    private String firstNonBlank(String first, String second) {
        if (StrUtil.isNotBlank(first)) {
            return first;
        }
        return second;
    }

    private String sha1Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString().substring(0, 16).toUpperCase();
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 算法不可用", exception);
        }
    }

    private String firstNonBlank(Collection<String> values) {
        if (CollUtil.isEmpty(values)) {
            return "";
        }
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private boolean shouldReplaceName(String candidate, String current) {
        if (StrUtil.isBlank(current)) {
            return StrUtil.isNotBlank(candidate);
        }
        if (StrUtil.isBlank(candidate)) {
            return false;
        }
        boolean candidateAcronym = isAcronym(candidate);
        boolean currentAcronym = isAcronym(current);
        if (currentAcronym && !candidateAcronym) {
            return true;
        }
        if (!currentAcronym && candidateAcronym) {
            return false;
        }
        return candidate.length() > current.length() + 1 && candidate.length() <= 32;
    }

    private boolean isAcronym(String value) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        return value.matches("[A-Z][A-Z0-9._-]{1,12}");
    }

    private double toDouble(Object value, double defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        }
        catch (Exception exception) {
            return defaultValue;
        }
    }

    private int toInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        }
        catch (Exception exception) {
            return defaultValue;
        }
    }

    private Map<String, Object> copyMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        return new LinkedHashMap<>(metadata);
    }

    private List<String> toStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            List<String> result = new ArrayList<>();
            for (Object item : collection) {
                if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        if (value instanceof String text) {
            if (StrUtil.isBlank(text)) {
                return List.of();
            }
            String[] parts = text.split("[\\n\\r,，;；、|]+");
            List<String> result = new ArrayList<>();
            for (String part : parts) {
                if (StrUtil.isNotBlank(part)) {
                    result.add(part.trim());
                }
            }
            return result;
        }
        return List.of(String.valueOf(value));
    }

    private Set<String> limitAliases(Collection<String> aliases, String primaryName) {
        if (CollUtil.isEmpty(aliases)) {
            return Set.of();
        }
        String normalizedPrimary = normalizeKey(primaryName);
        Set<String> result = new LinkedHashSet<>();
        for (String alias : aliases) {
            if (StrUtil.isBlank(alias)) {
                continue;
            }
            String limitedAlias = limit(alias, 500);
            if (!normalizeKey(limitedAlias).equals(normalizedPrimary)) {
                result.add(limitedAlias);
            }
        }
        return result;
    }

    private String bestText(String current, String candidate) {
        if (StrUtil.isBlank(current)) {
            return candidate;
        }
        if (StrUtil.isBlank(candidate)) {
            return current;
        }
        return candidate.length() > current.length() ? candidate : current;
    }

    private List<Long> remapIds(List<String> sourceIds, Map<String, Long> idMap) {
        if (CollUtil.isEmpty(sourceIds)) {
            return List.of();
        }
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        for (String sourceId : sourceIds) {
            Long id = idMap.get(sourceId);
            if (id != null) {
                result.add(id);
            }
        }
        return new ArrayList<>(result);
    }

    private BigDecimal weight(Double value) {
        double weight = value == null ? 1.0D : value;
        return BigDecimal.valueOf(weight).setScale(4, RoundingMode.HALF_UP);
    }

    private double relationWeightValue(SuperAgentKgRelation relation) {
        if (relation == null || relation.getWeight() == null) {
            return 1D;
        }
        return Math.max(0.05D, relation.getWeight().doubleValue());
    }

    private Map<String, Object> readMetadata(String metadataJson) {
        if (StrUtil.isBlank(metadataJson)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(metadataJson,
                objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        }
        catch (Exception exception) {
            return new LinkedHashMap<>();
        }
    }

    private double bounded(double value) {
        return Math.max(0D, Math.min(1D, value));
    }

    private double rounded(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).doubleValue();
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

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("GraphRAG 元数据 JSON 序列化失败", exception);
        }
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private final class EntityAccumulator {

        private final String canonicalKey;
        private final Set<String> sourceEntityIds = new LinkedHashSet<>();
        private final Set<Long> sourceChunkIds = new LinkedHashSet<>();
        private final Set<String> evidenceIds = new LinkedHashSet<>();
        private final Set<String> aliases = new LinkedHashSet<>();
        private final Set<String> candidateSources = new LinkedHashSet<>();
        private final Set<String> extractorSources = new LinkedHashSet<>();
        private final Set<String> entityResolutionSourceEntityIds = new LinkedHashSet<>();
        private final List<Map<String, Object>> sourceMetadata = new ArrayList<>();
        private final Set<String> variants = new LinkedHashSet<>();
        private String name = "";
        private String normalizedName = "";
        private String entityType = "";
        private String description = "";
        private int mentionCount;
        private double confidence;
        private double candidateScore;
        private boolean entityResolutionEnhanced;
        private double entityResolutionConfidence;
        private String entityResolutionReason = "";
        private String entityResolutionCanonicalName = "";

        private EntityAccumulator(String canonicalKey) {
            this.canonicalKey = canonicalKey;
        }

        private void merge(RagToolsGraphExtractResponse.Entity extracted) {
            sourceEntityIds.add(extracted.getId());
            entityType = limit(StrUtil.blankToDefault(entityType, StrUtil.blankToDefault(extracted.getType(), "CONCEPT")), 64);

            String extractedName = limit(extracted.getName(), 500);
            if (shouldReplaceName(extractedName, name)) {
                if (StrUtil.isNotBlank(name) && !normalizeKey(name).equals(normalizeKey(extractedName))) {
                    aliases.add(name);
                }
                name = extractedName;
            }
            else if (StrUtil.isNotBlank(extractedName) && !normalizeKey(extractedName).equals(normalizeKey(name))) {
                aliases.add(extractedName);
            }

            String extractedNormalizedName = limit(firstNonBlank(extracted.getNormalizedName(), extractedName), 500);
            if (StrUtil.isBlank(normalizedName) || shouldReplaceName(extractedNormalizedName, normalizedName)) {
                normalizedName = extractedNormalizedName;
            }

            description = bestText(description, limit(extracted.getDescription(), 1000));
            confidence = Math.max(confidence, extracted.getConfidence() == null ? 0D : extracted.getConfidence());
            if (CollUtil.isNotEmpty(extracted.getSourceChunkIds())) {
                sourceChunkIds.addAll(extracted.getSourceChunkIds());
            }
            if (CollUtil.isNotEmpty(extracted.getEvidenceIds())) {
                evidenceIds.addAll(extracted.getEvidenceIds());
            }
            aliases.addAll(limitAliases(extracted.getAliases(), name));
            variants.addAll(entityVariants(extracted));

            Map<String, Object> metadata = copyMetadata(extracted.getMetadata());
            if (metadata != null) {
                sourceMetadata.add(metadata);
                mentionCount += toInt(metadata.get("mentionCount"), 1);
                candidateScore += toDouble(metadata.get("candidateScore"), 0D);
                candidateSources.addAll(toStringList(metadata.get("candidateSources")));
                extractorSources.addAll(toStringList(metadata.get("extractorSources")));
                extractorSources.addAll(toStringList(metadata.get("sourceType")));
            }
            if (mentionCount <= 0) {
                mentionCount = 1;
            }
        }

        private void applyResolutionAdvice(EntityResolutionDecision decision) {
            if (decision == null) {
                return;
            }
            entityResolutionEnhanced = true;
            entityResolutionConfidence = Math.max(entityResolutionConfidence, decision.confidence());
            entityResolutionReason = bestText(entityResolutionReason, decision.reason());
            entityResolutionCanonicalName = bestText(entityResolutionCanonicalName, decision.canonicalName());
            entityResolutionSourceEntityIds.addAll(decision.sourceEntityIds());
        }
    }

    private final class RelationAccumulator {

        private final String canonicalKey;
        private final Long sourceEntityId;
        private final Long targetEntityId;
        private final String relationType;
        private final Set<String> sourceRelationIds = new LinkedHashSet<>();
        private final Set<String> sourceEntityIds = new LinkedHashSet<>();
        private final Set<String> targetEntityIds = new LinkedHashSet<>();
        private final Set<String> evidenceIds = new LinkedHashSet<>();
        private final Set<String> candidateSources = new LinkedHashSet<>();
        private final Set<String> extractorSources = new LinkedHashSet<>();
        private final List<Map<String, Object>> sourceMetadata = new ArrayList<>();
        private String description = "";
        private double weight;
        private double confidence;

        private RelationAccumulator(String canonicalKey, Long sourceEntityId, Long targetEntityId, String relationType) {
            this.canonicalKey = canonicalKey;
            this.sourceEntityId = sourceEntityId;
            this.targetEntityId = targetEntityId;
            this.relationType = relationType;
        }

        private void merge(RagToolsGraphExtractResponse.Relation extracted) {
            sourceRelationIds.add(extracted.getId());
            sourceEntityIds.add(extracted.getSourceEntityId());
            targetEntityIds.add(extracted.getTargetEntityId());
            if (CollUtil.isNotEmpty(extracted.getEvidenceIds())) {
                evidenceIds.addAll(extracted.getEvidenceIds());
            }
            description = bestText(description, limit(extracted.getDescription(), 1000));
            weight = Math.max(weight, extracted.getWeight() == null ? 1D : extracted.getWeight());
            confidence = Math.max(confidence, extracted.getConfidence() == null ? 0D : extracted.getConfidence());
            Map<String, Object> metadata = copyMetadata(extracted.getMetadata());
            if (metadata != null) {
                sourceMetadata.add(metadata);
                candidateSources.addAll(toStringList(metadata.get("candidateSources")));
                extractorSources.addAll(toStringList(metadata.get("extractorSources")));
                extractorSources.addAll(toStringList(metadata.get("sourceType")));
            }
        }
    }

    private record SavedEntities(Map<String, Long> sourceIdToEntityId,
                                 Map<Long, SuperAgentKgEntity> entitiesById) {
    }

    private record SavedRelations(Map<String, Long> sourceIdToRelationId,
                                  Map<Long, SuperAgentKgRelation> relationsById) {
    }

    private record SavedEvidences(Map<String, Long> sourceIdToEvidenceId,
                                  Map<Long, SuperAgentKgEvidence> evidencesById) {
    }

    private record PreparedGraph(SavedEntities entities,
                                 SavedRelations relations,
                                 SavedEvidences evidences,
                                 List<SuperAgentKgCommunity> communities) {
    }

    private record GraphRankSnapshot(Map<Long, NodeRank> entityRanks,
                                     Map<Long, RelationRank> relationRanks) {
    }

    private record NodeRank(double pagerank,
                            double rankBoost,
                            int degree,
                            int inDegree,
                            int outDegree,
                            double weightedDegree,
                            int rankPosition) {

        private static NodeRank empty() {
            return new NodeRank(0D, 0D, 0, 0, 0, 0D, 0);
        }
    }

    private record RelationRank(double rankBoost,
                                double sourceEntityRankBoost,
                                double targetEntityRankBoost,
                                double relationWeightBoost) {
    }

    private record CommunityRank(List<Long> relationIds,
                                 double rankBoost,
                                 double maxEntityRankBoost,
                                 double avgEntityRankBoost,
                                 List<Long> topRankedEntityIds) {
    }

    private record NodeRankWithId(Long entityId, NodeRank rank) {
    }

    private record EntityExtractionCandidate(String localEntityId,
                                             String name,
                                             String normalizedName,
                                             String entityType,
                                             List<String> aliases,
                                             String description,
                                             double confidence,
                                             List<Long> sourceChunkIds,
                                             String sourceId,
                                             String reason) {
    }

    private record RelationExtractionCandidate(String localRelationId,
                                               String sourceEntityId,
                                               String targetEntityId,
                                               String relationType,
                                               String requestedRelationType,
                                               String supportMode,
                                               String predicateQuoteText,
                                               String relationTypeReason,
                                               String relationTypeMappingStatus,
                                               String relationTypeMappingReason,
                                               String description,
                                               double weight,
                                               double confidence) {
    }

    private record GraphExtractionValidation(List<RagToolsGraphExtractResponse.Entity> entities,
                                             List<RagToolsGraphExtractResponse.Relation> relations,
                                             List<RagToolsGraphExtractResponse.Evidence> evidences,
                                             double confidence,
                                             String reason,
                                             String rejectedReason) {

        private boolean enhanced() {
            return rejectedReason == null
                && (CollUtil.isNotEmpty(entities) || CollUtil.isNotEmpty(relations) || CollUtil.isNotEmpty(evidences));
        }

        private static GraphExtractionValidation accepted(List<RagToolsGraphExtractResponse.Entity> entities,
                                                          List<RagToolsGraphExtractResponse.Relation> relations,
                                                          List<RagToolsGraphExtractResponse.Evidence> evidences,
                                                          double confidence,
                                                          String reason) {
            return new GraphExtractionValidation(entities, relations, evidences, confidence, reason, null);
        }

        private static GraphExtractionValidation rejected(String reason) {
            return new GraphExtractionValidation(List.of(), List.of(), List.of(), 0D, null, reason);
        }
    }

    private record EntityResolutionDecision(String canonicalKey,
                                            List<String> sourceEntityIds,
                                            String canonicalName,
                                            double confidence,
                                            String reason,
                                            int groupNo) {
    }

    private record EntityResolutionGroupValidation(boolean accepted,
                                                   Map<String, EntityResolutionDecision> decisions) {

        private static EntityResolutionGroupValidation accepted(Map<String, EntityResolutionDecision> decisions) {
            return new EntityResolutionGroupValidation(true, decisions);
        }

        private static EntityResolutionGroupValidation rejected() {
            return new EntityResolutionGroupValidation(false, Map.of());
        }
    }

    private record ValidatedFinding(String summary,
                                    String explanation,
                                    List<Long> evidenceIds) {
    }

    private record ValidatedCommunityReport(String title,
                                            String text,
                                            List<Long> evidenceIds,
                                            List<ValidatedFinding> findings,
                                            double rating,
                                            String ratingExplanation,
                                            double confidence,
                                            String reason) {
    }

    private record CommunityReportValidation(ValidatedCommunityReport report,
                                             String rejectedReason) {

        private static CommunityReportValidation accepted(ValidatedCommunityReport report) {
            return new CommunityReportValidation(report, null);
        }

        private static CommunityReportValidation rejected(String reason) {
            return new CommunityReportValidation(null, reason);
        }
    }

    private record EvidenceValidation(boolean valid,
                                      List<Long> evidenceIds) {

        private static EvidenceValidation valid(List<Long> evidenceIds) {
            return new EvidenceValidation(true, evidenceIds);
        }

        private static EvidenceValidation invalid() {
            return new EvidenceValidation(false, List.of());
        }
    }
}
