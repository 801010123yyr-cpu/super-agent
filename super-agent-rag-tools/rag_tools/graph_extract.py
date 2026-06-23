import hashlib
import re
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from itertools import combinations
from typing import Any

import networkx as nx

from rag_tools.schemas.graph_extract import (
    GraphCommunity,
    GraphEntity,
    GraphEvidence,
    GraphExtractRequest,
    GraphExtractResponse,
    GraphRelation,
)

MAX_ENTITIES_PER_CHUNK = 16
MAX_RELATIONS_PER_CHUNK = 12
MAX_QUOTE_LENGTH = 260

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
    "模块",
    "服务",
    "抽取",
    "社区",
    "检测",
    "文档",
    "问题",
    "内容",
    "说明",
    "信息",
    "功能",
}

ENGLISH_STOPWORDS = {
    "and",
    "agent",
    "are",
    "can",
    "for",
    "from",
    "into",
    "super",
    "that",
    "the",
    "this",
    "with",
    "your",
}

RELATION_WORDS = [
    ("依赖", "DEPENDS_ON", 0.92),
    ("调用", "CALLS", 0.9),
    ("使用", "USES", 0.86),
    ("采用", "USES", 0.84),
    ("支持", "SUPPORTS", 0.82),
    ("包含", "CONTAINS", 0.86),
    ("包括", "CONTAINS", 0.84),
    ("组成", "PART_OF", 0.8),
    ("属于", "BELONGS_TO", 0.86),
    ("负责", "RESPONSIBLE_FOR", 0.86),
    ("关联", "RELATED_TO", 0.74),
    ("映射", "MAPS_TO", 0.86),
    ("生成", "PRODUCES", 0.82),
    ("输出", "PRODUCES", 0.82),
    ("输入", "CONSUMES", 0.8),
    ("is a", "IS_A", 0.84),
    ("depends on", "DEPENDS_ON", 0.92),
    ("calls", "CALLS", 0.9),
    ("uses", "USES", 0.86),
    ("supports", "SUPPORTS", 0.82),
    ("contains", "CONTAINS", 0.84),
    ("belongs to", "BELONGS_TO", 0.86),
    ("maps to", "MAPS_TO", 0.86),
    ("produces", "PRODUCES", 0.82),
]

REVERSE_HINTS = {"由", "被", "来自"}


@dataclass
class CandidateName:
    name: str
    score: float = 0.0
    aliases: set[str] = field(default_factory=set)
    sources: set[str] = field(default_factory=set)


@dataclass
class RelationCandidate:
    source_id: str
    target_id: str
    source_name: str
    target_name: str
    relation_type: str
    phrase: str
    confidence: float


