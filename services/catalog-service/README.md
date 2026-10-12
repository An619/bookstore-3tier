# Catalog microservice

A Java 21 / Spring Boot service added to the Bookstore DevOps lab so the project can demonstrate Maven builds alongside the existing Node.js and React components.

## Endpoints

- `GET /api/health` — liveness response
- `GET /api/catalog/books` — sample catalog response

The sample catalog is intentionally in-memory for CI and container practice. It is not yet wired to PostgreSQL or the existing frontend.

## Build and test

From the repository root:

```bash
mvn -B -f services/catalog-service/pom.xml clean verify
docker build -t bookstore-catalog:local services/catalog-service
docker run --rm -p 8081:8081 bookstore-catalog:local
curl -fsS http://localhost:8081/api/health
curl -fsS http://localhost:8081/api/catalog/books
```

The service uses Java 21 and Maven. The existing React and Node.js components continue to use npm.
