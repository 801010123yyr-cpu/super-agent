package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.rag.model.AnswerPlan;
import org.javaup.ai.chatagent.rag.model.EvidenceRole;
import org.javaup.ai.chatagent.rag.model.QueryUnderstandingResult;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AnswerPlanService {

    public AnswerPlan build(QueryUnderstandingResult understanding) {
        List<EvidenceRole> requiredRoles = normalizeRoles(
            understanding == null ? null : understanding.getExpectedEvidenceRoles()
        );
        if (requiredRoles.isEmpty()) {
            return AnswerPlan.builder()
                .requiredRoles(List.of())
                .optionalRoles(List.of(EvidenceRole.GENERAL))
                .requireExplicitEvidence(false)
                .allowRoleFallback(true)
                .instruction("")
                .build();
        }
        return AnswerPlan.builder()
            .requiredRoles(requiredRoles)
            .optionalRoles(List.of(EvidenceRole.GENERAL))
            .requireExplicitEvidence(true)
            .allowRoleFallback(false)
            .instruction(buildInstruction(requiredRoles))
            .build();
    }

    private List<EvidenceRole> normalizeRoles(List<EvidenceRole> roles) {
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        return roles.stream()
            .filter(role -> role != null && role != EvidenceRole.GENERAL)
            .collect(Collectors.toCollection(LinkedHashSet::new))
            .stream()
            .limit(4)
            .toList();
    }

    private String buildInstruction(List<EvidenceRole> requiredRoles) {
        String joined = requiredRoles.stream()
            .map(Enum::name)
            .collect(Collectors.joining(" / "));
        if (StrUtil.isBlank(joined)) {
            return "";
        }
        return "本轮问题期望证据角色：" + joined + "。\n"
            + "只能用对应角色的证据回答对应问题；如果 final evidence 中没有这些角色，必须说明文档没有明确给出。\n"
            + "背景证据不能替代对应角色证据。";
    }
}
