# EventFlow — Distributed Stream Processing Platform

[![AWS](https://img.shields.io/badge/AWS-%23FF9900.svg?style=for-the-badge&logo=amazon-aws&logoColor=white)](https://aws.amazon.com)
[![Java](https://img.shields.io/badge/Java-17-%23ED8B00.svg?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-%236DB33F.svg?style=for-the-badge&logo=spring&logoColor=white)](https://spring.io/projects/spring-boot)
[![React](https://img.shields.io/badge/React-18-%2361DAFB.svg?style=for-the-badge&logo=react&logoColor=white)](https://reactjs.org/)
[![Kafka](https://img.shields.io/badge/Apache%20Kafka-3.6-%23231F20.svg?style=for-the-badge&logo=apache-kafka&logoColor=white)](https://kafka.apache.org/)
[![Redis](https://img.shields.io/badge/Redis-7.4-%23DC382D.svg?style=for-the-badge&logo=redis&logoColor=white)](https://redis.io/)
[![Terraform](https://img.shields.io/badge/Terraform-1.6-%23844FBA.svg?style=for-the-badge&logo=terraform&logoColor=white)](https://www.terraform.io/)
[![Docker](https://img.shields.io/badge/Docker-24-%232496ED.svg?style=for-the-badge&logo=docker&logoColor=white)](https://www.docker.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

**Live Demo:** [https://frontend.d1w981rd1y5z53.amplifyapp.com](https://frontend.d1w981rd1y5z53.amplifyapp.com)

---

## Overview

EventFlow is a production-grade distributed stream processing platform demonstrating real-world distributed systems concepts: event sourcing, CQRS, exactly-once processing semantics, and cloud-native deployment on AWS.

Events are ingested via a REST API, deduplicated using Redis, routed to typed Kafka topics, and monitored through a live React dashboard with Prometheus and Grafana.

---

## Architecture

```
┌─────────────────────────────────────┐
│   React Dashboard (TypeScript)      │
│   Recharts · SSE · Polling          │
└──────────────┬──────────────────────┘
               │ HTTP / SSE
┌──────────────▼──────────────────────┐
│   Event Ingestion Service (Java 17) │
│   Spring Boot 3 · Kafka · Redis     │
│   POST /api/v1/events               │
│   GET  /api/v1/events/stats         │
│   GET  /api/v1/events/stats/stream  │
└──────────────┬──────────────────────┘
               │
  ┌────────────▼──────────────┐
  │   Apache Kafka            │
  │   events.raw              │
  │   events.user             │
  │   events.transaction      │
  │   events.analytics        │
  │   events.critical         │
  │   events.dlq              │
  └────────────┬──────────────┘
               │
┌──────────────▼──────────────────────┐
│   Storage Layer                     │
│   PostgreSQL · Redis · MongoDB      │
│   Elasticsearch                     │
└─────────────────────────────────────┘
```

---

## Project Structure

```
EventFlow/
├── amplify.yml                          # AWS Amplify CI/CD config
├── README.md
├── .gitignore
│
├── frontend/
│   └── dashboard/                       # React 18 + TypeScript dashboard
│       ├── src/
│       │   ├── App.tsx                  # Main dashboard component
│       │   ├── App.css                  # Design system & all styles
│       │   ├── main.tsx
│       │   └── index.css
│       ├── package.json
│       ├── vite.config.ts               # Dev proxy: /api → localhost:8081
│       └── tsconfig.json
│
├── services/
│   └── event-ingestion-service/         # Spring Boot 3 microservice
│       ├── pom.xml
│       └── src/main/java/com/platform/ingestion/
│           ├── EventIngestionApplication.java
│           ├── config/
│           │   ├── CorsConfig.java      # CORS — allows React at :3000
│           │   ├── KafkaConfig.java
│           │   ├── MetricsConfig.java   # Pre-registers Prometheus meters
│           │   └── RedisConfig.java
│           ├── controller/
│           │   └── EventController.java # REST + SSE endpoints
│           ├── exception/
│           │   └── GlobalExceptionHandler.java
│           ├── kafka/
│           │   └── EventProducer.java
│           ├── model/
│           │   └── Event.java
│           ├── service/
│           │   └── EventIngestionService.java
│           └── validation/
│               └── EventValidator.java
│
├── infrastructure/
│   ├── docker/
│   │   ├── docker-compose.yml           # Full local stack (10 services)
│   │   ├── init-db.sql                  # PostgreSQL schema
│   │   ├── event-ingestion.Dockerfile   # Production container
│   │   └── monitoring/prometheus/
│   │       └── prometheus.yml
│   └── terraform/                       # AWS IaC (ECS, RDS, MSK, VPC)
│       ├── main.tf
│       ├── variables.tf
│       └── terraform.tfvars
│
├── monitoring/
│   └── grafana/
│       ├── dashboards/                  # Auto-provisioned Grafana dashboards
│       │   ├── dashboard.yml
│       │   └── eventflow-dashboard.json
│       └── datasources/
│           └── prometheus.yml
│
└── scripts/
    ├── setup-local.sh                   # First-time setup
    ├── start-all.sh                     # Start everything
    ├── stop-all.sh                      # Stop everything
    └── send-test-events.sh             # Generate demo traffic
```

---

## Prerequisites

| Tool | Minimum version | Check |
|------|----------------|-------|
| Java | 17 | `java -version` |
| Docker + Docker Compose | 24 / v2 | `docker compose version` |
| Node.js | 18 | `node --version` |
| Maven | 3.8 (or use `./mvnw`) | `mvn -version` |

---

## Running Locally

### Option A — One-command start (recommended)

```bash
git clone https://github.com/joshuabvarghese/EventFlow.git
cd EventFlow

# First time only: start infrastructure and build the Java service
./scripts/setup-local.sh

# Start everything (infrastructure + Java service)
./scripts/start-all.sh
```

Then open a second terminal for the dashboard:

```bash
cd frontend/dashboard
npm install   # first time only
npm run dev
```

Visit **http://localhost:3000** — the dashboard connects to the API automatically.

### Option B — Step by step

```bash
# 1. Start Docker infrastructure (Kafka, Redis, PostgreSQL, etc.)
cd infrastructure/docker
docker compose up -d
cd ../..

# 2. Wait ~45 s, then create Kafka topics
docker exec kafka kafka-topics --create --if-not-exists \
  --bootstrap-server localhost:9092 --topic events.raw \
  --partitions 10 --replication-factor 1

# Repeat for: events.user events.transaction events.analytics
#             events.system events.critical events.dlq

# 3. Build and start the Java service
cd services/event-ingestion-service
mvn clean package -DskipTests
java -jar target/event-ingestion-service-*.jar
cd ../..

# 4. Start the React dashboard
cd frontend/dashboard
npm install && npm run dev
```

### Generate demo traffic

```bash
# Sends 100 events across all types with realistic payloads
./scripts/send-test-events.sh

# Configure volume and concurrency
EVENTS_TO_SEND=1000 CONCURRENCY=10 ./scripts/send-test-events.sh
```

### Stop everything

```bash
./scripts/stop-all.sh
```

---

## Service URLs (local)

| Service | URL | Credentials |
|---------|-----|-------------|
| React Dashboard | http://localhost:3000 | — |
| API (ingest) | http://localhost:8081/api/v1/events | — |
| API (stats) | http://localhost:8081/api/v1/events/stats | — |
| API (health) | http://localhost:8081/api/v1/events/health | — |
| Actuator / Prometheus | http://localhost:8081/actuator/prometheus | — |
| Kafka UI | http://localhost:8082 | — |
| Prometheus | http://localhost:9090 | — |
| Grafana | http://localhost:3001 | admin / admin |
| Jaeger | http://localhost:16686 | — |
| PostgreSQL | localhost:5432 | platform / platform123 |
| Redis | localhost:6379 | — |

---

## API Reference

### Ingest a single event

```bash
curl -X POST http://localhost:8081/api/v1/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventType": "user.login",
    "userId":    "user_001",
    "timestamp": "2026-02-25T10:30:00.000Z",
    "data": { "ip": "192.168.1.1", "device": "web" }
  }'
```

Response `202 Accepted`:
```json
{
  "eventId":       "b1c2d3...",
  "status":        "accepted",
  "message":       "Event queued for processing",
  "correlationId": "b1c2d3..."
}
```

### Ingest a batch (up to 1,000 events)

```bash
curl -X POST http://localhost:8081/api/v1/events/batch \
  -H "Content-Type: application/json" \
  -d '[{"eventType":"analytics.page.view","userId":"u1","timestamp":"..."}, ...]'
```

### Get live stats (polled by dashboard)

```bash
curl http://localhost:8081/api/v1/events/stats | jq
```

```json
{
  "totalReceived":  847293,
  "totalProcessed": 845801,
  "totalFailed":    312,
  "successRate":    99.96,
  "eventsByType": {
    "analytics.page.view": 621000,
    "user.login":          124000,
    "transaction.created":  56000,
    "user.signup":          14000
  },
  "uptimeSeconds": 3600
}
```

### SSE stats stream (real-time push, no polling)

```bash
curl -N http://localhost:8081/api/v1/events/stats/stream
```

---

## Valid Event Types

| Type | Required data fields |
|------|---------------------|
| `user.signup` | `email`, `source` |
| `user.login` | — |
| `user.logout` | — |
| `user.profile.updated` | — |
| `transaction.created` | `amount`, `currency` |
| `transaction.completed` | `amount`, `currency` |
| `transaction.failed` | `amount`, `currency` |
| `analytics.page.view` | — |
| `analytics.button.click` | — |
| `analytics.form.submit` | — |
| `system.error` | — |
| `system.warning` | — |
| `fraud.detected` | `riskScore`, `reason` |
| `security.breach.attempted` | `ip`, `reason` |

Any other type matching `category.subcategory` format is accepted and routed to `events.raw`.

---

## AWS Deployment

The platform is designed to run on AWS Free Tier:

| Component | AWS Service |
|-----------|-------------|
| React Dashboard | AWS Amplify (auto-deploy on push) |
| Java Service | Elastic Beanstalk |
| Kafka | Amazon MSK |
| PostgreSQL | Amazon RDS (t3.micro) |
| Redis | ElastiCache (t3.micro) |
| Container registry | Amazon ECR |

### Deploy frontend (Amplify)

Push to `main` — AWS Amplify picks up `amplify.yml` automatically and deploys.

### Deploy infrastructure (Terraform)

```bash
cd infrastructure/terraform

# Update terraform.tfvars with your AWS account details
terraform init
terraform plan
terraform apply
```

### Build and push Docker image

```bash
# Build
docker build -f infrastructure/docker/event-ingestion.Dockerfile \
  -t event-ingestion-service:latest .

# Tag and push to ECR
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin <account>.dkr.ecr.us-east-1.amazonaws.com

docker tag event-ingestion-service:latest \
  <account>.dkr.ecr.us-east-1.amazonaws.com/event-ingestion-service:latest

docker push <account>.dkr.ecr.us-east-1.amazonaws.com/event-ingestion-service:latest
```

---

## Key Design Decisions

**Exactly-once semantics** — Kafka producer configured with `enable.idempotence=true` and `acks=all`. Redis deduplication provides a 24-hour sliding window to reject replayed events.

**CORS** — `CorsConfig.java` explicitly allows `localhost:3000` (dev) and the Amplify URL (prod). Changing allowed origins requires only an `application.yml` edit, not code.

**Prometheus metrics** — `MetricsConfig.java` pre-registers all meters at startup so Grafana panels have data from the first request rather than showing "No data" until each counter is first incremented.

**Error consistency** — `GlobalExceptionHandler.java` ensures every API error returns `{status, error, message, details, timestamp}` regardless of which layer throws.

**Event routing** — Events are routed to typed Kafka topics by category prefix (`user.*` → `events.user`, etc.) with a fast path for critical events (`error.*`, `security.*`, `fraud.*` → `events.critical`).

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Frontend | React 18, TypeScript, Recharts, Lucide, Vite |
| Backend | Java 17, Spring Boot 3.2, Spring Kafka, Reactor |
| Messaging | Apache Kafka 3.6 |
| Cache / Dedup | Redis 7 |
| Storage | PostgreSQL 16, MongoDB 7, Elasticsearch 8 |
| Observability | Prometheus, Grafana, Jaeger (Micrometer Tracing) |
| Infrastructure | Docker Compose, Terraform 1.6, AWS |
| CI/CD | AWS Amplify |

---

## License

MIT — see [LICENSE](LICENSE) for details.
