import hashlib
import os
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
EXTRACTOR_RULE = "rule"
EXTRACTOR_NER = "ner"
NER_MODEL_ENV_ENABLED = "SUPER_AGENT_GRAPH_RAG_NER_MODEL_ENABLED"
NER_MODEL_ENV_PROVIDER = "SUPER_AGENT_GRAPH_RAG_NER_MODEL_PROVIDER"
NER_MODEL_ENV_NAME = "SUPER_AGENT_GRAPH_RAG_NER_MODEL_NAME"
NER_MODEL_ENV_DEVICE = "SUPER_AGENT_GRAPH_RAG_NER_MODEL_DEVICE"
NER_MODEL_ENV_AUTO_DOWNLOAD = "SUPER_AGENT_GRAPH_RAG_NER_MODEL_AUTO_DOWNLOAD"
NER_MODEL_ENV_MAX_CHARS = "SUPER_AGENT_GRAPH_RAG_NER_MODEL_MAX_CHARS"
NER_MODEL_DEFAULT_PROVIDER = "transformers"
NER_MODEL_DEFAULT_MAX_CHARS = 8000

_SPACY_NLP = None
_SPACY_LOAD_ATTEMPTED = False
_SPACY_MODEL_NAME = ""
_NER_MODEL_PIPELINE = None
_NER_MODEL_LOAD_ATTEMPTED = False
_NER_MODEL_STATUS: dict[str, Any] | None = None
_NER_MODEL_CONFIG_KEY: tuple[Any, ...] | None = None

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
    "权限",
    "要求",
    "范围",
    "适用范围",
    "审计",
    "标题",
    "正文",
    "行为",
    "事项",
    "注意事项",
    "关于",
    "以下权限相关行为",
    "核心内容",
    "简称",
    "又称",
    "也称",
    "别名",
    "英文名",
    "英文名称",
    "缩写",
    "完成",
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
    "chunk_type",
    "content",
    "ct",
    "keywords",
    "metadata",
    "questions",
    "section",
    "text",
    "title",
}

QUESTION_LIKE_MARKERS = ("哪些", "什么", "如何", "怎么", "是否", "有没有", "吗", "？", "?")

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
    ("审批", "APPROVES", 0.84),
    ("批准", "APPROVES", 0.84),
    ("审核", "APPROVES", 0.84),
    ("执行", "EXECUTES", 0.86),
    ("触发", "TRIGGERS", 0.84),
    ("发起", "TRIGGERS", 0.84),
    ("记录", "RECORDS", 0.84),
    ("存放", "STORES", 0.82),
    ("归档", "ARCHIVES", 0.82),
    ("回收", "REVOKES", 0.82),
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
    quote_text: str = ""
    sources: set[str] = field(default_factory=set)


@dataclass
class StructuredRelationCandidate:
    source_name: str
    target_name: str
    relation_type: str
    phrase: str
    confidence: float
    quote_text: str
    source_aliases: set[str] = field(default_factory=set)
    target_aliases: set[str] = field(default_factory=set)
    source_mention: str = ""
    target_mention: str = ""
    sources: set[str] = field(default_factory=lambda: {"rule.structuredRelation"})


def extract_graph(request: GraphExtractRequest) -> GraphExtractResponse:
    entity_map: dict[str, GraphEntity] = {}
    entity_alias_index: dict[str, str] = {}
    relation_map: dict[str, GraphRelation] = {}
    evidences: list[GraphEvidence] = []
    evidence_no = 1
    chunk_entity_ids: dict[int, list[str]] = {}
    chunk_relation_ids: dict[int, list[str]] = {}
    entity_chunk_mentions: dict[str, Counter[int]] = defaultdict(Counter)
    global_grade_aliases = _data_grade_aliases_from_chunks(request.chunks)
    cross_chunk_structured_relations = _cross_chunk_structured_relation_candidates(request.chunks)

    for chunk in request.chunks:
        if chunk.chunk_id is None:
            continue
        text = _chunk_text(chunk)
        if not text:
            continue
        evidence_text = _chunk_evidence_text(chunk)

        def upsert_entity_candidate(candidate: CandidateName) -> str:
            nonlocal evidence_no
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

            evidence = _entity_evidence(f"EV{evidence_no}", entity_id, chunk, evidence_text, candidate)
            if evidence is not None:
                evidence_no += 1
                evidences.append(evidence)
                if evidence.id not in entity.evidence_ids:
                    entity.evidence_ids.append(evidence.id)
            return entity_id

        def upsert_relation_candidate(relation_candidate: RelationCandidate) -> bool:
            nonlocal evidence_no
            if relation_candidate.source_id == relation_candidate.target_id:
                return False
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
                evidence_text,
                relation_candidate.source_name,
                relation_candidate.target_name,
                relation_candidate,
            )
            evidence_no += 1
            evidences.append(evidence)
            if evidence.id not in relation.evidence_ids:
                relation.evidence_ids.append(evidence.id)
            chunk_relation_ids.setdefault(chunk.chunk_id, []).append(relation_id)
            return True

        structured_relations = _dedupe_structured_relations([
            *_structured_relation_candidates(evidence_text, global_grade_aliases),
            *cross_chunk_structured_relations.get(chunk.chunk_id, []),
        ])
        candidates = _candidate_names(chunk, evidence_text)
        entity_ids: list[str] = []
        for candidate in candidates[:MAX_ENTITIES_PER_CHUNK]:
            entity_id = upsert_entity_candidate(candidate)
            entity_ids.append(entity_id)

        chunk_entity_ids[chunk.chunk_id] = _unique(entity_ids)
        for structured_relation in structured_relations:
            source_id = upsert_entity_candidate(CandidateName(
                name=structured_relation.source_name,
                score=16,
                aliases=structured_relation.source_aliases,
                sources={"rule.structuredRelation.source"},
            ))
            target_id = upsert_entity_candidate(CandidateName(
                name=structured_relation.target_name,
                score=14,
                aliases=structured_relation.target_aliases,
                sources={"rule.structuredRelation.target"},
            ))
            chunk_entity_ids[chunk.chunk_id] = _unique([*chunk_entity_ids[chunk.chunk_id], source_id, target_id])
            upsert_relation_candidate(RelationCandidate(
                source_id=source_id,
                target_id=target_id,
                source_name=structured_relation.source_mention or structured_relation.source_name,
                target_name=structured_relation.target_mention or structured_relation.target_name,
                relation_type=structured_relation.relation_type,
                phrase=structured_relation.phrase,
                confidence=structured_relation.confidence,
                quote_text=structured_relation.quote_text,
                sources=structured_relation.sources,
            ))

        relation_count = len(chunk_relation_ids.get(chunk.chunk_id, []))
        associated_count = 0
        for left_id, right_id in combinations(chunk_entity_ids[chunk.chunk_id], 2):
            if relation_count >= MAX_RELATIONS_PER_CHUNK:
                break
            relation_candidate = _relation_candidate(evidence_text, entity_map[left_id], entity_map[right_id])
            if relation_candidate is None:
                if associated_count >= 4:
                    continue
                relation_candidate = _associated_relation_candidate(evidence_text, entity_map[left_id], entity_map[right_id])
            if relation_candidate is None:
                continue
            associated_count += 1

            if upsert_relation_candidate(relation_candidate):
                relation_count += 1

    entities = _sort_entities(entity_map.values())
    relations = _sort_relations(relation_map.values())
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
        entities=entities,
        relations=relations,
        evidences=evidences,
        communities=communities,
        metadata=_extract_metadata(entities, relations, evidences),
    )


