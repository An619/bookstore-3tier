# Microservices migration status

This repository now contains a first independently deployable microservice: **Catalog Service** (Java 21, Spring Boot, Maven). It exposes:

- `GET /api/catalog/books`
- `GET /api/catalog/books/{id}`
- `GET /actuator/health`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`

The catalog currently uses a small in-memory seed dataset to keep the first migration slice self-contained and easy to run in a beginner lab. It is **not yet a production data design**: records reset on restart, and the existing Node.js API/database remain in the repository. The frontend is not yet switched to this service. Next engineering milestones are durable catalog persistence with catalog-owned data, frontend/API routing, and contract/integration tests.

## Run the service

From the repository root:

```bash
docker compose up --build -d catalog-service
curl -fsS http://localhost:8081/actuator/health/readiness
curl -fsS http://localhost:8081/api/catalog/books
curl -fsS http://localhost:8081/api/catalog/books/2
docker compose logs -f catalog-service
docker compose down
```

For a Maven-only local run, use Java 21 and Maven 3.9+:

```bash
cd services/catalog-service
mvn -B -ntp verify
mvn spring-boot:run
```

## Tool responsibilities

- **Maven** compiles/tests the Java service and packages the executable JAR.
- **Jenkins** runs Node and Maven validation, builds all images, performs Compose smoke tests, validates Helm charts, and can optionally publish immutable commit-SHA tags.
- **SonarQube** analyzes Java, Node and frontend source. Configure Jenkins credential `sonarqube-token` and `SONAR_HOST_URL`; install SonarScanner on the agent.
- **Docker** packages the Java service in a non-root runtime image.
- **Kubernetes/Helm** deploys the catalog independently from the legacy three-tier chart.
- **Argo CD** watches the catalog Helm chart from Git; its Application uses manual sync intentionally.

## Security and rollout notes

Do not use default local credentials in shared environments. Configure registry image references and pull permissions before syncing Argo CD. Keep the catalog in-memory implementation labeled as a migration slice until persistence, ownership and failure behavior are designed and tested.
