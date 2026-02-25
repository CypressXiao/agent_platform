# governance — 治理层

本包实现 API 治理能力，包括限流和熔断。

## 文件清单

| 文件 | 说明 |
|------|------|
| `RateLimitService.java` | 限流服务。基于 Redis 滑动窗口算法，按 `tenantId:toolName` 粒度限流。默认每分钟 100 次（可通过配置调整）。使用 Redis `ZADD` + `ZRANGEBYSCORE` + `ZCARD` 实现精确的滑动窗口计数 |
| `CircuitBreakerService.java` | 熔断服务。基于 Resilience4j，按上游服务器粒度（`serverId`）创建独立的熔断器。默认配置：滑动窗口 10 次、失败率阈值 50%、半开状态允许 3 次探测、开路等待 30 秒。提供状态查询、成功/失败记录接口 |
| `GovernanceInterceptor.java` | 治理拦截器。在 Tool 调用前后执行治理检查：`preCheck()` 检查限流和熔断状态，`postCheck()` 记录调用结果（成功/失败）用于熔断器决策。内置 Tool 跳过治理检查 |

## 治理流程

```
Tool 调用前:
  → RateLimitService.tryAcquire()  # 限流检查
    → 超限 → 抛出 RATE_LIMITED (429)
  → CircuitBreakerService.isOpen() # 熔断检查
    → 开路 → 抛出 CIRCUIT_OPEN (503)

Tool 调用后:
  → CircuitBreakerService.recordSuccess/Failure() # 更新熔断器状态
```

## 设计要点

- 限流粒度为租户+工具，防止单个租户/工具耗尽资源
- 熔断粒度为上游服务器，一个服务器故障不影响其他服务器
- 内置 Tool 不经过治理层（无上游依赖，不需要限流/熔断）
