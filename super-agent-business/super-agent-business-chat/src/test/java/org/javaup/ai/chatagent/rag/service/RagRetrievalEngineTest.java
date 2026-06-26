package org.javaup.ai.chatagent.rag.service;

import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.model.RagRetrievalContext;
import org.javaup.ai.chatagent.rag.model.RetrievalIntent;
import org.javaup.ai.chatagent.rag.retrieve.channel.RetrievalChannel;
import org.javaup.ai.chatagent.rag.retrieve.channel.RetrievalChannelResult;
import org.javaup.ai.manage.model.DocumentRetrieveRequest;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.javaup.enums.RetrievalChannelEnum;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
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
                .isCloseTo(1.74D, org.assertj.core.data.Offset.offset(0.0001D));
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

    private static Document document(String id, String text, double score) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(DocumentKnowledgeMetadataKeys.SCORE, score);
        return Document.builder()
            .id(id)
            .text(text)
            .metadata(metadata)
            .build();
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

    private static class PassThroughDocumentKnowledgeService implements DocumentKnowledgeService {

        @Override
        public List<KnowledgeDocumentDescriptor> listRetrievableDocuments() {
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
}
