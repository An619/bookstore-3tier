# Helm and Argo CD GitOps

## Render the chart locally
From the repository root, with Helm installed:

```bash
helm lint ./helm/bookstore
helm template bookstore ./helm/bookstore --namespace bookstore
```

Review the rendered manifests before installing. The chart includes a single PostgreSQL StatefulSet with a persistent volume claim, backend/frontend Deployments and Services, database Secret and initialization ConfigMap. It is intended for learning; it is not a substitute for managed PostgreSQL or a reviewed production chart.

## Install in a disposable Kubernetes cluster
Build and make the application images available to the cluster first. For a remote cluster, push images to a registry and set the image repositories/tags in a separate values file.

```bash
helm upgrade --install bookstore ./helm/bookstore --namespace bookstore --create-namespace
kubectl get pods,svc,pvc -n bookstore
kubectl rollout status deployment/bookstore-backend -n bookstore
kubectl rollout status deployment/bookstore-frontend -n bookstore
```

If the Helm release has a different name, resource names may differ. Use `kubectl get all,pvc -n bookstore` to inspect actual names.

## Argo CD
The starter Application is in `argocd/bookstore-application.yaml`. Apply it only after Argo CD is installed and configured to access this repository. The manifest currently tracks `main`; do not expect the feature branch chart to deploy until the change is merged or the targetRevision is deliberately changed.

Automated prune/self-heal is powerful: review the Git diff and rendered manifests before merging. Never store real passwords in Git or plain Helm values. For shared/cloud environments, integrate an external secret manager and supply secret references rather than committing secret values.

## Image delivery contract
CI should build images with a commit-SHA tag and publish them to a registry. A reviewed GitOps change then updates the chart image tags. Argo CD reconciles the cluster from Git; Jenkins should not directly mutate the cluster in this design.
