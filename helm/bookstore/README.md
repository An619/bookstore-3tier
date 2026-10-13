# Bookstore Helm chart

This chart is a learning baseline for Kubernetes. It deploys the React/Nginx frontend, Node.js API and a single PostgreSQL StatefulSet with a persistent volume claim.

## Validate

```bash
helm lint ./helm/bookstore
helm template bookstore ./helm/bookstore --namespace bookstore
```

## Local/disposable cluster

Build the frontend and backend images and make them available to the selected Kubernetes cluster before installation. A local image on your workstation is not automatically available to a remote cluster.

```bash
helm upgrade --install bookstore ./helm/bookstore --namespace bookstore --create-namespace
kubectl get pods,svc,pvc -n bookstore
kubectl rollout status deployment/bookstore-backend -n bookstore
kubectl rollout status deployment/bookstore-frontend -n bookstore
```

The default images use the tag `local` and the default database password is for a disposable lab only. For a remote cluster, push commit-SHA-tagged images to a registry and override image repositories/tags.

## Database secret

For a learning-only installation, the chart creates a Secret from the local-only value in `values.yaml`. Never use that default in a shared or cloud environment.

For a shared environment, create a Kubernetes Secret using your approved secret-management workflow, containing keys `username` and `password`, then configure `database.existingSecret` to its name. Do not commit actual secret values or put real credentials in command history. On AWS, prefer a managed secrets integration and a reviewed access policy.

## Optional autoscaling and disruption budgets

Set `backend.autoscaling.enabled=true` or `frontend.autoscaling.enabled=true` only when Metrics Server is installed and resource requests are set appropriately. PodDisruptionBudgets are opt-in and only render for configurations with more than one desired replica.

## Production limitations

The in-cluster PostgreSQL StatefulSet is for learning only. A production design should normally consider managed PostgreSQL, tested backups and restores, encryption, monitoring, upgrade strategy, network restrictions, least-privilege access and a defined recovery objective. The chart has not yet been load-tested or validated on EKS.
