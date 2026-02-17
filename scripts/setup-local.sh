#!/bin/bash

set -e

echo "=================================="
echo "Distributed Stream Platform Setup"
echo "=================================="
echo ""

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Check prerequisites
echo -e "${YELLOW}Checking prerequisites...${NC}"

command -v java >/dev/null 2>&1 || { echo -e "${RED}Java 17+ is required but not installed.${NC}" >&2; exit 1; }
command -v mvn >/dev/null 2>&1 || { echo -e "${RED}Maven is required but not installed.${NC}" >&2; exit 1; }
command -v docker >/dev/null 2>&1 || { echo -e "${RED}Docker is required but not installed.${NC}" >&2; exit 1; }
command -v docker-compose >/dev/null 2>&1 || { echo -e "${RED}Docker Compose is required but not installed.${NC}" >&2; exit 1; }

echo -e "${GREEN}✓ All prerequisites met${NC}"
echo ""

# Start infrastructure
echo -e "${YELLOW}Starting infrastructure services...${NC}"
cd infrastructure/docker
docker-compose up -d

echo -e "${GREEN}Waiting for services to be healthy...${NC}"
sleep 30

# Check if Kafka is ready
echo -e "${YELLOW}Checking Kafka readiness...${NC}"
until docker exec kafka kafka-broker-api-versions --bootstrap-server localhost:9092 > /dev/null 2>&1; do
  echo "Waiting for Kafka..."
  sleep 5
done
echo -e "${GREEN}✓ Kafka is ready${NC}"

# Create Kafka topics
echo -e "${YELLOW}Creating Kafka topics...${NC}"
docker exec kafka kafka-topics --create --if-not-exists --bootstrap-server localhost:9092 --topic events.raw --partitions 10 --replication-factor 1
docker exec kafka kafka-topics --create --if-not-exists --bootstrap-server localhost:9092 --topic events.user --partitions 5 --replication-factor 1
docker exec kafka kafka-topics --create --if-not-exists --bootstrap-server localhost:9092 --topic events.transaction --partitions 5 --replication-factor 1
docker exec kafka kafka-topics --create --if-not-exists --bootstrap-server localhost:9092 --topic events.analytics --partitions 5 --replication-factor 1
docker exec kafka kafka-topics --create --if-not-exists --bootstrap-server localhost:9092 --topic events.system --partitions 3 --replication-factor 1
docker exec kafka kafka-topics --create --if-not-exists --bootstrap-server localhost:9092 --topic events.critical --partitions 3 --replication-factor 1
docker exec kafka kafka-topics --create --if-not-exists --bootstrap-server localhost:9092 --topic events.dlq --partitions 1 --replication-factor 1
echo -e "${GREEN}✓ Kafka topics created${NC}"

# Build services
cd ../..
echo -e "${YELLOW}Building services...${NC}"
./mvnw clean install -DskipTests

echo -e "${GREEN}✓ Build complete${NC}"
echo ""

echo "=================================="
echo -e "${GREEN}Setup Complete!${NC}"
echo "=================================="
echo ""
echo "Services are now running:"
echo "  - Kafka UI:        http://localhost:8082"
echo "  - Prometheus:      http://localhost:9090"
echo "  - Grafana:         http://localhost:3001 (admin/admin)"
echo "  - Jaeger:          http://localhost:16686"
echo "  - PostgreSQL:      localhost:5432"
echo "  - Redis:           localhost:6379"
echo "  - MongoDB:         localhost:27017"
echo "  - Elasticsearch:   http://localhost:9200"
echo ""
echo "To start the services:"
echo "  ./scripts/start-services.sh"
echo ""
echo "To send test events:"
echo "  ./scripts/send-test-events.sh"
echo ""
