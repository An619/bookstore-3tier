FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/catalog-service.jar /app/catalog-service.jar
USER app
EXPOSE 8081
HEALTHCHECK --interval=20s --timeout=3s --start-period=30s --retries=3 CMD wget -q -O - http://127.0.0.1:8081/api/health >/dev/null || exit 1
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/app/catalog-service.jar"]
