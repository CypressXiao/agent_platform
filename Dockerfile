FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# 复制 Maven 配置
COPY pom.xml .
COPY agent-platform-common/pom.xml agent-platform-common/
COPY agent-platform-gateway/pom.xml agent-platform-gateway/

# 下载依赖（利用 Docker 缓存）
RUN apk add --no-cache maven && \
    mvn dependency:go-offline -B

# 复制源代码
COPY agent-platform-common/src agent-platform-common/src
COPY agent-platform-gateway/src agent-platform-gateway/src

# 构建
RUN mvn clean package -DskipTests -pl agent-platform-gateway -am

# 运行时镜像
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# 创建非 root 用户
RUN addgroup -g 1000 app && \
    adduser -u 1000 -G app -s /bin/sh -D app

# 复制构建产物
COPY --from=builder /app/agent-platform-gateway/target/*.jar app.jar

# 切换到非 root 用户
USER app

# 暴露端口
EXPOSE 8080

# JVM 优化参数
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
