# Agent Platform — Agent 中台

面向多租户的 **Agent 基础设施中台**，为上层 Agent 应用提供统一的 MCP 协议网关、LLM 接入、记忆管理、任务规划、工作流编排等核心能力，并以认证授权、治理、审计作为贯穿所有模块的共享基础设施。

---

## 定位与设计理念

本项目不是一个单一的 MCP 网关，而是一个 **Agent 中台**：

- **认证授权、治理、审计** 是所有业务模块共用的横切基础设施
- **MCP 网关、LLM 路由、记忆服务、任务规划、工作流编排** 是同一层级的业务模块，各自独立、按需启用
- 所有业务模块通过统一的 Tool 体系互联互通（例如 Planner 可调用 LLM Tool，Workflow 可编排任意 Tool）

## 架构概览

```
                        Agent / MCP Client
                              │
                              ▼
┌──────────────────────────────────────────────────────────────┐
│                    Agent 中台 (agent-platform)                │
│                                                              │
│  ┌────────────────── 共享基础设施层 ──────────────────────┐  │
│  │  认证 (OAuth 2.1 / JWT)  │  授权 (OPA + Grant Engine)  │  │
│  │  治理 (限流 / 熔断)       │  审计 (全量日志 + Metrics)   │  │
│  │  密钥管理 (Vault)         │  多租户隔离                  │  │
│  └─────────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌──────────────── 业务模块层 (同级，按需启用) ───────────┐  │
│  │                                                        │  │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐  │  │
│  │  │ MCP 网关  │ │ LLM 路由 │ │ 记忆服务  │ │ 任务规划  │  │  │
│  │  │          │ │          │ │          │ │          │  │  │
│  │  │ 协议处理  │ │ 多供应商  │ │ 短期记忆  │ │ 目标分解  │  │  │
│  │  │ Tool聚合  │ │ 模型路由  │ │ 长期记忆  │ │ 步骤执行  │  │  │
│  │  │ Tool分发  │ │ 配额计费  │ │ 结构化    │ │ LLM驱动  │  │  │
│  │  │ 上游代理  │ │ 降级容错  │ │ 语义检索  │ │          │  │  │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘  │  │
│  │                                                        │  │
│  │  ┌──────────────────────────────────────────────────┐  │  │
│  │  │              工作流编排 (DAG Engine)               │  │  │
│  │  │  拓扑排序 · 并行执行 · 条件分支 · 上下文传递       │  │  │
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
  Upstream MCP    Upstream REST    LLM Providers   Mem0 / Milvus
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
| **密钥管理** | `mcp/upstream` | HashiCorp Vault 集成, Token Exchange (不透传调用方凭证) |
| **管理后台** | `admin` | 租户/服务器/Tool/Grant/审计的 CRUD REST API |

### 业务模块（同级，按需启用）

| 模块 | 包路径 | 配置开关 | 职责 |
|------|--------|----------|------|
| **MCP 网关** | `mcp/` (含 `router`, `registry`, `upstream`, `builtin`) | 始终启用 | MCP 协议 (JSON-RPC 2.0) 处理, Tool 聚合/路由/分发, 上游 MCP/REST 代理, 内置 Tool |
| **LLM 路由** | `llm/` | `agent-platform.llm-router.enabled` | 多供应商统一接入, 模型路由, RPM 配额, 用量计费, 降级容错 |
| **记忆服务** | `memory/` | `agent-platform.memory.enabled` | 三层记忆: 短期 (Redis TTL) / 长期 (Mem0+Milvus 或 MySQL 降级) / 结构化 (MySQL KV), 语义检索, 自动过期清理 |
| **任务规划** | `planner/` | `agent-platform.planner.enabled` | LLM 驱动的目标分解, 生成可执行步骤计划, 逐步执行, 上下文传递 |
| **工作流编排** | `workflow/` | `agent-platform.workflow.enabled` | DAG 工作流定义, 拓扑排序, Virtual Thread 并行执行, 条件分支, 节点间数据映射 |

### 模块间协作

```
Planner ──调用──→ LLM (llm_chat Tool) ──→ 生成步骤计划
    │
    └──执行──→ ToolDispatcher ──→ 任意已注册 Tool (MCP/REST/内置)

Workflow ──编排──→ ToolDispatcher ──→ 按 DAG 拓扑并行调用多个 Tool
    │
    └──节点可以是──→ llm_chat / memory_save / memory_query / 任意外部 Tool

