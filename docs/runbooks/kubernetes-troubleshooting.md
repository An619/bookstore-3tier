# Runbook: Kubernetes troubleshooting

Use the actual namespace and resource names from your cluster; the examples below assume namespace `bookstore`.

## First-response commands

```bash
kubectl get pods -n bookstore -o wide
kubectl get deploy,sts,svc,ingress,pvc -n bookstore
kubectl get events -n bookstore --sort-by=.lastTimestamp
kubectl describe pod <pod-name> -n bookstore
kubectl logs <pod-name> -n bookstore --all-containers=true --tail=200
kubectl logs <pod-name> -n bookstore --previous
```

## Common symptoms

### ImagePullBackOff
Check the exact image repository/tag, registry visibility and imagePullSecrets. Verify the image exists in the registry before changing the Deployment.

### CrashLoopBackOff
Inspect current and previous logs, container exit code, environment/secret references, probe configuration and resource limits. Avoid repeatedly deleting the pod before collecting evidence.

### Pod Pending
Inspect `kubectl describe pod` and events. Check node capacity, CPU/memory requests, PVC binding, node selectors/taints and storage-class availability.

### Frontend works but API fails
Check backend readiness and Service endpoints:
```bash
kubectl get endpointslices -n bookstore
kubectl describe svc <release>-backend -n bookstore
kubectl logs deploy/<release>-backend -n bookstore --tail=100
``
Verify Nginx proxies `/api/` to the backend Service on port 5000 and the backend can reach PostgreSQL.

### Database readiness fails
Inspect the StatefulSet pod logs, PVC state, Secret keys and PostgreSQL events. A ConfigMap seed script only runs when PostgreSQL initializes a new empty data directory; changing the script does not automatically rerun it on an existing database.

### Roll back a Helm release
```bash
helm history bookstore -n bookstore
helm rollback bookstore <revision> -n bookstore
kubectl rollout status deployment/bookstore-backend -n bookstore
``
After rollback, verify API health/readiness and the frontend API proxy.

## Incident notes to capture
- Start/end time and user-visible impact.
- Alert/event and affected components.
- Evidence: pod events, logs, metrics and recent change/commit.
- Root cause and contributing factors.
- Recovery steps and validation.
- Follow-up actions with owners and dates.
