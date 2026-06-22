import re
from collections import Counter, defaultdict

from rag_tools.schemas.raptor_build import RaptorBuildRequest, RaptorBuildResponse, RaptorChunk, RaptorNode

MAX_SUMMARY_CHARS = 900
MAX_WEIGHTED_CHARS = 1200
MAX_KEYWORDS = 12
MAX_QUESTIONS = 6

CHINESE_STOP_TERMS = {
    "这个", "那个", "以及", "如果", "进行", "可以", "需要", "相关", "当前", "主要", "包括", "通过",
    "使用", "根据", "支持", "实现", "文档", "内容", "问题", "答案", "用户", "系统",
}


def build_raptor(request: RaptorBuildRequest) -> RaptorBuildResponse:
    chunks = [chunk for chunk in request.chunks if chunk and chunk.chunk_id and _text_of(chunk)]
    if not chunks:
        return RaptorBuildResponse(nodes=[])

    max_cluster_size = max(2, min(request.max_cluster_size or 6, 12))
    max_levels = max(1, min(request.max_levels or 3, 5))

    nodes: list[RaptorNode] = []
    current_items: list[_TreeItem] = [_TreeItem.from_chunk(chunk) for chunk in chunks]
    level = 1

    while current_items and level <= max_levels:
        clusters = _cluster_items(current_items, max_cluster_size)
        level_nodes: list[RaptorNode] = []
        for index, cluster in enumerate(clusters, start=1):
            node = _build_node(cluster, level, index)
            level_nodes.append(node)
            nodes.append(node)

        if len(level_nodes) <= 1:
            break

        current_items = [_TreeItem.from_node(node) for node in level_nodes]
        level += 1

    parent_by_child: dict[str, str] = {}
    level_map = defaultdict(list)
    for node in nodes:
        level_map[node.level].append(node)
    for parent_level in sorted(level_map.keys()):
        child_level = parent_level - 1
        if child_level < 1:
            continue
        for parent in level_map[parent_level]:
            for child_id in parent.child_node_ids:
                parent_by_child[child_id] = parent.id
    for node in nodes:
        node.parent_id = parent_by_child.get(node.id, "")

    return RaptorBuildResponse(nodes=nodes)


class _TreeItem:
    def __init__(
        self,
        item_id: str,
        title: str,
        text: str,
        section_path: str,
        page_range: str,
        chunk_ids: list[int],
        parent_block_ids: list[int],
        child_node_ids: list[str],
        keywords: list[str],
        questions: list[str],
    ):
        self.item_id = item_id
        self.title = title
        self.text = text
        self.section_path = section_path
        self.page_range = page_range
        self.chunk_ids = chunk_ids
        self.parent_block_ids = parent_block_ids
        self.child_node_ids = child_node_ids
        self.keywords = keywords
        self.questions = questions

    @classmethod
    def from_chunk(cls, chunk: RaptorChunk) -> "_TreeItem":
        title = _first_non_blank(chunk.title, _last_section(chunk.section_path), f"chunk-{chunk.chunk_no or chunk.chunk_id}")
        text = _text_of(chunk)
        return cls(
            item_id=f"chunk-{chunk.chunk_id}",
            title=title,
            text=text,
            section_path=chunk.section_path or "",
            page_range=chunk.page_range or (str(chunk.page_no) if chunk.page_no is not None else ""),
            chunk_ids=[chunk.chunk_id],
            parent_block_ids=[chunk.parent_block_id] if chunk.parent_block_id else [],
            child_node_ids=[],
            keywords=_keywords_from_chunk(chunk, text),
            questions=_questions_from_chunk(chunk),
        )

    @classmethod
    def from_node(cls, node: RaptorNode) -> "_TreeItem":
        return cls(
            item_id=node.id,
            title=node.title,
            text=node.summary_with_weight or node.summary,
            section_path=node.section_path,
            page_range=node.page_range,
            chunk_ids=list(node.source_chunk_ids),
            parent_block_ids=list(node.source_parent_block_ids),
            child_node_ids=[node.id],
            keywords=list(node.keywords),
            questions=list(node.questions),
        )