Memory ──被调用──→ 作为 MCP Tool (memory_save, memory_query) 供 Agent 使用
LLM    ──被调用──→ 作为 MCP Tool (llm_chat, llm_embed) 供 Agent 使用
```

## 技术栈

| 组件 | 选型 |
|------|------|
| 语言 | Java 21 (Virtual Threads, Pattern Matching) |
| 框架 | Spring Boot 3.3 + Spring AI 1.0-M6 |
| 安全 | Spring Security 6.3 + OAuth 2.1 |
| 数据库 | MySQL 8.0 (Flyway 迁移) |
| 缓存 | Redis 7 + Caffeine |
| 向量存储 | Milvus 2.4 (via Mem0) |
| 记忆引擎 | Mem0 (可选, 降级到 MySQL) |
| 密钥管理 | HashiCorp Vault |
| 策略引擎 | OPA (Open Policy Agent) |
| 治理 | Resilience4j (限流 + 熔断) |
| 可观测 | Micrometer + OpenTelemetry + Prometheus |
| API 文档 | SpringDoc OpenAPI (Swagger UI) |

## 项目结构

```
agent-platform/
├── pom.xml                              # 父 POM (Maven 多模块)
├── docker-compose.yml                   # 本地开发环境 (MySQL, Redis, OPA, Milvus, Mem0)
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
        ├── admin/                       # Admin REST Controllers (Tenant/Server/Tool/Grant/Audit)
        │
        │── ─── 业务模块 (同级) ───
        ├── mcp/                         # [MCP 网关]
        │   ├── McpProtocolHandler.java  #   JSON-RPC 2.0 协议处理
        │   ├── router/                  #   ToolAggregator + ToolDispatcher
        │   ├── registry/                #   上游服务注册, 健康检查, Tool 发现
        │   ├── upstream/                #   McpProxy, RestProxy, TokenExchange, Vault
        │   └── builtin/                 #   内置 Tool (echo, health_check)
        │
        ├── llm/                         # [LLM 路由] LlmRouterService, 供应商/模型/配额管理
        ├── memory/                      # [记忆服务] MemoryService, Mem0Client, 三层记忆
        ├── planner/                     # [任务规划] PlanningEngine, LLM 驱动分解
        └── workflow/                    # [工作流编排] DagExecutionEngine, 拓扑并行执行
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
| `/api/v1/planner/plans` | GET | 规划记录查询 |
| `/api/v1/workflow/graphs` | POST/GET | 工作流 DAG 模板管理 |
| `/api/v1/workflow/runs` | GET | 工作流执行记录 |

### MCP Tool 列表

以下 Tool 通过 `tools/list` 暴露给 Agent，Agent 通过 `tools/call` 调用：

| Tool 名称 | 所属模块 | 说明 |
|-----------|---------|------|
| `echo` | MCP 网关 (内置) | 回显测试 |
| `health_check` | MCP 网关 (内置) | 上游服务健康检查 |
| `llm_chat` | LLM 路由 | 统一 LLM 对话 (多供应商路由) |
| `llm_embed` | LLM 路由 | 文本向量化 |
| `memory_save` | 记忆服务 | 保存记忆 (短期/长期/结构化) |
| `memory_query` | 记忆服务 | 查询记忆 (语义检索/精确查询) |
| `plan_create` | 任务规划 | LLM 驱动的目标分解 |
| `plan_execute` | 任务规划 | 执行已创建的计划 |
| `plan_status` | 任务规划 | 查询计划执行状态 |
| `workflow_run` | 工作流编排 | 执行 DAG 工作流 |
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

### 任务规划

LLM 驱动的任务分解引擎。

- **目标分解**: 调用 LLM 将自然语言目标分解为可执行步骤
- **Tool 绑定**: 每个步骤绑定一个可用 Tool，自动校验权限
- **逐步执行**: 按顺序执行步骤，支持上下文传递 (`$context.` 引用)
- **容错**: LLM 规划失败时降级到 fallback 步骤

### 工作流编排

基于 DAG 的工作流执行引擎。

- **DAG 定义**: JSON 格式定义节点 (nodes) 和边 (edges)
- **拓扑排序**: BFS 拓扑排序，自动识别可并行节点
- **并行执行**: Virtual Thread 并行执行同层节点
- **数据映射**: `$input.` 引用输入, `$node.xxx.field` 引用前置节点结果
- **条件分支**: 支持 condition 类型节点

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
  llm-router:
    enabled: true            # LLM 路由模块
  memory:
    enabled: true            # 记忆服务模块
    mem0:
      enabled: false         # Mem0 集成 (关闭则降级到 MySQL)
      url: http://localhost:8000
  planner:
    enabled: true            # 任务规划模块
  workflow:
    enabled: true            # 工作流编排模块
```

### 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `DB_USERNAME` | 数据库用户名 | `agent_platform` |
| `DB_PASSWORD` | 数据库密码 | `agent_platform` |
| `REDIS_HOST` | Redis 地址 | `localhost` |
| `REDIS_PORT` | Redis 端口 | `6379` |
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
