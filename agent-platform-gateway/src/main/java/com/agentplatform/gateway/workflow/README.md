# v2/workflow — 编排引擎

本包实现基于 DAG（有向无环图）的工作流编排引擎，作为 v2 可选子系统，通过配置开关启用。

**启用方式**: `agent-platform.v2.workflow.enabled=true`

## 文件清单

### 数据模型（model/）

| 文件 | 说明 |
|------|------|
| `WorkflowGraph.java` | 工作流模板实体，对应 `workflow_graph` 表。包含图名称、描述、所属租户、版本号、JSONB 格式的 DAG 定义（节点列表 + 边列表）、状态（`draft` / `published`） |
| `WorkflowRun.java` | 工作流执行记录实体，对应 `workflow_run` 表。包含关联的图 ID/版本、执行方租户、Trace ID、JSONB 格式的输入/输出/各节点执行详情、状态（`running` / `completed` / `failed`）、延迟 |

### 数据访问（repository/）

| 文件 | 说明 |
|------|------|
| `WorkflowGraphRepository.java` | 工作流模板数据访问，支持按所属租户和状态查询 |
| `WorkflowRunRepository.java` | 工作流执行记录数据访问，支持按图 ID 和执行方租户+状态查询 |

### 执行引擎（engine/）

| 文件 | 说明 |
|------|------|
| `DagExecutionEngine.java` | DAG 执行引擎。核心逻辑：① 从 JSONB 定义解析节点和边 ② 拓扑排序确定执行顺序 ③ 同层节点并行执行（`CompletableFuture`） ④ 每个节点通过 `ToolDispatcher.dispatchInternal()` 调用对应 Tool ⑤ 支持条件判断（`condition` 字段）和输出映射（`outputMapping`） ⑥ 记录每个节点的执行结果和耗时 |

### 内置工具 & 管理 API

| 文件 | 说明 |
|------|------|
| `WorkflowRunTool.java` | 内置工具 `workflow_run`。接收 `graph_id` 和 `input` 参数，检查图是否已发布、调用方是否有权限，然后委托 `DagExecutionEngine` 执行 |
| `WorkflowAdminController.java` | 管理 API（`/api/v2/workflow/**`）。支持创建/更新/发布工作流模板、查询模板列表、查询执行记录 |

## DAG 定义格式

```json
{
  "nodes": [
    {"id": "n1", "toolName": "tool_a", "input": {"key": "value"}, "condition": null, "outputMapping": {"result": "$.data"}},
    {"id": "n2", "toolName": "tool_b", "input": {"key": "${n1.result}"}}
  ],
  "edges": [
    {"from": "n1", "to": "n2"}
  ]
}
```

## 权限模型

- 只有图的所属租户或通过 Grant 获得权限的租户可以执行
- 节点调用 Tool 时使用 `dispatchInternal()`，继承调用方身份，不可越权
