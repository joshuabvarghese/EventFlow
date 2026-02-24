#!/bin/bash

# Send test events to the EventFlow platform.
# Supports concurrent sending for throughput testing.

set -e

API_URL="${API_URL:-http://localhost:8081}"
EVENTS_TO_SEND="${EVENTS_TO_SEND:-100}"
CONCURRENCY="${CONCURRENCY:-5}"        # parallel workers
DELAY="${DELAY:-0.05}"                 # seconds between events per worker

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'

echo ""
echo -e "${BLUE}EventFlow — Test Event Generator${NC}"
echo "API:         $API_URL"
echo "Events:      $EVENTS_TO_SEND"
echo "Concurrency: $CONCURRENCY workers"
echo ""

# ── Check service is up ────────────────────────────────────────────────────────
if ! curl -sf "$API_URL/actuator/health" >/dev/null 2>&1; then
  echo -e "${RED}✗ Service is not reachable at $API_URL${NC}"
  echo "  Start it first: ./scripts/start-all.sh"
  exit 1
fi
echo -e "${GREEN}✓ Service is healthy${NC}"
echo ""

# ── Event type distribution (weighted, realistic) ─────────────────────────────
EVENT_TYPES=(
  "analytics.page.view"      "analytics.page.view"    "analytics.page.view"
  "analytics.page.view"      "analytics.page.view"    # 5× — most common
  "user.login"               "user.login"             "user.login"   # 3×
  "analytics.button.click"   "analytics.button.click" # 2×
  "transaction.created"      "transaction.created"    # 2×
  "user.signup"
  "user.logout"
  "transaction.completed"
  "transaction.failed"
  "system.warning"
)

USER_IDS=("user_001" "user_002" "user_003" "user_004" "user_005"
          "user_006" "user_007" "user_008" "user_009" "user_010")

success_count=0
failure_count=0

generate_event() {
  local event_type="${EVENT_TYPES[$((RANDOM % ${#EVENT_TYPES[@]}))]}"
  local user_id="${USER_IDS[$((RANDOM % ${#USER_IDS[@]}))]}"
  local event_id="evt_$(date +%s%N)_$RANDOM"
  local timestamp
  timestamp=$(date -u +"%Y-%m-%dT%H:%M:%S.000Z")

  # Build data payload based on event type
  local data="{}"
  case "$event_type" in
    "user.signup")
      data="{\"email\":\"${user_id}@example.com\",\"source\":\"web\",\"plan\":\"free\"}"
      ;;
    "transaction.created"|"transaction.completed")
      local amount=$((RANDOM % 9900 + 100))
      data="{\"amount\":$amount,\"currency\":\"USD\",\"transactionId\":\"txn_$RANDOM\"}"
      ;;
    "transaction.failed")
      data="{\"amount\":$((RANDOM % 500 + 50)),\"currency\":\"USD\",\"reason\":\"insufficient_funds\"}"
      ;;
    "analytics.page.view")
      local pages=("/dashboard" "/events" "/analytics" "/settings" "/docs")
      local page="${pages[$((RANDOM % ${#pages[@]}))]}"
      data="{\"page\":\"$page\",\"referrer\":\"direct\",\"durationMs\":$((RANDOM % 5000 + 500))}"
      ;;
    "analytics.button.click")
      data="{\"element\":\"ingest-btn\",\"page\":\"/dashboard\",\"sessionId\":\"sess_$RANDOM\"}"
      ;;
  esac

  cat <<JSON
{
  "eventId":   "$event_id",
  "eventType": "$event_type",
  "userId":    "$user_id",
  "timestamp": "$timestamp",
  "source":    "test-script",
  "version":   "1.0",
  "data":      $data
}
JSON
}

send_event() {
  local event
  event=$(generate_event)
  local http_code
  http_code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$API_URL/api/v1/events" \
    -H "Content-Type: application/json" \
    -d "$event")
  echo "$http_code"
}

# ── Send loop ──────────────────────────────────────────────────────────────────
echo -e "${YELLOW}Sending $EVENTS_TO_SEND events (concurrency=$CONCURRENCY)…${NC}"
echo ""

START_TIME=$(date +%s)
tmp_dir=$(mktemp -d)

worker() {
  local worker_id=$1
  local count=$2
  local ok=0
  local fail=0
  for _ in $(seq 1 "$count"); do
    code=$(send_event)
    if [ "$code" = "202" ]; then ((ok++)); else ((fail++)); fi
    sleep "$DELAY"
  done
  echo "$ok $fail" > "$tmp_dir/worker_$worker_id"
}

per_worker=$(( EVENTS_TO_SEND / CONCURRENCY ))
remainder=$(( EVENTS_TO_SEND % CONCURRENCY ))

for i in $(seq 1 "$CONCURRENCY"); do
  count=$per_worker
  [ $i -eq "$CONCURRENCY" ] && count=$((per_worker + remainder))
  worker "$i" "$count" &
done

# Progress indicator
total_sent=0
while [ $total_sent -lt "$EVENTS_TO_SEND" ]; do
  completed=$(ls "$tmp_dir" 2>/dev/null | wc -l)
  total_sent=$(( completed * per_worker ))
  [ $total_sent -gt "$EVENTS_TO_SEND" ] && total_sent=$EVENTS_TO_SEND
  printf "\r  Progress: %d / %d events sent" "$total_sent" "$EVENTS_TO_SEND"
  sleep 1
done

wait  # wait for all workers

# Tally results
for f in "$tmp_dir"/worker_*; do
  read -r ok fail < "$f"
  success_count=$((success_count + ok))
  failure_count=$((failure_count + fail))
done
rm -rf "$tmp_dir"

END_TIME=$(date +%s)
ELAPSED=$((END_TIME - START_TIME))
ELAPSED=${ELAPSED:-1}
THROUGHPUT=$(( EVENTS_TO_SEND / ELAPSED ))

echo ""
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "${GREEN}✓ Successfully sent: $success_count${NC}"
[ $failure_count -gt 0 ] && echo -e "${RED}✗ Failed:            $failure_count${NC}"
echo "  Time elapsed:      ${ELAPSED}s"
echo "  Throughput:        ~${THROUGHPUT} events/s"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "View live stats:  curl $API_URL/api/v1/events/stats | jq"
echo "Dashboard:        http://localhost:3000"
echo "Grafana:          http://localhost:3001"
echo ""
