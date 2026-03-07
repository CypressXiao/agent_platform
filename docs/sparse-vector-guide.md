# 稀疏向量支持指南

本文档详细说明了 Agent Platform 中稀疏向量支持的实现和使用方法。

---

## 📋 概述

稀疏向量是用于关键词搜索和精确匹配的向量表示，与稠密向量的语义搜索形成互补。系统支持根据文档类型自动选择检索策略：

- **标准文档**：仅使用稠密向量 + tags 过滤
- **非标准文档**：使用稠密 + 稀疏混合检索

---

## 🏗️ 架构设计

### 向量类型对比

| 特性 | 稠密向量 | 稀疏向量 |
|------|----------|----------|
| **表示方式** | 连续数值向量 | 词频-权重映射 |
| **生成方式** | Embedding模型 | BM25/TF-IDF算法 |
| **匹配方式** | 语义相似度 | 词汇精确匹配 |
| **适用场景** | 概念搜索、相似性匹配 | 关键词搜索、精确匹配 |
| **存储开销** | 固定维度 | 高度压缩 |

### 混合检索策略

```
┌─────────────────────────────────────────────────────────┐
│                    混合检索架构                           │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  用户查询 ──┬── 稠密向量检索 ──┐                          │
│            │                  │                          │
│            └── 稀疏向量检索 ──┤── 结果融合 ─── 最终结果     │
│                              │                          │
│                              └── 重排序                  │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

---

## ⚙️ 配置说明

### ChunkingConfig 配置

```java
ChunkingConfig config = ChunkingConfig.builder()
    .enableSparseVector(true)        // 启用稀疏向量
    .sparseAnalyzer("chinese")       // 中文分析器
    .embeddingModel("qwen3-vl-embedding")
    .build();
```

### 预设配置

| 预设类型 | enableSparseVector | sparseAnalyzer | 适用场景 |
|----------|-------------------|----------------|----------|
| SOP | false | - | 标准操作流程 |
| FAQ | true | chinese | 常见问题 |
| PRODUCT_DOC | true | chinese | 产品文档 |

---

## 🔧 实现细节

### 数据流程

1. **文档分块**
   ```
   原始文档 → DocumentChunker → Chunk对象
   ```

2. **向量生成**
   ```
   Chunk.content ──┬── Embedding模型 → 稠密向量
                    └── BM25算法 → 稀疏向量
   ```

3. **向量存储**
   ```
   Chunk对象 + 向量 → Milvus Collection
   ```

### 存储结构

```json
{
  "id": "chunk-001",
  "vector": [0.1, 0.2, 0.3, ...],
  "sparse_vector": {
    "关键词1": 0.8,
    "关键词2": 0.6,
    "关键词3": 0.7
  },
  "content": "chunk原始内容",
  "metadata": {
    "document_name": "文档名称",
    "chunk_type": "step",
    "tenant_id": "tenant-001",
    "collection": "knowledge_base",
    "embedding_model": "qwen3-vl-embedding",
    "enable_sparse_vector": true,
    "sparse_analyzer": "chinese"
  }
}
```

### Milvus Collection 结构

| 字段名 | 数据类型 | 说明 |
|--------|----------|------|
| id | VarChar | 主键 |
| vector | FloatVector | 稠密向量 |
| sparse_vector | SparseFloatVector | 稀疏向量 |
| text | VarChar | 原文内容（带分析器） |

---

## 🚀 使用方法

### API 调用

```bash
POST /api/v1/chunking/chunk-and-store
{
  "content": "文档内容...",
  "documentName": "产品FAQ",
  "collection": "product_faq",
  "profile": "FAQ",
  "strategy": "MARKDOWN"
}
```

### 代码集成

```java
// 1. 获取ChunkProfile
ChunkProfile profile = profileRegistry.get("FAQ");
ChunkingConfig config = profile.getChunkingConfig();

// 2. 文档分块
List<Chunk> chunks = documentChunker.chunk(content, documentName, config);

// 3. 存储到向量库
List<DocumentInput> documents = chunks.stream()
    .map(chunk -> DocumentInput.builder()
        .id(chunk.getId())
        .content(chunk.getContent())
        .metadata(chunk.getMetadata())
        .build())
    .collect(Collectors.toList());

// 4. 传递ChunkingConfig以支持稀疏向量
vectorStoreService.store(identity, collection, documents, 
    config.getEmbeddingModel(), config);
```

---

## 📊 性能优化

### 索引策略

| 索引类型 | 适用向量 | 算法 | 参数 |
|----------|----------|------|------|
| HNSW | 稠密向量 | 图索引 | M=16, efConstruction=256 |
| SPARSE_INVERTED | 稀疏向量 | 倒排索引 | DAAT_MAXSCORE |

### 查询优化

1. **并行检索**：稠密和稀疏向量并行查询
2. **结果融合**：加权融合两种检索结果
3. **重排序**：使用更精细的排序算法

---

## 🔍 监控和调试

### 日志监控

```bash
# 查看稀疏向量创建日志
grep "sparse-enabled" logs/application.log

# 查看混合检索日志
grep "hybrid search" logs/application.log
```

### 性能指标

| 指标 | 说明 | 目标值 |
|------|------|--------|
| 稠密向量检索延迟 | 语义搜索响应时间 | <100ms |
| 稀疏向量检索延迟 | 关键词搜索响应时间 | <50ms |
| 混合检索总延迟 | 完整检索流程 | <200ms |
| 召回率 | 检索结果相关性 | >85% |

---

## 🛠️ 故障排除

### 常见问题

1. **稀疏向量未生成**
   - 检查 `enableSparseVector` 配置
   - 确认 `sparseAnalyzer` 设置正确
   - 验证 Milvus Collection 包含稀疏向量字段

2. **检索效果不佳**
   - 调整分析器参数
   - 优化分块策略
   - 检查关键词提取质量

3. **性能问题**
   - 检查索引配置
   - 优化查询参数
   - 监控资源使用情况

### 调试命令

```bash
# 检查Collection结构
python -c "
from pymilvus import connections, Collection
connections.connect('default', host='localhost', port='19530')
collection = Collection('agent_platform_faq_qwen3_vl_embedding')
print(collection.schema)
"

# 查看索引信息
python -c "
from pymilvus import connections, Collection
connections.connect('default', host='localhost', port='19530')
collection = Collection('agent_platform_faq_qwen3_vl_embedding')
print(collection.indexes)
"
```

---

## 📚 参考资料

- [Milvus 稀疏向量文档](https://milvus.io/docs/sparse_vector.md)
- [Milvus 全文搜索文档](https://milvus.io/docs/full-text-search.md)
- [BM25 算法说明](https://en.wikipedia.org/wiki/Okapi_BM25)
- [Spring AI Milvus 集成](https://docs.spring.io/spring-ai/reference/api/vectors/milvus.html)

---

## 🔄 版本历史

| 版本 | 日期 | 更新内容 |
|------|------|----------|
| v1.0 | 2024-01-XX | 初始稀疏向量支持 |
| v1.1 | 2024-XX-XX | 混合检索优化 |
| v1.2 | 2024-XX-XX | 性能改进 |
