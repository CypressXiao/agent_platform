# v2/planner — 规划服务

本包实现 LLM 驱动的任务规划与执行服务，支持多种执行策略，作为 v2 可选子系统，通过配置开关启用。

**启用方式**: `agent-platform.planner.enabled=true`

## 架构概览

```
┌─────────────────────────────────────────────────────────────────┐
│  PlanningEngine（策略调度器）                                    │
│     └─► 选择策略 → 执行 → 保存记忆（可选）                       │
├─────────────────────────────────────────────────────────────────┤
│  内置策略                                                        │
│  ├─ PlanThenExecuteStrategy  先规划后执行                        │
│  ├─ ReActLoopStrategy        ReAct 循环                          │
│  └─ HumanInLoopStrategy      关键步骤需人工审批                  │
├─────────────────────────────────────────────────────────────────┤
│  BaseExecutionStrategy（抽象基类）                               │
│     └─► 提供钩子方法，SDK 用户可覆盖自定义                       │
└─────────────────────────────────────────────────────────────────┘
```

## 执行策略

| 策略 | 说明 | 特点 |
|------|------|------|
| `plan_then_execute` | LLM 一次性生成完整步骤，顺序执行 | 简单，无反馈 |
| `react` | Reason → Act → Observe 循环 | 动态调整，每步反馈 |
| `human_in_loop` | 关键步骤暂停等待人工审批 | 可控，高风险操作需确认 |

## 文件清单

### 数据模型（model/）

| 文件 | 说明 |
|------|------|
| `Plan.java` | 规划实体，包含目标、步骤列表、策略类型、状态、LLM 信息 |

### 策略（strategy/）

| 文件 | 说明 |
|------|------|
| `ExecutionStrategy.java` | 策略接口，定义 `plan()`、`executeStep()`、`onStepComplete()` |
| `BaseExecutionStrategy.java` | 抽象基类，提供钩子方法供 SDK 用户覆盖 |
| `PlanContext.java` | 执行上下文，包含 `sessionId`、`memoryEnabled`、`state` 等 |
| `StepResult.java` | 步骤执行结果 |
| `NextAction.java` | 下一步动作枚举：`CONTINUE`/`REPLAN`/`WAIT_APPROVAL`/`END`/`FAILED` |
| `StrategyRegistry.java` | 策略注册表 |

#### 抽象策略类（SDK 用户继承）

| 文件 | 说明 | 必须实现的方法 |
|------|------|----------------|
| `PlanThenExecuteStrategy.java` | 先规划后执行策略 | `buildPlanningSystemPrompt()`, `buildPlanningUserPrompt()` |
| `ReActLoopStrategy.java` | ReAct 循环策略 | `buildReActSystemPrompt()`, `buildReActUserPrompt()` |
| `HumanInLoopStrategy.java` | 人工审批策略 | `buildPlanningSystemPrompt()`, `buildPlanningUserPrompt()`, `getApprovalRequiredTools()` |

#### 默认实现（开箱即用）

| 文件 | 说明 |
|------|------|
| `DefaultPlanThenExecuteStrategy.java` | 默认先规划后执行实现 |
| `DefaultReActStrategy.java` | 默认 ReAct 实现 |
| `DefaultHumanInLoopStrategy.java` | 默认人工审批实现 |

### 规划引擎

| 文件 | 说明 |
|------|------|
| `PlanningEngine.java` | 策略调度器，负责选择策略、执行步骤、保存记忆 |

### 内置工具

| 文件 | 工具名 | 说明 |
|------|--------|------|
| `PlanCreateTool.java` | `plan_create` | 创建并执行规划，支持 `strategy` 参数 |
| `PlanExecuteTool.java` | `plan_execute` | 继续执行暂停的规划（审批后） |
| `PlanStatusTool.java` | `plan_status` | 查询规划状态 |

## SDK 使用方式

### 方式 1：直接使用内置策略

```java
// 使用默认策略（plan_then_execute）
plan_create(goal="帮我查询天气")

// 使用 ReAct 策略
plan_create(goal="帮我订机票", strategy="react")

// 使用 Human-in-the-Loop 策略
plan_create(goal="帮我转账", strategy="human_in_loop")
```

### 方式 2：继承基类自定义策略

```java
public class MyReActStrategy extends ReActLoopStrategy {
    
    // 自定义 System Prompt
    @Override
    protected String buildReActSystemPrompt(List<ToolInfo> tools, PlanContext context) {
        return "我的自定义 Prompt";
    }
    
    // 自定义记忆查询（需开启 memoryEnabled）
    @Override
    protected List<Map<String, Object>> queryMemories(CallerIdentity identity, String goal, PlanContext context) {
        return memoryService.query(identity, goal, "semantic");
    }
    
    // 自定义记忆保存
    @Override
    public void saveToMemory(CallerIdentity identity, String goal, PlanContext context, boolean success) {
        memoryService.save(identity, buildSummary(goal, context));
    }
}
```

## 记忆开关

```
memoryEnabled = false (默认)
    └─► 不查询记忆，不保存记忆

memoryEnabled = true
    └─► 用户需要自己实现 queryMemories() 和 saveToMemory()
```

## 依赖关系

- 依赖 `llm_chat` 内置工具（LLM Router 子系统）进行 LLM 调用
- 可选依赖 Memory 模块（通过 `memoryEnabled` 开关控制）
