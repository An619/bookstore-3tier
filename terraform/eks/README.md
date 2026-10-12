# Terraform: AWS EKS lab

This configuration provisions a VPC with public/private subnets, one NAT gateway, an EKS cluster with managed worker nodes, and two immutable ECR repositories with image scanning on push and an untagged-image lifecycle policy.

## Prerequisites
- Terraform 1.6+
- AWS CLI configured with an identity that has the required VPC, IAM, EKS, EC2 and ECR permissions
- A current EKS Kubernetes version supported in your selected region
- Your public IP CIDR to restrict access to the public EKS API endpoint

## Prepare

```bash
cd terraform/eks
cp terraform.tfvars.example terraform.tfvars
```

Edit `terraform.tfvars`: replace the EKS version placeholder and replace the IP placeholder with your trusted public IP in `x.x.x.x/32` format. Do not widen the endpoint to `0.0.0.0/0`.

## Validate and plan

```bash
terraform fmt -recursive
terraform init
terraform validate
terraform plan
```

Review every resource and estimated cost before applying. The NAT gateway, EKS control plane, worker EC2 instances, EBS volumes, load balancers and data transfer can incur charges. This is not a free-tier guarantee.

## Provision and connect

```bash
terraform apply
aws eks update-kubeconfig --region ap-south-1 --name bookstore-lab-eks
kubectl get nodes
```

Use the actual outputs if you change the region/name inputs. Build and push images to the output ECR repositories, then configure Helm values with their full URLs and commit-SHA tags.

## Cleanup

After saving any evidence and confirming the environment is no longer needed:

```bash
terraform destroy
```

Review the destroy plan carefully. The ECR repositories use `force_delete = false`, so repositories containing images may need to be emptied before destruction. Back up any state and data you need.

## Important notes
- The Terraform code has been added as a starter and must be validated with the Terraform version and AWS region you choose before applying.
- Use a remote state backend with locking for team/shared use; this starter intentionally does not configure one automatically.
- Restrict AWS IAM permissions and use temporary credentials/roles where possible.
- PostgreSQL remains an in-cluster learning database in the Helm chart. A production architecture should evaluate managed PostgreSQL, backup/restore, encryption, monitoring and network access separately.
