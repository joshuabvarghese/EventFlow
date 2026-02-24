#!/bin/bash

set -e

echo "=================================="
echo "  EventFlow Platform — Local Setup"
echo "=================================="
echo ""

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

# ── Prerequisites ──────────────────────────────────────────────────────────────
echo -e "${YELLOW}Checking prerequisites...${NC}"

fail() { echo -e "${RED}✗ $1${NC}" >&2; exit 1; }

command -v java   >/dev/null 2>&1 || fail "Java 17+ is required but not installed."
command -v docker >/dev/null 2>&1 || fail "Docker is required but not installed."
command -v curl   >/dev/null 2>&1 || fail "curl is required but not installed."

# Accept either 'docker compose' (v2 plugin) or 'docker-compose' (v1 standalone)
if docker compose version >/dev/null 2>&1; then
  DOCKER_COMPOSE="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  DOCKER_COMPOSE="docker-compose"
else
  fail "Docker Compose is required but not installed."
fi

# Maven wrapper preferred; fall back to system mvn
if [ -f "./mvnw" ]; then
  MVN="./mvnw"
elif command -v mvn >/dev/null 2>&1; then
  MVN="mvn"
else
  fail "Maven (or ./mvnw) is required but not installed."
fi

echo -e "${GREEN}✓ All prerequisites met${NC}"
echo ""

# ── Create required directories ────────────────────────────────────────────────
mkdir -p logs
echo -e "${GREEN}✓ logs/ directory ready${NC}"

# ── Start infrastructure ───────────────────────────────────────────────────────
echo -e "${YELLOW}Starting infrastructure services (Kafka, Redis, PostgreSQL…)${NC}"
cd infrastructure/docker
$DOCKER_COMPOSE up -d

echo -e "${YELLOW}Waiting 45 s for services to initialise…${NC}"
sleep 45

# ── Wait for Kafka specifically ────────────────────────────────────────────────
echo -e "${YELLOW}Waiting for Kafka broker…${NC}"
for i in $(seq 1 12); do
  if docker exec kafka kafka-broker-api-versions --bootstrap-server localhost:9092 >/dev/null 2>&1; then
    echo -e "${GREEN}✓ Kafka is ready${NC}"
    break
  fi
  [ $i -eq 12 ] && { echo -e "${RED}✗ Kafka failed to start after 60 s. Check 'docker logs kafka'.${NC}"; exit 1; }
  echo "  Still waiting… ($((i * 5))s)"
  sleep 5
done

# ── Create Kafka topics ────────────────────────────────────────────────────────
echo -e "${YELLOW}Creating Kafka topics…${NC}"
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
    --replication-factor 1 >/dev/null 2>&1
  echo -e "  ${GREEN}✓${NC} $topic (${TOPICS[$topic]} partitions)"
done

# ── Build service ──────────────────────────────────────────────────────────────
cd ../..
echo ""
echo -e "${YELLOW}Building event-ingestion-service…${NC}"
cd services/event-ingestion-service
$MVN clean package -DskipTests -q
echo -e "${GREEN}✓ Build complete${NC}"
cd ../..

echo ""
echo "=================================="
echo -e "${GREEN}✓ Setup complete!${NC}"
echo "=================================="
echo ""
echo "Next steps:"
echo "  ./scripts/start-all.sh     — start the platform"
echo "  ./scripts/send-test-events.sh — send demo events"
echo ""
