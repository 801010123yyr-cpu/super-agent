import unittest
from unittest.mock import patch

import rag_tools.graph_extract as graph_extract
from rag_tools.graph_extract import extract_graph
from rag_tools.schemas.graph_extract import GraphChunk, GraphExtractRequest


class GraphExtractTest(unittest.TestCase):
    def setUp(self) -> None:
        self._env_patch = patch.dict(
            "os.environ",
            {"SUPER_AGENT_GRAPH_RAG_NER_MODEL_ENABLED": "false"},
            clear=False,
        )
        self._env_patch.start()

    def tearDown(self) -> None:
        self._env_patch.stop()
        graph_extract._NER_MODEL_PIPELINE = None
        graph_extract._NER_MODEL_LOAD_ATTEMPTED = False
        graph_extract._NER_MODEL_STATUS = None
        graph_extract._NER_MODEL_CONFIG_KEY = None

    def test_extract_graph_merges_aliases_without_relation_word_edges(self) -> None:
        response = extract_graph(
            GraphExtractRequest(
                documentId=10,
                taskId=20,
                chunks=[
                    GraphChunk(
                        chunkId=1001,
                        chunkNo=1,
                        title="GraphRAG 架构",
                        sectionPath="架构/GraphRAG",
                        text="超级智能体（SuperAgent）调用 RagTools 服务。",
                        contentWithWeight="超级智能体（SuperAgent）调用 RagTools 服务。",
                    ),
                    GraphChunk(
                        chunkId=1002,
                        chunkNo=2,
                        title="GraphRAG 架构",
                        sectionPath="架构/GraphRAG",
                        text="SuperAgent 依赖 GraphRAG。RagTools 支持 GraphRAG 抽取。",
                        contentWithWeight="SuperAgent 依赖 GraphRAG。RagTools 支持 GraphRAG 抽取。",
                    ),
                ],
            )
        )

        super_agent = self._entity_by_alias(response.entities, "超级智能体")
        self.assertEqual("SuperAgent", super_agent.name)
        self.assertIn("超级智能体", super_agent.aliases)
        self.assertEqual([1001, 1002], sorted(super_agent.source_chunk_ids))
        self.assertGreaterEqual(super_agent.confidence, 0.6)

        self.assertFalse(response.relations)
        self.assertFalse(response.communities)
        self.assert_no_lexical_relation_sources(response)

    def test_extract_graph_uses_explicit_alias_patterns_for_entities(self) -> None:
        response = extract_graph(
            GraphExtractRequest(
                documentId=10,
                taskId=21,
                chunks=[
                    GraphChunk(
                        chunkId=2001,
                        chunkNo=1,
                        title="GraphRAG 抽取链路",
                        sectionPath="架构/GraphRAG",
                        text="超级智能体简称SA，RagTools 又称 RT。SA 调用 RT 完成 GraphRAG 候选抽取。",
                        contentWithWeight="超级智能体简称SA，RagTools 又称 RT。SA 调用 RT 完成 GraphRAG 候选抽取。",
                    ),
                    GraphChunk(
                        chunkId=2002,
                        chunkNo=2,
                        title="GraphRAG 抽取链路",
                        sectionPath="架构/GraphRAG",
                        text="SuperAgent 是超级智能体的英文名。RagTools 支持 GraphRAG 实体关系抽取。",
                        contentWithWeight="SuperAgent 是超级智能体的英文名。RagTools 支持 GraphRAG 实体关系抽取。",
                    ),
                ],
            )
        )

        super_agent = self._entity_by_alias(response.entities, "SA")
        self.assertEqual("SuperAgent", super_agent.name)
        self.assertIn("超级智能体", super_agent.aliases)
        rag_tools = self._entity_by_alias(response.entities, "RT")
        self.assertEqual("RagTools", rag_tools.name)

        self.assertFalse(response.relations)
        self.assert_no_lexical_relation_sources(response)

    def test_extract_graph_handles_punctuated_alias_markers(self) -> None:
        response = extract_graph(
            GraphExtractRequest(
                documentId=10,
                taskId=211,
                chunks=[
                    GraphChunk(
                        chunkId=2101,
                        chunkNo=1,
                        title="系统名称",
                        sectionPath="审计/系统名称",
                        text=(
                            "审计系统，又称 `AuditTrail`，是客户数据访问控制链路中的安全审计平台。"
                            "业务人员在沟通中经常把 `AuditTrail` 简称为审计系统。"
                        ),
                        contentWithWeight=(
                            "审计系统，又称 `AuditTrail`，是客户数据访问控制链路中的安全审计平台。"
                            "业务人员在沟通中经常把 `AuditTrail` 简称为审计系统。"
                        ),
                    )
                ],
            )
        )

        audit_alias_entities = [
            entity
            for entity in response.entities
            if (
                entity.name == "AuditTrail" and "审计系统" in entity.aliases
            ) or (
                entity.name == "审计系统" and "AuditTrail" in entity.aliases
            )
        ]
        self.assertTrue(audit_alias_entities)
        self.assertFalse(any(entity.name == "为审计系统" for entity in response.entities))

    def test_extract_graph_ignores_weighted_content_labels_and_embedded_questions(self) -> None:
        text = (
            "[TITLE] `AuditTrail` 需记录以下权限相关行为： "
            "[SECTION] `AuditTrail` 需记录以下权限相关行为： "
            "[CHUNK_TYPE] TEXT "
            "[KEYWORDS] `AuditTrail` 需记录以下权限相关行为：；权限申请；权限审批；权限回收；临时权限延长 "
            "[QUESTIONS] 关于`AuditTrail` 需记录以下权限相关行为：的核心内容是什么？；"
            "`AuditTrail` 需记录以下权限相关行为：有哪些要求或注意事项？ "
            "[CONTENT] section: `AuditTrail` 需记录以下权限相关行为：\n"
            "- 权限申请。\n- 权限审批。\n- 权限回收。\n- 临时权限延长。"
        )
        response = extract_graph(
            GraphExtractRequest(
                documentId=10,
                taskId=212,
                chunks=[
                    GraphChunk(
                        chunkId=2121,
                        chunkNo=1,
                        title="`AuditTrail` 需记录以下权限相关行为：",
                        sectionPath="`AuditTrail` 需记录以下权限相关行为：",
                        text=text,
                        contentWithWeight=text,
                    )
                ],
            )
        )

        entity_names = {entity.name for entity in response.entities}
        self.assertIn("AuditTrail", entity_names)
        self.assertIn("权限申请", entity_names)
        self.assertTrue({"TITLE", "CONTENT", "KEYWORDS", "QUESTIONS", "CHUNK_TYPE", "TEXT"}.isdisjoint(entity_names))
        self.assertFalse(any("哪些" in name or "什么" in name or name.startswith("以下") for name in entity_names))

        entity_by_id = {entity.id: entity for entity in response.entities}
        relation_pairs = {
            (
                entity_by_id[relation.source_entity_id].name,
                relation.relation_type,
                entity_by_id[relation.target_entity_id].name,
            )
            for relation in response.relations
        }
        self.assertIn(("AuditTrail", "RECORDS", "权限申请"), relation_pairs)
        self.assertFalse(any("TITLE" in pair or "CONTENT" in pair or "QUESTIONS" in pair for pair in relation_pairs))
        self.assertFalse(any("[QUESTIONS]" in evidence.quote_text or "核心内容是什么" in evidence.quote_text for evidence in response.evidences))
        self.assertTrue(all(evidence.metadata.get("extractorSources") for evidence in response.evidences))

    def test_extract_graph_marks_ner_sources_and_keeps_evidence_grounded(self) -> None:
        with patch.dict("os.environ", {"SUPER_AGENT_GRAPH_RAG_NER_MODEL_ENABLED": "false"}, clear=False):
            response = extract_graph(
                GraphExtractRequest(
                    documentId=10,
                    taskId=214,
                    chunks=[
                        GraphChunk(
                            chunkId=2141,
                            chunkNo=1,
                            title="组件职责",
                            sectionPath="平台/组件职责",
                            text="BillingGateway 调用 OrderService。支付负责人负责发布审批。",
                            contentWithWeight=(
                                "[TITLE] 组件职责 [QUESTIONS] BillingGateway 有哪些职责？ "
                                "[CONTENT] BillingGateway 调用 OrderService。支付负责人负责发布审批。"
                            ),
                        )
                    ],
                )
            )

        billing_gateway = next(entity for entity in response.entities if entity.name == "BillingGateway")
        order_service = next(entity for entity in response.entities if entity.name == "OrderService")
        payment_owner = next(entity for entity in response.entities if entity.name == "支付负责人")
        self.assertIn("ner", billing_gateway.metadata.get("extractorSources"))
        self.assertIn("ner", order_service.metadata.get("extractorSources"))
        self.assertIn("ner", payment_owner.metadata.get("extractorSources"))

        self.assertFalse(response.relations)
        self.assert_no_lexical_relation_sources(response)
        self.assertTrue(all("[TITLE]" not in evidence.quote_text and "[QUESTIONS]" not in evidence.quote_text for evidence in response.evidences))
        self.assertTrue(all(evidence.metadata.get("sourceType") in {"rule", "ner"} for evidence in response.evidences))
        self.assertIn("extractorLayers", response.metadata)
        self.assertIn("extractorSourceCounts", response.metadata)
        self.assertTrue(any(layer.get("name") == "ner.model" and layer.get("status") == "disabled" for layer in response.metadata.get("extractorLayers")))

    def test_extract_graph_uses_configurable_model_ner_candidates_when_enabled(self) -> None:
        class FakeNerPipeline:
            def __call__(self, text: str):
                return [
                    {"word": "新风控平台", "entity_group": "ORG"},
                    {"word": "RiskGateway", "entity_group": "SYSTEM"},
                ]

        with patch.dict(
            "os.environ",
            {
                "SUPER_AGENT_GRAPH_RAG_NER_MODEL_ENABLED": "true",
                "SUPER_AGENT_GRAPH_RAG_NER_MODEL_NAME": "local-test-ner",
            },
            clear=False,
        ), patch.object(graph_extract, "_load_ner_model_pipeline", return_value=FakeNerPipeline()):
            response = extract_graph(
                GraphExtractRequest(
                    documentId=10,
                    taskId=215,
                    chunks=[
                        GraphChunk(
                            chunkId=2151,
                            chunkNo=1,
                            title="风控链路",
                            sectionPath="平台/风控链路",
                            text="新风控平台调用 RiskGateway 完成交易风控。",
                            contentWithWeight="新风控平台调用 RiskGateway 完成交易风控。",
                        )
                    ],
                )
            )

        platform = next(entity for entity in response.entities if entity.name == "新风控平台")
        gateway = next(entity for entity in response.entities if entity.name == "RiskGateway")
        self.assertIn("ner.model.transformers.org", platform.metadata.get("candidateSources"))
        self.assertIn("ner.model.transformers.system", gateway.metadata.get("candidateSources"))
        self.assertIn("ner", platform.metadata.get("extractorSources"))
        self.assertIn("ner", gateway.metadata.get("extractorSources"))
        self.assertTrue(any(evidence.entity_id == platform.id and evidence.quote_text for evidence in response.evidences))

        model_layer = next(layer for layer in response.metadata.get("extractorLayers") if layer.get("name") == "ner.model")
        self.assertEqual("loaded", model_layer.get("status"))
        self.assertEqual("local-test-ner", model_layer.get("modelName"))
        self.assertGreaterEqual(response.metadata.get("candidateSourceCounts", {}).get("ner.model.transformers.org", 0), 1)

    def test_extract_graph_captures_permission_related_recording_phrase_across_chunks(self) -> None:
        response = extract_graph(
            GraphExtractRequest(
                documentId=10,
                taskId=213,
                chunks=[
                    GraphChunk(
                        chunkId=2131,
                        chunkNo=1,
                        title="一、适用范围",
                        sectionPath="一、适用范围",
                        text=(
                            "本文档规定 `AuditTrail` 在客户数据权限流转场景中的审计留痕要求。"
                            "该规范适用于权限申请、权限审批、权限回收、临时权限延长和异常权限复核。"
                        ),
                        contentWithWeight=(
                            "本文档规定 `AuditTrail` 在客户数据权限流转场景中的审计留痕要求。"
                            "该规范适用于权限申请、权限审批、权限回收、临时权限延长和异常权限复核。"
                        ),
                    ),
                    GraphChunk(
                        chunkId=2132,
                        chunkNo=2,
                        title="`AuditTrail` 需记录以下权限相关行为：",
                        sectionPath="`AuditTrail` 需记录以下权限相关行为：",
                        text="`AuditTrail` 需记录以下权限相关行为：",
                        contentWithWeight=(
                            "[TITLE] `AuditTrail` 需记录以下权限相关行为： "
                            "[SECTION] `AuditTrail` 需记录以下权限相关行为： "
                            "[CHUNK_TYPE] TEXT [CONTENT] `AuditTrail` 需记录以下权限相关行为："
                        ),
                    ),
                    GraphChunk(
                        chunkId=2133,
                        chunkNo=3,
                        title="权限清单",
                        sectionPath="`AuditTrail` 需记录以下权限相关行为：",
                        text="- 权限申请。\n- 权限审批。\n- 权限回收。\n- 临时权限延长。",
                        contentWithWeight="- 权限申请。\n- 权限审批。\n- 权限回收。\n- 临时权限延长。",
                    ),
                ],
            )
        )

        entity_by_id = {entity.id: entity for entity in response.entities}
        relation_pairs = {
            (
                entity_by_id[relation.source_entity_id].name,
                relation.relation_type,
                entity_by_id[relation.target_entity_id].name,
            )
            for relation in response.relations
        }
        self.assertIn(("AuditTrail", "RECORDS", "权限申请"), relation_pairs)
        self.assertIn(("AuditTrail", "RECORDS", "权限审批"), relation_pairs)
        self.assertIn(("AuditTrail", "RECORDS", "权限回收"), relation_pairs)
        self.assertIn(("AuditTrail", "RECORDS", "临时权限延长"), relation_pairs)

    def test_extract_graph_does_not_create_relations_from_plain_action_sentences(self) -> None:
        response = extract_graph(
            GraphExtractRequest(
                documentId=10,
                taskId=22,
                chunks=[
                    GraphChunk(
                        chunkId=3001,
                        chunkNo=1,
                        title="发布负责人",
                        sectionPath="发布回滚/发布负责人",
                        text="发布负责人触发回滚。值班 SRE 执行流量切换。",
                        contentWithWeight="发布负责人触发回滚。值班 SRE 执行流量切换。",
                    ),
                    GraphChunk(
                        chunkId=3002,
                        chunkNo=2,
                        title="客户数据审批",
                        sectionPath="数据治理/信息安全部",
                        text="信息安全部审批高敏感数据。AuditTrail 记录权限申请、审批、回收和延长。",
                        contentWithWeight="信息安全部审批高敏感数据。AuditTrail 记录权限申请、审批、回收和延长。",
                    ),
                ],
            )
        )

        entity_by_id = {entity.id: entity for entity in response.entities}
        relation_pairs = {
            (
                entity_by_id[relation.source_entity_id].name,
                relation.relation_type,
                entity_by_id[relation.target_entity_id].name,
            )
            for relation in response.relations
        }
        self.assertNotIn(("发布负责人", "TRIGGERS", "回滚"), relation_pairs)
        self.assertNotIn(("值班 SRE", "EXECUTES", "流量切换"), relation_pairs)
        self.assertNotIn(("信息安全部", "APPROVES", "高敏感数据"), relation_pairs)
        self.assertNotIn(("AuditTrail", "RECORDS", "权限申请"), relation_pairs)
        self.assertNotIn(("AuditTrail", "APPROVES", "回收"), relation_pairs)
        self.assert_no_lexical_relation_sources(response)

    def test_extract_graph_lexical_relation_words_do_not_create_edges(self) -> None:
        response = extract_graph(
            GraphExtractRequest(
                documentId=10,
                taskId=221,
                chunks=[
                    GraphChunk(
                        chunkId=3101,
                        chunkNo=1,
                        title="适用范围",
                        sectionPath="一、适用范围",
                        text=(
                            "本文档规定 `AuditTrail` 在客户数据权限流转场景中的审计留痕要求。"
                            "该规范适用于权限申请、权限审批、权限回收、临时权限延长和异常权限复核。"
                        ),
                        contentWithWeight=(
                            "本文档规定 `AuditTrail` 在客户数据权限流转场景中的审计留痕要求。"
                            "该规范适用于权限申请、权限审批、权限回收、临时权限延长和异常权限复核。"
                        ),
                    ),
                    GraphChunk(
                        chunkId=3102,
                        chunkNo=2,
                        title="系统职责",
                        sectionPath="二、系统职责",
                        text=(
                            "审计系统负责接收权限变更事件、数据访问事件、导出事件和共享链接事件，"
                            "并把事件写入统一审计流水。审计系统本身不审批权限，也不直接回收权限。"
                        ),
                        contentWithWeight=(
                            "审计系统负责接收权限变更事件、数据访问事件、导出事件和共享链接事件，"
                            "并把事件写入统一审计流水。审计系统本身不审批权限，也不直接回收权限。"
                        ),
                    ),
                ],
            )
        )

        entity_by_id = {entity.id: entity for entity in response.entities}
        relation_pairs = {
            (
                entity_by_id[relation.source_entity_id].name,
                relation.relation_type,
                entity_by_id[relation.target_entity_id].name,
            )
            for relation in response.relations
        }
        self.assertNotIn(("AuditTrail", "APPROVES", "临时权限延长"), relation_pairs)
        self.assertNotIn(("审计系统", "RESPONSIBLE_FOR", "数据访问事件"), relation_pairs)
        self.assertNotIn(("AuditTrail", "RESPONSIBLE_FOR", "事件"), relation_pairs)
        self.assert_no_lexical_relation_sources(response)

    def test_extract_graph_ignores_generic_metadata_keyword_event_as_relation_target(self) -> None:
        text = (
            "审计系统负责接收权限变更事件、数据访问事件、导出事件和共享链接事件，"
            "并把事件写入统一审计流水。审计系统本身不审批权限，也不直接回收权限。"
        )
        response = extract_graph(
            GraphExtractRequest(
                documentId=10,
                taskId=222,
                chunks=[
                    GraphChunk(
                        chunkId=3103,
                        chunkNo=6,
                        title="二、系统职责",
                        sectionPath="二、系统职责",
                        text=text,
                        contentWithWeight=(
                            "[TITLE]\n二、系统职责\n\n"
                            "[SECTION]\n二、系统职责\n\n"
                            "[CHUNK_TYPE]\nTEXT\n\n"
                            "[KEYWORDS]\n二、系统职责；审计系统负责接收；事件；数据访问事件；导出事件和共享链\n\n"
                            "[QUESTIONS]\n关于二、系统职责的核心内容是什么？\n\n"
                            "[CONTENT]\nsection: 二、系统职责\n"
                            f"type: TEXT\n{text}"
                        ),
                        metadata={
                            "keywords": [
                                "二、系统职责",
                                "审计系统负责接收",
                                "事件",
                                "数据访问事件",
                                "导出事件和共享链",
                            ]
                        },
                    )
                ],
            )
        )

        entity_by_id = {entity.id: entity for entity in response.entities}
        entity_names = {entity.name for entity in response.entities}
        relation_pairs = {
            (
                entity_by_id[relation.source_entity_id].name,
                relation.relation_type,
                entity_by_id[relation.target_entity_id].name,
            )
            for relation in response.relations
        }
        self.assertNotIn("事件", entity_names)
        self.assertNotIn(("审计系统", "RESPONSIBLE_FOR", "事件"), relation_pairs)
        self.assert_no_lexical_relation_sources(response)

    def test_extract_graph_captures_o6_structured_policy_rows(self) -> None:
        response = extract_graph(
            GraphExtractRequest(
                documentId=10,
                taskId=23,
                chunks=[
                    GraphChunk(
                        chunkId=4001,
                        chunkNo=1,
                        title="发布职责",
                        sectionPath="发布回滚/角色职责",
                        text=(
                            "| 发布负责人 | 对应研发团队 | 整体协调发布流程、确认发布内容、触发回滚 |\n"
                            "| 值班 SRE | SRE 团队 | 控制发布窗口、观察监控、执行流量切换 |\n"
                            "| DBA | DBA 团队 | 审核和执行数据库脚本、保障数据恢复路径 |"
                        ),
                        contentWithWeight=(
                            "| 发布负责人 | 对应研发团队 | 整体协调发布流程、确认发布内容、触发回滚 |\n"
                            "| 值班 SRE | SRE 团队 | 控制发布窗口、观察监控、执行流量切换 |\n"
                            "| DBA | DBA 团队 | 审核和执行数据库脚本、保障数据恢复路径 |"
                        ),
                    ),
                    GraphChunk(
                        chunkId=4002,
                        chunkNo=2,
                        title="数据分级",
                        sectionPath="数据治理/数据分级",
                        text="| L4 | 高敏感信息 | 身份证号、银行卡号、完整通话录音、原始会话日志、隐私标签数据 | 严格受控，禁止本地导出 |",
                        contentWithWeight="| L4 | 高敏感信息 | 身份证号、银行卡号、完整通话录音、原始会话日志、隐私标签数据 | 严格受控，禁止本地导出 |",
                    ),
                    GraphChunk(
                        chunkId=4003,
                        chunkNo=3,
                        title="数据审批",
                        sectionPath="数据治理/L4",
                        text="| L4 | 部门负责人 + 数据治理负责人 + 信息安全部三级审批 | 最长 15 天 |",
                        contentWithWeight="| L4 | 部门负责人 + 数据治理负责人 + 信息安全部三级审批 | 最长 15 天 |",
                    ),
                    GraphChunk(
                        chunkId=4004,
                        chunkNo=4,
                        title="审计留痕",
                        sectionPath="数据治理/AuditTrail",
                        text="`AuditTrail` 需记录以下行为：",
                        contentWithWeight="`AuditTrail` 需记录以下行为：",
                    ),
                    GraphChunk(
                        chunkId=4005,
                        chunkNo=5,
                        title="审计留痕",
                        sectionPath="数据治理/AuditTrail",
                        text="- 用户登录、认证失败、二次验证。\n- SQL 查询、日志检索、接口调用。",
                        contentWithWeight="- 用户登录、认证失败、二次验证。\n- SQL 查询、日志检索、接口调用。",
                    ),
                    GraphChunk(
                        chunkId=4006,
                        chunkNo=6,
                        title="审计留痕",
                        sectionPath="数据治理/AuditTrail",
                        text="- 权限申请、审批、回收、延长。\n- 文件下载、报表导出、共享链接生成。",
                        contentWithWeight="- 权限申请、审批、回收、延长。\n- 文件下载、报表导出、共享链接生成。",
                    ),
                    GraphChunk(
                        chunkId=4007,
                        chunkNo=7,
                        title="允许存储平台",
                        sectionPath="数据治理/允许存储平台",
                        text="客户数据原则上仅允许存放于公司批准的平台：",
                        contentWithWeight="客户数据原则上仅允许存放于公司批准的平台：",
                    ),
                    GraphChunk(
                        chunkId=4008,
                        chunkNo=8,
                        title="允许存储平台",
                        sectionPath="数据治理/允许存储平台",
                        text="- 加密文件库：`VaultDocs`\n- 受控分析环境：`DataCleanRoom`",
                        contentWithWeight="- 加密文件库：`VaultDocs`\n- 受控分析环境：`DataCleanRoom`",
                    ),
                    GraphChunk(
                        chunkId=4009,
                        chunkNo=9,
                        title="角色与职责矩阵",
                        sectionPath="数据治理/角色与职责矩阵",
                        text="| 系统管理员 | 配置权限组、保留操作日志、回收异常权限 | 权限变更记录 |",
                        contentWithWeight="| 系统管理员 | 配置权限组、保留操作日志、回收异常权限 | 权限变更记录 |",
                    ),
                ],
            )
        )

        entity_by_id = {entity.id: entity for entity in response.entities}
        relation_pairs = {
            (
                entity_by_id[relation.source_entity_id].name,
                relation.relation_type,
                entity_by_id[relation.target_entity_id].name,
            )
            for relation in response.relations
        }
        self.assertIn(("发布负责人", "TRIGGERS", "回滚"), relation_pairs)
        self.assertIn(("值班 SRE", "EXECUTES", "流量切换"), relation_pairs)
        self.assertIn(("DBA", "EXECUTES", "数据库脚本"), relation_pairs)
        self.assertIn(("L4 数据", "APPROVES", "信息安全部"), relation_pairs)
        self.assertIn(("AuditTrail", "RECORDS", "权限申请"), relation_pairs)
        self.assertIn(("客户数据", "STORES", "VaultDocs"), relation_pairs)
        self.assertIn(("客户数据", "STORES", "DataCleanRoom"), relation_pairs)
        self.assertIn(("系统管理员", "REVOKES", "异常权限"), relation_pairs)
        self.assert_no_lexical_relation_sources(response)

        l4_data = self._entity_by_alias(response.entities, "高敏感信息")
        self.assertEqual("L4 数据", l4_data.name)
        self.assertIn("L4", l4_data.aliases)
        dba = self._entity_by_alias(response.entities, "DBA 团队")
        self.assertEqual("DBA", dba.name)
        audit_trail_relation_ids = {
            relation.id
            for relation in response.relations
            if (
                entity_by_id[relation.source_entity_id].name,
                relation.relation_type,
                entity_by_id[relation.target_entity_id].name,
            ) == ("AuditTrail", "RECORDS", "权限申请")
        }
        audit_quotes = [
            evidence.quote_text
            for evidence in response.evidences
            if evidence.relation_id in audit_trail_relation_ids
        ]
        self.assertTrue(any("AuditTrail" in quote and "权限申请" in quote and "审批" in quote and "回收" in quote for quote in audit_quotes))

        storage_relation_ids = {
            relation.id
            for relation in response.relations
            if (
                entity_by_id[relation.source_entity_id].name,
                relation.relation_type,
                entity_by_id[relation.target_entity_id].name,
            ) == ("客户数据", "STORES", "DataCleanRoom")
        }
        storage_quotes = [
            evidence.quote_text
            for evidence in response.evidences
            if evidence.relation_id in storage_relation_ids
        ]
        self.assertTrue(any("客户数据" in quote and "存放" in quote and "DataCleanRoom" in quote for quote in storage_quotes))

    def _entity_by_alias(self, entities, alias: str):
        for entity in entities:
            if alias in entity.aliases:
                return entity
        self.fail(f"Missing entity alias: {alias}")

    def assert_no_lexical_relation_sources(self, response) -> None:
        forbidden_sources = {"rule.relationWord", "rule.coOccurrence"}
        for relation in response.relations:
            self.assertTrue(forbidden_sources.isdisjoint(set(relation.metadata.get("candidateSources") or [])))
        for evidence in response.evidences:
            self.assertTrue(forbidden_sources.isdisjoint(set(evidence.metadata.get("candidateSources") or [])))


if __name__ == "__main__":
    unittest.main()