def extract_graph(request: GraphExtractRequest) -> GraphExtractResponse:
    entity_map: dict[str, GraphEntity] = {}
    entity_alias_index: dict[str, str] = {}
    relation_map: dict[str, GraphRelation] = {}
    evidences: list[GraphEvidence] = []
    evidence_no = 1
    chunk_entity_ids: dict[int, list[str]] = {}
    chunk_relation_ids: dict[int, list[str]] = {}
    entity_chunk_mentions: dict[str, Counter[int]] = defaultdict(Counter)

    for chunk in request.chunks:
        if chunk.chunk_id is None:
            continue
        text = _chunk_text(chunk)
        if not text:
            continue

        candidates = _candidate_names(chunk, text)
        entity_ids: list[str] = []
        for candidate in candidates[:MAX_ENTITIES_PER_CHUNK]:
            entity_type = _classify_entity(candidate.name, chunk)
            variants = _entity_variants(candidate.name, candidate.aliases)
            entity_id = _resolve_entity_id(entity_type, variants, entity_alias_index)
            entity = entity_map.get(entity_id)
            if entity is None:
                entity = GraphEntity(
                    id=entity_id,
                    name=candidate.name,
                    normalizedName=_normalize_entity(candidate.name),
                    aliases=sorted(_valid_aliases(candidate.aliases, candidate.name)),
                    type=entity_type,
                    description=_entity_description(candidate.name, entity_type, candidate.aliases, chunk.section_path),
                    confidence=_entity_confidence(candidate.score),
                    metadata={
                        "firstChunkNo": chunk.chunk_no,
                        "firstSectionPath": chunk.section_path,
                        "mentionCount": 0,
                        "candidateScore": 0.0,
                        "candidateSources": [],
                    },
                )
                entity_map[entity_id] = entity
            _merge_entity_candidate(entity, candidate, entity_type, chunk)
            _index_entity_aliases(entity_id, entity.type, _entity_variants(entity.name, set(entity.aliases)), entity_alias_index)
            if chunk.chunk_id not in entity.source_chunk_ids:
                entity.source_chunk_ids.append(chunk.chunk_id)
            entity_chunk_mentions[entity_id][chunk.chunk_id] += 1

            evidence = _entity_evidence(f"EV{evidence_no}", entity_id, chunk, text, candidate)
            evidence_no += 1
            evidences.append(evidence)
            if evidence.id not in entity.evidence_ids:
                entity.evidence_ids.append(evidence.id)
            entity_ids.append(entity_id)

        chunk_entity_ids[chunk.chunk_id] = _unique(entity_ids)
        relation_count = 0
        associated_count = 0
        for left_id, right_id in combinations(chunk_entity_ids[chunk.chunk_id], 2):
            if relation_count >= MAX_RELATIONS_PER_CHUNK:
                break
            relation_candidate = _relation_candidate(text, entity_map[left_id], entity_map[right_id])
            if relation_candidate is None:
                if associated_count >= 4:
                    continue
                relation_candidate = _associated_relation_candidate(text, entity_map[left_id], entity_map[right_id])
                if relation_candidate is None:
                    continue
                associated_count += 1

            relation_id = _relation_id(
                relation_candidate.source_id,
                relation_candidate.target_id,
                relation_candidate.relation_type,
            )
            relation = relation_map.get(relation_id)
            if relation is None:
                relation = GraphRelation(
                    id=relation_id,
                    sourceEntityId=relation_candidate.source_id,
                    targetEntityId=relation_candidate.target_id,
                    relationType=relation_candidate.relation_type,
                    description=_relation_description(relation_candidate, entity_map),
                    weight=_relation_weight(relation_candidate.relation_type, relation_candidate.confidence),
                    metadata={
                        "firstChunkNo": chunk.chunk_no,
                        "firstSectionPath": chunk.section_path,
                        "relationPhrases": [],
                        "sourceChunkIds": [],
                        "confidence": relation_candidate.confidence,
                    },
                )
                relation_map[relation_id] = relation
            _merge_relation_candidate(relation, relation_candidate, chunk)

            evidence = _relation_evidence(
                f"EV{evidence_no}",
                relation_id,
                chunk,
                text,
                relation_candidate.source_name,
                relation_candidate.target_name,
                relation_candidate,
            )
            evidence_no += 1
            evidences.append(evidence)
            if evidence.id not in relation.evidence_ids:
                relation.evidence_ids.append(evidence.id)
            chunk_relation_ids.setdefault(chunk.chunk_id, []).append(relation_id)
            relation_count += 1

    communities = _build_communities(
        request,
        entity_map,
        relation_map,
        evidences,
        chunk_entity_ids,
        chunk_relation_ids,
        entity_chunk_mentions,
    )
    return GraphExtractResponse(
        entities=_sort_entities(entity_map.values()),
        relations=_sort_relations(relation_map.values()),
        evidences=evidences,
        communities=communities,
    )


def _chunk_text(chunk) -> str:
    return (chunk.content_with_weight or chunk.text or "").strip()


