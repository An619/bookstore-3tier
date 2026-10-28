# Deploy to Amazon EKS

This is the path after practising locally. It creates **billable** AWS resources
(VPC with NAT gateway, EKS control plane and nodes, ECR storage) and needs an AWS
account you are allowed to spend in. Review `terraform/eks` before applying and
destroy everything when finished.

## What the repository automates

| Step | Command | Notes |
| --- | --- | --- |
| Review planned changes | `make eks-plan` | No changes; requires a filled `terraform.tfvars` |
| Create cluster + ECR | `make eks-apply` | VPC, EKS, managed nodes, 5 ECR repos. **No EBS by default.** Type the cluster name to confirm; `EKS_AUTO_APPROVE=true` skips (CI only) |
| Push images | `make eks-images` | Four images tagged with the 12-character commit SHA |
| Install workloads | `make eks-deploy` | Database Secrets, Helm releases from ECR, `EKS_DB_STORAGE=ephemeral` (no EBS) unless you override it |
| Reviewed GitOps manifest | `make eks-release` | Writes `deploy/releases/bookstore-lab.json` for Argo CD |
| Verify | `make eks-verify` | `scripts/verify-deploy.sh` against the load balancer: rollouts, pod health, Flyway versions, checkout saga |
| Check | `make eks-status` | Pods plus the frontend load-balancer hostname |
| Remove workloads | `make eks-uninstall` | Uninstalls Helm releases and waits for the load balancer to go |
| Tear down | `make eks-destroy` | Terraform destroy; type the cluster name to confirm |
| What costs money | `docs/aws-costs.md` | Every billable service, and what is free |

Requirements: `aws` (configured credentials), `terraform`, `kubectl`, `helm`,
`docker`, `git`, `python3`. `make eks-apply` refuses to run without
`terraform/eks/terraform.tfvars`, and requires you to type the cluster name
because it starts billing.

> If you applied an earlier revision of this Terraform, ECR repository names
> changed from `<project>-<environment>/<service>` to
> `<project>-<environment>-<service>`. `make eks-plan` will show the old
> repositories being replaced; recreate and re-push images, or keep the old names
> and pass `--repository-prefix` values that match them.

## First-time setup on your machine

```sh
cd terraform/eks
cp terraform.tfvars.example terraform.tfvars
# Then edit terraform.tfvars:
#  - kubernetes_version: a version your region currently supports
#  - cluster_endpoint_public_access_cidrs: YOUR public IP /32 (never 0.0.0.0/0)
#  - aws_region / project_name / environment
```

Set region and cluster name for the helper scripts (they read these environment
variables, with defaults `ap-south-1` and `bookstore-lab-eks`):

```sh
export AWS_REGION=ap-south-1
export EKS_CLUSTER=bookstore-lab-eks
```

If you change `project_name` or `environment`, also export `EKS_PROJECT` and
`EKS_ENVIRONMENT`: the scripts build image names as
`<account>.dkr.ecr.<region>.amazonaws.com/<project>-<environment>-<component>`,
which matches the Terraform repository names.

## Deploy

```sh
make eks-plan      # read the plan carefully
make eks-apply     # ~15-20 minutes
make eks-images    # ~10 minutes (Maven builds inside Docker)
make eks-deploy    # Secrets + Helm, waits for rollouts
make eks-verify    # waits for the load balancer, then proves the deployment end to end
make eks-status    # copy the load-balancer hostname into a browser
```

The frontend is exposed with `service.type=LoadBalancer`; the three APIs and all
databases stay on ClusterIP. Opening the hostname takes a few minutes after the
Service is created (AWS load balancer provisioning).

`make eks-deploy` is idempotent: rerunning it upgrades releases to the current
commit's images and keeps existing database Secrets (it never regenerates
passwords, which would break a running database).

## Credentials from a secret manager (optional)

The charts never create real credentials. For a shared cluster, let External
Secrets Operator sync the three database Secrets from AWS Secrets Manager
instead of creating them by hand: `deploy/eks/external-secrets/` has the
`ClusterSecretStore` and one `ExternalSecret` per database, plus the IRSA steps.
It is deliberately **not** part of `make eks-deploy`, because it needs an IAM
role binding first. The synced Secret names match what the charts reference, so
`make eks-deploy` picks them up unchanged.

## Availability and backups

The charts already render a PodDisruptionBudget, so node drains during upgrades
cannot take the last healthy replica. Two opt-in switches matter on a real
cluster:

```sh
# Two replicas + autoscaling (needs metrics-server, which EKS provides).
helm upgrade orders-service helm/orders-service --reuse-values \
  --set replicaCount=2 --set autoscaling.enabled=true --set autoscaling.maxReplicas=6

# Restorable nightly backups, copied off-cluster (needs S3 permissions, e.g. IRSA).
helm upgrade catalog-service helm/catalog-service --reuse-values \
  --set backup.enabled=true --set backup.s3.bucket=my-backups
```

`make backup-plan` prints the regime (schedule, destination, restore check) before
you enable it. EKS does not create the bucket or the IAM role for the uploader.

## Storage: no EBS required

