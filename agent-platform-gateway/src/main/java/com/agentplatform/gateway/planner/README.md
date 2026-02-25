# v2/planner — 规划服务

本包实现 LLM 驱动的任务规划与执行服务，作为 v2 可选子系统，通过配置开关启用。

**启用方式**: `agent-platform.v2.planner.enabled=true`

## 文件清单

### 数据模型（model/）

| 文件 | 说明 |
|------|------|
| `Plan.java` | 规划实体，对应 `plan` 表。包含执行方租户、目标描述、Trace ID、JSONB 格式的步骤列表和上下文、状态（`created` / `executing` / `completed` / `failed`）、使用的 LLM 模型信息、Token 消耗 |

### 数据访问（repository/）

| 文件 | 说明 |
|------|------|
| `PlanRepository.java` | 规划数据访问，支持按执行方租户和状态查询 |

### 规划引擎

| 文件 | 说明 |
|------|------|
| `PlanningEngine.java` | 规划引擎核心。**创建阶段**：收集调用方可用的 Tool 列表，构建 Prompt，调用 LLM（通过 `llm_chat` 内置工具）生成步骤分解，验证步骤中引用的 Tool 是否可访问，持久化 Plan。**执行阶段**：逐步执行 Plan 中的步骤，每步通过 `ToolDispatcher.dispatchInternal()` 调用对应 Tool，记录每步结果，失败时标记并停止 |

### 内置工具

| 文件 | 工具名 | 说明 |
|------|--------|------|
| `PlanCreateTool.java` | `plan_create` | 创建规划。接收 `goal`（目标描述），返回 Plan ID、状态、步骤列表 |
| `PlanExecuteTool.java` | `plan_execute` | 执行规划。接收 `plan_id`，逐步执行并返回最终状态和各步结果 |
| `PlanStatusTool.java` | `plan_status` | 查询规划状态。接收 `plan_id`，返回 Plan 的完整信息（目标、状态、步骤、Token 消耗） |

### 管理 API

| 文件 | 说明 |
|------|------|
| `PlannerAdminController.java` | 管理 API（`/api/v2/planner/**`）。支持按租户查询 Plan 列表、获取 Plan 详情 |

## 规划流程

```
Agent 调用 plan_create(goal="...")
  → PlanningEngine.createPlan()
    → ToolAggregator.listTools()     # 获取可用工具
    → ToolDispatcher("llm_chat")     # 调用 LLM 生成步骤
    → 解析 LLM 返回的 JSON 步骤列表
    → 验证每个步骤引用的 Tool 可访问
    → 持久化 Plan
  → 返回 Plan ID

Agent 调用 plan_execute(plan_id="...")
  → PlanningEngine.executePlan()
    → 逐步执行:
      → ToolDispatcher.dispatchInternal(step.toolName, step.args)
      → 记录结果到 Plan.steps
    → 更新 Plan 状态
  → 返回执行结果
```

## 依赖关系

- 依赖 `llm_chat` 内置工具（LLM Router 子系统）进行 LLM 调用
- 如果 LLM 不可用，使用 fallback 策略生成简单步骤
