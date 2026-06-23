import unittest

from rag_tools.graph_extract import extract_graph
from rag_tools.schemas.graph_extract import GraphChunk, GraphExtractRequest


class GraphExtractTest(unittest.TestCase):
    def test_extract_graph_merges_aliases_and_builds_relation_community(self) -> None:
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

        entity_by_id = {entity.id: entity for entity in response.entities}
        super_agent = self._entity_by_alias(response.entities, "超级智能体")
        self.assertEqual("SuperAgent", super_agent.name)
        self.assertIn("超级智能体", super_agent.aliases)
        self.assertEqual([1001, 1002], sorted(super_agent.source_chunk_ids))
        self.assertGreaterEqual(super_agent.confidence, 0.6)

        relation_pairs = {
            (
                entity_by_id[relation.source_entity_id].name,
                relation.relation_type,
                entity_by_id[relation.target_entity_id].name,
            )
            for relation in response.relations
        }
        self.assertIn(("SuperAgent", "CALLS", "RagTools"), relation_pairs)
        self.assertIn(("SuperAgent", "DEPENDS_ON", "GraphRAG"), relation_pairs)

        self.assertTrue(response.communities)
        community = response.communities[0]
        self.assertIn("GraphRAG", community.title)
        self.assertIn("主要关系", community.summary)
        self.assertEqual("networkx.greedy_modularity", community.metadata.get("communityAlgorithm"))
        self.assertGreaterEqual(community.metadata.get("relationCount"), 2)

    def test_extract_graph_uses_explicit_alias_patterns_for_entities_and_relations(self) -> None:
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

        entity_by_id = {entity.id: entity for entity in response.entities}
        relation_pairs = {
            (
                entity_by_id[relation.source_entity_id].name,
                relation.relation_type,
                entity_by_id[relation.target_entity_id].name,
            )
            for relation in response.relations
        }
        self.assertIn(("SuperAgent", "CALLS", "RagTools"), relation_pairs)

    def test_extract_graph_captures_policy_action_relation_words(self) -> None:
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
        self.assertIn(("发布负责人", "TRIGGERS", "回滚"), relation_pairs)
        self.assertIn(("值班 SRE", "EXECUTES", "流量切换"), relation_pairs)
        self.assertIn(("信息安全部", "APPROVES", "高敏感数据"), relation_pairs)
        self.assertIn(("AuditTrail", "RECORDS", "权限申请"), relation_pairs)

    def _entity_by_alias(self, entities, alias: str):
        for entity in entities:
            if alias in entity.aliases:
                return entity
        self.fail(f"Missing entity alias: {alias}")


if __name__ == "__main__":
    unittest.main()
