import hashlib
import re
from collections import defaultdict
from itertools import combinations

from rag_tools.schemas.graph_extract import (
    GraphCommunity,
    GraphEntity,
    GraphEvidence,
    GraphExtractRequest,
    GraphExtractResponse,
    GraphRelation,
)

MAX_ENTITIES_PER_CHUNK = 12
MAX_RELATIONS_PER_CHUNK = 8
MAX_QUOTE_LENGTH = 220

CHINESE_STOPWORDS = {
    "可以",
    "需要",
    "进行",
    "通过",
    "根据",
    "如果",
    "以及",
    "或者",
    "并且",
    "然后",
    "其中",
    "这个",
    "这些",
    "一种",
    "相关",
    "当前",
    "用户",
    "系统",
    "文档",
}

ENGLISH_STOPWORDS = {
    "and",
    "are",
    "can",
    "for",
    "from",
    "into",
    "that",
    "the",
    "this",
    "with",
    "your",
}

RELATION_WORDS = [
    ("依赖", "DEPENDS_ON"),
    ("调用", "CALLS"),
    ("使用", "USES"),
    ("采用", "USES"),
    ("支持", "SUPPORTS"),
    ("包含", "CONTAINS"),
    ("包括", "CONTAINS"),
    ("组成", "PART_OF"),
    ("属于", "BELONGS_TO"),
    ("负责", "RESPONSIBLE_FOR"),
    ("关联", "RELATED_TO"),
    ("映射", "MAPS_TO"),
    ("生成", "PRODUCES"),
    ("输出", "PRODUCES"),
    ("输入", "CONSUMES"),
    ("是", "IS_A"),
]


def extract_graph(request: GraphExtractRequest) -> GraphExtractResponse:
    entity_map: dict[str, GraphEntity] = {}
    relation_map: dict[str, GraphRelation] = {}
    evidences: list[GraphEvidence] = []
    evidence_no = 1
    chunk_entity_ids: dict[int, list[str]] = {}
    chunk_relation_ids: dict[int, list[str]] = {}

    for chunk in request.chunks:
        if chunk.chunk_id is None:
            continue
        text = _chunk_text(chunk)
        if not text:
            continue

        names = _candidate_names(chunk, text)
        entity_ids: list[str] = []
        for name in names[:MAX_ENTITIES_PER_CHUNK]:
            entity_type = _classify_entity(name, chunk)
            entity_id = _entity_id(name, entity_type)
            entity = entity_map.get(entity_id)
            if entity is None:
                entity = GraphEntity(
                    id=entity_id,
                    name=name,
                    normalizedName=_normalize_entity(name),
                    type=entity_type,
                    description=_entity_description(name, entity_type, chunk.section_path),
                    metadata={
                        "firstChunkNo": chunk.chunk_no,
                        "firstSectionPath": chunk.section_path,
                    },
                )
                entity_map[entity_id] = entity
            if chunk.chunk_id not in entity.source_chunk_ids:
                entity.source_chunk_ids.append(chunk.chunk_id)

            evidence = _entity_evidence(f"EV{evidence_no}", entity_id, chunk, text, name)
            evidence_no += 1
            evidences.append(evidence)
            entity.evidence_ids.append(evidence.id)
            entity_ids.append(entity_id)

        chunk_entity_ids[chunk.chunk_id] = _unique(entity_ids)
        relation_count = 0
        for source_id, target_id in combinations(chunk_entity_ids[chunk.chunk_id], 2):
            if relation_count >= MAX_RELATIONS_PER_CHUNK:
                break
            source = entity_map[source_id]
            target = entity_map[target_id]
            relation_type = _relation_type(text, source.name, target.name)
            if relation_type == "" and relation_count >= 4:
                continue
            if relation_type == "":
                relation_type = "ASSOCIATED_WITH"

            relation_id = _relation_id(source_id, target_id, relation_type)
            relation = relation_map.get(relation_id)
            if relation is None:
                relation = GraphRelation(
                    id=relation_id,
                    sourceEntityId=source_id,
                    targetEntityId=target_id,
                    relationType=relation_type,
                    description=f"{source.name} {relation_type} {target.name}",
                    weight=_relation_weight(relation_type),
                    metadata={
                        "firstChunkNo": chunk.chunk_no,
                        "firstSectionPath": chunk.section_path,
                    },
                )
                relation_map[relation_id] = relation

            evidence = _relation_evidence(f"EV{evidence_no}", relation_id, chunk, text, source.name, target.name)
            evidence_no += 1
            evidences.append(evidence)
            relation.evidence_ids.append(evidence.id)
            chunk_relation_ids.setdefault(chunk.chunk_id, []).append(relation_id)
            relation_count += 1

    communities = _build_communities(request, entity_map, relation_map, evidences, chunk_entity_ids, chunk_relation_ids)
    return GraphExtractResponse(
        entities=_sort_entities(entity_map.values()),
        relations=_sort_relations(relation_map.values()),
        evidences=evidences,
        communities=communities,
    )


