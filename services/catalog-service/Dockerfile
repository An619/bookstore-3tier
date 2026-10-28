# Build context is the repository root: the Maven build also verifies the
# shared consumer-driven contract in contracts/, which lives outside the
# service directory.
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace/services/catalog-service
COPY services/catalog-service/pom.xml ./
COPY services/catalog-service/src ./src
COPY contracts /workspace/contracts
RUN for attempt in 1 2 3; do mvn -B -ntp verify && exit 0; echo "Maven attempt $attempt failed; retrying"; sleep $((attempt * 5)); done; exit 1

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Patch the Alpine packages with the same base image, then drop privileges.
# The base tag is pinned in the repository, the point release is not.
RUN apk upgrade --no-cache \
 && apk add --no-cache wget \
 && addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app
COPY --from=build --chown=10001:10001 /workspace/services/catalog-service/target/catalog-service.jar /app/app.jar
USER 10001:10001
EXPOSE 8081
ENV SERVER_PORT=8081
HEALTHCHECK --interval=30s --timeout=3s --start-period=25s --retries=3 CMD wget -qO- http://127.0.0.1:8081/actuator/health/readiness >/dev/null || exit 1
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/app/app.jar"]