def _candidate_names(chunk, text: str) -> list[CandidateName]:
    candidates: dict[str, CandidateName] = {}

    def add(name: str, score: float, source: str, aliases: set[str] | None = None) -> None:
        cleaned = _clean_name(name)
        if not _valid_name(cleaned):
            return
        candidate = candidates.setdefault(cleaned, CandidateName(name=cleaned))
        candidate.score += score
        candidate.sources.add(source)
        for alias in aliases or set():
            alias_cleaned = _clean_name(alias)
            if _valid_name(alias_cleaned) and _normalize_entity(alias_cleaned) != _normalize_entity(cleaned):
                candidate.aliases.add(alias_cleaned)

    for part in _split_section_path(chunk.section_path):
        add(part, 8, "sectionPath")
    add(chunk.title, 10, "title")

    for keyword in _metadata_terms(chunk.metadata, "keywords", "autoKeywords"):
        add(keyword, 8, "metadata.keyword")
    for question_term in _metadata_terms(chunk.metadata, "questions", "autoQuestions"):
        for part in _split_query_like_phrase(question_term):
            add(part, 4, "metadata.question")

    for primary, alias in _alias_pairs(text + "\n" + (chunk.title or "")):
        add(primary, 10, "parentheticalAlias", {alias})
        add(alias, 4, "parentheticalAlias", {primary})

    for term in re.findall(r"[\u4e00-\u9fffA-Za-z0-9_（）()·./-]{2,40}", text):
        for item in _split_chinese_phrase(term):
            add(item, 3, "textPhrase")

    for term in re.findall(r"\b(?:[A-Z][A-Za-z0-9]+(?:[\s/-]+|$)){2,5}", text):
        add(term, 5, "englishPhrase")

    for term in re.findall(r"\b[A-Za-z][A-Za-z0-9._/-]{1,40}\b", text):
        if any(char.isupper() for char in term) or re.search(r"[0-9._/-]", term):
            add(term, 4, "englishToken")

    for candidate in list(candidates.values()):
        acronym = _english_acronym(candidate.name)
        if acronym:
            candidate.aliases.add(acronym)

    return sorted(
        candidates.values(),
        key=lambda item: (-item.score, -len(item.aliases), len(item.name), item.name.lower()),
    )


def _metadata_terms(metadata: dict[str, Any] | None, *keys: str) -> list[str]:
    if not metadata:
        return []
    terms: list[str] = []
    for key in keys:
        value = metadata.get(key)
        if value is None:
            continue
        if isinstance(value, str):
            parts = re.split(r"[\n\r,，;；、|]+", value)
        elif isinstance(value, list):
            parts = [str(item) for item in value if item is not None]
        else:
            parts = [str(value)]
        for part in parts:
            cleaned = _clean_name(part)
            if cleaned:
                terms.append(cleaned)
    return terms


def _alias_pairs(text: str) -> list[tuple[str, str]]:
    pairs: list[tuple[str, str]] = []
    pattern = re.compile(
        r"([\u4e00-\u9fffA-Za-z][\u4e00-\u9fffA-Za-z0-9_.·/\-\s]{1,40})[（(]"
        r"([A-Za-z][A-Za-z0-9_.\-]{1,16}|[\u4e00-\u9fff]{2,16})[）)]"
    )
    for match in pattern.finditer(text or ""):
        primary = _clean_name(match.group(1))
        alias = _clean_name(match.group(2))
        if _valid_name(primary) and _valid_name(alias):
            pairs.append((primary, alias))
    return pairs


def _split_section_path(section_path: str) -> list[str]:
    if not section_path:
        return []
    return [
        part.strip()
        for part in re.split(r"[/\\>|＞:：\n\r\t]+", section_path)
        if part and part.strip()
    ]


def _split_query_like_phrase(phrase: str) -> list[str]:
    return [
        item.strip()
        for item in re.split(r"(?:怎么|如何|为什么|哪些|什么|是否|以及|或者|并且|的|和|与|及|或|、|，|,|；|;|：|:)", phrase or "")
        if 2 <= len(item.strip()) <= 32
    ]


def _split_chinese_phrase(phrase: str) -> list[str]:
    parts: list[str] = []
    normalized = phrase.strip()
    if 2 <= len(normalized) <= 12:
        parts.append(normalized)
    for item in re.split(
        r"(?:的|和|与|及|或|并|在|对|通过|根据|用于|可以|需要|进行|实现|提供|支持|包括|包含|属于|作为|由|向|从|到|将|把|及其|以及)",
        normalized,
    ):
        item = item.strip()
        if 2 <= len(item) <= 18:
            parts.append(item)
    return parts


def _clean_name(name: str) -> str:
    if not name:
        return ""
    cleaned = re.sub(r"\s+", " ", name).strip(" \t\r\n,，。；;:：()（）[]【】{}<>《》\"'")
    cleaned = re.sub(r"[（(][^）)]{1,20}[）)]", "", cleaned).strip()
    cleaned = re.sub(r"^(第[一二三四五六七八九十百千万0-9]+[章节条款项、.．-]*)", "", cleaned).strip()
    cleaned = re.sub(r"^(一|二|三|四|五|六|七|八|九|十)[、.．-]+", "", cleaned).strip()
    return cleaned


