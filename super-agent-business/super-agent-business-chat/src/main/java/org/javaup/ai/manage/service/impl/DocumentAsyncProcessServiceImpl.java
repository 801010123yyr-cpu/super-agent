package org.javaup.ai.manage.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.config.DocumentManageProperties;
import org.javaup.ai.manage.data.SuperAgentDocumentBlock;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentChunk;
import org.javaup.ai.manage.data.SuperAgentDocumentParentBlock;
import org.javaup.ai.manage.data.SuperAgentDocumentParseArtifact;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyPlan;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyStep;
import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;
import org.javaup.ai.manage.data.SuperAgentDocumentTask;
import org.javaup.ai.manage.data.SuperAgentDocumentTaskLog;
import org.javaup.ai.manage.mapper.SuperAgentDocumentChunkMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentParentBlockMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentStrategyPlanMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentStrategyStepMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTaskMapper;
import org.javaup.ai.manage.model.graph.GraphRagBuildResult;
import org.javaup.ai.manage.model.raptor.RaptorBuildResult;
import org.javaup.ai.manage.service.DocumentAsyncProcessService;
import org.javaup.ai.manage.service.DocumentIndexBuildProgressCacheService;
import org.javaup.ai.manage.service.DocumentNavigationIndexService;
import org.javaup.ai.manage.service.DocumentParseArtifactService;
import org.javaup.ai.manage.service.DocumentParserService;
import org.javaup.ai.manage.service.DocumentProfileService;
import org.javaup.ai.manage.service.DocumentStorageService;
import org.javaup.ai.manage.service.DocumentStrategyService;
import org.javaup.ai.manage.service.DocumentStructureGraphProjectionService;
import org.javaup.ai.manage.service.DocumentStructureNodeService;
import org.javaup.ai.manage.service.DocumentTaskLogService;
import org.javaup.ai.manage.service.DocumentVectorGateway;
import org.javaup.ai.manage.service.GraphRagBuildService;
import org.javaup.ai.manage.service.GraphRagTypedChunkService;
import org.javaup.ai.manage.service.RaptorBuildService;
import org.javaup.ai.manage.service.keyword.DocumentKeywordSearchGateway;
import org.javaup.ai.manage.support.ChunkCandidate;
import org.javaup.ai.manage.support.DocumentAnalysisResult;
import org.javaup.ai.manage.support.DocumentBlockCandidate;
import org.javaup.ai.manage.support.DocumentParseArtifactCandidate;
import org.javaup.ai.manage.support.DocumentStrategyPlanDraft;
import org.javaup.ai.manage.support.DocumentStrategyStepDraft;
import org.javaup.ai.manage.support.MybatisBatchExecutor;
import org.javaup.ai.manage.support.ParentBlockCandidate;
import org.javaup.enums.BusinessStatus;
import org.javaup.enums.DocumentChunkSourceTypeEnum;
import org.javaup.enums.DocumentFileTypeEnum;
import org.javaup.enums.DocumentIndexStatusEnum;
import org.javaup.enums.DocumentLogLevelEnum;
import org.javaup.enums.DocumentOperatorTypeEnum;
import org.javaup.enums.DocumentParseStatusEnum;
import org.javaup.enums.DocumentPlanSourceEnum;
import org.javaup.enums.DocumentPlanStatusEnum;
import org.javaup.enums.DocumentStrategyExecuteStatusEnum;
import org.javaup.enums.DocumentStrategyPipelineTypeEnum;
import org.javaup.enums.DocumentStrategyStatusEnum;
import org.javaup.enums.DocumentTaskEventTypeEnum;
import org.javaup.enums.DocumentTaskStageEnum;
import org.javaup.enums.DocumentTaskStatusEnum;
import org.javaup.enums.DocumentVectorStatusEnum;
import org.javaup.enums.DocumentVectorStoreTypeEnum;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务实现层
 * @author: 阿星不是程序员
 **/

@Slf4j
@RequiredArgsConstructor
@Service
public class DocumentAsyncProcessServiceImpl implements DocumentAsyncProcessService {

    private static final Set<Long> RUNNING_INDEX_TASK_IDS = ConcurrentHashMap.newKeySet();

    private final SuperAgentDocumentMapper documentMapper;

    private final SuperAgentDocumentStrategyPlanMapper planMapper;

    private final SuperAgentDocumentStrategyStepMapper stepMapper;

    private final SuperAgentDocumentTaskMapper taskMapper;

    private final SuperAgentDocumentParentBlockMapper parentBlockMapper;

    private final SuperAgentDocumentChunkMapper chunkMapper;

    private final DocumentStorageService storageService;

    private final DocumentParseArtifactService parseArtifactService;

    private final DocumentParserService parserService;

    private final DocumentStrategyService strategyService;

    private final DocumentStructureNodeService structureNodeService;

    private final DocumentTaskLogService taskLogService;

    private final DocumentIndexBuildProgressCacheService progressCacheService;

    private final DocumentVectorGateway vectorGateway;

    private final ObjectProvider<DocumentKeywordSearchGateway> keywordSearchGatewayProvider;

    private final ObjectProvider<DocumentNavigationIndexService> navigationIndexServiceProvider;

    private final ObjectProvider<DocumentStructureGraphProjectionService> graphProjectionServiceProvider;

    private final DocumentProfileService documentProfileService;

    private final GraphRagBuildService graphRagBuildService;

    private final GraphRagTypedChunkService graphRagTypedChunkService;

    private final RaptorBuildService raptorBuildService;

    private final DocumentManageProperties properties;

    @Resource
    private UidGenerator uidGenerator;

    @Resource(name = "documentIndexBuildExecutorService")
    private ExecutorService indexBuildExecutorService;

