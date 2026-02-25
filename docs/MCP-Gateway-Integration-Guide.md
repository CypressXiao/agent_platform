# MCP Gateway 对接指南

本文档描述如何对接 MCP Gateway，包括平台能力、Client/Agent 接入、上游服务注册等流程。

---

## 目录

1. [平台能力概览](#平台能力概览)
2. [架构概览](#架构概览)
3. [Client/Agent 对接流程](#clientagent-对接流程)
4. [上游 MCP Server 注册](#上游-mcp-server-注册)
5. [上游 REST API 注册](#上游-rest-api-注册)
6. [工具变更通知](#工具变更通知)
7. [多租户工具隔离](#多租户工具隔离)
8. [API 参考](#api-参考)
9. [快速开始](#快速开始)
10. [常见问题](#常见问题)

---

## 平台能力概览

MCP Gateway 是一个**统一的 AI 工具网关**，提供以下核心能力：

### 支持的工具类型

| 类型 | 说明 | 工具发现 |
|------|------|---------|
| **MCP Server** | 标准 MCP 协议服务器 | ✅ 自动发现（调用 `tools/list`） |
| **REST API** | 任意 RESTful 接口 | 手动定义工具 |
| **内置工具** | 平台提供的公共工具 | 自动注册 |

### 核心功能

| 功能 | 说明 |
|------|------|
| **OAuth2 认证** | 符合 MCP 官方规范（RFC 9728），支持 `client_credentials` 模式 |
| **MCP 协议** | 完整支持 `initialize`、`tools/list`、`tools/call`、`ping` |
| **工具路由** | 根据工具类型自动路由到 MCP Server 或 REST API |
| **多租户隔离** | 每个租户只能访问自己的工具 + 平台公共工具 |
| **实时通知** | 通过 SSE 推送工具变更通知 |
| **治理能力** | 限流、熔断、审计日志 |
| **健康检查** | 定时检查上游服务健康状态 |

### 协议兼容性

| 标准 | 状态 |
|------|------|
| MCP 协议 | ✅ 完全兼容 |
| OAuth 2.0 (RFC 6749) | ✅ `client_credentials` 模式 |
| OAuth 2.0 Protected Resource Metadata (RFC 9728) | ✅ `/.well-known/oauth-protected-resource` |
| OpenID Connect Discovery | ✅ `/.well-known/openid-configuration` |

---

## 架构概览

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              MCP Gateway                                    │
│                                                                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │ OAuth2 认证 │  │ MCP 协议    │  │ 工具路由    │  │ 上游服务管理        │ │
│  │ /oauth2/*   │  │ /mcp/v1     │  │             │  │ MCP + REST          │ │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────────────┘ │
│                                                                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │ 多租户隔离  │  │ 限流/熔断   │  │ 审计日志    │  │ 健康检查            │ │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────────────┘ │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
        ▲                   ▲                                   ▲
        │                   │                                   │
        │ OAuth2 + MCP      │ OAuth2 + MCP                      │ MCP / REST
        │                   │                                   │
┌───────┴───────┐   ┌───────┴───────┐           ┌───────────────┴───────────────┐
│ Client/Agent  │   │ Client/Agent  │           │         上游服务               │
│ (下游消费者)   │   │ (下游消费者)   │           │  ┌─────────┐  ┌─────────────┐ │
└───────────────┘   └───────────────┘           │  │MCP Server│  │ REST API    │ │
                                                │  └─────────┘  └─────────────┘ │
                                                └───────────────────────────────┘
```

### 工具路由流程

```
Client 调用 tools/call
        │
        ▼
┌─────────────────────────────────────────────────────────────────┐
│                      ToolDispatcher                             │
│                                                                 │
│  1. 解析工具 → 2. Scope 校验 → 3. 策略检查 → 4. 治理拦截        │
│                                                                 │
│  5. 根据 sourceType 路由：                                      │
│     ├─ builtin      → BuiltinToolHandler（内置工具）            │
│     ├─ upstream_mcp → McpProxyService（转发到 MCP Server）      │
│     └─ upstream_rest→ RestProxyService（转发到 REST API）       │
│                                                                 │
│  6. 审计记录                                                    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Client/Agent 对接流程

### 步骤 1：获取凭证

联系 Gateway 管理员，获取：
- `client_id`：客户端标识
- `client_secret`：客户端密钥

管理员通过以下 API 注册 Client：

```bash
POST /api/v1/admin/oauth2-clients
{
  "clientId": "my-agent",
  "clientName": "My AI Agent",
  "scopes": ["mcp:tools-basic"],
  "tokenTtlMinutes": 60
}

# 返回
{
  "clientId": "my-agent",
  "clientSecret": "a1b2c3d4e5f6..."  # 只显示一次，请妥善保存
}
```

### 步骤 2：配置 Client

#### 方式 A：使用官方 MCP SDK（推荐）

**TypeScript**
```typescript
import { Client, ClientCredentialsProvider, StreamableHTTPClientTransport } from "@modelcontextprotocol/client";

const provider = new ClientCredentialsProvider({
  clientId: "my-agent",
  clientSecret: "a1b2c3d4e5f6..."
});

const client = new Client({ name: "my-agent", version: "1.0.0" });
const transport = new StreamableHTTPClientTransport(
  new URL("https://gateway.example.com/mcp"),
  { authProvider: provider }
);

await client.connect(transport);
const tools = await client.listTools();
```

**Python**
```python
from mcp.client.auth.extensions.client_credentials import ClientCredentialsOAuthProvider
from mcp.client.streamable_http import streamablehttp_client
from mcp import ClientSession

provider = ClientCredentialsOAuthProvider(
    server_url="https://gateway.example.com/mcp",
    client_id="my-agent",
    client_secret="a1b2c3d4e5f6...",
    scopes="mcp:tools-basic"
)

async with streamablehttp_client("https://gateway.example.com/mcp", auth_provider=provider) as (read, write, _):
    async with ClientSession(read, write) as session:
        await session.initialize()
        tools = await session.list_tools()
```

#### 方式 B：使用 Spring Security OAuth2 Client（Java）

```yaml
# application.yml
spring:
  security:
    oauth2:
      client:
        registration:
          mcp-gateway:
            authorization-grant-type: client_credentials
            client-id: my-agent
            client-secret: a1b2c3d4e5f6...
            scope: mcp:tools-basic
            provider: mcp-gateway
        provider:
          mcp-gateway:
            token-uri: https://gateway.example.com/oauth2/token
```

#### 方式 C：手动实现

```bash
# 1. 获取 Token
curl -X POST https://gateway.example.com/oauth2/token \
  -u "my-agent:a1b2c3d4e5f6..." \
  -d "grant_type=client_credentials&scope=mcp:tools-basic"

# 返回
{
  "access_token": "eyJ...",
  "token_type": "Bearer",
  "expires_in": 3600
}

# 2. 调用 MCP 端点
curl -X POST https://gateway.example.com/mcp/v1 \
  -H "Authorization: Bearer eyJ..." \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
      "protocolVersion": "2024-11-05",
      "capabilities": {},
      "clientInfo": { "name": "my-agent", "version": "1.0.0" }
    }
  }'
```

### 步骤 3：MCP 协议调用顺序

```
1. initialize     ← 必须第一个调用（握手）
2. tools/list     ← 获取可用工具列表
3. tools/call     ← 调用具体工具
4. ping           ← 可选，心跳检测
```

### 完整流程图

```
┌─────────────┐                              ┌─────────────┐
│ Client/Agent│                              │ MCP Gateway │
└──────┬──────┘                              └──────┬──────┘
       │                                            │
       │ 1. POST /oauth2/token                      │
       │    client_id + client_secret               │
       │───────────────────────────────────────────▶│
       │◀───────────────────────────────────────────│
       │    access_token (expires_in: 3600)         │
       │                                            │
       │ 2. POST /mcp/v1 (initialize)               │
       │    Authorization: Bearer <token>           │
       │───────────────────────────────────────────▶│
       │◀───────────────────────────────────────────│
       │    { serverInfo, capabilities }            │
       │                                            │
       │ 3. POST /mcp/v1 (tools/list)               │
       │───────────────────────────────────────────▶│
       │◀───────────────────────────────────────────│
       │    { tools: [...] }                        │
       │                                            │
       │ 4. POST /mcp/v1 (tools/call)               │
       │───────────────────────────────────────────▶│
       │◀───────────────────────────────────────────│
       │    { result: ... }                         │
       │                                            │
       │ 5. Token 过期后自动刷新                     │
       │    POST /oauth2/token                      │
       │───────────────────────────────────────────▶│
```

---

## 上游 MCP Server 注册

### 注册上游 MCP Server

管理员通过 API 注册上游 MCP Server：

```bash
POST /api/v1/admin/upstream-servers
{
  "name": "weather-mcp-server",
  "url": "https://weather-mcp.example.com/mcp",
  "serverType": "MCP",
  "description": "天气查询 MCP Server",
  "authType": "BEARER",
  "authToken": "upstream-server-token"
}
```

### Gateway 自动发现工具

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              MCP Gateway                                    │
│                                                                             │
│  1. 注册上游 Server                                                         │
│  2. 调用上游 Server 的 tools/list                                           │
│  3. 同步工具到本地数据库                                                     │
│  4. 通知下游 Client（通过 SSE 或版本号）                                     │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 上游 MCP Server 注册流程图

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   管理员    │     │ MCP Gateway │     │ 上游 MCP    │     │ Client/Agent│
└──────┬──────┘     └──────┬──────┘     └──────┬──────┘     └──────┬──────┘
       │                   │                   │                   │
       │ 1. 注册上游 Server │                   │                   │
       │──────────────────▶│                   │                   │
       │                   │                   │                   │
       │                   │ 2. tools/list     │                   │
       │                   │──────────────────▶│                   │
       │                   │◀──────────────────│                   │
       │                   │   返回工具列表     │                   │
       │                   │                   │                   │
       │                   │ 3. 同步到数据库    │                   │
       │                   │                   │                   │
       │                   │ 4. 推送 SSE 通知   │                   │
       │                   │───────────────────────────────────────▶│
       │                   │                   │                   │
       │                   │                   │   5. tools/list   │
       │                   │◀───────────────────────────────────────│
       │                   │──────────────────────────────────────▶│
       │                   │                   │   返回最新工具     │
```

---

## 上游 REST API 注册

对于非 MCP 协议的 RESTful API，可以手动注册并定义工具。

### 注册 REST API 服务器

```bash
POST /api/v1/admin/upstream-servers
{
  "name": "internal-user-service",
  "url": "https://user-service.internal/api",
  "serverType": "REST",
  "description": "内部用户服务 API",
  "ownerTid": "tenant-a",
  "authType": "BEARER",
  "authToken": "internal-api-token",
  "healthEndpoint": "/health",
  "tools": [
    {
      "toolName": "get-user-info",
      "description": "根据用户 ID 获取用户信息",
      "inputSchema": {
        "type": "object",
        "properties": {
          "userId": { "type": "string", "description": "用户 ID" }
        },
        "required": ["userId"]
      },
      "executionMapping": {
        "method": "GET",
        "path": "/users/{userId}",
        "pathParams": { "userId": "$.userId" }
      },
      "responseMapping": {
        "name": "$.data.name",
        "email": "$.data.email"
      }
    },
    {
      "toolName": "create-user",
      "description": "创建新用户",
      "inputSchema": {
        "type": "object",
        "properties": {
          "name": { "type": "string" },
          "email": { "type": "string" }
        },
        "required": ["name", "email"]
      },
      "executionMapping": {
        "method": "POST",
        "path": "/users",
        "body": {
          "name": "$.name",
          "email": "$.email"
        }
      }
    }
  ]
}
```

### MCP Server vs REST API 对比

| 特性 | MCP Server | REST API |
|------|-----------|----------|
| 工具发现 | ✅ 自动（调用 `tools/list`） | ❌ 手动定义 |
| 协议 | MCP JSON-RPC | HTTP REST |
| 适用场景 | 原生 MCP 服务 | 已有 REST 接口 |
| 参数映射 | 直接透传 | 需要配置 `executionMapping` |
| 响应映射 | 直接透传 | 可配置 `responseMapping` |

### REST 工具执行流程

```
Client 调用 tools/call("get-user-info", {"userId": "123"})
        │
        ▼
┌─────────────────────────────────────────────────────────────────┐
│                      RestProxyService                           │
│                                                                 │
│  1. 根据 executionMapping 构建 HTTP 请求：                       │
│     GET /users/123                                              │
│                                                                 │
│  2. 添加认证头：                                                 │
│     Authorization: Bearer <authToken>                           │
│                                                                 │
│  3. 发送请求到上游 REST API                                      │
│                                                                 │
│  4. 根据 responseMapping 转换响应                                │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
        │
        ▼
返回给 Client: {"name": "张三", "email": "zhangsan@example.com"}
```

---

## 工具变更通知

### 方式 A：SSE 实时通知（推荐）

```
Client 连接 /mcp/v1/sse
        │
        │ 保持长连接
        ▼
Gateway 推送 tools/list_changed 通知
        │
        ▼
Client 重新调用 tools/list 获取最新工具
```

**连接 SSE：**
```bash
curl -N -H "Authorization: Bearer <token>" \
  https://gateway.example.com/mcp/v1/sse
```

**收到通知：**
```json
event: message
data: {"jsonrpc":"2.0","method":"notifications/tools/list_changed"}
```

### 方式 B：轮询 + 版本号

```bash
# tools/list 返回版本号
{
  "tools": [...],
  "_meta": {
    "version": 42
  }
}

# Client 定期调用 tools/list，比较版本号
# 版本号变化 → 工具列表有更新
```

---

## 多租户工具隔离

### 设计原则

Gateway 采用**简洁的隔离模型**：

| 工具类型 | 谁提供 | 谁能用 | 怎么访问 |
|---------|--------|--------|---------|
| **平台公共工具** | 平台 (`ownerTid = "system"`) | 所有租户 | 通过 Gateway |
| **业务私有工具** | 各租户 | 只有自己 | 通过 Gateway |
| **跨业务调用** | 其他租户 | 需要的人 | **直接调用对方服务** |

### 为什么不在 Gateway 层做共享？

| 考虑点 | 说明 |
|--------|------|
| **所有权** | 工具是租户的资产，租户应该完全控制 |
| **计费** | 租户可以对外部调用收费 |
| **安全** | 租户可以做自己的鉴权和限流 |
| **解耦** | Gateway 只做路由，不管租户间关系 |

### 租户隔离模型

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              MCP Gateway                                    │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                         工具注册表                                   │   │
│  │                                                                     │   │
│  │  平台公共工具 (ownerTid = "system")    → 所有租户可用               │   │
│  │  租户 A 私有工具 (ownerTid = "A")      → 只有租户 A 可用            │   │
│  │  租户 B 私有工具 (ownerTid = "B")      → 只有租户 B 可用            │   │
│  │                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 跨业务调用

如果租户 B 需要使用租户 A 的能力，**不走 Gateway**，而是直接调用 A 的服务：

```
┌─────────────┐                         ┌─────────────┐
│   租户 B    │ ──── 直接调用 ─────────▶ │  租户 A 的  │
│   服务      │      A 暴露的 API        │   服务      │
└─────────────┘                         └─────────────┘
```

**优点**：
- 租户 A 完全控制自己的工具
- A 可以对 B 的调用计费
- A 可以做自己的鉴权、限流、业务逻辑
- Gateway 保持简单

### 平台公共工具

平台提供的公共工具使用特殊的 `ownerTid`：

```bash
# 注册平台公共工具
POST /api/v1/admin/tools
{
  "toolName": "platform-search",
  "ownerTid": "system",  # 特殊标识，表示平台公共
  "description": "平台提供的搜索工具"
}
```

所有租户调用 `tools/list` 时都能看到 `ownerTid = "system"` 的工具。

### 查询逻辑

```sql
-- 租户 A 的 Client 调用 tools/list 时
SELECT * FROM tool 
WHERE owner_tid = 'A'           -- 自己的私有工具
   OR owner_tid = 'system'      -- 平台公共工具
```

---

## API 参考

### OAuth2 端点（标准协议）

| 端点 | 方法 | 说明 |
|------|------|------|
| `/.well-known/oauth-protected-resource` | GET | 资源服务器元数据 |
| `/.well-known/openid-configuration` | GET | 授权服务器元数据 |
| `/oauth2/token` | POST | 获取 Token |
| `/oauth2/jwks` | GET | 公钥集 |
| `/oauth2/revoke` | POST | 撤销 Token |
| `/oauth2/introspect` | POST | Token 内省 |

### MCP 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/mcp/v1` | POST | MCP JSON-RPC 端点 |
| `/mcp/v1/sse` | GET | MCP SSE 端点（实时通知） |

### 管理 API

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/v1/admin/oauth2-clients` | POST | 注册 OAuth2 Client |
| `/api/v1/admin/oauth2-clients` | GET | 列出所有 Client |
| `/api/v1/admin/oauth2-clients/{id}` | DELETE | 删除 Client |
| `/api/v1/admin/upstream-servers` | POST | 注册上游服务器（MCP/REST） |
| `/api/v1/admin/upstream-servers` | GET | 列出上游服务器 |
| `/api/v1/admin/upstream-servers/{id}` | DELETE | 删除上游服务器 |
| `/api/v1/admin/tools` | GET | 列出所有工具 |
| `/api/v1/admin/tools/{id}/enable` | PUT | 启用工具 |
| `/api/v1/admin/tools/{id}/disable` | PUT | 禁用工具 |

### MCP 协议方法

| 方法 | 说明 | 必须 |
|------|------|------|
| `initialize` | 握手，交换版本和能力 | ✅ 必须第一个调用 |
| `ping` | 心跳检测 | 可选 |
| `tools/list` | 获取可用工具列表 | 可选 |
| `tools/call` | 调用指定工具 | 可选 |

### 请求/响应示例

#### 获取 Token

```bash
POST /oauth2/token
Content-Type: application/x-www-form-urlencoded
Authorization: Basic base64(client_id:client_secret)

grant_type=client_credentials&scope=mcp:tools-basic
```

```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "token_type": "Bearer",
  "expires_in": 3600
}
```

#### MCP initialize

```bash
POST /mcp/v1
Authorization: Bearer <token>
Content-Type: application/json

{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2024-11-05",
    "capabilities": {},
    "clientInfo": { "name": "my-agent", "version": "1.0.0" }
  }
}
```

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2024-11-05",
    "serverInfo": { "name": "MCP Gateway", "version": "1.0.0" },
    "capabilities": { "tools": {} }
  }
}
```

#### MCP tools/list

```bash
POST /mcp/v1
Authorization: Bearer <token>
Content-Type: application/json

{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/list",
  "params": {}
}
```

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "tools": [
      {
        "name": "weather",
        "description": "获取天气信息",
        "inputSchema": {
          "type": "object",
          "properties": {
            "city": { "type": "string", "description": "城市名称" }
          },
          "required": ["city"]
        }
      }
    ]
  }
}
```

#### MCP tools/call

```bash
POST /mcp/v1
Authorization: Bearer <token>
Content-Type: application/json

{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "weather",
    "arguments": { "city": "Beijing" }
  }
}
```

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "{\"temperature\": 25, \"condition\": \"晴\"}"
      }
    ]
  }
}
```

---

## 快速开始

### 1. 管理员：注册 Client

```bash
curl -X POST http://localhost:8080/api/v1/admin/oauth2-clients \
  -H "Content-Type: application/json" \
  -d '{"clientId": "demo-agent", "scopes": ["mcp:tools-basic"]}'
```

### 2. Client：获取 Token 并调用 MCP

```bash
# 获取 Token
TOKEN=$(curl -s -X POST http://localhost:8080/oauth2/token \
  -u "demo-agent:返回的secret" \
  -d "grant_type=client_credentials" | jq -r '.access_token')

# 调用 MCP
curl -X POST http://localhost:8080/mcp/v1 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```

### 3. 管理员：注册上游 MCP Server

```bash
curl -X POST http://localhost:8080/api/v1/admin/upstream-servers \
  -H "Content-Type: application/json" \
  -d '{
    "name": "weather-server",
    "url": "https://weather-mcp.example.com/mcp",
    "serverType": "MCP"
  }'
```

---

## 常见问题

### Q: Token 过期了怎么办？

A: 使用官方 SDK 或 Spring Security OAuth2 Client 会自动刷新。手动实现需要检测 401 响应后重新获取 Token。

### Q: 怎么知道工具列表变化了？

A: 两种方式：
1. 连接 `/mcp/v1/sse` 接收实时通知
2. 定期调用 `tools/list`，比较返回的 `_meta.version`

### Q: 不同租户的工具会混在一起吗？

A: 不会。每个 Client 只能看到：
- 自己租户的私有工具
- 平台公共工具（`ownerTid = "system"`）

### Q: 租户 B 想用租户 A 的工具怎么办？

A: **不走 Gateway**，直接调用租户 A 暴露的服务 API。这样 A 可以完全控制自己的工具，包括鉴权、限流、计费等。
