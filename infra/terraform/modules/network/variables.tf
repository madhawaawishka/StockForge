variable "name" {
  description = "Prefix for resource names, e.g. stockforge-dev."
  type        = string
}

variable "cidr_block" {
  description = "VPC address range; subnets are carved out of it as /24s."
  type        = string
  default     = "10.40.0.0/16"
}

variable "az_count" {
  description = "Availability zones to span (at least 2: RDS and MSK need subnets in two AZs)."
  type        = number
  default     = 2

  validation {
    condition     = var.az_count >= 2 && var.az_count <= 3
    error_message = "az_count must be 2 or 3."
  }
}

variable "single_nat_gateway" {
  description = "Share one NAT gateway across AZs (cheaper, but an AZ outage cuts outbound traffic for all)."
  type        = bool
  default     = true
}

variable "log_retention_days" {
  description = "Retention of VPC flow logs."
  type        = number
  default     = 30
}
