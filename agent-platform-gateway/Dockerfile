# Stage 1: Build
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build
COPY . .
RUN chmod +x mvnw 2>/dev/null || true
RUN ./mvnw -pl agent-platform-common,agent-platform-gateway -am package -DskipTests -q 2>/dev/null || \
    mvn -pl agent-platform-common,agent-platform-gateway -am package -DskipTests -q

# Stage 2: Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app

# Create non-root user
RUN groupadd -r appuser && useradd -r -g appuser appuser

COPY --from=builder /build/agent-platform-gateway/target/*.jar app.jar

# JVM tuning for containers
ENV JAVA_OPTS="-XX:+UseZGC \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseStringDeduplication \
  -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

USER appuser

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
