variable "aws_region" {
  description = "AWS region for the lab."
  type        = string
  default     = "ap-south-1"
}

variable "project_name" {
  description = "Short project prefix used in AWS resource names."
  type        = string
  default     = "bookstore"
}

variable "environment" {
  description = "Environment label."
  type        = string
  default     = "lab"
}

variable "kubernetes_version" {
  description = "An EKS Kubernetes version currently supported in the selected AWS region. Set this explicitly before applying."
  type        = string
}

variable "cluster_endpoint_public_access_cidrs" {
  description = "Your trusted public IP CIDR(s), typically your-public-ip/32. Do not use 0.0.0.0/0."
  type        = list(string)
}

variable "vpc_cidr" {
  description = "CIDR block for the EKS VPC."
  type        = string
  default     = "10.40.0.0/16"
}

variable "availability_zone_count" {
  description = "Number of Availability Zones used for the lab."
  type        = number
  default     = 3

  validation {
    condition     = var.availability_zone_count >= 2 && var.availability_zone_count <= 3
    error_message = "Use 2 or 3 Availability Zones."
  }
}

variable "node_instance_types" {
  description = "EC2 instance types for the managed node group; check current regional availability and cost."
  type        = list(string)
  default     = ["t3.medium"]
}

variable "node_desired_size" {
  description = "Desired worker count."
  type        = number
  default     = 2
}

variable "node_min_size" {
  description = "Minimum worker count."
  type        = number
  default     = 1
}

variable "node_max_size" {
  description = "Maximum worker count."
  type        = number
  default     = 3
}

variable "enable_ebs_csi" {
  description = "Create EBS-backed persistent storage (EBS CSI driver + IAM role). Disabled by default; the application runs without EBS using ephemeral or external databases."
  type        = bool
  default     = false
}