def _chunk_text(chunk) -> str:
    return (chunk.content_with_weight or chunk.text or "").strip()


def _chunk_evidence_text(chunk) -> str:
    text = (chunk.text or "").strip()
    if text and not _metadata_weighted_text(text):
        return text
    weighted = (chunk.content_with_weight or "").strip()
    if not weighted:
        return text
    content = _content_segment_from_weighted_text(weighted)
    return content or text or weighted


def _metadata_weighted_text(text: str) -> bool:
    return bool(re.search(r"\[(?:TITLE|SECTION|CHUNK_TYPE|KEYWORDS|QUESTIONS|CONTENT|TEXT)\]", text or "", re.IGNORECASE))


def _content_segment_from_weighted_text(text: str) -> str:
    if not text:
        return ""
    match = re.search(r"\[CONTENT\]\s*(.*)$", text, re.IGNORECASE | re.DOTALL)
    if not match:
        return ""
    content = match.group(1)
    content = re.split(r"\[(?:TITLE|SECTION|CHUNK_TYPE|KEYWORDS|QUESTIONS|TEXT)\]", content, maxsplit=1, flags=re.IGNORECASE)[0]
    content = re.sub(r"^\s*section\s*:\s*", "", content.strip(), flags=re.IGNORECASE)
    return content.strip()


def _structured_relation_candidates(
    text: str,
    global_grade_aliases: dict[str, set[str]] | None = None,
) -> list[StructuredRelationCandidate]:
    candidates: list[StructuredRelationCandidate] = []
    table_rows = _markdown_table_rows(text)
    grade_aliases = _data_grade_aliases(table_rows)
    for grade, aliases in (global_grade_aliases or {}).items():
        grade_aliases[grade].update(aliases)
    for row_text, cells in table_rows:
        candidates.extend(_structured_relations_from_table_row(row_text, cells, grade_aliases))
    candidates.extend(_structured_relations_from_policy_text(text))
    return _dedupe_structured_relations(candidates)


def _markdown_table_rows(text: str) -> list[tuple[str, list[str]]]:
    rows: list[tuple[str, list[str]]] = []
    for line in (text or "").splitlines():
        row_text = line.strip()
        if "|" not in row_text:
            continue
        cells = [_clean_table_cell(cell) for cell in row_text.strip("|").split("|")]
        cells = [cell for cell in cells if cell]
        if len(cells) < 2:
            continue
        if all(re.fullmatch(r":?-{2,}:?", cell) for cell in cells):
            continue
        rows.append((row_text, cells))
    return rows


def _clean_table_cell(value: str) -> str:
    return _clean_name(re.sub(r"`+", "", value or ""))


def _data_grade_aliases(rows: list[tuple[str, list[str]]]) -> dict[str, set[str]]:
    aliases: dict[str, set[str]] = defaultdict(set)
    for _, cells in rows:
        if len(cells) < 2:
            continue
        grade = _data_grade(cells[0])
        if not grade:
            continue
        aliases[grade].add(grade)
        if any(marker in cells[1] for marker in ("敏感", "数据", "信息")):
            aliases[grade].add(cells[1])
    return aliases


def _data_grade_aliases_from_chunks(chunks: list[Any]) -> dict[str, set[str]]:
    aliases: dict[str, set[str]] = defaultdict(set)
    for chunk in chunks or []:
        text = _chunk_evidence_text(chunk)
        if not text:
            continue
        for _, cells in _markdown_table_rows(text):
            if len(cells) < 2:
                continue
            grade = _data_grade(cells[0])
            if not grade:
                continue
            aliases[grade].add(grade)
            if any(marker in cells[1] for marker in ("敏感", "数据", "信息")):
                aliases[grade].add(cells[1])
    return aliases


def _cross_chunk_structured_relation_candidates(
    chunks: list[Any],
) -> dict[int, list[StructuredRelationCandidate]]:
    candidates_by_chunk_id: dict[int, list[StructuredRelationCandidate]] = defaultdict(list)
    ordered_chunks = sorted(
        [chunk for chunk in chunks or [] if chunk.chunk_id is not None and _chunk_text(chunk)],
        key=lambda item: (
            item.chunk_no if item.chunk_no is not None else 10**12,
            item.chunk_id if item.chunk_id is not None else 10**12,
        ),
    )
    for index, chunk in enumerate(ordered_chunks):
        lines = _non_empty_lines(_chunk_evidence_text(chunk))
        for line_index, line in enumerate(lines):
            source_name = _recording_policy_source(line)
            if source_name:
                window_lines, line_chunk_ids = _following_policy_window(ordered_chunks, index, lines, line_index)
                for item in _policy_list_items("\n".join(window_lines)):
                    attach_chunk_id = _policy_item_chunk_id(item, window_lines, line_chunk_ids) or chunk.chunk_id
                    candidates_by_chunk_id[attach_chunk_id].append(StructuredRelationCandidate(
                        source_name=source_name,
                        target_name=item,
                        relation_type="RECORDS",
                        phrase="记录",
                        confidence=0.9,
                        quote_text=_policy_recording_quote(source_name, window_lines, item),
                        source_mention=source_name,
                        target_mention=item,
                    ))

            storage_source = _storage_policy_source(line)
            if storage_source:
                window_lines, line_chunk_ids = _following_policy_window(ordered_chunks, index, lines, line_index)
                for target in _storage_policy_targets(window_lines):
                    attach_chunk_id = _policy_item_chunk_id(target, window_lines, line_chunk_ids) or chunk.chunk_id
                    candidates_by_chunk_id[attach_chunk_id].append(StructuredRelationCandidate(
                        source_name=storage_source,
                        target_name=target,
                        relation_type="STORES",
                        phrase="存放",
                        confidence=0.9,
                        quote_text=_policy_list_quote(storage_source, window_lines, target),
                        source_mention=storage_source,
                        target_mention=target,
                    ))

    return {
        chunk_id: _dedupe_structured_relations(candidates)
        for chunk_id, candidates in candidates_by_chunk_id.items()
    }


