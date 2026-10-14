# Bookstore Microservices DevOps Platform

A hands-on DevOps portfolio lab intended for Killercoda and local Docker practice first, then a disposable Kubernetes cluster or AWS EKS.

## Active application architecture

- **Presentation tier:** React + Vite served by unprivileged Nginx.
- **Catalog domain:** Java 21 + Spring Boot Catalog Service with its own PostgreSQL database.
- **Orders domain:** Java 21 + Spring Boot Orders Service with its own PostgreSQL database.
- **Inventory domain:** Java 21 + Spring Boot Inventory Service with its own PostgreSQL database.
- **API routing:** Nginx routes /api/catalog, /api/orders and /api/inventory to the corresponding service.
- **Data tier:** Three isolated PostgreSQL databases; each service owns its data store.
- **Local runtime:** Docker Compose runs the frontend, three microservices and three databases. The legacy Node.js backend is not part of the active runtime.

The old backend/ source and helm/bookstore/ chart are retained only as migration/reference material; they are not used by the active Compose stack, CI smoke tests or the microservices Jenkins pipeline. Do not deploy the legacy chart for the current architecture.

## DevOps capabilities in this repository

- GitHub Actions CI: Maven tests for all services, frontend lint/build, Compose integration smoke tests, Helm lint/render and Terraform formatting/validation.
- Jenkins declarative pipeline: Maven and frontend validation, image build, Compose smoke tests, Helm checks, optional SonarQube and Trivy stages, optional immutable image publishing to Docker Hub/ECR.
- Docker: multi-stage frontend build, non-root Java service images, unprivileged Nginx frontend, health checks and a private database network.
- Kubernetes packaging: Helm charts for Catalog, Orders and Inventory with Deployments, Services, probes, resource/security settings and separate learning-grade PostgreSQL workloads.
- GitOps starter: Argo CD Applications configured for manual sync until registry image values and secrets are reviewed.
- Infrastructure as code starter: Terraform VPC, EKS managed node group and ECR repositories.
- Operations: security baseline, Kubernetes troubleshooting, deployment rollback and PostgreSQL backup/restore runbooks.
- Monitoring starter: PrometheusRule examples and kube-prometheus-stack guide.

> This is a learning/portfolio project, not production-ready commerce software. The demo checkout is not an atomic distributed transaction. Stock concurrency, idempotency, authentication, payment/checkout, contract tests, tracing, database operations and secret management need further engineering before production.

## Repository layout

```text
backend/                   Retained legacy Node.js reference (not deployed)
services/
  catalog-service/         Catalog API, Maven tests and Dockerfile
  orders-service/          Orders API, Maven tests and Dockerfile
  inventory-service/       Inventory API, Maven tests and Dockerfile
frontend/                  React UI, Nginx config and multi-stage Dockerfile
database/                  Separate PostgreSQL initialization SQL per service
docker-compose.yml         Active microservices-only local stack
Jenkinsfile                CI pipeline for the active services
helm/                      Active service charts plus legacy reference chart
.github/workflows/ci.yml   Pull request/push CI
argocd/                    GitOps Application starters
terraform/eks/              AWS VPC, EKS and ECR starter
monitoring/                Prometheus alert rules and Grafana guide
docs/                      Architecture, CI/CD, security and runbooks
```

## Prerequisites

- Git
- Docker Engine with Docker Compose v2
- Node.js 22/npm for local frontend development
- Java 21 and Maven 3.9+ for service development
- For Kubernetes: kubectl and Helm 3
- For cloud provisioning: AWS CLI, Terraform, an AWS account and appropriate IAM permissions

## Run locally

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
```

Open http://localhost:8080. PostgreSQL is not published to the host. Use docker compose exec catalog-database psql -U cataloguser -d catalogdb for catalog database inspection, or use the matching database service and credentials for Orders/Inventory.

Run tests locally:

```bash
cd frontend && npm ci && npm run lint && npm run build
cd ..
for service in catalog-service orders-service inventory-service; do (cd services/$service && mvn -B -ntp verify); done
for chart in catalog-service orders-service inventory-service; do
  helm lint ./helm/$chart
  helm template "$chart" ./helm/$chart --namespace bookstore
done
```

Stop the stack with docker compose down. To remove local database volumes too (destructive), use docker compose down -v.

The Compose database passwords and Helm default passwords are for disposable local development only. Never reuse them in shared or production environments.

## Recommended learning path

1. Run the active app locally and understand each service's health/readiness check.
2. Review GitHub Actions and resolve any failed stages.
3. Configure Jenkins Multibranch Pipeline and optional SonarQube/Trivy integrations.
4. Build and push immutable commit-SHA images to a registry.
5. Deploy each Helm chart to a disposable Kubernetes cluster and practice troubleshooting.
6. Configure environment-specific values and manually sync Argo CD.
7. Add monitoring, test alert delivery and practice rollback/restore.
8. Review Terraform, set a supported EKS version and trusted API endpoint CIDR, then plan AWS infrastructure.
9. Deploy to EKS, validate workloads and destroy lab resources when finished.

## Engineering principles

- Never commit real credentials, tokens, kubeconfigs, private keys, Terraform state or database dumps.
- Prefer immutable commit-SHA image tags; promote the same tested image between environments.
- Keep PostgreSQL private and use least-privilege IAM/RBAC.
- Review CI and rendered manifests before deploying.
- Test backup/restore, rollback and failure scenarios.
- Record evidence, diagnosis, remediation and follow-up actions for each operational exercise.

See the runbooks under docs/runbooks/ and the setup notes under docs/.