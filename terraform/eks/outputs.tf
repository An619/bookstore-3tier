output "cluster_name" {
  description = "EKS cluster name."
  value       = module.eks.cluster_name
}

output "cluster_endpoint" {
  description = "EKS API endpoint."
  value       = module.eks.cluster_endpoint
}

output "aws_region" {
  description = "AWS region."
  value       = var.aws_region
}

output "backend_ecr_repository_url" {
  description = "Backend ECR repository URL."
  value       = aws_ecr_repository.backend.repository_url
}

output "frontend_ecr_repository_url" {
  description = "Frontend ECR repository URL."
  value       = aws_ecr_repository.frontend.repository_url
}

output "catalog_service_ecr_repository_url" {
  description = "Catalog service ECR repository URL."
  value       = aws_ecr_repository.catalog_service.repository_url
}

output "orders_service_ecr_repository_url" {
  description = "Orders service ECR repository URL."
  value       = aws_ecr_repository.orders_service.repository_url
}

output "inventory_service_ecr_repository_url" {
  description = "Inventory service ECR repository URL."
  value       = aws_ecr_repository.inventory_service.repository_url
}

output "configure_kubectl_command" {
  description = "Command to configure kubectl for this cluster."
  value       = "aws eks update-kubeconfig --region ${var.aws_region} --name ${module.eks.cluster_name}"
}

output "ebs_csi_role_arn" {
  description = "IAM role used by the EBS CSI driver (needed for volume provisioning)."
  value       = aws_iam_role.ebs_csi.arn
}

output "ecr_registry" {
  description = "ECR registry host name used in image references."
  value       = split("/", aws_ecr_repository.frontend.repository_url)[0]
}

output "ecr_repository_prefix" {
  description = "Prefix for image names, e.g. bookstore-lab- (matches scripts/prepare-release.py --repository-prefix)."
  value       = "${local.name}-"
}