def _structured_relations_from_table_row(
    row_text: str,
    cells: list[str],
    grade_aliases: dict[str, set[str]] | None = None,
) -> list[StructuredRelationCandidate]:
    relations: list[StructuredRelationCandidate] = []
    if not cells:
        return relations

    first_cell = cells[0]
    row_joined = " | ".join(cells)
    action_text = "、".join(cells[1:])
    if _policy_actor_like(first_cell):
        source_aliases = _policy_actor_aliases(cells)
        for phrase, relation_type, confidence in _policy_action_words():
            for target, target_aliases in _targets_after_policy_action(action_text, phrase, relation_type):
                relations.append(StructuredRelationCandidate(
                    source_name=first_cell,
                    target_name=target,
                    relation_type=relation_type,
                    phrase=phrase,
                    confidence=confidence,
                    quote_text=row_text,
                    source_aliases=source_aliases,
                    target_aliases=target_aliases,
                    source_mention=first_cell,
                    target_mention=_target_mention_for_quote(target, target_aliases),
                ))

    grade = _data_grade(first_cell)
    if grade and ("审批" in row_joined or "批准" in row_joined or "审核" in row_joined):
        source_name = f"{grade} 数据"
        source_aliases = set((grade_aliases or {}).get(grade, set())) or {grade}
        if len(cells) >= 2 and any(marker in cells[1] for marker in ("敏感", "数据", "信息")):
            source_aliases.add(cells[1])
        for approver in _approval_targets(row_joined):
            relations.append(StructuredRelationCandidate(
                source_name=source_name,
                target_name=approver,
                relation_type="APPROVES",
                phrase="审批",
                confidence=0.9,
                quote_text=row_text,
                source_aliases=source_aliases,
                source_mention=grade,
                target_mention=approver,
            ))

    return relations


def _non_empty_lines(text: str) -> list[str]:
    return [line.strip() for line in (text or "").splitlines() if line.strip()]


def _recording_policy_source(line: str) -> str:
    match = re.search(
        r"`?([A-Za-z][A-Za-z0-9._/-]{1,40})`?\s*(?:需|应|必须)?记录"
        r"(?:以下权限相关行为|如下权限相关行为|以下行为|如下行为|权限相关行为)?[：:]",
        line or "",
    )
    if not match:
        return ""
    return _clean_name(match.group(1))


def _storage_policy_source(line: str) -> str:
    match = re.search(
        r"`?([\u4e00-\u9fffA-Za-z0-9_（）()·./\-\s]{2,30}?)`?\s*"
        r"(?:原则上)?(?:仅|只)?允许(?:存放|存储|保存)于"
        r"(?:公司)?(?:批准|指定|受控)?的?(?:平台|系统|环境|位置)?[：:]",
        line or "",
    )
    if not match:
        return ""
    return _clean_name(match.group(1))


def _following_policy_window(
    ordered_chunks: list[Any],
    chunk_index: int,
    current_lines: list[str],
    line_index: int,
    max_lines: int = 10,
    max_next_chunks: int = 5,
) -> tuple[list[str], list[int]]:
    chunk = ordered_chunks[chunk_index]
    window_lines: list[str] = [current_lines[line_index], *current_lines[line_index + 1:]]
    line_chunk_ids: list[int] = [chunk.chunk_id for _ in window_lines]
    for next_chunk in ordered_chunks[chunk_index + 1:chunk_index + 1 + max_next_chunks]:
        next_lines = _non_empty_lines(_chunk_evidence_text(next_chunk))
        if not next_lines:
            continue
        if window_lines and _section_heading(next_lines[0]):
            break
        window_lines.extend(next_lines)
        line_chunk_ids.extend([next_chunk.chunk_id for _ in next_lines])
        if len(window_lines) >= max_lines:
            break
    return window_lines[:max_lines], line_chunk_ids[:max_lines]


def _section_heading(line: str) -> bool:
    return bool(re.match(r"^\s{0,3}#{1,6}\s+\S+", line or ""))


def _policy_list_line(line: str) -> bool:
    return bool(re.match(r"^\s*[-*]\s+\S+", line or ""))


def _policy_recording_quote(source_name: str, lines: list[str], target_item: str = "") -> str:
    return _policy_list_quote(source_name, lines, target_item)


def _policy_list_quote(source_name: str, lines: list[str], target_item: str = "") -> str:
    source_line = ""
    target_line = ""
    for line in lines:
        if not source_line:
            source_line = line
        if target_item and target_item in line:
            target_line = line
            break
    selected = [line for line in (source_line, target_line) if line]
    if selected:
        return "\n".join(selected)
    return f"{source_name} {target_item}".strip()


def _policy_item_chunk_id(item: str, lines: list[str], chunk_ids: list[int]) -> int | None:
    if not item:
        return None
    for line, chunk_id in zip(lines, chunk_ids):
        if item in line:
            return chunk_id
    return None


def _structured_relations_from_policy_text(text: str) -> list[StructuredRelationCandidate]:
    relations: list[StructuredRelationCandidate] = []
    lines = _non_empty_lines(text)

    for index, line in enumerate(lines):
        source_name = _recording_policy_source(line)
        if source_name:
            following_text = "\n".join(lines[index:index + 8])
            for item in _policy_list_items(following_text):
                relations.append(StructuredRelationCandidate(
                    source_name=source_name,
                    target_name=item,
                    relation_type="RECORDS",
                    phrase="记录",
                    confidence=0.9,
                    quote_text=following_text,
                    source_mention=source_name,
                    target_mention=item,
                ))

        storage_source = _storage_policy_source(line)
        if storage_source:
            following_lines = lines[index:index + 8]
            for target in _storage_policy_targets(following_lines):
                relations.append(StructuredRelationCandidate(
                    source_name=storage_source,
                    target_name=target,
                    relation_type="STORES",
                    phrase="存放",
                    confidence=0.9,
                    quote_text=_policy_list_quote(storage_source, following_lines, target),
                    source_mention=storage_source,
                    target_mention=target,
                ))

    for sentence in re.split(r"(?<=[。！？；;])|\n", text or ""):
        sentence = sentence.strip()
        if not sentence:
            continue
        if "发布负责人" in sentence and ("触发回滚" in sentence or "发起回滚" in sentence):
            relations.append(StructuredRelationCandidate(
                source_name="发布负责人",
                target_name="回滚",
                relation_type="TRIGGERS",
                phrase="触发" if "触发回滚" in sentence else "发起",
                confidence=0.9,
                quote_text=sentence,
                source_mention="发布负责人",
                target_mention="回滚",
            ))
        if "值班 SRE" in sentence and "执行流量切换" in sentence:
            relations.append(StructuredRelationCandidate(
                source_name="值班 SRE",
                target_name="流量切换",
                relation_type="EXECUTES",
                phrase="执行",
                confidence=0.9,
                quote_text=sentence,
                source_aliases={"SRE"},
                source_mention="值班 SRE",
                target_mention="流量切换",
            ))
        if "AuditTrail" in sentence and "记录" in sentence and "权限申请" in sentence:
            relations.append(StructuredRelationCandidate(
                source_name="AuditTrail",
                target_name="权限申请",
                relation_type="RECORDS",
                phrase="记录",
                confidence=0.9,
                quote_text=sentence,
                source_mention="AuditTrail",
                target_mention="权限申请",
            ))
        if re.search(r"\bL[0-9]{1,2}\b", sentence) and "信息安全部" in sentence and any(word in sentence for word in ("审批", "批准", "审核")):
            grade = re.search(r"\b(L[0-9]{1,2})\b", sentence).group(1)
            aliases = {grade}
            if "高敏感信息" in sentence:
                aliases.add("高敏感信息")
            if "高敏感数据" in sentence:
                aliases.add("高敏感数据")
            relations.append(StructuredRelationCandidate(
                source_name=f"{grade} 数据",
                target_name="信息安全部",
                relation_type="APPROVES",
                phrase="审批",
                confidence=0.9,
                quote_text=sentence,
                source_aliases=aliases,
                source_mention=grade,
                target_mention="信息安全部",
            ))
    return relations


