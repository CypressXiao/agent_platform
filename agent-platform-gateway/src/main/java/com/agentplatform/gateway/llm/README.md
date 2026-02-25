# v2/llm — LLM 路由服务

本包实现多供应商 LLM 统一路由、配额管理和用量追踪，作为 v2 可选子系统，通过配置开关启用。

**启用方式**: `agent-platform.v2.llm-router.enabled=true`

## 文件清单

### 数据模型（model/）

| 文件 | 说明 |
|------|------|
| `LlmProvider.java` | LLM 供应商实体，对应 `llm_provider` 表。包含供应商名称、Base URL、API Key 引用（Vault 路径）、状态 |
| `LlmModelConfig.java` | 模型配置实体，对应 `llm_model_config` 表。包含模型名称、所属供应商、最大 Token 数、输入/输出定价、是否支持流式和工具调用、备用模型 ID、状态 |
| `LlmTenantQuota.java` | 租户配额实体，对应 `llm_tenant_quota` 表。包含 RPM（每分钟请求数）限制、TPM（每分钟 Token 数）限制、月度 Token 预算、当前已用量、重置时间 |
| `LlmUsageRecord.java` | 用量记录实体，对应 `llm_usage_record` 表。包含租户、模型、Trace ID、输入/输出/总 Token 数、费用、延迟 |

### 数据访问（repository/）

| 文件 | 说明 |
|------|------|
| `LlmProviderRepository.java` | 供应商数据访问，支持按状态查询 |
| `LlmModelConfigRepository.java` | 模型配置数据访问，支持按供应商、状态、模型 ID+状态查询 |
| `LlmTenantQuotaRepository.java` | 租户配额数据访问，支持按租户和按租户+模型查询 |
| `LlmUsageRecordRepository.java` | 用量记录数据访问，支持按租户+时间范围查询、按租户+时间聚合 Token 总量 |

### 服务层

| 文件 | 说明 |
|------|------|
| `LlmRouterService.java` | LLM 路由核心服务。完整调用链路：① 解析模型配置 ② 检查租户配额（月度 Token 预算） ③ 从 Vault 获取 API Key ④ 构建请求并调用 LLM Provider ⑤ 调用失败时自动 fallback 到备用模型 ⑥ 记录用量（Token 数、费用、延迟）⑦ 更新配额已用量 |

### 内置工具

| 文件 | 工具名 | 说明 |
|------|--------|------|
| `LlmChatTool.java` | `llm_chat` | 统一聊天补全。接收 `model`（模型名/别名）、`messages`（消息列表）、`temperature`（可选）、`max_tokens`（可选），路由到对应供应商并返回结果 |
| `LlmEmbedTool.java` | `llm_embed` | 文本嵌入。接收 `model`（嵌入模型名）、`texts`（文本列表），调用供应商的 `/v1/embeddings` 端点，返回嵌入向量 |

### 管理 API

| 文件 | 说明 |
|------|------|
| `LlmAdminController.java` | 管理 API（`/api/v2/llm/**`）。支持供应商管理（增/查）、模型配置管理（增/查）、租户配额设置/查询、用量统计（按时间范围聚合请求数/Token 数/费用） |

## 路由与 Fallback 流程

```
Agent 调用 llm_chat(model="gpt-4o", messages=[...])
  → LlmRouterService.chat()
    → 查找 model config (gpt-4o, status=active)
    → 检查租户配额（月度 Token 预算）
    → 获取 Provider API Key (Vault)
    → POST {provider.baseUrl}/v1/chat/completions
    → 成功 → 记录用量 → 返回结果
    → 失败 → 检查 fallbackModelId
           → 有备用模型 → 递归调用备用模型
           → 无备用模型 → 抛出 LLM_PROVIDER_ERROR
```

## 计费模型

- 每次调用记录 `promptTokens`、`completionTokens`、`totalTokens`
- 费用 = `promptTokens * inputPricePerToken + completionTokens * outputPricePerToken`
- 月度配额按 `totalTokens` 累计，到达 `monthlyTokenBudget` 后拒绝请求