def _cluster_items(items: list[_TreeItem], max_cluster_size: int) -> list[list[_TreeItem]]:
    grouped: dict[str, list[_TreeItem]] = defaultdict(list)
    for item in items:
        grouped[_cluster_key(item)].append(item)

    clusters: list[list[_TreeItem]] = []
    for group_items in grouped.values():
        group_items.sort(key=lambda item: _first_chunk_id(item))
        for start in range(0, len(group_items), max_cluster_size):
            clusters.append(group_items[start:start + max_cluster_size])
    return clusters


def _build_node(cluster: list[_TreeItem], level: int, node_no: int) -> RaptorNode:
    source_chunk_ids = _dedupe_ints(chunk_id for item in cluster for chunk_id in item.chunk_ids)
    source_parent_block_ids = _dedupe_ints(parent_id for item in cluster for parent_id in item.parent_block_ids)
    child_node_ids = _dedupe_strings(child_id for item in cluster for child_id in item.child_node_ids)
    keywords = _merge_keywords(cluster)
    questions = _merge_questions(cluster, keywords)
    section_path = _common_section_path([item.section_path for item in cluster])
    page_range = _merge_page_ranges([item.page_range for item in cluster])
    title = _node_title(cluster, section_path, keywords, level, node_no)
    summary = _summarize(cluster, title, keywords)
    summary_with_weight = _limit(
        "\n".join(
            part for part in [
                f"标题：{title}",
                f"章节：{section_path}" if section_path else "",
                f"关键词：{'、'.join(keywords)}" if keywords else "",
                f"摘要：{summary}",
                f"典型问题：{'；'.join(questions)}" if questions else "",
            ]
            if part
        ),
        MAX_WEIGHTED_CHARS,
    )

    return RaptorNode(
        id=f"raptor-l{level}-{node_no}",
        level=level,
        nodeNo=node_no,
        title=title,
        summary=summary,
        summaryWithWeight=summary_with_weight,
        childNodeIds=child_node_ids,
        sourceChunkIds=source_chunk_ids,
        sourceParentBlockIds=source_parent_block_ids,
        sectionPath=section_path,
        pageRange=page_range,
        keywords=keywords,
        questions=questions,
        metadata={
            "itemCount": len(cluster),
            "summaryStrategy": "extractive_rule_v1",
            "firstChunkId": source_chunk_ids[0] if source_chunk_ids else None,
            "lastChunkId": source_chunk_ids[-1] if source_chunk_ids else None,
        },
    )


def _text_of(chunk: RaptorChunk) -> str:
    return (chunk.content_with_weight or chunk.text or "").strip()


def _cluster_key(item: _TreeItem) -> str:
    if item.section_path:
        parts = [part.strip() for part in re.split(r"[/>\n]+", item.section_path) if part.strip()]
        if parts:
            return parts[0]
    if item.keywords:
        return item.keywords[0]
    return "default"


def _first_chunk_id(item: _TreeItem) -> int:
    return item.chunk_ids[0] if item.chunk_ids else 0


def _node_title(cluster: list[_TreeItem], section_path: str, keywords: list[str], level: int, node_no: int) -> str:
    if section_path:
        return _last_section(section_path)
    titles = [item.title for item in cluster if item.title]
    if titles:
        counter = Counter(titles)
        return counter.most_common(1)[0][0]
    if keywords:
        return "、".join(keywords[:3])
    return f"层级摘要 L{level}-{node_no}"


def _summarize(cluster: list[_TreeItem], title: str, keywords: list[str]) -> str:
    candidate_sentences: list[str] = []
    for item in cluster:
        for sentence in _split_sentences(item.text):
            normalized = sentence.strip()
            if 18 <= len(normalized) <= 260:
                candidate_sentences.append(normalized)
    if not candidate_sentences:
        candidate_sentences = [_limit(item.text, 220) for item in cluster if item.text]

    scored: list[tuple[float, int, str]] = []
    keyword_set = set(keywords)
    for index, sentence in enumerate(candidate_sentences):
        score = sum(1 for keyword in keyword_set if keyword and keyword in sentence)
        score += min(len(sentence), 180) / 180.0
        scored.append((score, index, sentence))
    scored.sort(key=lambda item: (-item[0], item[1]))

    selected = [sentence for _, _, sentence in scored[:4]]
    summary = "；".join(selected)
    if not summary:
        summary = f"{title} 相关内容覆盖 {len(cluster)} 个片段。"
    return _limit(summary, MAX_SUMMARY_CHARS)


