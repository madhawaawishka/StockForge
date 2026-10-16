terraform {
  required_version = ">= 1.9.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # Remote state with locking for anything shared. Kept commented so `terraform init` works without an AWS account:
  # backend "s3" {
  #   bucket       = "stockforge-terraform-state"
  #   key          = "dev/terraform.tfstate"
  #   region       = "eu-west-1"
  #   encrypt      = true
  #   use_lockfile = true
  # }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "stockforge"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