def _chunk_text(chunk) -> str:
    return (chunk.content_with_weight or chunk.text or "").strip()


def _candidate_names(chunk, text: str) -> list[str]:
    scored: dict[str, int] = {}

    def add(name: str, score: int) -> None:
        cleaned = _clean_name(name)
        if not _valid_name(cleaned):
            return
        scored[cleaned] = scored.get(cleaned, 0) + score

    for part in _split_section_path(chunk.section_path):
        add(part, 8)
    add(chunk.title, 10)
    if chunk.chunk_type:
        add(chunk.chunk_type, 2)

    for term in re.findall(r"[\u4e00-\u9fffA-Za-z0-9_（）()·]{2,32}", text):
        for item in _split_chinese_phrase(term):
            add(item, 3)

    for term in re.findall(r"\b[A-Za-z][A-Za-z0-9._/-]{1,40}\b", text):
        add(term, 4 if any(char.isupper() for char in term) else 2)

    return [
        name
        for name, _ in sorted(
            scored.items(),
            key=lambda item: (-item[1], len(item[0]), item[0].lower()),
        )
    ]


def _split_section_path(section_path: str) -> list[str]:
    if not section_path:
        return []
    return [
        part.strip()
        for part in re.split(r"[/\\>|＞:：\n\r\t]+", section_path)
        if part and part.strip()
    ]


def _split_chinese_phrase(phrase: str) -> list[str]:
    parts: list[str] = []
    normalized = phrase.strip()
    if 2 <= len(normalized) <= 10:
        parts.append(normalized)
    for item in re.split(r"(?:的|和|与|及|或|并|在|对|通过|根据|用于|可以|需要|进行|实现|提供|支持|包括|包含|属于|作为|由|向|从|到|将|把)", normalized):
        item = item.strip()
        if 2 <= len(item) <= 16:
            parts.append(item)
    return parts


def _clean_name(name: str) -> str:
    if not name:
        return ""
    cleaned = re.sub(r"\s+", " ", name).strip(" \t\r\n,，。；;:：()（）[]【】{}<>《》\"'")
    cleaned = re.sub(r"^(第[一二三四五六七八九十百千万0-9]+[章节条款项、.．-]*)", "", cleaned).strip()
    return cleaned


def _valid_name(name: str) -> bool:
    if not name or len(name) < 2 or len(name) > 32:
        return False
    lowered = name.lower()
    if lowered in ENGLISH_STOPWORDS or name in CHINESE_STOPWORDS:
        return False
    if re.fullmatch(r"[0-9._/-]+", name):
        return False
    if re.fullmatch(r"[A-Za-z]{1,2}", name):
        return False
    if len(re.findall(r"[\u4e00-\u9fffA-Za-z]", name)) < 2:
        return False
    return True


def _classify_entity(name: str, chunk) -> str:
    if name and name == chunk.title:
        return "SECTION"
    if any(part == name for part in _split_section_path(chunk.section_path)):
        return "SECTION"
    if re.search(r"(公司|部门|团队|委员会|中心|机构|用户|客户)$", name):
        return "ORG"
    if re.search(r"(系统|平台|服务|模块|组件|接口|API|SDK|Engine|Service)$", name, re.IGNORECASE):
        return "SYSTEM"
    if re.search(r"(流程|规则|策略|方案|计划|制度|标准)$", name):
        return "PROCESS"
    if re.search(r"(金额|数量|比例|时间|日期|费用|成本|得分|score|count|amount)$", name, re.IGNORECASE):
        return "METRIC"
    return "CONCEPT"


def _entity_description(name: str, entity_type: str, section_path: str) -> str:
    if section_path:
        return f"{name}，来自章节：{section_path}"
    return f"{name}，类型：{entity_type}"


def _entity_evidence(evidence_id: str, entity_id: str, chunk, text: str, name: str) -> GraphEvidence:
    return GraphEvidence(
        id=evidence_id,
        entityId=entity_id,
        chunkId=chunk.chunk_id,
        parentBlockId=chunk.parent_block_id,
        quoteText=_quote(text, [name]),
        pageNo=chunk.page_no,
        pageRange=chunk.page_range,
        bboxJson=chunk.bbox_json,
        sectionPath=chunk.section_path,
        metadata={
            "chunkNo": chunk.chunk_no,
            "chunkType": chunk.chunk_type,
            "sourceBlockIds": chunk.source_block_ids,
        },
    )


def _relation_evidence(evidence_id: str, relation_id: str, chunk, text: str, source_name: str, target_name: str) -> GraphEvidence:
    return GraphEvidence(
        id=evidence_id,
        relationId=relation_id,
        chunkId=chunk.chunk_id,
        parentBlockId=chunk.parent_block_id,
        quoteText=_quote(text, [source_name, target_name]),
        pageNo=chunk.page_no,
        pageRange=chunk.page_range,
        bboxJson=chunk.bbox_json,
        sectionPath=chunk.section_path,
        metadata={
            "chunkNo": chunk.chunk_no,
            "chunkType": chunk.chunk_type,
            "sourceBlockIds": chunk.source_block_ids,
        },
    )