This path does **not** use EBS. EKS has no built-in volume provisioner, so
instead of installing the EBS CSI driver the deployment chooses how the three
PostgreSQL databases get storage via `EKS_DB_STORAGE`:

| Mode | What happens | Data survives pod restart? | Use when |
| --- | --- | --- | --- |
| `ephemeral` (**default**) | Databases run with `emptyDir`; no StorageClass, no CSI driver, no EBS volumes | **No** | Practising the full topology cheaply |
| `pvc` | Databases use an existing StorageClass you name with `EKS_STORAGE_CLASS` (EFS, Longhorn, or EBS if you have it) | Yes, if the provisioner is durable | You already run a storage provider |
| `external` | No database pods; services point at managed PostgreSQL via `EKS_DB_HOST_CATALOG/ORDERS/INVENTORY`; Secrets must pre-exist | Yes (managed by the provider) | Anything you care about |

Examples:

```sh
make eks-deploy                                  # ephemeral (default), no EBS
EKS_DB_STORAGE=pvc EKS_STORAGE_CLASS=efs-sc make eks-deploy   # your own class
EKS_DB_STORAGE=external \
  EKS_DB_HOST_CATALOG=catalog.abc.ap-south-1.rds.amazonaws.com \
  EKS_DB_HOST_ORDERS=orders.abc.ap-south-1.rds.amazonaws.com \
  EKS_DB_HOST_INVENTORY=inventory.abc.ap-south-1.rds.amazonaws.com \
  make eks-deploy
```

Rendered charts make this explicit with a `storage-mode:` marker, and
`sh scripts/check-charts.sh` verifies all three profiles.

With `ephemeral`, a database pod restart **destroys its data**: the smoke test
and demo orders disappear, and Flyway re-runs the migrations and re-seeds the
sample rows on the next start. That is acceptable for a practice cluster; do
not rely on it. With `pvc`, no
StorageClass is created for you: install EFS CSI, Longhorn or similar, or run
`kubectl apply -f deploy/eks/optional/ebs-storageclass.yaml` **only** if you
deliberately enabled EBS.

### If you later want EBS

Set `enable_ebs_csi = true` in `terraform/eks/terraform.tfvars` (it defaults to
`false`), apply, then use `EKS_DB_STORAGE=pvc EKS_STORAGE_CLASS=gp3` together
with the optional StorageClass file above. Nothing enables EBS implicitly.

## GitOps with Argo CD

```sh
make eks-release       # writes deploy/releases/bookstore-lab.json
# review it, commit it, then:
kubectl apply -f deploy/releases/bookstore-lab.json
```

The manifest pins the ECR image paths and the commit SHA, so Argo CD deploys
exactly what you pushed. Argo CD itself is not installed by Terraform; install it
separately, and keep the repository's Applications on manual sync for a shared
cluster. Note that a private ECR registry needs pull permission for the nodes
(the managed node role already has `AmazonEC2ContainerRegistryReadOnly`).

## What is still not production-ready

- Single NAT gateway, two AZs and small nodes: fine for a lab, not for real
  traffic. No autoscaling policy tuning, no PodDisruptionBudgets.
- The default `ephemeral` database storage loses data on every pod restart. For
  anything real, use `external` managed PostgreSQL (or a durable StorageClass)
  with backups and tested restores.
- No authentication/authorization in the application, no WAF, no TLS unless you
  add an ingress controller and certificates.
- Secrets are Kubernetes Secrets created from locally generated passwords. Use
  AWS Secrets Manager with External Secrets or IRSA-scoped access for anything
  shared.
- Monitoring is opt-in (`serviceMonitor.enabled`); no alert routing is configured.

## Cost control

`make eks-destroy` removes the VPC, NAT gateway, nodes, cluster and ECR
repositories. If you stop midway, run it anyway: a forgotten NAT gateway or node
group keeps billing.

Order matters: run `make eks-uninstall` **first** so the frontend load balancer is
deleted, then `make eks-destroy`. Destroying the cluster first can leave an
orphaned ELB billing until it is cleaned up manually. Destroy also stops if the
ECR repositories still contain images (`force_delete = false`): empty them, then
re-run. See `docs/aws-costs.md` for the complete cost breakdown.

## Troubleshooting

- `Unable to locate credentials` — run `aws sts get-caller-identity` first.
- `AWS_REGION` must match `aws_region` in `terraform.tfvars`; the scripts use it
  to build ECR host names.
- `kubectl` points at the wrong cluster — `make eks-apply` writes the kubeconfig
  entry; check `kubectl config current-context`.
- Pods `ImagePullBackOff` — the tag is the current commit; rerun
  `make eks-images` after new commits, or check the ECR repository names.
- Database pods `Pending` with unbound PVCs — only applies in `pvc` mode: check
  `kubectl get storageclass` for the class you named and that its provisioner is
  running. In the default `ephemeral` mode there are no PVCs at all.
- Data vanished after a pod restart — expected in `ephemeral` mode; switch to
  `pvc` or `external` for persistence.
- Load balancer hostname empty — wait; check
  `kubectl -n bookstore describe service bookstore-frontend` events.
