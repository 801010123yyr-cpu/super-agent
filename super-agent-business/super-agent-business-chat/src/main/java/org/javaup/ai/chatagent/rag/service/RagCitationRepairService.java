package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.model.trace.ConversationTraceStageCode;
import org.javaup.ai.chatagent.service.ConversationTraceRecorder;
import org.javaup.ai.ragtools.client.RagToolsClient;
import org.javaup.ai.ragtools.model.RagToolsCitationRepairRequest;
import org.javaup.ai.ragtools.model.RagToolsCitationRepairResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class RagCitationRepairService {

    private static final int MAX_SEGMENTS = 16;
    private static final int MAX_MATCHES_PER_SEGMENT = 1;
    private static final double MIN_SCORE = 0.18D;

    private final RagToolsClient ragToolsClient;

    public RagCitationRepairService(RagToolsClient ragToolsClient) {
        this.ragToolsClient = ragToolsClient;
    }

    public List<SearchReference> repair(String answer,
                                        List<SearchReference> references,
                                        ConversationTraceRecorder traceRecorder,
                                        String executionMode) {
        if (StrUtil.isBlank(answer) || references == null || references.isEmpty()) {
            return references == null ? List.of() : references;
        }

        List<SearchReference> documentReferences = references.stream()
            .filter(this::isRepairableDocumentReference)
            .toList();
        if (documentReferences.isEmpty()) {
            return references;
        }

        ConversationTraceRecorder.StageHandle citationStage = traceRecorder == null
            ? null
            : traceRecorder.startStage(
                ConversationTraceStageCode.CITATION_REPAIR,
                StrUtil.blankToDefault(executionMode, ""),
                "正在修复回答句与原文证据的引用关系。",
                Map.of(
                    "candidateReferenceCount", references.size(),
                    "documentReferenceCount", documentReferences.size()
                )
            );
        try {
            RagToolsCitationRepairRequest request = buildRequest(answer, documentReferences);
            RagToolsCitationRepairResponse response = ragToolsClient.repairCitations(request);
            if (response == null) {
                throw new IllegalStateException("rag-tools citation repair 返回空响应");
            }
            List<SearchReference> repairedReferences = applyRepairResults(references, documentReferences, response.getCitations());
            if (traceRecorder != null) {
                traceRecorder.completeStage(citationStage, "引用修复完成。", Map.of(
                    "candidateReferenceCount", references.size(),
                    "documentReferenceCount", documentReferences.size(),
                    "repairedReferenceCount", repairedReferences.size(),
                    "removedDocumentReferenceCount", Math.max(0, documentReferences.size() - countDocumentReferences(repairedReferences)),
                    "citations", buildCitationTrace(repairedReferences)
                ));
            }
            log.info("引用修复完成: candidateReferenceCount={}, documentReferenceCount={}, repairedReferenceCount={}",
                references.size(),
                documentReferences.size(),
                repairedReferences.size());
            return repairedReferences;
        }
        catch (RuntimeException exception) {
            if (traceRecorder != null) {
                traceRecorder.failStage(citationStage, "引用修复失败。", exception.getMessage(), null);
            }
            throw exception;
        }
    }

    private RagToolsCitationRepairRequest buildRequest(String answer, List<SearchReference> documentReferences) {
        List<RagToolsCitationRepairRequest.Evidence> evidences = documentReferences.stream()
            .map(reference -> new RagToolsCitationRepairRequest.Evidence(
                evidenceId(reference),
                StrUtil.blankToDefault(reference.getSnippet(), ""),
                reference.getDocumentId(),
                StrUtil.blankToDefault(reference.getDocumentName(), ""),
                reference.getChunkId(),
                reference.getParentBlockId(),
                reference.getPageNo(),
                StrUtil.blankToDefault(reference.getPageRange(), ""),
                StrUtil.blankToDefault(reference.getBboxJson(), ""),
                StrUtil.blankToDefault(reference.getSectionPath(), ""),
                buildEvidenceMetadata(reference)
            ))
            .toList();
        return new RagToolsCitationRepairRequest(
            answer,
            evidences,
            MAX_SEGMENTS,
            MAX_MATCHES_PER_SEGMENT,
            MIN_SCORE
        );
    }

    private List<SearchReference> applyRepairResults(List<SearchReference> allReferences,
                                                     List<SearchReference> documentReferences,
                                                     List<RagToolsCitationRepairResponse.Result> citations) {
        Map<String, SearchReference> referencesByEvidenceId = new LinkedHashMap<>();
        documentReferences.forEach(reference -> referencesByEvidenceId.put(evidenceId(reference), reference));

        LinkedHashMap<String, SearchReference> repairedDocumentMap = new LinkedHashMap<>();
        for (RagToolsCitationRepairResponse.Result citation : citations == null ? List.<RagToolsCitationRepairResponse.Result>of() : citations) {
            if (citation == null || StrUtil.isBlank(citation.getEvidenceId())) {
                continue;
            }
            SearchReference reference = referencesByEvidenceId.get(citation.getEvidenceId());
            if (reference == null) {
                continue;
            }
            applyCitation(reference, citation);
            repairedDocumentMap.putIfAbsent(reference.uniqueKey(), reference);
        }

        List<SearchReference> repairedReferences = new ArrayList<>();
        allReferences.stream()
            .filter(reference -> !isRepairableDocumentReference(reference))
            .forEach(repairedReferences::add);
        repairedReferences.addAll(repairedDocumentMap.values());
        return repairedReferences;
    }

    private void applyCitation(SearchReference reference, RagToolsCitationRepairResponse.Result citation) {
        reference.setCitationRepaired(true);
        reference.setAnswerSegment(StrUtil.blankToDefault(citation.getAnswerSegment(), ""));
        reference.setQuoteText(StrUtil.blankToDefault(citation.getQuoteText(), ""));
        reference.setCitationScore(citation.getScore());
        reference.setCitationSegmentIndex(citation.getSegmentIndex());
        reference.setCitationRank(citation.getRank());
        if (citation.getPageNo() != null) {
            reference.setPageNo(citation.getPageNo());
        }
        if (StrUtil.isNotBlank(citation.getPageRange())) {
            reference.setPageRange(citation.getPageRange());
        }
        if (StrUtil.isNotBlank(citation.getBboxJson())) {
            reference.setBboxJson(citation.getBboxJson());
        }
        if (citation.getChunkId() != null) {
            reference.setChunkId(citation.getChunkId());
        }
        if (citation.getParentBlockId() != null) {
            reference.setParentBlockId(citation.getParentBlockId());
        }
        if (StrUtil.isNotBlank(citation.getSectionPath())) {
            reference.setSectionPath(citation.getSectionPath());
        }
    }

    private Map<String, Object> buildEvidenceMetadata(SearchReference reference) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("referenceId", StrUtil.blankToDefault(reference.getReferenceId(), ""));
        metadata.put("chunkNo", reference.getChunkNo());
        metadata.put("parentBlockNo", reference.getParentBlockNo());
        metadata.put("sourceBlockIds", StrUtil.blankToDefault(reference.getSourceBlockIds(), ""));
        metadata.put("knowledgeScopeCode", StrUtil.blankToDefault(reference.getKnowledgeScopeCode(), ""));
        metadata.put("knowledgeScopeName", StrUtil.blankToDefault(reference.getKnowledgeScopeName(), ""));
        metadata.put("channel", StrUtil.blankToDefault(reference.getChannel(), ""));
        return metadata;
    }

    private List<Map<String, Object>> buildCitationTrace(List<SearchReference> references) {
        return references.stream()
            .filter(SearchReference::isCitationRepaired)
            .limit(8)
            .map(reference -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("referenceId", StrUtil.blankToDefault(reference.getReferenceId(), ""));
                item.put("documentName", StrUtil.blankToDefault(reference.getDocumentName(), reference.getTitle()));
                item.put("chunkId", reference.getChunkId());
                item.put("parentBlockId", reference.getParentBlockId());
                item.put("pageNo", reference.getPageNo());
                item.put("pageRange", StrUtil.blankToDefault(reference.getPageRange(), ""));
                item.put("score", reference.getCitationScore());
                item.put("answerSegment", StrUtil.blankToDefault(reference.getAnswerSegment(), ""));
                item.put("quoteText", StrUtil.blankToDefault(reference.getQuoteText(), ""));
                return item;
            })
            .toList();
    }

    private int countDocumentReferences(List<SearchReference> references) {
        return (int) references.stream().filter(this::isRepairableDocumentReference).count();
    }

    private boolean isRepairableDocumentReference(SearchReference reference) {
        return reference != null
            && "DOCUMENT".equalsIgnoreCase(StrUtil.blankToDefault(reference.getSourceType(), ""))
            && StrUtil.isNotBlank(reference.getSnippet());
    }

    private String evidenceId(SearchReference reference) {
        if (StrUtil.isNotBlank(reference.getReferenceId())) {
            return reference.getReferenceId();
        }
        return reference.uniqueKey();
    }
}
