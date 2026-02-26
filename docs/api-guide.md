# Agent Platform API 使用指南

## 目录

- [认证](#认证)
- [向量存储 API](#向量存储-api)
- [Prompt 管理 API](#prompt-管理-api)
- [LLM 调用 API](#llm-调用-api)
- [Memory API](#memory-api)
- [MCP 协议调用](#mcp-协议调用)

---

## 认证

所有 API 都需要 Bearer Token 认证。

### 获取 Token

```bash
# 使用 OAuth2 Client Credentials 获取 Token
curl -X POST http://localhost:8080/oauth2/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials" \
  -d "client_id=your-client-id" \
  -d "client_secret=your-client-secret" \
  -d "scope=mcp:tools"
```

### 使用 Token

```bash
curl -X GET http://localhost:8080/api/v1/prompts \
  -H "Authorization: Bearer <your-token>"
```

---

## 向量存储 API

### 存储文档

将文档转换为向量并存储到指定 Collection。

```bash
curl -X POST http://localhost:8080/api/v1/vectors/store \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "collection": "knowledge_base",
    "documents": [
      {
        "content": "Agent Platform 是一个面向多租户的 Agent 能力网关",
        "metadata": {
          "source": "readme",
          "category": "introduction"
        }
      },
      {
        "content": "平台支持 LLM 调用、向量存储、Prompt 管理等能力",
        "metadata": {
          "source": "readme",
          "category": "features"
        }
      }
    ]
  }'
```

**响应**:
```json
{
  "success": true,
  "collection": "knowledge_base",
  "document_ids": ["doc-uuid-1", "doc-uuid-2"],
  "count": 2
}
```

### 向量检索

根据查询文本进行相似度检索。

```bash
curl -X POST http://localhost:8080/api/v1/vectors/search \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "collection": "knowledge_base",
    "query": "Agent Platform 有什么功能",
    "topK": 5,
    "threshold": 0.7
  }'
```

**响应**:
```json
{
  "success": true,
  "collection": "knowledge_base",
  "query": "Agent Platform 有什么功能",
  "results": [
    {
      "content": "平台支持 LLM 调用、向量存储、Prompt 管理等能力",
      "score": 0.92,
      "metadata": {
        "source": "readme",
        "category": "features"
      }
    }
  ],
  "count": 1
}
```

### 删除向量

```bash
curl -X DELETE http://localhost:8080/api/v1/vectors \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "collection": "knowledge_base",
    "documentIds": ["doc-uuid-1", "doc-uuid-2"]
  }'
```

### 生成 Embedding

```bash
curl -X POST http://localhost:8080/api/v1/vectors/embed \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "texts": ["Hello world", "你好世界"]
  }'
```

---

## Prompt 管理 API

### 创建模板

```bash
curl -X POST http://localhost:8080/api/v1/prompts \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "chat_assistant",
    "description": "通用对话助手 Prompt",
    "template": "你是 {{role}}，一个专业的 {{domain}} 助手。\n\n用户问题：{{question}}\n\n请提供详细的回答。",
    "variables": ["role", "domain", "question"]
  }'
```

**响应**:
```json
{
  "templateId": "uuid",
  "tenantId": "tenant-1",
  "name": "chat_assistant",
  "version": 1,
  "status": "ACTIVE",
  "template": "你是 {{role}}，一个专业的 {{domain}} 助手...",
  "variables": ["role", "domain", "question"],
  "createdAt": "2024-01-01T00:00:00Z"
}
```

### 列出模板

```bash
curl -X GET http://localhost:8080/api/v1/prompts \
  -H "Authorization: Bearer <token>"
```

### 获取模板

```bash
# 获取最新版本
curl -X GET http://localhost:8080/api/v1/prompts/chat_assistant \
  -H "Authorization: Bearer <token>"

# 获取指定版本
curl -X GET "http://localhost:8080/api/v1/prompts/chat_assistant?version=1" \
  -H "Authorization: Bearer <token>"
```

### 创建新版本

```bash
curl -X POST http://localhost:8080/api/v1/prompts/chat_assistant/versions \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "template": "你是 {{role}}，一个资深的 {{domain}} 专家。\n\n用户问题：{{question}}\n\n请提供专业、详细的回答。",
    "description": "优化了角色描述"
  }'
```

### 渲染模板

```bash
curl -X POST http://localhost:8080/api/v1/prompts/chat_assistant/render \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "role": "AI 助手",
    "domain": "软件开发",
    "question": "如何设计一个高可用系统？"
  }'
```

**响应**:
```json
{
  "template_name": "chat_assistant",
  "rendered": "你是 AI 助手，一个专业的 软件开发 助手。\n\n用户问题：如何设计一个高可用系统？\n\n请提供详细的回答。"
}
```

---

## LLM 调用 API

### 配置供应商

```bash
curl -X POST http://localhost:8080/api/v1/llm/providers \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "openai",
    "type": "OPENAI",
    "apiKey": "sk-xxx",
    "baseUrl": "https://api.openai.com",
    "enabled": true
  }'
```

### 配置模型

```bash
curl -X POST http://localhost:8080/api/v1/llm/models \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "modelId": "gpt-4",
    "providerId": "provider-uuid",
    "displayName": "GPT-4",
    "inputTokenPrice": 0.03,
    "outputTokenPrice": 0.06,
    "enabled": true
  }'
```

### 查看用量

```bash
curl -X GET http://localhost:8080/api/v1/llm/usage \
  -H "Authorization: Bearer <token>"
```

---

## Memory API

### 创建命名空间

```bash
curl -X POST http://localhost:8080/api/v1/memory/namespaces \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "user_sessions",
    "description": "用户会话记忆"
  }'
```

### 列出命名空间

```bash
curl -X GET http://localhost:8080/api/v1/memory/namespaces \
  -H "Authorization: Bearer <token>"
```

---

## MCP 协议调用

MCP (Model Context Protocol) 是 Agent 服务调用平台能力的主要方式。

### 列出可用 Tool

```bash
curl -X POST http://localhost:8080/mcp/v1/messages \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/list"
  }'
```

**响应**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "tools": [
      {
        "name": "vector_store",
        "description": "存储文档到向量库",
        "inputSchema": {...}
      },
      {
        "name": "vector_search",
        "description": "向量相似度检索",
        "inputSchema": {...}
      },
      {
        "name": "llm_chat",
        "description": "LLM 对话",
        "inputSchema": {...}
      }
    ]
  }
}
```

### 调用 Tool

```bash
curl -X POST http://localhost:8080/mcp/v1/messages \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/call",
    "params": {
      "name": "vector_search",
      "arguments": {
        "collection": "knowledge_base",
        "query": "如何使用 Agent Platform",
        "top_k": 5
      }
    }
  }'
```

### RAG 查询

```bash
curl -X POST http://localhost:8080/mcp/v1/messages \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {
      "name": "rag_query",
      "arguments": {
        "collection": "knowledge_base",
        "query": "Agent Platform 的核心功能是什么？",
        "top_k": 3,
        "model": "gpt-4"
      }
    }
  }'
```

### Prompt 渲染

```bash
curl -X POST http://localhost:8080/mcp/v1/messages \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 4,
    "method": "tools/call",
    "params": {
      "name": "prompt_render",
      "arguments": {
        "template_name": "chat_assistant",
        "variables": {
          "role": "技术顾问",
          "domain": "云原生",
          "question": "什么是 Kubernetes？"
        }
      }
    }
  }'
