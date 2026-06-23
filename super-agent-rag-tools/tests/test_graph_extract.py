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

    def _entity_by_alias(self, entities, alias: str):
        for entity in entities:
            if alias in entity.aliases:
                return entity
        self.fail(f"Missing entity alias: {alias}")


if __name__ == "__main__":
    unittest.main()
