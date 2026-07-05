package org.javaup.ai.manage.support;

import org.javaup.ai.manage.config.DocumentManageProperties;
import org.javaup.ai.manage.data.SuperAgentKnowledgeBase;
import org.javaup.ai.manage.model.KnowledgeBaseIndexingOptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBaseIndexingConfigResolverTest {

    @Test
    void resolvesNestedIndexingGraphAndRaptorBuildConfig() {
        KnowledgeBaseIndexingConfigResolver resolver = new KnowledgeBaseIndexingConfigResolver(defaultProperties());
        SuperAgentKnowledgeBase knowledgeBase = new SuperAgentKnowledgeBase();
        knowledgeBase.setId(1L);
        knowledgeBase.setBaseName("测试库");
        knowledgeBase.setRetrievalConfigJson("""
            {
              "vectorTopK": 8,
              "indexing": {
                "childRecursiveMaxChars": 640,
                "childRecursiveOverlapChars": 80,
                "childSemanticMaxChars": 600,
                "childSemanticMinChars": 180,
                "childSemanticSimilarityThreshold": 0.22,
                "parentBlockMaxChars": 1800,
                "parentBlockOverlapChars": 160,
                "parentSemanticMaxChars": 1500,
                "parentSemanticMinChars": 360
              }
            }
            """);
        knowledgeBase.setGraphRagConfigJson("""
            {
              "graphRagTopK": 6,
              "build": {
                "graphRagBuildEnabled": false
              }
            }
            """);
        knowledgeBase.setRaptorConfigJson("""
            {
              "raptorTopK": 5,
              "build": {
                "raptorBuildEnabled": true,
                "raptorMaxClusterSize": 8,
                "raptorMaxLevels": 4,
                "raptorLlmSummaryEnabled": false,
                "raptorSummaryQualityFloor": 0.55
              }
            }
            """);

        KnowledgeBaseIndexingOptions options = resolver.resolve(knowledgeBase);

        assertThat(options.getChunk().getChildRecursiveMaxChars()).isEqualTo(640);
        assertThat(options.getChunk().getChildRecursiveOverlapChars()).isEqualTo(80);
        assertThat(options.getChunk().getChildSemanticMaxChars()).isEqualTo(600);
        assertThat(options.getChunk().getChildSemanticMinChars()).isEqualTo(180);
        assertThat(options.getChunk().getChildSemanticSimilarityThreshold()).isEqualTo(0.22D);
        assertThat(options.getChunk().getParentBlockMaxChars()).isEqualTo(1800);
        assertThat(options.getChunk().getParentBlockOverlapChars()).isEqualTo(160);
        assertThat(options.getChunk().getParentSemanticMaxChars()).isEqualTo(1500);
        assertThat(options.getChunk().getParentSemanticMinChars()).isEqualTo(360);
        assertThat(options.getGraphRag().getGraphRagBuildEnabled()).isFalse();
        assertThat(options.getRaptor().getRaptorBuildEnabled()).isTrue();
        assertThat(options.getRaptor().getRaptorMaxClusterSize()).isEqualTo(8);
        assertThat(options.getRaptor().getRaptorMaxLevels()).isEqualTo(4);
        assertThat(options.getRaptor().getRaptorLlmSummaryEnabled()).isFalse();
        assertThat(options.getRaptor().getRaptorSummaryQualityFloor()).isEqualTo(0.55D);
    }

    @Test
    void clampsUnsafeValuesToUsableRanges() {
        KnowledgeBaseIndexingConfigResolver resolver = new KnowledgeBaseIndexingConfigResolver(defaultProperties());
        SuperAgentKnowledgeBase knowledgeBase = new SuperAgentKnowledgeBase();
        knowledgeBase.setRetrievalConfigJson("""
            {
              "indexing": {
                "childRecursiveMaxChars": 10,
                "childRecursiveOverlapChars": 9999,
                "childSemanticSimilarityThreshold": 9,
                "parentBlockMaxChars": 200,
                "parentBlockOverlapChars": 9999
              }
            }
            """);
        knowledgeBase.setRaptorConfigJson("""
            {
              "build": {
                "raptorMaxClusterSize": 1,
                "raptorMaxLevels": 99,
                "raptorSummaryQualityFloor": -1
              }
            }
            """);

        KnowledgeBaseIndexingOptions options = resolver.resolve(knowledgeBase);

        assertThat(options.getChunk().getChildRecursiveMaxChars()).isEqualTo(100);
        assertThat(options.getChunk().getChildRecursiveOverlapChars()).isEqualTo(99);
        assertThat(options.getChunk().getChildSemanticSimilarityThreshold()).isEqualTo(1D);
        assertThat(options.getChunk().getParentBlockMaxChars()).isEqualTo(300);
        assertThat(options.getChunk().getParentBlockOverlapChars()).isEqualTo(299);
        assertThat(options.getRaptor().getRaptorMaxClusterSize()).isEqualTo(2);
        assertThat(options.getRaptor().getRaptorMaxLevels()).isEqualTo(8);
        assertThat(options.getRaptor().getRaptorSummaryQualityFloor()).isZero();
    }

    private static DocumentManageProperties defaultProperties() {
        DocumentManageProperties properties = new DocumentManageProperties();
        properties.getChunk().setRecursiveMaxChars(800);
        properties.getChunk().setRecursiveOverlapChars(120);
        properties.getChunk().setSemanticMaxChars(700);
        properties.getChunk().setSemanticMinChars(240);
        properties.getChunk().setSemanticSimilarityThreshold(0.18D);
        properties.getChunk().setParentBlockMaxChars(2200);
        properties.getChunk().setParentBlockOverlapChars(180);
        properties.getChunk().setParentSemanticMaxChars(1600);
        properties.getChunk().setParentSemanticMinChars(480);
        return properties;
    }
}
