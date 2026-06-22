from typing import Annotated, Any

from pydantic import BaseModel, ConfigDict, Field


class GraphChunk(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    chunk_id: Annotated[int | None, Field(alias="chunkId")] = None
    parent_block_id: Annotated[int | None, Field(alias="parentBlockId")] = None
    chunk_no: Annotated[int | None, Field(alias="chunkNo")] = None
    chunk_type: Annotated[str, Field(alias="chunkType")] = ""
    title: str = ""
    section_path: Annotated[str, Field(alias="sectionPath")] = ""
    page_no: Annotated[int | None, Field(alias="pageNo")] = None
    page_range: Annotated[str, Field(alias="pageRange")] = ""
    bbox_json: Annotated[str, Field(alias="bboxJson")] = ""
    text: str = ""
    content_with_weight: Annotated[str, Field(alias="contentWithWeight")] = ""
    source_block_ids: Annotated[str, Field(alias="sourceBlockIds")] = ""
    metadata: dict[str, Any] = Field(default_factory=dict)


class GraphExtractRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    document_id: Annotated[int | None, Field(alias="documentId")] = None
    task_id: Annotated[int | None, Field(alias="taskId")] = None
    chunks: list[GraphChunk] = Field(default_factory=list)


class GraphEntity(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: str
    name: str
    normalized_name: Annotated[str, Field(alias="normalizedName")]
    type: str = "CONCEPT"
    description: str = ""
    source_chunk_ids: Annotated[list[int], Field(alias="sourceChunkIds")] = Field(default_factory=list)
    evidence_ids: Annotated[list[str], Field(alias="evidenceIds")] = Field(default_factory=list)
    metadata: dict[str, Any] = Field(default_factory=dict)


class GraphEvidence(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: str
    entity_id: Annotated[str, Field(alias="entityId")] = ""
    relation_id: Annotated[str, Field(alias="relationId")] = ""
    chunk_id: Annotated[int | None, Field(alias="chunkId")] = None
    parent_block_id: Annotated[int | None, Field(alias="parentBlockId")] = None
    quote_text: Annotated[str, Field(alias="quoteText")] = ""
    page_no: Annotated[int | None, Field(alias="pageNo")] = None
    page_range: Annotated[str, Field(alias="pageRange")] = ""
    bbox_json: Annotated[str, Field(alias="bboxJson")] = ""
    section_path: Annotated[str, Field(alias="sectionPath")] = ""
    metadata: dict[str, Any] = Field(default_factory=dict)


class GraphRelation(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: str
    source_entity_id: Annotated[str, Field(alias="sourceEntityId")]
    target_entity_id: Annotated[str, Field(alias="targetEntityId")]
    relation_type: Annotated[str, Field(alias="relationType")] = "ASSOCIATED_WITH"
    description: str = ""
    weight: float = 1.0
    evidence_ids: Annotated[list[str], Field(alias="evidenceIds")] = Field(default_factory=list)
    metadata: dict[str, Any] = Field(default_factory=dict)


class GraphCommunity(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: str
    title: str
    summary: str = ""
    entity_ids: Annotated[list[str], Field(alias="entityIds")] = Field(default_factory=list)
    relation_ids: Annotated[list[str], Field(alias="relationIds")] = Field(default_factory=list)
    evidence_ids: Annotated[list[str], Field(alias="evidenceIds")] = Field(default_factory=list)
    metadata: dict[str, Any] = Field(default_factory=dict)


class GraphExtractResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    entities: list[GraphEntity] = Field(default_factory=list)
    relations: list[GraphRelation] = Field(default_factory=list)
    evidences: list[GraphEvidence] = Field(default_factory=list)
    communities: list[GraphCommunity] = Field(default_factory=list)
