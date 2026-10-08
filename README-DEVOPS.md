# Bookstore DevOps Practice

This branch adds a portable DevOps setup for the existing React + Node/Express + PostgreSQL application.

## Practice order

1. Killercoda Kubernetes
2. Jenkins CI/CD on Killercoda
3. Docker Hub image push
4. EKS deployment
5. Later: replace in-cluster PostgreSQL with Amazon RDS

## Local Docker

docker compose up --build

Open port 8080.

## Helm on Killercoda

helm upgrade --install bookstore ./helm/bookstore \
  --namespace bookstore --create-namespace \
  -f ./helm/bookstore/values-killercoda.yaml

kubectl get pods -n bookstore
kubectl get svc -n bookstore

The frontend is exposed as NodePort 30080.

## Helm on EKS

aws eks update-kubeconfig --region <region> --name <cluster>

helm upgrade --install bookstore ./helm/bookstore \
  --namespace bookstore --create-namespace \
  -f ./helm/bookstore/values-eks.yaml

kubectl get svc -n bookstore

The frontend uses a LoadBalancer service.

## Jenkins

Create a Jenkins credential:
- ID: dockerhub-creds
- Type: Username with password
- Username: Docker Hub username
- Password: Docker Hub access token

The Jenkins agent needs Docker, kubectl, Helm, and cluster access.

Do not commit passwords, tokens, kubeconfigs, or .env files.
