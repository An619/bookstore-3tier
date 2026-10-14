# Kubernetes deployment runbook

This runbook takes the merged microservices project from a passing CI build to a **disposable Kubernetes lab**. It does not provision AWS or assume registry credentials.

## 1. Choose the target

Use a Kubernetes cluster you control (for example, a Killercoda Kubernetes playground or local kind/minikube). Check the context before changing anything:

```bash
kubectl config current-context
kubectl get nodes -o wide
helm version
```

Do not run these commands against a production cluster. The charts currently include PostgreSQL StatefulSets intended for learning, not production data services.

## 2. Build and publish the three Java service images

Run from the repository root on a machine with Docker and access to Docker Hub. Replace `DOCKERHUB_USER` with your registry username. Use a unique tag (prefer the Git commit SHA); do not overwrite a tag if the registry repository is configured as immutable.

```bash
git checkout main
git pull --ff-only
git rev-parse --short=12 HEAD
docker login
```

Build and push the Catalog, Orders and Inventory images. Replace `<COMMIT_SHA>` with the value printed by `git rev-parse`:

```bash
export DOCKERHUB_USER=<your-dockerhub-username>
export IMAGE_TAG=<COMMIT_SHA>

docker build -t ${DOCKERHUB_USER}/bookstore-catalog-service:${IMAGE_TAG} ./services/catalog-service
docker build -t ${DOCKERHUB_USER}/bookstore-orders-service:${IMAGE_TAG} ./services/orders-service
docker build -t ${DOCKERHUB_USER}/bookstore-inventory-service:${IMAGE_TAG} ./services/inventory-service

docker push ${DOCKERHUB_USER}/bookstore-catalog-service:${IMAGE_TAG}
docker push ${DOCKERHUB_USER}/bookstore-orders-service:${IMAGE_TAG}
docker push ${DOCKERHUB_USER}/bookstore-inventory-service:${IMAGE_TAG}
```

If any repository is private, create an image-pull secret in the target namespace and reference it in the chart pod specs before deployment. Never commit registry passwords or access tokens.

## 3. Create namespace and database credentials

```bash
kubectl create namespace bookstore --dry-run=client -o yaml | kubectl apply -f -
read -rsp "Database password for this lab: " DB_PASSWORD; echo
kubectl -n bookstore create secret generic catalog-db-secret --from-literal=username=cataloguser --from-literal=password="$DB_PASSWORD" --dry-run=client -o yaml | kubectl apply -f -
kubectl -n bookstore create secret generic orders-db-secret --from-literal=username=serviceuser --from-literal=password="$DB_PASSWORD" --dry-run=client -o yaml | kubectl apply -f -
kubectl -n bookstore create secret generic inventory-db-secret --from-literal=username=serviceuser --from-literal=password="$DB_PASSWORD" --dry-run=client -o yaml | kubectl apply -f -
unset DB_PASSWORD
```

These charts expect the secret keys `username` and `password`. Use a strong disposable lab password. If the PostgreSQL StatefulSets have already initialized persistent volumes with a different password, changing the Kubernetes Secret alone will not change the database user's password; reset the disposable lab volume or update the DB user deliberately.

## 4. Install or upgrade each Helm release

Run from the repository root. The following commands use the image tag built above and publish the services as internal ClusterIP services:

```bash
helm upgrade --install catalog-service ./helm/catalog-service -n bookstore \
  --set image.repository="${DOCKERHUB_USER}/bookstore-catalog-service" \
  --set image.tag="${IMAGE_TAG}" --set image.pullPolicy=IfNotPresent

helm upgrade --install orders-service ./helm/orders-service -n bookstore \
  --set image.repository="${DOCKERHUB_USER}/bookstore-orders-service" \
  --set image.tag="${IMAGE_TAG}" --set image.pullPolicy=IfNotPresent

helm upgrade --install inventory-service ./helm/inventory-service -n bookstore \
  --set image.repository="${DOCKERHUB_USER}/bookstore-inventory-service" \
  --set image.tag="${IMAGE_TAG}" --set image.pullPolicy=IfNotPresent
```

Wait and inspect:

```bash
kubectl -n bookstore get pods,svc,pvc
kubectl -n bookstore get events --sort-by=.lastTimestamp
kubectl -n bookstore rollout status deployment/catalog-service --timeout=180s
kubectl -n bookstore rollout status deployment/orders-service --timeout=180s
kubectl -n bookstore rollout status deployment/inventory-service --timeout=180s
```

The exact Deployment names follow the Helm release names above. If a pod is not ready, inspect it before retrying:

```bash
kubectl -n bookstore describe pod <pod-name>
kubectl -n bookstore logs <pod-name> --all-containers=true --tail=200
kubectl -n bookstore get pvc
```

A pending PVC usually indicates the cluster lacks a working default StorageClass. A `CreateContainerConfigError` may indicate a missing/misnamed Secret. An `ImagePullBackOff` commonly indicates a wrong image name/tag or registry access issue.

## 5. Smoke-test the services

Port-forward one service at a time from a terminal:

```bash
kubectl -n bookstore port-forward svc/catalog-service 18081:8081
```

In a second terminal:

```bash
curl -fsS http://localhost:18081/actuator/health/readiness
curl -fsS http://localhost:18081/api/catalog/books
```

Stop the first port-forward with Ctrl+C, then test Orders and Inventory:

```bash
kubectl -n bookstore port-forward svc/orders-service 18082:8082
# In another terminal: curl -fsS http://localhost:18082/actuator/health/readiness
# Then stop port-forward and repeat for inventory:
kubectl -n bookstore port-forward svc/inventory-service 18083:8083
# In another terminal: curl -fsS http://localhost:18083/actuator/health/readiness
curl -fsS http://localhost:18083/api/inventory
```

For a browser-based playground, use its exposed-port/web-preview feature only if that platform supports it; a local `kubectl port-forward` is not automatically public.

## 6. Roll back or clean up

```bash
helm history catalog-service -n bookstore
helm rollback catalog-service <REVISION> -n bookstore
```

When finished with this disposable lab, remove the releases and namespace only if you do not need the data:

```bash
helm uninstall catalog-service -n bookstore
helm uninstall orders-service -n bookstore
helm uninstall inventory-service -n bookstore
kubectl delete namespace bookstore
```

Deleting the namespace and PVCs can permanently delete lab database data. Confirm the current Kubernetes context before cleanup.

## Current scope and known gaps

- CI passing is not the same as a successful deployment; record the actual pod, PVC and HTTP test results for your chosen cluster.
- This runbook deploys the three Java microservices, not the complete React frontend/legacy Node stack.
- PostgreSQL-in-Helm and lab credentials are educational only. Production needs managed/secured database operations, backup/restore, external secret management, authentication/authorization, TLS, resource/capacity review, and a reviewed ingress strategy.
- Inventory reservation must be made concurrency-safe and order creation needs idempotency/contract tests before this should be considered production-grade.
- AWS EKS is intentionally not created by this runbook. Review Terraform cost implications and variables separately before provisioning.