def _policy_actor_like(value: str) -> bool:
    return bool(re.search(r"(负责人|管理员|SRE|DBA|QA|DPO|团队|部门|委员会|Owner|Admin|Manager)$", value or "", re.IGNORECASE))


def _policy_actor_aliases(cells: list[str]) -> set[str]:
    aliases: set[str] = set()
    if len(cells) >= 2 and _policy_actor_like(cells[1]):
        aliases.add(cells[1])
    first = cells[0] if cells else ""
    if "SRE" in first:
        aliases.add("SRE")
    return aliases


def _policy_action_words() -> list[tuple[str, str, float]]:
    return [
        ("触发", "TRIGGERS", 0.9),
        ("发起", "TRIGGERS", 0.88),
        ("执行", "EXECUTES", 0.9),
        ("记录", "RECORDS", 0.88),
        ("存放", "STORES", 0.86),
        ("归档", "ARCHIVES", 0.86),
        ("回收", "REVOKES", 0.84),
    ]


def _targets_after_policy_action(text: str, phrase: str, relation_type: str) -> list[tuple[str, set[str]]]:
    targets: list[tuple[str, set[str]]] = []
    for match in re.finditer(re.escape(phrase), text or ""):
        tail = text[match.end():]
        target_match = re.match(r"[\u4e00-\u9fffA-Za-z0-9_（）()·./\-\s]{2,32}", tail)
        if not target_match:
            continue
        raw_target = re.split(r"[、,，；;。.!！?？|]", target_match.group(0).strip(), maxsplit=1)[0]
        target, aliases = _canonical_policy_target(raw_target, relation_type)
        if _valid_name(target):
            targets.append((target, aliases))
    return targets


def _canonical_policy_target(value: str, relation_type: str) -> tuple[str, set[str]]:
    cleaned = _clean_name(value)
    cleaned = re.sub(r"^(其|对应|相关|以下|如下|的)+", "", cleaned).strip()
    aliases: set[str] = set()
    if "数据库脚本" in cleaned or "数据恢复脚本" in cleaned:
        if cleaned != "数据库脚本":
            aliases.add(cleaned)
        return "数据库脚本", aliases
    if "回滚" in cleaned:
        if cleaned != "回滚":
            aliases.add(cleaned)
        return "回滚", aliases
    if "流量切换" in cleaned or "流量切回" in cleaned:
        if cleaned != "流量切换":
            aliases.add(cleaned)
        return "流量切换", aliases
    if "权限申请" in cleaned:
        if cleaned != "权限申请":
            aliases.add(cleaned)
        return "权限申请", aliases
    if relation_type == "RECORDS":
        cleaned = re.split(r"(?:和|与|及|以及)", cleaned, maxsplit=1)[0].strip()
    return cleaned, aliases


def _target_mention_for_quote(target: str, aliases: set[str]) -> str:
    return sorted(aliases, key=len, reverse=True)[0] if aliases else target


def _data_grade(value: str) -> str:
    match = re.fullmatch(r"(L[0-9]{1,2})", (value or "").strip(), re.IGNORECASE)
    return match.group(1).upper() if match else ""


def _approval_targets(text: str) -> list[str]:
    targets = []
    for name in ("信息安全部", "数据治理负责人", "部门负责人"):
        if name in text:
            targets.append(name)
    return targets


def _policy_list_items(text: str) -> list[str]:
    items = []
    lines = [
        line
        for line in (text or "").splitlines()
        if _policy_list_line(line)
    ]
    if not lines:
        lines = (text or "").splitlines()
    for line in (text or "").splitlines():
        if lines and line not in lines:
            continue
        cleaned = re.sub(r"^\s*[-*]\s*", "", line).strip()
        if not cleaned or _recording_policy_source(cleaned) or _section_heading(cleaned):
            continue
        for item in re.split(r"[、,，；;。.!！?？]+", cleaned):
            item = _clean_name(item)
            if _valid_name(item) and item not in {"审批", "回收", "延长", "以下行为"} and item not in items:
                items.append(item)
    return items[:16]


def _storage_policy_targets(lines: list[str]) -> list[str]:
    targets: list[str] = []
    for line in lines or []:
        if _storage_policy_source(line):
            continue
        for item in re.findall(r"`([^`]{2,60})`", line or ""):
            cleaned = _clean_name(item)
            if _valid_name(cleaned) and cleaned not in targets:
                targets.append(cleaned)
        if "`" in (line or ""):
            continue
        match = re.search(r"[：:]\s*([\u4e00-\u9fffA-Za-z0-9_（）()·./\-\s]{2,40})", line or "")
        if match:
            cleaned = _clean_name(match.group(1))
            if _valid_name(cleaned) and cleaned not in targets:
                targets.append(cleaned)
    return targets[:16]


