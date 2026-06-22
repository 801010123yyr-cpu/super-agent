from typing import Annotated, Any

from pydantic import BaseModel, ConfigDict, Field


class RaptorChunk(BaseModel):
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


class RaptorBuildRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    document_id: Annotated[int | None, Field(alias="documentId")] = None
    task_id: Annotated[int | None, Field(alias="taskId")] = None
    max_cluster_size: Annotated[int, Field(alias="maxClusterSize")] = 6
    max_levels: Annotated[int, Field(alias="maxLevels")] = 3
    chunks: list[RaptorChunk] = Field(default_factory=list)


class RaptorNode(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: str
    parent_id: Annotated[str, Field(alias="parentId")] = ""
    level: int
    node_no: Annotated[int, Field(alias="nodeNo")]
    title: str = ""
    summary: str = ""
    summary_with_weight: Annotated[str, Field(alias="summaryWithWeight")] = ""
    child_node_ids: Annotated[list[str], Field(alias="childNodeIds")] = Field(default_factory=list)
    source_chunk_ids: Annotated[list[int], Field(alias="sourceChunkIds")] = Field(default_factory=list)
    source_parent_block_ids: Annotated[list[int], Field(alias="sourceParentBlockIds")] = Field(default_factory=list)
    section_path: Annotated[str, Field(alias="sectionPath")] = ""
    page_range: Annotated[str, Field(alias="pageRange")] = ""
    keywords: list[str] = Field(default_factory=list)
    questions: list[str] = Field(default_factory=list)
    metadata: dict[str, Any] = Field(default_factory=dict)


class RaptorBuildResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    nodes: list[RaptorNode] = Field(default_factory=list)
