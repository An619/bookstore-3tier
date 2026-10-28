# Build context is the repository root: the Maven build also verifies the
# shared consumer-driven contract in contracts/, which lives outside the
# service directory.
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace/services/orders-service
COPY services/orders-service/pom.xml ./
COPY services/orders-service/src ./src
COPY contracts /workspace/contracts
RUN mvn -B -ntp verify

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Patch the Alpine packages with the same base image, then drop privileges.
# The base tag is pinned in the repository, the point release is not.
RUN apk upgrade --no-cache \
 && apk add --no-cache wget \
 && addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app
COPY --from=build --chown=10001:10001 /workspace/services/orders-service/target/orders-service.jar /app/app.jar
USER 10001:10001
EXPOSE 8082
ENV SERVER_PORT=8082
HEALTHCHECK --interval=30s --timeout=3s --start-period=25s --retries=3 CMD wget -qO- http://127.0.0.1:8082/actuator/health/readiness >/dev/null || exit 1
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/app/app.jar"]
