# Architecture and production gaps

## Current application runtime

Browser -> React/Vite frontend -> Nginx API routing -> Java/Spring Boot Catalog, Orders and Inventory services -> separate PostgreSQL databases.

There is no legacy Node.js API in the Compose or Kubernetes runtime. Each domain service is independently built, tested, containerized, and packaged with its own Helm chart and Argo CD Application. Docker Compose gives each service its own PostgreSQL database and persistent volume.

## Delivery platform

- GitHub: source control, pull requests and automated CI.
- Jenkins: Maven/npm validation, image builds, optional quality/security scanning and optional image publishing.
- SonarQube and Trivy: optional code-quality and vulnerability checks.
- Container registry: immutable commit-tagged images.
- Helm and Argo CD: Kubernetes packaging and GitOps reconciliation.
- Terraform and AWS EKS: infrastructure-as-code starter and cloud runtime.
- Prometheus/Grafana: monitoring examples and operational guidance.

## Production gaps to close

- Make inventory reservation atomic and concurrency-safe; add idempotency and durable order orchestration.
- Add authentication/authorization, TLS, API rate limiting, service-to-service identity and secret management.
- Add service contract tests, distributed tracing, structured logs, request metrics and SLO-based alerts.
- Use managed PostgreSQL with tested backups, restore procedures, encryption and recovery objectives.
- Review resource sizing, availability targets, deployment strategy, ingress restrictions and rollback policy.
- Validate actual Jenkins/SonarQube/Trivy integrations in configured environments; repository configuration alone does not prove they ran.
- Review Terraform plan and AWS costs before provisioning EKS, NAT Gateway, EC2 nodes, load balancers or persistent volumes.