    @Override
    public void handleParseRoute(Long documentId, Long taskId) {

        SuperAgentDocument document = documentMapper.selectById(documentId);
        SuperAgentDocumentTask task = taskMapper.selectById(taskId);
        if (document == null || task == null) {
            log.warn("解析任务对应的文档或任务不存在，documentId={}, taskId={}", documentId, taskId);
            return;
        }

        Date startTime = new Date();
        long parseRouteStartedNanos = System.nanoTime();
        log.info("开始异步解析文档，documentId={}, taskId={}, fileName={}, fileType={}, objectName={}",
            documentId, taskId, document.getOriginalFileName(), document.getFileType(), document.getObjectName());
        try {

            task.setTaskStatus(DocumentTaskStatusEnum.RUNNING.getCode());
            task.setCurrentStage(DocumentTaskStageEnum.CONTENT_PARSE.getCode());
            task.setStartTime(startTime);
            taskMapper.updateById(task);

            document.setParseStatus(DocumentParseStatusEnum.PARSING.getCode());
            documentMapper.updateById(document);

            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.CONTENT_PARSE.getCode(),
                DocumentTaskEventTypeEnum.START.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "开始解析文档内容。",
                Map.of("objectName", document.getObjectName()));

            long downloadStartedNanos = System.nanoTime();
            byte[] fileBytes = storageService.downloadObject(document.getObjectName());
            long downloadCostMillis = elapsedMillis(downloadStartedNanos);
            log.info("解析源文件下载完成，documentId={}, taskId={}, objectName={}, fileSizeBytes={}, costMillis={}",
                documentId, taskId, document.getObjectName(), fileBytes == null ? 0 : fileBytes.length, downloadCostMillis);

            long parserStartedNanos = System.nanoTime();
            DocumentAnalysisResult analysisResult = parserService.parse(fileBytes, document.getOriginalFileName(),
                document.getMimeType(), DocumentFileTypeEnum.getRc(document.getFileType()));
            long parserCostMillis = elapsedMillis(parserStartedNanos);
            int artifactCount = analysisResult.getParseArtifacts() == null ? 0 : analysisResult.getParseArtifacts().size();
            int blockCount = analysisResult.getBlocks() == null ? 0 : analysisResult.getBlocks().size();
            int structureCandidateCount = analysisResult.getStructureNodes() == null ? 0 : analysisResult.getStructureNodes().size();
            log.info("文档解析服务调用完成，documentId={}, taskId={}, fileName={}, charCount={}, tokenCount={}, blockCount={}, artifactCount={}, structureCandidateCount={}, costMillis={}",
                documentId, taskId, document.getOriginalFileName(), analysisResult.getCharCount(), analysisResult.getTokenCount(),
                blockCount, artifactCount, structureCandidateCount, parserCostMillis);

            long parsedTextUploadStartedNanos = System.nanoTime();
            String parseTextPath = storageService.uploadParsedText(documentId, analysisResult.getParsedText());
            long parsedTextUploadCostMillis = elapsedMillis(parsedTextUploadStartedNanos);
            log.info("解析文本上传完成，documentId={}, taskId={}, parseTextPath={}, charCount={}, costMillis={}",
                documentId, taskId, parseTextPath, analysisResult.getCharCount(), parsedTextUploadCostMillis);

            long artifactPersistStartedNanos = System.nanoTime();
            saveParseArtifactsAndBlocks(documentId, taskId, analysisResult);
            long artifactPersistCostMillis = elapsedMillis(artifactPersistStartedNanos);
            log.info("解析产物和 blocks 入库完成，documentId={}, taskId={}, artifactCount={}, blockCount={}, costMillis={}",
                documentId, taskId, artifactCount, blockCount, artifactPersistCostMillis);

            long structurePersistStartedNanos = System.nanoTime();
            List<SuperAgentDocumentStructureNode> structureNodes = structureNodeService.replaceDocumentNodes(
                documentId,
                taskId,
                analysisResult.getStructureNodes()
            );
            long structurePersistCostMillis = elapsedMillis(structurePersistStartedNanos);
            int structureNodeCount = structureNodes.size();
            log.info("结构节点入库完成，documentId={}, taskId={}, structureNodeCount={}, costMillis={}",
                documentId, taskId, structureNodeCount, structurePersistCostMillis);

            long navigationStartedNanos = System.nanoTime();
            syncNavigationArtifacts(documentId, taskId, structureNodes);
            long navigationCostMillis = elapsedMillis(navigationStartedNanos);
            log.info("导航产物同步完成，documentId={}, taskId={}, structureNodeCount={}, costMillis={}",
                documentId, taskId, structureNodeCount, navigationCostMillis);

            long profileStartedNanos = System.nanoTime();
            documentProfileService.generateProfile(documentId, analysisResult, structureNodes);
            long profileCostMillis = elapsedMillis(profileStartedNanos);
            log.info("文档画像生成流程完成，documentId={}, taskId={}, costMillis={}",
                documentId, taskId, profileCostMillis);

            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.CONTENT_PARSE.getCode(),
                DocumentTaskEventTypeEnum.COMPLETE.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "文档解析完成，解析服务耗时 " + parserCostMillis + "ms，总耗时 " + elapsedMillis(parseRouteStartedNanos) + "ms。",
                detail(
                    "charCount", analysisResult.getCharCount(),
                    "tokenCount", analysisResult.getTokenCount(),
                    "structureLevel", analysisResult.getStructureLevel(),
                    "contentQualityLevel", analysisResult.getContentQualityLevel(),
                    "structureNodeCount", structureNodeCount,
                    "artifactCount", artifactCount,
                    "blockCount", blockCount,
                    "downloadCostMillis", downloadCostMillis,
                    "parserCostMillis", parserCostMillis,
                    "parsedTextUploadCostMillis", parsedTextUploadCostMillis,
                    "artifactPersistCostMillis", artifactPersistCostMillis,
                    "structurePersistCostMillis", structurePersistCostMillis,
                    "navigationCostMillis", navigationCostMillis,
                    "profileCostMillis", profileCostMillis,
                    "costMillis", elapsedMillis(parseRouteStartedNanos)
                ));

            task.setCurrentStage(DocumentTaskStageEnum.STRATEGY_ROUTE.getCode());
            taskMapper.updateById(task);

            long strategyRecommendStartedNanos = System.nanoTime();
            DocumentStrategyPlanDraft planDraft = strategyService.recommendStrategy(document, analysisResult);
            long strategyRecommendCostMillis = elapsedMillis(strategyRecommendStartedNanos);
            log.info("文档策略推荐计算完成，documentId={}, taskId={}, parentStepCount={}, childStepCount={}, costMillis={}",
                documentId, taskId, planDraft.getParentSteps().size(), planDraft.getChildSteps().size(), strategyRecommendCostMillis);

            Long planId = uidGenerator.getUid();
            int planVersion = getNextPlanVersion(documentId);

            long strategyPersistStartedNanos = System.nanoTime();
            SuperAgentDocumentStrategyPlan plan = new SuperAgentDocumentStrategyPlan();
            plan.setId(planId);
            plan.setDocumentId(documentId);
            plan.setPlanVersion(planVersion);
            plan.setPlanSource(DocumentPlanSourceEnum.SYSTEM_RECOMMEND.getCode());
            plan.setPlanStatus(DocumentPlanStatusEnum.WAIT_CONFIRM.getCode());
            plan.setStrategyCount(planDraft.getParentSteps().size() + planDraft.getChildSteps().size());
            plan.setStrategySnapshot(planDraft.getStrategySnapshot());
            plan.setRecommendReason(planDraft.getRecommendReason());
            plan.setStatus(BusinessStatus.YES.getCode());
            planMapper.insert(plan);

            for (int index = 0; index < planDraft.getParentSteps().size(); index++) {
                DocumentStrategyStepDraft draft = planDraft.getParentSteps().get(index);
                SuperAgentDocumentStrategyStep step = new SuperAgentDocumentStrategyStep();
                step.setId(uidGenerator.getUid());
                step.setPlanId(planId);
                step.setDocumentId(documentId);
                step.setPipelineType(draft.getPipelineType());
                step.setStepNo(index + 1);
                step.setStrategyType(draft.getStrategyType());
                step.setStrategyRole(draft.getStrategyRole());
                step.setSourceType(draft.getSourceType());
                step.setExecuteStatus(DocumentStrategyExecuteStatusEnum.WAIT_EXECUTE.getCode());
                step.setRecommendReason(draft.getRecommendReason());
                step.setStatus(BusinessStatus.YES.getCode());
                stepMapper.insert(step);
            }
            for (int index = 0; index < planDraft.getChildSteps().size(); index++) {
                DocumentStrategyStepDraft draft = planDraft.getChildSteps().get(index);
                SuperAgentDocumentStrategyStep step = new SuperAgentDocumentStrategyStep();
                step.setId(uidGenerator.getUid());
                step.setPlanId(planId);
                step.setDocumentId(documentId);
                step.setPipelineType(draft.getPipelineType());
                step.setStepNo(index + 1);
                step.setStrategyType(draft.getStrategyType());
                step.setStrategyRole(draft.getStrategyRole());
                step.setSourceType(draft.getSourceType());
                step.setExecuteStatus(DocumentStrategyExecuteStatusEnum.WAIT_EXECUTE.getCode());
                step.setRecommendReason(draft.getRecommendReason());
                step.setStatus(BusinessStatus.YES.getCode());
                stepMapper.insert(step);
            }
            long strategyPersistCostMillis = elapsedMillis(strategyPersistStartedNanos);
            log.info("文档推荐策略入库完成，documentId={}, taskId={}, planId={}, planVersion={}, parentStepCount={}, childStepCount={}, costMillis={}",
                documentId, taskId, planId, planVersion, planDraft.getParentSteps().size(), planDraft.getChildSteps().size(),
                strategyPersistCostMillis);

            document.setParseStatus(DocumentParseStatusEnum.PARSE_SUCCESS.getCode());
            document.setStrategyStatus(DocumentStrategyStatusEnum.RECOMMENDED.getCode());
            document.setCharCount(analysisResult.getCharCount());
            document.setTokenCount(analysisResult.getTokenCount());
            document.setStructureLevel(analysisResult.getStructureLevel());
            document.setContentQualityLevel(analysisResult.getContentQualityLevel());
            document.setParseTextPath(parseTextPath);
            document.setParseErrorMsg(null);
            document.setCurrentPlanId(planId);
            document.setLastParseTaskId(taskId);
            document.setStructureNodeCount(structureNodeCount);
            documentMapper.updateById(document);