def _valid_name(name: str) -> bool:
    if not name or len(name) < 2 or len(name) > 40:
        return False
    lowered = name.lower()
    if lowered in ENGLISH_STOPWORDS or name in CHINESE_STOPWORDS:
        return False
    if lowered in {word for word, _, _ in RELATION_WORDS}:
        return False
    for word, _, _ in RELATION_WORDS:
        if re.search(r"[\u4e00-\u9fff]", word) and word in name and len(name) > len(word) + 1:
            return False
    if re.fullmatch(r"[0-9._/-]+", name):
        return False
    if re.fullmatch(r"[A-Za-z]{1,2}", name):
        return False
    if re.fullmatch(r"[A-Za-z]+", name) and name.islower():
        return False
    if len(re.findall(r"[\u4e00-\u9fffA-Za-z]", name)) < 2:
        return False
    return True


def _classify_entity(name: str, chunk) -> str:
    if name and name == chunk.title:
        return "SECTION"
    if any(part == name for part in _split_section_path(chunk.section_path)):
        return "SECTION"
    if re.search(r"(公司|部门|团队|委员会|中心|机构|用户|客户|组织)$", name):
        return "ORG"
    if re.search(r"(系统|平台|服务|模块|组件|接口|API|SDK|Engine|Service|Client|Server)$", name, re.IGNORECASE):
        return "SYSTEM"
    if re.search(r"(流程|规则|策略|方案|计划|制度|标准|机制|链路)$", name):
        return "PROCESS"
    if re.search(r"(金额|数量|比例|时间|日期|费用|成本|得分|指标|score|count|amount|metric)$", name, re.IGNORECASE):
        return "METRIC"
    return "CONCEPT"


def _entity_description(name: str, entity_type: str, aliases: set[str], section_path: str) -> str:
    alias_text = ""
    if aliases:
        alias_text = "，别名：" + "、".join(sorted(aliases)[:5])
    if section_path:
        return f"{name}，类型：{entity_type}{alias_text}，来自章节：{section_path}"
    return f"{name}，类型：{entity_type}{alias_text}"


def _entity_confidence(score: float) -> float:
    return round(min(0.99, 0.35 + score / 30.0), 4)


def _entity_variants(name: str, aliases: set[str] | list[str]) -> list[str]:
    variants: list[str] = []
    for item in [name, *list(aliases or [])]:
        cleaned = _clean_name(item)
        if not cleaned:
            continue
        variants.append(cleaned)
        acronym = _english_acronym(cleaned)
        if acronym:
            variants.append(acronym)
    return _unique(variants)


def _resolve_entity_id(entity_type: str, variants: list[str], alias_index: dict[str, str]) -> str:
    normalized_variants = [_normalize_entity(variant) for variant in variants if _normalize_entity(variant)]
    for variant in normalized_variants:
        entity_id = alias_index.get(_entity_alias_key(entity_type, variant))
        if entity_id:
            return entity_id
    primary = normalized_variants[0] if normalized_variants else ""
    return "ENT_" + _hash(f"{entity_type}:{primary}")


def _index_entity_aliases(entity_id: str, entity_type: str, variants: list[str], alias_index: dict[str, str]) -> None:
    for variant in variants:
        normalized = _normalize_entity(variant)
        if normalized:
            alias_index[_entity_alias_key(entity_type, normalized)] = entity_id


def _entity_alias_key(entity_type: str, normalized_variant: str) -> str:
    return f"{entity_type}:{normalized_variant}"


