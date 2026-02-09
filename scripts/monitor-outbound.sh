#!/usr/bin/env bash
# Polls the servicelinker-outbound PubSub subscription on the local emulator
# and prints any messages that arrive. Acknowledges messages after printing.
#
# Usage: ./scripts/monitor-outbound.sh

EMULATOR_HOST="${PUBSUB_EMULATOR_HOST:-localhost:8085}"
PROJECT="local-project"
SUBSCRIPTION="servicelinker-outbound-sub"
POLL_INTERVAL=2

echo "Monitoring subscription $SUBSCRIPTION on $EMULATOR_HOST (poll every ${POLL_INTERVAL}s)..."
echo "Press Ctrl+C to stop."
echo ""

while true; do
  RESPONSE=$(curl -s -X POST \
    "http://$EMULATOR_HOST/v1/projects/$PROJECT/subscriptions/$SUBSCRIPTION:pull" \
    -H "Content-Type: application/json" \
    -d '{"maxMessages": 10}')

  if echo "$RESPONSE" | grep -q "receivedMessages"; then
    echo "=== $(date '+%H:%M:%S') Outbound message received ==="
    echo "$RESPONSE" | python3 -m json.tool

    ACK_IDS=$(echo "$RESPONSE" | python3 -c "
import sys, json
msgs = json.load(sys.stdin).get('receivedMessages', [])
print(json.dumps([m['ackId'] for m in msgs]))
")
    curl -s -X POST \
      "http://$EMULATOR_HOST/v1/projects/$PROJECT/subscriptions/$SUBSCRIPTION:acknowledge" \
      -H "Content-Type: application/json" \
      -d "{\"ackIds\": $ACK_IDS}" > /dev/null

    echo ""
  fi

  sleep "$POLL_INTERVAL"
done
