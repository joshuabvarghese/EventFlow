#!/bin/bash

set -e

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m'

echo ""
echo "╔══════════════════════════════════════════════════════════╗"
echo "║           EventFlow — Distributed Stream Platform        ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""

# ── Prerequisites ──────────────────────────────────────────────────────────────
echo -e "${BLUE}[1/6]${NC} Checking prerequisites…"

fail() { echo -e "${RED}✗ $1${NC}"; exit 1; }

command -v java   >/dev/null 2>&1 || fail "Java 17+ required. Install from https://adoptium.net"
command -v docker >/dev/null 2>&1 || fail "Docker required. Install from https://docker.com"

if docker compose version >/dev/null 2>&1; then
  DOCKER_COMPOSE="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  DOCKER_COMPOSE="docker-compose"
else
  fail "Docker Compose required."
fi

if [ -f "./mvnw" ]; then
  MVN="./mvnw"
elif command -v mvn >/dev/null 2>&1; then
  MVN="mvn"
else
  fail "Maven (or ./mvnw) required."
fi

echo -e "${GREEN}✓ Prerequisites OK (Docker Compose: $DOCKER_COMPOSE, Maven: $MVN)${NC}"
echo ""

# ── Ensure logs directory exists ───────────────────────────────────────────────
mkdir -p logs

# ── Infrastructure ─────────────────────────────────────────────────────────────
echo -e "${BLUE}[2/6]${NC} Starting infrastructure (Kafka, PostgreSQL, Redis, Elasticsearch…)"
cd infrastructure/docker
$DOCKER_COMPOSE up -d
cd ../..

echo -e "${YELLOW}  Waiting 60 s for services to be healthy…${NC}"
sleep 60
echo -e "${GREEN}✓ Infrastructure started${NC}"
echo ""

# ── Kafka topics ───────────────────────────────────────────────────────────────
echo -e "${BLUE}[3/6]${NC} Creating Kafka topics…"

declare -A TOPICS=(
  ["events.raw"]="10"
  ["events.user"]="5"
  ["events.transaction"]="5"
  ["events.analytics"]="5"
  ["events.system"]="3"
  ["events.critical"]="3"
  ["events.dlq"]="1"
)

for topic in "${!TOPICS[@]}"; do
  docker exec kafka kafka-topics --create --if-not-exists \
    --bootstrap-server localhost:9092 \
    --topic "$topic" \
    --partitions "${TOPICS[$topic]}" \
    --replication-factor 1 >/dev/null 2>&1 || true
  echo -e "  ${GREEN}✓${NC} $topic"
done
echo ""

# ── Build ──────────────────────────────────────────────────────────────────────
echo -e "${BLUE}[4/6]${NC} Building event-ingestion-service…"
cd services/event-ingestion-service

JAR_PATH="target/event-ingestion-service-1.0.0-SNAPSHOT.jar"
if [ -f "$JAR_PATH" ]; then
  echo -e "${YELLOW}  Using existing JAR (delete target/ to force rebuild)${NC}"
else
  $MVN clean package -DskipTests -q
  echo -e "${GREEN}✓ Build complete${NC}"
fi
cd ../..
echo ""

# ── Stop any running instance ──────────────────────────────────────────────────
echo -e "${BLUE}[5/6]${NC} Starting Event Ingestion Service…"
if [ -f ".service.pid" ]; then
  OLD_PID=$(cat .service.pid)
  if ps -p "$OLD_PID" >/dev/null 2>&1; then
    echo -e "${YELLOW}  Stopping previous instance (PID $OLD_PID)…${NC}"
    kill "$OLD_PID" 2>/dev/null || true
    sleep 2
  fi
  rm -f .service.pid
fi
lsof -ti:8081 | xargs kill -9 2>/dev/null || true

nohup java \
  -Xms256m -Xmx512m \
  -Dspring.profiles.active=local \
  -jar services/event-ingestion-service/target/event-ingestion-service-*.jar \
  > logs/event-ingestion.log 2>&1 &

SERVICE_PID=$!
echo $SERVICE_PID > .service.pid

echo -e "${YELLOW}  Waiting for service to start (PID $SERVICE_PID)…${NC}"
for i in $(seq 1 30); do
  if curl -sf http://localhost:8081/actuator/health >/dev/null 2>&1; then
    echo -e "${GREEN}✓ Service is healthy${NC}"
    break
  fi
  sleep 2
  if [ $i -eq 30 ]; then
    echo -e "${RED}✗ Service failed to start after 60 s.${NC}"
    echo -e "${RED}  Check: tail -50 logs/event-ingestion.log${NC}"
    exit 1
  fi
done
echo ""

# ── Dashboard instructions ─────────────────────────────────────────────────────
echo -e "${BLUE}[6/6]${NC} Start the React dashboard (separate terminal):"
echo ""
echo "  cd frontend/dashboard"
echo "  npm install   # first time only"
echo "  npm run dev"
echo ""

echo "╔══════════════════════════════════════════════════════════╗"
echo "║                   ✓  PLATFORM READY                     ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""
echo -e "${GREEN}Endpoints:${NC}"
echo "  API:           http://localhost:8081/api/v1/events"
echo "  Stats:         http://localhost:8081/api/v1/events/stats"
echo "  Health:        http://localhost:8081/api/v1/events/health"
echo "  Prometheus:    http://localhost:8081/actuator/prometheus"
echo "  Kafka UI:      http://localhost:8082"
echo "  Grafana:       http://localhost:3001  (admin / admin)"
echo "  Jaeger:        http://localhost:16686"
echo ""
echo -e "${GREEN}Quick commands:${NC}"
echo "  ./scripts/send-test-events.sh          # send 100 demo events"
echo "  curl http://localhost:8081/api/v1/events/stats | jq"
echo "  tail -f logs/event-ingestion.log"
echo "  ./scripts/stop-all.sh"
echo ""
