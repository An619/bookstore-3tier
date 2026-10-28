# Database credentials from a secret manager (optional)

The charts never create production credentials: `database.createSecret=false`
(their default) expects a Secret that already exists. You can create it by hand,
or let External Secrets Operator (ESO) sync it from AWS Secrets Manager, SSM
Parameter Store, Vault or GCP/Azure equivalents.

This directory is **not** applied by `make eks-deploy`; it is a starting point
for a shared cluster.

## 1. Install the operator (once per cluster)

```sh
helm repo add external-secrets https://charts.external-secrets.io
helm repo update
helm install external-secrets external-secrets/external-secrets \
  --namespace external-secrets --create-namespace
```

## 2. Let the operator read AWS

The `ClusterSecretStore` below uses the node/pod role. On EKS the cleanest
option is IRSA: create an IAM role that can read the secret and bind it to the
`external-secrets` service account.

```sh
eksctl create iamserviceaccount \
  --cluster bookstore-lab-eks --namespace external-secrets \
  --name external-secrets \
  --attach-policy-arn arn:aws:iam::aws:policy/SecretsManagerReadWrite \
  --approve
```

## 3. Create the secrets in Secrets Manager

One JSON secret per database, with `username` and `password` keys:

```sh
aws secretsmanager create-secret --name bookstore/lab/catalog-db \
  --secret-string '{"username":"cataloguser","password":"<generated>"}'
```

## 4. Point the charts at the synced Secrets

The names must match `database.existingSecret`:

```sh
kubectl apply -f deploy/eks/external-secrets/cluster-secret-store.yaml
kubectl apply -f deploy/eks/external-secrets/external-secrets.yaml

make eks-deploy     # the charts find catalog-db-secret, orders-db-secret, inventory-db-secret
```

`sh scripts/verify-deploy.sh` fails if a database cannot connect, so a missing or
mis-synced Secret shows up there rather than in a customer report.

**Do not** commit real secret values, and do not use `createSecret=true` outside
a disposable lab.
