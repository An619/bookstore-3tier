# Bookstore Microservices DevOps Platform

A hands-on DevOps portfolio lab built around independently deployable Bookstore microservices. Start with Docker Compose and local CI, then practice Kubernetes/Helm and GitOps before considering AWS EKS.

## Application architecture

- **Presentation:** React + Vite, served by unprivileged Nginx.
- **Catalog service:** Java 21 + Spring Boot, owns catalog data in its own PostgreSQL database.
- **Orders service:** Java 21 + Spring Boot, owns order data in its own PostgreSQL database.
- **Inventory service:** Java 21 + Spring Boot, owns stock data in its own PostgreSQL database and exposes query/reserve/release APIs.
- **API routing:** Nginx routes `/api/catalog`, `/api/orders`, and `/api/inventory` to their respective services.
- **Persistence:** separate PostgreSQL database and volume for each microservice; databases are not published to host ports.

The original Node.js API and its database remain in the repository as migration/reference code, but are **not part of the active Docker Compose runtime or the frontend request path**. The active app runtime is the React/Nginx frontend plus Catalog, Orders, Inventory, and their separate databases.

## DevOps capabilities

- GitHub Actions: Maven tests for all Java services, frontend lint/build, Docker Compose integration smoke tests, Helm lint/render, and Terraform format/validation.
- Jenkins declarative pipeline: Java/Node validation, image build, Compose smoke tests, Helm checks, optional SonarQube/Trivy, optional Docker Hub/ECR publishing.
- Docker Compose: health-gated dependencies, isolated service databases, persistent development volumes.
- Kubernetes packaging: Helm charts for Catalog, Orders and Inventory; a legacy chart is retained as migration reference.
- GitOps: Argo CD Application manifests with manual sync.
- Infrastructure as code: Terraform starter for VPC, EKS managed node group, and ECR.
- Operations: monitoring examples, security baseline, troubleshooting, rollback and database runbooks.

> This is a portfolio/lab implementation, not production-ready commerce software. Before production, add authentication/authorization, API contract and end-to-end tests, idempotent order handling, concurrency-safe inventory reservation, distributed workflow/outbox or saga handling, tracing, robust secrets management, backups/restore tests, and managed database operations. The Kubernetes PostgreSQL charts are for learning. No AWS infrastructure is provisioned by the repository itself.

## Repository layout

```text
frontend/                 React UI, Nginx routes and multi-stage Dockerfile
services/
  catalog-service/        Java/Spring Boot catalog microservice
  orders-service/         Java/Spring Boot orders microservice
  inventory-service/      Java/Spring Boot inventory microservice
database/                 Separate database initialization SQL
docker-compose.yml        Active microservices-only local runtime
Jenkinsfile               CI, image build/scan and optional publishing
.github/workflows/ci.yml  Pull-request CI and integration smoke tests
helm/                     Microservice charts and legacy reference chart
argocd/                   GitOps Application manifests
terraform/eks/             AWS VPC, EKS and ECR starter
monitoring/               Prometheus alert rules and Grafana guide
docs/                     Architecture, migration notes and runbooks
backend/                  Retained legacy Node.js migration/reference source
```

## Prerequisites

- Git
- Docker Engine with Docker Compose v2
- Node.js 22/npm for frontend development
- Java 21 and Maven for local service tests
- For Kubernetes: kubectl and Helm 3
- For cloud provisioning: AWS CLI, Terraform, an AWS account and suitable IAM permissions

## Run locally

From the repository root:

```bash
docker compose up --build -d
docker compose ps
curl -fsS http://localhost:8080/
curl -fsS http://localhost:8080/api/catalog/books
curl -fsS http://localhost:8081/actuator/health/readiness
curl -fsS http://localhost:8081/api/catalog/books
curl -fsS http://localhost:8082/actuator/health/readiness
curl -fsS http://localhost:8082/api/orders
curl -fsS http://localhost:8083/actuator/health/readiness
curl -fsS http://localhost:8083/api/inventory
```

Open http://localhost:8080. Service endpoints are exposed locally for learning: Catalog on 8081, Orders on 8082, Inventory on 8083. PostgreSQL ports are not published to the host.

Run validation locally:

```bash
cd frontend && npm ci && npm run lint && npm run build
cd ..
for service in catalog-service orders-service inventory-service; do (cd services/$service && mvn -B -ntp verify); done
helm lint ./helm/catalog-service
helm lint ./helm/orders-service
helm lint ./helm/inventory-service
helm template catalog-service ./helm/catalog-service --namespace bookstore
helm template orders-service ./helm/orders-service --namespace bookstore
helm template inventory-service ./helm/inventory-service --namespace bookstore
```

Stop the stack with `docker compose down`. To remove local database volumes too (destructive), use `docker compose down -v`.

## Recommended learning path

1. Run the microservices stack and inspect readiness endpoints.
2. Review GitHub Actions and fix any failing checks.
3. Configure Jenkins Multibranch Pipeline and optional SonarQube/Trivy integrations.
4. Build and publish immutable commit-SHA images to a registry.
5. Deploy each Helm chart to a disposable Kubernetes cluster and practice troubleshooting.
6. Configure environment-specific values and manually sync Argo CD.
7. Add monitoring, test alert delivery and practice rollback/restore.
8. Review Terraform, set a supported EKS version and a trusted API endpoint CIDR, then plan AWS infrastructure.
9. Deploy to EKS only after reviewing cost, security and cleanup steps.

## Engineering principles

- Never commit real credentials, tokens, kubeconfigs, private keys, Terraform state or database dumps.
- Prefer immutable commit-SHA image tags and promote the same tested image between environments.
- Keep PostgreSQL private and use least-privilege IAM/RBAC.
- Review CI results and rendered manifests before deploying.
- Test backup/restore, rollback and failure scenarios.
- Record evidence, diagnosis, remediation and follow-up actions for operational exercises.

See `docs/runbooks/` and `docs/` for operational guides.
