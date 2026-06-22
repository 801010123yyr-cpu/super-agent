import base64
import hashlib
import html
import json
import re
from html.parser import HTMLParser

from fastapi import HTTPException

from rag_tools.schemas.document_parse import (
    DocumentBlock,
    DocumentParseRequest,
    DocumentParseResponse,
    ParseArtifact,
    StructureNode,
    json_metadata,
)

PARSER_NAME = "rag-tools-python-parser"
PARSER_VERSION = "0.1.0"

NODE_TYPE_DOCUMENT = 1
NODE_TYPE_SECTION = 2


def parse_document(request: DocumentParseRequest) -> DocumentParseResponse:
    content = _decode_content(request.content_base64)
    file_type = _resolve_file_type(request.file_name, request.file_type, request.mime_type)

    if file_type in {"TXT", "MD"}:
        blocks = _parse_plain_text(content, markdown=file_type == "MD")
    elif file_type == "HTML":
        blocks = _parse_html(content)
    elif file_type == "PDF":
        blocks = _parse_pdf(content)
    elif file_type == "DOCX":
        blocks = _parse_docx(content)
    elif file_type == "DOC":
        raise HTTPException(status_code=422, detail="DOC 解析未启用，请先转为 DOCX 后上传。")
    else:
        raise HTTPException(status_code=422, detail=f"不支持的文件类型: {file_type or 'UNKNOWN'}")

    normalized_blocks = _normalize_blocks(blocks)
    parsed_text = _render_parsed_text(normalized_blocks)
    structure_nodes = _build_structure_nodes(request.file_name, normalized_blocks, parsed_text)
    paragraph_list = _paragraphs(parsed_text)
    heading_count = sum(1 for block in normalized_blocks if block.block_type == "TITLE")

    artifacts = _build_artifacts(request.file_name, parsed_text, normalized_blocks)

    return DocumentParseResponse(
        parsedText=parsed_text,
        charCount=len(parsed_text),
        tokenCount=_estimate_token_count(parsed_text),
        structureLevel=_structure_level(heading_count, len(paragraph_list)),
        contentQualityLevel=_content_quality_level(parsed_text),
        headingCount=heading_count,
        paragraphCount=len(paragraph_list),
        maxParagraphLength=max((len(item) for item in paragraph_list), default=0),
        artifacts=artifacts,
        blocks=normalized_blocks,
        structureNodes=structure_nodes,
    )


def _decode_content(content_base64: str) -> bytes:
    if not content_base64:
        raise HTTPException(status_code=422, detail="contentBase64 不能为空。")
    try:
        return base64.b64decode(content_base64, validate=True)
    except Exception as exception:
        raise HTTPException(status_code=422, detail="contentBase64 不是合法 Base64。") from exception


def _resolve_file_type(file_name: str, file_type: str, mime_type: str) -> str:
    explicit = (file_type or "").strip().upper()
    if explicit:
        return explicit
    suffix = (file_name or "").rsplit(".", 1)[-1].lower() if "." in (file_name or "") else ""
    if suffix == "pdf":
        return "PDF"
    if suffix == "docx":
        return "DOCX"
    if suffix == "doc":
        return "DOC"
    if suffix in {"md", "markdown"}:
        return "MD"
    if suffix in {"html", "htm"}:
        return "HTML"
    if suffix == "txt" or (mime_type or "").startswith("text/"):
        return "TXT"
    return explicit


def _parse_plain_text(content: bytes, markdown: bool) -> list[DocumentBlock]:
    text = _decode_text(content)
    blocks = []
    block_no = 1
    for paragraph in _paragraphs(text):
        block_type = "TITLE" if markdown and paragraph.lstrip().startswith("#") else _classify_text_block(paragraph)
        clean_text = re.sub(r"^#{1,6}\s+", "", paragraph).strip()
        blocks.append(_block(block_no, block_type, clean_text))
        block_no += 1
    return blocks


def _parse_html(content: bytes) -> list[DocumentBlock]:
    parser = _HtmlBlockParser()
    parser.feed(_decode_text(content))
    return [
        _block(index + 1, block_type, text)
        for index, (block_type, text) in enumerate(parser.blocks)
        if text.strip()
    ]


