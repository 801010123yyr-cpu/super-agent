package org.javaup.ai.manage.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.javaup.ai.chatagent.rag.model.RagRuntimeOptions;
import org.javaup.ai.manage.data.SuperAgentKnowledgeBase;
import org.javaup.enums.KnowledgeBaseSelectionMode;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBaseSelectionSnapshot {

    @Builder.Default
    private KnowledgeBaseSelectionMode selectionMode = KnowledgeBaseSelectionMode.NONE;

    @Builder.Default
    private List<Long> selectedKnowledgeBaseIds = new ArrayList<>();

    @Builder.Default
    private List<String> selectedKnowledgeBaseNames = new ArrayList<>();

    @Builder.Default
    private List<SuperAgentKnowledgeBase> selectedKnowledgeBases = new ArrayList<>();

    @Builder.Default
    private List<KnowledgeDocumentDescriptor> allowedDocuments = new ArrayList<>();

    @Builder.Default
    private List<Long> allowedDocumentIds = new ArrayList<>();

    @Builder.Default
    private List<Long> allowedTaskIds = new ArrayList<>();

    private RagRuntimeOptions ragRuntimeOptions;

    public static KnowledgeBaseSelectionSnapshot none(RagRuntimeOptions options) {
        return KnowledgeBaseSelectionSnapshot.builder()
            .selectionMode(KnowledgeBaseSelectionMode.NONE)
            .ragRuntimeOptions(options)
            .build();
    }
}
