import re

from fastapi import HTTPException

from rag_tools.semantic_model import SemanticModelUnavailable, score_citation_pairs
from rag_tools.schemas.citation_repair import (
    CitationRepairRequest,
    CitationRepairResponse,
    CitationRepairResult,
)


def repair_citations(request: CitationRepairRequest) -> CitationRepairResponse:
    segments = _split_answer_segments(request.answer, request.max_segments)
    if not segments or not request.evidences:
        return CitationRepairResponse()

    pairs: list[tuple[str, str]] = []
    pair_refs: list[tuple[int, int]] = []
    for segment_index, segment in enumerate(segments, start=1):
        for evidence_index, evidence in enumerate(request.evidences):
            pairs.append((segment, evidence.text or ""))
            pair_refs.append((segment_index, evidence_index))
    try:
        semantic_scores = score_citation_pairs(pairs)
    except SemanticModelUnavailable as exception:
        raise HTTPException(status_code=503, detail=str(exception)) from exception

    scores_by_segment: dict[int, list[tuple[float, int]]] = {}
    for score, (segment_index, evidence_index) in zip(semantic_scores, pair_refs):
        if score < request.min_score:
            continue
        scores_by_segment.setdefault(segment_index, []).append((score, evidence_index))

    citations: list[CitationRepairResult] = []
    for segment_index, segment in enumerate(segments, start=1):
        scored_evidences = sorted(scores_by_segment.get(segment_index, []), key=lambda item: (-item[0], item[1]))
        for rank, (score, evidence_index) in enumerate(scored_evidences[: request.max_matches_per_segment], start=1):
            evidence = request.evidences[evidence_index]
            citations.append(
                CitationRepairResult(
                    evidence_id=evidence.id,
                    answer_segment=segment,
                    segment_index=segment_index,
                    quote_text=_best_quote(segment, evidence.text),
                    score=round(score, 6),
                    rank=rank,
                    document_id=evidence.document_id,
                    document_name=evidence.document_name,
                    chunk_id=evidence.chunk_id,
                    parent_block_id=evidence.parent_block_id,
                    page_no=evidence.page_no,
                    page_range=evidence.page_range,
                    bbox_json=evidence.bbox_json,
                    section_path=evidence.section_path,
                )
            )
    return CitationRepairResponse(citations=citations)


def _split_answer_segments(answer: str, max_segments: int) -> list[str]:
    normalized = (answer or "").strip()
    if not normalized:
        return []
    raw_segments = re.split(r"(?<=[。！？!?；;])\s+|\n+", normalized)
    segments: list[str] = []
    for raw_segment in raw_segments:
        for segment in re.split(r"(?<=[。！？!?；;])", raw_segment):
            cleaned = segment.strip()
            if len(cleaned) >= 6:
                segments.append(cleaned)
            if len(segments) >= max_segments:
                return segments
    return segments


def _best_quote(segment: str, evidence_text: str) -> str:
    text = (evidence_text or "").strip()
    if len(text) <= 260:
        return text

    normalized_text = _normalize(text)
    best_index = -1
    for term in _quote_terms(segment):
        if len(term) < 2:
            continue
        best_index = normalized_text.find(term)
        if best_index >= 0:
            break
    if best_index < 0:
        return text[:259] + "…"

    start = max(0, best_index - 100)
    end = min(len(text), best_index + 160)
    quote = text[start:end].strip()
    if start > 0:
        quote = "…" + quote
    if end < len(text):
        quote = quote + "…"
    return quote


def _quote_terms(segment: str) -> list[str]:
    normalized = _normalize(segment)
    terms = re.findall(r"[a-z0-9._-]{2,}", normalized)
    terms.extend(re.findall(r"[\u4e00-\u9fff]{2,}", normalized))
    return sorted(set(terms), key=len, reverse=True)


def _normalize(text: str) -> str:
    return (text or "").lower()
