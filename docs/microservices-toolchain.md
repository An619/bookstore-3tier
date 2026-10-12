# Microservices and DevOps toolchain lab

## Current application services

- `frontend`: React/Vite served by Nginx.
- `backend`: existing Node.js/Express API connected to PostgreSQL.
- `services/catalog-service`: separate Java 21/Spring Boot service built and tested with Maven. It exposes `/api/health` and `/api/catalog/books`; sample data is in-memory and is not yet the source of truth for the frontend.

This is a staged migration lab, not a claim that every business domain has already been extracted. The legacy Node API remains for existing app functionality.

## Tool responsibilities

1. **Jenkins** orchestrates tests, builds and image creation.
2. **Maven** runs the Java catalog service's tests and packages its JAR. Node and React remain npm projects.
3. **SonarQube** analyzes JavaScript and Java sources using the root `sonar-project.properties`.
4. **Docker** packages frontend, Node API and Java catalog service.
5. **Kubernetes/Helm** deploys the catalog as its own Deployment and ClusterIP Service, in addition to the existing app resources.
6. **Argo CD** reconciles the Helm release after environment image tags/configuration are committed to Git.

## Local validation

```bash
mvn -B -f services/catalog-service/pom.xml clean verify
docker compose up --build -d
curl -fsS http://localhost:8081/api/health
curl -fsS http://localhost:8081/api/catalog/books
helm lint ./helm/bookstore
helm template bookstore ./helm/bookstore --namespace bookstore
```

The catalog service is intentionally separate from the existing frontend routing at this stage. Routing the user interface through it is a later migration task. Do not use the in-memory sample endpoint as production inventory data.
