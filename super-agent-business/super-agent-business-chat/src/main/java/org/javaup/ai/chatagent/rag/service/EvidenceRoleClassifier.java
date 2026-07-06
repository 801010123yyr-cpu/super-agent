package org.javaup.ai.chatagent.rag.service;

import org.javaup.ai.chatagent.rag.model.EvidenceRole;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class EvidenceRoleClassifier {

    /**
     * Evidence role must come from controlled structured metadata. Do not infer it
     * from section titles, question text, or document content with contains rules.
     */
    public EvidenceRole classify(Document evidence) {
        if (evidence == null || evidence.getMetadata() == null) {
            return EvidenceRole.GENERAL;
        }
        return readStructuredRole(evidence.getMetadata());
    }

    private EvidenceRole readStructuredRole(Map<String, Object> metadata) {
        Object raw = metadata.get(DocumentKnowledgeMetadataKeys.EVIDENCE_ROLE);
        if (raw == null) {
            return EvidenceRole.GENERAL;
        }
        try {
            return EvidenceRole.valueOf(String.valueOf(raw).trim().toUpperCase());
        }
        catch (IllegalArgumentException exception) {
            return EvidenceRole.GENERAL;
        }
    }
}
