package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.rag.model.EvidenceRole;
import org.javaup.ai.chatagent.rag.model.EvidenceApplicabilityResult;
import org.javaup.ai.chatagent.rag.model.QueryUnderstandingResult;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 判断候选证据是否适用于当前 query understanding 的目标实体。
 */
@Service
public class EvidenceApplicabilityService {

    private final EvidenceRoleClassifier evidenceRoleClassifier;

    public EvidenceApplicabilityService() {
        this(new EvidenceRoleClassifier());
    }

    public EvidenceApplicabilityService(EvidenceRoleClassifier evidenceRoleClassifier) {
        this.evidenceRoleClassifier = evidenceRoleClassifier == null ? new EvidenceRoleClassifier() : evidenceRoleClassifier;
    }

    public EvidenceApplicabilityResult evaluate(QueryUnderstandingResult understanding, Document document) {
        if (understanding == null || document == null) {
            return EvidenceApplicabilityResult.unknown("missing understanding or evidence");
        }
        EvidenceApplicabilityResult roleResult = evaluateExpectedRole(understanding, document);
        if (roleResult != null && !roleResult.isApplicable()) {
            return roleResult;
        }
        List<String> targets = normalizedTerms(understanding.getTargetEntities());
        if (targets.isEmpty()) {
            return roleResult == null ? EvidenceApplicabilityResult.unknown("target entity is empty") : roleResult;
        }
        String evidenceText = normalizedEvidenceText(document);
        boolean targetSupported = targets.stream().anyMatch(evidenceText::contains);
        if (targetSupported) {
            return EvidenceApplicabilityResult.applicable("target entity supported by evidence");
        }

        List<String> excluded = normalizedTerms(understanding.getExcludedEntities());
        boolean excludedOnly = !excluded.isEmpty() && excluded.stream().anyMatch(evidenceText::contains);
        if (excludedOnly) {
            return EvidenceApplicabilityResult.notApplicable("evidence only supports excluded entity");
        }

        if (understanding.isNegativeBoundary() || explicitEvidenceRequired(understanding)) {
            return EvidenceApplicabilityResult.notApplicable("target entity is not explicitly supported by evidence");
        }
        return EvidenceApplicabilityResult.unknown("target entity not found in evidence");
    }

    private EvidenceApplicabilityResult evaluateExpectedRole(QueryUnderstandingResult understanding, Document document) {
        List<EvidenceRole> expectedRoles = normalizeExpectedRoles(understanding.getExpectedEvidenceRoles());
        EvidenceRole actualRole = evidenceRoleClassifier.classify(document);
        if (document.getMetadata() != null) {
            document.getMetadata().put(DocumentKnowledgeMetadataKeys.EVIDENCE_ROLE, actualRole.name());
            if (!expectedRoles.isEmpty()) {
                document.getMetadata().put(
                    DocumentKnowledgeMetadataKeys.EXPECTED_EVIDENCE_ROLES,
                    expectedRoles.stream().map(Enum::name).toList()
                );
            }
        }
        if (expectedRoles.isEmpty()) {
            return null;
        }
        if (actualRole == EvidenceRole.GENERAL) {
            return EvidenceApplicabilityResult.unknown("expected evidence role is " + roleText(expectedRoles) + " but evidence role is GENERAL");
        }
        if (expectedRoles.contains(actualRole)) {
            return EvidenceApplicabilityResult.applicable("evidence role matched: " + actualRole.name());
        }
        return EvidenceApplicabilityResult.notApplicable(
            "evidence role mismatch: expected " + roleText(expectedRoles) + ", actual " + actualRole.name()
        );
    }

    private List<EvidenceRole> normalizeExpectedRoles(List<EvidenceRole> roles) {
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        return roles.stream()
            .filter(role -> role != null && role != EvidenceRole.GENERAL)
            .distinct()
            .limit(4)
            .toList();
    }

    private String roleText(List<EvidenceRole> roles) {
        return roles == null || roles.isEmpty()
            ? "GENERAL"
            : String.join("/", roles.stream().map(Enum::name).toList());
    }

    private boolean explicitEvidenceRequired(QueryUnderstandingResult understanding) {
        return "EXPLICIT_EVIDENCE_REQUIRED".equalsIgnoreCase(StrUtil.blankToDefault(understanding.getAnswerExpectation(), ""));
    }

    private List<String> normalizedTerms(List<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        return terms.stream()
            .map(this::normalize)
            .filter(term -> term.length() >= 2)
            .distinct()
            .limit(8)
            .toList();
    }

    private String normalizedEvidenceText(Document document) {
        List<String> values = new ArrayList<>();
        if (document.getMetadata() != null) {
            Map<String, Object> metadata = document.getMetadata();
            add(values, metadata.get(DocumentKnowledgeMetadataKeys.KG_ENTITY_NAME));
            add(values, metadata.get(DocumentKnowledgeMetadataKeys.KG_CANONICAL_ENTITY_NAME));
            add(values, metadata.get(DocumentKnowledgeMetadataKeys.KG_RELATED_ENTITY_NAME));
            add(values, metadata.get(DocumentKnowledgeMetadataKeys.KG_QUERY_PLAN_ENTITIES));
            add(values, metadata.get(DocumentKnowledgeMetadataKeys.TITLE));
            add(values, metadata.get(DocumentKnowledgeMetadataKeys.SECTION_PATH));
            add(values, metadata.get(DocumentKnowledgeMetadataKeys.CANONICAL_PATH));
            add(values, metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME));
        }
        add(values, document.getText());
        return normalize(String.join(" ", values));
    }

    private void add(List<String> values, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value);
        if (StrUtil.isNotBlank(text)) {
            values.add(text);
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replaceAll("[\\s>`*#_\\-，,。；;：:（）()“”\"'\\[\\]{}]+", "")
            .toLowerCase(Locale.ROOT)
            .trim();
    }
}