def _parse_pdf(content: bytes) -> list[DocumentBlock]:
    try:
        import fitz
    except ImportError as exception:
        raise HTTPException(status_code=500, detail="Python parser 缺少 pymupdf 依赖，请执行 pip install -r requirements.txt。") from exception

    blocks = []
    with fitz.open(stream=content, filetype="pdf") as document:
        block_no = 1
        for page_index, page in enumerate(document, start=1):
            for raw_block in page.get_text("blocks"):
                if len(raw_block) < 5:
                    continue
                text = _cleanup_text(str(raw_block[4]))
                if not text:
                    continue
                bbox_json = json_metadata({
                    "x0": round(float(raw_block[0]), 2),
                    "y0": round(float(raw_block[1]), 2),
                    "x1": round(float(raw_block[2]), 2),
                    "y1": round(float(raw_block[3]), 2),
                })
                blocks.append(_block(block_no, _classify_text_block(text), text, page_no=page_index, bbox_json=bbox_json))
                block_no += 1
    return blocks


def _parse_docx(content: bytes) -> list[DocumentBlock]:
    try:
        from docx import Document
    except ImportError as exception:
        raise HTTPException(status_code=500, detail="Python parser 缺少 python-docx 依赖，请执行 pip install -r requirements.txt。") from exception

    from io import BytesIO

    document = Document(BytesIO(content))
    blocks = []
    block_no = 1
    for paragraph in document.paragraphs:
        text = _cleanup_text(paragraph.text)
        if not text:
            continue
        style_name = (paragraph.style.name if paragraph.style is not None else "") or ""
        block_type = "TITLE" if "heading" in style_name.lower() else _classify_text_block(text)
        blocks.append(_block(block_no, block_type, text))
        block_no += 1

    for table in document.tables:
        rows = []
        for row in table.rows:
            rows.append([_cleanup_text(cell.text) for cell in row.cells])
        if not rows:
            continue
        table_html = _table_html(rows)
        text = "\n".join(" | ".join(cell for cell in row if cell) for row in rows)
        blocks.append(_block(block_no, "TABLE", text, table_html=table_html))
        block_no += 1
    return blocks


def _normalize_blocks(blocks: list[DocumentBlock]) -> list[DocumentBlock]:
    normalized = []
    current_section = ""
    current_section_path = ""
    for index, block in enumerate(blocks, start=1):
        text = _cleanup_text(block.text)
        if not text and not block.table_html and not block.image_caption:
            continue
        block.block_no = len(normalized) + 1
        block.text = text
        if block.block_type == "TITLE":
            current_section = text
            current_section_path = text
        block.section_path = block.section_path or current_section_path
        block.canonical_path = block.canonical_path or _canonical_path(block.section_path, block.block_no)
        block.content_with_weight = block.content_with_weight or _content_with_weight(block, current_section)
        normalized.append(block)
    return normalized


def _build_structure_nodes(file_name: str, blocks: list[DocumentBlock], parsed_text: str) -> list[StructureNode]:
    nodes = [
        StructureNode(
            nodeNo=1,
            nodeType=NODE_TYPE_DOCUMENT,
            parentNodeNo=None,
            depth=0,
            nodeCode="ROOT",
            title=file_name or "document",
            anchorText=file_name or "document",
            canonicalPath="/",
            sectionPath="",
            contentText=parsed_text[:10000],
        )
    ]
    previous_heading_node_no = None
    for block in blocks:
        if block.block_type != "TITLE":
            continue
        node_no = len(nodes) + 1
        nodes.append(
            StructureNode(
                nodeNo=node_no,
                nodeType=NODE_TYPE_SECTION,
                parentNodeNo=1,
                prevSiblingNodeNo=previous_heading_node_no,
                depth=1,
                nodeCode=str(node_no - 1),
                title=block.text,
                anchorText=block.text[:200],
                canonicalPath=_canonical_path(block.text, node_no),
                sectionPath=block.text,
                contentText=block.text,
            )
        )
        if previous_heading_node_no is not None:
            nodes[-2].next_sibling_node_no = node_no
        previous_heading_node_no = node_no
    return nodes


def _build_artifacts(file_name: str, parsed_text: str, blocks: list[DocumentBlock]) -> list[ParseArtifact]:
    base_name = (file_name or "document").rsplit(".", 1)[0]
    markdown_bytes = parsed_text.encode("utf-8")
    blocks_json_bytes = json.dumps(
        [block.model_dump(by_alias=True) for block in blocks],
        ensure_ascii=False,
        indent=2,
    ).encode("utf-8")
    return [
        _artifact("MARKDOWN", f"{base_name}.md", "text/markdown;charset=UTF-8", markdown_bytes),
        _artifact("JSON", f"{base_name}.blocks.json", "application/json;charset=UTF-8", blocks_json_bytes),
    ]


def _artifact(artifact_type: str, file_name: str, content_type: str, content: bytes) -> ParseArtifact:
    return ParseArtifact(
        artifactType=artifact_type,
        fileName=file_name,
        contentType=content_type,
        contentBase64=base64.b64encode(content).decode("ascii"),
        contentHash=hashlib.sha256(content).hexdigest(),
        parserName=PARSER_NAME,
        parserVersion=PARSER_VERSION,
    )


