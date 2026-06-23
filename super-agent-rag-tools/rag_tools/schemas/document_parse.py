from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class DocumentParseRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    file_name: str = Field(default="", alias="fileName")
    mime_type: str = Field(default="", alias="mimeType")
    file_type: str = Field(default="", alias="fileType")
    content_base64: str = Field(default="", alias="contentBase64")


class ParseArtifact(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    artifact_type: str = Field(alias="artifactType")
    file_name: str = Field(alias="fileName")
    content_type: str = Field(default="application/octet-stream", alias="contentType")
    content_base64: str = Field(alias="contentBase64")
    content_hash: str = Field(default="", alias="contentHash")
    parser_name: str = Field(default="", alias="parserName")
    parser_version: str = Field(default="", alias="parserVersion")


class DocumentBlock(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    block_no: int = Field(alias="blockNo")
    block_type: str = Field(alias="blockType")
    parent_block_no: int | None = Field(default=None, alias="parentBlockNo")
    section_path: str = Field(default="", alias="sectionPath")
    canonical_path: str = Field(default="", alias="canonicalPath")
    page_no: int | None = Field(default=None, alias="pageNo")
    page_range: str = Field(default="", alias="pageRange")
    bbox_json: str = Field(default="", alias="bboxJson")
    text: str = ""
    content_with_weight: str = Field(default="", alias="contentWithWeight")
    table_html: str = Field(default="", alias="tableHtml")
    table_rows: list[list[str]] = Field(default_factory=list, alias="tableRows")
    image_file_name: str = Field(default="", alias="imageFileName")
    image_content_base64: str = Field(default="", alias="imageContentBase64")
    image_caption: str = Field(default="", alias="imageCaption")
    metadata_json: str = Field(default="", alias="metadataJson")


class StructureNode(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    node_no: int = Field(alias="nodeNo")
    node_type: int = Field(alias="nodeType")
    parent_node_no: int | None = Field(default=None, alias="parentNodeNo")
    prev_sibling_node_no: int | None = Field(default=None, alias="prevSiblingNodeNo")
    next_sibling_node_no: int | None = Field(default=None, alias="nextSiblingNodeNo")
    depth: int = 0
    node_code: str = Field(default="", alias="nodeCode")
    title: str = ""
    anchor_text: str = Field(default="", alias="anchorText")
    canonical_path: str = Field(default="", alias="canonicalPath")
    section_path: str = Field(default="", alias="sectionPath")
    content_text: str = Field(default="", alias="contentText")
    item_index: int | None = Field(default=None, alias="itemIndex")


class DocumentParseResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    parsed_text: str = Field(default="", alias="parsedText")
    char_count: int = Field(default=0, alias="charCount")
    token_count: int = Field(default=0, alias="tokenCount")
    structure_level: int = Field(default=0, alias="structureLevel")
    content_quality_level: int = Field(default=0, alias="contentQualityLevel")
    heading_count: int = Field(default=0, alias="headingCount")
    paragraph_count: int = Field(default=0, alias="paragraphCount")
    max_paragraph_length: int = Field(default=0, alias="maxParagraphLength")
    artifacts: list[ParseArtifact] = Field(default_factory=list)
    blocks: list[DocumentBlock] = Field(default_factory=list)
    structure_nodes: list[StructureNode] = Field(default_factory=list, alias="structureNodes")


def json_metadata(data: dict[str, Any]) -> str:
    import json

    return json.dumps(data, ensure_ascii=False, separators=(",", ":"))
