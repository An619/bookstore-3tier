# Bookstore 3-Tier DevOps Platform

A hands-on portfolio project for learning production-style delivery practices around a three-tier application.

## Architecture
- **Presentation:** React + Vite served by Nginx.
- **Application:** Node.js + Express REST API.
- **Data:** PostgreSQL.
- **Local runtime:** Docker Compose.
- **Planned delivery stack:** Jenkins, SonarQube, Docker, Trivy, Helm, Argo CD, Kubernetes, Terraform, AWS EKS, Prometheus and Grafana.

> This is an evolving learning lab, not yet a production-ready service. Do not use it for real workloads until security, availability, backup, testing and operational requirements are completed.

## Run locally
Prerequisites: Git and Docker Engine with the Docker Compose plugin.

```bash
docker compose up --build -d
docker compose ps
curl -fsS http://localhost:5000/api/health
curl -fsS http://localhost:5000/api/books
curl -fsS http://localhost:8080/api/books
```

Open http://localhost:8080. Stop with `docker compose down`. To delete the local database volume too (destructive), use `docker compose down -v`.

The Compose database password is for disposable local development only. Never reuse it in shared or production environments.

## Planned milestones
1. Repeatable local environment and repository hygiene.
2. Automated tests, linting and CI quality gates.
3. Jenkins pipeline and SonarQube.
4. Kubernetes manifests and troubleshooting.
5. Helm and Argo CD GitOps.
6. Trivy scanning, secrets handling and RBAC.
7. Prometheus/Grafana, alerts and incident runbooks.
8. Terraform-managed AWS EKS, ECR and ingress.
9. Backup/restore, rollback, disaster recovery and cost controls.

## Engineering principles
- Never commit real credentials, access tokens, kubeconfigs or private keys.
- Prefer immutable image tags (commit SHA or release version) over `latest`.
- Keep deployment configuration version-controlled.
- Require tests and quality checks before releases.
- Validate in a disposable lab before applying changes to AWS.
- Document incident evidence, diagnosis, remediation and prevention.

A project does not by itself establish five years of professional experience. Implement, test and document each capability before presenting it as completed.
