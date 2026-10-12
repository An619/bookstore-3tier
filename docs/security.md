# Security baseline

## Secrets
- Never commit GitHub PATs, Docker Hub tokens, AWS access keys, kubeconfigs, private keys or real database passwords.
- The Compose password and Helm default are local disposable lab values only.
- For shared environments use a managed secret store or an externally managed Kubernetes Secret. Restrict access to workloads that need the secret.
- Rotate any credential that has accidentally been committed or printed in logs.

## Containers
- Backend runs as the non-root `node` user.
- Frontend uses the unprivileged Nginx image and listens on port 8080.
- Keep `.dockerignore` files current so local files and credentials are not copied into image build contexts.
- Scan images and dependencies; update vulnerable packages and base images.

## Kubernetes
- Backend and frontend templates use non-root identities, drop Linux capabilities and use the RuntimeDefault seccomp profile.
- Keep PostgreSQL internal; do not expose its port with a public LoadBalancer or Ingress.
- Use least-privilege service accounts and RBAC; do not mount the default service-account token unless required.
- Use network policies only after verifying the selected CNI enforces them.
- Add TLS, ingress restrictions, resource requests/limits and reviewed secrets integration before a shared deployment.

## CI/CD
- Protect the main branch and require CI before merging.
- Limit who can edit workflows/Jenkinsfiles and who can access privileged Jenkins agents.
- Use short-lived or narrowly scoped credentials.
- Publish immutable commit-SHA image tags; promote the same tested image between environments.
- Require review before changing GitOps manifests or infrastructure.

## Operational readiness
- Document alert thresholds, backup frequency, recovery-point objective and recovery-time objective.
- Test restoration from backup, deployment rollback and failed dependency scenarios.
- Record findings and remediation; a successful build alone is not a security assessment.
