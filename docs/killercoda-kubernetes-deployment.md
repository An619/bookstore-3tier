# Killercoda Kubernetes deployment with GHCR images

This guide is for the **single-node Kubernetes playground**. It deploys the three Java services and their learning PostgreSQL workloads. It does not create AWS resources.

## 1. Publish images

The `Publish images to GHCR` workflow runs on pushes to `main` and publishes five images:
- `ghcr.io/an619/bookstore-backend:latest`
- `ghcr.io/an619/bookstore-frontend:latest`
- `ghcr.io/an619/bookstore-catalog-service:latest`
- `ghcr.io/an619/bookstore-orders-service:latest`
- `ghcr.io/an619/bookstore-inventory-service:latest`

Wait for the workflow to finish successfully under **Actions**. In GitHub Packages, make the packages public if you want to pull them anonymously from Killercoda. Do not put a GitHub token in repository files or shell history. If the packages remain private, configure a Kubernetes imagePullSecret using a narrowly scoped token and remove it when done.

## 2. Start Killercoda

Choose the Kubernetes playground, preferably the one-node option for a first test. Open a terminal with `kubectl` and `helm`. Clone the repository:

```bash
git clone https://github.com/An619/bookstore-3tier.git
cd bookstore-3tier
kubectl get nodes
helm version
```

Confirm the node is `Ready` before continuing.

## 3. Create database secrets

These are throwaway lab passwords only. Do not reuse real credentials.

```bash
kubectl create namespace bookstore --dry-run=client -o yaml | kubectl apply -f -
kubectl -n bookstore create secret generic catalog-db-secret --from-literal=password='catalog-lab-change-me' --dry-run=client -o yaml | kubectl apply -f -
kubectl -n bookstore create secret generic orders-db-secret --from-literal=password='orders-lab-change-me' --dry-run=client -o yaml | kubectl apply -f -
kubectl -n bookstore create secret generic inventory-db-secret --from-literal=password='inventory-lab-change-me' --dry-run=client -o yaml | kubectl apply -f -
```

If a chart expects a different secret key, inspect its templates before installing; never guess or paste production credentials.

## 4. Install the microservices

The following uses the `latest` tag for learning convenience. For repeatable deployments, replace it with the immutable `sha-<full-commit-sha>` tag from the publish workflow.

```bash
helm upgrade --install catalog-service ./helm/catalog-service -n bookstore \
  --set image.repository=ghcr.io/an619/bookstore-catalog-service \
  --set image.tag=latest --set image.pullPolicy=Always

helm upgrade --install orders-service ./helm/orders-service -n bookstore \
  --set image.repository=ghcr.io/an619/bookstore-orders-service \
  --set image.tag=latest --set image.pullPolicy=Always

helm upgrade --install inventory-service ./helm/inventory-service -n bookstore \
  --set image.repository=ghcr.io/an619/bookstore-inventory-service \
  --set image.tag=latest --set image.pullPolicy=Always
```

## 5. Verify and troubleshoot

```bash
kubectl -n bookstore get pods,svc,pvc
kubectl -n bookstore rollout status deployment/catalog-service --timeout=180s
kubectl -n bookstore rollout status deployment/orders-service --timeout=180s
kubectl -n bookstore rollout status deployment/inventory-service --timeout=180s
kubectl -n bookstore get events --sort-by=.lastTimestamp
kubectl -n bookstore logs deployment/catalog-service --tail=100
kubectl -n bookstore logs deployment/orders-service --tail=100
kubectl -n bookstore logs deployment/inventory-service --tail=100
```

If a pod is `ImagePullBackOff`, check GHCR package visibility and the image name/tag. If it is `Pending`, inspect PVC status and available playground storage. If it is `CrashLoopBackOff`, inspect pod logs and events. Single-node playground storage and resources are limited, so all database PVCs may not bind or all Java pods may not fit simultaneously.

To test a service locally from the terminal, use port forwarding, for example:

```bash
kubectl -n bookstore port-forward svc/catalog-service 18081:8081
```

In another terminal run `curl -fsS http://127.0.0.1:18081/actuator/health/readiness`. Repeat with the service's configured port for orders and inventory.

## 6. Clean up the playground

```bash
helm uninstall catalog-service -n bookstore
helm uninstall orders-service -n bookstore
helm uninstall inventory-service -n bookstore
kubectl delete namespace bookstore
```

This deletes the namespace and its PVCs in this lab. Do not use this command in a shared or production namespace.

## Limitations

- This is a learning deployment, not a production reference architecture.
- PostgreSQL-in-Helm is for lab use; production should use managed databases, backups, encryption, secret management and tested restore procedures.
- The legacy React/Nginx app and Argo CD are not installed by the commands above.
- No EKS cluster or paid cloud resources are created by this guide.
