# Bookstore Microservices DevOps Platform

A hands-on portfolio project built around independently deployable application services. Run it with Docker Compose first, then practice Kubernetes/Helm, Argo CD, CI/CD, monitoring and optional AWS EKS.

## Architecture

- **Frontend:** React + Vite, served by unprivileged Nginx.
- **Catalog Service:** Java 21 / Spring Boot; owns catalog data and its PostgreSQL database.
- **Orders Service:** Java 21 / Spring Boot; owns order data and its PostgreSQL database.
- **Inventory Service:** Java 21 / Spring Boot; owns stock data and its PostgreSQL database.
- **API routing:** Nginx routes `/api/catalog`, `/api/orders` and `/api/inventory` directly to their service.
- **Persistence:** separate PostgreSQL database/container and volume per service.
- **No legacy Node.js backend participates in the application runtime.** The microservices are the source of truth for catalog, orders and inventory.

The frontend currently demonstrates the order flow by reserving inventory and then creating an order, with a best-effort compensating release if order creation fails. This is not a distributed transaction; production-grade orchestration and concurrency-safe inventory are still required.

## DevOps stack

- GitHub Actions: Maven tests, frontend lint/build, Docker Compose integration smoke tests, Helm lint/render and Terraform validation.
- Jenkins: validation, image builds, optional SonarQube and Trivy scans, optional Docker Hub/ECR publishing.
- Docker Compose: isolated service databases, health-gated dependencies and local integration testing.
- Kubernetes: Helm charts for the frontend gateway plus Catalog, Orders and Inventory.
- GitOps: Argo CD Applications for each service; sync is intentionally manual.
- Terraform: VPC, EKS managed nodes and immutable ECR repositories for frontend and all microservices.
- Operations: Prometheus alert examples, troubleshooting and deployment/restore runbooks.

This is a learning/portfolio platform, not a production-ready commerce system. Configure real credentials only in secret managers or CI credentials; never commit them. Review AWS cost before provisioning EKS, NAT Gateway, load balancers or EC2 resources.

## Repository layout

```text
frontend/                       React UI, Nginx routing, Dockerfile
services/catalog-service/       Catalog Spring Boot service
services/orders-service/        Orders Spring Boot service
services/inventory-service/     Inventory Spring Boot service
database/                       Per-service PostgreSQL init SQL
docker-compose.yml              Microservices-only local stack
Jenkinsfile                     CI/CD pipeline
helm/                           Frontend and domain-service charts
argocd/                         GitOps Application manifests
terraform/eks/                  AWS EKS and ECR starter
.github/workflows/ci.yml        Pull request CI
monitoring/                     Prometheus rules and Grafana guide
docs/                           Architecture and operations runbooks
```

## Run locally

Requirements: Docker Engine and Docker Compose v2. From the repository root:

```bash
docker compose up --build -d
docker compose ps
curl -fsS http://localhost:8080/
curl -fsS http://localhost:8080/api/catalog/books
curl -fsS http://localhost:8080/api/orders
curl -fsS http://localhost:8080/api/inventory
curl -fsS http://localhost:8081/actuator/health/readiness
curl -fsS http://localhost:8082/actuator/health/readiness
curl -fsS http://localhost:8083/actuator/health/readiness
```

Open http://localhost:8080. PostgreSQL is not published to the host. Inspect a database with `docker compose exec catalog-database psql -U cataloguser -d catalogdb` (change service/database/user for Orders or Inventory).

Stop with `docker compose down`. To also delete local database volumes, use `docker compose down -v` (destructive).

## Run tests

Requirements: Java 21, Maven, Node.js 22/npm and Helm 3.

```bash
for service in catalog-service orders-service inventory-service; do (cd services/$service && mvn -B -ntp verify); done
cd frontend && npm ci && npm run lint && npm run build
cd ..
for chart in frontend-service catalog-service orders-service inventory-service; do helm lint ./helm/$chart; helm template $chart ./helm/$chart --namespace bookstore >/dev/null; done
terraform -chdir=terraform/eks fmt -check -recursive
terraform -chdir=terraform/eks init -backend=false
terraform -chdir=terraform/eks validate
```

## Suggested learning path

1. Run Compose and test each service independently and through Nginx.
2. Inspect the CI workflow and Jenkinsfile; configure Jenkins credentials and agents.
3. Add SonarQube/Trivy integrations and review findings.
4. Build and push immutable commit-tagged images to a registry.
5. Deploy the three Helm charts to a disposable Kubernetes cluster.
6. Configure environment-specific image values and manually sync Argo CD.
7. Add metrics, tracing, alerting, contract tests, backups and rollback drills.
8. Review Terraform plan and costs before creating EKS. Destroy lab infrastructure after practice.

## Known engineering work before production

- Move stock reservation to an atomic/transactional operation to prevent overselling.
- Add idempotency keys, durable order orchestration and reconciliation/outbox patterns.
- Add authentication/authorization, rate limiting and secret management.
- Add service-to-service contract tests, distributed tracing and meaningful SLO alerts.
- Replace learning-grade in-cluster PostgreSQL with a reviewed managed database architecture and tested backup/restore.
