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
    private static final int MAX_TRACE_CANDIDATES = 24;
    private static final int MAX_TRACE_CITATIONS = 24;

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
                traceRecorder.completeStage(
                    citationStage,
                    "引用修复完成。",
                    buildRepairTraceSnapshot(references, documentReferences, response.getCitations(), repairedReferences)
                );
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
        metadata.put("tableId", reference.getTableId());
        metadata.put("tableNo", reference.getTableNo());
        metadata.put("tableTitle", StrUtil.blankToDefault(reference.getTableTitle(), ""));
        metadata.put("tableOperation", StrUtil.blankToDefault(reference.getTableOperation(), ""));
        metadata.put("tableMetricColumn", StrUtil.blankToDefault(reference.getTableMetricColumn(), ""));
        metadata.put("tableGroupByColumn", StrUtil.blankToDefault(reference.getTableGroupByColumn(), ""));
        metadata.put("tableMatchedRowCount", reference.getTableMatchedRowCount());
        metadata.put("tableEvidenceRowIds", reference.getTableEvidenceRowIds());
        metadata.put("tableEvidenceRowNos", reference.getTableEvidenceRowNos());
        metadata.put("tableEvidenceColumnIds", reference.getTableEvidenceColumnIds());
        metadata.put("tableEvidenceColumnNos", reference.getTableEvidenceColumnNos());
        metadata.put("tableEvidenceColumnNames", reference.getTableEvidenceColumnNames());
        metadata.put("tableEvidenceCellIds", reference.getTableEvidenceCellIds());
        metadata.put("tableEvidenceCellCoordinates", reference.getTableEvidenceCellCoordinates());
        metadata.put("tableEvidenceCellBboxJsons", reference.getTableEvidenceCellBboxJsons());
        return metadata;
    }

    private Map<String, Object> buildRepairTraceSnapshot(List<SearchReference> allReferences,
                                                         List<SearchReference> documentReferences,
                                                         List<RagToolsCitationRepairResponse.Result> citations,
                                                         List<SearchReference> repairedReferences) {
        List<RagToolsCitationRepairResponse.Result> safeCitations = citations == null ? List.of() : citations;
        List<Map<String, Object>> finalCitations = buildCitationTrace(repairedReferences, MAX_TRACE_CITATIONS);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("candidateReferenceCount", allReferences == null ? 0 : allReferences.size());
        snapshot.put("documentReferenceCount", documentReferences == null ? 0 : documentReferences.size());
        snapshot.put("matchedCitationCount", safeCitations.size());
        snapshot.put("repairedReferenceCount", repairedReferences == null ? 0 : repairedReferences.size());
        snapshot.put("repairedDocumentReferenceCount", countDocumentReferences(repairedReferences));
        snapshot.put("removedDocumentReferenceCount", Math.max(0, (documentReferences == null ? 0 : documentReferences.size()) - countDocumentReferences(repairedReferences)));
        snapshot.put("minScore", MIN_SCORE);
        snapshot.put("maxSegments", MAX_SEGMENTS);
        snapshot.put("maxMatchesPerSegment", MAX_MATCHES_PER_SEGMENT);
        snapshot.put("candidateEvidences", buildCandidateEvidenceTrace(documentReferences));
        snapshot.put("matchedCitations", buildMatchedCitationTrace(safeCitations, documentReferences));
        snapshot.put("finalCitations", finalCitations);
        snapshot.put("removedCandidates", buildRemovedCandidateTrace(documentReferences, safeCitations));
        snapshot.put("citations", finalCitations);
        return snapshot;
    }

    private List<Map<String, Object>> buildCandidateEvidenceTrace(List<SearchReference> documentReferences) {
        return (documentReferences == null ? List.<SearchReference>of() : documentReferences).stream()
            .limit(MAX_TRACE_CANDIDATES)
            .map(reference -> {
                Map<String, Object> item = baseReferenceTrace(reference);
                item.put("evidenceId", evidenceId(reference));
                item.put("candidateText", StrUtil.blankToDefault(reference.getSnippet(), ""));
                item.put("repairedBefore", reference.isCitationRepaired());
                item.put("previousAnswerSegment", StrUtil.blankToDefault(reference.getAnswerSegment(), ""));
                item.put("previousQuoteText", StrUtil.blankToDefault(reference.getQuoteText(), ""));
                item.put("previousCitationScore", reference.getCitationScore());
                return item;
            })
            .toList();
    }

    private List<Map<String, Object>> buildMatchedCitationTrace(List<RagToolsCitationRepairResponse.Result> citations,
                                                               List<SearchReference> documentReferences) {
        Map<String, SearchReference> referencesByEvidenceId = new LinkedHashMap<>();
        (documentReferences == null ? List.<SearchReference>of() : documentReferences)
            .forEach(reference -> referencesByEvidenceId.put(evidenceId(reference), reference));
        return (citations == null ? List.<RagToolsCitationRepairResponse.Result>of() : citations).stream()
            .limit(MAX_TRACE_CITATIONS)
            .map(citation -> {
                SearchReference reference = citation == null ? null : referencesByEvidenceId.get(citation.getEvidenceId());
                Map<String, Object> item = reference == null ? new LinkedHashMap<>() : baseReferenceTrace(reference);
                item.put("evidenceId", citation == null ? "" : StrUtil.blankToDefault(citation.getEvidenceId(), ""));
                item.put("matched", reference != null);
                item.put("answerSegment", citation == null ? "" : StrUtil.blankToDefault(citation.getAnswerSegment(), ""));
                item.put("segmentIndex", citation == null ? null : citation.getSegmentIndex());
                item.put("quoteText", citation == null ? "" : StrUtil.blankToDefault(citation.getQuoteText(), ""));
                item.put("citationScore", citation == null ? null : citation.getScore());
                item.put("rank", citation == null ? null : citation.getRank());
                item.put("repairedBefore", reference != null && reference.isCitationRepaired());
                item.put("repairedAfter", reference != null);
                item.put("filteredReason", reference == null ? "rag-tools 返回的 evidenceId 不在候选证据中" : "");
                if (citation != null) {
                    item.put("documentId", citation.getDocumentId() == null && reference != null ? reference.getDocumentId() : citation.getDocumentId());
                    item.put("documentName", StrUtil.blankToDefault(citation.getDocumentName(), reference == null ? "" : reference.getDocumentName()));
                    item.put("chunkId", citation.getChunkId() == null && reference != null ? reference.getChunkId() : citation.getChunkId());
                    item.put("parentBlockId", citation.getParentBlockId() == null && reference != null ? reference.getParentBlockId() : citation.getParentBlockId());
                    item.put("pageNo", citation.getPageNo() == null && reference != null ? reference.getPageNo() : citation.getPageNo());
                    item.put("pageRange", StrUtil.blankToDefault(citation.getPageRange(), reference == null ? "" : reference.getPageRange()));
                    item.put("bboxJson", StrUtil.blankToDefault(citation.getBboxJson(), reference == null ? "" : reference.getBboxJson()));
                    item.put("sectionPath", StrUtil.blankToDefault(citation.getSectionPath(), reference == null ? "" : reference.getSectionPath()));
                }
                return item;
            })
            .toList();
    }

    private List<Map<String, Object>> buildRemovedCandidateTrace(List<SearchReference> documentReferences,
                                                                List<RagToolsCitationRepairResponse.Result> citations) {
        List<String> matchedEvidenceIds = (citations == null ? List.<RagToolsCitationRepairResponse.Result>of() : citations).stream()
            .filter(citation -> citation != null && StrUtil.isNotBlank(citation.getEvidenceId()))
            .map(RagToolsCitationRepairResponse.Result::getEvidenceId)
            .toList();
        return (documentReferences == null ? List.<SearchReference>of() : documentReferences).stream()
            .filter(reference -> !matchedEvidenceIds.contains(evidenceId(reference)))
            .limit(MAX_TRACE_CANDIDATES)
            .map(reference -> {
                Map<String, Object> item = baseReferenceTrace(reference);
                item.put("evidenceId", evidenceId(reference));
                item.put("candidateText", StrUtil.blankToDefault(reference.getSnippet(), ""));
                item.put("repairedBefore", reference.isCitationRepaired());
                item.put("repairedAfter", false);
                item.put("filteredReason", "未达到 citation repair 语义匹配阈值或该答案句已有更高分证据");
                return item;
            })
            .toList();
    }

    private List<Map<String, Object>> buildCitationTrace(List<SearchReference> references) {
        return buildCitationTrace(references, 8);
    }

    private List<Map<String, Object>> buildCitationTrace(List<SearchReference> references, int limit) {
        return (references == null ? List.<SearchReference>of() : references).stream()
            .filter(SearchReference::isCitationRepaired)
            .limit(limit)
            .map(reference -> {
                Map<String, Object> item = baseReferenceTrace(reference);
                item.put("evidenceId", evidenceId(reference));
                item.put("tableId", reference.getTableId());
                item.put("tableEvidenceRowNos", reference.getTableEvidenceRowNos());
                item.put("tableEvidenceColumnNames", reference.getTableEvidenceColumnNames());
                item.put("tableEvidenceCellCoordinates", reference.getTableEvidenceCellCoordinates());
                item.put("tableEvidenceCellBboxJsons", reference.getTableEvidenceCellBboxJsons());
                item.put("score", reference.getCitationScore());
                item.put("citationScore", reference.getCitationScore());
                item.put("rank", reference.getCitationRank());
                item.put("segmentIndex", reference.getCitationSegmentIndex());
                item.put("answerSegment", StrUtil.blankToDefault(reference.getAnswerSegment(), ""));
                item.put("quoteText", StrUtil.blankToDefault(reference.getQuoteText(), ""));
                item.put("repairedBefore", false);
                item.put("repairedAfter", true);
                item.put("filteredReason", "");
                return item;
            })
            .toList();
    }

    private Map<String, Object> baseReferenceTrace(SearchReference reference) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("referenceId", StrUtil.blankToDefault(reference.getReferenceId(), ""));
        item.put("sourceType", StrUtil.blankToDefault(reference.getSourceType(), ""));
        item.put("channel", StrUtil.blankToDefault(reference.getChannel(), ""));
        item.put("documentId", reference.getDocumentId());
        item.put("documentName", StrUtil.blankToDefault(reference.getDocumentName(), reference.getTitle()));
        item.put("chunkId", reference.getChunkId());
        item.put("chunkNo", reference.getChunkNo());
        item.put("parentBlockId", reference.getParentBlockId());
        item.put("parentBlockNo", reference.getParentBlockNo());
        item.put("sectionPath", StrUtil.blankToDefault(reference.getSectionPath(), ""));
        item.put("pageNo", reference.getPageNo());
        item.put("pageRange", StrUtil.blankToDefault(reference.getPageRange(), ""));
        item.put("bboxJson", StrUtil.blankToDefault(reference.getBboxJson(), ""));
        item.put("rerankScore", reference.getScore());
        item.put("referenceScore", reference.getScore());
        item.put("tableId", reference.getTableId());
        item.put("tableNo", reference.getTableNo());
        item.put("tableTitle", StrUtil.blankToDefault(reference.getTableTitle(), ""));
        item.put("kgEvidenceId", reference.getKgEvidenceId());
        item.put("kgEntityName", StrUtil.blankToDefault(reference.getKgEntityName(), ""));
        item.put("kgRelationType", StrUtil.blankToDefault(reference.getKgRelationType(), ""));
        item.put("raptorNodeId", reference.getRaptorNodeId());
        item.put("raptorNodeTitle", StrUtil.blankToDefault(reference.getRaptorNodeTitle(), ""));
        return item;
    }

    private int countDocumentReferences(List<SearchReference> references) {
        return (int) references.stream().filter(this::isRepairableDocumentReference).count();
    }

    private boolean isRepairableDocumentReference(SearchReference reference) {
        return reference != null
            && ("DOCUMENT".equalsIgnoreCase(StrUtil.blankToDefault(reference.getSourceType(), ""))
            || "DOCUMENT_TABLE".equalsIgnoreCase(StrUtil.blankToDefault(reference.getSourceType(), "")))
            && StrUtil.isNotBlank(reference.getSnippet());
    }

    private String evidenceId(SearchReference reference) {
        if (StrUtil.isNotBlank(reference.getReferenceId())) {
            return reference.getReferenceId();
        }
        return reference.uniqueKey();
    }
}