def _merge_entity_candidate(entity: GraphEntity, candidate: CandidateName, entity_type: str, chunk) -> None:
    metadata = entity.metadata or {}
    metadata["mentionCount"] = int(metadata.get("mentionCount") or 0) + 1
    metadata["candidateScore"] = round(float(metadata.get("candidateScore") or 0.0) + candidate.score, 4)
    metadata["lastChunkNo"] = chunk.chunk_no
    metadata["lastSectionPath"] = chunk.section_path
    sources = set(metadata.get("candidateSources") or [])
    sources.update(candidate.sources)
    metadata["candidateSources"] = sorted(sources)

    aliases = set(entity.aliases or [])
    aliases.update(candidate.aliases)
    if _better_entity_name(candidate.name, entity.name):
        aliases.add(entity.name)
        entity.name = candidate.name
        entity.normalized_name = _normalize_entity(candidate.name)
        entity.description = _entity_description(candidate.name, entity_type, aliases, chunk.section_path)
    elif _normalize_entity(candidate.name) != _normalize_entity(entity.name):
        aliases.add(candidate.name)

    entity.aliases = sorted(_valid_aliases(aliases, entity.name))
    entity.confidence = max(float(entity.confidence or 0.0), _entity_confidence(candidate.score))
    entity.metadata = metadata


def _valid_aliases(aliases: set[str] | list[str], primary_name: str) -> set[str]:
    primary = _normalize_entity(primary_name)
    return {
        alias
        for alias in aliases or []
        if _valid_name(alias) and _normalize_entity(alias) != primary
    }


def _better_entity_name(candidate_name: str, current_name: str) -> bool:
    if not current_name:
        return True
    if _is_acronym(current_name) and not _is_acronym(candidate_name):
        return True
    if not _is_acronym(current_name) and _is_acronym(candidate_name):
        return False
    return len(candidate_name) > len(current_name) and len(candidate_name) <= 32


def _is_acronym(name: str) -> bool:
    return bool(re.fullmatch(r"[A-Z][A-Z0-9._-]{1,12}", name or ""))


def _english_acronym(name: str) -> str:
    words = re.findall(r"[A-Za-z][A-Za-z0-9]*", name or "")
    if len(words) < 2:
        return ""
    acronym = "".join(word[0].upper() for word in words if word)
    return acronym if 2 <= len(acronym) <= 10 else ""


def _entity_evidence(evidence_id: str, entity_id: str, chunk, text: str, candidate: CandidateName) -> GraphEvidence:
    return GraphEvidence(
        id=evidence_id,
        entityId=entity_id,
        chunkId=chunk.chunk_id,
        parentBlockId=chunk.parent_block_id,
        quoteText=_quote(text, [candidate.name, *sorted(candidate.aliases)]),
        pageNo=chunk.page_no,
        pageRange=chunk.page_range,
        bboxJson=chunk.bbox_json,
        sectionPath=chunk.section_path,
        metadata={
            "chunkNo": chunk.chunk_no,
            "chunkType": chunk.chunk_type,
            "sourceBlockIds": chunk.source_block_ids,
            "candidateName": candidate.name,
            "candidateAliases": sorted(candidate.aliases),
            "candidateScore": round(candidate.score, 4),
            "candidateSources": sorted(candidate.sources),
        },
    )


def _relation_candidate(text: str, left: GraphEntity, right: GraphEntity) -> RelationCandidate | None:
    left_position = _find_entity_mention(text, left)
    right_position = _find_entity_mention(text, right)
    if left_position is None or right_position is None:
        return None

    start = min(left_position[0], right_position[0])
    end = max(left_position[0] + len(left_position[1]), right_position[0] + len(right_position[1]))
    if end - start > 160:
        return None
    window = text[start:end]
    lowered_window = window.lower()
    for phrase, relation_type, confidence in RELATION_WORDS:
        if phrase.lower() not in lowered_window:
            continue
        left_before_right = left_position[0] <= right_position[0]
        source = left if left_before_right else right
        target = right if left_before_right else left
        source_name = left_position[1] if left_before_right else right_position[1]
        target_name = right_position[1] if left_before_right else left_position[1]
        between = text[min(left_position[0], right_position[0]):max(left_position[0], right_position[0])]
        if relation_type in {"RESPONSIBLE_FOR", "PRODUCES", "CONSUMES"} and any(hint in between for hint in REVERSE_HINTS):
            source, target = target, source
            source_name, target_name = target_name, source_name
        return RelationCandidate(
            source_id=source.id,
            target_id=target.id,
            source_name=source_name,
            target_name=target_name,
            relation_type=relation_type,
            phrase=phrase,
            confidence=confidence,
        )
    return None


