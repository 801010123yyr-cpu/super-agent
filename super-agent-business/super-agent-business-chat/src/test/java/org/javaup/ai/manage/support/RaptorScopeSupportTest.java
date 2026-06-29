package org.javaup.ai.manage.support;

import org.javaup.ai.manage.data.SuperAgentDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RaptorScopeSupportTest {

    @Test
    void buildsKnowledgeAndGlobalScopeKeysFromJavaSelectedDocuments() {
        SuperAgentDocument release = document(1L, "release");
        SuperAgentDocument qa = document(2L, "qa");
        SuperAgentDocument blank = document(3L, " ");

        List<String> scopeKeys = RaptorScopeSupport.searchScopeKeys(List.of(release, qa, blank));

        assertThat(scopeKeys).containsExactly("knowledge:release", "knowledge:qa", "global");
    }

    @Test
    void keepsDocumentScopeSeparateFromDatasetScope() {
        assertThat(RaptorScopeSupport.documentScopeKey(99L)).isEqualTo("document:99");
        assertThat(RaptorScopeSupport.knowledgeScopeKey(" Release Ops ")).isEqualTo("knowledge:release_ops");
        assertThat(RaptorScopeSupport.isDatasetScope(RaptorScopeSupport.SCOPE_TYPE_DATASET)).isTrue();
        assertThat(RaptorScopeSupport.isDatasetScope(RaptorScopeSupport.SCOPE_TYPE_DOCUMENT)).isFalse();
    }

    private static SuperAgentDocument document(Long id, String scopeCode) {
        SuperAgentDocument document = new SuperAgentDocument();
        document.setId(id);
        document.setKnowledgeScopeCode(scopeCode);
        return document;
    }
}
