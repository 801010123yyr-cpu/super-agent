package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnswerPlan {

    @Builder.Default
    private List<EvidenceRole> requiredRoles = new ArrayList<>();

    @Builder.Default
    private List<EvidenceRole> optionalRoles = new ArrayList<>();

    private boolean requireExplicitEvidence;

    private boolean allowRoleFallback;

    private String instruction;
}
