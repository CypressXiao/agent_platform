# router — 路由引擎

本包是 MCP Gateway 的核心路由层，负责 `tools/list` 聚合和 `tools/call` 分发。

## 文件清单

| 文件 | 说明 |
|------|------|
| `ToolAggregator.java` | 工具聚合器（对应 `tools/list`）。为调用方聚合可见的工具列表：① 自有租户的 active 工具 ② 系统内置工具 ③ 通过 Grant 共享的跨租户工具。结果经 OPA 策略过滤后按名称去重（自有优先），支持 Caffeine 缓存。内部定义 `ToolView` record 作为返回结构 |
| `ToolDispatcher.java` | 工具分发器（对应 `tools/call`）。核心调用链路：① 解析工具 → ② Scope 校验 → ③ OPA 策略检查 → ④ 跨租户 Grant 检查 → ⑤ 治理拦截（限流/熔断） → ⑥ 路由到处理器（builtin / upstream_mcp / upstream_rest） → ⑦ 审计记录。另提供 `dispatchInternal()` 方法供 v2 子系统内部调用（跳过 Scope 校验但保留策略+审计） |

## 调用链路

```
Agent 发起 tools/call
  → ToolDispatcher.dispatch()
    → resolveToolForCaller()     # 查找工具
    → ScopeValidator.validate()  # Scope 校验
    → PolicyEngine.evaluate()    # OPA 策略
    → GrantEngine.check()        # 跨租户授权（如需）
    → GovernanceInterceptor.preCheck()  # 限流 + 熔断
    → routeToHandler()           # 分发到 builtin / mcpProxy / restProxy
    → GovernanceInterceptor.postCheck() # 记录成功
    → AuditLogService.logSuccess()      # 审计
```

## 设计要点

- `dispatchInternal()` 是 v2 子系统（Workflow、Planner）调用外部 Tool 的入口，确保不可越权
- 路由使用 `switch` 表达式按 `sourceType` 分发，支持三种来源
- 上游服务器调用前检查健康状态，不健康则快速失败
