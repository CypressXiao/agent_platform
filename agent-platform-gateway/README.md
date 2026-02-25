# agent-platform-gateway — 网关主模块

本模块是 Agent Platform 的核心 Spring Boot 应用，实现 MCP Gateway 全部功能。

## 包结构

```
com.agentplatform.gateway/
├── config/       # 基础配置（Security, Redis, WebClient, Dev 模式）
├── authn/        # 认证层（RFC 9728 Protected Resource Metadata）
├── authz/        # 授权层（Scope, OPA Policy, Grant）
├── registry/     # 注册中心（上游服务器注册, Tool 发现, 内置 Tool, 健康检查）
├── router/       # 路由引擎（tools/list 聚合, tools/call 分发）
├── upstream/     # 上游代理（MCP Proxy, REST Proxy, Token Exchange, Vault）
├── governance/   # 治理层（限流, 熔断, 拦截器）
├── audit/        # 审计与可观测（审计日志, Micrometer 指标）
├── mcp/          # MCP 协议层（JSON-RPC 2.0 端点, Spring AI 集成）
├── admin/        # v1 管理 API（Tenant, Server, Tool, Grant, Audit）
├── builtin/      # 内置工具（echo, health_check）
└── v2/           # v2 可选子系统（按需启用）
    ├── workflow/  # 编排引擎（DAG 执行）
    ├── planner/   # 规划服务（LLM 驱动）
    ├── memory/    # 记忆服务（持久化存储）
    └── llm/       # LLM 路由（多供应商, 配额, 计费）
```

## 核心调用链路

```
MCP Client → /mcp/v1 (JSON-RPC)
  → McpProtocolHandler
    → tools/list → ToolAggregator → 聚合可见工具
    → tools/call → ToolDispatcher
      → ScopeValidator → PolicyEngine → GrantEngine  (授权)
      → GovernanceInterceptor                         (治理)
      → BuiltinToolHandler / McpProxy / RestProxy     (执行)
      → AuditLogService                               (审计)
```

## 配置文件

| 文件 | 说明 |
|------|------|
| `application.yml` | 主配置（数据源, Redis, OAuth2, 治理参数, v2 开关） |
| `application-dev.yml` | 开发环境（禁用 JWT, 启用全部 v2, DEBUG 日志） |
| `application-test.yml` | 测试环境（H2 内存数据库, 禁用 Flyway/Redis/Vault/OPA） |

## 数据库迁移

| 文件 | 说明 |
|------|------|
| `V1__init_schema.sql` | v1 核心表（tenant, upstream_server, tool, grant_record, audit_log, policy） |
| `V2__v2_schema.sql` | v2 子系统表（workflow, plan, memory, llm）+ pgvector 扩展 |

## 每个子目录下均有 README.md 详细说明该包的功能、文件清单和设计要点。
