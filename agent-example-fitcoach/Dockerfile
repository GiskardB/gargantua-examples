# ═══════════════════════════════════════════════════════════════════
# FitCoach AI — Multi-stage Dockerfile (JVM + GraalVM native)
#
# Build targets:
#   runtime-jvm    — Eclipse Temurin 21 JRE + ZGC (~250 MB, ~2s startup)
#   runtime-native — GraalVM native binary on distroless (~50 MB, <100ms)
#
# Usage:
#   docker build --target runtime-jvm    -t fitcoach:jvm    .
#   docker build --target runtime-native -t fitcoach:native .
# ═══════════════════════════════════════════════════════════════════

# ─────────────────────────────────────────────────────────────────
# Stage 1 — Builder (Maven 3.9 + JDK 21)
# ─────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -B 2>/dev/null || true

COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn clean package -DskipTests -B

# ─────────────────────────────────────────────────────────────────
# Stage 2A — JVM Runtime (Eclipse Temurin 21 JRE on Ubuntu Jammy)
# Glibc-based (NOT Alpine/musl) because Gargantua's semantic routing
# pulls in ONNX Runtime (via langchain4j-embeddings), whose native
# libonnxruntime.so depends on libstdc++.so.6 — which is not available
# on Alpine. Ubuntu Jammy ships libstdc++ by default.
# ─────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy AS runtime-jvm

RUN groupadd --system appgroup && useradd --system --gid appgroup appuser

WORKDIR /app

COPY --from=builder /build/target/*.jar app.jar

RUN chown -R appuser:appgroup /app
USER appuser

ENV JAVA_OPTS="-XX:+UseZGC -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar app.jar"]

# ─────────────────────────────────────────────────────────────────
# Stage 2B — GraalVM Native Image Builder
# ─────────────────────────────────────────────────────────────────
FROM ghcr.io/graalvm/native-image-community:21 AS native-builder

WORKDIR /build
COPY --from=builder /build /build

RUN --mount=type=cache,target=/root/.m2 \
    mvn -Pnative native:compile -DskipTests -B

# ─────────────────────────────────────────────────────────────────
# Stage 2C — Distroless Native Runtime
# ─────────────────────────────────────────────────────────────────
FROM gcr.io/distroless/base-debian12 AS runtime-native

WORKDIR /app

COPY --from=native-builder /build/target/agent-example app

EXPOSE 8080

ENTRYPOINT ["/app/app"]