def _block(
    block_no: int,
    block_type: str,
    text: str,
    *,
    page_no: int | None = None,
    bbox_json: str = "",
    table_html: str = "",
) -> DocumentBlock:
    return DocumentBlock(
        blockNo=block_no,
        blockType=block_type,
        pageNo=page_no,
        pageRange=str(page_no) if page_no is not None else "",
        bboxJson=bbox_json,
        text=text,
        tableHtml=table_html,
        metadataJson=json_metadata({"parser": PARSER_NAME}),
    )


def _render_parsed_text(blocks: list[DocumentBlock]) -> str:
    parts = []
    for block in blocks:
        if block.block_type == "TITLE":
            parts.append(f"# {block.text}")
        elif block.block_type == "TABLE" and block.table_html:
            parts.append(block.text)
        else:
            parts.append(block.text)
    return _cleanup_text("\n\n".join(part for part in parts if part))


def _content_with_weight(block: DocumentBlock, current_section: str) -> str:
    parts = []
    if current_section:
        parts.append(f"section: {current_section}")
    if block.block_type:
        parts.append(f"type: {block.block_type}")
    if block.text:
        parts.append(block.text)
    return "\n".join(parts)


def _canonical_path(section_path: str, block_no: int) -> str:
    section = re.sub(r"[^0-9a-zA-Z\u4e00-\u9fff]+", "-", section_path or "root").strip("-")
    return f"/{section or 'root'}/{block_no}"


def _decode_text(content: bytes) -> str:
    for encoding in ("utf-8", "utf-8-sig", "gb18030"):
        try:
            return content.decode(encoding)
        except UnicodeDecodeError:
            continue
    return content.decode("utf-8", errors="replace")


def _cleanup_text(text: str) -> str:
    if not text:
        return ""
    return (
        text.replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace("\x00", " ")
        .replace("\t", " ")
        .strip()
    )


def _paragraphs(text: str) -> list[str]:
    cleaned = _cleanup_text(text)
    if not cleaned:
        return []
    return [item.strip() for item in re.split(r"\n\s*\n+", cleaned) if item.strip()]


def _classify_text_block(text: str) -> str:
    stripped = text.strip()
    if not stripped:
        return "TEXT"
    if len(stripped) <= 80 and (
        re.match(r"^([一二三四五六七八九十]+、|第[一二三四五六七八九十0-9]+[章节条]|[0-9]+(\.[0-9]+)*[、.])", stripped)
        or stripped.endswith(("章", "节", "：", ":"))
    ):
        return "TITLE"
    return "TEXT"


def _estimate_token_count(text: str) -> int:
    if not text:
        return 0
    chinese_count = len(re.findall(r"[\u4e00-\u9fff]", text))
    english_count = len(re.findall(r"[A-Za-z]+", text))
    return chinese_count + english_count + max(1, (len(text) - chinese_count) // 4)


def _structure_level(heading_count: int, paragraph_count: int) -> int:
    if heading_count >= 5:
        return 3
    if heading_count >= 2:
        return 2
    if paragraph_count >= 3:
        return 1
    return 0


def _content_quality_level(text: str) -> int:
    if not text or len(text) < 20:
        return 1
    broken_ratio = text.count("�") / max(len(text), 1)
    if broken_ratio > 0.02 or len(text) < 100:
        return 1
    if broken_ratio > 0.005 or len(text) < 500:
        return 2
    return 3


def _table_html(rows: list[list[str]]) -> str:
    body = []
    for row in rows:
        cells = "".join(f"<td>{html.escape(cell)}</td>" for cell in row)
        body.append(f"<tr>{cells}</tr>")
    return "<table><tbody>" + "".join(body) + "</tbody></table>"


class _HtmlBlockParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.blocks: list[tuple[str, str]] = []
        self._current_tag = ""
        self._buffer: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag in {"h1", "h2", "h3", "h4", "h5", "h6", "p", "li"}:
            self._flush()
            self._current_tag = tag

    def handle_endtag(self, tag: str) -> None:
        if tag == self._current_tag:
            self._flush()

    def handle_data(self, data: str) -> None:
        if self._current_tag:
            self._buffer.append(data)

    def _flush(self) -> None:
        text = _cleanup_text("".join(self._buffer))
        if text:
            block_type = "TITLE" if self._current_tag.startswith("h") else _classify_text_block(text)
            self.blocks.append((block_type, text))
        self._buffer = []
        self._current_tag = ""
