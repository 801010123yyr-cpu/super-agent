import math
import re
from collections import Counter

from fastapi import FastAPI

from rag_tools.citation_repair import repair_citations
from rag_tools.document_parser import parse_document
from rag_tools.schemas.citation_repair import CitationRepairRequest, CitationRepairResponse
from rag_tools.schemas.document_parse import DocumentParseRequest, DocumentParseResponse
from rag_tools.schemas.rerank import RerankRequest, RerankResponse, RerankResult

SERVICE_NAME = "super-agent-rag-tools"
SERVICE_VERSION = "0.1.0"

app = FastAPI(title=SERVICE_NAME, version=SERVICE_VERSION)


@app.get("/health")
def health() -> dict[str, str]:
    return {
        "status": "ok",
        "service": SERVICE_NAME,
        "version": SERVICE_VERSION,
    }


@app.post("/rerank", response_model=RerankResponse)
def rerank(request: RerankRequest) -> RerankResponse:
    top_k = max(0, min(request.top_k, len(request.candidates)))
    query_terms = _tokenize(request.query)
    query_bigrams = _char_bigrams(request.query)
    scored_candidates = []
    for index, candidate in enumerate(request.candidates):
        score = _score_candidate(query_terms, query_bigrams, candidate.text)
        scored_candidates.append((score, index, candidate))
    scored_candidates.sort(key=lambda item: (-item[0], item[1]))
    results = [
        RerankResult(
            id=candidate.id,
            score=round(score, 6),
            rank=index + 1,
        )
        for index, (score, _, candidate) in enumerate(scored_candidates[:top_k])
    ]
    return RerankResponse(results=results)


@app.post("/document/parse", response_model=DocumentParseResponse)
def document_parse(request: DocumentParseRequest) -> DocumentParseResponse:
    return parse_document(request)


@app.post("/citation/repair", response_model=CitationRepairResponse)
def citation_repair(request: CitationRepairRequest) -> CitationRepairResponse:
    return repair_citations(request)


def _score_candidate(query_terms: list[str], query_bigrams: set[str], text: str) -> float:
    text_terms = _tokenize(text)
    if not query_terms or not text_terms:
        return 0.0

    text_counter = Counter(text_terms)
    unique_query_terms = set(query_terms)
    matched_terms = unique_query_terms.intersection(text_counter.keys())
    term_recall = len(matched_terms) / max(len(unique_query_terms), 1)
    term_frequency = sum(math.log1p(text_counter[term]) for term in matched_terms)
    length_penalty = 1.0 / (1.0 + max(len(text_terms) - 80, 0) / 160.0)

    text_bigrams = _char_bigrams(text)
    bigram_overlap = len(query_bigrams.intersection(text_bigrams)) / max(len(query_bigrams), 1)
    phrase_bonus = 0.15 if request_phrase_present(query_terms, text) else 0.0

    return (term_recall * 0.55 + bigram_overlap * 0.25 + min(term_frequency / 8.0, 1.0) * 0.20 + phrase_bonus) * length_penalty


def request_phrase_present(query_terms: list[str], text: str) -> bool:
    normalized_text = _normalize(text)
    return any(len(term) >= 3 and term in normalized_text for term in query_terms)


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
