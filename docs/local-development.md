# Running it locally

## With Docker

```bash
make up
```

Brings up Redis, a single-node Kafka in KRaft mode, two gateway nodes, message-service and
notification-service. First build takes a few minutes while Maven resolves.

Open the console on **http://localhost:8081** and, in a second window, **http://localhost:8091**. Those
are two different gateway processes. Connect `alice` on one and `bob` on the other, then send a message:
it leaves gateway 1, goes through Kafka, is stored and sequenced, and is routed back out through gateway
2's Redis channel. That cross-node hop is the part worth watching, and it is why the compose file runs
two gateways instead of one.

To see the offline path, disconnect `bob` and send to him anyway:

```bash
docker compose logs -f notification-service
```

Within 30 seconds of the disconnect his presence key expires and the message takes the push branch. The
logging push sender prints what APNs or FCM would have been sent.

## Without Docker

Needs Java 17, Maven, and a local Redis and Kafka.

```bash
mvn clean package
java -jar message-service/target/message-service-1.0.0.jar
java -jar notification-service/target/notification-service-1.0.0.jar
NODE_ID=gw-1 java -jar gateway-service/target/gateway-service-1.0.0.jar
NODE_ID=gw-2 PORT=8091 java -jar gateway-service/target/gateway-service-1.0.0.jar
```

Each gateway needs a distinct `NODE_ID`; it is the address notification-service routes to.

## Against real Cosmos DB

```bash
export CHAT_STORE=cosmos
export COSMOS_ENDPOINT=https://your-account.documents.azure.com:443/
export COSMOS_KEY=...
export COSMOS_REGIONS="East US,West US 2"
```

Containers are created on startup if missing, with `/conversationId` as the partition key and autoscale
throughput. The Cosmos emulator works too, though its TLS certificate has to be trusted by the JVM first.

## Tests

```bash
mvn test
```

Unit tests only, no containers required. They cover the parts where a subtle bug would be expensive and
invisible: token verification and tampering, multi-device delivery and the last-socket-closed signal,
rate limiting, store ordering and pagination, schema evolution of the wire format, and the push preview
and badge rules.

## Load

```bash
k6 run --vus 200 --duration 60s scripts/load-test.js
```

Reports p95 and p99 from pressing send to the message arriving back sequenced. On a laptop with the full
compose stack, expect a p95 in the low tens of milliseconds at a couple of hundred virtual users; the
thresholds in the script are set deliberately loose so a busy machine does not produce false failures.

## API

```bash
TOKEN=$(curl -s "localhost:8081/dev/token?userId=alice" | jq -r .token)

# Create a group
curl -X POST localhost:8082/api/conversations \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"GROUP","members":["bob","carol"],"title":"Platform team"}'

# Newest page, served from cache
curl "localhost:8082/api/conversations/dm:alice:bob/messages?limit=50" \
  -H "Authorization: Bearer $TOKEN"

# Scroll back, served from the store
curl "localhost:8082/api/conversations/dm:alice:bob/messages?beforeSeq=1400&limit=50" \
  -H "Authorization: Bearer $TOKEN"
```

Paging walks backwards with `beforeSeq` rather than an offset, so messages arriving mid-scroll cannot
shift the page boundaries underneath the reader.

There is also `scripts/smoke-test.sh`, which creates a conversation, sends over a socket and reads it
back (needs `curl`, `jq` and `websocat`).
