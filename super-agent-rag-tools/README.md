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

`/document/parse` 是 Java 调用的唯一文档解析入口，但入口内部按文件类型固定路由，不提供用户可选 provider 开关。TXT、MD、HTML 走 `native_text` 本地确定性结构解析，适合 Markdown 标题、纯文本段落和简单 HTML；PDF、DOCX、XLSX、PNG、JPG/JPEG、BMP、GIF 走阿里云 Document Mind 文档解析（大模型版），用于 OCR、layout、reading order、复杂表格和图片结构。DOC/XLS 不启用旧解析链路，需先转成 DOCX/XLSX 后上传。Document Mind 会把 OCR、layout、reading order、table、figure 结果映射为统一 `DocumentBlock`，并保存 `ALIYUN_DOCMIND_JSON` 原始解析产物以及标准 `MARKDOWN`、`JSON`、`LAYOUT_JSON` artifact；`native_text` 会稳定输出标准 `MARKDOWN` 和 `JSON` artifact，不依赖阿里云 AccessKey。Document Mind 最小配置只需要 `ALIBABA_CLOUD_ACCESS_KEY_ID` 和 `ALIBABA_CLOUD_ACCESS_KEY_SECRET`；endpoint、超时、VLM 增强、公式增强和 HTML 表格输出都有默认值，日常不需要配置。PDF/DOCX/XLSX/图片解析时 SDK 或 AccessKey 缺失会直接返回明确错误，不回退 Tika/PyMuPDF/纯文本解析。解析结果会返回 `parsedText`、`artifacts`、`blocks`、`structureNodes`；Java 负责保存解析产物、生成 Parent/Child、写向量和 ES 索引。

`/citation/repair` 当前使用 cross-encoder 进行答案句和候选证据的语义匹配，默认复用 `RAG_TOOLS_RERANK_MODEL`，也可通过 `RAG_TOOLS_CITATION_MODEL` 单独覆盖。接口返回 `answerSegment`、`quoteText`、`chunkId`、`pageNo`、`bboxJson` 和 `score`。Java 负责决定何时调用、如何过滤引用、如何发送给前端和归档。

`/graph/extract` 当前使用增强版轻量抽取从 chunk 中抽取实体、关系、证据和社区摘要：Python 会输出实体别名、置信度、关系短语、来源 chunk 和 NetworkX community detection 结果；Java 负责 canonical entity 合并、关系去重、保存到 `KgEntity`、`KgRelation`、`KgEvidence`、`KgCommunity` 对应表，并把 canonical KG 产物投影为 `GRAPH_ENTITY`、`GRAPH_RELATION`、`GRAPH_COMMUNITY` 三类 typed document chunk，复用现有 PGVector 与 ES/BM25 统一检索面。第二阶段 O6 已补同义实体合并、community report 检索、关系类问题直召、别名命中、typed KG chunk 投影、Java 构建租约/checkpoint/有限重试/独立 timeout 和 Java PageRank/rankBoost 第一版；后续仍要继续补 LLM/NER 抽取、更强 entity resolution、模型生成 community report、跨文档图谱和 LLM query rewrite。

`/raptor/build` 当前使用 `sentence-transformers` embedding 进行 balanced AHC 层次聚类，默认模型为 `BAAI/bge-small-zh-v1.5`，可通过 `RAG_TOOLS_EMBEDDING_MODEL` 覆盖；当 Java 请求 `llmSummaryEnabled=true` 且 rag-tools 配置了 OpenAI-compatible LLM 时，会调用 LLM 生成 RAPTOR 摘要并输出 `qualityScore`、`summaryStrategy`、`summaryQualitySignals`、`clusterQualitySignals`。Java 负责质量分过滤、保存 `RaptorNode`、写摘要向量和 ES/BM25 摘要索引，并在问答时通过 `RaptorRetrievalChannel` 先召回摘要节点再下钻原文 chunk。Python 不负责路由、融合、最终回答生成或 citation。

## 本地配置

rag-tools 会优先读取环境变量；没有环境变量时读取工作目录下的 `rag-tools.yaml`。也可通过 `RAG_TOOLS_CONFIG=/path/to/rag-tools.yaml` 指定配置文件。配置值支持 `${ENV_NAME}` 或 `${ENV_NAME:default}` 形式的环境变量占位符。

```yaml
ragTools:
  llm:
    baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1"
    apiKey: "${ALI_BAI_LIAN_API_KEY}"
    model: "qwen-plus-latest"
    timeoutSeconds: 30
```

等价环境变量：

```bash
export RAG_TOOLS_LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
export RAG_TOOLS_LLM_API_KEY=你的模型APIKey
export RAG_TOOLS_LLM_MODEL=qwen-plus-latest
export RAG_TOOLS_LLM_TIMEOUT_SECONDS=30
```

RAPTOR LLM 摘要提示词放在 `prompt/raptor-summary-system.txt` 和 `prompt/raptor-summary-user.txt`，不要直接写在 Python 代码里。
