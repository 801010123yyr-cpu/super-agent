package org.javaup.ai.chatagent.rag.service;

import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.RagRuntimeOptions;
import org.javaup.ai.manage.data.SuperAgentKnowledgeBase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBaseRuntimeConfigResolverTest {

    @Test
    void singleKnowledgeBaseOverridesNonEmptyRetrievalConfig() {
        ChatRagProperties defaults = defaults();
        KnowledgeBaseRuntimeConfigResolver resolver = new KnowledgeBaseRuntimeConfigResolver(defaults);
        SuperAgentKnowledgeBase kb = knowledgeBase(1L, "研发库", """
            {
              "vectorTopK": 12,
              "keywordTopK": 6,
              "minVectorSimilarity": 0.62,
              "keywordChannelEnabled": false,
              "hybrid": {
                "vectorWeight": 1.7,
                "keywordWeight": 0.4
              }
            }
            """);

        RagRuntimeOptions options = resolver.resolve(List.of(kb));

        assertThat(options.getVectorTopK()).isEqualTo(12);
        assertThat(options.getKeywordTopK()).isEqualTo(6);
        assertThat(options.getMinVectorSimilarity()).isEqualTo(0.62D);
        assertThat(options.isKeywordChannelEnabled()).isFalse();
        assertThat(options.getHybrid().getVectorWeight()).isEqualTo(1.7D);
        assertThat(options.getHybrid().getKeywordWeight()).isEqualTo(0.4D);
        assertThat(options.getKbConfigConflictFields()).isEmpty();
    }

    @Test
    void multipleKnowledgeBasesOnlyOverrideConsistentFieldsAndReportConflicts() {
        ChatRagProperties defaults = defaults();
        KnowledgeBaseRuntimeConfigResolver resolver = new KnowledgeBaseRuntimeConfigResolver(defaults);
        SuperAgentKnowledgeBase kbA = knowledgeBase(1L, "研发库", """
            {
              "vectorTopK": 12,
              "keywordTopK": 6,
              "graphRagTopK": 9,
              "hybrid": {
                "vectorWeight": 1.5
              }
            }
            """);
        SuperAgentKnowledgeBase kbB = knowledgeBase(2L, "客服库", """
            {
              "vectorTopK": 12,
              "keywordTopK": 8,
              "graphRagTopK": 9,
              "hybrid": {
                "vectorWeight": 1.5
              }
            }
            """);

        RagRuntimeOptions options = resolver.resolve(List.of(kbA, kbB));

        assertThat(options.getVectorTopK()).isEqualTo(12);
        assertThat(options.getGraphRagTopK()).isEqualTo(9);
        assertThat(options.getKeywordTopK()).isEqualTo(defaults.getKeywordTopK());
        assertThat(options.getHybrid().getVectorWeight()).isEqualTo(1.5D);
        assertThat(options.getKbConfigConflictFields()).containsExactly("keywordTopK");
    }

    private static ChatRagProperties defaults() {
        ChatRagProperties properties = new ChatRagProperties();
        properties.setVectorTopK(8);
        properties.setKeywordTopK(8);
        properties.setGraphRagTopK(5);
        properties.setMinVectorSimilarity(0.45D);
        properties.setKeywordChannelEnabled(true);
        properties.getHybrid().setVectorWeight(1.0D);
        properties.getHybrid().setKeywordWeight(1.0D);
        return properties;
    }

    private static SuperAgentKnowledgeBase knowledgeBase(Long id, String name, String retrievalConfigJson) {
        SuperAgentKnowledgeBase knowledgeBase = new SuperAgentKnowledgeBase();
        knowledgeBase.setId(id);
        knowledgeBase.setBaseName(name);
        knowledgeBase.setRetrievalConfigJson(retrievalConfigJson);
        return knowledgeBase;
    }
}
