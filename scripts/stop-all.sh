#!/bin/bash

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo ""
echo "Stopping Distributed Stream Platform..."
echo ""

# Stop Java service
if [ -f ".service.pid" ]; then
    PID=$(cat .service.pid)
    if ps -p $PID > /dev/null 2>&1; then
        echo -e "${YELLOW}Stopping Event Ingestion Service (PID: $PID)...${NC}"
        kill $PID
        sleep 2
        if ps -p $PID > /dev/null 2>&1; then
            kill -9 $PID
        fi
        echo -e "${GREEN}✓ Service stopped${NC}"
    fi
    rm .service.pid
else
    # Try to kill by port
    PID=$(lsof -ti:8081 2>/dev/null)
    if [ ! -z "$PID" ]; then
        echo -e "${YELLOW}Stopping service on port 8081 (PID: $PID)...${NC}"
        kill -9 $PID
        echo -e "${GREEN}✓ Service stopped${NC}"
    fi
fi

# Stop infrastructure
echo -e "${YELLOW}Stopping infrastructure (Kafka, PostgreSQL, Redis, etc.)...${NC}"
cd infrastructure/docker
docker-compose down

echo ""
echo -e "${GREEN}✓ All services stopped${NC}"
echo ""
echo "To completely remove all data:"
echo "  cd infrastructure/docker && docker-compose down -v"
echo ""
