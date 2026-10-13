# Bookstore 3-Tier DevOps Platform

A hands-on DevOps portfolio lab around a three-tier application. The repository is designed to be practiced in Killercoda first and then moved to a local Kubernetes cluster or AWS EKS.

## Application architecture

- **Presentation tier:** React + Vite served by unprivileged Nginx.
- **Application tier (legacy):** Node.js + Express REST API.
- **Microservices migration slice:** Java 21 + Spring Boot Catalog Service built with Maven, with its own API, tests, container image and Helm/Argo CD deployment artifacts. The legacy Node API remains during this staged migration.
- **Data tier:** PostgreSQL.
- **Local runtime:** Docker Compose with health-gated dependencies and a persistent development database volume.

## DevOps capabilities in this repository

- GitHub Actions CI: backend unit tests, frontend lint/build, Docker Compose smoke tests, Helm lint/render and Terraform formatting/validation.
- Jenkins declarative pipeline: Node and Maven validation, image build, Compose smoke test, Helm checks, optional SonarQube and Trivy stages, optional commit-tagged Docker Hub/ECR publishing for all application images.
- Docker: multi-stage frontend build, non-root backend, unprivileged Nginx frontend, health checks and a private database network.
- Kubernetes packaging: Helm chart for the legacy three-tier stack and a separate Helm chart for the Java Catalog Service, with probes, resources and non-root runtime configuration.
- GitOps starter: Argo CD Application configured for manual sync until environment values are reviewed.
- Infrastructure as code starter: Terraform VPC, EKS managed node group and immutable ECR repositories.
- Operations: security baseline, Kubernetes troubleshooting, deployment rollback and PostgreSQL backup/restore runbooks.
- Monitoring starter: PrometheusRule examples and kube-prometheus-stack guide.

> This is a learning/portfolio lab, not yet production-ready. The Helm PostgreSQL StatefulSet, default lab credentials, Terraform starter, monitoring rules and optional integrations must be reviewed and tested for the target environment. A project does not itself establish years of professional experience.

## Repository layout

```text
backend/          Node.js API, tests and Dockerfile
services/catalog-service/  Java 21/Spring Boot microservice, Maven build, tests and Dockerfile
frontend/         React UI, Nginx config and multi-stage Dockerfile
database/         PostgreSQL initialization SQL
docker-compose.yml  Local three-tier stack
Jenkinsfile       CI pipeline with Maven, SonarQube and opt-in integrations
services/catalog-service/  Java microservice and Maven build
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
curl -fsS http://localhost:5000/api/health
curl -fsS http://localhost:5000/api/ready
curl -fsS http://localhost:5000/api/books
curl -fsS http://localhost:8080/api/books
curl -fsS http://localhost:8081/actuator/health/readiness
curl -fsS http://localhost:8081/api/catalog/books
```

Open http://localhost:8080. PostgreSQL is intentionally not published to the host; use `docker compose exec database psql -U bookuser -d bookstore` for local database inspection.

Run tests locally:

```bash
cd backend && npm ci && npm test
cd ../frontend && npm ci && npm run lint && npm run build
cd ..
cd services/catalog-service && mvn -B -ntp verify && cd ../..
helm lint ./helm/bookstore
helm lint ./helm/catalog-service
helm template bookstore ./helm/bookstore --namespace bookstore
helm template catalog-service ./helm/catalog-service --namespace bookstore
```

Stop the stack with `docker compose down`. To remove the local database volume too (destructive), use `docker compose down -v`.

The Compose database password and Helm default password are for disposable local development only. Never reuse them in shared or production environments.

## Recommended learning path

1. Run the app locally and understand each tier and health/readiness check.
2. Review GitHub Actions and fix any failed CI stages.
3. Configure Jenkins Multibranch Pipeline and optional SonarQube/Trivy integrations.
4. Validate the Java Catalog Service with Maven and SonarQube, then build/push immutable images to a registry.
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
