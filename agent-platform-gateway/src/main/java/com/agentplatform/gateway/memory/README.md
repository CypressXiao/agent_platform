# v2/memory — 分层记忆服务

本包实现 Agent 分层记忆存储与检索服务，支持三种记忆层级和可选的 Mem0 智能记忆后端。

**启用方式**: `agent-platform.v2.memory.enabled=true`
**Mem0 增强**: `agent-platform.v2.memory.mem0.enabled=true`（可选，关闭时功能正常）

## 架构概览

```
┌─────────────────────────────────────────────────┐
│  memory_save(type=short/long/entity)             │
│  memory_query(mode=recent/semantic/exact/entity)  │
└──────────┬──────────┬──────────┬────────────────┘
           │          │          │
     ┌─────▼─────┐ ┌──▼───┐ ┌───▼────┐
     │ Redis     │ │ Mem0 │ │ PG     │
     │ 短期记忆   │ │ 长期  │ │ 结构化  │
     │ TTL 过期   │ │ 语义  │ │ 实体   │
     └───────────┘ └──┬───┘ └────────┘
                      │ (开关 OFF 时)
                   ┌──▼───┐
                   │ PG   │
                   │ 精确  │
                   └──────┘
```

## 三层记忆

| 层级 | 存储 | 用途 | TTL |
|------|------|------|-----|
| **短期记忆** | Redis List | 对话上下文、工作记忆 | 分钟~小时（自动过期） |
| **长期记忆** | Mem0 → Milvus（ON）/ PostgreSQL（OFF） | 历史摘要、知识片段、用户偏好 | 天~永久 |
| **结构化记忆** | PostgreSQL `entity_memory` 表 | 用户 Profile、实体关系、事实知识 | 永久（可手动删除） |

## 文件清单

### 数据模型（model/）

| 文件 | 说明 |
|------|------|
| `MemoryEntry.java` | 长期记忆条目实体（PG fallback），对应 `memory_entry` 表 |
| `MemoryNamespace.java` | 命名空间配置实体，对应 `memory_namespace` 表。定义配额、TTL、嵌入模型 |
| `EntityMemory.java` | 结构化实体记忆实体，对应 `entity_memory` 表。key-value 形式存储事实/偏好/Profile，支持 Upsert |

### 数据访问（repository/）

| 文件 | 说明 |
|------|------|
| `MemoryEntryRepository.java` | 长期记忆数据访问（PG fallback）。分页查询、计数、清空、过期清理 |
| `MemoryNamespaceRepository.java` | 命名空间数据访问 |
| `EntityMemoryRepository.java` | 结构化实体记忆数据访问。按租户+Agent+类型+Key 精确查找，支持 Upsert |

### Mem0 客户端（client/）

| 文件 | 说明 |
|------|------|
| `Mem0RestClient.java` | Mem0 REST API 客户端（`@ConditionalOnProperty` 按需创建）。封装 `POST /memories`（添加）、`POST /memories/search`（语义搜索）、`GET /memories`（列表）、`DELETE /memories/{id}`（删除）、健康检查 |

### 短期记忆存储（store/）

| 文件 | 说明 |
|------|------|
| `ShortTermMemoryStore.java` | Redis 短期记忆存储。使用 Redis List 按 `stm:{tenantId}:{agentId}:{namespace}` 存储，自动 TTL 过期，最多保留 100 条/Key，支持最近 N 条查询 |

### 服务层

| 文件 | 说明 |
|------|------|
| `MemoryService.java` | 分层记忆服务核心。路由逻辑：短期→Redis、长期→Mem0(ON)/PG(OFF)、结构化→PG。Mem0 通过 `Optional<Mem0RestClient>` 注入，开关关闭时自动降级。内置定时清理过期条目 |

### 内置工具

| 文件 | 工具名 | 说明 |
|------|--------|------|
| `MemorySaveTool.java` | `memory_save` | 统一保存工具。`type` 参数：`short`（Redis）/ `long`（Mem0 或 PG）/ `entity`（PG 结构化）。Entity 模式支持 `entity_type` + `entity_key` 参数 |
| `MemoryQueryTool.java` | `memory_query` | 统一查询工具。`mode` 参数：`recent`（Redis 最新）/ `semantic`（Mem0 语义搜索或 PG 降级）/ `exact`（PG 精确）/ `entity`（PG 实体查询） |

### 管理 API

| 文件 | 说明 |
|------|------|
| `MemoryAdminController.java` | 管理 API（`/api/v2/memory/**`）。命名空间 CRUD、统计信息（含 Mem0 状态）、`/health` 端点（显示各层存储状态） |

## 配置

```yaml
agent-platform:
  v2:
    memory:
      enabled: true       # 启用记忆子系统
      mem0:
        enabled: false    # Mem0 开关（OFF = PG fallback）
        url: http://localhost:8000
```

## Mem0 开关行为

| 开关 | 长期记忆保存 | 长期记忆查询 | 短期/结构化 |
|------|------------|------------|-----------|
| **ON** | Mem0 LLM 提取 + Milvus 向量化 | Mem0 语义相似度搜索 | 不受影响 |
| **OFF** | PostgreSQL `memory_entry` 表 | PostgreSQL 精确查询（按时间排序） | 不受影响 |

## 数据库迁移

- `V2__v2_schema.sql` — `memory_entry` + `memory_namespace` 表
- `V3__entity_memory.sql` — `entity_memory` 表（结构化记忆）