def _associated_relation_candidate(text: str, left: GraphEntity, right: GraphEntity) -> RelationCandidate | None:
    left_position = _find_entity_mention(text, left)
    right_position = _find_entity_mention(text, right)
    if left_position is None or right_position is None:
        return None
    if abs(left_position[0] - right_position[0]) > 100:
        return None
    if left.confidence < 0.55 and right.confidence < 0.55:
        return None
    return RelationCandidate(
        source_id=left.id,
        target_id=right.id,
        source_name=left_position[1],
        target_name=right_position[1],
        relation_type="ASSOCIATED_WITH",
        phrase="co_occurrence",
        confidence=0.45,
    )


def _find_entity_mention(text: str, entity: GraphEntity) -> tuple[int, str] | None:
    mentions = [entity.name, *(entity.aliases or [])]
    found: list[tuple[int, str]] = []
    for mention in mentions:
        position = _find_name(text, mention)
        if position >= 0:
            found.append((position, mention))
    return min(found, key=lambda item: item[0]) if found else None


def _relation_description(candidate: RelationCandidate, entity_map: dict[str, GraphEntity]) -> str:
    source = entity_map[candidate.source_id]
    target = entity_map[candidate.target_id]
    return f"{source.name} {candidate.relation_type} {target.name}"


def _merge_relation_candidate(relation: GraphRelation, candidate: RelationCandidate, chunk) -> None:
    relation.weight = max(float(relation.weight or 0.0), _relation_weight(candidate.relation_type, candidate.confidence))
    metadata = relation.metadata or {}
    phrases = set(metadata.get("relationPhrases") or [])
    phrases.add(candidate.phrase)
    metadata["relationPhrases"] = sorted(phrases)
    source_chunk_ids = set(metadata.get("sourceChunkIds") or [])
    if chunk.chunk_id is not None:
        source_chunk_ids.add(chunk.chunk_id)
    metadata["sourceChunkIds"] = sorted(source_chunk_ids)
    metadata["confidence"] = max(float(metadata.get("confidence") or 0.0), candidate.confidence)
    relation.metadata = metadata


def _relation_evidence(
    evidence_id: str,
    relation_id: str,
    chunk,
    text: str,
    source_name: str,
    target_name: str,
    candidate: RelationCandidate,
) -> GraphEvidence:
    return GraphEvidence(
        id=evidence_id,
        relationId=relation_id,
        chunkId=chunk.chunk_id,
        parentBlockId=chunk.parent_block_id,
        quoteText=_quote(text, [source_name, target_name, candidate.phrase]),
        pageNo=chunk.page_no,
        pageRange=chunk.page_range,
        bboxJson=chunk.bbox_json,
        sectionPath=chunk.section_path,
        metadata={
            "chunkNo": chunk.chunk_no,
            "chunkType": chunk.chunk_type,
            "sourceBlockIds": chunk.source_block_ids,
            "sourceName": source_name,
            "targetName": target_name,
            "relationPhrase": candidate.phrase,
            "confidence": candidate.confidence,
        },
    )


def _relation_weight(relation_type: str, confidence: float) -> float:
    if relation_type == "ASSOCIATED_WITH":
        return 0.45
    if relation_type in {"IS_A", "BELONGS_TO", "CONTAINS", "PART_OF"}:
        return round(min(0.95, 0.75 + confidence * 0.15), 4)
    return round(min(0.92, 0.65 + confidence * 0.2), 4)


def _quote(text: str, names: list[str]) -> str:
    compact = re.sub(r"\s+", " ", text or "").strip()
    if not compact:
        return ""
    positions = [_find_name(compact, name) for name in names if name]
    positions = [position for position in positions if position >= 0]
    if not positions:
        return compact[:MAX_QUOTE_LENGTH]
    center = min(positions)
    start = max(0, center - 90)
    end = min(len(compact), center + MAX_QUOTE_LENGTH)
    return compact[start:end].strip()


def _find_name(text: str, name: str) -> int:
    if not text or not name:
        return -1
    index = text.find(name)
    if index >= 0:
        return index
    return text.lower().find(name.lower())


