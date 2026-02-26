# Agent Platform 能力说明

## 概述

Agent Platform 是一个面向多租户的 **Agent 能力网关**，为上层 Agent 服务提供统一的能力入口。

```
┌─────────────────────────────────────────────────────────────────┐
│                        Agent 服务                                │
│              (使用 Spring AI Alibaba 等框架)                      │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                   Agent Platform Gateway                         │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │                    协议入口层                                ││
│  │         MCP (JSON-RPC 2.0)  │  REST API                     ││
│  └─────────────────────────────────────────────────────────────┘│
│                                                                  │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │                    能力层                                    ││
│  │  LLM 路由 │ 向量/RAG │ Prompt 管理 │ Memory │ Tool 聚合     ││
│  └─────────────────────────────────────────────────────────────┘│
│                                                                  │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │                    治理层                                    ││
│  │  认证授权 │ 限流熔断 │ 配额管理 │ 审计日志 │ Tracing        ││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
```

---

## 核心能力

### 1. LLM 路由服务

**功能**: 统一的 LLM 调用入口，支持多供应商路由。

| 特性 | 说明 |
|------|------|
| 多供应商支持 | OpenAI、Azure OpenAI、阿里通义、自定义 |
| 智能路由 | 按模型、成本、可用性路由 |
| 配额管理 | 按租户/模型设置调用配额 |
| 用量统计 | Token 用量、成本统计 |
| 故障转移 | 自动切换备用供应商 |

**调用方式**:
- MCP Tool: `llm_chat`
- REST API: `/api/v1/llm/*`

**示例**:
```json
// MCP 调用
{
  "method": "tools/call",
  "params": {
    "name": "llm_chat",
    "arguments": {
      "model": "gpt-4",
      "messages": [
        {"role": "user", "content": "你好"}
      ]
    }
  }
}
```

---

### 2. 向量存储/RAG 服务

**功能**: 文档向量化存储、相似度检索、RAG 查询。

| 特性 | 说明 |
|------|------|
| 自动 Embedding | 文档自动转换为向量 |
| 多 Collection | 按业务隔离数据 |
| 相似度检索 | 支持 Top-K 和阈值过滤 |
| RAG 封装 | 检索 + Prompt + LLM 一站式 |
| 多租户隔离 | 数据按 tenant_id 隔离 |

**调用方式**:
- MCP Tool: `vector_store`, `vector_search`, `vector_delete`, `rag_query`
- REST API: `/api/v1/vectors/*`

**示例**:
```json
// 存储文档
POST /api/v1/vectors/store
{
  "collection": "knowledge_base",
  "documents": [
    {"content": "Agent Platform 是...", "metadata": {"source": "doc"}}
  ]
}

// 检索
POST /api/v1/vectors/search
{
  "collection": "knowledge_base",
  "query": "什么是 Agent Platform",
  "topK": 5
}

// RAG 查询 (MCP)
{
  "name": "rag_query",
  "arguments": {
    "collection": "knowledge_base",
    "query": "Agent Platform 有什么功能？",
    "model": "gpt-4"
  }
}
```

---

### 3. Prompt 管理服务

**功能**: Prompt 模板的存储、版本控制、渲染。

| 特性 | 说明 |
|------|------|
| 模板存储 | 持久化存储 Prompt 模板 |
| 版本控制 | 支持多版本，可回滚 |
| 变量渲染 | `{{variable}}` 语法 |
| 租户隔离 | 每个租户独立管理 |

**调用方式**:
- MCP Tool: `prompt_render`
- REST API: `/api/v1/prompts/*`

**示例**:
```json
// 创建模板
POST /api/v1/prompts
{
  "name": "chat_assistant",
  "template": "你是 {{role}}，请回答：{{question}}",
  "variables": ["role", "question"]
}

// 渲染模板
POST /api/v1/prompts/chat_assistant/render
{
  "role": "技术顾问",
  "question": "如何设计微服务架构？"
}

// 响应
{
  "rendered": "你是 技术顾问，请回答：如何设计微服务架构？"
}
```

---

### 4. Memory 服务

**功能**: Agent 记忆管理，支持短期/长期/结构化记忆。

| 特性 | 说明 |
|------|------|
| 短期记忆 | 会话级别，Redis 存储 |
| 长期记忆 | 持久化，MySQL 存储 |
| 结构化记忆 | Key-Value 存储 |
| 命名空间 | 按业务隔离 |