def _relation_type(text: str, source_name: str, target_name: str) -> str:
    source_pos = _find_name(text, source_name)
    target_pos = _find_name(text, target_name)
    if source_pos < 0 or target_pos < 0:
        return ""
    start = min(source_pos, target_pos)
    end = max(source_pos + len(source_name), target_pos + len(target_name))
    if end - start > 120:
        return ""
    window = text[start:end]
    for word, relation_type in RELATION_WORDS:
        if word in window:
            return relation_type
    return ""


def _find_name(text: str, name: str) -> int:
    index = text.find(name)
    if index >= 0:
        return index
    return text.lower().find(name.lower())


def _relation_weight(relation_type: str) -> float:
    if relation_type == "ASSOCIATED_WITH":
        return 0.45
    if relation_type in {"IS_A", "BELONGS_TO", "CONTAINS", "PART_OF"}:
        return 0.85
    return 0.75


def _quote(text: str, names: list[str]) -> str:
    compact = re.sub(r"\s+", " ", text or "").strip()
    if not compact:
        return ""
    positions = [_find_name(compact, name) for name in names if name]
    positions = [position for position in positions if position >= 0]
    if not positions:
        return compact[:MAX_QUOTE_LENGTH]
    center = min(positions)
    start = max(0, center - 80)
    end = min(len(compact), center + MAX_QUOTE_LENGTH)
    return compact[start:end].strip()


def _build_communities(
    request: GraphExtractRequest,
    entity_map: dict[str, GraphEntity],
    relation_map: dict[str, GraphRelation],
    evidences: list[GraphEvidence],
    chunk_entity_ids: dict[int, list[str]],
    chunk_relation_ids: dict[int, list[str]],
) -> list[GraphCommunity]:
    chunk_section: dict[int, str] = {
        chunk.chunk_id: _community_title(chunk.section_path, chunk.title)
        for chunk in request.chunks
        if chunk.chunk_id is not None
    }
    entity_ids_by_title: dict[str, set[str]] = defaultdict(set)
    relation_ids_by_title: dict[str, set[str]] = defaultdict(set)
    evidence_ids_by_title: dict[str, set[str]] = defaultdict(set)

    for chunk_id, entity_ids in chunk_entity_ids.items():
        title = chunk_section.get(chunk_id, "全文")
        entity_ids_by_title[title].update(entity_ids)
    for chunk_id, relation_ids in chunk_relation_ids.items():
        title = chunk_section.get(chunk_id, "全文")
        relation_ids_by_title[title].update(relation_ids)
    for evidence in evidences:
        if evidence.chunk_id is None:
            continue
        title = chunk_section.get(evidence.chunk_id, "全文")
        evidence_ids_by_title[title].add(evidence.id)

    communities: list[GraphCommunity] = []
    for index, title in enumerate(sorted(entity_ids_by_title.keys()), start=1):
        entity_ids = sorted(entity_ids_by_title[title])
        relation_ids = sorted(relation_ids_by_title.get(title, set()))
        evidence_ids = sorted(evidence_ids_by_title.get(title, set()))
        names = [entity_map[entity_id].name for entity_id in entity_ids[:6] if entity_id in entity_map]
        summary = f"{title} 主题下包含 " + "、".join(names)
        if len(entity_ids) > 6:
            summary += f" 等 {len(entity_ids)} 个实体"
        communities.append(GraphCommunity(
            id=f"COM{index}",
            title=title,
            summary=summary,
            entityIds=entity_ids,
            relationIds=relation_ids,
            evidenceIds=evidence_ids[:20],
            metadata={
                "documentId": request.document_id,
                "taskId": request.task_id,
            },
        ))
    return communities


def _community_title(section_path: str, title: str) -> str:
    parts = _split_section_path(section_path)
    if parts:
        return parts[0]
    return _clean_name(title) or "全文"


def _entity_id(name: str, entity_type: str) -> str:
    return "ENT_" + _hash(f"{entity_type}:{_normalize_entity(name)}")


def _relation_id(source_id: str, target_id: str, relation_type: str) -> str:
    left, right = sorted([source_id, target_id])
    return "REL_" + _hash(f"{left}:{relation_type}:{right}")


def _hash(value: str) -> str:
    return hashlib.sha1(value.encode("utf-8")).hexdigest()[:16].upper()


def _normalize_entity(name: str) -> str:
    return re.sub(r"\s+", "", name or "").lower()


def _unique(items: list[str]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for item in items:
        if item not in seen:
            seen.add(item)
            result.append(item)
    return result


def _sort_entities(entities) -> list[GraphEntity]:
    return sorted(entities, key=lambda entity: (entity.type, entity.name.lower(), entity.id))


def _sort_relations(relations) -> list[GraphRelation]:
    return sorted(relations, key=lambda relation: (relation.relation_type, relation.source_entity_id, relation.target_entity_id))