            finishTaskSuccess(task, DocumentTaskStageEnum.STRATEGY_ROUTE.getCode(), startTime);
            taskLogService.saveLog(taskId, documentId,
                DocumentTaskStageEnum.STRATEGY_ROUTE.getCode(),
                DocumentTaskEventTypeEnum.RECOMMEND_STRATEGY.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "系统已生成推荐策略，推荐耗时 " + strategyRecommendCostMillis + "ms，入库耗时 "
                    + strategyPersistCostMillis + "ms，总耗时 " + elapsedMillis(parseRouteStartedNanos) + "ms。",
                detail("planId", planId,
                    "strategySnapshot", planDraft.getStrategySnapshot(),
                    "parentStepCount", planDraft.getParentSteps().size(),
                    "childStepCount", planDraft.getChildSteps().size(),
                    "structureNodeCount", structureNodeCount,
                    "recommendReason", planDraft.getRecommendReason(),
                    "strategyRecommendCostMillis", strategyRecommendCostMillis,
                    "strategyPersistCostMillis", strategyPersistCostMillis,
                    "costMillis", elapsedMillis(parseRouteStartedNanos)));
            log.info("异步解析文档完成，documentId={}, taskId={}, planId={}, charCount={}, tokenCount={}, blockCount={}, structureNodeCount={}, costMillis={}",
                documentId, taskId, planId, analysisResult.getCharCount(), analysisResult.getTokenCount(),
                blockCount, structureNodeCount, elapsedMillis(parseRouteStartedNanos));
        }
        catch (Exception exception) {
            long failedCostMillis = elapsedMillis(parseRouteStartedNanos);
            Integer failedStage = task.getCurrentStage() == null
                ? DocumentTaskStageEnum.CONTENT_PARSE.getCode() : task.getCurrentStage();
            log.error("异步解析文档失败，documentId={}, taskId={}, currentStage={}, costMillis={}",
                documentId, taskId, stageLabel(failedStage), failedCostMillis, exception);

            document.setParseStatus(DocumentParseStatusEnum.PARSE_FAILED.getCode());
            document.setParseErrorMsg(exception.getMessage());
            documentMapper.updateById(document);

            failTask(task, startTime, exception, failedStage);
            taskLogService.saveLog(taskId, documentId,
                failedStage,
                DocumentTaskEventTypeEnum.FAILED.getCode(),
                DocumentLogLevelEnum.ERROR.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "文档解析失败，当前阶段 " + stageLabel(failedStage) + "，已耗时 " + failedCostMillis + "ms。",
                detail("error", exception.getMessage(),
                    "currentStage", failedStage,
                    "currentStageName", stageName(failedStage),
                    "costMillis", failedCostMillis));
        }
    }

    @Override
    public void submitIndexBuild(Long documentId, Long taskId, Long planId) {
        SuperAgentDocumentTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("索引构建任务不存在，跳过提交后台执行，documentId={}, taskId={}, planId={}",
                documentId, taskId, planId);
            return;
        }
        if (Objects.equals(task.getTaskStatus(), DocumentTaskStatusEnum.SUCCESS.getCode())) {
            log.info("索引构建任务已成功，跳过 Kafka 重复投递消息，documentId={}, taskId={}, planId={}",
                documentId, taskId, planId);
            return;
        }
        if (!RUNNING_INDEX_TASK_IDS.add(taskId)) {
            log.warn("索引构建任务已在本机执行中，跳过重复提交，documentId={}, taskId={}, planId={}",
                documentId, taskId, planId);
            return;
        }

        try {
            indexBuildExecutorService.execute(() -> {
                try {
                    doHandleIndexBuild(documentId, taskId, planId);
                }
                finally {
                    RUNNING_INDEX_TASK_IDS.remove(taskId);
                }
            });
            log.info("索引构建任务已提交后台执行，documentId={}, taskId={}, planId={}", documentId, taskId, planId);
        }
        catch (RejectedExecutionException exception) {
            RUNNING_INDEX_TASK_IDS.remove(taskId);
            log.error("索引构建后台线程池已满，无法提交任务，documentId={}, taskId={}, planId={}",
                documentId, taskId, planId, exception);
            throw exception;
        }
    }

    @Override
    public void handleIndexBuild(Long documentId, Long taskId, Long planId) {
        if (!RUNNING_INDEX_TASK_IDS.add(taskId)) {
            log.warn("索引构建任务已在本机执行中，跳过重复执行，documentId={}, taskId={}, planId={}",
                documentId, taskId, planId);
            return;
        }
        try {
            doHandleIndexBuild(documentId, taskId, planId);
        }
        finally {
            RUNNING_INDEX_TASK_IDS.remove(taskId);
        }
    }

    private void doHandleIndexBuild(Long documentId, Long taskId, Long planId) {

        SuperAgentDocument document = documentMapper.selectById(documentId);
        SuperAgentDocumentTask task = taskMapper.selectById(taskId);
        SuperAgentDocumentStrategyPlan plan = planMapper.selectById(planId);
        if (document == null || task == null || plan == null) {
            log.warn("索引任务对应的数据不存在，documentId={}, taskId={}, planId={}", documentId, taskId, planId);
            return;
        }
        if (Objects.equals(task.getTaskStatus(), DocumentTaskStatusEnum.SUCCESS.getCode())) {
            log.info("索引构建任务已成功，跳过重复执行，documentId={}, taskId={}, planId={}",
                documentId, taskId, planId);
            return;
        }
        boolean reenterRunningTask = Objects.equals(task.getTaskStatus(), DocumentTaskStatusEnum.RUNNING.getCode());

        Date startTime = new Date();
        long buildStartedNanos = System.nanoTime();
        log.info("开始执行索引构建任务，documentId={}, taskId={}, planId={}, lastParseTaskId={}",
            documentId, taskId, planId, document.getLastParseTaskId());

        List<SuperAgentDocumentStrategyStep> stepList = listSteps(planId);
        log.info("索引构建策略步骤读取完成，documentId={}, taskId={}, planId={}, stepCount={}",
            documentId, taskId, planId, stepList.size());
        try {

            task.setTaskStatus(DocumentTaskStatusEnum.RUNNING.getCode());
            task.setCurrentStage(DocumentTaskStageEnum.CHUNK_EXECUTE.getCode());
            task.setStartTime(startTime);
            taskMapper.updateById(task);

            document.setIndexStatus(DocumentIndexStatusEnum.BUILDING.getCode());
            documentMapper.updateById(document);
            progressCacheService.init(document, task);

            if (reenterRunningTask) {
                cleanupIndexBuildTaskArtifacts(documentId, taskId);
            }

            updateStepExecuteStatus(planId, DocumentStrategyExecuteStatusEnum.EXECUTING.getCode());

            saveIndexBuildLog(taskId, documentId,
                DocumentTaskStageEnum.CHUNK_EXECUTE.getCode(),
                DocumentTaskEventTypeEnum.START.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
	                "开始执行切块流水线。",
	                Map.of("strategySnapshot", plan.getStrategySnapshot()));

	            long chunkStartedNanos = System.nanoTime();
	            long blockLoadStartedNanos = System.nanoTime();
	            List<SuperAgentDocumentBlock> documentBlocks = parseArtifactService.listBlocks(documentId, document.getLastParseTaskId());
	            long blockLoadCostMillis = elapsedMillis(blockLoadStartedNanos);
	            log.info("索引构建读取解析 blocks 完成，documentId={}, taskId={}, lastParseTaskId={}, blockCount={}, costMillis={}",
	                documentId, taskId, document.getLastParseTaskId(), documentBlocks.size(), blockLoadCostMillis);
	            if (documentBlocks.isEmpty()) {
	                throw new IllegalStateException("当前文档没有结构化解析 blocks，无法执行 Parent/Child 切块。");
	            }

	            long parentBuildStartedNanos = System.nanoTime();
	            List<ParentBlockCandidate> parentBlockCandidateList = strategyService.buildParentBlocks(document, plan, stepList, documentBlocks);
	            long parentBuildCostMillis = elapsedMillis(parentBuildStartedNanos);
	            long chunkCostMillis = elapsedMillis(chunkStartedNanos);
	            log.info("切块流水线执行完成，documentId={}, taskId={}, blockCount={}, parentCount={}, childCount={}, blockLoadCostMillis={}, parentBuildCostMillis={}, costMillis={}",
	                documentId, taskId, documentBlocks.size(), parentBlockCandidateList.size(),
	                countChildCandidates(parentBlockCandidateList), blockLoadCostMillis, parentBuildCostMillis, chunkCostMillis);

            updateStepExecuteStatus(planId, DocumentStrategyExecuteStatusEnum.EXECUTE_SUCCESS.getCode());

            saveIndexBuildLog(taskId, documentId,
                DocumentTaskStageEnum.CHUNK_EXECUTE.getCode(),
                DocumentTaskEventTypeEnum.COMPLETE.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "切块执行完成，读取 blocks 耗时 " + blockLoadCostMillis + "ms，Parent/Child 候选构建耗时 "
                    + parentBuildCostMillis + "ms，总耗时 " + chunkCostMillis + "ms。",
                Map.of(
	                    "parentCount", parentBlockCandidateList.size(),
	                    "childCount", countChildCandidates(parentBlockCandidateList),
	                    "blockCount", documentBlocks.size(),
	                    "blockLoadCostMillis", blockLoadCostMillis,
	                    "parentBuildCostMillis", parentBuildCostMillis,
	                    "costMillis", chunkCostMillis
	                ));

	            task.setCurrentStage(DocumentTaskStageEnum.CHUNK_POST_PROCESS.getCode());
	            taskMapper.updateById(task);

	            long postProcessStartedNanos = System.nanoTime();
	            List<ParentBlockCandidate> finalParentBlockList = parentBlockCandidateList.stream()
	                .filter(item -> item != null
	                    && StrUtil.isNotBlank(item.getText())
	                    && item.getChildChunks() != null
	                    && item.getChildChunks().stream().anyMatch(child -> StrUtil.isNotBlank(child.getText())))
	                .toList();
	            long postProcessCostMillis = elapsedMillis(postProcessStartedNanos);
	            log.info("切块后处理完成，documentId={}, taskId={}, parentCount={}, childCount={}, costMillis={}",
	                documentId, taskId, finalParentBlockList.size(), countChildCandidates(finalParentBlockList), postProcessCostMillis);

            saveIndexBuildLog(taskId, documentId,
                DocumentTaskStageEnum.CHUNK_POST_PROCESS.getCode(),
                DocumentTaskEventTypeEnum.COMPLETE.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
                "切块后处理完成，耗时 " + postProcessCostMillis + "ms。",
                Map.of(
	                    "parentCount", finalParentBlockList.size(),
	                    "childCount", countChildCandidates(finalParentBlockList),
	                    "costMillis", postProcessCostMillis
	                ));

	            long parentChildEntityBuildStartedNanos = System.nanoTime();
	            ParentChildEntityBundle entityBundle = buildParentChildEntities(documentId, taskId, planId, finalParentBlockList);
	            long parentChildEntityBuildCostMillis = elapsedMillis(parentChildEntityBuildStartedNanos);
	            List<SuperAgentDocumentParentBlock> parentBlockEntityList = entityBundle.parentBlocks();
	            List<SuperAgentDocumentChunk> chunkEntityList = entityBundle.childChunks();
	            log.info("Parent/Child 实体构建完成，documentId={}, taskId={}, parentCount={}, chunkCount={}, costMillis={}",
	                documentId, taskId, parentBlockEntityList.size(), chunkEntityList.size(), parentChildEntityBuildCostMillis);

	            long parentChildPersistStartedNanos = System.nanoTime();
	            MybatisBatchExecutor.insertBatch(SuperAgentDocumentParentBlock.class, parentBlockEntityList);
	            MybatisBatchExecutor.insertBatch(SuperAgentDocumentChunk.class, chunkEntityList);
	            long parentChildPersistCostMillis = elapsedMillis(parentChildPersistStartedNanos);
	            log.info("Parent/Child 元数据入库完成，documentId={}, taskId={}, parentCount={}, chunkCount={}, costMillis={}",
	                documentId, taskId, parentBlockEntityList.size(), chunkEntityList.size(), parentChildPersistCostMillis);

	            task.setCurrentStage(DocumentTaskStageEnum.VECTORIZE.getCode());
	            taskMapper.updateById(task);

	            int embeddingBatchSize = embeddingBatchSize();
	            saveIndexBuildLog(taskId, documentId,
	                DocumentTaskStageEnum.VECTORIZE.getCode(),
	                DocumentTaskEventTypeEnum.START.getCode(),
	                DocumentLogLevelEnum.INFO.getCode(),
	                DocumentOperatorTypeEnum.SYSTEM.getCode(),
	                null,
	                "开始执行向量化。",
	                detail("chunkCount", chunkEntityList.size(),
	                    "embeddingBatchSize", embeddingBatchSize,
	                    "embeddingBatchCount", batchCount(chunkEntityList.size(), embeddingBatchSize),
	                    "vectorStoreType", DocumentVectorStoreTypeEnum.PG_VECTOR.getMsg(),
	                    "parentCount", parentBlockEntityList.size()));
	            log.info("开始执行原文 chunk 向量化阶段，documentId={}, taskId={}, chunkCount={}, parentCount={}, embeddingBatchSize={}, embeddingBatchCount={}",
	                documentId, taskId, chunkEntityList.size(), parentBlockEntityList.size(), embeddingBatchSize,
	                batchCount(chunkEntityList.size(), embeddingBatchSize));

	            long vectorStartedNanos = System.nanoTime();
	            vectorGateway.vectorize(chunkEntityList);
	            long vectorCostMillis = elapsedMillis(vectorStartedNanos);
	            long chunkUpdateStartedNanos = System.nanoTime();
	            MybatisBatchExecutor.updateBatchById(SuperAgentDocumentChunk.class, chunkEntityList);
	            long chunkUpdateCostMillis = elapsedMillis(chunkUpdateStartedNanos);
	            log.info("原文 chunk 向量化阶段完成，documentId={}, taskId={}, chunkCount={}, vectorCostMillis={}, chunkUpdateCostMillis={}",
	                documentId, taskId, chunkEntityList.size(), vectorCostMillis, chunkUpdateCostMillis);

	            saveIndexBuildLog(taskId, documentId,
	                DocumentTaskStageEnum.VECTORIZE.getCode(),
	                DocumentTaskEventTypeEnum.COMPLETE.getCode(),
	                DocumentLogLevelEnum.INFO.getCode(),
	                DocumentOperatorTypeEnum.SYSTEM.getCode(),
	                null,
	                "向量化完成，向量写入耗时 " + vectorCostMillis + "ms，chunk 状态更新耗时 "
	                    + chunkUpdateCostMillis + "ms。",
	                detail("chunkCount", chunkEntityList.size(),
	                    "embeddingBatchSize", embeddingBatchSize,
	                    "embeddingBatchCount", batchCount(chunkEntityList.size(), embeddingBatchSize),
	                    "vectorStoreType", DocumentVectorStoreTypeEnum.PG_VECTOR.getMsg(),
	                    "parentCount", parentBlockEntityList.size(),
	                    "vectorCostMillis", vectorCostMillis,
	                    "chunkUpdateCostMillis", chunkUpdateCostMillis));

	            DocumentKeywordSearchGateway keywordSearchGateway = keywordSearchGatewayProvider.getIfAvailable();
	            task.setCurrentStage(DocumentTaskStageEnum.KEYWORD_INDEX.getCode());
	            taskMapper.updateById(task);
	            saveIndexBuildLog(taskId, documentId,
	                DocumentTaskStageEnum.KEYWORD_INDEX.getCode(),
	                DocumentTaskEventTypeEnum.START.getCode(),
	                DocumentLogLevelEnum.INFO.getCode(),
	                DocumentOperatorTypeEnum.SYSTEM.getCode(),
	                null,
	                "开始构建关键词索引。",
	                detail("chunkCount", chunkEntityList.size(), "enabled", keywordSearchGateway != null));
	            log.info("开始构建关键词索引，documentId={}, taskId={}, chunkCount={}, enabled={}",
	                documentId, taskId, chunkEntityList.size(), keywordSearchGateway != null);
	            long keywordStartedNanos = System.nanoTime();
	            if (keywordSearchGateway != null) {
	                keywordSearchGateway.indexChunks(chunkEntityList);
	            }
	            long keywordCostMillis = elapsedMillis(keywordStartedNanos);
	            log.info("关键词索引阶段完成，documentId={}, taskId={}, chunkCount={}, enabled={}, costMillis={}",
	                documentId, taskId, chunkEntityList.size(), keywordSearchGateway != null, keywordCostMillis);
	            saveIndexBuildLog(taskId, documentId,
	                DocumentTaskStageEnum.KEYWORD_INDEX.getCode(),
	                DocumentTaskEventTypeEnum.COMPLETE.getCode(),
	                DocumentLogLevelEnum.INFO.getCode(),
	                DocumentOperatorTypeEnum.SYSTEM.getCode(),
	                null,
	                "关键词索引完成，耗时 " + keywordCostMillis + "ms。",
	                detail("chunkCount", chunkEntityList.size(), "enabled", keywordSearchGateway != null, "costMillis", keywordCostMillis));

	            task.setCurrentStage(DocumentTaskStageEnum.GRAPH_RAG.getCode());
	            taskMapper.updateById(task);
	            saveIndexBuildLog(taskId, documentId,
	                DocumentTaskStageEnum.GRAPH_RAG.getCode(),
	                DocumentTaskEventTypeEnum.START.getCode(),
	                DocumentLogLevelEnum.INFO.getCode(),
	                DocumentOperatorTypeEnum.SYSTEM.getCode(),
	                null,
	                "开始构建 GraphRAG 实体关系图谱。",
	                detail("chunkCount", chunkEntityList.size(), "parentCount", parentBlockEntityList.size()));
	            log.info("开始构建 GraphRAG 实体关系图谱，documentId={}, taskId={}, chunkCount={}, parentCount={}",
	                documentId, taskId, chunkEntityList.size(), parentBlockEntityList.size());

	            long graphRagStartedNanos = System.nanoTime();
	            GraphRagBuildResult graphRagBuildResult = graphRagBuildService.rebuildDocumentGraph(documentId, taskId, chunkEntityList);
	            long graphRagCostMillis = elapsedMillis(graphRagStartedNanos);
	            log.info("GraphRAG 构建阶段完成，documentId={}, taskId={}, entityCount={}, relationCount={}, evidenceCount={}, communityCount={}, costMillis={}",
	                documentId, taskId, graphRagBuildResult.getEntityCount(), graphRagBuildResult.getRelationCount(),
	                graphRagBuildResult.getEvidenceCount(), graphRagBuildResult.getCommunityCount(), graphRagCostMillis);

	            saveIndexBuildLog(taskId, documentId,
	                DocumentTaskStageEnum.GRAPH_RAG.getCode(),
	                DocumentTaskEventTypeEnum.COMPLETE.getCode(),
	                DocumentLogLevelEnum.INFO.getCode(),
	                DocumentOperatorTypeEnum.SYSTEM.getCode(),
	                null,
	                "GraphRAG 实体关系图谱构建完成，耗时 " + graphRagCostMillis + "ms。",
	                detail("entityCount", graphRagBuildResult.getEntityCount(),
	                    "relationCount", graphRagBuildResult.getRelationCount(),
	                    "evidenceCount", graphRagBuildResult.getEvidenceCount(),
	                    "communityCount", graphRagBuildResult.getCommunityCount(),
	                    "costMillis", graphRagCostMillis));

	            long graphTypedBuildStartedNanos = System.nanoTime();
	            log.info("开始构建 GraphRAG typed chunks，documentId={}, taskId={}", documentId, taskId);
	            List<SuperAgentDocumentChunk> graphTypedChunkList = graphRagTypedChunkService.buildTypedChunks(
	                documentId,
	                taskId,
	                planId,
	                chunkEntityList,
	                nextChunkNo(chunkEntityList)
	            );
	            long graphTypedBuildCostMillis = elapsedMillis(graphTypedBuildStartedNanos);
	            if (!graphTypedChunkList.isEmpty()) {
	                task.setCurrentStage(DocumentTaskStageEnum.GRAPH_TYPED_INDEX.getCode());
	                taskMapper.updateById(task);
	                saveIndexBuildLog(taskId, documentId,
	                    DocumentTaskStageEnum.GRAPH_TYPED_INDEX.getCode(),
	                    DocumentTaskEventTypeEnum.START.getCode(),
	                    DocumentLogLevelEnum.INFO.getCode(),
	                    DocumentOperatorTypeEnum.SYSTEM.getCode(),
	                    null,
	                    "开始索引 GraphRAG typed chunks。",
	                    detail("graphTypedChunkCount", graphTypedChunkList.size(), "buildCostMillis", graphTypedBuildCostMillis));
	                log.info("开始索引 GraphRAG typed chunks，documentId={}, taskId={}, graphTypedChunkCount={}, buildCostMillis={}",
	                    documentId, taskId, graphTypedChunkList.size(), graphTypedBuildCostMillis);

	                long graphTypedInsertStartedNanos = System.nanoTime();
	                MybatisBatchExecutor.insertBatch(SuperAgentDocumentChunk.class, graphTypedChunkList);
	                long graphTypedInsertCostMillis = elapsedMillis(graphTypedInsertStartedNanos);
	                long graphTypedVectorStartedNanos = System.nanoTime();
	                vectorGateway.vectorize(graphTypedChunkList);
	                long graphTypedVectorCostMillis = elapsedMillis(graphTypedVectorStartedNanos);
	                long graphTypedKeywordStartedNanos = System.nanoTime();
	                if (keywordSearchGateway != null) {
	                    keywordSearchGateway.indexChunks(graphTypedChunkList);
	                }
	                long graphTypedKeywordCostMillis = elapsedMillis(graphTypedKeywordStartedNanos);
	                long graphTypedUpdateStartedNanos = System.nanoTime();
	                MybatisBatchExecutor.updateBatchById(SuperAgentDocumentChunk.class, graphTypedChunkList);
	                long graphTypedUpdateCostMillis = elapsedMillis(graphTypedUpdateStartedNanos);
	                log.info("GraphRAG typed chunks 索引阶段完成，documentId={}, taskId={}, graphTypedChunkCount={}, buildCostMillis={}, insertCostMillis={}, vectorCostMillis={}, keywordCostMillis={}, updateCostMillis={}",
	                    documentId, taskId, graphTypedChunkList.size(), graphTypedBuildCostMillis, graphTypedInsertCostMillis,
	                    graphTypedVectorCostMillis, graphTypedKeywordCostMillis, graphTypedUpdateCostMillis);

	                saveIndexBuildLog(taskId, documentId,
	                    DocumentTaskStageEnum.GRAPH_TYPED_INDEX.getCode(),
	                    DocumentTaskEventTypeEnum.COMPLETE.getCode(),
	                    DocumentLogLevelEnum.INFO.getCode(),
	                    DocumentOperatorTypeEnum.SYSTEM.getCode(),
	                    null,
	                    "GraphRAG typed chunks 索引完成，构建耗时 " + graphTypedBuildCostMillis
	                        + "ms，入库耗时 " + graphTypedInsertCostMillis
	                        + "ms，向量耗时 " + graphTypedVectorCostMillis
	                        + "ms，关键词索引耗时 " + graphTypedKeywordCostMillis
	                        + "ms，状态更新耗时 " + graphTypedUpdateCostMillis + "ms。",
	                    detail("graphTypedChunkCount", graphTypedChunkList.size(),
	                        "chunkTypes", List.of("GRAPH_ENTITY", "GRAPH_RELATION", "GRAPH_COMMUNITY"),
	                        "buildCostMillis", graphTypedBuildCostMillis,
	                        "insertCostMillis", graphTypedInsertCostMillis,
	                        "vectorCostMillis", graphTypedVectorCostMillis,
	                        "keywordCostMillis", graphTypedKeywordCostMillis,
	                        "updateCostMillis", graphTypedUpdateCostMillis));
	            }
	            else {
	                log.info("GraphRAG typed chunks 为空，跳过派生索引，documentId={}, taskId={}, buildCostMillis={}",
	                    documentId, taskId, graphTypedBuildCostMillis);
	            }

	            task.setCurrentStage(DocumentTaskStageEnum.RAPTOR.getCode());
	            taskMapper.updateById(task);
	            saveIndexBuildLog(taskId, documentId,
	                DocumentTaskStageEnum.RAPTOR.getCode(),
	                DocumentTaskEventTypeEnum.START.getCode(),
	                DocumentLogLevelEnum.INFO.getCode(),
	                DocumentOperatorTypeEnum.SYSTEM.getCode(),
	                null,
	                "开始构建 RAPTOR 层级摘要树。",
	                detail("chunkCount", chunkEntityList.size(), "parentCount", parentBlockEntityList.size()));
	            log.info("开始构建 RAPTOR 层级摘要树，documentId={}, taskId={}, chunkCount={}, parentCount={}",
	                documentId, taskId, chunkEntityList.size(), parentBlockEntityList.size());

	            long raptorStartedNanos = System.nanoTime();
	            RaptorBuildResult raptorBuildResult = raptorBuildService.rebuildDocumentTree(documentId, taskId, chunkEntityList);
	            long raptorCostMillis = elapsedMillis(raptorStartedNanos);
	            log.info("RAPTOR 构建阶段完成，documentId={}, taskId={}, nodeCount={}, levelCount={}, sourceChunkCount={}, costMillis={}",
	                documentId, taskId, raptorBuildResult.getNodeCount(), raptorBuildResult.getLevelCount(),
	                raptorBuildResult.getSourceChunkCount(), raptorCostMillis);

	            saveIndexBuildLog(taskId, documentId,
	                DocumentTaskStageEnum.RAPTOR.getCode(),
	                DocumentTaskEventTypeEnum.COMPLETE.getCode(),
	                DocumentLogLevelEnum.INFO.getCode(),
	                DocumentOperatorTypeEnum.SYSTEM.getCode(),
	                null,
	                "RAPTOR 层级摘要树构建完成，耗时 " + raptorCostMillis + "ms。",
	                detail("nodeCount", raptorBuildResult.getNodeCount(),
	                    "levelCount", raptorBuildResult.getLevelCount(),
	                    "sourceChunkCount", raptorBuildResult.getSourceChunkCount(),
	                    "sourceQualityReport", raptorBuildResult.getSourceQualityReport(),
	                    "savedQualityReport", raptorBuildResult.getSavedQualityReport(),
	                    "costMillis", raptorCostMillis));

            task.setCurrentStage(DocumentTaskStageEnum.STORE_COMPLETE.getCode());
            taskMapper.updateById(task);

            plan.setPlanStatus(DocumentPlanStatusEnum.EXECUTED.getCode());
            planMapper.updateById(plan);

            document.setIndexStatus(DocumentIndexStatusEnum.BUILD_SUCCESS.getCode());
            document.setLastIndexTaskId(taskId);
            documentMapper.updateById(document);

            finishTaskSuccess(task, DocumentTaskStageEnum.STORE_COMPLETE.getCode(), startTime);
            progressCacheService.update(document, task);
            saveIndexBuildLog(taskId, documentId,
                DocumentTaskStageEnum.STORE_COMPLETE.getCode(),
                DocumentTaskEventTypeEnum.COMPLETE.getCode(),
                DocumentLogLevelEnum.INFO.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
                null,
	                "索引构建完成，总耗时 " + elapsedMillis(buildStartedNanos) + "ms。",
	                Map.of("taskId", taskId,
	                    "chunkCount", chunkEntityList.size(),
	                    "graphTypedChunkCount", graphTypedChunkList.size(),
	                    "parentCount", parentBlockEntityList.size(),
	                    "costMillis", elapsedMillis(buildStartedNanos)));
	            log.info("索引构建任务执行完成，documentId={}, taskId={}, planId={}, parentCount={}, chunkCount={}, graphTypedChunkCount={}, costMillis={}",
	                documentId, taskId, planId, parentBlockEntityList.size(), chunkEntityList.size(), graphTypedChunkList.size(),
	                elapsedMillis(buildStartedNanos));
        }
        catch (Exception exception) {
            long failedCostMillis = elapsedMillis(buildStartedNanos);
            Integer failedStage = task.getCurrentStage() == null
                ? DocumentTaskStageEnum.CHUNK_EXECUTE.getCode() : task.getCurrentStage();
            log.error("异步构建索引失败，documentId={}, taskId={}, planId={}, currentStage={}, costMillis={}",
                documentId, taskId, planId, stageLabel(failedStage), failedCostMillis, exception);

            document.setIndexStatus(DocumentIndexStatusEnum.BUILD_FAILED.getCode());
            documentMapper.updateById(document);

	            if (Objects.equals(failedStage, DocumentTaskStageEnum.VECTORIZE.getCode())) {
	                chunkMapper.update(null, new LambdaUpdateWrapper<SuperAgentDocumentChunk>()
	                    .eq(SuperAgentDocumentChunk::getTaskId, taskId)
	                    .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode())
	                    .set(SuperAgentDocumentChunk::getVectorStatus, DocumentVectorStatusEnum.VECTOR_FAILED.getCode())
	                    .set(SuperAgentDocumentChunk::getVectorStoreType, DocumentVectorStoreTypeEnum.PG_VECTOR.getCode()));
	            }

            updateStepExecuteStatus(planId, DocumentStrategyExecuteStatusEnum.EXECUTE_FAILED.getCode());
            failTask(task, startTime, exception, failedStage);
            progressCacheService.update(document, task);
            saveIndexBuildLog(taskId, documentId,
                failedStage,
                DocumentTaskEventTypeEnum.FAILED.getCode(),
                DocumentLogLevelEnum.ERROR.getCode(),
                DocumentOperatorTypeEnum.SYSTEM.getCode(),
	                null,
	                "索引构建失败，当前阶段 " + stageLabel(failedStage) + "，已耗时 " + failedCostMillis + "ms。",
	                detail("error", exception.getMessage(),
	                    "currentStage", failedStage,
	                    "currentStageName", stageName(failedStage),
	                    "costMillis", failedCostMillis));
        }
    }

    private void cleanupIndexBuildTaskArtifacts(Long documentId, Long taskId) {
        log.warn("检测到索引构建任务重入，开始清理同 task 旧产物，documentId={}, taskId={}", documentId, taskId);
        long startedNanos = System.nanoTime();

        vectorGateway.deleteByTask(documentId, taskId);

        DocumentKeywordSearchGateway keywordSearchGateway = keywordSearchGatewayProvider.getIfAvailable();
        if (keywordSearchGateway != null) {
            keywordSearchGateway.deleteByTask(documentId, taskId);
        }

        graphRagBuildService.deleteByTask(documentId, taskId);
        raptorBuildService.deleteByTask(documentId, taskId);

        int deletedChunkCount = chunkMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentChunk>()
            .eq(SuperAgentDocumentChunk::getDocumentId, documentId)
            .eq(SuperAgentDocumentChunk::getTaskId, taskId));
        int deletedParentCount = parentBlockMapper.delete(new LambdaQueryWrapper<SuperAgentDocumentParentBlock>()
            .eq(SuperAgentDocumentParentBlock::getDocumentId, documentId)
            .eq(SuperAgentDocumentParentBlock::getTaskId, taskId));

        log.warn("索引构建任务重入清理完成，documentId={}, taskId={}, deletedParentCount={}, deletedChunkCount={}, costMillis={}",
            documentId, taskId, deletedParentCount, deletedChunkCount, elapsedMillis(startedNanos));
    }

    private void saveIndexBuildLog(Long taskId,
                                   Long documentId,
                                   Integer stageType,
                                   Integer eventType,
                                   Integer logLevel,
                                   Integer operatorType,
                                   Long operatorId,
                                   String content,
                                   Object detail) {
        SuperAgentDocumentTaskLog taskLog = taskLogService.saveLog(taskId, documentId, stageType, eventType, logLevel,
            operatorType, operatorId, content, detail);
        SuperAgentDocument document = documentMapper.selectById(documentId);
        SuperAgentDocumentTask task = taskMapper.selectById(taskId);
        progressCacheService.update(document, task, taskLog);
    }

    private ParentChildEntityBundle buildParentChildEntities(Long documentId,
                                                             Long taskId,
                                                             Long planId,
                                                             List<ParentBlockCandidate> parentBlockCandidateList) {
        List<SuperAgentDocumentParentBlock> parentBlockEntityList = new ArrayList<>();
        List<SuperAgentDocumentChunk> chunkEntityList = new ArrayList<>();
        int globalChunkNo = 1;

        for (int parentIndex = 0; parentIndex < parentBlockCandidateList.size(); parentIndex++) {
            ParentBlockCandidate parentCandidate = parentBlockCandidateList.get(parentIndex);
            if (parentCandidate == null || StrUtil.isBlank(parentCandidate.getText())) {
                continue;
            }

            SuperAgentDocumentParentBlock parentBlock = new SuperAgentDocumentParentBlock();
            parentBlock.setId(uidGenerator.getUid());
            parentBlock.setDocumentId(documentId);
            parentBlock.setTaskId(taskId);
            parentBlock.setPlanId(planId);
            parentBlock.setParentNo(parentIndex + 1);
            parentBlock.setSourceType(parentCandidate.getSourceType() == null
                ? DocumentChunkSourceTypeEnum.ORIGINAL.getCode() : parentCandidate.getSourceType());
            parentBlock.setSectionPath(parentCandidate.getSectionPath());
            parentBlock.setStructureNodeId(parentCandidate.getStructureNodeId());
            parentBlock.setStructureNodeType(parentCandidate.getStructureNodeType());
            parentBlock.setCanonicalPath(parentCandidate.getCanonicalPath());
            parentBlock.setItemIndex(parentCandidate.getItemIndex());
            parentBlock.setParentText(parentCandidate.getText().trim());
            parentBlock.setCharCount(parentCandidate.getText().length());
            parentBlock.setTokenCount(estimateTokenCount(parentCandidate.getText()));
            parentBlock.setStatus(BusinessStatus.YES.getCode());

            int startChunkNo = globalChunkNo;
            int childCount = 0;
            for (ChunkCandidate childCandidate : parentCandidate.getChildChunks()) {
                if (childCandidate == null || StrUtil.isBlank(childCandidate.getText())) {
                    continue;
                }
                SuperAgentDocumentChunk chunk = new SuperAgentDocumentChunk();
                chunk.setId(uidGenerator.getUid());
                chunk.setDocumentId(documentId);
                chunk.setTaskId(taskId);
                chunk.setPlanId(planId);
                chunk.setParentBlockId(parentBlock.getId());
                chunk.setChunkNo(globalChunkNo++);
                chunk.setSourceType(childCandidate.getSourceType() == null
                    ? DocumentChunkSourceTypeEnum.ORIGINAL.getCode() : childCandidate.getSourceType());
                chunk.setSectionPath(StrUtil.blankToDefault(childCandidate.getSectionPath(), parentCandidate.getSectionPath()));
                chunk.setStructureNodeId(childCandidate.getStructureNodeId());
                chunk.setStructureNodeType(childCandidate.getStructureNodeType());
                chunk.setCanonicalPath(childCandidate.getCanonicalPath());
                chunk.setItemIndex(childCandidate.getItemIndex());
                chunk.setChunkText(childCandidate.getText().trim());
                chunk.setContentWithWeight(StrUtil.blankToDefault(childCandidate.getContentWithWeight(), childCandidate.getText()).trim());
                chunk.setChunkType(childCandidate.getChunkType());
                chunk.setTitle(childCandidate.getTitle());
                chunk.setKeywords(childCandidate.getKeywords());
                chunk.setQuestions(childCandidate.getQuestions());
                chunk.setCharCount(childCandidate.getText().length());

                chunk.setTokenCount(estimateTokenCount(childCandidate.getText()));
                chunk.setVectorStatus(DocumentVectorStatusEnum.WAIT_VECTOR.getCode());
                chunk.setVectorStoreType(DocumentVectorStoreTypeEnum.PG_VECTOR.getCode());
                chunk.setPageNo(childCandidate.getPageNo());
                chunk.setPageRange(childCandidate.getPageRange());
                chunk.setBboxJson(childCandidate.getBboxJson());
                chunk.setSourceBlockIds(childCandidate.getSourceBlockIds());
                chunk.setStatus(BusinessStatus.YES.getCode());
                chunkEntityList.add(chunk);
                childCount++;
            }

            parentBlock.setChildCount(childCount);
            parentBlock.setStartChunkNo(childCount == 0 ? null : startChunkNo);
            parentBlock.setEndChunkNo(childCount == 0 ? null : globalChunkNo - 1);
            parentBlock.setPageRange(parentCandidate.getPageRange());
            parentBlock.setSourceBlockIds(parentCandidate.getSourceBlockIds());
            parentBlockEntityList.add(parentBlock);
        }

        return new ParentChildEntityBundle(parentBlockEntityList, chunkEntityList);
    }

    private int nextChunkNo(List<SuperAgentDocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return 1;
        }
        return chunks.stream()
            .map(SuperAgentDocumentChunk::getChunkNo)
            .filter(Objects::nonNull)
            .max(Integer::compareTo)
            .orElse(0) + 1;
    }

    private void saveParseArtifactsAndBlocks(Long documentId, Long taskId, DocumentAnalysisResult analysisResult) {
        List<SuperAgentDocumentParseArtifact> artifacts = buildParseArtifactEntities(
            documentId,
            taskId,
            analysisResult == null ? List.of() : analysisResult.getParseArtifacts()
        );
        List<SuperAgentDocumentBlock> blocks = buildDocumentBlockEntities(
            documentId,
            taskId,
            analysisResult == null ? List.of() : analysisResult.getBlocks()
        );
        parseArtifactService.replaceTaskArtifacts(documentId, taskId, artifacts, blocks);
    }

    private List<SuperAgentDocumentParseArtifact> buildParseArtifactEntities(Long documentId,
                                                                             Long taskId,
                                                                             List<DocumentParseArtifactCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<SuperAgentDocumentParseArtifact> artifacts = new ArrayList<>();
        for (DocumentParseArtifactCandidate candidate : candidates) {
            if (candidate == null || StrUtil.isBlank(candidate.getArtifactType()) || StrUtil.isBlank(candidate.getContentBase64())) {
                continue;
            }
            byte[] content = decodeBase64(candidate.getContentBase64(), "解析产物 " + candidate.getFileName());
            String objectName = storageService.uploadParseArtifact(
                documentId,
                taskId,
                StrUtil.blankToDefault(candidate.getFileName(), candidate.getArtifactType().toLowerCase() + ".bin"),
                content,
                candidate.getContentType()
            );

            SuperAgentDocumentParseArtifact artifact = new SuperAgentDocumentParseArtifact();
            artifact.setId(uidGenerator.getUid());
            artifact.setDocumentId(documentId);
            artifact.setTaskId(taskId);
            artifact.setArtifactType(candidate.getArtifactType());
            artifact.setObjectName(objectName);
            artifact.setContentHash(StrUtil.blankToDefault(candidate.getContentHash(), sha256(content)));
            artifact.setParserName(candidate.getParserName());
            artifact.setParserVersion(candidate.getParserVersion());
            artifact.setStatus(BusinessStatus.YES.getCode());
            artifacts.add(artifact);
        }
        return artifacts;
    }

    private List<SuperAgentDocumentBlock> buildDocumentBlockEntities(Long documentId,
                                                                     Long taskId,
                                                                     List<DocumentBlockCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<Integer, Long> idByBlockNo = new LinkedHashMap<>();
        for (DocumentBlockCandidate candidate : candidates) {
            if (candidate != null && candidate.getBlockNo() != null) {
                idByBlockNo.put(candidate.getBlockNo(), uidGenerator.getUid());
            }
        }

        List<SuperAgentDocumentBlock> blocks = new ArrayList<>();
        for (DocumentBlockCandidate candidate : candidates) {
            if (candidate == null || candidate.getBlockNo() == null || StrUtil.isBlank(candidate.getBlockType())) {
                continue;
            }
            SuperAgentDocumentBlock block = new SuperAgentDocumentBlock();
            block.setId(idByBlockNo.get(candidate.getBlockNo()));
            block.setDocumentId(documentId);
            block.setTaskId(taskId);
            block.setBlockNo(candidate.getBlockNo());
            block.setBlockType(candidate.getBlockType());
            block.setParentBlockId(candidate.getParentBlockNo() == null ? null : idByBlockNo.get(candidate.getParentBlockNo()));
            block.setSectionPath(candidate.getSectionPath());
            block.setCanonicalPath(candidate.getCanonicalPath());
            block.setPageNo(candidate.getPageNo());
            block.setPageRange(candidate.getPageRange());
            block.setBboxJson(candidate.getBboxJson());
            block.setText(candidate.getText());
            block.setContentWithWeight(candidate.getContentWithWeight());
            block.setTableHtml(candidate.getTableHtml());
            block.setImageObjectName(uploadBlockImage(documentId, taskId, candidate));
            block.setImageCaption(candidate.getImageCaption());
            block.setMetadataJson(mergeBlockMetadata(candidate));
            block.setStatus(BusinessStatus.YES.getCode());
            blocks.add(block);
        }
        return blocks;
    }

    private String mergeBlockMetadata(DocumentBlockCandidate candidate) {
        String metadataJson = StrUtil.blankToDefault(candidate.getMetadataJson(), "");
        if (StrUtil.isBlank(candidate.getTableRowsJson())) {
            return metadataJson;
        }
        String tableRowsJson = candidate.getTableRowsJson().trim();
        if (StrUtil.isBlank(metadataJson) || "{}".equals(metadataJson.trim())) {
            return "{\"tableRows\":" + tableRowsJson + "}";
        }
        String trimmedMetadata = metadataJson.trim();
        if (trimmedMetadata.endsWith("}")) {
            return trimmedMetadata.substring(0, trimmedMetadata.length() - 1) + ",\"tableRows\":" + tableRowsJson + "}";
        }
        return "{\"parserMetadata\":" + quoteJsonString(metadataJson) + ",\"tableRows\":" + tableRowsJson + "}";
    }

    private String quoteJsonString(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            + "\"";
    }

    private String uploadBlockImage(Long documentId, Long taskId, DocumentBlockCandidate candidate) {
        if (candidate == null || StrUtil.isBlank(candidate.getImageContentBase64())) {
            return null;
        }
        byte[] content = decodeBase64(candidate.getImageContentBase64(), "block image " + candidate.getBlockNo());
        return storageService.uploadParseArtifact(
            documentId,
            taskId,
            StrUtil.blankToDefault(candidate.getImageFileName(), "block-" + candidate.getBlockNo() + ".png"),
            content,
            "image/png"
        );
    }

    private int countChildCandidates(List<ParentBlockCandidate> parentBlockCandidateList) {
        if (parentBlockCandidateList == null || parentBlockCandidateList.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (ParentBlockCandidate candidate : parentBlockCandidateList) {
            if (candidate == null || candidate.getChildChunks() == null) {
                continue;
            }
            count += (int) candidate.getChildChunks().stream()
                .filter(child -> child != null && StrUtil.isNotBlank(child.getText()))
                .count();
        }
        return count;
    }

    private void updateStepExecuteStatus(Long planId, Integer executeStatus) {

        stepMapper.update(null, new LambdaUpdateWrapper<SuperAgentDocumentStrategyStep>()
            .eq(SuperAgentDocumentStrategyStep::getPlanId, planId)
            .eq(SuperAgentDocumentStrategyStep::getStatus, BusinessStatus.YES.getCode())
            .set(SuperAgentDocumentStrategyStep::getExecuteStatus, executeStatus));
    }

    private List<SuperAgentDocumentStrategyStep> listSteps(Long planId) {
        List<SuperAgentDocumentStrategyStep> stepList = stepMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentStrategyStep>()
            .eq(SuperAgentDocumentStrategyStep::getPlanId, planId)
            .eq(SuperAgentDocumentStrategyStep::getStatus, BusinessStatus.YES.getCode()));
        return stepList.stream()
            .sorted(Comparator
                .comparingInt((SuperAgentDocumentStrategyStep step) -> pipelineOrder(step.getPipelineType()))
                .thenComparing(SuperAgentDocumentStrategyStep::getStepNo)
                .thenComparing(SuperAgentDocumentStrategyStep::getId))
            .toList();
    }

    private int pipelineOrder(String pipelineType) {
        return DocumentStrategyPipelineTypeEnum.PARENT.getCode().equalsIgnoreCase(
            StrUtil.blankToDefault(pipelineType, "")
        ) ? 0 : 1;
    }

    private int getNextPlanVersion(Long documentId) {

        List<SuperAgentDocumentStrategyPlan> planList = planMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentStrategyPlan>()
            .eq(SuperAgentDocumentStrategyPlan::getDocumentId, documentId)
            .eq(SuperAgentDocumentStrategyPlan::getStatus, BusinessStatus.YES.getCode())
            .orderByDesc(SuperAgentDocumentStrategyPlan::getPlanVersion)
            .last("limit 1"));
        return planList.isEmpty() ? 1 : planList.get(0).getPlanVersion() + 1;
    }

    private void finishTaskSuccess(SuperAgentDocumentTask task, Integer stage, Date startTime) {

        Date finishTime = new Date();
        task.setTaskStatus(DocumentTaskStatusEnum.SUCCESS.getCode());
        task.setCurrentStage(stage);
        task.setFinishTime(finishTime);
        task.setCostMillis(finishTime.getTime() - startTime.getTime());
        task.setErrorCode(null);
        task.setErrorMsg(null);
        taskMapper.updateById(task);
    }

    private void syncNavigationArtifacts(Long documentId,
                                         Long parseTaskId,
                                         List<SuperAgentDocumentStructureNode> structureNodes) {
        long startedNanos = System.nanoTime();
        log.info("开始同步导航产物: documentId={}, parseTaskId={}, structureNodeCount={}",
            documentId,
            parseTaskId,
            structureNodes == null ? 0 : structureNodes.size());
        DocumentNavigationIndexService navigationIndexService = navigationIndexServiceProvider.getIfAvailable();
        if (navigationIndexService != null) {
            long navigationIndexStartedNanos = System.nanoTime();
            log.info("同步导航 ES 索引: documentId={}, parseTaskId={}", documentId, parseTaskId);
            navigationIndexService.reindexDocumentNodes(documentId, parseTaskId, structureNodes);
            log.info("同步导航 ES 索引完成: documentId={}, parseTaskId={}, costMillis={}",
                documentId, parseTaskId, elapsedMillis(navigationIndexStartedNanos));
        }
        else {
            log.info("跳过导航 ES 索引同步，因为服务未启用: documentId={}, parseTaskId={}", documentId, parseTaskId);
        }
        DocumentStructureGraphProjectionService graphProjectionService = graphProjectionServiceProvider.getIfAvailable();
        if (graphProjectionService != null && graphProjectionService.enabled()) {
            long graphProjectionStartedNanos = System.nanoTime();
            log.info("同步结构图投影: documentId={}, parseTaskId={}", documentId, parseTaskId);
            graphProjectionService.projectToGraph(documentId, parseTaskId);
            log.info("同步结构图投影完成: documentId={}, parseTaskId={}, costMillis={}",
                documentId, parseTaskId, elapsedMillis(graphProjectionStartedNanos));
        }
        else {
            log.info("跳过结构图投影，因为图服务未启用: documentId={}, parseTaskId={}", documentId, parseTaskId);
        }
        log.info("导航产物同步流程完成: documentId={}, parseTaskId={}, costMillis={}",
            documentId, parseTaskId, elapsedMillis(startedNanos));
    }

    private void failTask(SuperAgentDocumentTask task, Date startTime, Exception exception, Integer currentStage) {

        Date finishTime = new Date();
        task.setTaskStatus(DocumentTaskStatusEnum.FAILED.getCode());
        task.setCurrentStage(currentStage);
        task.setFinishTime(finishTime);
        task.setCostMillis(finishTime.getTime() - startTime.getTime());
        task.setErrorCode("TASK_FAILED");
        task.setErrorMsg(exception.getMessage());
        taskMapper.updateById(task);
    }

    private int estimateTokenCount(String text) {
        if (StrUtil.isBlank(text)) {
            return 0;
        }
        int chineseCount = 0;
        int englishCount = 0;

        for (char current : text.toCharArray()) {
            if (String.valueOf(current).matches("[\\u4e00-\\u9fa5]")) {
                chineseCount++;
            }
        }

        for (String word : text.split("\\s+")) {
            if (word.matches(".*[A-Za-z].*")) {
                englishCount++;
            }
        }

        return chineseCount + englishCount + Math.max(1, (text.length() - chineseCount) / 4);
    }

    private Map<String, Object> detail(Object... keyValues) {
        Map<String, Object> detailMap = new LinkedHashMap<>();

        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            detailMap.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        return detailMap;
    }

    private int embeddingBatchSize() {
        Integer configured = properties.getIndexBuild().getEmbeddingBatchSize();
        if (configured == null || configured <= 0) {
            return DefaultDocumentVectorGateway.EMBEDDING_BATCH_SIZE_LIMIT;
        }
        return Math.min(configured, DefaultDocumentVectorGateway.EMBEDDING_BATCH_SIZE_LIMIT);
    }

    private int batchCount(int total, int batchSize) {
        if (total <= 0) {
            return 0;
        }
        return (total + Math.max(1, batchSize) - 1) / Math.max(1, batchSize);
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private String stageLabel(Integer stage) {
        if (stage == null) {
            return "UNKNOWN";
        }
        return stage + "-" + stageName(stage);
    }

    private String stageName(Integer stage) {
        DocumentTaskStageEnum stageEnum = DocumentTaskStageEnum.getRc(stage);
        return stageEnum == null ? "UNKNOWN" : stageEnum.getMsg();
    }

    private byte[] decodeBase64(String contentBase64, String label) {
        try {
            return Base64.getDecoder().decode(contentBase64);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(label + " Base64 解码失败", exception);
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException("计算解析产物 hash 失败", exception);
        }
    }

    private record ParentChildEntityBundle(
        List<SuperAgentDocumentParentBlock> parentBlocks,
        List<SuperAgentDocumentChunk> childChunks
    ) {
    }
}
