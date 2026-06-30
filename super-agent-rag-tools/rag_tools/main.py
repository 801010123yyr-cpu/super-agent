import logging
import time
import warnings

from fastapi import FastAPI, HTTPException
from pydantic.warnings import UnsupportedFieldAttributeWarning

from rag_tools.citation_repair import repair_citations
from rag_tools.document_parser import document_parser_status, parse_document
from rag_tools.graph_extract import extract_graph
from rag_tools.raptor_build import build_raptor
from rag_tools.schemas.citation_repair import CitationRepairRequest, CitationRepairResponse
from rag_tools.schemas.document_parse import DocumentParseRequest, DocumentParseResponse
from rag_tools.schemas.graph_extract import GraphExtractRequest, GraphExtractResponse
from rag_tools.schemas.raptor_build import RaptorBuildRequest, RaptorBuildResponse
from rag_tools.schemas.rerank import RerankRequest, RerankResponse, RerankResult
from rag_tools.semantic_model import SemanticModelUnavailable, score_rerank_pairs, semantic_model_status

SERVICE_NAME = "super-agent-rag-tools"
SERVICE_VERSION = "0.1.0"

logger = logging.getLogger(__name__)

warnings.filterwarnings("ignore", category=UnsupportedFieldAttributeWarning)

app = FastAPI(title=SERVICE_NAME, version=SERVICE_VERSION)


@app.get("/health")
def health() -> dict[str, object]:
    return {
        "status": "ok",
        "service": SERVICE_NAME,
        "version": SERVICE_VERSION,
        "semanticModels": semantic_model_status(),
        "documentParsers": document_parser_status(),
    }


@app.post("/rerank", response_model=RerankResponse)
def rerank(request: RerankRequest) -> RerankResponse:
    top_k = max(0, min(request.top_k, len(request.candidates)))
    if top_k == 0:
        return RerankResponse()
    try:
        scores = score_rerank_pairs(request.query, [candidate.text for candidate in request.candidates])
    except SemanticModelUnavailable as exception:
        raise HTTPException(status_code=503, detail=str(exception)) from exception

    scored_candidates = [
        (score, index, candidate)
        for index, (score, candidate) in enumerate(zip(scores, request.candidates))
    ]
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
    started = time.perf_counter()
    try:
        response = parse_document(request)
        logger.info(
            "document_parse completed file_name=%s file_type=%s parsed_text_length=%s block_count=%s artifact_count=%s structure_node_count=%s cost_ms=%s",
            request.file_name,
            request.file_type,
            len(response.parsed_text or ""),
            len(response.blocks or []),
            len(response.artifacts or []),
            len(response.structure_nodes or []),
            int((time.perf_counter() - started) * 1000),
        )
        return response
    except Exception:
        logger.exception(
            "document_parse failed file_name=%s file_type=%s cost_ms=%s",
            request.file_name,
            request.file_type,
            int((time.perf_counter() - started) * 1000),
        )
        raise


@app.post("/citation/repair", response_model=CitationRepairResponse)
def citation_repair(request: CitationRepairRequest) -> CitationRepairResponse:
    return repair_citations(request)


@app.post("/graph/extract", response_model=GraphExtractResponse)
def graph_extract(request: GraphExtractRequest) -> GraphExtractResponse:
    return extract_graph(request)


@app.post("/raptor/build", response_model=RaptorBuildResponse)
def raptor_build(request: RaptorBuildRequest) -> RaptorBuildResponse:
    return build_raptor(request)
