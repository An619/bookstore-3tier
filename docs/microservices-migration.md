# Microservices migration status

The repository now has three separately deployable Java 21/Spring Boot services—Catalog, Orders and Inventory—each with its own PostgreSQL database. Nginx routes all three domain APIs while the legacy Node.js API remains temporarily for routes not yet migrated.

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

Open http://localhost:8080 to confirm the UI gets books from the Catalog Service. The legacy Node.js API remains available on port 5000 during the migration.

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
```

Use a unique secret password in real environments. The in-chart PostgreSQL workload is for learning; production should use a managed database, backups, encryption, restore tests, and a secret manager.

## DevOps tools

- **Maven** compiles/tests the Java service and packages the executable JAR.
- **Jenkins** runs Node and Maven validation, builds images, performs Compose smoke tests, validates Helm charts, and can optionally publish immutable commit-SHA tags.
- **SonarQube** analyzes Java, Node and frontend source. Configure Jenkins credential `sonarqube-token` and `SONAR_HOST_URL`; install SonarScanner on the agent.
- **Docker** packages the service in a non-root runtime image.
- **Kubernetes/Helm** deploys the catalog independently from the remaining legacy API.
- **Argo CD** watches the catalog Helm chart from Git; its Application uses manual sync intentionally.

## Remaining migration work

This is a stronger staged microservices migration, but it is not production-complete. Catalog, Orders and Inventory are independently deployable and own separate databases. Order creation does not yet reserve stock transactionally; customer identity, payment/checkout, inter-service authentication, retries/idempotency, distributed tracing, contract testing and production database operations remain future work.