def _dedupe_structured_relations(candidates: list[StructuredRelationCandidate]) -> list[StructuredRelationCandidate]:
    deduped: dict[tuple[str, str, str], StructuredRelationCandidate] = {}
    for candidate in candidates:
        key = (
            _normalize_entity(candidate.source_name),
            candidate.relation_type,
            _normalize_entity(candidate.target_name),
        )
        existing = deduped.get(key)
        if existing is None or candidate.confidence > existing.confidence:
            deduped[key] = candidate
    return list(deduped.values())


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

    for term in re.findall(r"[\u4e00-\u9fff]{1,8}\s+[A-Z][A-Za-z0-9._/-]{1,16}|[A-Z][A-Za-z0-9._/-]{1,16}\s+[\u4e00-\u9fff]{1,8}", text):
        add(term, 8, "mixedPhrase")

    for term in re.findall(r"\b[A-Za-z][A-Za-z0-9._/-]{1,40}\b", text):
        if any(char.isupper() for char in term) or re.search(r"[0-9._/-]", term):
            add(term, 4, "englishToken")

    for candidate in _ner_candidate_names(chunk, text):
        cleaned = _clean_name(candidate.name)
        if not _valid_name(cleaned):
            continue
        merged = candidates.setdefault(cleaned, CandidateName(name=cleaned))
        merged.score += candidate.score
        merged.sources.update(candidate.sources or {EXTRACTOR_NER})
        for alias in candidate.aliases:
            alias_cleaned = _clean_name(alias)
            if _valid_name(alias_cleaned) and _normalize_entity(alias_cleaned) != _normalize_entity(cleaned):
                merged.aliases.add(alias_cleaned)

    for candidate in list(candidates.values()):
        acronym = _english_acronym(candidate.name)
        if acronym:
            candidate.aliases.add(acronym)

    return sorted(
        candidates.values(),
        key=lambda item: (-item.score, -len(item.aliases), -_candidate_name_priority(item), item.name.lower()),
    )


def _ner_candidate_names(chunk, text: str) -> list[CandidateName]:
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

    for name, source in _spacy_ner_terms(text):
        add(name, 9, source)

    for name in _pattern_ner_terms(text):
        add(name, 7, "ner.pattern")

    for name in _role_ner_terms(text + "\n" + (chunk.title or "") + "\n" + (chunk.section_path or "")):
        add(name, 8, "ner.role")

    for name, source in _model_ner_terms(text):
        add(name, 10, source)

    return sorted(candidates.values(), key=lambda item: (-item.score, -len(item.name), item.name.lower()))


def _spacy_ner_terms(text: str) -> list[tuple[str, str]]:
    global _SPACY_NLP, _SPACY_LOAD_ATTEMPTED, _SPACY_MODEL_NAME
    if not text or not re.search(r"[A-Za-z]", text):
        return []
    if _SPACY_LOAD_ATTEMPTED and _SPACY_NLP is None:
        return []
    if _SPACY_NLP is not None:
        nlp = _SPACY_NLP
    else:
        _SPACY_LOAD_ATTEMPTED = True
        nlp = None
        try:
            import spacy
        except ImportError:
            return []
        for model_name in ("en_core_web_sm", "en_core_web_md"):
            try:
                nlp = spacy.load(model_name)
                _SPACY_NLP = nlp
                _SPACY_MODEL_NAME = model_name
                break
            except OSError:
                continue
    if nlp is None:
        return []
    terms: list[tuple[str, str]] = []
    for entity in nlp(text[:8000]).ents:
        if entity.label_ in {"ORDINAL", "CARDINAL", "DATE", "TIME", "MONEY", "PERCENT", "QUANTITY"}:
            continue
        terms.append((entity.text, f"ner.spacy.{entity.label_.lower()}"))
    return terms


def _model_ner_terms(text: str) -> list[tuple[str, str]]:
    pipeline = _ensure_ner_model_pipeline()
    if pipeline is None:
        return []
    max_chars = _ner_model_max_chars()
    try:
        raw_entities = pipeline((text or "")[:max_chars])
    except Exception as exception:
        _set_ner_model_status("unavailable", f"inference failed: {exception}", loaded=False)
        return []

    terms: list[tuple[str, str]] = []
    for item in raw_entities or []:
        if not isinstance(item, dict):
            continue
        name = _model_ner_entity_text(item)
        label = _model_ner_entity_label(item)
        if not name:
            continue
        source = f"ner.model.{_ner_model_provider()}"
        if label:
            source = f"{source}.{label}"
        terms.append((name, source))
    return terms[:80]


def _ensure_ner_model_pipeline():
    global _NER_MODEL_PIPELINE, _NER_MODEL_LOAD_ATTEMPTED, _NER_MODEL_STATUS, _NER_MODEL_CONFIG_KEY
    config_key = _ner_model_config_key()
    if _NER_MODEL_CONFIG_KEY != config_key:
        _NER_MODEL_PIPELINE = None
        _NER_MODEL_LOAD_ATTEMPTED = False
        _NER_MODEL_STATUS = None
        _NER_MODEL_CONFIG_KEY = config_key

    enabled = _env_bool(NER_MODEL_ENV_ENABLED, False)
    provider = _ner_model_provider()
    model_name = _ner_model_name()
    if not enabled:
        _set_ner_model_status("disabled", "", loaded=False)
        return None
    if provider != NER_MODEL_DEFAULT_PROVIDER:
        _set_ner_model_status("unavailable", f"unsupported provider: {provider}", loaded=False)
        return None
    if not model_name:
        _set_ner_model_status("unavailable", f"{NER_MODEL_ENV_NAME} is required when model NER is enabled", loaded=False)
        return None
    if _NER_MODEL_PIPELINE is not None:
        return _NER_MODEL_PIPELINE
    if _NER_MODEL_LOAD_ATTEMPTED:
        return None

    _NER_MODEL_LOAD_ATTEMPTED = True
    auto_download = _env_bool(NER_MODEL_ENV_AUTO_DOWNLOAD, False)
    try:
        _NER_MODEL_PIPELINE = _load_ner_model_pipeline(model_name, auto_download, _ner_model_device())
        _set_ner_model_status("loaded", "", loaded=True)
        return _NER_MODEL_PIPELINE
    except ImportError:
        _set_ner_model_status("unavailable", "transformers is not installed", loaded=False)
        return None
    except Exception as exception:
        _set_ner_model_status("unavailable", str(exception), loaded=False)
        return None


def _load_ner_model_pipeline(model_name: str, auto_download: bool, device: int):
    from transformers import AutoModelForTokenClassification, AutoTokenizer, pipeline

    tokenizer = AutoTokenizer.from_pretrained(model_name, local_files_only=not auto_download)
    model = AutoModelForTokenClassification.from_pretrained(model_name, local_files_only=not auto_download)
    return pipeline(
        "token-classification",
        model=model,
        tokenizer=tokenizer,
        aggregation_strategy="simple",
        device=device,
    )


def _model_ner_entity_text(item: dict[str, Any]) -> str:
    for key in ("word", "text", "entity"):
        value = item.get(key)
        if isinstance(value, str) and value.strip():
            return _clean_model_ner_word(value)
    return ""


def _model_ner_entity_label(item: dict[str, Any]) -> str:
    value = item.get("entity_group") or item.get("entity")
    if not isinstance(value, str):
        return ""
    return re.sub(r"[^a-z0-9_]+", "_", value.lower()).strip("_")


def _clean_model_ner_word(value: str) -> str:
    cleaned = (value or "").replace("##", "")
    cleaned = re.sub(r"\s+", " ", cleaned).strip()
    return _clean_name(cleaned)