def _split_sentences(text: str) -> list[str]:
    return [part.strip() for part in re.split(r"[。！？!?；;\n]+", text or "") if part.strip()]


def _keywords_from_chunk(chunk: RaptorChunk, text: str) -> list[str]:
    metadata_keywords = _parse_metadata_words(chunk.metadata.get("keywords") if chunk.metadata else None)
    inline_keywords = _extract_terms((chunk.title or "") + " " + (chunk.section_path or "") + " " + text)
    return _dedupe_strings([*metadata_keywords, *inline_keywords])[:MAX_KEYWORDS]


def _questions_from_chunk(chunk: RaptorChunk) -> list[str]:
    return _parse_metadata_words(chunk.metadata.get("questions") if chunk.metadata else None)[:MAX_QUESTIONS]


def _merge_keywords(cluster: list[_TreeItem]) -> list[str]:
    counter: Counter[str] = Counter()
    for item in cluster:
        for keyword in item.keywords:
            if keyword:
                counter[keyword] += 1
    return [keyword for keyword, _ in counter.most_common(MAX_KEYWORDS)]


def _merge_questions(cluster: list[_TreeItem], keywords: list[str]) -> list[str]:
    questions = _dedupe_strings(question for item in cluster for question in item.questions)
    if questions:
        return questions[:MAX_QUESTIONS]
    generated = []
    for keyword in keywords[:3]:
        generated.append(f"{keyword} 的核心内容是什么？")
    return generated[:MAX_QUESTIONS]


def _extract_terms(text: str) -> list[str]:
    normalized = (text or "").lower()
    terms = re.findall(r"[a-z][a-z0-9._/-]{2,40}", normalized)
    chinese_terms = re.findall(r"[\u4e00-\u9fff]{2,12}", normalized)
    for term in chinese_terms:
        if term in CHINESE_STOP_TERMS:
            continue
        if len(term) <= 6:
            terms.append(term)
            continue
        terms.extend(term[index:index + 4] for index in range(0, len(term) - 1, 2))
    return _dedupe_strings(terms)


def _parse_metadata_words(value: object) -> list[str]:
    if value is None:
        return []
    if isinstance(value, list):
        return _dedupe_strings(str(item).strip() for item in value if str(item).strip())
    text = str(value).strip()
    if not text:
        return []
    text = text.strip("[]")
    return _dedupe_strings(part.strip().strip('"') for part in re.split(r"[,，、;；\n]+", text) if part.strip())


def _common_section_path(paths: list[str]) -> str:
    normalized_paths = [[part.strip() for part in re.split(r"[/>\n]+", path or "") if part.strip()] for path in paths if path]
    if not normalized_paths:
        return ""
    common: list[str] = []
    for index in range(min(len(path) for path in normalized_paths)):
        value = normalized_paths[0][index]
        if all(len(path) > index and path[index] == value for path in normalized_paths):
            common.append(value)
        else:
            break
    return " > ".join(common)


def _merge_page_ranges(page_ranges: list[str]) -> str:
    pages: list[int] = []
    for page_range in page_ranges:
        for number in re.findall(r"\d+", page_range or ""):
            pages.append(int(number))
    if not pages:
        return ""
    return str(min(pages)) if min(pages) == max(pages) else f"{min(pages)}-{max(pages)}"


def _last_section(section_path: str) -> str:
    parts = [part.strip() for part in re.split(r"[/>\n]+", section_path or "") if part.strip()]
    return parts[-1] if parts else ""


def _first_non_blank(*values: str) -> str:
    for value in values:
        if value:
            return value
    return ""


def _dedupe_ints(values) -> list[int]:
    result: list[int] = []
    seen: set[int] = set()
    for value in values:
        if value is None or value in seen:
            continue
        seen.add(value)
        result.append(value)
    return result


def _dedupe_strings(values) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        text = str(value).strip()
        if not text or text in seen:
            continue
        seen.add(text)
        result.append(text)
    return result


def _limit(value: str, max_chars: int) -> str:
    text = (value or "").strip()
    return text if len(text) <= max_chars else text[:max_chars]
