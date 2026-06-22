from typing import Annotated, Any

from pydantic import BaseModel, ConfigDict, Field


class DocumentParseRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    file_name: Annotated[str, Field(alias="fileName")] = ""
    mime_type: Annotated[str, Field(alias="mimeType")] = ""
    file_type: Annotated[str, Field(alias="fileType")] = ""
    content_base64: Annotated[str, Field(alias="contentBase64")] = ""


class ParseArtifact(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    artifact_type: Annotated[str, Field(alias="artifactType")]
    file_name: Annotated[str, Field(alias="fileName")]
    content_type: Annotated[str, Field(alias="contentType")] = "application/octet-stream"
    content_base64: Annotated[str, Field(alias="contentBase64")]
    content_hash: Annotated[str, Field(alias="contentHash")] = ""
    parser_name: Annotated[str, Field(alias="parserName")] = ""
    parser_version: Annotated[str, Field(alias="parserVersion")] = ""


class DocumentBlock(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    block_no: Annotated[int, Field(alias="blockNo")]
    block_type: Annotated[str, Field(alias="blockType")]
    parent_block_no: Annotated[int | None, Field(alias="parentBlockNo")] = None
    section_path: Annotated[str, Field(alias="sectionPath")] = ""
    canonical_path: Annotated[str, Field(alias="canonicalPath")] = ""
    page_no: Annotated[int | None, Field(alias="pageNo")] = None
    page_range: Annotated[str, Field(alias="pageRange")] = ""
    bbox_json: Annotated[str, Field(alias="bboxJson")] = ""
    text: str = ""
    content_with_weight: Annotated[str, Field(alias="contentWithWeight")] = ""
    table_html: Annotated[str, Field(alias="tableHtml")] = ""
    table_rows: Annotated[list[list[str]], Field(alias="tableRows")] = Field(default_factory=list)
    image_file_name: Annotated[str, Field(alias="imageFileName")] = ""
    image_content_base64: Annotated[str, Field(alias="imageContentBase64")] = ""
    image_caption: Annotated[str, Field(alias="imageCaption")] = ""
    metadata_json: Annotated[str, Field(alias="metadataJson")] = ""


class StructureNode(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    node_no: Annotated[int, Field(alias="nodeNo")]
    node_type: Annotated[int, Field(alias="nodeType")]
    parent_node_no: Annotated[int | None, Field(alias="parentNodeNo")] = None
    prev_sibling_node_no: Annotated[int | None, Field(alias="prevSiblingNodeNo")] = None
    next_sibling_node_no: Annotated[int | None, Field(alias="nextSiblingNodeNo")] = None
    depth: int = 0
    node_code: Annotated[str, Field(alias="nodeCode")] = ""
    title: str = ""
    anchor_text: Annotated[str, Field(alias="anchorText")] = ""
    canonical_path: Annotated[str, Field(alias="canonicalPath")] = ""
    section_path: Annotated[str, Field(alias="sectionPath")] = ""
    content_text: Annotated[str, Field(alias="contentText")] = ""
    item_index: Annotated[int | None, Field(alias="itemIndex")] = None


class DocumentParseResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    parsed_text: Annotated[str, Field(alias="parsedText")] = ""
    char_count: Annotated[int, Field(alias="charCount")] = 0
    token_count: Annotated[int, Field(alias="tokenCount")] = 0
    structure_level: Annotated[int, Field(alias="structureLevel")] = 0
    content_quality_level: Annotated[int, Field(alias="contentQualityLevel")] = 0
    heading_count: Annotated[int, Field(alias="headingCount")] = 0
    paragraph_count: Annotated[int, Field(alias="paragraphCount")] = 0
    max_paragraph_length: Annotated[int, Field(alias="maxParagraphLength")] = 0
    artifacts: list[ParseArtifact] = Field(default_factory=list)
    blocks: list[DocumentBlock] = Field(default_factory=list)
    structure_nodes: Annotated[list[StructureNode], Field(alias="structureNodes")] = Field(default_factory=list)


def json_metadata(data: dict[str, Any]) -> str:
    import json

    return json.dumps(data, ensure_ascii=False, separators=(",", ":"))