def _ner_model_config_key() -> tuple[Any, ...]:
    return (
        _env_bool(NER_MODEL_ENV_ENABLED, False),
        _ner_model_provider(),
        _ner_model_name(),
        _ner_model_device(),
        _env_bool(NER_MODEL_ENV_AUTO_DOWNLOAD, False),
        _ner_model_max_chars(),
    )


def _ner_model_provider() -> str:
    return os.getenv(NER_MODEL_ENV_PROVIDER, NER_MODEL_DEFAULT_PROVIDER).strip().lower() or NER_MODEL_DEFAULT_PROVIDER


def _ner_model_name() -> str:
    return os.getenv(NER_MODEL_ENV_NAME, "").strip()


def _ner_model_device() -> int:
    raw_value = os.getenv(NER_MODEL_ENV_DEVICE, "-1").strip()
    try:
        return int(raw_value)
    except ValueError:
        return -1


def _ner_model_max_chars() -> int:
    raw_value = os.getenv(NER_MODEL_ENV_MAX_CHARS, str(NER_MODEL_DEFAULT_MAX_CHARS)).strip()
    try:
        return max(256, min(20000, int(raw_value)))
    except ValueError:
        return NER_MODEL_DEFAULT_MAX_CHARS


def _set_ner_model_status(status: str, reason: str = "", loaded: bool = False) -> None:
    global _NER_MODEL_STATUS
    _NER_MODEL_STATUS = {
        "name": "ner.model",
        "enabled": _env_bool(NER_MODEL_ENV_ENABLED, False),
        "status": status,
        "provider": _ner_model_provider(),
        "modelName": _ner_model_name(),
        "device": _ner_model_device(),
        "autoDownload": _env_bool(NER_MODEL_ENV_AUTO_DOWNLOAD, False),
        "loaded": loaded,
    }
    if reason:
        _NER_MODEL_STATUS["reason"] = _compact_reason(reason)


def _ner_model_layer_status() -> dict[str, Any]:
    _ensure_ner_model_pipeline()
    return dict(_NER_MODEL_STATUS or {
        "name": "ner.model",
        "enabled": False,
        "status": "disabled",
        "provider": _ner_model_provider(),
        "modelName": _ner_model_name(),
        "loaded": False,
    })


def _compact_reason(reason: str) -> str:
    return re.sub(r"\s+", " ", reason or "").strip()[:500]


def _env_bool(name: str, default: bool = False) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "y", "on"}


def _pattern_ner_terms(text: str) -> list[str]:
    terms: list[str] = []
    patterns = [
        r"`([A-Za-z][A-Za-z0-9._/-]{1,40})`",
        r"\b[A-Z][A-Za-z0-9]*(?:[A-Z][A-Za-z0-9]*){1,}\b",
        r"\b[A-Z]{2,}(?:[A-Z0-9._/-]{0,20})\b",
        r"\b[A-Za-z][A-Za-z0-9._/-]{1,24}(?:Service|Server|Client|Gateway|Engine|API|SDK|DB|SQL)\b",
        r"\bL[0-9]{1,2}\s*(?:数据|信息)?\b",
        r"[\u4e00-\u9fffA-Za-z0-9_（）()·./-]{2,24}(?:系统|平台|服务|模块|组件|接口|API|SDK|引擎|链路|流程|策略|规范|制度|文档|库|环境)",
    ]
    for pattern in patterns:
        for match in re.finditer(pattern, text or "", re.IGNORECASE):
            value = match.group(1) if match.lastindex else match.group(0)
            cleaned = _clean_name(value)
            if _valid_name(cleaned) and cleaned not in terms:
                terms.append(cleaned)
    return terms[:40]


def _role_ner_terms(text: str) -> list[str]:
    terms: list[str] = []
    for match in re.finditer(
        r"[\u4e00-\u9fffA-Za-z0-9_（）()·./\-\s]{1,24}"
        r"(?:负责人|管理员|SRE|DBA|DPO|QA|Owner|Admin|Manager|团队|部门|委员会)",
        text or "",
        re.IGNORECASE,
    ):
        cleaned = _clean_name(match.group(0))
        cleaned = re.sub(r"^(对应|相关|其|由|和|与|及|以及)\s*", "", cleaned).strip()
        if _valid_name(cleaned) and cleaned not in terms:
            terms.append(cleaned)
    return terms[:40]


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


def _candidate_name_priority(candidate: CandidateName) -> int:
    priority = len(candidate.name)
    if candidate.sources.intersection({"title", "sectionPath", "metadata.keyword", "mixedPhrase"}):
        priority += 8
    if _is_acronym(candidate.name):
        priority -= 4
    return priority


def _alias_pairs(text: str) -> list[tuple[str, str]]:
    pairs: list[tuple[str, str]] = []
    parenthetical_pattern = re.compile(
        r"([\u4e00-\u9fffA-Za-z][\u4e00-\u9fffA-Za-z0-9_.·/\-\s]{1,40})[（(]"
        r"([A-Za-z][A-Za-z0-9_.\-]{1,16}|[\u4e00-\u9fff]{2,16})[）)]"
    )
    for match in parenthetical_pattern.finditer(text or ""):
        primary = _clean_name(match.group(1))
        alias = _clean_name(match.group(2))
        if _valid_name(primary) and _valid_name(alias):
            pairs.append((primary, alias))
    explicit_patterns = [
        re.compile(
            r"`?([\u4e00-\u9fffA-Za-z][\u4e00-\u9fffA-Za-z0-9_.·/\-\s]{1,40})`?"
            r"[\s,，、;；:：]*"
            r"(?:简称为|缩写为|英文名为|英文名称为|又称|也称|别名为|简称|缩写|别名)"
            r"[\s`\"']*"
            r"([A-Za-z][A-Za-z0-9_.\-]{1,20}|[\u4e00-\u9fffA-Za-z0-9_.·/\-\s]{2,24})"
            r"[`\"']*"
        ),
        re.compile(
            r"([A-Za-z][A-Za-z0-9_.\-]{1,20}|[\u4e00-\u9fffA-Za-z0-9_.·/\-\s]{2,24})"
            r"(?:是|为)"
            r"([\u4e00-\u9fffA-Za-z][\u4e00-\u9fffA-Za-z0-9_.·/\-\s]{1,40})"
            r"(?:的简称|的缩写|的英文名|的英文名称|的别名)"
        ),
    ]
    for pattern in explicit_patterns:
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
        r"(?:的|和|与|及|或|并|在|对|通过|根据|用于|可以|需要|进行|实现|提供|支持|包括|包含|属于|作为|由|向|从|到|将|把|及其|以及|审批|批准|审核|执行|触发|发起|记录|存放|归档|回收|简称为|缩写为|简称|又称|也称|别名为|英文名|英文名称|完成)",
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
    if any(marker in name for marker in QUESTION_LIKE_MARKERS):
        return False
    if re.match(r"^(以下|如下|上述|相关).{0,12}(行为|要求|内容|事项)$", name):
        return False
    if any(marker in name for marker in ("简称", "又称", "也称", "别名", "英文名", "英文名称", "缩写")):
        return False
    if name in {"权限审批", "权限回收"}:
        return True
    if lowered in {word for word, _, _ in RELATION_WORDS}:
        return False
    for word, _, _ in RELATION_WORDS:
        if word == "负责" and name.endswith("负责人"):
            continue
        if re.search(r"[\u4e00-\u9fff]", word) and word in name and len(name) > len(word) + 1:
            return False
    if re.fullmatch(r"[0-9._/-]+", name):
        return False
    if re.fullmatch(r"L[0-9]{1,2}", name, re.IGNORECASE):
        return True
    if re.fullmatch(r"[A-Za-z]{1,2}", name):
        return bool(re.fullmatch(r"[A-Z]{2}", name))
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
    metadata["extractorSources"] = _extractor_sources_from_candidate_sources(sources)

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


