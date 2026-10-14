# Microservices cutover

The application runtime has been cut over from the legacy Node.js monolith to three independently deployable Java 21 / Spring Boot services. The old Node backend, its single shared database initialization script, and the legacy monolith Helm/Argo CD deployment entry have been removed from the repository. Docker Compose and CI now exercise only the microservices runtime.

## Runtime architecture

Browser -> React/Vite -> Nginx API routing -> Catalog / Orders / Inventory services -> service-owned PostgreSQL databases.

- **Catalog Service** owns book/catalog records and its own PostgreSQL database.
- **Orders Service** owns order records and its own PostgreSQL database.
- **Inventory Service** owns stock records and its own PostgreSQL database.
- **Frontend** calls the domain APIs through Nginx; it does not call a legacy Node.js API.
- **Persistence isolation:** each service has a separate PostgreSQL database workload and Compose volume.

## Service APIs

- Catalog: `GET /api/catalog/books`, `GET /api/catalog/books/{id}`
- Orders: `GET /api/orders`, `GET /api/orders/{id}`, `POST /api/orders` with `customerName`, `bookId`, and `quantity`
- Inventory: `GET /api/inventory`, `GET /api/inventory/{bookId}`, `POST /api/inventory/{bookId}/reserve?quantity=1`, `POST /api/inventory/{bookId}/release?quantity=1`
- Health: each Java service exposes Spring Boot Actuator health, liveness and readiness endpoints.

The frontend demo currently reserves stock and then creates an order; if order creation fails it attempts a compensating stock release. This is a demonstration flow, not a durable distributed transaction. Inventory reservation still needs atomic/transactional update semantics before handling concurrent production traffic.

## Run and verify locally

Requirements: Docker Engine with Compose v2.

```bash
docker compose up --build -d
docker compose ps
curl -fsS http://localhost:8080/api/catalog/books
curl -fsS http://localhost:8080/api/orders
curl -fsS http://localhost:8080/api/inventory
curl -fsS http://localhost:8081/actuator/health/readiness
curl -fsS http://localhost:8082/actuator/health/readiness
curl -fsS http://localhost:8083/actuator/health/readiness
docker compose logs -f catalog-service orders-service inventory-service
```

Open http://localhost:8080. Stop with `docker compose down`; this preserves database volumes. `docker compose down -v` deletes local database data.

## Kubernetes deployment

The frontend gateway and each domain service have dedicated Helm charts and Argo CD Applications. The charts include Deployment/Service and a service-owned PostgreSQL StatefulSet/PVC for learning. Create each database Secret before installing its chart, set immutable image repository/tag values, and review rendered manifests before syncing.

```bash
kubectl create namespace bookstore
for chart in frontend-service catalog-service orders-service inventory-service; do helm lint ./helm/$chart; done
```

Use managed databases and a secret manager for production. Do not reuse learning credentials.

## CI/CD

GitHub Actions validates Maven tests for all services, frontend lint/build, Docker Compose API smoke tests, Helm lint/render and Terraform validation. Jenkins runs equivalent validation and image builds; SonarQube, Trivy and registry publishing are opt-in and require a configured Jenkins agent and credentials.

## Remaining production hardening

The runtime cutover is complete at the application/deployment level, but this is not production-ready. Remaining work includes atomic inventory reservation, idempotency, durable order orchestration/outbox and reconciliation, authentication/authorization, API rate limits, service contract tests, distributed tracing, SLO-based alerting, managed PostgreSQL, backups/restore drills, and environment-specific image/secret configuration.
