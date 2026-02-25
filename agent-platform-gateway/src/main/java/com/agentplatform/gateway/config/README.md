# config — 基础配置

本包负责 Spring Boot 应用的核心基础设施配置。

## 文件清单

| 文件 | 说明 |
|------|------|
| `SecurityConfig.java` | 生产环境安全配置。配置 OAuth 2.1 Resource Server（JWT 验证）、CSRF 禁用、无状态会话、公开端点白名单（`/actuator/**`、`/.well-known/**`）、Admin API 需要 `SCOPE_admin` 权限 |
| `DevSecurityConfig.java` | 开发环境安全配置（`@Profile("dev")`）。禁用 JWT 验证，放行所有请求，提供固定的 `CallerIdentity`（`dev-tenant`），方便本地调试 |
| `McpAuthenticationEntryPoint.java` | 自定义认证失败处理器，返回符合 MCP 协议的 JSON-RPC 错误格式（而非 Spring 默认 HTML） |
| `RedisConfig.java` | Redis 连接和序列化配置。使用 `Jackson2JsonRedisSerializer` 替代默认 JDK 序列化，配置 `StringRedisTemplate` |
| `WebClientConfig.java` | `WebClient.Builder` 配置。设置默认超时（连接 5s、读取 30s）、默认请求头、日志过滤器，用于调用上游服务 |

## 配置激活

- **生产环境**: 默认激活 `SecurityConfig`
- **开发环境**: `--spring.profiles.active=dev` 激活 `DevSecurityConfig`，覆盖安全策略
