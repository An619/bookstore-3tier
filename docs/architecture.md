# Architecture and production gaps

## Current application
Browser -> Nginx/React frontend -> Node.js/Express API -> PostgreSQL.

Docker Compose provides a repeatable local environment. PostgreSQL uses a named volume so ordinary container recreation does not discard data.

## Planned target platform
- GitHub: source control and pull requests.
- Jenkins: tests, quality checks and image builds.
- SonarQube and Trivy: code quality and vulnerability checks.
- Container registry: versioned images.
- Helm: reusable Kubernetes packaging.
- Argo CD: GitOps reconciliation from Git.
- Kubernetes / AWS EKS: application runtime.
- Terraform: infrastructure as code.
- Prometheus and Grafana: metrics, dashboards and alerts.

## Production gaps to close
- Replace local-only credentials with a proper secrets solution.
- Keep PostgreSQL private in cloud deployments.
- Versioned database migrations (the services validate their schema at startup).
- Replace the stubbed payment step with a PSP adapter and reconciliation against
  the provider's ledger.
- Define and test database backup/restore and recovery objectives.
- Set resource sizing, availability targets, alert thresholds and rollback policy.
- Add TLS, ingress restrictions, image scanning and least-privilege IAM/RBAC.
