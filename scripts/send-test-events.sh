#!/bin/bash

# Script to send test events to the platform for demo purposes

set -e

API_URL="${API_URL:-http://localhost:8081}"
EVENTS_TO_SEND="${EVENTS_TO_SEND:-100}"

echo "=========================================="
echo "  Sending Test Events to Platform"
echo "=========================================="
echo "API URL: $API_URL"
echo "Number of events: $EVENTS_TO_SEND"
echo ""

# Color codes
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Array of event types
EVENT_TYPES=(
    "user.signup"
    "user.login"
    "user.logout"
    "transaction.created"
    "transaction.completed"
    "analytics.page.view"
    "analytics.button.click"
)

# Array of user IDs for demo
USER_IDS=(
    "user_001"
    "user_002"
    "user_003"
    "user_004"
    "user_005"
)

# Function to generate random event
generate_event() {
    local event_type=${EVENT_TYPES[$RANDOM % ${#EVENT_TYPES[@]}]}
    local user_id=${USER_IDS[$RANDOM % ${#USER_IDS[@]}]}
    local timestamp=$(date -u +"%Y-%m-%dT%H:%M:%S.000Z")
    
    cat <<EOF
{
  "eventType": "$event_type",
  "userId": "$user_id",
  "timestamp": "$timestamp",
  "data": {
    "source": "test-script",
    "randomValue": $RANDOM,
    "testId": "test_$(date +%s)"
  }
}
EOF
}

# Send events
success_count=0
failure_count=0

for i in $(seq 1 $EVENTS_TO_SEND); do
    event=$(generate_event)
    
    response=$(curl -s -w "\n%{http_code}" -X POST "$API_URL/api/v1/events" \
        -H "Content-Type: application/json" \
        -d "$event")
    
    http_code=$(echo "$response" | tail -n1)
    
    if [ "$http_code" -eq 202 ]; then
        ((success_count++))
        echo -ne "${GREEN}✓${NC} Sent $i/$EVENTS_TO_SEND events (Success: $success_count, Failed: $failure_count)\r"
    else
        ((failure_count++))
        echo -ne "${RED}✗${NC} Sent $i/$EVENTS_TO_SEND events (Success: $success_count, Failed: $failure_count)\r"
    fi
    
    # Small delay to avoid overwhelming the system
    sleep 0.1
done

echo ""
echo ""
echo "=========================================="
echo "  Test Complete!"
echo "=========================================="
echo -e "${GREEN}Successfully sent: $success_count${NC}"
echo -e "${RED}Failed: $failure_count${NC}"
echo ""
echo "View the dashboard at: http://localhost:3000"
echo "Check metrics at: http://localhost:8081/actuator/metrics"
echo "View Grafana at: http://localhost:3001 (admin/admin)"
echo "Check Kafka UI at: http://localhost:8082"
echo ""

# Display statistics
if [ "$failure_count" -eq 0 ]; then
    echo -e "${GREEN}✓ All events sent successfully!${NC}"
else
    echo -e "${YELLOW}⚠ Some events failed. Check the logs for details.${NC}"
fi
