# StockForge on AWS — Terraform reference deployment

A reference deployment of the same five container images that run in `docker-compose.yml` (spec §34, Tier 3).
**It has been validated (`terraform fmt`, `terraform validate`) but never applied** — running it creates billable
resources. Treat it as a reviewed design in code, not as a tested production environment.

```
                    ┌──────────────── VPC 10.40.0.0/16, two AZs ────────────────┐
internet ──HTTPS──▶ │ ALB (public subnets) ──▶ api-gateway :8080   (health :8180)│
                    │                              │ gRPC (Cloud Map DNS)        │
                    │                 inventory-service :9091 ◀── order-service   │
                    │ private subnets:   │ TLS        │ TLS          │ TLS        │
                    │   RDS PostgreSQL ◀─┘   ElastiCache Redis   MSK Kafka ◀─ payment, notification
                    └────────────────────────────────────────────────────────────┘
```

| Layout | |
| :--- | :--- |
| `modules/network` | VPC, public/private subnets in 2–3 AZs, NAT, VPC flow logs |
| `modules/ecs-service` | one Fargate service: task definition (read-only root FS, non-root user, secrets from Secrets Manager), security group, Cloud Map name, optional ALB target |
| `environments/dev` | ECR, ECS cluster, RDS, ElastiCache, MSK, ALB, secrets, and the five services |

## Design choices
- **Same artefact everywhere.** ECS runs the images CI builds from the repository's `Dockerfile`; configuration is
  environment variables only (12-factor), exactly as in compose.
- **One public entry point.** Only the ALB is internet-facing and it forwards only to the gateway's API port. The
  gateway's actuator (management port 8180) is reachable for ALB health checks but never routed publicly.
- **Least-privilege network.** Every security group admits named callers only: the ALB → gateway; gateway → inventory
  and order gRPC; order → inventory gRPC; schema owners → PostgreSQL; producers/consumers → Kafka; gateway and
  inventory → Redis.
- **Secrets never in code or state files you share.** Database role passwords and the JWT key are generated, stored
  in Secrets Manager and injected by ECS; each task's execution role may read only its own secrets. The RDS admin
  password is managed and rotated by RDS. (Terraform state still contains generated values — keep state in an
  encrypted, access-controlled S3 bucket, see `versions.tf`.)
- **Encryption in transit and at rest** for PostgreSQL (`rds.force_ssl`), Redis and Kafka (TLS client–broker and
  inter-broker); ECR images scanned on push with immutable tags.
- **Safe rollouts.** ECS rolling deployments keep 100 % healthy capacity, roll back automatically when new tasks fail
  (deployment circuit breaker), and give each task 30 s to shut down gracefully — the same window the services use.

## First deployment
1. Create an S3 state bucket and enable the `backend "s3"` block in `environments/dev/versions.tf`.
2. `cp terraform.tfvars.example terraform.tfvars` and set `image_tag` (and `certificate_arn` for HTTPS).
3. `terraform init && terraform plan -out plan && terraform apply plan`.
4. Push the images: `docker build --build-arg SERVICE=<name> -t <ecr-url>:<tag> . && docker push <ecr-url>:<tag>`.
5. **Bootstrap the database once** (roles and schemas, ADR-002): from a host inside the VPC (SSM session or a one-off
   ECS task), run `database/init/01-roles-and-schemas.sql` as the RDS admin user, replacing each `*_local_only`
   password with the value from that service's Secrets Manager secret. Flyway then creates each service's tables on
   start-up.

## Cost (eu-west-1, on-demand, rough)
Roughly USD 350–450 per month while running: MSK (2 × kafka.t3.small) and NAT are the largest items, then RDS
db.t4g.medium, two cache.t4g.small nodes, nine Fargate tasks and the ALB. `terraform destroy` removes everything
(enable `deletion_protection` for anything holding real data).

## Deliberately left out
- Observability backend: the services export OTLP traces and Prometheus metrics; on AWS this would be the ADOT
  collector as a sidecar with Amazon Managed Prometheus/Grafana and X-Ray, or Grafana Cloud.
- Autoscaling policies, WAF in front of the ALB, mTLS between services, and a CI/CD role (GitHub OIDC) for
  `terraform apply` and image pushes.
- Full certificate verification to RDS (`sslmode=verify-full` needs the RDS CA bundle in the image).
