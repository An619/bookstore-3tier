# Deploy to Amazon EKS

This is the path after practising locally. It creates **billable** AWS resources
(VPC with NAT gateway, EKS control plane and nodes, ECR storage) and needs an AWS
account you are allowed to spend in. Review `terraform/eks` before applying and
destroy everything when finished.

## What the repository automates

| Step | Command | Notes |
| --- | --- | --- |
| Review planned changes | `make eks-plan` | No changes; requires a filled `terraform.tfvars` |
| Create cluster + ECR | `make eks-apply` | VPC, EKS, managed nodes, 5 ECR repos, EBS CSI driver |
| Push images | `make eks-images` | Four images tagged with the 12-character commit SHA |
| Install workloads | `make eks-deploy` | gp3 StorageClass, database Secrets, Helm releases from ECR |
| Reviewed GitOps manifest | `make eks-release` | Writes `deploy/releases/bookstore-lab.json` for Argo CD |
| Check | `make eks-status` | Pods plus the frontend load-balancer hostname |
| Tear down | `make eks-destroy` | Terraform destroy; type the cluster name to confirm |

Requirements: `aws` (configured credentials), `terraform`, `kubectl`, `helm`,
`docker`, `git`, `python3`.

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
make eks-deploy    # gp3 storage + Secrets + Helm, waits for rollouts
make eks-status    # copy the load-balancer hostname into a browser
```

The frontend is exposed with `service.type=LoadBalancer`; the three APIs and all
databases stay on ClusterIP. Opening the hostname takes a few minutes after the
Service is created (AWS load balancer provisioning).

`make eks-deploy` is idempotent: rerunning it upgrades releases to the current
commit's images and keeps existing database Secrets (it never regenerates
passwords, which would break a running database).

## Storage: the gap this fixes

EKS has no in-tree EBS provisioner. `terraform/eks` now creates
`aws-ebs-csi-driver` with its own IRSA role, and `make eks-deploy` applies
`deploy/eks/storageclass.yaml` (gp3, encrypted, `WaitForFirstConsumer`). Without
both, the PostgreSQL PersistentVolumeClaims stay `Pending` forever. If your
cluster already has a default StorageClass you prefer, remove the default
annotation from `gp3` and set `database.storage`/class values instead.

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
- Databases are in-cluster StatefulSets with `Delete` reclaim policy. Production
  needs RDS/operator, backups with PITR, and tested restores.
- No authentication/authorization in the application, no WAF, no TLS unless you
  add an ingress controller and certificates.
- Secrets are Kubernetes Secrets created from locally generated passwords. Use
  AWS Secrets Manager with External Secrets or IRSA-scoped access for anything
  shared.
- Monitoring is opt-in (`serviceMonitor.enabled`); no alert routing is configured.

## Cost control

`make eks-destroy` removes the VPC, NAT gateway, nodes, cluster and ECR
repositories. If you stop midway, run it anyway: a forgotten NAT gateway or node
group keeps billing. ECR images are deleted with the repositories, so re-push
after recreating.

## Troubleshooting

- `Unable to locate credentials` — run `aws sts get-caller-identity` first.
- `kubectl` points at the wrong cluster — `make eks-apply` writes the kubeconfig
  entry; check `kubectl config current-context`.
- Pods `ImagePullBackOff` — the tag is the current commit; rerun
  `make eks-images` after new commits, or check the ECR repository names.
- `Pending` pods with unbound PVCs — confirm the EBS CSI add-on is `ACTIVE`
  (`kubectl get pods -n kube-system`) and that `gp3` exists
  (`kubectl get storageclass`).
- Load balancer hostname empty — wait; check
  `kubectl -n bookstore describe service bookstore-frontend` events.
