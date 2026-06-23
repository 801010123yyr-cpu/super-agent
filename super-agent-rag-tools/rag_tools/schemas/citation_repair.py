from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class CitationEvidence(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: str
    text: str = ""
    document_id: int | None = Field(default=None, alias="documentId")
    document_name: str = Field(default="", alias="documentName")
    chunk_id: int | None = Field(default=None, alias="chunkId")
    parent_block_id: int | None = Field(default=None, alias="parentBlockId")
    page_no: int | None = Field(default=None, alias="pageNo")
    page_range: str = Field(default="", alias="pageRange")
    bbox_json: str = Field(default="", alias="bboxJson")
    section_path: str = Field(default="", alias="sectionPath")
    metadata: dict[str, Any] = Field(default_factory=dict)


class CitationRepairRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    answer: str = ""
    evidences: list[CitationEvidence] = Field(default_factory=list)
    max_segments: int = Field(default=16, alias="maxSegments", ge=1)
    max_matches_per_segment: int = Field(default=1, alias="maxMatchesPerSegment", ge=1)
    min_score: float = Field(default=0.18, alias="minScore", ge=0.0)


class CitationRepairResult(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    evidence_id: str = Field(alias="evidenceId")
    answer_segment: str = Field(alias="answerSegment")
    segment_index: int = Field(alias="segmentIndex")
    quote_text: str = Field(alias="quoteText")
    score: float
    rank: int
    document_id: int | None = Field(default=None, alias="documentId")
    document_name: str = Field(default="", alias="documentName")
    chunk_id: int | None = Field(default=None, alias="chunkId")
    parent_block_id: int | None = Field(default=None, alias="parentBlockId")
    page_no: int | None = Field(default=None, alias="pageNo")
    page_range: str = Field(default="", alias="pageRange")
    bbox_json: str = Field(default="", alias="bboxJson")
    section_path: str = Field(default="", alias="sectionPath")


class CitationRepairResponse(BaseModel):
    citations: list[CitationRepairResult] = Field(default_factory=list)