```

---

## 错误处理

### 错误响应格式

```json
{
  "error": {
    "code": "UNAUTHORIZED",
    "message": "Invalid or expired token",
    "details": {}
  }
}
```

### 常见错误码

| 错误码 | HTTP 状态码 | 说明 |
|--------|-------------|------|
| `UNAUTHORIZED` | 401 | 未认证或 Token 无效 |
| `FORBIDDEN` | 403 | 无权限访问 |
| `NOT_FOUND` | 404 | 资源不存在 |
| `RATE_LIMITED` | 429 | 请求频率超限 |
| `QUOTA_EXCEEDED` | 429 | 配额超限 |
| `INTERNAL_ERROR` | 500 | 服务器内部错误 |

---

## SDK 集成示例

### Python

```python
import requests

class AgentPlatformClient:
    def __init__(self, base_url, token):
        self.base_url = base_url
        self.headers = {
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json"
        }
    
    def vector_search(self, collection, query, top_k=5):
        response = requests.post(
            f"{self.base_url}/api/v1/vectors/search",
            headers=self.headers,
            json={
                "collection": collection,
                "query": query,
                "topK": top_k
            }
        )
        return response.json()
    
    def render_prompt(self, template_name, variables):
        response = requests.post(
            f"{self.base_url}/api/v1/prompts/{template_name}/render",
            headers=self.headers,
            json=variables
        )
        return response.json()

# 使用示例
client = AgentPlatformClient("http://localhost:8080", "your-token")
results = client.vector_search("knowledge_base", "Agent Platform 功能")
print(results)
```

### Java (Spring WebClient)

```java
@Service
public class AgentPlatformClient {
    
    private final WebClient webClient;
    
    public AgentPlatformClient(@Value("${agent-platform.url}") String baseUrl,
                               @Value("${agent-platform.token}") String token) {
        this.webClient = WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Bearer " + token)
            .build();
    }
    
    public Mono<Map<String, Object>> vectorSearch(String collection, String query, int topK) {
        return webClient.post()
            .uri("/api/v1/vectors/search")
            .bodyValue(Map.of(
                "collection", collection,
                "query", query,
                "topK", topK
            ))
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<>() {});
    }
}
```
