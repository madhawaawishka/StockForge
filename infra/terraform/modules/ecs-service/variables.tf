variable "name" {
  description = "Service name; also its DNS name in the service-discovery namespace."
  type        = string
}

variable "environment" {
  description = "Environment name, e.g. dev."
  type        = string
}

variable "region" {
  description = "AWS region (for the log driver)."
  type        = string
}

variable "image" {
  description = "Container image, pinned by digest or immutable tag (e.g. <account>.dkr.ecr.<region>.amazonaws.com/stockforge/inventory-service:1.4.2)."
  type        = string
}

variable "cluster_arn" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "subnet_ids" {
  description = "Private subnets for the tasks."
  type        = list(string)
}

variable "service_discovery_namespace_id" {
  type = string
}

variable "http_port" {
  type = number
}

variable "grpc_port" {
  type    = number
  default = null
}

variable "management_port" {
  description = "Separate actuator port (the gateway's); health checks use it when set."
  type        = number
  default     = null
}

variable "cpu" {
  description = "CPU units (1024 = 1 vCPU). Below 1 vCPU a JVM service spends its budget on JIT and GC; see docs/benchmarks."
  type        = number
  default     = 1024
}

variable "memory" {
  description = "MiB; the JVM sizes its heap at 75% of it (JAVA_TOOL_OPTIONS in the image)."
  type        = number
  default     = 2048
}

variable "desired_count" {
  type    = number
  default = 2
}

variable "environment_variables" {
  description = "Plain configuration. Never put secrets here."
  type        = map(string)
  default     = {}
}

variable "secrets" {
  description = "Environment variable name => Secrets Manager ARN (optionally with :json-key:: suffix)."
  type        = map(string)
  default     = {}
}

variable "allowed_callers" {
  description = "Caller name => security group id of everything allowed to connect to this service's ports."
  type        = map(string)
  default     = {}
}

variable "target_group_arn" {
  description = "ALB target group, only for the public gateway."
  type        = string
  default     = null
}

variable "log_retention_days" {
  type    = number
  default = 30
}
