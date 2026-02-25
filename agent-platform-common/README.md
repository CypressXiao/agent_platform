# agent-platform-common — 共享模块

本模块定义了平台所有模块共享的核心组件，包括数据模型、数据访问层、DTO 和异常处理。

## 包结构

```
com.agentplatform.common/
├── model/        # 核心 JPA 实体（Tenant, Tool, Grant, AuditLog, ...）
├── repository/   # Spring Data JPA Repository 接口
├── dto/          # Admin API 请求/响应 DTO
└── exception/    # 统一错误码 + 全局异常处理器
```

## 模块职责

| 职责 | 说明 |
|------|------|
| 数据模型 | 7 个 JPA 实体 + 1 个 CallerIdentity record，映射到 PostgreSQL |
| 数据访问 | 6 个 Repository 接口，含自定义 JPQL 和原生 SQL 查询 |
| 接口契约 | 4 个 DTO 类，定义 Admin API 的输入格式 |
| 错误处理 | 25 种错误码枚举 + 统一异常类 + 全局异常处理器 |

## 依赖

- Spring Boot Starter Data JPA
- Hypersistence Utils（JSONB 支持）
- Lombok
- Jakarta Validation

## 被依赖方

- `agent-platform-gateway`（主应用模块）