**调用方式**:
- MCP Tool: `memory_store`, `memory_retrieve`, `memory_search`
- REST API: `/api/v1/memory/*`

**示例**:
```json
// 存储记忆 (MCP)
{
  "name": "memory_store",
  "arguments": {
    "namespace": "user_sessions",
    "key": "user_123_context",
    "value": {"last_topic": "微服务", "preferences": ["简洁"]}
  }
}

// 检索记忆
{
  "name": "memory_retrieve",
  "arguments": {
    "namespace": "user_sessions",
    "key": "user_123_context"
  }
}
```

---

### 5. MCP Tool 聚合

**功能**: 聚合上游 MCP 服务器的 Tool，统一暴露给 Agent。

| 特性 | 说明 |
|------|------|
| Tool 发现 | 自动发现上游 Tool |
| 统一入口 | 单一端点调用所有 Tool |
| 权限控制 | 按 Tool 粒度授权 |
| 审计日志 | 记录所有调用 |

**内置 Tool 列表**:

| Tool | 模块 | 说明 |
|------|------|------|
| `echo` | 内置 | 回显测试 |
| `health_check` | 内置 | 健康检查 |
| `llm_chat` | LLM | LLM 对话 |
| `vector_store` | 向量 | 存储文档 |
| `vector_search` | 向量 | 相似度检索 |
| `vector_delete` | 向量 | 删除向量 |
| `rag_query` | RAG | RAG 查询 |
| `prompt_render` | Prompt | 渲染模板 |
| `memory_store` | Memory | 存储记忆 |
| `memory_retrieve` | Memory | 检索记忆 |
| `memory_search` | Memory | 搜索记忆 |

---

## 治理能力

### 认证授权

| 能力 | 说明 |
|------|------|
| OAuth 2.1 | 标准 OAuth 2.1 认证 |
| JWT | Bearer Token 认证 |
| Scope | 基于 Scope 的权限控制 |
| OPA | 基于策略的细粒度授权 |
| Grant | 跨租户资源共享 |

### 限流熔断

| 能力 | 说明 |
|------|------|
| 滑动窗口限流 | Redis 实现 |
| 熔断器 | Resilience4j |
| 降级 | 自动降级策略 |

### 配额管理

| 能力 | 说明 |
|------|------|
| LLM 配额 | 按租户/模型设置 Token 配额 |
| API 配额 | 按租户设置 API 调用配额 |

### 审计日志

| 能力 | 说明 |
|------|------|
| 全量记录 | 所有 Tool 调用记录 |
| 查询接口 | `/api/v1/admin/audit` |

### Tracing

| 能力 | 说明 |
|------|------|
| OpenTelemetry | 分布式追踪 |
| Span 自动创建 | Tool/LLM/Vector 调用自动创建 Span |

---

## 模块开关

所有能力模块可按需启用：

```yaml
agent-platform:
  llm-router:
    enabled: true      # LLM 路由
  vector:
    enabled: true      # 向量存储
  prompt:
    enabled: true      # Prompt 管理
  memory:
    enabled: true      # Memory 服务
```

---

## 接入方式

### 方式一：MCP 协议 (推荐)

适用于 Agent 框架（如 Spring AI Alibaba）直接调用。

```
POST /mcp/v1/messages
Content-Type: application/json
Authorization: Bearer <token>

{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "vector_search",
    "arguments": {...}
  }
}
```

### 方式二：REST API

适用于直接 HTTP 调用。

```
POST /api/v1/vectors/search
Content-Type: application/json
Authorization: Bearer <token>

{...}
```

---

## 多租户

所有数据按 `tenant_id` 隔离：

- `tenant_id` 从 JWT Token 中提取
- 向量数据自动添加 `tenant_id` 到 metadata
- Prompt 模板按 `tenant_id` 存储
- Memory 按 `tenant_id + namespace` 隔离
- LLM 配额按 `tenant_id` 管理

---

## 技术栈

| 组件 | 技术 |
|------|------|
| 框架 | Spring Boot 3.2 + Spring AI |
| 数据库 | MySQL 8.0 |
| 缓存 | Redis 7 |
| 向量库 | Milvus 2.3+ |
| 认证 | Spring Security + OAuth 2.1 |
| 授权 | OPA (Open Policy Agent) |
| 追踪 | Micrometer + OpenTelemetry |
| 熔断 | Resilience4j |