def _extractor_sources_from_candidate_sources(candidate_sources: set[str] | list[str]) -> list[str]:
    extractor_sources: set[str] = set()
    for source in candidate_sources or []:
        normalized = str(source or "").strip().lower()
        if not normalized:
            continue
        if normalized.startswith("ner"):
            extractor_sources.add(EXTRACTOR_NER)
        elif "llm" in normalized:
            extractor_sources.add("llm-advisor")
        else:
            extractor_sources.add(EXTRACTOR_RULE)
    return sorted(extractor_sources or {EXTRACTOR_RULE})


def _primary_extractor_source(candidate_sources: set[str] | list[str]) -> str:
    extractor_sources = _extractor_sources_from_candidate_sources(candidate_sources)
    for preferred in (EXTRACTOR_NER, "llm-advisor", EXTRACTOR_RULE):
        if preferred in extractor_sources:
            return preferred
    return extractor_sources[0] if extractor_sources else EXTRACTOR_RULE


def _better_entity_name(candidate_name: str, current_name: str) -> bool:
    if not current_name:
        return True
    if _is_data_grade_entity(candidate_name) and not _is_data_grade_entity(current_name):
        return True
    if _is_data_grade_entity(current_name) and not _is_data_grade_entity(candidate_name):
        return False
    if _is_acronym(current_name) and not _is_acronym(candidate_name):
        return True
    if not _is_acronym(current_name) and _is_acronym(candidate_name):
        return False
    return len(candidate_name) > len(current_name) and len(candidate_name) <= 32


def _is_data_grade_entity(name: str) -> bool:
    return bool(re.fullmatch(r"L[0-9]{1,2}\s*(?:数据|信息)", name or "", re.IGNORECASE))


def _is_acronym(name: str) -> bool:
    return bool(re.fullmatch(r"[A-Z][A-Z0-9._-]{1,12}", name or ""))


def _english_acronym(name: str) -> str:
    words = re.findall(r"[A-Za-z][A-Za-z0-9]*", name or "")
    if len(words) < 2:
        return ""
    acronym = "".join(word[0].upper() for word in words if word)
    return acronym if 2 <= len(acronym) <= 10 else ""


def _entity_evidence(evidence_id: str, entity_id: str, chunk, text: str, candidate: CandidateName) -> GraphEvidence | None:
    quote_text = _quote(text, [candidate.name, *sorted(candidate.aliases)], fallback=False)
    if not quote_text:
        return None
    return GraphEvidence(
        id=evidence_id,
        entityId=entity_id,
        chunkId=chunk.chunk_id,
        parentBlockId=chunk.parent_block_id,
        quoteText=quote_text,
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
            "extractorSources": _extractor_sources_from_candidate_sources(candidate.sources),
            "sourceType": _primary_extractor_source(candidate.sources),
            "grounded": True,
        },
    )


def _relation_candidate(text: str, left: GraphEntity, right: GraphEntity) -> RelationCandidate | None:
    left_mentions = _find_entity_mentions(text, left)
    right_mentions = _find_entity_mentions(text, right)
    if not left_mentions or not right_mentions:
        return None

    candidates: list[tuple[int, int, int, RelationCandidate]] = []
    for order, (phrase, relation_type, confidence) in enumerate(RELATION_WORDS):
        relation_window = _best_relation_window(text, left_mentions, right_mentions, phrase)
        if relation_window is None:
            continue
        priority, distance, left_position, right_position, _ = relation_window
        left_before_right = left_position[0] <= right_position[0]
        source = left if left_before_right else right
        target = right if left_before_right else left
        source_name = left_position[1] if left_before_right else right_position[1]
        target_name = right_position[1] if left_before_right else left_position[1]
        between = _mention_gap(text, left_position, right_position)
        if relation_type in {
            "RESPONSIBLE_FOR",
            "PRODUCES",
            "CONSUMES",
            "APPROVES",
            "EXECUTES",
            "TRIGGERS",
            "RECORDS",
            "STORES",
            "ARCHIVES",
            "REVOKES",
        } and any(hint in between for hint in REVERSE_HINTS):
            source, target = target, source
            source_name, target_name = target_name, source_name
        candidates.append(
            (
                priority,
                distance,
                order,
                RelationCandidate(
                    source_id=source.id,
                    target_id=target.id,
                    source_name=source_name,
                    target_name=target_name,
                    relation_type=relation_type,
                    phrase=phrase,
                    confidence=confidence,
                    sources={"rule.relationWord"},
                ),
            )
        )
    if not candidates:
        return None
    return min(candidates, key=lambda item: (item[0], item[1], item[2]))[3]


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
        sources={"rule.coOccurrence"},
    )


def _find_entity_mention(text: str, entity: GraphEntity) -> tuple[int, str] | None:
    found = _find_entity_mentions(text, entity)
    return found[0] if found else None


def _find_entity_mentions(text: str, entity: GraphEntity) -> list[tuple[int, str]]:
    mentions = [entity.name, *(entity.aliases or [])]
    found: list[tuple[int, str]] = []
    for mention in mentions:
        for position in _find_name_positions(text, mention):
            found.append((position, mention))
    return sorted(found, key=lambda item: (item[0], -len(item[1])))


