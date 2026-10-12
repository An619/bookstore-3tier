# Bookstore Helm chart

This learning chart deploys the React/Nginx frontend, existing Node.js API, Java/Spring Boot catalog microservice and a single PostgreSQL StatefulSet with a persistent volume claim.

## Validate

```bash
helm lint ./helm/bookstore
helm template bookstore ./helm/bookstore --namespace bookstore
```

## Install in a disposable cluster

Build all three application images and make them available to the selected Kubernetes cluster before installation. Images on your workstation are not automatically available to a remote cluster.

```bash
helm upgrade --install bookstore ./helm/bookstore --namespace bookstore --create-namespace
kubectl get pods,svc,pvc -n bookstore
kubectl rollout status deployment/bookstore-backend -n bookstore
kubectl rollout status deployment/bookstore-frontend -n bookstore
kubectl rollout status deployment/bookstore-catalog -n bookstore
kubectl port-forward svc/bookstore-catalog 8081:8081 -n bookstore
```

The catalog service is available internally at `bookstore-catalog:8081` when the default release name is used. Its sample data is in-memory and is not yet connected to the legacy API or PostgreSQL. The frontend continues to use the legacy Node API at this migration stage.

The default image tags and database password are for a disposable lab only. For a remote cluster, push commit-SHA-tagged images and override image repositories/tags using reviewed environment values.

## Database secrets

For learning-only installation, the chart creates a Secret from the local-only value in `values.yaml`. Never use that default in a shared or cloud environment. For a shared environment, pre-create a Kubernetes Secret through an approved secret workflow with `username` and `password` keys, then set `database.existingSecret`.

## Production limitations

The in-cluster PostgreSQL StatefulSet and in-memory catalog are learning components, not a production data architecture. A production design needs explicit data ownership, migrations, backups/restores, encryption, monitoring, network restrictions, least-privilege access and recovery objectives. This chart has not been load-tested or validated on EKS.
