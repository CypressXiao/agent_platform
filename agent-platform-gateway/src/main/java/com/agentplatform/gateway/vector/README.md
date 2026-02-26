# Vector Store 模块

## 概述
向量存储服务，为 Agent 服务提供 Embedding + 向量存储 + 相似度检索的封装能力。

## 核心能力
1. **向量存储** - 文本 → Embedding → 存储到 Milvus
2. **向量检索** - 查询文本 → Embedding → 相似度搜索
3. **多租户隔离** - 每个租户独立的 Collection

## 技术栈
- **Embedding**: Spring AI OpenAI / 通义千问
- **向量数据库**: Milvus
- **封装层**: Spring AI VectorStore

## 内置工具
| 工具名 | 功能 |
|--------|------|
| `vector_store` | 存储文本到向量库（自动 Embedding） |
| `vector_search` | 相似度检索 |
| `vector_delete` | 删除向量 |

## 配置
```yaml
agent-platform:
  vector:
    enabled: true
    milvus:
      host: localhost
      port: 19530
    embedding:
      provider: openai  # openai / dashscope
```

## 使用示例
```json
// vector_store
{
  "collection": "knowledge_base",
  "documents": [
    {"id": "doc1", "content": "Spring AI 是...", "metadata": {"source": "docs"}}
  ]
}

// vector_search
{
  "collection": "knowledge_base",
  "query": "什么是 Spring AI",
  "top_k": 5
}
```
