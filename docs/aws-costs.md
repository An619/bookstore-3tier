# What costs money on AWS (and what does not)

Only the **EKS** path bills you. Killercoda, the local `kind` lab, Docker Compose
and the Jenkins/SonarQube containers are free (you pay only your own machine and
electricity). Docker Hub's free tier covers the optional image publishing.

Prices below are approximate **us-east-1** list prices checked October 2026, to
show relative weight. Mumbai (`ap-south-1`) is typically ~5-15% higher. Always
confirm with the AWS calculator before applying: prices and free tiers change.

## Billable services this repository creates

| # | Service | What creates it | Approx. cost | Can you avoid it? |
| --- | --- | --- | --- | --- |
| 1 | **EKS control plane** | `module.eks` | **$0.10/hour ≈ $73/month** per cluster, charged even with zero workloads | No, while the cluster exists. `make eks-destroy` stops it. |
| 1a | EKS extended support | Automatic if your cluster's Kubernetes minor version ages out (14 months) | **$0.60/hour ≈ $438/month** (the $0.10 plus a $0.50 surcharge) | Keep `kubernetes_version` current in `terraform.tfvars` and upgrade before the cutoff. |
| 2 | **EC2 worker nodes** | `eks_managed_node_groups` (`t3.medium`, desired 2, max 3) | ≈ $0.04/hour each ≈ **$60/month for 2** | Reduce `node_desired_size`/`node_instance_types`; use Spot (`capacity_type`); destroy when idle. |
| 3 | **EBS root volumes** | Every node has a 30 GB disk (`disk_size = 30`) | ≈ 60 GB total, a few dollars/month | You cannot remove a node's root disk; you *can* lower `disk_size`. This is unrelated to the app data (see #4). |
| 4 | App database storage (EBS) | **Only if you opt in** | ~$0.08/GB-month for gp3 | **Default is off.** `EKS_DB_STORAGE=ephemeral` uses `emptyDir` with no volume. |
| 5 | **NAT Gateway** | `enable_nat_gateway = true`, `single_nat_gateway = true` | **$0.045/hour ≈ $33/month** + **$0.045/GB** processed | Needed for private-subnet nodes to pull images and reach the internet. Could use public subnets (not recommended) or VPC endpoints (also paid, cheaper for heavy pulls). |
| 6 | **Public IPv4 address** | Attached to the NAT Gateway (and any public LB) | $0.005/hour ≈ $3.65/month each | None while NAT exists; newer accounts get a 12-month free tier for 750 hours. |
| 7 | **Elastic Load Balancer** | `make eks-deploy` sets `service.type=LoadBalancer` | ≈ **$16-25/month** for a small ELB, plus per-GB/LCU usage | Yes: keep the Service `ClusterIP` and use `kubectl port-forward`, or delete the Service when finished. |
| 8 | **ECR storage** | 5 private repositories (4 services + legacy backend) | $0.10/GB-month; the four images are a few hundred MB → **under $1/month** | Keep images small and let the lifecycle policy expire untagged ones. |
| 9 | **Data transfer** | Internet egress, cross-AZ traffic, NAT-processed traffic | $0.09/GB egress (first tier); ~$0.01/GB per direction cross-AZ; $0.045/GB through NAT | Keep AZ count at 2 for the lab; pulling images costs NAT processing. |
| 10 | **ECR image scanning (enhanced)** | `scan_on_push = true` uses *basic* scanning | Basic scan is included | Enhanced scanning (Inspector) is billed per image; this repo does not enable it. |

Nothing else in the repository is billable. In particular it does **not** create
RDS, S3 buckets, CloudWatch alarms/log groups, Secrets Manager secrets, KMS
customer-managed keys, Route 53 zones or ACM certificates. If you add them, they
cost extra — see the next section.

## Billable if you add it later

| Service | Typical trigger | Indicative cost |
| --- | --- | --- |
| Route 53 hosted zone | Custom domain for ingress | ≈ $0.50/month per zone + queries |
| AWS Certificate Manager | TLS for an ALB/CloudFront | Free for public certificates; you must still pay for the load balancer |
| Application/Network LB for ingress | Installing an ingress controller | Same ELB charges as #7, often ×1 (shared) |
| RDS / Aurora PostgreSQL | `EKS_DB_STORAGE=external` against AWS | Largest item if you use it; depends on class, storage, Multi-AZ |
| Secrets Manager | Replacing Kubernetes Secrets | ≈ $0.40/secret/month + API calls |
| CloudWatch Logs / Container Insights | Enabling log shipping or metrics | ≈ $0.50/GB ingested; Container Insights per-series/metric |
| AWS WAF, Shield Advanced, GuardDuty | Security add-ons | Varies; WAF from ~$5/month + per-request |
| VPC interface endpoints | Private access to ECR/STS without NAT | ≈ $0.01/hour per AZ per endpoint, plus per-GB |
| KMS customer-managed key | Encrypting volumes with your own key | ≈ $1/month + requests |
| S3 + DynamoDB | Terraform remote state with locking | Cents |
| AWS Support (Business) | Support plan | Percentage of monthly spend |

## Rough monthly total for the default lab

Running 24×7 with the defaults (1 cluster, 2 × `t3.medium`, 1 NAT gateway, 1
load balancer, ephemeral databases):

| Item | ≈ USD/month |
| --- | --- |
| EKS control plane | 73 |
| 2 worker nodes | 60 |
| NAT gateway | 33 |
| Public IPv4 | 3.65 |
| Load balancer | 16-25 |
| Node root disks + ECR | 5-8 |
| **Total** | **≈ $190-205** |

An hour of practice costs roughly **$0.26**. That is why the destroy step matters
more than any optimization below.

## Cost-saving switches in this repository

```sh
# No EBS for application data (default)
make eks-deploy                       # EKS_DB_STORAGE=ephemeral

# Smallest practical cluster: one node, no public load balancer
EKS_NODE_SIZE=t3.small make eks-...   # edit node_instance_types/desired_size in terraform.tfvars
kubectl -n bookstore port-forward svc/bookstore-frontend 8080:80   # instead of LoadBalancer

# Tear down completely when the exercise is finished
make eks-destroy
```

Set `node_desired_size = 1` and `node_instance_types = ["t3.small"]` in
`terraform.tfvars` for a cheaper practice cluster, and keep
`availability_zone_count = 2`.

## The three common ways this lab leaks money

1. **Forgetting to destroy.** The control plane, nodes and NAT gateway bill
   whether or not you use them. `make eks-destroy` removes everything it created.
2. **An orphaned load balancer.** If you `terraform destroy` while the Helm
   release still exists, AWS may keep the ELB billing until it is cleaned up.
   Run `make eks-uninstall` first (it uninstalls the releases and waits for the
   Service's load balancer to disappear), then `make eks-destroy`.
3. **`terraform destroy` failing on non-empty ECR repositories.** Repositories use
   `force_delete = false`, so destroy stops if images remain. Empty or delete the
   images first, then re-run destroy.

Also check **Billing → Cost Explorer** after the first hour and set a **budget
alert**. Cheapest possible check: `aws ce get-cost-and-usage` for the lab tag.

## Verify rather than trust this page

```sh
terraform -chdir=terraform/eks plan -out=tfplan   # review resources first
terraform -chdir=terraform/eks show tfplan | grep -E 'resource "(aws_eks|aws_instance|aws_nat|aws_ecr|aws_iam)"'
```

Then price exactly what the plan creates with the AWS Pricing Calculator for your
region. Treat all figures above as order-of-magnitude guidance, not a quote.