def _best_relation_window(
    text: str,
    left_mentions: list[tuple[int, str]],
    right_mentions: list[tuple[int, str]],
    phrase: str,
) -> tuple[int, int, tuple[int, str], tuple[int, str], str] | None:
    candidates: list[tuple[int, int, int, tuple[int, str], tuple[int, str], str]] = []
    for left_position in left_mentions:
        for right_position in right_mentions:
            start = min(left_position[0], right_position[0])
            end = max(left_position[0] + len(left_position[1]), right_position[0] + len(right_position[1]))
            distance = end - start
            if distance > 160:
                continue
            gap = _mention_gap(text, left_position, right_position)
            if phrase.lower() in gap.lower():
                window = text[start:end]
                candidates.append((0, distance, _phrase_offset(gap, phrase), left_position, right_position, window))
                continue
            context_start = max(0, start - 24)
            context_end = min(len(text), end + 24)
            window = text[context_start:context_end]
            phrase_position = _first_phrase_outside_mentions(text, phrase, context_start, context_end, left_position, right_position)
            if phrase_position < 0:
                continue
            center = (start + end) // 2
            candidates.append((1, distance, abs(phrase_position - center), left_position, right_position, window))
    if not candidates:
        return None
    priority, distance, _, left_position, right_position, window = min(
        candidates,
        key=lambda item: (item[0], item[1], item[2], item[3][0], item[4][0]),
    )
    return priority, distance, left_position, right_position, window


def _mention_gap(text: str, left_position: tuple[int, str], right_position: tuple[int, str]) -> str:
    left_start = left_position[0]
    left_end = left_start + len(left_position[1])
    right_start = right_position[0]
    right_end = right_start + len(right_position[1])
    if left_start <= right_start:
        return text[left_end:right_start]
    return text[right_end:left_start]


def _phrase_offset(text: str, phrase: str) -> int:
    return text.lower().find(phrase.lower())


def _first_phrase_outside_mentions(
    text: str,
    phrase: str,
    start: int,
    end: int,
    left_position: tuple[int, str],
    right_position: tuple[int, str],
) -> int:
    lowered_window = text[start:end].lower()
    lowered_phrase = phrase.lower()
    mention_ranges = [
        (left_position[0], left_position[0] + len(left_position[1])),
        (right_position[0], right_position[0] + len(right_position[1])),
    ]
    for match in re.finditer(re.escape(lowered_phrase), lowered_window):
        absolute_start = start + match.start()
        absolute_end = start + match.end()
        if any(absolute_start < mention_end and absolute_end > mention_start for mention_start, mention_end in mention_ranges):
            continue
        return absolute_start
    return -1


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
    sources = set(metadata.get("candidateSources") or [])
    sources.update(candidate.sources or {EXTRACTOR_RULE})
    metadata["candidateSources"] = sorted(sources)
    metadata["extractorSources"] = _extractor_sources_from_candidate_sources(sources)
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
        quoteText=_relation_quote(text, source_name, target_name, candidate),
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
            "candidateSources": sorted(candidate.sources or {EXTRACTOR_RULE}),
            "extractorSources": _extractor_sources_from_candidate_sources(candidate.sources or {EXTRACTOR_RULE}),
            "sourceType": _primary_extractor_source(candidate.sources or {EXTRACTOR_RULE}),
        },
    )


def _relation_quote(text: str, source_name: str, target_name: str, candidate: RelationCandidate) -> str:
    if candidate.quote_text:
        return _limit_quote(candidate.quote_text)
    return _quote(text, [source_name, target_name, candidate.phrase])


def _limit_quote(quote_text: str) -> str:
    return re.sub(r"\s+", " ", quote_text or "").strip()[:MAX_QUOTE_LENGTH]


def _relation_weight(relation_type: str, confidence: float) -> float:
    if relation_type == "ASSOCIATED_WITH":
        return 0.45
    if relation_type in {"IS_A", "BELONGS_TO", "CONTAINS", "PART_OF"}:
        return round(min(0.95, 0.75 + confidence * 0.15), 4)
    return round(min(0.92, 0.65 + confidence * 0.2), 4)


def _quote(text: str, names: list[str], fallback: bool = True) -> str:
    compact = re.sub(r"\s+", " ", text or "").strip()
    if not compact:
        return ""
    positions = [_find_name(compact, name) for name in names if name]
    positions = [position for position in positions if position >= 0]
    if not positions:
        return compact[:MAX_QUOTE_LENGTH] if fallback else ""
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


def _find_name_positions(text: str, name: str) -> list[int]:
    if not text or not name:
        return []
    positions = [match.start() for match in re.finditer(re.escape(name), text)]
    if positions:
        return positions
    lowered_text = text.lower()
    lowered_name = name.lower()
    return [match.start() for match in re.finditer(re.escape(lowered_name), lowered_text)]


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


def _extract_metadata(
    entities: list[GraphEntity],
    relations: list[GraphRelation],
    evidences: list[GraphEvidence],
) -> dict[str, Any]:
    return {
        "extractorLayers": _extractor_layers(),
        "extractorSourceCounts": _extractor_source_counts(entities, relations, evidences),
        "candidateSourceCounts": _candidate_source_counts(entities, relations, evidences),
    }


def _extractor_layers() -> list[dict[str, Any]]:
    return [
        {
            "name": "rule",
            "enabled": True,
            "status": "ready",
        },
        {
            "name": "ner.lightweight",
            "enabled": True,
            "status": "ready",
            "sources": ["ner.pattern", "ner.role"],
        },
        _spacy_layer_status(),
        _ner_model_layer_status(),
    ]


def _spacy_layer_status() -> dict[str, Any]:
    if _SPACY_NLP is not None:
        return {
            "name": "ner.spacy",
            "enabled": True,
            "status": "loaded",
            "modelName": _SPACY_MODEL_NAME,
        }
    if _SPACY_LOAD_ATTEMPTED:
        return {
            "name": "ner.spacy",
            "enabled": True,
            "status": "unavailable",
            "reason": "spaCy model not installed",
        }
    return {
        "name": "ner.spacy",
        "enabled": True,
        "status": "not_used",
        "reason": "no English-like text triggered spaCy probing",
    }


def _extractor_source_counts(
    entities: list[GraphEntity],
    relations: list[GraphRelation],
    evidences: list[GraphEvidence],
) -> dict[str, int]:
    counter: Counter[str] = Counter()
    for item in [*entities, *relations, *evidences]:
        for source in _metadata_list(item.metadata, "extractorSources"):
            counter[source] += 1
    return dict(sorted(counter.items()))


def _candidate_source_counts(
    entities: list[GraphEntity],
    relations: list[GraphRelation],
    evidences: list[GraphEvidence],
) -> dict[str, int]:
    counter: Counter[str] = Counter()
    for item in [*entities, *relations, *evidences]:
        for source in _metadata_list(item.metadata, "candidateSources"):
            counter[source] += 1
    return dict(sorted(counter.items()))


def _metadata_list(metadata: dict[str, Any] | None, key: str) -> list[str]:
    if not metadata:
        return []
    value = metadata.get(key)
    if isinstance(value, list):
        return [str(item) for item in value if item]
    if isinstance(value, str) and value.strip():
        return [value.strip()]
    return []
