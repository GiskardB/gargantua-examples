FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -B 2>/dev/null || true
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn clean package -DskipTests -B

# Glibc-based runtime (NOT Alpine/musl) because Gargantua's semantic routing
# pulls in ONNX Runtime via langchain4j-embeddings, whose native libonnxruntime.so
# depends on libstdc++.so.6 — not available on Alpine. Ubuntu Jammy ships it.
FROM eclipse-temurin:21-jre-jammy
RUN groupadd --system app && useradd --system --gid app app
USER app
WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
