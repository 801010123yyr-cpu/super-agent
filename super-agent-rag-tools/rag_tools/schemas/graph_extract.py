from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class GraphChunk(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    chunk_id: int | None = Field(default=None, alias="chunkId")
    parent_block_id: int | None = Field(default=None, alias="parentBlockId")
    chunk_no: int | None = Field(default=None, alias="chunkNo")
    chunk_type: str = Field(default="", alias="chunkType")
    title: str = ""
    section_path: str = Field(default="", alias="sectionPath")
    page_no: int | None = Field(default=None, alias="pageNo")
    page_range: str = Field(default="", alias="pageRange")
    bbox_json: str = Field(default="", alias="bboxJson")
    text: str = ""
    content_with_weight: str = Field(default="", alias="contentWithWeight")
    source_block_ids: str = Field(default="", alias="sourceBlockIds")
    metadata: dict[str, Any] = Field(default_factory=dict)


class GraphExtractRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    document_id: int | None = Field(default=None, alias="documentId")
    task_id: int | None = Field(default=None, alias="taskId")
    chunks: list[GraphChunk] = Field(default_factory=list)


class GraphEntity(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: str
    name: str
    normalized_name: str = Field(alias="normalizedName")
    aliases: list[str] = Field(default_factory=list)
    type: str = "CONCEPT"
    description: str = ""
    confidence: float = 0.0
    source_chunk_ids: list[int] = Field(default_factory=list, alias="sourceChunkIds")
    evidence_ids: list[str] = Field(default_factory=list, alias="evidenceIds")
    metadata: dict[str, Any] = Field(default_factory=dict)


class GraphEvidence(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: str
    entity_id: str = Field(default="", alias="entityId")
    relation_id: str = Field(default="", alias="relationId")
    chunk_id: int | None = Field(default=None, alias="chunkId")
    parent_block_id: int | None = Field(default=None, alias="parentBlockId")
    quote_text: str = Field(default="", alias="quoteText")
    page_no: int | None = Field(default=None, alias="pageNo")
    page_range: str = Field(default="", alias="pageRange")
    bbox_json: str = Field(default="", alias="bboxJson")
    section_path: str = Field(default="", alias="sectionPath")
    metadata: dict[str, Any] = Field(default_factory=dict)


class GraphRelation(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: str
    source_entity_id: str = Field(alias="sourceEntityId")
    target_entity_id: str = Field(alias="targetEntityId")
    relation_type: str = Field(default="ASSOCIATED_WITH", alias="relationType")
    description: str = ""
    weight: float = 1.0
    evidence_ids: list[str] = Field(default_factory=list, alias="evidenceIds")
    metadata: dict[str, Any] = Field(default_factory=dict)


class GraphCommunity(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: str
    title: str
    summary: str = ""
    entity_ids: list[str] = Field(default_factory=list, alias="entityIds")
    relation_ids: list[str] = Field(default_factory=list, alias="relationIds")
    evidence_ids: list[str] = Field(default_factory=list, alias="evidenceIds")
    metadata: dict[str, Any] = Field(default_factory=dict)


class GraphExtractResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    entities: list[GraphEntity] = Field(default_factory=list)
    relations: list[GraphRelation] = Field(default_factory=list)
    evidences: list[GraphEvidence] = Field(default_factory=list)
    communities: list[GraphCommunity] = Field(default_factory=list)
    metadata: dict[str, Any] = Field(default_factory=dict)
