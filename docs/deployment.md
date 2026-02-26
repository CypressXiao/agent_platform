# Agent Platform 部署指南

## 目录

- [环境要求](#环境要求)
- [本地开发](#本地开发)
- [Docker Compose 部署](#docker-compose-部署)
- [环境变量配置](#环境变量配置)
- [健康检查](#健康检查)

---

## 环境要求

| 组件 | 版本要求 | 说明 |
|------|----------|------|
| JDK | 21+ | 推荐 GraalVM 或 Eclipse Temurin |
| Maven | 3.9+ | 构建工具 |
| MySQL | 8.0+ | 主数据库 |
| Redis | 7.0+ | 缓存、限流、会话 |
| Milvus | 2.3+ | 向量数据库（可选，启用向量模块时需要） |

---

## 本地开发

### 1. 启动依赖服务

```bash
# 启动 MySQL
docker run -d --name mysql \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=agent_platform \
  -p 3306:3306 \
  mysql:8.0

# 启动 Redis
docker run -d --name redis \
  -p 6379:6379 \
  redis:7-alpine

# 启动 Milvus（可选，向量模块需要）
docker run -d --name milvus \
  -p 19530:19530 \
  -p 9091:9091 \
  milvusdb/milvus:v2.3.0 \
  milvus run standalone
```

### 2. 配置环境变量

```bash
export SPRING_PROFILES_ACTIVE=dev
export OPENAI_API_KEY=sk-xxx  # 如果使用 OpenAI
```

### 3. 启动应用

```bash
cd agent-platform
mvn spring-boot:run -pl agent-platform-gateway
```

### 4. 访问服务

- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **API 文档**: http://localhost:8080/v3/api-docs
- **健康检查**: http://localhost:8080/actuator/health

---

## Docker Compose 部署

### docker-compose.yml

```yaml
version: '3.8'

services:
  # MySQL 数据库
  mysql:
    image: mysql:8.0
    container_name: agent-platform-mysql
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:-root}
      MYSQL_DATABASE: agent_platform
    ports:
      - "3306:3306"
    volumes:
      - mysql_data:/var/lib/mysql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 10s
      timeout: 5s
      retries: 5

  # Redis 缓存
  redis:
    image: redis:7-alpine
    container_name: agent-platform-redis
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

  # Milvus 向量数据库（可选）
  milvus:
    image: milvusdb/milvus:v2.3.0
    container_name: agent-platform-milvus
    command: milvus run standalone
    environment:
      ETCD_USE_EMBED: "true"
      COMMON_STORAGETYPE: local
    ports:
      - "19530:19530"
      - "9091:9091"
    volumes:
      - milvus_data:/var/lib/milvus
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9091/healthz"]
      interval: 30s
      timeout: 10s
      retries: 5
    profiles:
      - vector

  # Agent Platform Gateway
  gateway:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: agent-platform-gateway
    environment:
      SPRING_PROFILES_ACTIVE: docker
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/agent_platform?useSSL=false&allowPublicKeyRetrieval=true
      SPRING_DATASOURCE_USERNAME: root
      SPRING_DATASOURCE_PASSWORD: ${MYSQL_ROOT_PASSWORD:-root}
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATA_REDIS_PORT: 6379
      MILVUS_HOST: milvus
      MILVUS_PORT: 19530
      OPENAI_API_KEY: ${OPENAI_API_KEY:-}
    ports:
      - "8080:8080"
    depends_on:
      mysql:
        condition: service_healthy
      redis:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 5

volumes:
  mysql_data:
  redis_data:
  milvus_data:
```

### Dockerfile

```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app
COPY . .
RUN ./mvnw clean package -DskipTests -pl agent-platform-gateway -am

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=builder /app/agent-platform-gateway/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 启动命令

```bash
# 基础服务（不含向量模块）
docker-compose up -d

# 包含向量模块
docker-compose --profile vector up -d

# 查看日志
docker-compose logs -f gateway

# 停止服务
docker-compose down
```

---

## 环境变量配置

### 必需配置

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `SPRING_DATASOURCE_URL` | MySQL 连接 URL | `jdbc:mysql://localhost:3306/agent_platform` |
| `SPRING_DATASOURCE_USERNAME` | MySQL 用户名 | `root` |
| `SPRING_DATASOURCE_PASSWORD` | MySQL 密码 | - |
| `SPRING_DATA_REDIS_HOST` | Redis 主机 | `localhost` |
| `SPRING_DATA_REDIS_PORT` | Redis 端口 | `6379` |

### 可选配置

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `MILVUS_HOST` | Milvus 主机 | `localhost` |
| `MILVUS_PORT` | Milvus 端口 | `19530` |
| `OPENAI_API_KEY` | OpenAI API Key | - |
| `OPENAI_BASE_URL` | OpenAI Base URL | `https://api.openai.com` |

### 模块开关

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `AGENT_PLATFORM_LLM_ROUTER_ENABLED` | 启用 LLM 路由 | `false` |
| `AGENT_PLATFORM_MEMORY_ENABLED` | 启用 Memory 服务 | `false` |
| `AGENT_PLATFORM_VECTOR_ENABLED` | 启用向量服务 | `false` |
| `AGENT_PLATFORM_PROMPT_ENABLED` | 启用 Prompt 管理 | `false` |

---

## 健康检查

### 端点

| 端点 | 说明 |
|------|------|
| `/actuator/health` | 应用健康状态 |
| `/actuator/health/liveness` | 存活探针 |
| `/actuator/health/readiness` | 就绪探针 |
| `/actuator/prometheus` | Prometheus 指标 |

### Kubernetes 探针配置示例

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 5
```

---

## 常见问题

### 1. 数据库连接失败

确保 MySQL 已启动，且连接参数正确：
```bash
mysql -h localhost -u root -p -e "SELECT 1"
```

### 2. Milvus 连接失败

检查 Milvus 服务状态：
```bash
curl http://localhost:9091/healthz
```

### 3. OpenAI API 调用失败

确保 `OPENAI_API_KEY` 已正确设置，且网络可访问 OpenAI API。
