package org.javaup.ai.chatagent.rag.executor;

import org.javaup.ai.chatagent.model.SearchReference;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RagChatExecutorTest {

    @Test
    void referenceTraceItemKeepsCrossDocumentCommunityMetadata() throws Exception {
        SearchReference reference = new SearchReference();
        reference.setReferenceId("1");
        reference.setSourceType("GRAPH_RAG");
        reference.setChannel("graph-rag");
        reference.setKgRelationGroupKey("CONCEPT:审计系统->RECORDS->ORGANIZATION:信息安全部");
        reference.setKgRelationGroupRelationCount(1);
        reference.setKgRelationGroupEvidenceCount(1);
        reference.setKgRelationGroupDocumentCount(1);
        reference.setKgCrossDocumentCommunityKey("community:audit-system");
        reference.setKgCrossDocumentCommunityEntityCount(6);
        reference.setKgCrossDocumentCommunityRelationGroupCount(5);
        reference.setKgCrossDocumentCommunityEvidenceCount(5);
        reference.setKgCrossDocumentCommunityDocumentCount(2);
        reference.setKgQualityScore(1.0D);

        RagChatExecutor executor = new RagChatExecutor(null, null, null, null);
        Method method = RagChatExecutor.class.getDeclaredMethod("referenceTraceItem", SearchReference.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) method.invoke(executor, reference);

        assertThat(item.get("kgRelationGroupKey")).isEqualTo("CONCEPT:审计系统->RECORDS->ORGANIZATION:信息安全部");
        assertThat(item.get("kgRelationGroupRelationCount")).isEqualTo(1);
        assertThat(item.get("kgRelationGroupEvidenceCount")).isEqualTo(1);
        assertThat(item.get("kgRelationGroupDocumentCount")).isEqualTo(1);
        assertThat(item.get("kgCrossDocumentCommunityKey")).isEqualTo("community:audit-system");
        assertThat(item.get("kgCrossDocumentCommunityEntityCount")).isEqualTo(6);
        assertThat(item.get("kgCrossDocumentCommunityRelationGroupCount")).isEqualTo(5);
        assertThat(item.get("kgCrossDocumentCommunityEvidenceCount")).isEqualTo(5);
        assertThat(item.get("kgCrossDocumentCommunityDocumentCount")).isEqualTo(2);
        assertThat(item.get("kgQualityScore")).isEqualTo(1.0D);
    }
}
