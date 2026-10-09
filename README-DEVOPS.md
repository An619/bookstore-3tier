# Bookstore DevOps Practice

This branch adds a portable DevOps setup for the React + Node/Express + PostgreSQL application.

## Practice order

1. Deploy the app with Helm on Killercoda.
2. Learn Docker builds and publish the two images to Docker Hub.
3. Configure Jenkins credentials and run the CI/CD pipeline.
4. Deploy to EKS after the Killercoda workflow works.
5. For a production-style AWS setup, move PostgreSQL to Amazon RDS.

## Prerequisites

- A Kubernetes playground/cluster with `kubectl` configured.
- Helm 3.
- Docker and a Docker Hub account for building/publishing images.
- Use your **Docker Hub username**, which may differ from your GitHub username.

## Local Docker

```bash
docker compose up --build
```

Open the Killercoda/local preview for port 8080. The database is initialized from `database/init.sql`.

## Helm on Killercoda

First build and push both images to your own public Docker Hub repositories, replacing `YOUR_DOCKERHUB_USERNAME`:

```bash
docker build -t YOUR_DOCKERHUB_USERNAME/bookstore-backend:killercoda ./backend
docker build -t YOUR_DOCKERHUB_USERNAME/bookstore-frontend:killercoda ./frontend
docker login
docker push YOUR_DOCKERHUB_USERNAME/bookstore-backend:killercoda
docker push YOUR_DOCKERHUB_USERNAME/bookstore-frontend:killercoda
```

Then deploy the chart with your image names:

```bash
helm lint ./helm/bookstore
helm upgrade --install bookstore ./helm/bookstore \
  --namespace bookstore --create-namespace \
  -f ./helm/bookstore/values-killercoda.yaml \
  --set backend.image.repository=YOUR_DOCKERHUB_USERNAME/bookstore-backend \
  --set backend.image.tag=killercoda \
  --set frontend.image.repository=YOUR_DOCKERHUB_USERNAME/bookstore-frontend \
  --set frontend.image.tag=killercoda \
  --wait --timeout 5m

kubectl get pods -n bookstore
kubectl get svc -n bookstore
kubectl get events -n bookstore --sort-by=.metadata.creationTimestamp
```

The frontend is exposed as NodePort 30080. Use Killercoda's exposed-port/web-preview feature if it supports that port; otherwise use `kubectl port-forward -n bookstore svc/bookstore-frontend 8080:80` and preview port 8080.

The Helm chart now mounts the chart's `files/init.sql` into PostgreSQL's initialization directory. PostgreSQL runs this script only when its data directory is first initialized. On a reused persistent volume, it will not automatically re-run the script.

## Helm on EKS

```bash
aws eks update-kubeconfig --region YOUR_AWS_REGION --name YOUR_CLUSTER_NAME
kubectl get nodes
helm lint ./helm/bookstore
helm upgrade --install bookstore ./helm/bookstore \
  --namespace bookstore --create-namespace \
  -f ./helm/bookstore/values-eks.yaml
kubectl get pods -n bookstore
kubectl get svc -n bookstore
``

The frontend uses a LoadBalancer service. The EKS values request a `gp3` StorageClass for PostgreSQL; verify that this StorageClass exists and that the Amazon EBS CSI driver is installed before using in-cluster PostgreSQL persistence. For a more production-like design, use Amazon RDS instead of running PostgreSQL in Kubernetes.

## Jenkins

Create a Jenkins credential:
- ID: `dockerhub-creds`
- Type: Username with password
- Username: Docker Hub username
- Password: Docker Hub access token (not your account password)

Set the pipeline parameter `DOCKERHUB_USERNAME` to your actual Docker Hub username. The Jenkins agent needs Node.js/npm, Docker with daemon access, Helm, and Kubernetes credentials for the selected target cluster. Create/configure separate, secure cluster access for Killercoda and EKS as needed.

The chart's `bookpass` database password is only a lab default. Do not use it for a public or production deployment. Do not commit passwords, tokens, kubeconfigs, or `.env` files.
