# VivahGo Infrastructure

This module handles containerization and Kubernetes orchestration.

## Local Development
Use `docker-compose.yml` to spin up the local development environment:
- PostgreSQL (ACID Ledger)
- Redis (15-min Distributed Holds)
- Java Backend (SparkJava + jOOQ MCP Server)
- Python Agent (LangGraph AI Orchestrator)

## Kubernetes (Production)
The `k8s/` directory contains manifests for deploying to GCP:
- `deployment.yaml`: Defines pod specs for the backend and agent.
- `service.yaml`: Networking configuration.
- `configmap.yaml`: Environment variable configurations.
- `hpa.yaml`: Horizontal Pod Autoscaler targeting CPU thresholds to handle parallel workload bursts.
