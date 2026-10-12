# Bookstore DevOps and Microservices Lab

A hands-on portfolio project to practice a staged migration from a three-tier bookstore app toward independently deployable services. Start locally, practice Kubernetes on Killercoda, then consider a carefully budgeted AWS EKS lab.

## Application architecture

- **Frontend:** React + Vite served by unprivileged Nginx.
- **Existing API:** Node.js + Express, currently still responsible for the legacy API and PostgreSQL integration.
- **Catalog microservice:** Java 21 + Spring Boot, built/tested with Maven, with its own API, Docker image and Kubernetes Deployment/Service.
- **Data tier:** PostgreSQL for the existing Node.js API.
- **Local runtime:** Docker Compose.

**Migration status:** the Java catalog service is an initial independently deployable microservice. Its sample catalog is in-memory and the existing frontend still calls the legacy Node API. The project is therefore a staged migration lab, not a completed full business-domain microservices conversion.

## Toolchain

- **Jenkins:** CI pipeline for Maven tests, Node tests, frontend build, SonarQube (optional), Docker image builds, Trivy (optional), Compose smoke tests and optional registry publishing.
- **Maven:** builds and tests the Java catalog service. The Node.js and React components correctly continue to use npm.
- **SonarQube:** root `sonar-project.properties` config analyzes Java and JavaScript/TypeScript sources. Configure `SONAR_HOST_URL` and Jenkins credential `sonarqube-token`, then enable the pipeline parameter.
- **Docker:** builds frontend, backend and catalog service images.
- **Kubernetes + Helm:** includes Deployments/Services for frontend, backend and catalog, plus the learning PostgreSQL StatefulSet/PVC and probes/resources.
- **Argo CD:** manual-sync GitOps starter pointing to the Helm chart. Review environment-specific image tags and secrets before syncing.
- **Terraform:** starter for VPC, EKS managed nodes and ECR repositories. It does not create anything until you explicitly run Terraform.
- **Monitoring and runbooks:** PrometheusRule examples, troubleshooting, rollback and PostgreSQL backup/restore guidance.

## Repository layout

```text
frontend/                       React/Vite and Nginx
backend/                        Node.js/Express API and tests
services/catalog-service/       Java/Spring Boot microservice and Maven build
database/                       PostgreSQL initialization SQL
docker-compose.yml              Local multi-container stack
Jenkinsfile                     CI pipeline and optional integrations
sonar-project.properties        SonarQube project configuration
.github/workflows/ci.yml        GitHub Actions validation
helm/bookstore/                 Kubernetes Helm chart
argocd/                         Argo CD Application starter
terraform/eks/                  AWS infrastructure starter
monitoring/                     Prometheus alerts and guide
docs/                           Architecture, setup and operational runbooks
```

## Prerequisites

- Git and Docker Engine with Docker Compose v2
- Node.js 22/npm for frontend and backend development
- Java 21 and Maven for catalog service development
- For Kubernetes: kubectl and Helm 3
- For AWS: AWS CLI, Terraform, an AWS account and reviewed IAM permissions

## Run locally

From the repository root:

```bash
docker compose up --build -d
docker compose ps
curl -fsS http://localhost:5000/api/ready
curl -fsS http://localhost:5000/api/books
curl -fsS http://localhost:8081/api/health
curl -fsS http://localhost:8081/api/catalog/books
curl -fsS http://localhost:8080/api/books
```

Open the UI at http://localhost:8080. PostgreSQL is intentionally not published to the host. Inspect it with `docker compose exec database psql -U bookuser -d bookstore`.

## Run validations

```bash
mvn -B -f services/catalog-service/pom.xml clean verify
cd backend && npm ci && npm test
cd ../frontend && npm ci && npm run lint && npm run build
cd ..
helm lint ./helm/bookstore
helm template bookstore ./helm/bookstore --namespace bookstore
```

Stop services with `docker compose down`. To remove the local PostgreSQL volume too (destructive), use `docker compose down -v`.

## Recommended learning sequence

1. Start and test the Compose stack.
2. Review GitHub Actions and Jenkins stages; configure SonarQube and registry credentials only when ready.
3. Build and scan all three application images.
4. Deploy with Helm to a disposable Kubernetes cluster and practice probes, logs, rollouts and rollback.
5. Configure Argo CD manual sync with reviewed image tags.
6. Only then review Terraform and AWS costs before creating EKS infrastructure.

## Safety notes

- Local DB passwords are disposable lab defaults; never reuse them in shared/cloud environments.
- Never commit tokens, credentials, kubeconfigs, private keys or Terraform state.
- EKS, NAT gateways, EC2 nodes, load balancers and data transfer can incur charges.
- A portfolio project demonstrates practice; it does not itself establish years of professional experience.

See `docs/microservices-toolchain.md` and the runbooks under `docs/runbooks/`.
