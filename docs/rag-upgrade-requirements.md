# Advanced RAG 升级需求说明

> 目标：为 Agent Platform Gateway 的 RAG 查询链路补齐混合检索、响应回包与语义改写能力，为后续开发提供清晰的实现边界。

## 1. Hybrid Search 补全

- **背景**：`SparseMilvusVectorStore` 目前只是占位，启用 `enableSparseVector=true` 时仍旧只跑稠密检索。
- **目标**：直接基于 Milvus 稀疏/混合检索 API，实现“稠密 + 稀疏”一体化查询。
- **要求**：
  1. **写入**：当 `ChunkingConfig.enableSparseVector=true` 时，向量写入需要包含稀疏字段/全文字段，遵循 profile 中的 analyzer 设置；Milvus collection schema 中的 `sparse_vector`、`text` 字段需实际生效。
  2. **检索**：`SimilaritySearch` 在混合模式下构造 Milvus `SearchParam`（或 Hybrid Search API），并按设定权重融合稠密与稀疏得分；默认支持 topK、filter、阈值等参数。
  3. **结果**：保持 `VectorStoreService.SearchResult` 接口不变，但日志中需能区分稠密/稀疏得分，方便调试。

## 2. RAG Search 响应回包

- **背景**：`AdvancedRagStrategy` 当前在检索之后还会调用 LLM 生成答案，导致“只想看检索命中”场景不适配。
- **目标**：提供“仅返回检索结果”的模式，默认关闭 LLM 生成。
- **要求**：
  1. **新开关**：新增 `enableAnswerLLM`（或类似）参数，默认 `false`。当关闭时直接返回检索/重排/上下文补全后的结果列表，不再调用 `llmRouterService`。
  2. **回包结构**：`RagResponse.answer` 可为空或给出提示字符串，但重点是 `sources`、`rerankScore`、`metadata` 等原始信息完整暴露。
  3. **兼容性**：保留现有 LLM 生成路径（打开开关即可恢复），同时在日志里明确回包模式。

## 3. 语义改写剥离

- **背景**：`QueryRewriter` 只有一个固定 prompt 的关键词抽取，无法根据历史/复合问题做自适应改写。
- **目标**：将语义改写抽象为一个可配置的策略服务，通过一次 LLM 调用自动识别场景（无需调用方指定策略）并实现：
  - 有历史上下文 → 做指代消解补全
  - 复合问题 → 拆解子问题
  - 表达标准化 → 统一术语 / 同义词扩展
  - 简单查询 → 直接返回
- **要求**：
  1. **新模块**：引入 `SemanticRewriteService`（命名可调整），输入 `history`、`query`、`collection` 等信息，内部自行判别指代/复合/标准化/简单场景，输出结构化结果（JSON/对象）。
  2. **Prompt 规范**：在 system prompt 中明确决策树，并规定输出字段，如 `mode`、`final_query`、`sub_queries[]`、`clarified_query`、`rewrite_reason` 等，所有逻辑分支对调用方透明。
  3. **策略接入**：`AdvancedRagStrategy` 根据返回结构决定是否进行多查询扩展或直接使用 `final_query`，避免在策略内部散落逻辑，且无需调用方传入任何策略开关。

## 里程碑建议

1. **Milvus Hybrid Search**：补齐 collection schema、写入、检索逻辑，验证混合召回有效；
2. **RAG 回包开关**：实现仅检索模式，并为后续 UI/工具链暴露更多检索细节；
3. **Semantic Rewrite Service**：完成 prompt + 数据结构 + 策略接入，确保改写行为可监控、可迭代。

以上需求完成后，Advanced RAG 查询才能真正做到“改写 → 混合检索 → 重排 → 上下文补全”且输出透明，为后续 GraphRAG / HyDE 等能力扩展奠定基础。
