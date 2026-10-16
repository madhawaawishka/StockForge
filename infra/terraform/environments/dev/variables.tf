variable "region" {
  type    = string
  default = "eu-west-1"
}

variable "environment" {
  type    = string
  default = "dev"
}

variable "image_tag" {
  description = "Immutable image tag deployed for every service (the CI build's git SHA)."
  type        = string
}

variable "certificate_arn" {
  description = "ACM certificate for the public HTTPS listener. Without one, only plain HTTP is served (demo only)."
  type        = string
  default     = ""
}

variable "postgres_engine_version" {
  description = "RDS PostgreSQL major version. Local development uses 18; use the newest version RDS offers."
  type        = string
  default     = "17"
}

variable "db_instance_class" {
  type    = string
  default = "db.t4g.medium"
}

variable "db_multi_az" {
  description = "Standby in a second AZ with automatic failover. Off in dev to save cost."
  type        = bool
  default     = false
}

variable "redis_node_type" {
  type    = string
  default = "cache.t4g.small"
}

variable "kafka_version" {
  type    = string
  default = "3.9.x"
}

variable "kafka_instance_type" {
  type    = string
  default = "kafka.t3.small"
}

variable "deletion_protection" {
  description = "Protect the database from `terraform destroy`. Turn on for anything that holds real data."
  type        = bool
  default     = false
}
