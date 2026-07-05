package org.javaup.ai.chatagent.rag.service;

import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.QueryType;
import org.javaup.ai.chatagent.rag.model.QueryUnderstandingResult;
import org.javaup.ai.chatagent.rag.model.RetrievalIntent;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.javaup.enums.RetrievalChannelEnum;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FinalEvidenceSelectionPolicyTest {

    @Test
    void keepsBodyEvidenceWhenTitleCandidateRanksAboveBodyCandidate() {
        ConversationExecutionPlan plan = ConversationExecutionPlan.builder()
            .retrievalIntent(RetrievalIntent.GENERAL)
            .queryUnderstanding(QueryUnderstandingResult.builder()
                .queryType(QueryType.DOCUMENT_QA)
                .sectionAnchors(List.of("14.1.2"))
                .confidence(0.84D)
                .source("test")
                .build())
            .build();

        Document title = doc("title", "# 14.1.2", 0.99D, Map.of(
            DocumentKnowledgeMetadataKeys.DOCUMENT_ID, 1L,
            DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_ID, 101L,
            DocumentKnowledgeMetadataKeys.SECTION_PATH, "14.1.2",
            DocumentKnowledgeMetadataKeys.CANONICAL_PATH, "14.1/14.1.2",
            DocumentKnowledgeMetadataKeys.CHUNK_TYPE, "TITLE"
        ));
        Document body = doc("body", "1. 新版本切块异常。\n2. 父子块配置错误。\n3. 向量索引构建不完整。\n4. 检索过滤条件误收紧。", 0.50D, Map.of(
            DocumentKnowledgeMetadataKeys.DOCUMENT_ID, 1L,
            DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_ID, 101L,
            DocumentKnowledgeMetadataKeys.SECTION_PATH, "14.1.2",
            DocumentKnowledgeMetadataKeys.CANONICAL_PATH, "14.1/14.1.2",
            DocumentKnowledgeMetadataKeys.CHUNK_TYPE, "BODY"
        ));
        Document unrelated = doc("unrelated", "其他章节内容", 0.98D, Map.of(
            DocumentKnowledgeMetadataKeys.DOCUMENT_ID, 1L,
            DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_ID, 999L,
            DocumentKnowledgeMetadataKeys.SECTION_PATH, "12.3"
        ));

        ChatRagProperties properties = new ChatRagProperties();
        properties.setFinalTopK(2);
        List<Document> selected = new FinalEvidenceSelectionPolicy(properties).select(List.of(title, unrelated, body), plan);

        assertThat(selected).extracting(Document::getId).containsExactly("title", "body");
        assertThat(body.getMetadata())
            .containsEntry(DocumentKnowledgeMetadataKeys.FINAL_SELECTION_RESERVE_TYPE, "SAME_SECTION_BODY");
    }

    @Test
    void reservesRaptorSourceChunkInsteadOfSummaryOnlyEvidence() {
        ConversationExecutionPlan plan = ConversationExecutionPlan.builder()
            .retrievalIntent(RetrievalIntent.RAPTOR)
            .queryUnderstanding(QueryUnderstandingResult.builder()
                .queryType(QueryType.GLOBAL_SUMMARY)
                .channels(List.of(RetrievalIntent.RAPTOR))
                .confidence(0.86D)
                .source("test")
                .build())
            .build();

        Document summaryOnly = doc("raptor-summary", "RAPTOR 摘要：灰度上线需要持续观察核心指标。", 0.99D, Map.of(
            DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "RAPTOR",
            DocumentKnowledgeMetadataKeys.CHANNEL, RetrievalChannelEnum.RAPTOR.getName(),
            DocumentKnowledgeMetadataKeys.RAPTOR_NODE_ID, 3001L,
            DocumentKnowledgeMetadataKeys.RAPTOR_SOURCE_STATUS, "SUMMARY_ONLY",
            DocumentKnowledgeMetadataKeys.CHUNK_TYPE, "RAPTOR_SUMMARY"
        ));
        Document sourceChunk = doc("raptor-source", "原文：灰度期需要观察回答准确率、人工转接率和无证据回复率。", 0.50D, Map.of(
            DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "RAPTOR",
            DocumentKnowledgeMetadataKeys.CHANNEL, RetrievalChannelEnum.RAPTOR.getName(),
            DocumentKnowledgeMetadataKeys.RAPTOR_NODE_ID, 3001L,
            DocumentKnowledgeMetadataKeys.RAPTOR_SOURCE_STATUS, "SOURCE_CHUNK",
            DocumentKnowledgeMetadataKeys.CHUNK_ID, 2001L,
            DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, 9001L,
            DocumentKnowledgeMetadataKeys.CHUNK_TYPE, "RAPTOR_SOURCE_CHUNK"
        ));

        ChatRagProperties properties = new ChatRagProperties();
        properties.setFinalTopK(1);
        List<Document> selected = new FinalEvidenceSelectionPolicy(properties).select(List.of(summaryOnly, sourceChunk), plan);

        assertThat(selected).extracting(Document::getId).containsExactly("raptor-source");
        assertThat(sourceChunk.getMetadata())
            .containsEntry(DocumentKnowledgeMetadataKeys.FINAL_SELECTION_RESERVE_TYPE, "RAPTOR_SOURCE_CHUNK")
            .containsEntry(DocumentKnowledgeMetadataKeys.FINAL_SELECTION_REASON, "SELECTED_RAPTOR_SOURCE_CHUNK");
    }

    @Test
    void reservesGraphRelationQuoteInsteadOfCommunitySummaryOnlyEvidence() {
        ConversationExecutionPlan plan = ConversationExecutionPlan.builder()
            .retrievalIntent(RetrievalIntent.GRAPH_RAG)
            .queryUnderstanding(QueryUnderstandingResult.builder()
                .queryType(QueryType.GRAPH_RELATION)
                .channels(List.of(RetrievalIntent.GRAPH_RAG))
                .confidence(0.86D)
                .source("test")
                .build())
            .build();

        Document communitySummaryOnly = doc("graph-community-summary", "社区摘要：该社区覆盖服务和团队。", 0.99D, Map.of(
            DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "GRAPH_RAG",
            DocumentKnowledgeMetadataKeys.CHANNEL, RetrievalChannelEnum.GRAPH_RAG.getName(),
            DocumentKnowledgeMetadataKeys.KG_COMMUNITY_ID, 3001L,
            DocumentKnowledgeMetadataKeys.KG_COMMUNITY_TITLE, "服务职责社区",
            DocumentKnowledgeMetadataKeys.KG_COMMUNITY_SUMMARY, "该社区覆盖服务和团队，但没有原文 quote。",
            DocumentKnowledgeMetadataKeys.KG_COMMUNITY_SUMMARY_ONLY, true,
            DocumentKnowledgeMetadataKeys.KG_EVIDENCE_GROUNDING_LEVEL, "COMMUNITY_SUMMARY_ONLY"
        ));
        Document relationQuote = doc("graph-relation-quote", "原文：PaymentService 由 OwnerTeam 负责维护。", 0.45D, Map.of(
            DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "GRAPH_RAG",
            DocumentKnowledgeMetadataKeys.CHANNEL, RetrievalChannelEnum.GRAPH_RAG.getName(),
            DocumentKnowledgeMetadataKeys.KG_RELATION_ID, 7001L,
            DocumentKnowledgeMetadataKeys.KG_RELATION_TYPE, "CALLS",
            DocumentKnowledgeMetadataKeys.KG_EVIDENCE_ID, 8001L,
            DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_SOURCE, "llm.controlled.query_plan.v1",
            DocumentKnowledgeMetadataKeys.KG_EVIDENCE_GROUNDING_LEVEL, "RELATION_STRONG_QUOTE",
            DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET, "PaymentService 由 OwnerTeam 负责维护。"
        ));

        ChatRagProperties properties = new ChatRagProperties();
        properties.setFinalTopK(1);
        List<Document> selected = new FinalEvidenceSelectionPolicy(properties).select(List.of(communitySummaryOnly, relationQuote), plan);

        assertThat(selected).extracting(Document::getId).containsExactly("graph-relation-quote");
        assertThat(relationQuote.getMetadata())
            .containsEntry(DocumentKnowledgeMetadataKeys.FINAL_SELECTION_RESERVE_TYPE, "GRAPH_RAG_QUOTE")
            .containsEntry(DocumentKnowledgeMetadataKeys.FINAL_SELECTION_REASON, "SELECTED_GRAPH_RAG_QUOTE");
    }

    private static Document doc(String id, String text, double score, Map<String, Object> metadata) {
        LinkedHashMap<String, Object> mergedMetadata = new LinkedHashMap<>(metadata);
        mergedMetadata.put(DocumentKnowledgeMetadataKeys.SCORE, score);
        return Document.builder()
            .id(id)
            .text(text)
            .metadata(mergedMetadata)
            .score(score)
            .build();
    }
}