def _build_communities(
    request: GraphExtractRequest,
    entity_map: dict[str, GraphEntity],
    relation_map: dict[str, GraphRelation],
    evidences: list[GraphEvidence],
    chunk_entity_ids: dict[int, list[str]],
    chunk_relation_ids: dict[int, list[str]],
    entity_chunk_mentions: dict[str, Counter[int]],
) -> list[GraphCommunity]:
    if not entity_map:
        return []

    graph = nx.Graph()
    for entity_id, entity in entity_map.items():
        graph.add_node(entity_id, name=entity.name, entity_type=entity.type, confidence=entity.confidence)
    for relation in relation_map.values():
        if relation.source_entity_id == relation.target_entity_id:
            continue
        graph.add_edge(
            relation.source_entity_id,
            relation.target_entity_id,
            weight=float(relation.weight or 0.0),
            relation_id=relation.id,
            relation_type=relation.relation_type,
        )

    communities = _detect_graph_communities(graph)
    evidence_by_id = {evidence.id: evidence for evidence in evidences}
    chunk_section = _chunk_section_map(request)
    result: list[GraphCommunity] = []
    for index, entity_ids in enumerate(communities, start=1):
        if len(entity_ids) < 2:
            continue
        relation_ids = _community_relation_ids(entity_ids, relation_map)
        evidence_ids = _community_evidence_ids(entity_ids, relation_ids, entity_map, relation_map, evidence_by_id)
        title = _community_title(entity_ids, relation_ids, entity_map, relation_map)
        summary = _community_summary(title, entity_ids, relation_ids, entity_map, relation_map, evidence_by_id, chunk_section)
        result.append(GraphCommunity(
            id=f"COM{index}",
            title=title,
            summary=summary,
            entityIds=sorted(entity_ids),
            relationIds=sorted(relation_ids),
            evidenceIds=evidence_ids[:30],
            metadata={
                "documentId": request.document_id,
                "taskId": request.task_id,
                "communityAlgorithm": "networkx.greedy_modularity",
                "entityCount": len(entity_ids),
                "relationCount": len(relation_ids),
                "mentionedChunkIds": _mentioned_chunks(entity_ids, entity_chunk_mentions),
                "chunkRelationIds": {
                    str(chunk_id): relation_ids
                    for chunk_id, relation_ids in chunk_relation_ids.items()
                    if relation_ids
                },
            },
        ))
    return result


def _detect_graph_communities(graph: nx.Graph) -> list[set[str]]:
    if graph.number_of_nodes() == 0:
        return []
    if graph.number_of_edges() == 0:
        return [set(component) for component in nx.connected_components(graph)]
    if graph.number_of_nodes() >= 4 and graph.number_of_edges() >= 3:
        communities = nx.algorithms.community.greedy_modularity_communities(graph, weight="weight")
        return [set(community) for community in communities]
    return [set(component) for component in nx.connected_components(graph)]


def _community_relation_ids(entity_ids: set[str], relation_map: dict[str, GraphRelation]) -> list[str]:
    return [
        relation.id
        for relation in relation_map.values()
        if relation.source_entity_id in entity_ids and relation.target_entity_id in entity_ids
    ]


def _community_evidence_ids(
    entity_ids: set[str],
    relation_ids: list[str],
    entity_map: dict[str, GraphEntity],
    relation_map: dict[str, GraphRelation],
    evidence_by_id: dict[str, GraphEvidence],
) -> list[str]:
    evidence_ids: list[str] = []
    for relation_id in relation_ids:
        relation = relation_map.get(relation_id)
        if relation:
            evidence_ids.extend(relation.evidence_ids)
    for entity_id in entity_ids:
        entity = entity_map.get(entity_id)
        if entity:
            evidence_ids.extend(entity.evidence_ids[:4])
    return [
        evidence_id
        for evidence_id in _unique(evidence_ids)
        if evidence_id in evidence_by_id
    ]


def _community_title(
    entity_ids: set[str],
    relation_ids: list[str],
    entity_map: dict[str, GraphEntity],
    relation_map: dict[str, GraphRelation],
) -> str:
    top_entities = _top_entities(entity_ids, entity_map, relation_map)[:3]
    names = [entity_map[entity_id].name for entity_id in top_entities]
    return " / ".join(names) if names else "未命名图谱社区"


