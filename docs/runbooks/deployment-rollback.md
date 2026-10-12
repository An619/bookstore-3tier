# Runbook: release verification and rollback

## Before release
- Record the source commit and image digests/tags.
- Confirm CI tests, Helm lint/render and image scans.
- Review the deployment diff and environment values.
- Confirm the database backup/restore plan if a release changes schema or data.

## Kubernetes rollout checks

```bash
kubectl get deploy,pods,svc -n bookstore
kubectl rollout status deployment/<release>-backend -n bookstore
kubectl rollout status deployment/<release>-frontend -n bookstore
kubectl get events -n bookstore --sort-by=.lastTimestamp
```

Verify API liveness/readiness and the frontend API route through the configured ingress or port-forward. Check logs and dashboards for elevated errors or restarts.

## Roll back

For Helm-managed releases:

```bash
helm history <release> -n bookstore
helm rollback <release> <previous-revision> -n bookstore
kubectl rollout status deployment/<release>-backend -n bookstore
kubectl rollout status deployment/<release>-frontend -n bookstore
```

For Argo CD GitOps, revert the deployment-config commit and let the reviewed Git state reconcile; do not leave Git and the cluster intentionally divergent without recording why.

## After rollback
- Verify health/readiness and a representative book-list request.
- Confirm no unexpected database migration/data impact.
- Record the incident timeline, root cause, detection gap and follow-up actions.
