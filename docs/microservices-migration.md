# Microservices migration status

The repository now has a **real catalog migration slice**: the React frontend calls a separately deployable Java 21/Spring Boot Catalog Service through Nginx, and that service owns a separate PostgreSQL database. The old Node.js API remains temporarily for routes that have not yet migrated.

## Catalog Service API

- `GET /api/catalog/books`
- `GET /api/catalog/books/{id}`
- `GET /actuator/health`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness` (includes database readiness)

The service uses Spring Data JPA and PostgreSQL. Its local schema is in `database/catalog-init.sql`; Docker Compose persists catalog data in the `catalog_data` volume.

## Run and verify locally

From the repository root:

```bash
docker compose up --build -d
docker compose ps
curl -fsS http://localhost:8080/api/catalog/books
curl -fsS http://localhost:8081/api/catalog/books
curl -fsS http://localhost:8081/actuator/health/readiness
docker compose logs -f catalog-service
```

Open http://localhost:8080 to confirm the UI gets books from the Catalog Service. The legacy Node.js API remains available on port 5000 during the migration.

Stop containers with `docker compose down`. This preserves local data. To delete both local database volumes and all their data, run `docker compose down -v` (destructive).

For Maven tests, use Java 21 and Maven 3.9+:

```bash
cd services/catalog-service
mvn -B -ntp verify
```

## Kubernetes deployment

The Helm chart includes a PostgreSQL StatefulSet, private ClusterIP Service, PVC, SQL initialization ConfigMap, and Catalog Service Deployment/Service. Create the database credential Secret before installing the chart:

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

This is not yet a complete microservices conversion. Catalog is independently deployed and owns its data, but order, inventory, customer/identity and checkout responsibilities have not yet been extracted from the legacy API. The next services should be designed around business capabilities, separate data ownership, and explicit API contracts rather than just creating extra demo endpoints.
