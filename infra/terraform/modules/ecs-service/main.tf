# One StockForge service on ECS Fargate: task definition, service, log group, security group, Cloud Map registration
# (so other services reach it by DNS name) and, optionally, an ALB target group for the public gateway.

locals {
  container_name = var.name
  port_mappings = concat(
    [{ containerPort = var.http_port, protocol = "tcp", name = "http" }],
    var.grpc_port == null ? [] : [{ containerPort = var.grpc_port, protocol = "tcp", name = "grpc" }],
    var.management_port == null ? [] : [{ containerPort = var.management_port, protocol = "tcp", name = "management" }],
  )
  health_port = coalesce(var.management_port, var.http_port)
}

resource "aws_cloudwatch_log_group" "this" {
  name              = "/stockforge/${var.environment}/${var.name}"
  retention_in_days = var.log_retention_days
}

# --- IAM: the execution role pulls the image and reads secrets; the task role is what the application may do (nothing).

data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "${var.environment}-${var.name}-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "read_secrets" {
  count = length(var.secrets) > 0 ? 1 : 0
  statement {
    actions = ["secretsmanager:GetSecretValue"]
    # valueFrom may carry a ":json-key::" suffix; IAM needs the bare secret ARN (its first seven fields).
    resources = distinct([for arn in values(var.secrets) : join(":", slice(split(":", arn), 0, 7))])
  }
}

# Least privilege: each service can read only the secrets it is given.
resource "aws_iam_role_policy" "read_secrets" {
  count  = length(var.secrets) > 0 ? 1 : 0
  name   = "read-own-secrets"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.read_secrets[0].json
}

resource "aws_iam_role" "task" {
  name               = "${var.environment}-${var.name}-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

# --- Networking

resource "aws_security_group" "this" {
  name        = "${var.environment}-${var.name}"
  description = "${var.name}: inbound only from the callers allowed below"
  vpc_id      = var.vpc_id

  egress {
    description = "Data stores, Kafka, other services, AWS APIs"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_vpc_security_group_ingress_rule" "from_callers" {
  # Keys are caller names and port numbers, known at plan time even when the security groups are not created yet.
  for_each = {
    for pair in setproduct(keys(var.allowed_callers), [for p in local.port_mappings : p.containerPort]) :
    "${pair[0]}-${pair[1]}" => { source = var.allowed_callers[pair[0]], port = pair[1] }
  }
  security_group_id            = aws_security_group.this.id
  referenced_security_group_id = each.value.source
  from_port                    = each.value.port
  to_port                      = each.value.port
  ip_protocol                  = "tcp"
}

resource "aws_service_discovery_service" "this" {
  name = var.name
  dns_config {
    namespace_id   = var.service_discovery_namespace_id
    routing_policy = "MULTIVALUE"
    dns_records {
      type = "A"
      ttl  = 10
    }
  }
  health_check_custom_config {}
}

# --- Task and service

resource "aws_ecs_task_definition" "this" {
  family                   = "${var.environment}-${var.name}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([{
    name                   = local.container_name
    image                  = var.image
    essential              = true
    readonlyRootFilesystem = true
    user                   = "10001:10001"
    portMappings           = local.port_mappings
    environment            = [for k, v in var.environment_variables : { name = k, value = v }]
    secrets                = [for k, arn in var.secrets : { name = k, valueFrom = arn }]
    # Tomcat needs a writable /tmp; everything else is read-only.
    mountPoints = [{ sourceVolume = "tmp", containerPath = "/tmp", readOnly = false }]
    # SIGTERM starts Spring's graceful shutdown (20 s per phase); ECS waits this long before SIGKILL.
    stopTimeout = 30
    healthCheck = {
      command     = ["CMD", "curl", "-fsS", "http://localhost:${local.health_port}/actuator/health/liveness"]
      interval    = 10
      timeout     = 3
      retries     = 3
      startPeriod = 60
    }
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.this.name
        awslogs-region        = var.region
        awslogs-stream-prefix = var.name
      }
    }
  }])

  volume {
    name = "tmp"
  }
}

resource "aws_ecs_service" "this" {
  name                   = var.name
  cluster                = var.cluster_arn
  task_definition        = aws_ecs_task_definition.this.arn
  desired_count          = var.desired_count
  launch_type            = "FARGATE"
  enable_execute_command = false
  propagate_tags         = "SERVICE"

  # Rolling deploys: never fewer healthy tasks than desired; roll back automatically when new tasks fail to start.
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [aws_security_group.this.id]
    assign_public_ip = false
  }

  service_registries {
    registry_arn = aws_service_discovery_service.this.arn
  }

  dynamic "load_balancer" {
    for_each = var.target_group_arn == null ? [] : [var.target_group_arn]
    content {
      target_group_arn = load_balancer.value
      container_name   = local.container_name
      container_port   = var.http_port
    }
  }

  lifecycle {
    # Autoscaling owns the task count once the service exists; a Terraform run must not reset it.
    ignore_changes = [desired_count]
  }
}
