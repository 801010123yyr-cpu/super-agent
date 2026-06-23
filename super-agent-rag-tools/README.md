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

## 启动和验证入口

本文档只说明 Python 工具服务定位、接口和能力边界。项目启动、Python 本地开发模式、接口 curl 验证、Java/Vue 启动、完整 RAG 验证流程统一看：

```text
document/项目启动与完整验证手册-P.md
```

不要在本 README 里维护第二套启动命令，避免后续 AI 工具看到多个入口后产生不一致改造。

## 当前能力边界

`/rerank` 当前使用 `sentence-transformers` 的 cross-encoder 模型，默认模型为 `BAAI/bge-reranker-base`，可通过 `RAG_TOOLS_RERANK_MODEL` 覆盖。模型或依赖不可用时接口返回明确错误，不再回到轻量词法排序。Java 主链路仍保持 `RagRetrievalEngine -> RagRerankService -> RagToolsClient`。

`/document/parse` 当前支持 TXT、MD、HTML、PDF、DOCX、XLSX。DOC/XLS 不启用旧解析链路，需先转成 DOCX/XLSX 后上传。PDF 会尽量输出 text/table/image block，并在 PyMuPDF 能识别时输出表格 cell bbox；XLSX 会输出统一 `TABLE` block、`tableRows`、sheet/cell 来源坐标 metadata 和基础 merged-cell metadata，常见合并标题行会进入 `tableTitleRows`，真实表头仍作为 `tableRows` 首行，常见两层分组表头会扁平化为一行列名并保留 `tableHeaderRows`，纵向合并数据单元格会补值并标记 `mergedValueFilled`。TABLE block 会写入邻近 `tableContext` 并追加到 `contentWithWeight`，提升普通检索召回。O5 已补 PDF/XLSX 表格解析内存样例单测，测试命令统一看 `document/项目启动与完整验证手册-P.md`。解析结果会返回 `parsedText`、`artifacts`、`blocks`、`structureNodes`；Java 负责保存解析产物、生成 Parent/Child、写向量和 ES 索引。

`/citation/repair` 当前使用 cross-encoder 进行答案句和候选证据的语义匹配，默认复用 `RAG_TOOLS_RERANK_MODEL`，也可通过 `RAG_TOOLS_CITATION_MODEL` 单独覆盖。接口返回 `answerSegment`、`quoteText`、`chunkId`、`pageNo`、`bboxJson` 和 `score`。Java 负责决定何时调用、如何过滤引用、如何发送给前端和归档。

`/graph/extract` 当前使用增强版轻量抽取从 chunk 中抽取实体、关系、证据和社区摘要：Python 会输出实体别名、置信度、关系短语、来源 chunk 和 NetworkX community detection 结果；Java 负责 canonical entity 合并、关系去重、保存到 `KgEntity`、`KgRelation`、`KgEvidence`、`KgCommunity` 对应表，并把 canonical KG 产物投影为 `GRAPH_ENTITY`、`GRAPH_RELATION`、`GRAPH_COMMUNITY` 三类 typed document chunk，复用现有 PGVector 与 ES/BM25 统一检索面。第二阶段 O6 已补同义实体合并、community report 检索、关系类问题直召、别名命中、typed KG chunk 投影、Java 构建租约/checkpoint/有限重试/独立 timeout 和 Java PageRank/rankBoost 第一版；后续仍要继续补 LLM/NER 抽取、更强 entity resolution、模型生成 community report、跨文档图谱和 LLM query rewrite。

`/raptor/build` 当前使用 `sentence-transformers` embedding 进行贪心聚类，默认模型为 `BAAI/bge-small-zh-v1.5`，可通过 `RAG_TOOLS_EMBEDDING_MODEL` 覆盖；摘要仍是抽取式摘要，避免在 Python 侧引入第二套回答生成链路。Java 负责保存 `RaptorNode`、写摘要向量，并在问答时通过 `RaptorRetrievalChannel` 先召回摘要节点再下钻原文 chunk。
