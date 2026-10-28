# Helm and Argo CD GitOps

> For the current microservices-only stack and release workflow, see
> [Microservices build and delivery](microservices-delivery.md). Legacy instructions
> below are retained for reference; the Node API now requires `--profile legacy`.

## Render the chart locally
From the repository root, with Helm installed:

```bash
helm lint ./helm/bookstore
helm template bookstore ./helm/bookstore --namespace bookstore
```

Review rendered manifests before installation. The chart includes frontend/backend Deployments and Services, a PostgreSQL StatefulSet with persistent storage, a database Secret/ConfigMap, optional Ingress, optional HPA and optional PodDisruptionBudgets.

## Image delivery contract
CI builds images tagged with the source commit. Publish those images to a registry and set the chart's frontend/backend image repositories and commit-SHA tags for the target environment. A local image named bookstore-frontend:local or bookstore-backend:local is not available to a remote cluster unless you explicitly load it.

For AWS, use the ECR URLs output by Terraform and a reviewed environment values file. The sample chart defaults are for a disposable lab.

## Database credentials
The default password in values.yaml is a local-only learning value. Never use it in a shared or cloud environment. Create a Kubernetes Secret with keys username and password using your approved secret-management workflow, then set database.existingSecret to that Secret name. Do not commit actual secret values or put real credentials in command history.

## Argo CD
The starter Application is argocd/bookstore-application.yaml. It tracks main, uses the chart at helm/bookstore, and intentionally uses **manual sync**. This avoids accidentally deploying local-only image tags and credentials when Argo CD is first connected.

After you configure the image tags, database Secret, ingress and target cluster, review the rendered diff and then sync from Argo CD. Only enable automated sync/self-heal/prune after you understand the impact and have a reviewed rollback process.

## Deploy in a disposable cluster
Build and make images available to the cluster first, then:

```bash
helm upgrade --install bookstore ./helm/bookstore --namespace bookstore --create-namespace
kubectl get pods,svc,pvc -n bookstore
kubectl rollout status deployment/bookstore-backend -n bookstore
kubectl rollout status deployment/bookstore-frontend -n bookstore
```

Resource names depend on the Helm release name. Inspect actual resources with kubectl get all,pvc -n bookstore.

## Important limitation
The PostgreSQL StatefulSet is a learning component, not a managed/production database. A production design needs tested backup/restore, upgrade, encryption, monitoring, access controls and a defined recovery objective.
