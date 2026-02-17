#!/bin/bash

set -e

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m'

echo ""
echo "╔════════════════════════════════════════════════════════════╗"
echo "║                                                            ║"
echo "║      DISTRIBUTED STREAM PLATFORM - QUICK START            ║"
echo "║                                                            ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

# Check prerequisites
echo -e "${BLUE}[1/6]${NC} Checking prerequisites..."

if ! command -v java &> /dev/null; then
    echo -e "${RED}✗ Java not found. Please install Java 17+${NC}"
    exit 1
fi

if ! command -v mvn &> /dev/null; then
    echo -e "${RED}✗ Maven not found. Please install Maven 3.8+${NC}"
    exit 1
fi

if ! command -v docker &> /dev/null; then
    echo -e "${RED}✗ Docker not found. Please install Docker${NC}"
    exit 1
fi

if ! command -v docker-compose &> /dev/null; then
    echo -e "${RED}✗ Docker Compose not found. Please install Docker Compose${NC}"
    exit 1
fi

echo -e "${GREEN}✓ All prerequisites met${NC}"
echo ""

# Start infrastructure
echo -e "${BLUE}[2/6]${NC} Starting infrastructure (Kafka, PostgreSQL, Redis, etc.)..."
cd infrastructure/docker
docker-compose up -d

echo -e "${YELLOW}⏳ Waiting for services to be ready (60 seconds)...${NC}"
sleep 60

# Verify services are up
echo -e "${GREEN}✓ Infrastructure started${NC}"
docker-compose ps
echo ""

# Create Kafka topics
echo -e "${BLUE}[3/6]${NC} Creating Kafka topics..."

topics=(
    "events.raw:10:1"
    "events.user:5:1"
    "events.transaction:5:1"
    "events.analytics:5:1"
    "events.system:3:1"
    "events.critical:3:1"
    "events.dlq:1:1"
)

for topic_config in "${topics[@]}"; do
    IFS=':' read -r topic partitions replication <<< "$topic_config"
    docker exec kafka kafka-topics --create --if-not-exists \
        --bootstrap-server localhost:9092 \
        --topic "$topic" \
        --partitions "$partitions" \
        --replication-factor "$replication" &> /dev/null || true
    echo -e "${GREEN}✓${NC} Created topic: $topic"
done

echo ""

# Build service
echo -e "${BLUE}[4/6]${NC} Building Event Ingestion Service..."
cd ../..
cd services/event-ingestion-service

if [ ! -f "target/event-ingestion-service-1.0.0-SNAPSHOT.jar" ]; then
    mvn clean package -DskipTests -q
    echo -e "${GREEN}✓ Build complete${NC}"
else
    echo -e "${YELLOW}⚠ Using existing JAR (delete target/ to rebuild)${NC}"
fi

echo ""

# Start the service in background
echo -e "${BLUE}[5/6]${NC} Starting Event Ingestion Service..."
cd ../..

# Kill any existing Java process on port 8081
lsof -ti:8081 | xargs kill -9 2>/dev/null || true

# Start service in background
nohup java -jar services/event-ingestion-service/target/event-ingestion-service-*.jar \
    > logs/event-ingestion.log 2>&1 &

SERVICE_PID=$!
echo $SERVICE_PID > .service.pid

echo -e "${YELLOW}⏳ Waiting for service to start...${NC}"
for i in {1..30}; do
    if curl -s http://localhost:8081/actuator/health > /dev/null 2>&1; then
        echo -e "${GREEN}✓ Service started (PID: $SERVICE_PID)${NC}"
        break
    fi
    sleep 2
    if [ $i -eq 30 ]; then
        echo -e "${RED}✗ Service failed to start. Check logs/event-ingestion.log${NC}"
        exit 1
    fi
done

echo ""

# Instructions for dashboard
echo -e "${BLUE}[6/6]${NC} To start the dashboard (optional):"
echo ""
echo "  cd frontend/dashboard"
echo "  npm install    # First time only"
echo "  npm run dev"
echo ""

echo "╔════════════════════════════════════════════════════════════╗"
echo "║                                                            ║"
echo "║                   ✓ PLATFORM READY!                        ║"
echo "║                                                            ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""
echo -e "${GREEN}Services Running:${NC}"
echo ""
echo "  📊 Event Ingestion API:  http://localhost:8081"
echo "  📈 Actuator Metrics:     http://localhost:8081/actuator"
echo "  🔍 Kafka UI:             http://localhost:8082"
echo "  📉 Grafana:              http://localhost:3001 (admin/admin)"
echo "  🔎 Jaeger Tracing:       http://localhost:16686"
echo "  🗄️  PostgreSQL:           localhost:5432"
echo "  🔴 Redis:                localhost:6379"
echo ""
echo -e "${GREEN}Quick Commands:${NC}"
echo ""
echo "  # Send 100 test events"
echo "  ./scripts/send-test-events.sh"
echo ""
echo "  # Check service health"
echo "  curl http://localhost:8081/api/v1/events/health | jq"
echo ""
echo "  # View statistics"
echo "  curl http://localhost:8081/api/v1/events/stats | jq"
echo ""
echo "  # Send a single event"
echo "  curl -X POST http://localhost:8081/api/v1/events \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"eventType\":\"user.signup\",\"userId\":\"demo_123\",\"timestamp\":\"2024-02-15T10:30:00.000Z\",\"data\":{}}'"
echo ""
echo "  # View service logs"
echo "  tail -f logs/event-ingestion.log"
echo ""
echo "  # Stop everything"
echo "  ./scripts/stop-all.sh"
echo ""
echo -e "${YELLOW}💡 Tip:${NC} Start with './scripts/send-test-events.sh' to see events flowing!"
echo ""
