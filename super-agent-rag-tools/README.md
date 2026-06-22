# super-agent-rag-tools

`super-agent-rag-tools` 是 super-agent 的 Python RAG 工具服务。

当前已完成阶段 B/C/E：先建立 Java 调 Python 的工具服务闭环，再把 Java RAG 主链路中的 rerank 阶段切到本服务，并将文档解析主链路切换为 Python `/document/parse`。

- `GET /health`
- `POST /rerank`
- `POST /document/parse`
- `POST /citation/repair`

Java 仍然负责 RAG 主链路编排、检索融合、Parent/Child 切块、证据组织、回答生成和引用事件发送；Python 只作为工具服务承接 Java 不适合直接实现的解析、OCR、layout、table/image、rerank、citation repair 等能力。当前 `/rerank` 是唯一 rerank 通道，`/document/parse` 是唯一文档解析通道；旧 HTTP rerank 和 Tika 主解析链路都已删除，不做旧逻辑 fallback。

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

`/rerank` 当前使用无外部模型依赖的轻量词法排序，便于本地验证 Java -> Python rerank 闭环。后续可以在 Python 内部替换为 BGE/cross-encoder 等真实模型，但 Java 主链路仍保持 `RagRetrievalEngine -> RagRerankService -> RagToolsClient`。

`/document/parse` 当前支持 TXT、MD、HTML、PDF、DOCX。DOC 不启用旧解析链路，需转成 DOCX 后上传。解析结果会返回 `parsedText`、`artifacts`、`blocks`、`structureNodes`；Java 负责保存解析产物、生成 Parent/Child、写向量和 ES 索引。

`/citation/repair` 当前使用轻量词法相似度把回答句子关联到候选证据，返回 `answerSegment`、`quoteText`、`chunkId`、`pageNo`、`bboxJson` 和 `score`。Java 负责决定何时调用、如何过滤引用、如何发送给前端和归档。后续可以在 Python 内部替换为 sentence embedding 或 cross-encoder 匹配模型。
