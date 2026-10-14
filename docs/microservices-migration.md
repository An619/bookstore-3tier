# Microservices migration status

The active runtime now consists of a React/Nginx frontend, three independently deployable Java 21/Spring Boot services (Catalog, Orders and Inventory), and one PostgreSQL database per service. The legacy Node.js backend is no longer started by Docker Compose, routed by Nginx, or checked by the active CI/Jenkins integration smoke tests. Its source and old Helm chart remain in the repository as migration reference material only.

## Service APIs

- Catalog: GET /api/catalog/books, GET /api/catalog/books/{id}
- Orders: GET /api/orders, GET /api/orders/{id}, POST /api/orders with customerName, bookId, and quantity
- Inventory: GET /api/inventory, GET /api/inventory/{bookId}, POST /api/inventory/{bookId}/reserve?quantity=1, POST /api/inventory/{bookId}/release?quantity=1

The Orders service validates basic order input. Inventory rejects invalid quantities and insufficient stock. The browser currently coordinates a basic reserve-then-create flow with a compensating release if order creation fails; this is not an atomic distributed transaction.

## Run and verify locally

From the repository root:

```bash
docker compose up --build -d
docker compose ps
curl -fsS http://localhost:8080/nginx-health
curl -fsS http://localhost:8080/api/catalog/books
curl -fsS http://localhost:8081/actuator/health/readiness
curl -fsS http://localhost:8081/api/catalog/books
curl -fsS http://localhost:8082/api/orders
curl -fsS http://localhost:8083/api/inventory
curl -fsS http://localhost:8080/api/orders
curl -fsS http://localhost:8080/api/inventory
docker compose logs -f orders-service inventory-service catalog-service
```

Open http://localhost:8080 to confirm the UI uses the Catalog, Orders and Inventory services. There should be no active backend container and no host port 5000 binding.

Stop containers with docker compose down. This preserves local data. To delete all local database volumes and their data, run docker compose down -v (destructive).

For Maven tests, use Java 21 and Maven 3.9+:

```bash
for service in catalog-service orders-service inventory-service; do (cd services/$service && mvn -B -ntp verify); done
```

## Kubernetes deployment

Each active service has a Helm chart with a Deployment/Service and a service-owned PostgreSQL StatefulSet/PVC. Create the matching Secret for each chart before installation. Example for Catalog:

```bash
kubectl create namespace bookstore
kubectl -n bookstore create secret generic catalog-db-secret --from-literal=username=cataloguser --from-literal=password='<choose-a-unique-strong-password>'
helm upgrade --install catalog-service ./helm/catalog-service -n bookstore --set image.repository='<your-registry>/bookstore-catalog-service' --set image.tag='<immutable-commit-sha>'
kubectl -n bookstore get pods,svc,pvc
```

Repeat for Orders using Secret orders-db-secret and image bookstore-orders-service; repeat for Inventory using inventory-db-secret and image bookstore-inventory-service. Use a different strong password for each database. The in-chart PostgreSQL workload is for learning; production should use a managed database, backups, encryption, restore tests and a secret manager.

## DevOps tools

- **Maven** compiles, tests and packages each Java service.
- **Jenkins** validates all three Maven services and the frontend, builds service images, runs Compose smoke tests, validates the active Helm charts, and can optionally publish immutable commit-SHA tags.
- **SonarQube** can analyze the Java and frontend code. Configure Jenkins credential sonarqube-token and SONAR_HOST_URL; install SonarScanner on the agent.
- **Docker** packages each active service in a container image.
- **Kubernetes/Helm** deploys each active service independently.
- **Argo CD** watches the active service charts from Git; the Application manifests use manual sync intentionally until image values and secrets are configured.

## Remaining engineering work

The legacy Node.js API is no longer in the active application runtime, but its source and old Helm chart are retained for reference. Production completeness is a separate goal: implement atomic concurrency-safe stock reservation, idempotent order requests, durable order orchestration (for example an outbox/Saga), authentication/authorization, payment/checkout, contract and end-to-end tests, distributed tracing, resilience policies, managed databases, backups, secret management and environment-specific deployment values. Do not treat this portfolio lab as production-ready commerce software.