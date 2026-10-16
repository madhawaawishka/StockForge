# StockForge on AWS (spec §34, Tier 3): a reference deployment of the same containers that run in docker-compose.
#
#   internet ── ALB (only public entry) ── api-gateway ──gRPC── inventory-service, order-service
#                                                            Kafka (MSK) ── payment-service, notification-worker
#   private subnets: ECS Fargate tasks, RDS PostgreSQL, ElastiCache Redis, MSK — encrypted at rest and in transit.
#
# Not applied by this project: see ../../README.md for cost, the one-off database bootstrap and what is left out.

locals {
  name     = "stockforge-${var.environment}"
  services = ["api-gateway", "inventory-service", "order-service", "payment-service", "notification-worker"]
  # Each service owns one schema and one database role (ADR-002); the gateway has no database.
  db_services = {
    inventory-service   = { schema = "inventory", role = "inventory_svc", env_prefix = "INVENTORY" }
    order-service       = { schema = "ordering", role = "order_svc", env_prefix = "ORDER" }
    payment-service     = { schema = "payment", role = "payment_svc", env_prefix = "PAYMENT" }
    notification-worker = { schema = "notification", role = "notification_svc", env_prefix = "NOTIFICATION" }
  }
  discovery_domain = "stockforge.internal"
  image            = { for s in local.services : s => "${aws_ecr_repository.service[s].repository_url}:${var.image_tag}" }

  # Configuration shared by every service; the services read these names (see each application.yml).
  common_env = {
    KAFKA_BOOTSTRAP_SERVERS              = aws_msk_cluster.this.bootstrap_brokers_tls
    SPRING_KAFKA_SECURITY_PROTOCOL       = "SSL"
    STOCKFORGE_MESSAGING_TOPICS_REPLICAS = "2"
    REDIS_HOST                           = aws_elasticache_replication_group.this.primary_endpoint_address
    SPRING_DATA_REDIS_SSL_ENABLED        = "true"
    TRACING_SAMPLING_PROBABILITY         = "0.1"
  }
}

module "network" {
  source = "../../modules/network"
  name   = local.name
}

# --- Container registry: immutable tags, scanned on push (spec §28 supply chain)

resource "aws_ecr_repository" "service" {
  for_each             = toset(local.services)
  name                 = "stockforge/${each.key}"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
  encryption_configuration {
    encryption_type = "KMS"
  }
}

resource "aws_ecr_lifecycle_policy" "service" {
  for_each   = aws_ecr_repository.service
  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last 30 images"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 30 }
      action       = { type = "expire" }
    }]
  })
}

# --- Compute

resource "aws_ecs_cluster" "this" {
  name = local.name
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_service_discovery_private_dns_namespace" "this" {
  name = local.discovery_domain
  vpc  = module.network.vpc_id
}

# --- Secrets: generated here, stored in Secrets Manager, injected into tasks; never in images, env files or Git.

resource "random_password" "db_role" {
  for_each = local.db_services
  length   = 32
  special  = false
}

resource "aws_secretsmanager_secret" "db_role" {
  for_each                = local.db_services
  name                    = "${local.name}/${each.key}/db-password"
  recovery_window_in_days = 7
}

resource "aws_secretsmanager_secret_version" "db_role" {
  for_each      = local.db_services
  secret_id     = aws_secretsmanager_secret.db_role[each.key].id
  secret_string = random_password.db_role[each.key].result
}

resource "random_password" "jwt" {
  length  = 64
  special = false
}

resource "aws_secretsmanager_secret" "jwt" {
  name                    = "${local.name}/api-gateway/jwt-hmac-key"
  recovery_window_in_days = 7
}

resource "aws_secretsmanager_secret_version" "jwt" {
  secret_id     = aws_secretsmanager_secret.jwt.id
  secret_string = random_password.jwt.result
}

# --- PostgreSQL

