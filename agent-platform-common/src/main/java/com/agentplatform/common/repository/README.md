# repository — 数据访问层

本包定义了所有核心实体的 Spring Data JPA Repository 接口。

## 文件清单

| 文件 | 说明 |
|------|------|
| `TenantRepository.java` | 租户数据访问，支持按状态查询 |
| `UpstreamServerRepository.java` | 上游服务器数据访问，支持按所属租户、服务器类型、健康状态查询 |
| `ToolRepository.java` | 工具数据访问，核心查询包括：按租户+状态查询、按来源查询、`findAccessibleByName`（聚合自有+系统工具）、`findSystemTools` |
| `GrantRepository.java` | 授权数据访问，核心查询包括：`findActiveByGranteeTid`（查活跃授权）、`findActiveGrant`（原生 SQL + JSONB `@>` 操作符检查工具包含关系）、`revoke`（批量撤销） |
| `AuditLogRepository.java` | 审计日志数据访问，支持按租户分页、按工具名分页、按时间范围查询、按 Trace ID 追踪 |
| `PolicyRepository.java` | 策略数据访问，支持按租户和资源类型查询 |

## 设计要点

- 所有 Repository 继承 `JpaRepository`，自动获得 CRUD + 分页能力
- `GrantRepository.findActiveGrant` 使用 PostgreSQL 原生查询（`nativeQuery = true`），利用 JSONB `@>` 操作符判断工具 ID 是否在授权列表中
- `GrantRepository.revoke` 使用 `@Modifying` 注解实现批量更新
- 查询方法遵循 Spring Data 命名约定，自动生成 SQL
