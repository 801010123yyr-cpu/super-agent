package org.javaup.ai.manage.support;

import org.javaup.ai.manage.data.SuperAgentDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RaptorScopeSupportTest {

    @Test
    void buildsKnowledgeBaseAwareScopeKeysFromSelectedDocuments() {
        SuperAgentDocument release = document(1L);
        release.setKnowledgeBaseId(10L);
        SuperAgentDocument qa = document(2L);
        qa.setKnowledgeBaseId(10L);
        SuperAgentDocument blank = document(3L);
        blank.setKnowledgeBaseId(20L);

        List<String> scopeKeys = RaptorScopeSupport.searchScopeKeys(List.of(release, qa, blank));

        assertThat(scopeKeys).containsExactly(
            "kb:10",
            "kb:20"
        );
    }

    @Test
    void keepsDocumentScopeSeparateFromDatasetScope() {
        assertThat(RaptorScopeSupport.documentScopeKey(99L)).isEqualTo("document:99");
        assertThat(RaptorScopeSupport.knowledgeBaseScopeKey(10L)).isEqualTo("kb:10");
        assertThat(RaptorScopeSupport.knowledgeScopeKey(10L, 30L)).isEqualTo("kb:10:scope:30");
        assertThat(RaptorScopeSupport.isDatasetScope(RaptorScopeSupport.SCOPE_TYPE_DATASET)).isTrue();
        assertThat(RaptorScopeSupport.isDatasetScope(RaptorScopeSupport.SCOPE_TYPE_DOCUMENT)).isFalse();
    }

    private static SuperAgentDocument document(Long id) {
        SuperAgentDocument document = new SuperAgentDocument();
        document.setId(id);
        return document;
    }
}
