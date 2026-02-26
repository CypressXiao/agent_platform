# Agent Platform — Agent 能力网关

面向多租户的 **Agent 能力网关**，为上层 Agent 服务提供统一的能力入口，包括 LLM 接入、向量存储/RAG、Prompt 管理、记忆管理等核心能力，并以认证授权、治理、审计、Tracing 作为贯穿所有模块的共享基础设施。

> **定位**: 本平台是 **for Agent 服务** 的能力网关，不是 for 用户的。Agent 编排逻辑（如 Planner、Workflow）由上层 Agent 应用使用 Spring AI Alibaba 等框架实现。
>
> **Gateway 的含义**: 不仅仅是 MCP 协议网关，而是 Agent 服务调用各种能力的统一入口。MCP 只是协议之一，所有能力调用都需要认证。

## 📚 文档

| 文档 | 说明 |
|------|------|
| [**能力说明**](docs/capabilities.md) | 平台核心能力详细说明 |
| [部署指南](docs/deployment.md) | Docker Compose 部署、环境变量配置 |
| [API 使用指南](docs/api-guide.md) | REST API 和 MCP 协议调用示例 |
| [Swagger UI](http://localhost:8080/swagger-ui.html) | 在线 API 文档（需启动服务） |

---

## 定位与设计理念

本项目是一个 **Agent 能力网关**：

- **认证授权、治理、审计、Tracing** 是所有业务模块共用的横切基础设施
- **MCP 网关、LLM 路由、向量存储/RAG、Prompt 管理、记忆服务** 是同一层级的业务模块，各自独立、按需启用
- 平台的价值在于 **基础能力 + 封装能力**，将复杂的多步骤操作（如 Embedding + 存储）封装为简单的高层 Tool

## 架构概览

```
                     Agent 服务 (使用 Spring AI Alibaba 等框架)
                              │
                              │ 调用 MCP Tool
                              ▼
┌──────────────────────────────────────────────────────────────┐
│                    Agent 中台 (agent-platform)                │
│                                                              │
│  ┌────────────────── 共享基础设施层 ──────────────────────┐  │
│  │  认证 (OAuth 2.1 / JWT)  │  授权 (OPA + Grant Engine)  │  │
│  │  治理 (限流 / 熔断)       │  审计 (全量日志 + Metrics)   │  │
│  │  密钥管理 (Vault)         │  多租户隔离                  │  │
│  │  Tracing (OpenTelemetry)  │                              │  │
│  └─────────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌──────────────── 业务模块层 (同级，按需启用) ───────────┐  │
│  │                                                        │  │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐  │  │
│  │  │ MCP 网关  │ │ LLM 路由 │ │ 向量/RAG  │ │ Prompt   │  │  │
│  │  │          │ │          │ │          │ │ 管理     │  │  │
│  │  │ 协议处理  │ │ 多供应商  │ │ Embedding│ │ 模板存储  │  │  │
│  │  │ Tool聚合  │ │ 模型路由  │ │ 向量存储  │ │ 版本管理  │  │  │
│  │  │ Tool分发  │ │ 配额计费  │ │ 相似检索  │ │ 变量渲染  │  │  │
│  │  │ 上游代理  │ │ 降级容错  │ │ RAG查询  │ │          │  │  │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘  │  │
│  │                                                        │  │
│  │  ┌──────────────────────────────────────────────────┐  │  │
│  │  │                    记忆服务                        │  │  │
│  │  │  短期记忆 (Redis) · 长期记忆 (Mem0) · 结构化记忆   │  │  │
│  │  └──────────────────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌──────────────── 数据与公共模型层 ─────────────────────┐  │
│  │  实体模型 (Tenant, Tool, Grant, AuditLog, ...)        │  │
│  │  JPA Repository · DTO · 统一异常处理                   │  │
│  └────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
       │              │              │              │
       ▼              ▼              ▼              ▼
  Upstream MCP    Upstream REST    LLM Providers   Milvus
  Servers         APIs             (OpenAI等)      (向量存储)
```

## 模块说明

### 共享基础设施（横切关注点，所有模块共用）

| 模块 | 包路径 | 职责 |
|------|--------|------|
| **认证** | `authn`, `config` | OAuth 2.1 JWT 验证, RFC 9728 资源元数据, Spring Security 配置 |
| **授权** | `authz` | Grant Engine (跨租户共享), Policy Engine (OPA ABAC), Scope Validator |
| **治理** | `governance` | Redis 滑动窗口限流 (按租户+Tool), Resilience4j 熔断 (按上游服务) |
| **审计** | `audit` | 全量调用审计日志, 成功/失败记录, 延迟统计 |
| **Tracing** | `tracing` | Micrometer Tracing + OpenTelemetry, 自动追踪 Tool/LLM/Vector 调用 |
| **密钥管理** | `mcp/upstream` | HashiCorp Vault 集成, Token Exchange (不透传调用方凭证) |
| **管理后台** | `admin` | 租户/服务器/Tool/Grant/审计的 CRUD REST API |

### 业务模块（同级，按需启用）

| 模块 | 包路径 | 配置开关 | 职责 |
|------|--------|----------|------|
| **MCP 网关** | `mcp/` | 始终启用 | MCP 协议 (JSON-RPC 2.0) 处理, Tool 聚合/路由/分发, 上游 MCP/REST 代理 |
| **LLM 路由** | `llm/` | `agent-platform.llm-router.enabled` | 多供应商统一接入, 模型路由, RPM 配额, 用量计费, 降级容错 |
| **向量存储/RAG** | `vector/` | `agent-platform.vector.enabled` | Embedding + Milvus 向量存储, 相似度检索, RAG 查询封装 |
| **Prompt 管理** | `prompt/` | `agent-platform.prompt.enabled` | Prompt 模板存储, 版本管理, Mustache 变量渲染 |
| **记忆服务** | `memory/` | `agent-platform.memory.enabled` | 三层记忆: 短期 (Redis) / 长期 (Mem0) / 结构化 (MySQL), 语义检索 |

### 模块间协作

```
Agent 服务 ──调用──→ MCP Gateway ──→ 路由到对应模块

RAG 查询流程:
  rag_query Tool ──→ VectorStoreService.search() ──→ LlmRouterService.chat()
                          │                              │
                          ▼                              ▼
                      Milvus 检索                    LLM 生成答案

向量存储流程:
  vector_store Tool ──→ EmbeddingModel.embed() ──→ VectorStore.add()
                              │                        │
                              ▼                        ▼
                        OpenAI Embedding           Milvus 存储
```

## 技术栈

| 组件 | 选型 |
|------|------|
| 语言 | Java 21 (Virtual Threads, Pattern Matching) |
| 框架 | Spring Boot 3.3 + Spring AI 1.0-M6 |
| 安全 | Spring Security 6.3 + OAuth 2.1 |
| 数据库 | MySQL 8.0 (Flyway 迁移) |
| 缓存 | Redis 7 + Caffeine |
| 向量存储 | Milvus 2.4 (via Spring AI) |
| Embedding | OpenAI / 通义千问 (via Spring AI) |
| 记忆引擎 | Mem0 (可选, 降级到 MySQL) |
| 密钥管理 | HashiCorp Vault |
| 策略引擎 | OPA (Open Policy Agent) |
| 治理 | Resilience4j (限流 + 熔断) |
| 可观测 | Micrometer Tracing + OpenTelemetry + Prometheus |
| API 文档 | SpringDoc OpenAPI (Swagger UI) |

## 项目结构

```
agent-platform/
├── pom.xml                              # 父 POM (Maven 多模块)
├── docker-compose.yml                   # 本地开发环境 (MySQL, Redis, OPA, Milvus)
├── opa/policies/                        # OPA 策略文件
│
├── agent-platform-common/               # 公共模型层 (Maven Module)
│   └── src/main/java/.../common/
│       ├── model/                       # 实体: Tenant, Tool, Grant, AuditLog, UpstreamServer, ...
│       ├── repository/                  # JPA Repository
│       ├── dto/                         # 请求/响应 DTO
│       └── exception/                   # McpErrorCode + McpException 统一异常
│
└── agent-platform-gateway/              # 主服务 (Spring Boot Application)
    ├── Dockerfile
    └── src/main/java/.../gateway/
        │
        │── AgentPlatformApplication.java  # 启动类
        │
        │── ─── 共享基础设施 ───
        ├── config/                      # SecurityConfig, RedisConfig, WebClientConfig
        ├── authn/                       # RFC 9728 Protected Resource Metadata
        ├── authz/                       # GrantEngine, PolicyEngine, ScopeValidator
        ├── governance/                  # RateLimitService, CircuitBreakerService
        ├── audit/                       # AuditLogService
        ├── tracing/                     # TracingService, ToolTracingAspect
        ├── admin/                       # Admin REST Controllers
        │
        │── ─── 业务模块 (同级) ───
        ├── mcp/                         # [MCP 网关] 协议处理, Tool 聚合/路由/分发
        ├── llm/                         # [LLM 路由] 多供应商接入, 配额管理
        ├── vector/                      # [向量/RAG] Embedding, 向量存储, RAG 查询
        ├── prompt/                      # [Prompt 管理] 模板存储, 版本管理, 渲染
        └── memory/                      # [记忆服务] 三层记忆架构
```

## 快速开始

### 前置条件

- Java 21+
- Maven 3.9+
- Docker & Docker Compose

### 1. 启动基础设施

```bash
# 启动核心依赖
docker-compose up -d mysql redis opa

# (可选) 启动记忆服务依赖
docker-compose up -d milvus mem0
```

### 2. 构建 & 运行

```bash
# 构建
mvn clean package -DskipTests

# 以 dev 模式运行 (禁用 JWT 验证, 启用所有业务模块)
java --enable-preview -jar agent-platform-gateway/target/agent-platform-gateway-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=dev
```

### 3. 验证

```bash
# 健康检查
curl http://localhost:8080/actuator/health

# MCP initialize
curl -X POST http://localhost:8080/mcp/v1 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":"1","method":"initialize","params":{}}'

# tools/list — 查看所有可用 Tool (含 MCP 网关内置 + LLM + Memory + Planner + Workflow)
curl -X POST http://localhost:8080/mcp/v1 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":"2","method":"tools/list","params":{}}'

# tools/call — 调用内置 echo Tool
curl -X POST http://localhost:8080/mcp/v1 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":"3","method":"tools/call","params":{"name":"echo","arguments":{"message":"hello"}}}'

# tools/call — 调用 LLM (需启用 llm-router)
curl -X POST http://localhost:8080/mcp/v1 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":"4","method":"tools/call","params":{"name":"llm_chat","arguments":{"model":"default","messages":[{"role":"user","content":"你好"}]}}}'
```

## API 概览

### MCP 协议端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/mcp/v1` | POST | JSON-RPC 2.0 — `initialize`, `ping`, `tools/list`, `tools/call` |
| `/.well-known/oauth-protected-resource` | GET | RFC 9728 资源元数据 |

### Admin API

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/v1/admin/tenants` | POST/GET | 租户管理 |
| `/api/v1/admin/servers/mcp` | POST | 注册 Upstream MCP Server |
| `/api/v1/admin/servers/rest` | POST | 注册 Upstream REST API |
| `/api/v1/admin/servers` | GET/DELETE | 查询/删除上游服务器 |
| `/api/v1/admin/tools` | GET | 查询 Tool 列表 |
| `/api/v1/admin/grants` | POST/GET | Grant 管理 (跨租户共享) |
| `/api/v1/admin/grants/{id}/revoke` | POST | 撤销 Grant |
| `/api/v1/admin/audit` | GET | 审计日志查询 |
| `/api/v1/llm/providers` | POST/GET | LLM 供应商管理 |
| `/api/v1/llm/models` | POST/GET | 模型配置 |
| `/api/v1/llm/quotas/{tenantId}` | GET/PUT | 租户 LLM 配额 |
| `/api/v1/llm/usage` | GET | LLM 用量统计 |
| `/api/v1/memory/namespaces` | POST/GET | 记忆命名空间管理 |
| `/api/v1/prompts` | POST/GET | Prompt 模板管理（租户级别） |
| `/api/v1/prompts/{name}/versions` | POST | 创建新版本 |
| `/api/v1/vectors/store` | POST | 存储文档到向量库（租户级别） |
| `/api/v1/vectors/search` | POST | 向量相似度检索 |
| `/api/v1/vectors` | DELETE | 删除向量 |
| `/api/v1/vectors/embed` | POST | 获取文本 Embedding |

### MCP Tool 列表

以下 Tool 通过 `tools/list` 暴露给 Agent 服务，通过 `tools/call` 调用：

| Tool 名称 | 所属模块 | 说明 |
|-----------|---------|------|
| `echo` | MCP 网关 (内置) | 回显测试 |
| `health_check` | MCP 网关 (内置) | 上游服务健康检查 |
| `llm_chat` | LLM 路由 | 统一 LLM 对话 (多供应商路由) |
| `vector_store` | 向量/RAG | 存储文档 (自动 Embedding) |
| `vector_search` | 向量/RAG | 相似度检索 |
| `vector_delete` | 向量/RAG | 删除向量 |
| `rag_query` | 向量/RAG | RAG 查询 (检索 + LLM 生成) |
| `prompt_render` | Prompt 管理 | 渲染 Prompt 模板 |
| `memory_save` | 记忆服务 | 保存记忆 (短期/长期/结构化) |
| `memory_query` | 记忆服务 | 查询记忆 (语义检索/精确查询) |
| *(动态注册)* | MCP 网关 | 来自 Upstream MCP/REST 的外部 Tool |

## 核心功能详解

### MCP 网关

MCP 网关是中台的协议入口，实现 MCP 规范 (JSON-RPC 2.0)，负责 Tool 的聚合、路由和上游代理。

- **Tool 聚合** (`ToolAggregator`): 合并自有 Tool + 系统内置 Tool + Grant 共享 Tool，经 Policy 过滤后返回
- **Tool 分发** (`ToolDispatcher`): 权限检查 → 策略评估 → 治理拦截 → 路由到 builtin / upstream_mcp / upstream_rest 处理器
- **上游代理**: MCP Proxy (JSON-RPC 转发) + REST Proxy (HTTP 映射) + Token Exchange (凭证代理，不透传)
- **Tool 发现**: 自动从 Upstream MCP Server 发现并注册 Tool

### LLM 路由

统一的 LLM 接入层，屏蔽不同供应商的差异。

- **多供应商**: 支持 OpenAI 兼容 API 的任意供应商 (OpenAI, Azure, 通义千问, DeepSeek 等)
- **模型路由**: 按租户配置路由到指定模型，支持 fallback 降级
- **配额管理**: 按租户+模型的 RPM 限制 (Redis 滑动窗口)
- **用量计费**: 自动记录 prompt/completion tokens，按价格计算费用
- **密钥安全**: API Key 从 Vault 获取，不暴露给调用方

### 记忆服务

三层记忆架构，满足不同场景需求。

- **短期记忆** (Redis): 会话上下文，TTL 自动过期
- **长期记忆** (Mem0 + Milvus): LLM 智能提取 + 向量化 + 语义检索；Mem0 不可用时降级到 MySQL 精确查询
- **结构化记忆** (MySQL): Key-Value 实体记忆，支持 Upsert
- **命名空间隔离**: 按租户+Agent+命名空间隔离，支持配额限制

### 向量存储/RAG

基于 Spring AI 的向量存储和 RAG 能力。

- **自动 Embedding**: 存储时自动调用 Embedding 模型
- **多租户隔离**: 按租户+Collection 隔离向量数据
- **相似度检索**: 支持 Top-K 和相似度阈值过滤
- **RAG 封装**: `rag_query` 工具封装 检索 → Prompt → LLM 完整流程

### Prompt 管理

Prompt 模板管理服务。

- **模板存储**: 支持多租户的 Prompt 模板存储
- **版本管理**: 支持模板版本控制
- **变量渲染**: Mustache 风格变量替换 (`{{variable}}`)
- **自动提取**: 自动从模板中提取变量列表

### 认证授权 (共享)

- **OAuth 2.1**: JWT Bearer Token 验证, RFC 8707 Resource Parameter
- **Tool-level Scope**: 细粒度权限控制
- **OPA 策略引擎**: 可扩展的 ABAC 策略评估
- **Grant 机制**: 跨租户 Tool 共享，支持 Tool 列表、Scope 限制、过期时间、撤销

### 治理 (共享)

- **限流**: Redis 滑动窗口，按租户+Tool 粒度
- **熔断**: Resilience4j，按上游服务粒度，自动降级

## 配置

### 模块开关

各业务模块通过配置开关独立启用/禁用：

```yaml
agent-platform:
  prompt:
    enabled: true            # Prompt 管理模块
  vector:
    enabled: true            # 向量存储/RAG 模块
    milvus:
      host: localhost
      port: 19530
      collection: agent_platform_vectors
  memory:
    enabled: true            # 记忆服务模块
    mem0:
      enabled: false         # Mem0 集成 (关闭则降级到 MySQL)
      url: http://localhost:8000
  llm-router:
    enabled: true            # LLM 路由模块
```

### 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `DB_USERNAME` | 数据库用户名 | `agent_platform` |
| `DB_PASSWORD` | 数据库密码 | `agent_platform` |
| `REDIS_HOST` | Redis 地址 | `localhost` |
| `REDIS_PORT` | Redis 端口 | `6379` |
| `MILVUS_HOST` | Milvus 地址 | `localhost` |
| `MILVUS_PORT` | Milvus 端口 | `19530` |
| `VAULT_ENABLED` | 是否启用 Vault | `false` |
| `VAULT_URL` | Vault 地址 | `http://localhost:8200` |
| `OPA_ENABLED` | 是否启用 OPA | `false` |
| `OPA_URL` | OPA 地址 | `http://localhost:8181` |
| `MEM0_URL` | Mem0 服务地址 | `http://localhost:8000` |
| `OAUTH2_ISSUER_URI` | OAuth2 Issuer | `https://auth.example.com` |
| `OAUTH2_JWK_SET_URI` | JWK Set URI | - |

### Profiles

| Profile | 说明 |
|---------|------|
| `default` | 生产模式: JWT 验证启用, 所有业务模块关闭, DDL validate |
| `dev` | 开发模式: JWT 验证禁用, 所有业务模块启用, DDL auto-update |

## 基础设施依赖

```bash
# docker-compose.yml 包含以下服务:
docker-compose up -d

# MySQL 8.0       — 主数据库 (端口 3306)
# Redis 7         — 缓存 + 短期记忆 + 限流计数器 (端口 6379)
# OPA             — 策略引擎 (端口 8181)
# Milvus 2.4      — 向量数据库 (端口 19530) [记忆服务可选]
# Mem0            — AI 记忆引擎 (端口 8000) [记忆服务可选]
```

## 可观测

- **Metrics**: Prometheus (`/actuator/prometheus`)
- **Tracing**: OpenTelemetry (OTLP export)
- **Audit**: 全量审计日志 (MySQL, 含调用方/Tool/Grant/延迟/状态)
- **API 文档**: Swagger UI (`/swagger-ui.html`)
