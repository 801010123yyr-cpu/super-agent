import math
import re
from collections import Counter

from rag_tools.schemas.citation_repair import (
    CitationEvidence,
    CitationRepairRequest,
    CitationRepairResponse,
    CitationRepairResult,
)


def repair_citations(request: CitationRepairRequest) -> CitationRepairResponse:
    segments = _split_answer_segments(request.answer, request.max_segments)
    if not segments or not request.evidences:
        return CitationRepairResponse()

    citations: list[CitationRepairResult] = []
    for segment_index, segment in enumerate(segments, start=1):
        scored_evidences = []
        segment_terms = _tokenize(segment)
        segment_bigrams = _char_bigrams(segment)
        for evidence_index, evidence in enumerate(request.evidences):
            score = _score_evidence(segment_terms, segment_bigrams, evidence.text)
            if score < request.min_score:
                continue
            scored_evidences.append((score, evidence_index, evidence))

        scored_evidences.sort(key=lambda item: (-item[0], item[1]))
        for rank, (score, _, evidence) in enumerate(scored_evidences[: request.max_matches_per_segment], start=1):
            citations.append(
                CitationRepairResult(
                    evidence_id=evidence.id,
                    answer_segment=segment,
                    segment_index=segment_index,
                    quote_text=_best_quote(segment_terms, evidence.text),
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


def _score_evidence(segment_terms: list[str], segment_bigrams: set[str], evidence_text: str) -> float:
    evidence_terms = _tokenize(evidence_text)
    if not segment_terms or not evidence_terms:
        return 0.0

    evidence_counter = Counter(evidence_terms)
    unique_segment_terms = set(segment_terms)
    matched_terms = unique_segment_terms.intersection(evidence_counter.keys())
    term_recall = len(matched_terms) / max(len(unique_segment_terms), 1)
    term_frequency = sum(math.log1p(evidence_counter[term]) for term in matched_terms)

    evidence_bigrams = _char_bigrams(evidence_text)
    bigram_overlap = len(segment_bigrams.intersection(evidence_bigrams)) / max(len(segment_bigrams), 1)
    phrase_bonus = 0.12 if _phrase_present(segment_terms, evidence_text) else 0.0
    length_penalty = 1.0 / (1.0 + max(len(evidence_terms) - 180, 0) / 360.0)

    return (term_recall * 0.50 + bigram_overlap * 0.30 + min(term_frequency / 10.0, 1.0) * 0.20 + phrase_bonus) * length_penalty


def _best_quote(segment_terms: list[str], evidence_text: str) -> str:
    text = (evidence_text or "").strip()
    if len(text) <= 260:
        return text

    normalized_text = _normalize(text)
    best_index = -1
    for term in sorted(set(segment_terms), key=len, reverse=True):
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


def _phrase_present(terms: list[str], text: str) -> bool:
    normalized_text = _normalize(text)
    return any(len(term) >= 4 and term in normalized_text for term in terms)


def _tokenize(text: str) -> list[str]:
    normalized = _normalize(text)
    alnum_terms = re.findall(r"[a-z0-9._-]{2,}", normalized)
    chinese_terms = re.findall(r"[\u4e00-\u9fff]{2,}", normalized)
    split_chinese_terms = []
    for term in chinese_terms:
        if len(term) <= 6:
            split_chinese_terms.append(term)
            continue
        split_chinese_terms.extend(term[index:index + 4] for index in range(0, len(term) - 1, 2))
    return alnum_terms + split_chinese_terms


def _char_bigrams(text: str) -> set[str]:
    normalized = _normalize(text)
    chars = [char for char in normalized if not char.isspace()]
    return {chars[index] + chars[index + 1] for index in range(len(chars) - 1)}


def _normalize(text: str) -> str:
    return (text or "").lower()
