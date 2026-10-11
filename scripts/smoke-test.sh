#!/usr/bin/env bash
# End-to-end check against a running stack: create a conversation, send over a socket, read it back.
# Needs curl, jq and websocat.
set -euo pipefail

GATEWAY="${GATEWAY:-localhost:8081}"
MESSAGES="${MESSAGES:-localhost:8082}"

echo "1. Minting tokens"
ALICE=$(curl -fsS "http://$GATEWAY/dev/token?userId=alice" | jq -r .token)
BOB=$(curl -fsS "http://$GATEWAY/dev/token?userId=bob" | jq -r .token)

echo "2. Creating the conversation"
CONVERSATION=$(curl -fsS -X POST "http://$MESSAGES/api/conversations" \
  -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' \
  -d '{"type":"DIRECT","members":["bob"]}' | jq -r .conversationId)
echo "   $CONVERSATION"

echo "3. Sending over the socket"
printf '{"type":"send","conversationId":"%s","body":"smoke test","sentAt":%s}\n' \
  "$CONVERSATION" "$(date +%s000)" \
  | websocat -n1 "ws://$GATEWAY/ws?token=$ALICE&deviceId=smoke"

sleep 1

echo "4. Reading it back as bob"
curl -fsS "http://$MESSAGES/api/conversations/$CONVERSATION/messages?limit=5" \
  -H "Authorization: Bearer $BOB" | jq '.messages[] | {serverSeq, senderId, body}'

echo
echo "Passed."
