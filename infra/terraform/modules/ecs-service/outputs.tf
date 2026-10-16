output "security_group_id" {
  value = aws_security_group.this.id
}

output "dns_name" {
  description = "Name other services use to reach this one (inside the namespace's domain)."
  value       = aws_service_discovery_service.this.name
}

output "log_group_name" {
  value = aws_cloudwatch_log_group.this.name
}
