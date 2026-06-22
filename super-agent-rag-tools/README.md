# super-agent-rag-tools

`super-agent-rag-tools` 是 super-agent 的 Python RAG 工具服务。

当前已经完成 RAGFlow 能力对齐改造的第一轮主链路收口：Java 负责 RAG 编排、索引构建、混合检索、证据组织和回答生成；Python 只作为工具服务提供 Java 不适合直接实现的文档解析、模型推理和图/摘要算法能力。

- `GET /health`
- `POST /rerank`
- `POST /document/parse`
- `POST /citation/repair`
- `POST /graph/extract`
- `POST /raptor/build`

Java 仍然负责 RAG 主链路编排、检索融合、Parent/Child 切块、证据组织、回答生成和引用事件发送；Python 只作为工具服务承接 Java 不适合直接实现的解析、OCR、layout、table/image、rerank、citation repair、GraphRAG 抽取、RAPTOR 摘要树构建等能力。当前 `/rerank` 是唯一 rerank 通道，`/document/parse` 是唯一文档解析通道；旧 HTTP rerank 和 Tika 主解析链路都已删除，不保留旧逻辑兜底。

## 启动

推荐通过根目录 Docker Compose 启动：

```bash
docker compose up -d rag-tools
```

本地 Python 开发模式：

```bash
cd super-agent-rag-tools
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn rag_tools.main:app --host 0.0.0.0 --port 18080
```

## 验证

```bash
curl http://127.0.0.1:18080/health
```

```bash
curl -X POST http://127.0.0.1:18080/rerank \
  -H 'Content-Type: application/json' \
  -d '{"query":"报销时限","topK":2,"candidates":[{"id":"1","text":"出差结束后 10 个工作日内提交报销。"},{"id":"2","text":"生产发布需要准备回滚方案。"}]}'
```

```bash
CONTENT_BASE64=$(printf '# 报销制度\n\n出差结束后 10 个工作日内提交报销。' | base64)
curl -X POST http://127.0.0.1:18080/document/parse \
  -H 'Content-Type: application/json' \
  -d '{"fileName":"demo.md","fileType":"MD","mimeType":"text/markdown","contentBase64":"'"$CONTENT_BASE64"'"}'
```

```bash
curl -X POST http://127.0.0.1:18080/citation/repair \
  -H 'Content-Type: application/json' \
  -d '{"answer":"报销时限是出差结束后 10 个工作日内提交。","evidences":[{"id":"1","text":"出差结束后 10 个工作日内提交报销。","documentId":1,"chunkId":10,"pageNo":3,"bboxJson":"[0,0,10,10]"}]}'
```

```bash
curl -X POST http://127.0.0.1:18080/graph/extract \
  -H 'Content-Type: application/json' \
  -d '{"documentId":1,"taskId":1,"chunks":[{"chunkId":10,"chunkNo":1,"text":"报销制度规定，员工出差结束后 10 个工作日内提交报销。","sectionPath":"报销制度"}]}'
```

```bash
curl -X POST http://127.0.0.1:18080/raptor/build \
  -H 'Content-Type: application/json' \
  -d '{"documentId":1,"taskId":1,"chunks":[{"chunkId":10,"chunkNo":1,"text":"报销制度规定，员工出差结束后 10 个工作日内提交报销。","sectionPath":"报销制度"},{"chunkId":11,"chunkNo":2,"text":"生产发布需要准备回滚方案。","sectionPath":"发布流程"}],"maxClusterSize":6,"maxLevels":3}'
```

`/rerank` 当前使用无外部模型依赖的轻量词法排序，便于本地验证 Java -> Python rerank 闭环。后续可以在 Python 内部替换为 BGE/cross-encoder 等真实模型，但 Java 主链路仍保持 `RagRetrievalEngine -> RagRerankService -> RagToolsClient`。

`/document/parse` 当前支持 TXT、MD、HTML、PDF、DOCX。DOC 不启用旧解析链路，需转成 DOCX 后上传。解析结果会返回 `parsedText`、`artifacts`、`blocks`、`structureNodes`；Java 负责保存解析产物、生成 Parent/Child、写向量和 ES 索引。

`/citation/repair` 当前使用轻量词法相似度把回答句子关联到候选证据，返回 `answerSegment`、`quoteText`、`chunkId`、`pageNo`、`bboxJson` 和 `score`。Java 负责决定何时调用、如何过滤引用、如何发送给前端和归档。后续可以在 Python 内部替换为 sentence embedding 或 cross-encoder 匹配模型。

`/graph/extract` 当前使用轻量规则从 chunk 中抽取实体、关系、证据和社区摘要。Java 负责保存到 `KgEntity`、`KgRelation`、`KgEvidence`、`KgCommunity` 对应表，并在问答时通过 `GraphRagRetrievalChannel` 参与混合检索。后续可以在 Python 内部替换为 LLM 抽取、实体消歧和社区发现算法。

`/raptor/build` 当前使用轻量聚类和摘要生成 RAPTOR 摘要树。Java 负责保存 `RaptorNode`、写摘要向量，并在问答时通过 `RaptorRetrievalChannel` 先召回摘要节点再下钻原文 chunk。后续可以在 Python 内部替换为 embedding 聚类和强摘要模型。