def _community_summary(
    title: str,
    entity_ids: set[str],
    relation_ids: list[str],
    entity_map: dict[str, GraphEntity],
    relation_map: dict[str, GraphRelation],
    evidence_by_id: dict[str, GraphEvidence],
    chunk_section: dict[int, str],
) -> str:
    top_entities = _top_entities(entity_ids, entity_map, relation_map)[:6]
    entity_text = "、".join(entity_map[entity_id].name for entity_id in top_entities)
    relation_texts: list[str] = []
    for relation_id in relation_ids[:6]:
        relation = relation_map[relation_id]
        source = entity_map.get(relation.source_entity_id)
        target = entity_map.get(relation.target_entity_id)
        if source and target:
            relation_texts.append(f"{source.name}-{relation.relation_type}-{target.name}")
    section_counter: Counter[str] = Counter()
    for relation_id in relation_ids:
        relation = relation_map.get(relation_id)
        if not relation:
            continue
        for evidence_id in relation.evidence_ids:
            evidence = evidence_by_id.get(evidence_id)
            if evidence and evidence.chunk_id is not None:
                section_counter[chunk_section.get(evidence.chunk_id, "全文")] += 1
    section_text = "、".join(section for section, _ in section_counter.most_common(3))
    summary = f"{title} 社区包含 {len(entity_ids)} 个实体，核心实体包括 {entity_text}"
    if relation_texts:
        summary += "；主要关系包括 " + "、".join(relation_texts)
    if section_text:
        summary += "；代表章节为 " + section_text
    return summary + "。"


def _top_entities(
    entity_ids: set[str],
    entity_map: dict[str, GraphEntity],
    relation_map: dict[str, GraphRelation],
) -> list[str]:
    degree = Counter()
    for relation in relation_map.values():
        if relation.source_entity_id in entity_ids:
            degree[relation.source_entity_id] += 1
        if relation.target_entity_id in entity_ids:
            degree[relation.target_entity_id] += 1
    return sorted(
        entity_ids,
        key=lambda entity_id: (
            -degree[entity_id],
            -float(entity_map[entity_id].confidence or 0.0),
            entity_map[entity_id].name.lower(),
        ),
    )


def _chunk_section_map(request: GraphExtractRequest) -> dict[int, str]:
    return {
        chunk.chunk_id: _section_title(chunk.section_path, chunk.title)
        for chunk in request.chunks
        if chunk.chunk_id is not None
    }


def _section_title(section_path: str, title: str) -> str:
    parts = _split_section_path(section_path)
    if parts:
        return parts[0]
    return _clean_name(title) or "全文"


def _mentioned_chunks(entity_ids: set[str], entity_chunk_mentions: dict[str, Counter[int]]) -> list[int]:
    counter: Counter[int] = Counter()
    for entity_id in entity_ids:
        counter.update(entity_chunk_mentions.get(entity_id, Counter()))
    return [chunk_id for chunk_id, _ in counter.most_common(20)]


def _relation_id(source_id: str, target_id: str, relation_type: str) -> str:
    if relation_type == "ASSOCIATED_WITH":
        source_id, target_id = sorted([source_id, target_id])
    return "REL_" + _hash(f"{source_id}:{relation_type}:{target_id}")


def _hash(value: str) -> str:
    return hashlib.sha1(value.encode("utf-8")).hexdigest()[:16].upper()


def _normalize_entity(name: str) -> str:
    normalized = re.sub(r"\s+", "", name or "").lower()
    normalized = re.sub(r"[`*_#，,。；;：:（）()“”\"'\[\]{}<>《》/\\|-]+", "", normalized)
    return normalized


def _unique(items: list[Any]) -> list[Any]:
    result: list[Any] = []
    seen: set[Any] = set()
    for item in items:
        if item not in seen:
            seen.add(item)
            result.append(item)
    return result


def _sort_entities(entities) -> list[GraphEntity]:
    return sorted(entities, key=lambda entity: (-float(entity.confidence or 0.0), entity.type, entity.name.lower(), entity.id))


def _sort_relations(relations) -> list[GraphRelation]:
    return sorted(
        relations,
        key=lambda relation: (
            -float(relation.weight or 0.0),
            relation.relation_type,
            relation.source_entity_id,
            relation.target_entity_id,
        ),
    )
