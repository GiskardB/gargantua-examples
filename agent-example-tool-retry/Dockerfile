FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -q
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn clean package -DskipTests -q

# Glibc-based runtime (NOT Alpine/musl) — ONNX Runtime needs libstdc++.so.6.
FROM eclipse-temurin:21-jre-jammy
RUN groupadd --system app && useradd --system --gid app app
USER app
WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
