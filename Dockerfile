# syntax=docker/dockerfile:1.6
FROM maven:3.8.4-eclipse-temurin-17-alpine AS builder

WORKDIR /workspace

COPY pom.xml .
 
# Leverage BuildKit cache for Maven repo
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -T 1C dependency:go-offline

COPY src ./src
 
# Use cached Maven repo and quiet, parallel build
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -T 1C -DskipTests clean package

FROM eclipse-temurin:17-jre-alpine

# Download OTel Java agent at build time
RUN apk add --no-cache curl && \
    curl -L https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar \
    -o /opentelemetry-javaagent.jar

COPY --from=builder /workspace/target/*.war  /Marginal-tax-rate-calculator-0.0.1-SNAPSHOT.war

EXPOSE 8080

# Simple liveness check using Actuator health endpoint
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-javaagent:/opentelemetry-javaagent.jar", "-jar", "/Marginal-tax-rate-calculator-0.0.1-SNAPSHOT.war"]
