FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN for attempt in 1 2 3; do mvn -B -ntp verify && exit 0; echo "Maven attempt $attempt failed; retrying"; sleep $((attempt * 5)); done; exit 1

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache wget && addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app
COPY --from=build --chown=10001:10001 /workspace/target/catalog-service.jar /app/app.jar
USER 10001:10001
EXPOSE 8081
ENV SERVER_PORT=8081
HEALTHCHECK --interval=30s --timeout=3s --start-period=25s --retries=3 CMD wget -qO- http://127.0.0.1:8081/actuator/health/readiness >/dev/null || exit 1
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/app/app.jar"]
