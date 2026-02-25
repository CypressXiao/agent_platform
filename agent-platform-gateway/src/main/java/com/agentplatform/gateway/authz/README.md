# authz — 授权层

本包实现平台的多层授权机制：Scope 校验、策略引擎、跨租户 Grant 检查。

## 文件清单

| 文件 | 说明 |
|------|------|
| `ScopeValidator.java` | Scope 校验器。检查调用方 JWT Token 中的 Scope 是否满足目标 Tool 的要求。内置 Tool 需要 `mcp:tools-basic`，上游 Tool 需要 `mcp:tools-basic`，管理操作需要 `mcp:tools-admin` |
| `PolicyEngine.java` | OPA 策略引擎集成。将调用方身份和目标 Tool 信息发送到 OPA（`/v1/data/authz/allow`），获取 allow/deny 决策。支持通过配置开关禁用（`opa.enabled=false` 时默认放行） |
| `GrantEngine.java` | 跨租户授权引擎。当调用方访问非自有 Tool 时，检查是否存在有效的 Grant 记录。支持 Grant 过期检查、撤销操作、Redis 缓存加速查询 |

## 授权流程

```
请求进入 → ScopeValidator（Scope 是否足够？）
         → PolicyEngine（OPA 策略是否允许？）
         → GrantEngine（跨租户时是否有 Grant？）
         → 通过 → 执行 Tool
```

## 设计原则

- **Deny by Default**: 无明确授权则拒绝
- **最小权限**: Tool 级别的细粒度 Scope 控制
- **不可越权**: v2 子系统复用同一授权链路，不能获得超出调用方的权限
