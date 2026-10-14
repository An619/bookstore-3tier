# Bookstore 3-Tier DevOps Platform

A hands-on DevOps portfolio lab using independently deployable Catalog, Orders and Inventory microservices behind an Nginx/React frontend. The repository is designed to be practiced in Killercoda first and then moved to a local Kubernetes cluster or AWS EKS.

## Application architecture

- **Presentation tier:** React + Vite served by unprivileged Nginx.
- **Catalog domain:** Java 21 + Spring Boot Catalog Service with its own PostgreSQL database.
- **Orders domain:** Java 21 + Spring Boot Orders Service with its own PostgreSQL database and order API.
- **Inventory domain:** Java 21 + Spring Boot Inventory Service with its own PostgreSQL database, stock query, reserve and release endpoints.
- **Runtime:** The active application uses only the Catalog, Orders and Inventory microservices; the retired Node.js API is retained in `backend/` as migration history and is not deployed.
- **Data tier:** PostgreSQL.
- **Local runtime:** Docker Compose with health-gated dependencies and a persistent development database volume.

## DevOps capabilities in this repository

- GitHub Actions CI: Java microservice tests, frontend lint/build, Docker Compose integration smoke tests, Helm lint/render and Terraform formatting/validation.
- Jenkins declarative pipeline: Maven microservice validation, frontend lint/build, image build, Compose smoke test, Helm checks, optional SonarQube and Trivy stages, optional commit-tagged Docker Hub/ECR publishing for frontend and all microservices.
- Docker: multi-stage frontend build, non-root Java service images, unprivileged Nginx frontend, health checks and private service/database networking.
- Kubernetes packaging: separate Helm charts for the frontend, Catalog, Orders and Inventory services; each backend service has its own database workload, probes and resource/security settings.
- GitOps starter: Argo CD Application configured for manual sync until environment values are reviewed.
- Infrastructure as code starter: Terraform VPC, EKS managed node group and immutable ECR repositories.
- Operations: security baseline, Kubernetes troubleshooting, deployment rollback and PostgreSQL backup/restore runbooks.
- Monitoring starter: PrometheusRule examples and kube-prometheus-stack guide.

> This is a learning/portfolio lab and a staged microservices migration, not yet production-ready. The Helm PostgreSQL StatefulSet, default lab credentials, Terraform starter, monitoring rules and optional integrations must be reviewed and tested for the target environment. A project does not itself establish years of professional experience.

## Repository layout

```text
backend/          Retired Node.js API kept for migration history (not deployed)
services/              Catalog, Orders and Inventory Java/Spring Boot microservices
  catalog-service/      Maven build, tests and Dockerfile
  orders-service/       Maven build, tests and Dockerfile
  inventory-service/    Maven build, tests and Dockerfile
frontend/         React UI, Nginx config and multi-stage Dockerfile
database/         Separate PostgreSQL initialization SQL for each service (legacy SQL is not used by the active runtime)
docker-compose.yml  Local stack with isolated database per service
Jenkinsfile       CI pipeline with Maven, SonarQube and opt-in integrations
helm/              Kubernetes Helm charts for all services
.github/workflows/ci.yml  Pull request CI
helm/bookstore/   Kubernetes Helm chart
argocd/           GitOps Application starter
terraform/eks/    AWS VPC, EKS and ECR starter
monitoring/       Prometheus alert rules and Grafana guide
docs/             Architecture, CI/CD, security and runbooks
```

## Prerequisites

- Git
- Docker Engine with Docker Compose v2
- Node.js 22/npm for local development
- For Kubernetes: kubectl and Helm 3
- For cloud provisioning: AWS CLI, Terraform, an AWS account and appropriate IAM permissions

## Run locally

From the repository root:

```bash
docker compose up --build -d
docker compose ps
curl -fsS http://localhost:8080/api/catalog/books
curl -fsS http://localhost:8081/actuator/health/readiness
curl -fsS http://localhost:8081/api/catalog/books
curl -fsS http://localhost:8082/api/orders
curl -fsS http://localhost:8083/api/inventory
curl -fsS http://localhost:8080/api/orders
curl -fsS http://localhost:8080/api/inventory
```

Open http://localhost:8080. PostgreSQL is intentionally not published to the host. Inspect the active databases with `docker compose exec catalog-database psql -U cataloguser -d catalogdb`, `docker compose exec orders-database psql -U serviceuser -d ordersdb`, or `docker compose exec inventory-database psql -U serviceuser -d inventorydb`.

Run tests locally:

```bash
cd frontend && npm ci && npm run lint && npm run build
cd ..
for service in catalog-service orders-service inventory-service; do (cd services/$service && mvn -B -ntp verify); done
helm lint ./helm/bookstore
helm lint ./helm/catalog-service
helm lint ./helm/orders-service
helm lint ./helm/inventory-service
helm template bookstore ./helm/bookstore --namespace bookstore
helm template catalog-service ./helm/catalog-service --namespace bookstore
helm template orders-service ./helm/orders-service --namespace bookstore
helm template inventory-service ./helm/inventory-service --namespace bookstore
```

Stop the stack with `docker compose down`. To remove the local database volume too (destructive), use `docker compose down -v`.

The Compose database password and Helm default password are for disposable local development only. Never reuse them in shared or production environments.

## Recommended learning path

1. Run the app locally and understand each tier and health/readiness check.
2. Review GitHub Actions and fix any failed CI stages.
3. Configure Jenkins Multibranch Pipeline and optional SonarQube/Trivy integrations.
4. Validate the Java microservices with Maven and SonarQube, then build/push immutable images to a registry.
5. Deploy the Helm chart to a disposable Kubernetes cluster and practice troubleshooting.
6. Configure environment-specific values and manually sync Argo CD.
7. Add monitoring, test alert delivery and practice rollback/restore.
8. Review Terraform, set a supported EKS version and trusted API endpoint CIDR, then plan AWS infrastructure.
9. Deploy to EKS, validate the workload and destroy lab resources when finished.

## Engineering principles

- Never commit real credentials, tokens, kubeconfigs, private keys, Terraform state or database dumps.
- Prefer immutable commit-SHA image tags; promote the same tested image between environments.
- Keep PostgreSQL private and use least-privilege IAM/RBAC.
- Review CI and rendered manifests before deploying.
- Test backup/restore, rollback and failure scenarios.
- Record evidence, diagnosis, remediation and follow-up actions for each operational exercise.

See the runbooks under `docs/runbooks/` and the setup notes under `docs/`.
