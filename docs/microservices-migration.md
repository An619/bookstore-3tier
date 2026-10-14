# Microservices migration status

The active runtime now consists of a React/Nginx frontend plus three separately deployable Java 21/Spring Boot services—Catalog, Orders and Inventory—each with its own PostgreSQL database. Nginx routes the domain APIs only to these microservices. The retired Node.js API under `backend/` is retained for migration history but is not included in Docker Compose, Nginx routing, Jenkins builds, or the active Argo CD application.

## Service APIs

- Catalog: `GET /api/catalog/books`, `GET /api/catalog/books/{id}`
- Orders: `GET /api/orders`, `GET /api/orders/{id}`, `POST /api/orders` with `customerName`, `bookId`, and `quantity`
- Inventory: `GET /api/inventory`, `GET /api/inventory/{bookId}`, `POST /api/inventory/{bookId}/reserve?quantity=1`, `POST /api/inventory/{bookId}/release?quantity=1`

The Orders service validates basic order input. Inventory rejects invalid quantities and insufficient stock. These are learning-grade APIs; order creation does not yet orchestrate an atomic stock reservation or payment.

## Catalog Service API

- `GET /api/catalog/books`
- `GET /api/catalog/books/{id}`
- `GET /actuator/health`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness` (includes database readiness)

Each service uses Spring Data JPA and PostgreSQL. SQL initialization files are under `database/`; Docker Compose persists each service's data in its own named volume.

## Run and verify locally

From the repository root:

```bash
docker compose up --build -d
docker compose ps
curl -fsS http://localhost:8080/api/catalog/books
curl -fsS http://localhost:8081/api/catalog/books
curl -fsS http://localhost:8081/actuator/health/readiness
curl -fsS http://localhost:8082/api/orders
curl -fsS http://localhost:8083/api/inventory
curl -fsS http://localhost:8080/api/orders
curl -fsS http://localhost:8080/api/inventory
docker compose logs -f orders-service inventory-service catalog-service
```

Open http://localhost:8080 to confirm the UI gets books from Catalog and loads Orders and Inventory. The retired Node.js API is not started by Compose.

Stop containers with `docker compose down`. This preserves local data. To delete both local database volumes and all their data, run `docker compose down -v` (destructive).

For Maven tests, use Java 21 and Maven 3.9+:

```bash
cd services/catalog-service
mvn -B -ntp verify
```

## Kubernetes deployment

Each service has a Helm chart with a Deployment/Service and a service-owned PostgreSQL StatefulSet/PVC. Create the matching Secret for each chart before installation. Create the database credential Secret before installing the chart:

```bash
kubectl create namespace bookstore
kubectl -n bookstore create secret generic catalog-db-secret \
  --from-literal=username=cataloguser \
  --from-literal=password='<choose-a-unique-strong-password>'
helm upgrade --install catalog-service ./helm/catalog-service -n bookstore \
  --set image.repository='<your-registry>/bookstore-catalog-service' \
  --set image.tag='<immutable-commit-sha>'
kubectl -n bookstore get pods,svc,pvc

kubectl -n bookstore create secret generic orders-db-secret \
  --from-literal=username=serviceuser \
  --from-literal=password='<choose-a-different-strong-password>'
helm upgrade --install orders-service ./helm/orders-service -n bookstore \
  --set image.repository='<your-registry>/bookstore-orders-service' \
  --set image.tag='<immutable-commit-sha>'

kubectl -n bookstore create secret generic inventory-db-secret \
  --from-literal=username=serviceuser \
  --from-literal=password='<choose-another-strong-password>'
helm upgrade --install inventory-service ./helm/inventory-service -n bookstore \
  --set image.repository='<your-registry>/bookstore-inventory-service' \
  --set image.tag='<immutable-commit-sha>'

kubectl -n bookstore get pods,svc,pvc
```

Use a unique secret password in real environments. The in-chart PostgreSQL workload is for learning; production should use a managed database, backups, encryption, restore tests, and a secret manager.

## DevOps tools

- **Maven** compiles/tests the Java service and packages the executable JAR.
- **Jenkins** validates the frontend and all three Maven services, builds/scans images, performs Compose smoke tests, validates the active Helm charts, and can optionally publish immutable commit-SHA tags.
- **SonarQube** analyzes Java microservice and frontend source. Configure Jenkins credential `sonarqube-token` and `SONAR_HOST_URL`; install SonarScanner on the agent.
- **Docker** packages the service in a non-root runtime image.
- **Kubernetes/Helm** deploys the frontend and each backend microservice independently. `helm/bookstore` is deprecated and retained only as migration reference.
- **Argo CD** watches the frontend Helm chart from Git; its Application uses manual sync intentionally. Separate Application manifests exist for Catalog, Orders and Inventory.

## Remaining migration work

The legacy Node.js backend is retired from the active runtime; Catalog, Orders and Inventory are independently deployable and own separate databases. This is a microservices architecture for a portfolio lab, but it is not production-complete. Order creation does not yet reserve stock transactionally; customer identity, payment/checkout, inter-service authentication, retries/idempotency, distributed tracing, contract testing and production database operations remain future work.
