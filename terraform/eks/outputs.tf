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

output "frontend_ecr_repository_url" {
  description = "Frontend ECR repository URL."
  value       = aws_ecr_repository.frontend.repository_url
}

output "configure_kubectl_command" {
  description = "Command to configure kubectl for this cluster."
  value       = "aws eks update-kubeconfig --region ${var.aws_region} --name ${module.eks.cluster_name}"
}
