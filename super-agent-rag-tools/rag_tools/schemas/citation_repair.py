from typing import Annotated, Any

from pydantic import BaseModel, ConfigDict, Field


class CitationEvidence(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: str
    text: str = ""
    document_id: Annotated[int | None, Field(alias="documentId")] = None
    document_name: Annotated[str, Field(alias="documentName")] = ""
    chunk_id: Annotated[int | None, Field(alias="chunkId")] = None
    parent_block_id: Annotated[int | None, Field(alias="parentBlockId")] = None
    page_no: Annotated[int | None, Field(alias="pageNo")] = None
    page_range: Annotated[str, Field(alias="pageRange")] = ""
    bbox_json: Annotated[str, Field(alias="bboxJson")] = ""
    section_path: Annotated[str, Field(alias="sectionPath")] = ""
    metadata: dict[str, Any] = Field(default_factory=dict)


class CitationRepairRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    answer: str = ""
    evidences: list[CitationEvidence] = Field(default_factory=list)
    max_segments: Annotated[int, Field(alias="maxSegments", ge=1)] = 16
    max_matches_per_segment: Annotated[int, Field(alias="maxMatchesPerSegment", ge=1)] = 1
    min_score: Annotated[float, Field(alias="minScore", ge=0.0)] = 0.18


class CitationRepairResult(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    evidence_id: Annotated[str, Field(alias="evidenceId")]
    answer_segment: Annotated[str, Field(alias="answerSegment")]
    segment_index: Annotated[int, Field(alias="segmentIndex")]
    quote_text: Annotated[str, Field(alias="quoteText")]
    score: float
    rank: int
    document_id: Annotated[int | None, Field(alias="documentId")] = None
    document_name: Annotated[str, Field(alias="documentName")] = ""
    chunk_id: Annotated[int | None, Field(alias="chunkId")] = None
    parent_block_id: Annotated[int | None, Field(alias="parentBlockId")] = None
    page_no: Annotated[int | None, Field(alias="pageNo")] = None
    page_range: Annotated[str, Field(alias="pageRange")] = ""
    bbox_json: Annotated[str, Field(alias="bboxJson")] = ""
    section_path: Annotated[str, Field(alias="sectionPath")] = ""


class CitationRepairResponse(BaseModel):
    citations: list[CitationRepairResult] = Field(default_factory=list)
