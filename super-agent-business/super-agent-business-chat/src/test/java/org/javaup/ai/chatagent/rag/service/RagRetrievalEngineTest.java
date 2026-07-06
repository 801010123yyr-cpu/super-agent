package org.javaup.ai.chatagent.rag.service;

import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.model.QueryType;
import org.javaup.ai.chatagent.rag.model.QueryUnderstandingResult;
import org.javaup.ai.chatagent.rag.model.RagRetrievalContext;
import org.javaup.ai.chatagent.rag.model.RetrievalIntent;
import org.javaup.ai.chatagent.rag.retrieve.channel.RetrievalChannel;
import org.javaup.ai.chatagent.rag.retrieve.channel.RetrievalChannelResult;
import org.javaup.ai.chatagent.service.ConversationTraceRecorder;
import org.javaup.ai.chatagent.service.RetrievalObserveStore;
import org.javaup.ai.chatagent.model.ChannelExecutionView;
import org.javaup.ai.chatagent.model.RetrievalResultView;
import org.javaup.ai.manage.model.DocumentRetrieveRequest;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.ai.manage.model.StructureAnchoredEvidenceRequest;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.javaup.enums.KnowledgeBaseSelectionMode;
import org.javaup.enums.RetrievalChannelEnum;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class RagRetrievalEngineTest {

    @Test
    void weightedHybridUsesMetadataBoostAndRecordsChannelScores() {
        ChatRagProperties properties = new ChatRagProperties();
        properties.setRerankEnabled(false);
        properties.setMinVectorSimilarity(0D);
        properties.setKeywordRelativeScoreFloor(0D);
        properties.setCandidateTopK(10);
        properties.setFinalTopK(2);
        properties.getHybrid().setOriginalScoreWeight(0D);
        properties.getHybrid().setMetadataBoostWeight(1D);

        Document vectorFirst = document("doc-a", "普通流程说明", 0.91D);
        Document vectorSecond = document("doc-b", "报销材料说明", 0.90D);
        Document keywordFirst = document("doc-a", "普通流程说明", 10D);
        Document keywordSecond = document("doc-b", "报销时限是 10 个工作日", 10D);
        keywordSecond.getMetadata().put(DocumentKnowledgeMetadataKeys.TITLE, "报销时限");
        keywordSecond.getMetadata().put(DocumentKnowledgeMetadataKeys.KEYWORDS, "报销 时限");

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            RagRetrievalEngine engine = new RagRetrievalEngine(
                List.of(
                    new StaticRetrievalChannel(RetrievalChannelEnum.VECTOR.getName(), List.of(vectorFirst, vectorSecond)),
                    new StaticRetrievalChannel(RetrievalChannelEnum.KEYWORD.getName(), List.of(keywordFirst, keywordSecond))
                ),
                properties,
                null,
                new PassThroughDocumentKnowledgeService(),
                executorService
            );

            ConversationExecutionPlan plan = ConversationExecutionPlan.builder()
                .mode(ExecutionMode.RETRIEVAL)
                .retrievalQuestion("报销时限")
                .retrievalSubQuestions(List.of("报销时限"))
                .build();

            RagRetrievalContext context = engine.retrieve(plan, null);

            List<Document> documents = context.getSubQuestionEvidenceList().get(0).getDocuments();
            assertThat(documents).extracting(Document::getId).containsExactly("doc-b", "doc-a");
            assertThat(context.getUsedChannels()).containsExactlyInAnyOrder(
                RetrievalChannelEnum.VECTOR.getName(),
                RetrievalChannelEnum.KEYWORD.getName()
            );

            assertThat(documents.get(0).getMetadata())
                .containsEntry(DocumentKnowledgeMetadataKeys.CHANNEL, "hybrid")
                .containsEntry(DocumentKnowledgeMetadataKeys.VECTOR_SCORE, 0.90D)
                .containsEntry(DocumentKnowledgeMetadataKeys.KEYWORD_SCORE, 10D);
            assertThat(documents.get(0).getMetadata().get(DocumentKnowledgeMetadataKeys.HYBRID_SCORE))
                .isInstanceOf(Number.class);
            assertThat(((Number) documents.get(0).getMetadata().get(DocumentKnowledgeMetadataKeys.METADATA_BOOST)).doubleValue())
                .isGreaterThan(0D);
        }
        finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void tableIntentRaisesTableChannelWeight() {
        ChatRagProperties properties = new ChatRagProperties();
        properties.setRerankEnabled(false);
        properties.setMinVectorSimilarity(0D);
        properties.setKeywordRelativeScoreFloor(0D);
        properties.setCandidateTopK(10);
        properties.setFinalTopK(1);
        properties.getHybrid().setOriginalScoreWeight(0D);
        properties.getHybrid().setMetadataBoostWeight(0D);

        Document tableDoc = document("table-doc", "报销金额合计为 1200 元。", 1D);
        Document vectorDoc = document("vector-doc", "报销流程说明。", 1D);

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            RagRetrievalEngine engine = new RagRetrievalEngine(
                List.of(
                    new StaticRetrievalChannel(RetrievalChannelEnum.TABLE.getName(), List.of(tableDoc)),
                    new StaticRetrievalChannel(RetrievalChannelEnum.VECTOR.getName(), List.of(vectorDoc))
                ),
                properties,
                null,
                new PassThroughDocumentKnowledgeService(),
                executorService
            );

            ConversationExecutionPlan plan = ConversationExecutionPlan.builder()
                .mode(ExecutionMode.RETRIEVAL)
                .retrievalIntent(RetrievalIntent.TABLE)
                .retrievalQuestion("报销金额合计是多少")
                .retrievalSubQuestions(List.of("报销金额合计是多少"))
                .build();

            RagRetrievalContext context = engine.retrieve(plan, null);

            List<Document> documents = context.getSubQuestionEvidenceList().get(0).getDocuments();
            assertThat(documents).extracting(Document::getId).containsExactly("table-doc");
            assertThat(documents.get(0).getMetadata())
                .containsEntry(DocumentKnowledgeMetadataKeys.RETRIEVAL_INTENT, RetrievalIntent.TABLE.name());
            assertThat(((Number) documents.get(0).getMetadata().get(DocumentKnowledgeMetadataKeys.CHANNEL_WEIGHT)).doubleValue())
                .isCloseTo(1.296D, org.assertj.core.data.Offset.offset(0.0001D));
        }
        finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void rerankFailureKeepsHybridCandidates() {
        ChatRagProperties properties = new ChatRagProperties();
        properties.setRerankEnabled(true);
        properties.setMinVectorSimilarity(0D);
        properties.setCandidateTopK(10);
        properties.setFinalTopK(1);

        Document vectorDoc = document("vector-doc", "AuditTrail 需记录权限申请、权限审批、权限回收、临时权限延长。", 0.92D);
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            RagRetrievalEngine engine = new RagRetrievalEngine(
                List.of(new StaticRetrievalChannel(RetrievalChannelEnum.VECTOR.getName(), List.of(vectorDoc))),
                properties,
                new RagRerankService(null, properties) {
                    @Override
                    public List<Document> rerank(String query, List<Document> candidates) {
                        throw new IllegalStateException("rerank timeout");
                    }
                },
                new PassThroughDocumentKnowledgeService(),
                executorService
            );

            ConversationExecutionPlan plan = ConversationExecutionPlan.builder()
                .mode(ExecutionMode.RETRIEVAL)
                .retrievalQuestion("审计系统有哪些权限相关要求？")
                .retrievalSubQuestions(List.of("审计系统有哪些权限相关要求？"))
                .build();

            RagRetrievalContext context = engine.retrieve(plan, null);

            List<Document> documents = context.getSubQuestionEvidenceList().get(0).getDocuments();
            assertThat(documents).extracting(Document::getId).containsExactly("vector-doc");
            assertThat(documents.get(0).getMetadata())
                .containsEntry(DocumentKnowledgeMetadataKeys.RERANK_STATUS, "FAILED");
            assertThat(String.valueOf(documents.get(0).getMetadata().get(DocumentKnowledgeMetadataKeys.RERANK_ERROR)))
                .contains("IllegalStateException")
                .contains("rerank timeout");
            assertThat(context.getUsedChannels()).containsExactly(RetrievalChannelEnum.VECTOR.getName());
            assertThat(context.getRetrievalNotes())
                .anySatisfy(note -> assertThat(note).contains("rerank 失败或超时"));
        }
        finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void graphRagCanonicalMetadataIsExposedInNotesAndReferences() {
        ChatRagProperties properties = new ChatRagProperties();
        properties.setRerankEnabled(false);
        properties.setCandidateTopK(10);
        properties.setFinalTopK(1);

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "GRAPH_RAG");
        metadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, RetrievalChannelEnum.GRAPH_RAG.getName());
        metadata.put(DocumentKnowledgeMetadataKeys.SCORE, 0.88D);
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, 1001L);
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, "O6跨文档图谱-审计证据规范A.md");
        metadata.put(DocumentKnowledgeMetadataKeys.KG_ENTITY_ID, 2001L);
        metadata.put(DocumentKnowledgeMetadataKeys.KG_ENTITY_NAME, "AuditTrail");
        metadata.put(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_KEY, "SYSTEM:audittrail");
        metadata.put(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_NAME, "审计系统");
        metadata.put(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_COUNT, 2);
        metadata.put(DocumentKnowledgeMetadataKeys.KG_CANONICAL_DOCUMENT_COUNT, 2);
        metadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY, "SYSTEM:audittrail->RECORDS->PROCESS:permission-apply");
        metadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_RELATION_COUNT, 2);
        metadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_EVIDENCE_COUNT, 3);
        metadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_DOCUMENT_COUNT, 2);
        metadata.put(DocumentKnowledgeMetadataKeys.KG_GRAPH_PATH, "审计系统 -[ALIAS_OF]-> AuditTrail");
        Document graphDoc = Document.builder()
            .id("graphrag-1")
            .text("AuditTrail 需记录权限申请、权限审批、权限回收、临时权限延长。")
            .metadata(metadata)
            .score(0.88D)
            .build();

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            RagRetrievalEngine engine = new RagRetrievalEngine(
                List.of(new StaticRetrievalChannel(RetrievalChannelEnum.GRAPH_RAG.getName(), List.of(graphDoc))),
                properties,
                null,
                new PassThroughDocumentKnowledgeService(),
                executorService
            );

            ConversationExecutionPlan plan = ConversationExecutionPlan.builder()
                .mode(ExecutionMode.RETRIEVAL)
                .retrievalQuestion("审计系统有哪些权限相关要求？")
                .retrievalSubQuestions(List.of("审计系统有哪些权限相关要求？"))
                .build();

            RagRetrievalContext context = engine.retrieve(plan, null);

            assertThat(context.getRetrievalNotes())
                .anySatisfy(note -> assertThat(note)
                    .contains("GraphRAG canonical 观测")
                    .contains("审计系统")
                    .contains("entities=2, docs=2")
                    .contains("relationGroup evidence=3, docs=2"));
            assertThat(context.getSubQuestionEvidenceList().get(0).getReferences())
                .hasSize(1)
                .first()
                .satisfies(reference -> {
                    assertThat(reference.getKgCanonicalEntityKey()).isEqualTo("SYSTEM:audittrail");
                    assertThat(reference.getKgCanonicalEntityName()).isEqualTo("审计系统");
                    assertThat(reference.getKgCanonicalEntityCount()).isEqualTo(2);
                    assertThat(reference.getKgCanonicalDocumentCount()).isEqualTo(2);
                    assertThat(reference.getKgRelationGroupKey()).isEqualTo("SYSTEM:audittrail->RECORDS->PROCESS:permission-apply");
                    assertThat(reference.getKgRelationGroupRelationCount()).isEqualTo(2);
                    assertThat(reference.getKgRelationGroupEvidenceCount()).isEqualTo(3);
                    assertThat(reference.getKgRelationGroupDocumentCount()).isEqualTo(2);
                });
        }
        finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void finalReferencesFillKnowledgeBaseMetadataFromDocumentDescriptor() {
        ChatRagProperties properties = new ChatRagProperties();
        properties.setRerankEnabled(false);
        properties.setMinVectorSimilarity(0D);
        properties.setKeywordRelativeScoreFloor(0D);
        properties.setCandidateTopK(10);
        properties.setFinalTopK(1);

        Document keywordDoc = document("chunk-9001", "值班 SRE 负责执行回滚演练。", 0.91D);
        keywordDoc.getMetadata().put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "DOCUMENT");
        keywordDoc.getMetadata().put(DocumentKnowledgeMetadataKeys.CHANNEL, RetrievalChannelEnum.KEYWORD.getName());
        keywordDoc.getMetadata().put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, 1001L);
        keywordDoc.getMetadata().put(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, "生产发布回滚规范A.md");
        keywordDoc.getMetadata().put(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_NAME, "");

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            RagRetrievalEngine engine = new RagRetrievalEngine(
                List.of(new StaticRetrievalChannel(RetrievalChannelEnum.KEYWORD.getName(), List.of(keywordDoc))),
                properties,
                null,
                new DescriptorDocumentKnowledgeService(List.of(new KnowledgeDocumentDescriptor(
                    1001L,
                    "生产发布回滚规范A.md",
                    2001L,
                    3001L,
                    "生产运维知识库"
                ))),
                executorService
            );

            ConversationExecutionPlan plan = ConversationExecutionPlan.builder()
                .mode(ExecutionMode.RETRIEVAL)
                .retrievalQuestion("值班 SRE 负责什么？")
                .retrievalSubQuestions(List.of("值班 SRE 负责什么？"))
                .knowledgeBaseSelectionMode(KnowledgeBaseSelectionMode.SELECTED)
                .selectedKnowledgeBaseIds(List.of(3001L))
                .build();

            RagRetrievalContext context = engine.retrieve(plan, null);

            Document finalDocument = context.getSubQuestionEvidenceList().get(0).getDocuments().get(0);
            assertThat(finalDocument.getMetadata())
                .containsEntry(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_ID, 3001L)
                .containsEntry(DocumentKnowledgeMetadataKeys.KNOWLEDGE_BASE_NAME, "生产运维知识库");
            assertThat(context.getSubQuestionEvidenceList().get(0).getReferences())
                .hasSize(1)
                .first()
                .satisfies(reference -> {
                    assertThat(reference.getKnowledgeBaseId()).isEqualTo(3001L);
                    assertThat(reference.getKnowledgeBaseName()).isEqualTo("生产运维知识库");
                });
        }
        finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void weightedHybridPreservesGraphRagMetadataWhenSameCandidateAlsoComesFromVector() {
        ChatRagProperties properties = new ChatRagProperties();
        properties.setRerankEnabled(false);
        properties.setMinVectorSimilarity(0D);
        properties.setCandidateTopK(10);
        properties.setFinalTopK(1);

        Document vectorDoc = document("chunk-1001", "AuditTrail 需记录权限申请、权限审批、权限回收、临时权限延长。", 0.94D);
        vectorDoc.getMetadata().put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "DOCUMENT");
        vectorDoc.getMetadata().put(DocumentKnowledgeMetadataKeys.CHANNEL, RetrievalChannelEnum.VECTOR.getName());

        LinkedHashMap<String, Object> graphMetadata = new LinkedHashMap<>();
        graphMetadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "GRAPH_RAG");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, RetrievalChannelEnum.GRAPH_RAG.getName());
        graphMetadata.put(DocumentKnowledgeMetadataKeys.SCORE, 0.78D);
        graphMetadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, "O6跨文档图谱-审计证据规范A.md");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_ENTITY_ID, 2001L);
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_ENTITY_NAME, "AuditTrail");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_KEY, "CONCEPT:审计系统");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_NAME, "审计系统");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_COUNT, 4);
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_CANONICAL_DOCUMENT_COUNT, 2);
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_ID, 3001L);
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_TYPE, "RECORDS");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY, "CONCEPT:审计系统->RECORDS->CONCEPT:权限申请");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_RELATION_COUNT, 2);
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_EVIDENCE_COUNT, 3);
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_DOCUMENT_COUNT, 2);
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID, 4001L);
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_GRAPH_PATH, "一跳：AuditTrail --RECORDS--> 权限申请");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY, "xdoc-community:concept审计系统");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_ENTITY_COUNT, 6);
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_RELATION_GROUP_COUNT, 6);
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_EVIDENCE_COUNT, 6);
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_DOCUMENT_COUNT, 2);
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_QUALITY_SCORE, 0.86D);
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_QUALITY_REASONS, "groundedEvidence,crossDocumentSupport");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_NOISE_REASONS, "");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_PAGERANK, 0.21D);
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_RANK_POSITION, 1);
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_DEGREE, 5);
        Document graphDoc = Document.builder()
            .id("chunk-1001")
            .text("GraphRAG: AuditTrail RECORDS 权限申请")
            .metadata(graphMetadata)
            .score(0.78D)
            .build();

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            RagRetrievalEngine engine = new RagRetrievalEngine(
                List.of(
                    new StaticRetrievalChannel(RetrievalChannelEnum.VECTOR.getName(), List.of(vectorDoc)),
                    new StaticRetrievalChannel(RetrievalChannelEnum.GRAPH_RAG.getName(), List.of(graphDoc))
                ),
                properties,
                null,
                new PassThroughDocumentKnowledgeService(),
                executorService
            );

            ConversationExecutionPlan plan = ConversationExecutionPlan.builder()
                .mode(ExecutionMode.RETRIEVAL)
                .retrievalQuestion("审计系统有哪些权限相关要求？")
                .retrievalSubQuestions(List.of("审计系统有哪些权限相关要求？"))
                .build();

            RagRetrievalContext context = engine.retrieve(plan, null);

            Document finalDocument = context.getSubQuestionEvidenceList().get(0).getDocuments().get(0);
            assertThat(finalDocument.getMetadata())
                .containsEntry(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "DOCUMENT")
                .containsEntry(DocumentKnowledgeMetadataKeys.CHANNEL, "hybrid")
                .containsEntry(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_NAME, "审计系统")
                .containsEntry(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY, "CONCEPT:审计系统->RECORDS->CONCEPT:权限申请")
                .containsEntry(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY, "xdoc-community:concept审计系统")
                .containsEntry(DocumentKnowledgeMetadataKeys.KG_QUALITY_SCORE, 0.86D)
                .containsEntry(DocumentKnowledgeMetadataKeys.KG_PAGERANK, 0.21D)
                .containsEntry(DocumentKnowledgeMetadataKeys.KG_RANK_POSITION, 1)
                .containsEntry(DocumentKnowledgeMetadataKeys.KG_DEGREE, 5);
            assertThat(context.getSubQuestionEvidenceList().get(0).getReferences())
                .hasSize(1)
                .first()
                .satisfies(reference -> {
                    assertThat(reference.getSourceType()).isEqualTo("DOCUMENT");
                    assertThat(reference.getChannel()).isEqualTo("hybrid");
                    assertThat(reference.getKgCanonicalEntityName()).isEqualTo("审计系统");
                    assertThat(reference.getKgRelationGroupKey()).isEqualTo("CONCEPT:审计系统->RECORDS->CONCEPT:权限申请");
                    assertThat(reference.getKgCrossDocumentCommunityKey()).isEqualTo("xdoc-community:concept审计系统");
                    assertThat(reference.getKgQualityScore()).isEqualTo(0.86D);
                    assertThat(reference.getKgPagerank()).isEqualTo(0.21D);
                    assertThat(reference.getKgRankPosition()).isEqualTo(1);
                    assertThat(reference.getKgDegree()).isEqualTo(5);
                });
        }
        finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void graphRagCanonicalObservationPrioritizesRelationGroupSupport() {
        ChatRagProperties properties = new ChatRagProperties();
        properties.setRerankEnabled(false);
        properties.setCandidateTopK(10);
        properties.setFinalTopK(2);
        properties.getHybrid().setOriginalScoreWeight(1D);
        properties.getHybrid().setMetadataBoostWeight(0D);

        Document lexicalOnly = graphRagDocument(
            "graph-low",
            "审计系统别名说明。",
            0.95D,
            "审计系统",
            "审计系统",
            1,
            1,
            null,
            null,
            null
        );
        Document relationGroupSupported = graphRagDocument(
            "graph-strong",
            "AuditTrail 需记录权限申请、权限审批、权限回收、临时权限延长。",
            0.80D,
            "AuditTrail",
            "审计系统",
            2,
            2,
            "SYSTEM:audittrail->RECORDS->PROCESS:permission",
            4,
            2
        );

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            RagRetrievalEngine engine = new RagRetrievalEngine(
                List.of(new StaticRetrievalChannel(RetrievalChannelEnum.GRAPH_RAG.getName(), List.of(lexicalOnly, relationGroupSupported))),
                properties,
                null,
                new PassThroughDocumentKnowledgeService(),
                executorService
            );

            ConversationExecutionPlan plan = ConversationExecutionPlan.builder()
                .mode(ExecutionMode.RETRIEVAL)
                .retrievalQuestion("审计系统有哪些权限相关要求？")
                .retrievalSubQuestions(List.of("审计系统有哪些权限相关要求？"))
                .build();

            RagRetrievalContext context = engine.retrieve(plan, null);

            String note = context.getRetrievalNotes().stream()
                .filter(item -> item.contains("GraphRAG canonical 观测"))
                .findFirst()
                .orElseThrow();
            assertThat(note)
                .contains("relationGroup evidence=4, docs=2")
                .contains("AuditTrail");
            assertThat(note.indexOf("relationGroup evidence=4, docs=2"))
                .isLessThan(note.indexOf("entities=1, docs=1"));
        }
        finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void finalEvidenceBudgetReservesQueryPlannedGraphRagRelationEvidence() {
        ChatRagProperties properties = new ChatRagProperties();
        properties.setRerankEnabled(false);
        properties.setMinVectorSimilarity(0D);
        properties.setKeywordRelativeScoreFloor(0D);
        properties.setCandidateTopK(10);
        properties.setFinalTopK(2);
        properties.getHybrid().setOriginalScoreWeight(1D);
        properties.getHybrid().setMetadataBoostWeight(0D);

        Document vectorHigh = document("vector-high", "普通文本证据一，包含审批流程背景。", 0.99D);
        vectorHigh.getMetadata().put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "DOCUMENT");
        vectorHigh.getMetadata().put(DocumentKnowledgeMetadataKeys.CHANNEL, RetrievalChannelEnum.VECTOR.getName());
        Document keywordHigh = document("keyword-high", "普通文本证据二，包含部门说明。", 0.98D);
        keywordHigh.getMetadata().put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "DOCUMENT");
        keywordHigh.getMetadata().put(DocumentKnowledgeMetadataKeys.CHANNEL, RetrievalChannelEnum.KEYWORD.getName());

        LinkedHashMap<String, Object> graphMetadata = new LinkedHashMap<>();
        graphMetadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "GRAPH_RAG");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, RetrievalChannelEnum.GRAPH_RAG.getName());
        graphMetadata.put(DocumentKnowledgeMetadataKeys.SCORE, 0.60D);
        graphMetadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, "审批关系.md");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_ENTITY_ID, 1001L);
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_ENTITY_NAME, "权限申请");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_ID, 2001L);
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_TYPE, "CALLS");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATED_ENTITY_ID, 1002L);
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATED_ENTITY_NAME, "信息安全部");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID, 3001L);
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_GRAPH_PATH, "二跳：AuditTrail --RECORDS--> 权限申请 --CALLS--> 信息安全部");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_NHOP_PATH, "AuditTrail --RECORDS--> 权限申请 --CALLS--> 信息安全部");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_SOURCE, "java.graph_query_profile.v2,llm.controlled.query_plan.v1");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_ANSWER_TYPES, "ORG");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_ENTITIES, "审计系统");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY, "PROCESS:权限申请->CALLS->ORG:信息安全部");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_QUALITY_SCORE, 0.84D);
        Document graphDoc = Document.builder()
            .id("graph-approval")
            .text("GraphRAG: 权限申请 CALLS 信息安全部。")
            .metadata(graphMetadata)
            .score(0.60D)
            .build();

        ExecutorService executorService = Executors.newFixedThreadPool(3);
        try {
            RagRetrievalEngine engine = new RagRetrievalEngine(
                List.of(
                    new StaticRetrievalChannel(RetrievalChannelEnum.VECTOR.getName(), List.of(vectorHigh)),
                    new StaticRetrievalChannel(RetrievalChannelEnum.KEYWORD.getName(), List.of(keywordHigh)),
                    new StaticRetrievalChannel(RetrievalChannelEnum.GRAPH_RAG.getName(), List.of(graphDoc))
                ),
                properties,
                null,
                new PassThroughDocumentKnowledgeService(),
                executorService
            );

            ConversationExecutionPlan plan = ConversationExecutionPlan.builder()
                .mode(ExecutionMode.RETRIEVAL)
                .retrievalIntent(RetrievalIntent.GRAPH_RAG)
                .retrievalQuestion("审计系统相关的权限审批部门是谁？")
                .retrievalSubQuestions(List.of("审计系统相关的权限审批部门是谁？"))
                .build();

            RagRetrievalContext context = engine.retrieve(plan, null);

            List<Document> documents = context.getSubQuestionEvidenceList().get(0).getDocuments();
            assertThat(documents).hasSize(2);
            assertThat(documents)
                .extracting(Document::getId)
                .contains("graph-approval");
            Document reservedGraphDocument = documents.stream()
                .filter(document -> "graph-approval".equals(document.getId()))
                .findFirst()
                .orElseThrow();
            assertThat(reservedGraphDocument.getMetadata())
                .containsEntry(DocumentKnowledgeMetadataKeys.KG_RELATION_TYPE, "CALLS")
                .containsEntry(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_ANSWER_TYPES, "ORG")
                .containsEntry(DocumentKnowledgeMetadataKeys.KG_NHOP_PATH, "AuditTrail --RECORDS--> 权限申请 --CALLS--> 信息安全部");
        }
        finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void finalEvidencePolicyKeepsSameSectionBodyAfterRerank() {
        ChatRagProperties properties = new ChatRagProperties();
        properties.setRerankEnabled(false);
        properties.setMinVectorSimilarity(0D);
        properties.setKeywordRelativeScoreFloor(0D);
        properties.setCandidateTopK(10);
        properties.setFinalTopK(2);
        properties.getHybrid().setOriginalScoreWeight(1D);
        properties.getHybrid().setMetadataBoostWeight(0D);

        Document title = document("title", "# 14.1.2", 0.99D);
        title.getMetadata().put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, 1L);
        title.getMetadata().put(DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_ID, 101L);
        title.getMetadata().put(DocumentKnowledgeMetadataKeys.SECTION_PATH, "14.1.2");
        title.getMetadata().put(DocumentKnowledgeMetadataKeys.CANONICAL_PATH, "14.1/14.1.2");
        title.getMetadata().put(DocumentKnowledgeMetadataKeys.CHUNK_TYPE, "TITLE");

        Document unrelated = document("unrelated", "其他章节内容。", 0.98D);
        unrelated.getMetadata().put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, 1L);
        unrelated.getMetadata().put(DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_ID, 999L);
        unrelated.getMetadata().put(DocumentKnowledgeMetadataKeys.SECTION_PATH, "12.3");

        Document body = document("body", "1. 新版本切块异常。\n2. 父子块配置错误。\n3. 向量索引构建不完整。", 0.50D);
        body.getMetadata().put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, 1L);
        body.getMetadata().put(DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_ID, 101L);
        body.getMetadata().put(DocumentKnowledgeMetadataKeys.SECTION_PATH, "14.1.2");
        body.getMetadata().put(DocumentKnowledgeMetadataKeys.CANONICAL_PATH, "14.1/14.1.2");
        body.getMetadata().put(DocumentKnowledgeMetadataKeys.CHUNK_TYPE, "BODY");

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            RagRetrievalEngine engine = new RagRetrievalEngine(
                List.of(new StaticRetrievalChannel(RetrievalChannelEnum.KEYWORD.getName(), List.of(title, unrelated, body))),
                properties,
                null,
                new PassThroughDocumentKnowledgeService(),
                executorService
            );

            ConversationExecutionPlan plan = ConversationExecutionPlan.builder()
                .mode(ExecutionMode.RETRIEVAL)
                .retrievalIntent(RetrievalIntent.GENERAL)
                .retrievalQuestion("14.1.2")
                .retrievalSubQuestions(List.of("14.1.2"))
                .queryUnderstanding(QueryUnderstandingResult.builder()
                    .queryType(QueryType.DOCUMENT_QA)
                    .sectionAnchors(List.of("14.1.2"))
                    .confidence(0.84D)
                    .source("test")
                    .build())
                .build();

            RagRetrievalContext context = engine.retrieve(plan, null);

            List<Document> documents = context.getSubQuestionEvidenceList().get(0).getDocuments();
            assertThat(documents).extracting(Document::getId).containsExactly("title", "body");
            assertThat(documents.get(1).getMetadata())
                .containsEntry(DocumentKnowledgeMetadataKeys.FINAL_SELECTION_RESERVE_TYPE, "SAME_SECTION_BODY");
        }
        finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void structureAnchorEvidenceBypassesReserveWindowAndReplacesTitleOnlyEvidence() {
        ChatRagProperties properties = new ChatRagProperties();
        properties.setRerankEnabled(false);
        properties.setMinVectorSimilarity(0D);
        properties.setKeywordRelativeScoreFloor(0D);
        properties.setCandidateTopK(10);
        properties.setReserveCandidateTopK(2);
        properties.setFinalTopK(2);
        properties.getHybrid().setOriginalScoreWeight(1D);
        properties.getHybrid().setMetadataBoostWeight(0D);

        Document title = document("title", "#### 14.3.1 检查顺序", 0.99D);
        title.getMetadata().put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, 1L);
        title.getMetadata().put(DocumentKnowledgeMetadataKeys.TASK_ID, 11L);
        title.getMetadata().put(DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_ID, 301L);
        title.getMetadata().put(DocumentKnowledgeMetadataKeys.SECTION_PATH, "14.3.1");
        title.getMetadata().put(DocumentKnowledgeMetadataKeys.CANONICAL_PATH, "14.3/14.3.1");
        title.getMetadata().put(DocumentKnowledgeMetadataKeys.CHUNK_TYPE, "TITLE");

        Document unrelated = document("unrelated", "其他章节内容。", 0.98D);
        unrelated.getMetadata().put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, 1L);
        unrelated.getMetadata().put(DocumentKnowledgeMetadataKeys.TASK_ID, 11L);
        unrelated.getMetadata().put(DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_ID, 999L);
        unrelated.getMetadata().put(DocumentKnowledgeMetadataKeys.SECTION_PATH, "12.3");

        Document structureBody = document("structure-body", "1. 检查机器人策略。\n2. 检查知识召回质量。\n3. 检查人工排队规则。", 0.30D);
        structureBody.getMetadata().put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, 1L);
        structureBody.getMetadata().put(DocumentKnowledgeMetadataKeys.TASK_ID, 11L);
        structureBody.getMetadata().put(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, 9301L);
        structureBody.getMetadata().put(DocumentKnowledgeMetadataKeys.CHUNK_ID, 930101L);
        structureBody.getMetadata().put(DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_ID, 301L);
        structureBody.getMetadata().put(DocumentKnowledgeMetadataKeys.SECTION_PATH, "14.3.1");
        structureBody.getMetadata().put(DocumentKnowledgeMetadataKeys.CANONICAL_PATH, "14.3/14.3.1");
        structureBody.getMetadata().put(DocumentKnowledgeMetadataKeys.CHUNK_TYPE, "BODY");
        structureBody.getMetadata().put(DocumentKnowledgeMetadataKeys.CHANNEL, "structure-anchor");
        structureBody.getMetadata().put(DocumentKnowledgeMetadataKeys.FINAL_SELECTION_RESERVE_TYPE, "STRUCTURE_ANCHOR_BODY_CANDIDATE");
        structureBody.getMetadata().put(DocumentKnowledgeMetadataKeys.STRUCTURE_ANCHOR_MATCH_TYPE, "NODE_ID");
        structureBody.getMetadata().put(DocumentKnowledgeMetadataKeys.STRUCTURE_ANCHOR_RAW_BODY, true);
        structureBody.getMetadata().put(DocumentKnowledgeMetadataKeys.STRUCTURE_ANCHOR_BYPASS_RESERVE_WINDOW, true);

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            RagRetrievalEngine engine = new RagRetrievalEngine(
                List.of(new StaticRetrievalChannel(RetrievalChannelEnum.KEYWORD.getName(), List.of(title, unrelated))),
                properties,
                null,
                new StructureExpansionDocumentKnowledgeService(List.of(structureBody)),
                executorService
            );

            ConversationExecutionPlan plan = ConversationExecutionPlan.builder()
                .mode(ExecutionMode.RETRIEVAL)
                .retrievalIntent(RetrievalIntent.GENERAL)
                .retrievalQuestion("14.3.1")
                .retrievalSubQuestions(List.of("14.3.1"))
                .selectedDocumentId(1L)
                .selectedTaskId(11L)
                .retrievalDocumentIds(List.of(1L))
                .retrievalTaskIds(List.of(11L))
                .queryUnderstanding(QueryUnderstandingResult.builder()
                    .queryType(QueryType.DOCUMENT_QA)
                    .sectionAnchors(List.of("14.3.1"))
                    .confidence(0.84D)
                    .source("test")
                    .build())
                .build();

            RagRetrievalContext context = engine.retrieve(plan, null);

            List<Document> documents = context.getSubQuestionEvidenceList().get(0).getDocuments();
            assertThat(documents).extracting(Document::getId).contains("structure-body");
            Document selectedBody = documents.stream()
                .filter(document -> "structure-body".equals(document.getId()))
                .findFirst()
                .orElseThrow();
            assertThat(selectedBody.getMetadata())
                .containsEntry(DocumentKnowledgeMetadataKeys.FINAL_SELECTION_RESERVE_TYPE, "STRUCTURE_ANCHOR_BODY")
                .containsEntry(DocumentKnowledgeMetadataKeys.FINAL_SELECTION_REASON, "REPLACED_TITLE_ONLY_WITH_BODY");
            assertThat(context.getRetrievalNotes())
                .anySatisfy(note -> assertThat(note).contains("结构锚点正文扩展命中 1 条"));
        }
        finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void finalEvidenceMarksExcludedOnlyEvidenceAsNotApplicable() {
        ChatRagProperties properties = new ChatRagProperties();
        properties.setRerankEnabled(false);
        properties.setMinVectorSimilarity(0D);
        properties.setKeywordRelativeScoreFloor(0D);
        properties.setCandidateTopK(10);
        properties.setFinalTopK(1);

        Document excludedEvidence = document("excluded", "1. 先检查策略配置。\n2. 再检查服务队列。", 0.99D);
        excludedEvidence.getMetadata().put(DocumentKnowledgeMetadataKeys.TITLE, "人工转接率异常升高");
        excludedEvidence.getMetadata().put(DocumentKnowledgeMetadataKeys.SECTION_PATH, "14.3.1");

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            RagRetrievalEngine engine = new RagRetrievalEngine(
                List.of(new StaticRetrievalChannel(RetrievalChannelEnum.KEYWORD.getName(), List.of(excludedEvidence))),
                properties,
                null,
                new PassThroughDocumentKnowledgeService(),
                executorService
            );

            ConversationExecutionPlan plan = ConversationExecutionPlan.builder()
                .mode(ExecutionMode.RETRIEVAL)
                .retrievalQuestion("文档是否明确给出目标对象的检查顺序？")
                .retrievalSubQuestions(List.of("文档是否明确给出目标对象的检查顺序？"))
                .queryUnderstanding(QueryUnderstandingResult.builder()
                    .queryType(QueryType.DOCUMENT_QA)
                    .targetEntities(List.of("知识引用错误率突然升高"))
                    .excludedEntities(List.of("人工转接率异常升高"))
                    .negativeBoundary(true)
                    .answerExpectation("EXPLICIT_EVIDENCE_REQUIRED")
                    .confidence(0.9D)
                    .source("test")
                    .build())
                .build();

            RagRetrievalContext context = engine.retrieve(plan, null);

            Document finalDocument = context.getSubQuestionEvidenceList().get(0).getDocuments().get(0);
            assertThat(finalDocument.getMetadata())
                .containsEntry(DocumentKnowledgeMetadataKeys.FINAL_SELECTION_REASON, "FILTERED_NOT_APPLICABLE_TO_TARGET_ENTITY")
                .containsEntry(DocumentKnowledgeMetadataKeys.EVIDENCE_APPLICABILITY_STATUS, "NOT_APPLICABLE");
            assertThat(context.getRetrievalNotes())
                .anySatisfy(note -> assertThat(note).contains("未明确支持当前目标对象"));
            assertThat(context.getSubQuestionEvidenceList().get(0).getReferences())
                .singleElement()
                .satisfies(reference -> {
                    assertThat(reference.getFinalSelectionReason()).isEqualTo("FILTERED_NOT_APPLICABLE_TO_TARGET_ENTITY");
                    assertThat(reference.getEvidenceApplicabilityStatus()).isEqualTo("NOT_APPLICABLE");
                });
        }
        finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void finalEvidenceBudgetCanReserveGraphRagEvidenceAfterRerankWindow() {
        ChatRagProperties properties = new ChatRagProperties();
        properties.setRerankEnabled(true);
        properties.setMinVectorSimilarity(0D);
        properties.setKeywordRelativeScoreFloor(0D);
        properties.setCandidateTopK(10);
        properties.setFinalTopK(2);
        properties.getHybrid().setOriginalScoreWeight(1D);
        properties.getHybrid().setMetadataBoostWeight(0D);

        Document vectorHigh = document("vector-high", "普通文本证据一，包含审批流程背景。", 0.99D);
        vectorHigh.getMetadata().put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "DOCUMENT");
        vectorHigh.getMetadata().put(DocumentKnowledgeMetadataKeys.CHANNEL, RetrievalChannelEnum.VECTOR.getName());
        Document keywordHigh = document("keyword-high", "普通文本证据二，包含部门说明。", 0.98D);
        keywordHigh.getMetadata().put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "DOCUMENT");
        keywordHigh.getMetadata().put(DocumentKnowledgeMetadataKeys.CHANNEL, RetrievalChannelEnum.KEYWORD.getName());

        LinkedHashMap<String, Object> graphMetadata = new LinkedHashMap<>();
        graphMetadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "GRAPH_RAG");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, RetrievalChannelEnum.GRAPH_RAG.getName());
        graphMetadata.put(DocumentKnowledgeMetadataKeys.SCORE, 0.60D);
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_ENTITY_ID, 1001L);
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_ENTITY_NAME, "权限申请");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_ID, 2001L);
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_TYPE, "CALLS");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATED_ENTITY_ID, 1002L);
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATED_ENTITY_NAME, "信息安全部");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID, 3001L);
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_GRAPH_PATH, "二跳：AuditTrail --RECORDS--> 权限申请 --CALLS--> 信息安全部");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_NHOP_PATH, "AuditTrail --RECORDS--> 权限申请 --CALLS--> 信息安全部");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_SOURCE, "java.graph_query_profile.v2,llm.controlled.query_plan.v1");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_ANSWER_TYPES, "ORG");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_ENTITIES, "审计系统");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY, "PROCESS:权限申请->CALLS->ORG:信息安全部");
        graphMetadata.put(DocumentKnowledgeMetadataKeys.KG_QUALITY_SCORE, 0.84D);
        Document graphDoc = Document.builder()
            .id("graph-approval")
            .text("GraphRAG: 权限申请 CALLS 信息安全部。")
            .metadata(graphMetadata)
            .score(0.60D)
            .build();

        ExecutorService executorService = Executors.newFixedThreadPool(3);
        try {
            RagRetrievalEngine engine = new RagRetrievalEngine(
                List.of(
                    new StaticRetrievalChannel(RetrievalChannelEnum.VECTOR.getName(), List.of(vectorHigh)),
                    new StaticRetrievalChannel(RetrievalChannelEnum.KEYWORD.getName(), List.of(keywordHigh)),
                    new StaticRetrievalChannel(RetrievalChannelEnum.GRAPH_RAG.getName(), List.of(graphDoc))
                ),
                properties,
                new RagRerankService(null, properties) {
                    @Override
                    public List<Document> rerank(String query, List<Document> candidates) {
                        Document vector = candidates.stream()
                            .filter(document -> "vector-high".equals(document.getId()))
                            .findFirst()
                            .orElseThrow();
                        Document keyword = candidates.stream()
                            .filter(document -> "keyword-high".equals(document.getId()))
                            .findFirst()
                            .orElseThrow();
                        Document graph = candidates.stream()
                            .filter(document -> "graph-approval".equals(document.getId()))
                            .findFirst()
                            .orElseThrow();
                        vector.getMetadata().put(DocumentKnowledgeMetadataKeys.RERANK_RANK, 1);
                        keyword.getMetadata().put(DocumentKnowledgeMetadataKeys.RERANK_RANK, 2);
                        graph.getMetadata().put(DocumentKnowledgeMetadataKeys.RERANK_RANK, 3);
                        return List.of(vector, keyword, graph);
                    }
                },
                new PassThroughDocumentKnowledgeService(),
                executorService
            );

            ConversationExecutionPlan plan = ConversationExecutionPlan.builder()
                .mode(ExecutionMode.RETRIEVAL)
                .retrievalIntent(RetrievalIntent.GRAPH_RAG)
                .retrievalQuestion("审计系统相关的权限审批部门是谁？")
                .retrievalSubQuestions(List.of("审计系统相关的权限审批部门是谁？"))
                .build();

            RagRetrievalContext context = engine.retrieve(plan, null);

            assertThat(context.getSubQuestionEvidenceList().get(0).getRerankedCandidateCount()).isEqualTo(3);
            List<Document> documents = context.getSubQuestionEvidenceList().get(0).getDocuments();
            assertThat(documents).hasSize(2);
            assertThat(documents)
                .extracting(Document::getId)
                .contains("graph-approval");
            Document reservedGraphDocument = documents.stream()
                .filter(document -> "graph-approval".equals(document.getId()))
                .findFirst()
                .orElseThrow();
            assertThat(reservedGraphDocument.getMetadata())
                .containsEntry(DocumentKnowledgeMetadataKeys.RERANK_RANK, 3)
                .containsEntry(DocumentKnowledgeMetadataKeys.KG_RELATION_TYPE, "CALLS")
                .containsEntry(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_ANSWER_TYPES, "ORG");
            assertThat(context.getUsedChannels()).contains(RetrievalChannelEnum.RERANK.getName());
        }
        finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void weightedHybridBoostsQueryPlannedGraphRagEvidenceByKgMetadata() {
        ChatRagProperties properties = new ChatRagProperties();
        properties.setRerankEnabled(false);
        properties.setMinVectorSimilarity(0D);
        properties.setKeywordRelativeScoreFloor(0D);
        properties.setCandidateTopK(10);
        properties.setFinalTopK(2);
        properties.getHybrid().setOriginalScoreWeight(0D);
        properties.getHybrid().setMetadataBoostWeight(1D);

        LinkedHashMap<String, Object> plannedMetadata = new LinkedHashMap<>();
        plannedMetadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "GRAPH_RAG");
        plannedMetadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, RetrievalChannelEnum.GRAPH_RAG.getName());
        plannedMetadata.put(DocumentKnowledgeMetadataKeys.SCORE, 0.60D);
        plannedMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_ID, 2001L);
        plannedMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_TYPE, "APPROVES");
        plannedMetadata.put(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID, 3001L);
        plannedMetadata.put(DocumentKnowledgeMetadataKeys.KG_GRAPH_PATH, "二跳：AuditTrail --RECORDS--> 权限申请 --APPROVES--> 信息安全部");
        plannedMetadata.put(DocumentKnowledgeMetadataKeys.KG_NHOP_PATH, "AuditTrail --RECORDS--> 权限申请 --APPROVES--> 信息安全部");
        plannedMetadata.put(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_SOURCE, "java.graph_query_profile.v2,llm.controlled.query_plan.v1");
        plannedMetadata.put(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_ANSWER_TYPES, "ORG");
        plannedMetadata.put(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_ENTITIES, "审计系统");
        plannedMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY, "PROCESS:权限申请->APPROVES->ORG:信息安全部");
        plannedMetadata.put(DocumentKnowledgeMetadataKeys.KG_QUALITY_SCORE, 0.90D);
        plannedMetadata.put(DocumentKnowledgeMetadataKeys.KG_RANK_BOOST, 0.70D);
        Document plannedGraphDoc = Document.builder()
            .id("graph-planned")
            .text("GraphRAG: 权限申请 APPROVES 信息安全部。")
            .metadata(plannedMetadata)
            .score(0.60D)
            .build();

        LinkedHashMap<String, Object> plainMetadata = new LinkedHashMap<>();
        plainMetadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "GRAPH_RAG");
        plainMetadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, RetrievalChannelEnum.GRAPH_RAG.getName());
        plainMetadata.put(DocumentKnowledgeMetadataKeys.SCORE, 0.60D);
        plainMetadata.put(DocumentKnowledgeMetadataKeys.KG_ENTITY_ID, 1001L);
        plainMetadata.put(DocumentKnowledgeMetadataKeys.KG_ENTITY_NAME, "AuditTrail");
        plainMetadata.put(DocumentKnowledgeMetadataKeys.KG_GRAPH_PATH, "AuditTrail");
        Document plainGraphDoc = Document.builder()
            .id("graph-plain")
            .text("GraphRAG: AuditTrail。")
            .metadata(plainMetadata)
            .score(0.60D)
            .build();

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            RagRetrievalEngine engine = new RagRetrievalEngine(
                List.of(new StaticRetrievalChannel(RetrievalChannelEnum.GRAPH_RAG.getName(), List.of(plainGraphDoc, plannedGraphDoc))),
                properties,
                null,
                new PassThroughDocumentKnowledgeService(),
                executorService
            );

            ConversationExecutionPlan plan = ConversationExecutionPlan.builder()
                .mode(ExecutionMode.RETRIEVAL)
                .retrievalIntent(RetrievalIntent.GRAPH_RAG)
                .retrievalQuestion("审计系统相关的权限审批部门是谁？")
                .retrievalSubQuestions(List.of("审计系统相关的权限审批部门是谁？"))
                .build();

            RagRetrievalContext context = engine.retrieve(plan, null);

            List<Document> documents = context.getSubQuestionEvidenceList().get(0).getDocuments();
            assertThat(documents).extracting(Document::getId).containsExactly("graph-planned", "graph-plain");
            double plannedBoost = ((Number) documents.get(0).getMetadata()
                .get(DocumentKnowledgeMetadataKeys.METADATA_BOOST)).doubleValue();
            double plainBoost = ((Number) documents.get(1).getMetadata()
                .get(DocumentKnowledgeMetadataKeys.METADATA_BOOST)).doubleValue();
            assertThat(plannedBoost).isGreaterThan(plainBoost);
            assertThat(documents.get(0).getMetadata())
                .containsEntry(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_ANSWER_TYPES, "ORG")
                .containsEntry(DocumentKnowledgeMetadataKeys.KG_NHOP_PATH, "AuditTrail --RECORDS--> 权限申请 --APPROVES--> 信息安全部");
        }
        finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void finalEvidenceBudgetReservesCrossDocumentCommunityEvidenceForCommunityReportQuestion() {
        ChatRagProperties properties = new ChatRagProperties();
        properties.setRerankEnabled(false);
        properties.setMinVectorSimilarity(0D);
        properties.setKeywordRelativeScoreFloor(0D);
        properties.setCandidateTopK(10);
        properties.setFinalTopK(2);
        properties.getHybrid().setOriginalScoreWeight(1D);
        properties.getHybrid().setMetadataBoostWeight(0D);

        Document vectorHigh = document("vector-high", "普通原文证据一，描述审计系统背景。", 0.99D);
        vectorHigh.getMetadata().put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "DOCUMENT");
        vectorHigh.getMetadata().put(DocumentKnowledgeMetadataKeys.CHANNEL, RetrievalChannelEnum.VECTOR.getName());
        Document keywordHigh = document("keyword-high", "普通原文证据二，描述跨文档图谱背景。", 0.98D);
        keywordHigh.getMetadata().put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "DOCUMENT");
        keywordHigh.getMetadata().put(DocumentKnowledgeMetadataKeys.CHANNEL, RetrievalChannelEnum.KEYWORD.getName());

        LinkedHashMap<String, Object> communityMetadata = new LinkedHashMap<>();
        communityMetadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "GRAPH_RAG");
        communityMetadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, RetrievalChannelEnum.GRAPH_RAG.getName());
        communityMetadata.put(DocumentKnowledgeMetadataKeys.SCORE, 0.58D);
        communityMetadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, "O6跨文档图谱-审计证据规范A.md");
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID, 3001L);
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY, "CONCEPT:审计系统->RECORDS->CONCEPT:权限申请");
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_EVIDENCE_COUNT, 5);
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_DOCUMENT_COUNT, 2);
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_GRAPH_PATH, "跨文档社区：审计系统 / 权限申请 / 权限审批");
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_TITLE, "跨文档图谱社区：审计系统 / 权限申请 / 权限审批");
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_SUMMARY, "社区报告覆盖审计系统、权限申请、权限审批和留痕证据。");
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY, "xdoc-community:concept审计系统");
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_ENTITY_COUNT, 6);
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_RELATION_GROUP_COUNT, 5);
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_EVIDENCE_COUNT, 5);
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_DOCUMENT_COUNT, 2);
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_QUALITY_SCORE, 1.0D);
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_QUALITY_REASONS, "crossDocument,groundedEvidence,memberQuality");
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_PAGERANK, 0.08D);
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_RANK_POSITION, 1);
        Document communityDoc = Document.builder()
            .id("graph-community")
            .text("GraphRAG: 跨文档图谱社区报告，覆盖审计系统权限留痕证据。")
            .metadata(communityMetadata)
            .score(0.58D)
            .build();

        ExecutorService executorService = Executors.newFixedThreadPool(3);
        try {
            RagRetrievalEngine engine = new RagRetrievalEngine(
                List.of(
                    new StaticRetrievalChannel(RetrievalChannelEnum.VECTOR.getName(), List.of(vectorHigh)),
                    new StaticRetrievalChannel(RetrievalChannelEnum.KEYWORD.getName(), List.of(keywordHigh)),
                    new StaticRetrievalChannel(RetrievalChannelEnum.GRAPH_RAG.getName(), List.of(communityDoc))
                ),
                properties,
                null,
                new PassThroughDocumentKnowledgeService(),
                executorService
            );

            ConversationExecutionPlan plan = ConversationExecutionPlan.builder()
                .mode(ExecutionMode.RETRIEVAL)
                .retrievalIntent(RetrievalIntent.RAPTOR)
                .retrievalQuestion("审计系统相关的跨文档图谱社区总结是什么？")
                .retrievalSubQuestions(List.of("审计系统相关的跨文档图谱社区总结是什么？"))
                .build();

            RagRetrievalContext context = engine.retrieve(plan, null);

            List<Document> documents = context.getSubQuestionEvidenceList().get(0).getDocuments();
            assertThat(documents).hasSize(2);
            assertThat(documents)
                .extracting(Document::getId)
                .contains("graph-community");
            Document reservedCommunityDocument = documents.stream()
                .filter(document -> "graph-community".equals(document.getId()))
                .findFirst()
                .orElseThrow();
            assertThat(reservedCommunityDocument.getMetadata())
                .containsEntry(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY, "xdoc-community:concept审计系统")
                .containsEntry(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_DOCUMENT_COUNT, 2)
                .containsEntry(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY, "CONCEPT:审计系统->RECORDS->CONCEPT:权限申请")
                .containsEntry(DocumentKnowledgeMetadataKeys.KG_QUALITY_SCORE, 1.0D);
            assertThat(context.getRetrievalNotes())
                .anySatisfy(note -> assertThat(note)
                    .contains("GraphRAG canonical 观测")
                    .contains("community=跨文档图谱社区：审计系统 / 权限申请 / 权限审批")
                    .contains("relationGroups=5")
                    .contains("docs=2"));
            assertThat(context.getSubQuestionEvidenceList().get(0).getReferences())
                .anySatisfy(reference -> {
                    assertThat(reference.getKgCrossDocumentCommunityKey()).isEqualTo("xdoc-community:concept审计系统");
                    assertThat(reference.getKgCrossDocumentCommunityDocumentCount()).isEqualTo(2);
                    assertThat(reference.getKgRelationGroupKey()).isEqualTo("CONCEPT:审计系统->RECORDS->CONCEPT:权限申请");
                    assertThat(reference.getKgQualityScore()).isEqualTo(1.0D);
                });
        }
        finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void hybridCandidateBudgetReservesCrossDocumentCommunityEvidenceForCommunityReportQuestion() {
        ChatRagProperties properties = new ChatRagProperties();
        properties.setRerankEnabled(false);
        properties.setMinVectorSimilarity(0D);
        properties.setKeywordRelativeScoreFloor(0D);
        properties.setCandidateTopK(2);
        properties.setFinalTopK(2);
        properties.getHybrid().setOriginalScoreWeight(1D);
        properties.getHybrid().setMetadataBoostWeight(0D);

        Document vectorHigh = document("vector-high", "普通原文证据一，描述审计系统背景。", 0.99D);
        vectorHigh.getMetadata().put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "DOCUMENT");
        vectorHigh.getMetadata().put(DocumentKnowledgeMetadataKeys.CHANNEL, RetrievalChannelEnum.VECTOR.getName());
        Document keywordHigh = document("keyword-high", "普通原文证据二，描述跨文档图谱背景。", 0.98D);
        keywordHigh.getMetadata().put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "DOCUMENT");
        keywordHigh.getMetadata().put(DocumentKnowledgeMetadataKeys.CHANNEL, RetrievalChannelEnum.KEYWORD.getName());

        LinkedHashMap<String, Object> communityMetadata = new LinkedHashMap<>();
        communityMetadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "GRAPH_RAG");
        communityMetadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, RetrievalChannelEnum.GRAPH_RAG.getName());
        communityMetadata.put(DocumentKnowledgeMetadataKeys.SCORE, 0.40D);
        communityMetadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, "O6跨文档图谱-审计证据规范A.md");
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID, 3001L);
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY, "CONCEPT:审计系统->RECORDS->CONCEPT:权限申请");
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_EVIDENCE_COUNT, 5);
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_DOCUMENT_COUNT, 1);
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_GRAPH_PATH, "跨文档社区：审计系统 / 权限申请 / 权限审批");
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_TITLE, "跨文档图谱社区：审计系统 / 权限申请 / 权限审批");
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_COMMUNITY_SUMMARY, "社区报告覆盖审计系统、权限申请、权限审批和留痕证据。");
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_KEY, "xdoc-community:concept审计系统");
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_ENTITY_COUNT, 6);
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_RELATION_GROUP_COUNT, 5);
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_EVIDENCE_COUNT, 5);
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_CROSS_DOCUMENT_COMMUNITY_DOCUMENT_COUNT, 2);
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_QUALITY_SCORE, 1.0D);
        communityMetadata.put(DocumentKnowledgeMetadataKeys.KG_QUALITY_REASONS, "crossDocument,groundedEvidence,memberQuality");
        Document communityDoc = Document.builder()
            .id("graph-community")
            .text("GraphRAG: 跨文档图谱社区报告，覆盖审计系统权限留痕证据。")
            .metadata(communityMetadata)
            .score(0.40D)
            .build();

        ExecutorService executorService = Executors.newFixedThreadPool(3);
        try {
            RagRetrievalEngine engine = new RagRetrievalEngine(
                List.of(
                    new StaticRetrievalChannel(RetrievalChannelEnum.VECTOR.getName(), List.of(vectorHigh)),
                    new StaticRetrievalChannel(RetrievalChannelEnum.KEYWORD.getName(), List.of(keywordHigh)),
                    new StaticRetrievalChannel(RetrievalChannelEnum.GRAPH_RAG.getName(), List.of(communityDoc))
                ),
                properties,
                null,
                new PassThroughDocumentKnowledgeService(),
                executorService
            );

            ConversationExecutionPlan plan = ConversationExecutionPlan.builder()
                .mode(ExecutionMode.RETRIEVAL)
                .retrievalIntent(RetrievalIntent.RAPTOR)
                .retrievalQuestion("审计系统相关的跨文档图谱社区总结是什么？")
                .retrievalSubQuestions(List.of("审计系统相关的跨文档图谱社区总结是什么？"))
                .build();

            RagRetrievalContext context = engine.retrieve(plan, null);

            List<Document> documents = context.getSubQuestionEvidenceList().get(0).getDocuments();
            assertThat(documents).hasSize(2);
            assertThat(documents)
                .extracting(Document::getId)
                .contains("graph-community");
            assertThat(context.getSubQuestionEvidenceList().get(0).getReferences())
                .anySatisfy(reference -> {
                    assertThat(reference.getKgCrossDocumentCommunityKey()).isEqualTo("xdoc-community:concept审计系统");
                    assertThat(reference.getKgCrossDocumentCommunityDocumentCount()).isEqualTo(2);
                    assertThat(reference.getKgRelationGroupKey()).isEqualTo("CONCEPT:审计系统->RECORDS->CONCEPT:权限申请");
                    assertThat(reference.getKgQualityScore()).isEqualTo(1.0D);
                });
            assertThat(context.getRetrievalNotes())
                .anySatisfy(note -> assertThat(note)
                    .contains("GraphRAG canonical 观测")
                    .contains("community=跨文档图谱社区：审计系统 / 权限申请 / 权限审批"));
        }
        finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void retrievalObservationRecordsRaptorParentBlockId() {
        ChatRagProperties properties = new ChatRagProperties();
        properties.setRerankEnabled(false);
        properties.setMinVectorSimilarity(0D);
        properties.setKeywordRelativeScoreFloor(0D);
        properties.setCandidateTopK(10);
        properties.setFinalTopK(1);

        LinkedHashMap<String, Object> raptorMetadata = new LinkedHashMap<>();
        raptorMetadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "RAPTOR");
        raptorMetadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, RetrievalChannelEnum.RAPTOR.getName());
        raptorMetadata.put(DocumentKnowledgeMetadataKeys.SCORE, 0.87D);
        raptorMetadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, 1001L);
        raptorMetadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, "跨文档上线规范.md");
        raptorMetadata.put(DocumentKnowledgeMetadataKeys.CHUNK_ID, 2001L);
        raptorMetadata.put(DocumentKnowledgeMetadataKeys.CHUNK_NO, 7);
        raptorMetadata.put(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, 9001L);
        raptorMetadata.put(DocumentKnowledgeMetadataKeys.SECTION_PATH, "上线治理 > 灰度");
        raptorMetadata.put(DocumentKnowledgeMetadataKeys.RAPTOR_NODE_ID, 3001L);
        Document raptorDoc = Document.builder()
            .id("raptor-3001-2001")
            .text("RAPTOR: 灰度期需要观察回答准确率、人工转接率和无证据回复率。")
            .metadata(raptorMetadata)
            .score(0.87D)
            .build();

        InMemoryRetrievalObserveStore observeStore = new InMemoryRetrievalObserveStore();
        ConversationTraceRecorder traceRecorder = new ConversationTraceRecorder(
            null,
            observeStore,
            "conv-raptor-observe",
            8001L,
            "trace-raptor-observe"
        );

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            RagRetrievalEngine engine = new RagRetrievalEngine(
                List.of(new StaticRetrievalChannel(RetrievalChannelEnum.RAPTOR.getName(), List.of(raptorDoc))),
                properties,
                null,
                new PassThroughDocumentKnowledgeService(),
                executorService
            );

            ConversationExecutionPlan plan = ConversationExecutionPlan.builder()
                .mode(ExecutionMode.RETRIEVAL)
                .retrievalIntent(RetrievalIntent.RAPTOR)
                .retrievalQuestion("总结灰度上线风险控制要求")
                .retrievalSubQuestions(List.of("总结灰度上线风险控制要求"))
                .build();

            engine.retrieve(plan, traceRecorder);

            assertThat(observeStore.results)
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.getChannelType()).isEqualTo(RetrievalChannelEnum.RAPTOR.getName());
                    assertThat(result.getChunkId()).isEqualTo(2001L);
                    assertThat(result.getParentBlockId()).isEqualTo(9001L);
                });
        }
        finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void rerankCandidateWindowLimitsModelInputAndRecordsFilterReasons() {
        ChatRagProperties properties = new ChatRagProperties();
        properties.setRerankEnabled(true);
        properties.setMinVectorSimilarity(0D);
        properties.setKeywordRelativeScoreFloor(0D);
        properties.setCandidateTopK(5);
        properties.setRerankCandidateTopK(2);
        properties.setReserveCandidateTopK(2);
        properties.setFinalTopK(1);

        Document first = document("doc-1", "第一条候选。", 0.99D);
        first.getMetadata().put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, 1L);
        Document second = document("doc-2", "第二条候选。", 0.98D);
        second.getMetadata().put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, 2L);
        Document third = document("doc-3", "第三条候选。", 0.97D);
        third.getMetadata().put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, 3L);

        InMemoryRetrievalObserveStore observeStore = new InMemoryRetrievalObserveStore();
        ConversationTraceRecorder traceRecorder = new ConversationTraceRecorder(
            null,
            observeStore,
            "conv-rerank-window",
            9001L,
            "trace-rerank-window"
        );

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            RagRetrievalEngine engine = new RagRetrievalEngine(
                List.of(new StaticRetrievalChannel(RetrievalChannelEnum.VECTOR.getName(), List.of(first, second, third))),
                properties,
                new RagRerankService(null, properties) {
                    @Override
                    public List<Document> rerank(String query, List<Document> candidates) {
                        assertThat(candidates).extracting(Document::getId).containsExactly("doc-1", "doc-2");
                        for (int index = 0; index < candidates.size(); index++) {
                            Document candidate = candidates.get(index);
                            candidate.getMetadata().put(DocumentKnowledgeMetadataKeys.RERANK_RANK, index + 1);
                            candidate.getMetadata().put(DocumentKnowledgeMetadataKeys.RERANK_SCORE, 1.0D - index * 0.1D);
                            candidate.getMetadata().put(DocumentKnowledgeMetadataKeys.RERANK_STATUS, "SUCCESS");
                        }
                        return candidates;
                    }
                },
                new PassThroughDocumentKnowledgeService(),
                executorService
            );

            ConversationExecutionPlan plan = ConversationExecutionPlan.builder()
                .mode(ExecutionMode.RETRIEVAL)
                .retrievalQuestion("候选窗口测试")
                .retrievalSubQuestions(List.of("候选窗口测试"))
                .build();

            RagRetrievalContext context = engine.retrieve(plan, traceRecorder);

            assertThat(context.getSubQuestionEvidenceList().get(0).getRerankedCandidateCount()).isEqualTo(2);
            assertThat(context.getSubQuestionEvidenceList().get(0).getDocuments())
                .extracting(Document::getId)
                .containsExactly("doc-1");
            assertThat(reasonByDocumentId(observeStore.results, 1L)).isEqualTo("SELECTED_TOP_RANK");
            assertThat(reasonByDocumentId(observeStore.results, 2L)).isEqualTo("FILTERED_BY_FINAL_TOP_K");
            assertThat(reasonByDocumentId(observeStore.results, 3L)).isEqualTo("FILTERED_BY_RERANK_CANDIDATE_TOP_K");
        }
        finally {
            executorService.shutdownNow();
        }
    }

    private static Document document(String id, String text, double score) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(DocumentKnowledgeMetadataKeys.SCORE, score);
        return Document.builder()
            .id(id)
            .text(text)
            .metadata(metadata)
            .build();
    }

    private static String reasonByDocumentId(List<RetrievalResultView> results, Long documentId) {
        return results.stream()
            .filter(result -> documentId.equals(result.getDocumentId()))
            .findFirst()
            .map(RetrievalResultView::getSelectionReason)
            .orElseThrow();
    }

    private static Document graphRagDocument(String id,
                                             String text,
                                             double score,
                                             String entityName,
                                             String canonicalName,
                                             int canonicalEntityCount,
                                             int canonicalDocumentCount,
                                             String relationGroupKey,
                                             Integer relationGroupEvidenceCount,
                                             Integer relationGroupDocumentCount) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "GRAPH_RAG");
        metadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, RetrievalChannelEnum.GRAPH_RAG.getName());
        metadata.put(DocumentKnowledgeMetadataKeys.SCORE, score);
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, id + ".md");
        metadata.put(DocumentKnowledgeMetadataKeys.KG_ENTITY_ID, id.hashCode());
        metadata.put(DocumentKnowledgeMetadataKeys.KG_ENTITY_NAME, entityName);
        metadata.put(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_KEY, "SYSTEM:" + canonicalName);
        metadata.put(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_NAME, canonicalName);
        metadata.put(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_COUNT, canonicalEntityCount);
        metadata.put(DocumentKnowledgeMetadataKeys.KG_CANONICAL_DOCUMENT_COUNT, canonicalDocumentCount);
        metadata.put(DocumentKnowledgeMetadataKeys.KG_GRAPH_PATH, entityName + " --RECORDS--> 权限要求");
        if (relationGroupKey != null) {
            metadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_ID, Math.abs(id.hashCode()));
            metadata.put(DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID, Math.abs(id.hashCode()) + 1L);
            metadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_KEY, relationGroupKey);
            metadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_EVIDENCE_COUNT, relationGroupEvidenceCount);
            metadata.put(DocumentKnowledgeMetadataKeys.KG_RELATION_GROUP_DOCUMENT_COUNT, relationGroupDocumentCount);
        }
        return Document.builder()
            .id(id)
            .text(text)
            .metadata(metadata)
            .score(score)
            .build();
    }

    private record StaticRetrievalChannel(String channelName, List<Document> documents) implements RetrievalChannel {

        @Override
        public boolean supports(ConversationExecutionPlan plan) {
            return true;
        }

        @Override
        public RetrievalChannelResult retrieve(String subQuestion, ConversationExecutionPlan plan) {
            return new RetrievalChannelResult(channelName, documents);
        }
    }

    private static class InMemoryRetrievalObserveStore implements RetrievalObserveStore {

        private final List<RetrievalResultView> results = new ArrayList<>();
        private final List<ChannelExecutionView> channelExecutions = new ArrayList<>();

        @Override
        public void batchSaveResults(String conversationId, long exchangeId, List<RetrievalResultView> results) {
            this.results.addAll(results);
        }

        @Override
        public void batchSaveChannelExecutions(String conversationId, long exchangeId, List<ChannelExecutionView> executions) {
            this.channelExecutions.addAll(executions);
        }

        @Override
        public List<RetrievalResultView> listResults(String conversationId, long exchangeId) {
            return results;
        }

        @Override
        public List<ChannelExecutionView> listChannelExecutions(String conversationId, long exchangeId) {
            return channelExecutions;
        }

        @Override
        public void deleteByConversation(String conversationId) {
            results.clear();
            channelExecutions.clear();
        }
    }

    private static class PassThroughDocumentKnowledgeService implements DocumentKnowledgeService {

        @Override
        public List<KnowledgeDocumentDescriptor> listRetrievableDocuments() {
            return List.of();
        }

        @Override
        public List<KnowledgeDocumentDescriptor> listRetrievableDocumentsByKnowledgeBaseIds(java.util.Collection<Long> knowledgeBaseIds) {
            return List.of();
        }

        @Override
        public List<Document> vectorSearch(DocumentRetrieveRequest request) {
            return List.of();
        }

        @Override
        public List<Document> keywordSearch(DocumentRetrieveRequest request) {
            return List.of();
        }

        @Override
        public List<Document> elevateToParentBlocks(List<Document> childDocuments, int maxChars) {
            return childDocuments;
        }
    }

    private static class StructureExpansionDocumentKnowledgeService extends PassThroughDocumentKnowledgeService {

        private final List<Document> expanded;

        private StructureExpansionDocumentKnowledgeService(List<Document> expanded) {
            this.expanded = expanded;
        }

        @Override
        public List<Document> expandStructureAnchoredEvidence(StructureAnchoredEvidenceRequest request) {
            assertThat(request.getDocumentIds()).containsExactly(1L);
            assertThat(request.getTaskIds()).containsExactly(11L);
            assertThat(request.getStructureNodeIds()).contains(301L);
            return expanded;
        }
    }

    private static class DescriptorDocumentKnowledgeService extends PassThroughDocumentKnowledgeService {

        private final List<KnowledgeDocumentDescriptor> descriptors;

        private DescriptorDocumentKnowledgeService(List<KnowledgeDocumentDescriptor> descriptors) {
            this.descriptors = descriptors;
        }

        @Override
        public List<KnowledgeDocumentDescriptor> listRetrievableDocuments() {
            return descriptors;
        }

        @Override
        public List<KnowledgeDocumentDescriptor> listRetrievableDocumentsByKnowledgeBaseIds(java.util.Collection<Long> knowledgeBaseIds) {
            if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
                return List.of();
            }
            return descriptors.stream()
                .filter(descriptor -> descriptor.getKnowledgeBaseId() != null
                    && knowledgeBaseIds.contains(descriptor.getKnowledgeBaseId()))
                .toList();
        }
    }
}