resource "aws_security_group" "postgres" {
  name        = "${local.name}-postgres"
  description = "PostgreSQL: only from services that own a schema"
  vpc_id      = module.network.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "postgres" {
  for_each                     = local.db_services
  security_group_id            = aws_security_group.postgres.id
  referenced_security_group_id = module.service[each.key].security_group_id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

resource "aws_db_subnet_group" "this" {
  name       = local.name
  subnet_ids = module.network.private_subnet_ids
}

resource "aws_db_parameter_group" "this" {
  name   = "${local.name}-postgres${var.postgres_engine_version}"
  family = "postgres${var.postgres_engine_version}"

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
  parameter {
    name         = "shared_preload_libraries"
    value        = "pg_stat_statements"
    apply_method = "pending-reboot"
  }
  # Same guard rails the services set per connection (application.yml): nothing waits or runs forever.
  parameter {
    name  = "idle_in_transaction_session_timeout"
    value = "10000"
  }
}

resource "aws_db_instance" "this" {
  identifier                   = local.name
  engine                       = "postgres"
  engine_version               = var.postgres_engine_version
  instance_class               = var.db_instance_class
  allocated_storage            = 50
  max_allocated_storage        = 200
  storage_type                 = "gp3"
  storage_encrypted            = true
  db_name                      = "stockforge"
  username                     = "stockforge_admin"
  manage_master_user_password  = true # rotated by RDS in Secrets Manager; used only for the bootstrap
  db_subnet_group_name         = aws_db_subnet_group.this.name
  vpc_security_group_ids       = [aws_security_group.postgres.id]
  parameter_group_name         = aws_db_parameter_group.this.name
  multi_az                     = var.db_multi_az
  publicly_accessible          = false
  backup_retention_period      = 7
  copy_tags_to_snapshot        = true
  deletion_protection          = var.deletion_protection
  skip_final_snapshot          = !var.deletion_protection
  final_snapshot_identifier    = var.deletion_protection ? "${local.name}-final" : null
  performance_insights_enabled = true
  auto_minor_version_upgrade   = true
}

# --- Redis (cache and rate-limit buckets; losing it is survivable, NFR-9)

resource "aws_security_group" "redis" {
  name        = "${local.name}-redis"
  description = "Redis: only from the gateway and the inventory service"
  vpc_id      = module.network.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "redis" {
  for_each                     = toset(["api-gateway", "inventory-service"])
  security_group_id            = aws_security_group.redis.id
  referenced_security_group_id = module.service[each.key].security_group_id
  from_port                    = 6379
  to_port                      = 6379
  ip_protocol                  = "tcp"
}

resource "aws_elasticache_subnet_group" "this" {
  name       = local.name
  subnet_ids = module.network.private_subnet_ids
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id       = local.name
  description                = "StockForge cache and rate limiting"
  engine                     = "redis"
  node_type                  = var.redis_node_type
  num_cache_clusters         = 2
  automatic_failover_enabled = true
  multi_az_enabled           = true
  subnet_group_name          = aws_elasticache_subnet_group.this.name
  security_group_ids         = [aws_security_group.redis.id]
  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
}

# --- Kafka (MSK): TLS between clients and brokers, two brokers in two AZs

resource "aws_security_group" "kafka" {
  name        = "${local.name}-kafka"
  description = "Kafka: only from services that produce or consume"
  vpc_id      = module.network.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "kafka" {
  for_each                     = toset(["inventory-service", "order-service", "payment-service", "notification-worker"])
  security_group_id            = aws_security_group.kafka.id
  referenced_security_group_id = module.service[each.key].security_group_id
  from_port                    = 9094
  to_port                      = 9094
  ip_protocol                  = "tcp"
}

resource "aws_msk_configuration" "this" {
  name           = local.name
  kafka_versions = [var.kafka_version]
  # Topics are declared by the services with explicit partition counts (spec §18), never created by accident.
  # With two brokers and replication factor 2, min.insync.replicas=1 keeps writes available when one broker is down;
  # three brokers with min.insync.replicas=2 is the production setting.
  server_properties = <<-EOT
    auto.create.topics.enable=false
    default.replication.factor=2
    min.insync.replicas=1
    unclean.leader.election.enable=false
  EOT
}

resource "aws_msk_cluster" "this" {
  cluster_name           = local.name
  kafka_version          = var.kafka_version
  number_of_broker_nodes = 2

  broker_node_group_info {
    instance_type   = var.kafka_instance_type
    client_subnets  = module.network.private_subnet_ids
    security_groups = [aws_security_group.kafka.id]
    storage_info {
      ebs_storage_info {
        volume_size = 50
      }
    }
  }

  configuration_info {
    arn      = aws_msk_configuration.this.arn
    revision = aws_msk_configuration.this.latest_revision
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }
}

# --- Public edge: the load balancer reaches only the gateway's API port

resource "aws_security_group" "alb" {
  name        = "${local.name}-alb"
  description = "Public load balancer"
  vpc_id      = module.network.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "alb" {
  for_each          = toset(var.certificate_arn == "" ? ["80"] : ["80", "443"])
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = tonumber(each.key)
  to_port           = tonumber(each.key)
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "alb" {
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = module.network.vpc_cidr_block
  ip_protocol       = "-1"
}

resource "aws_lb" "this" {
  name                       = local.name
  load_balancer_type         = "application"
  internal                   = false
  subnets                    = module.network.public_subnet_ids
  security_groups            = [aws_security_group.alb.id]
  drop_invalid_header_fields = true
}

resource "aws_lb_target_group" "gateway" {
  name                 = "${local.name}-gateway"
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = module.network.vpc_id
  deregistration_delay = 30 # matches the services' graceful-shutdown window

  health_check {
    # Readiness lives on the gateway's management port, which the ALB never routes public traffic to.
    port                = "8180"
    path                = "/actuator/health/readiness"
    matcher             = "200"
    interval            = 10
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener" "https" {
  count             = var.certificate_arn == "" ? 0 : 1
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.gateway.arn
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  # With a certificate, plain HTTP only redirects; without one (demo), it serves the API.
  dynamic "default_action" {
    for_each = var.certificate_arn == "" ? [] : [1]
    content {
      type = "redirect"
      redirect {
        port        = "443"
        protocol    = "HTTPS"
        status_code = "HTTP_301"
      }
    }
  }
  dynamic "default_action" {
    for_each = var.certificate_arn == "" ? [1] : []
    content {
      type             = "forward"
      target_group_arn = aws_lb_target_group.gateway.arn
    }
  }
}

# --- Services

module "service" {
  source   = "../../modules/ecs-service"
  for_each = local.service_specs

  name                           = each.key
  environment                    = var.environment
  region                         = var.region
  image                          = local.image[each.key]
  cluster_arn                    = aws_ecs_cluster.this.arn
  vpc_id                         = module.network.vpc_id
  subnet_ids                     = module.network.private_subnet_ids
  service_discovery_namespace_id = aws_service_discovery_private_dns_namespace.this.id
  http_port                      = each.value.http_port
  grpc_port                      = each.value.grpc_port
  management_port                = each.value.management_port
  desired_count                  = each.value.desired_count
  environment_variables          = merge(local.common_env, each.value.env)
  secrets                        = each.value.secrets
  allowed_callers                = each.value.callers
  target_group_arn               = each.key == "api-gateway" ? aws_lb_target_group.gateway.arn : null
}

locals {
  # Encrypted (the server also forces TLS). verify-full additionally needs the RDS CA bundle baked into the image.
  jdbc_url = "jdbc:postgresql://${aws_db_instance.this.address}:5432/stockforge?sslmode=require"

  db_env = {
    for name, db in local.db_services : name => {
      "${db.env_prefix}_DB_URL"      = local.jdbc_url
      "${db.env_prefix}_DB_USERNAME" = db.role
    }
  }
  db_secrets = {
    for name, db in local.db_services : name => {
      "${db.env_prefix}_DB_PASSWORD" = aws_secretsmanager_secret.db_role[name].arn
    }
  }

  # Security-group references between services use the module outputs, so they are wired in a second step below
  # (aws_vpc_security_group_ingress_rule.service_to_service) to avoid a dependency cycle between modules.
  service_specs = {
    api-gateway = {
      http_port = 8080, grpc_port = null, management_port = 8180, desired_count = 2
      env = {
        INVENTORY_GRPC_TARGET = "dns:///inventory-service.${local.discovery_domain}:9091"
        ORDER_GRPC_TARGET     = "dns:///order-service.${local.discovery_domain}:9092"
      }
      secrets = { GATEWAY_JWT_SECRET = aws_secretsmanager_secret.jwt.arn }
      callers = { alb = aws_security_group.alb.id }
    }
    inventory-service = {
      http_port = 8081, grpc_port = 9091, management_port = null, desired_count = 2
      env       = local.db_env["inventory-service"]
      secrets   = local.db_secrets["inventory-service"]
      callers   = {}
    }
    order-service = {
      http_port = 8082, grpc_port = 9092, management_port = null, desired_count = 2
      env = merge(local.db_env["order-service"], {
        INVENTORY_GRPC_TARGET = "dns:///inventory-service.${local.discovery_domain}:9091"
      })
      secrets = local.db_secrets["order-service"]
      callers = {}
    }
    payment-service = {
      http_port = 8083, grpc_port = null, management_port = null, desired_count = 2
      env       = local.db_env["payment-service"]
      secrets   = local.db_secrets["payment-service"]
      callers   = {}
    }
    notification-worker = {
      http_port = 8084, grpc_port = null, management_port = null, desired_count = 1
      env       = local.db_env["notification-worker"]
      secrets   = local.db_secrets["notification-worker"]
      callers   = {}
    }
  }

  # Who may call whose gRPC port: the gateway calls inventory and order; order calls inventory.
  grpc_calls = {
    "api-gateway-to-inventory" = { from = "api-gateway", to = "inventory-service", port = 9091 }
    "api-gateway-to-order"     = { from = "api-gateway", to = "order-service", port = 9092 }
    "order-to-inventory"       = { from = "order-service", to = "inventory-service", port = 9091 }
  }
}

resource "aws_vpc_security_group_ingress_rule" "service_to_service" {
  for_each                     = local.grpc_calls
  security_group_id            = module.service[each.value.to].security_group_id
  referenced_security_group_id = module.service[each.value.from].security_group_id
  from_port                    = each.value.port
  to_port                      = each.value.port
  ip_protocol                  = "tcp"
}
