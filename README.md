# Real-time chat system

A Teams-style messaging backend: 1:1 and group chat over WebSockets, sized for 50 million daily users and
about 70,000 messages a second at peak.

Java 17 · Spring Boot 3 · WebSockets · Kafka · Redis · Azure Cosmos DB

```bash
make up   # then open http://localhost:8081 and http://localhost:8091
```

Two gateway nodes come up on those ports. Connect `alice` on one and `bob` on the other and send a
message — it crosses the node boundary through Kafka and Redis on its way back, which is the part of the
design actually worth looking at.

---

## The problem

Two requirements pull against each other. A message has to feel instant, which argues for pushing it
straight from sender to recipient. A message must never be lost, which argues for writing it down before
telling anyone. The design is an attempt to get both: accept fast, persist before anyone is notified, and
treat delivery as retryable rather than something that must succeed on the first attempt.

## How a message travels

```
client ──WebSocket──▶ gateway ──Kafka(key=conversation)──▶ message-service
                                                               │
                                        ┌──────────────────────┼─────────────────────┐
                                        ▼                      ▼                     ▼
                                  Cosmos DB             Redis cache          Kafka(key=recipient)
                              PK=conversationId        last 50 msgs                  │
                                                                                     ▼
                                                                           notification-service
                                                                            online? ──┬── yes ──▶ Redis
                                                                                      │           channel
                                                                                      │           gw:{node}
                                                                                      │              │
                                                                                      no             ▼
                                                                                      │          gateway
                                                                                      ▼              │
                                                                                 APNs / FCM          ▼
                                                                                                  client
```

1. The client generates a UUID for the message. That id is the idempotency key for the entire pipeline,
   which is what makes a resend after a dropped connection safe.
2. The gateway authenticates against the handshake identity, rate-limits, and produces to Kafka keyed by
   conversation id. It never touches the database.
3. The client is told **queued**, not stored, and keeps the message greyed out in its outbox until it
   comes back with a sequence number. That is why the sender's own devices are included in fan-out.
4. message-service assigns the sequence number, upserts to Cosmos, writes the cache, and only then fans
   out one event per member.
5. notification-service checks presence: live socket, or push.

## Four decisions worth defending

**Gateways hold no state.** Sockets and nothing else — no database access, no business logic. That
constraint makes the whole fleet disposable: kill a node mid-conversation and the only loss is a set of
connections clients re-establish in a second.

**Kafka sits between accept and write.** It decouples the rate clients send at from the rate the
database can absorb, so a traffic spike becomes queue depth instead of failed sends. More importantly it
turns recovery into a replay: a crashed consumer, a rolled-back deploy and a region that went read-only
all have the same procedure, which is to read from the last committed offset. Durability lives in the
log, where it is cheap.

**Presence is a key that expires, not a status row.** `presence:{userId} = nodeId` with a 30 second TTL,
refreshed while the socket is healthy. The usual design — a row set to offline by a disconnect handler —
lies the moment a process is killed before its handler runs, and every system that does it that way ends
up with a reaper job that is itself one more thing that can be down. Here a crashed gateway's users mark
themselves offline within 30 seconds with nothing running. Liveness is proven continuously rather than
asserted once.

**Eventual consistency, deliberately.** Strong consistency would mean a cross-region quorum before every
message is acknowledged, paid on every message forever, to avoid a failover gap that replaying from Kafka
already repairs. What users actually notice is a message that vanishes — a durability problem, not a
consistency one.

## The numbers

| | |
| --- | --- |
| 50M daily users × 40 messages | 2B messages/day |
| 2B / 86,400 × 3 peak factor | **~70,000 messages/second** |
| 2B × 200 bytes | **400 GB/day**, ~146 TB/year |
| × 3.5 recipients average | ~245,000 delivery events/second |
| 20% connected at peak ÷ 50k per node | 10M sockets, ~200 gateway nodes |
| ~95% of reads served from the cache | ~500,000 RU/s on Cosmos at peak |

Full derivations, the partition sizing, and the three bottlenecks that bite before raw throughput does
are in [docs/capacity-planning.md](docs/capacity-planning.md).

## Why these data stores

**Cosmos DB partitioned by `conversationId`.** Everything a chat screen asks for — the last fifty
messages of one conversation, in order — is answered from a single logical partition, so a history read
is a single-partition query instead of a fan-out across the account. Partitioning by user instead would
scatter a group chat across as many partitions as it has members. The cost is a per-partition ceiling, so
very long-lived conversations bucket their key by month.

**Redis for the last 50 messages.** Opening a chat shows the bottom of the thread and almost nobody
scrolls, so the newest page is simultaneously the smallest slice of the data and nearly all of the read
traffic. It is a sorted set scored by sequence number, not a list: ZADD of a message already present just
rescores it, so a Kafka replay cannot duplicate it the way LPUSH would.

**Redis for presence, on its own cluster.** 10M keys is trivial in size; the load is a million writes a
second from heartbeats, which is a completely different profile from the cache and does not belong next
to it.

## Correctness under replay

At-least-once delivery means duplicates are normal rather than exceptional, and three layers absorb them:

- The store upserts on `messageId`, so a replayed write overwrites instead of appending.
- `seq-of:{messageId}` remembers the sequence number already assigned, so a message reprocessed after a
  crash keeps its original position in the thread instead of jumping to the end.
- The cache rescores rather than reinserting.

Offsets are committed by hand, after the durable write, so the system can never acknowledge a message it
did not store. Storing before fanning out is deliberate for the same reason: the opposite order would let
someone receive a message that was never written, and watch it vanish on reload.

## Repository layout

```
common/                shared message contract, Kafka topics, Redis key layout, token handling
gateway-service/       WebSocket termination, auth, rate limiting, presence heartbeats
message-service/       sequencing, Cosmos DB writes, cache, fan-out, history API
notification-service/  socket-or-push routing, APNs and FCM senders
docs/                  architecture, capacity planning, failure modes, data model
scripts/               k6 load test, end-to-end smoke test
```

## Documentation

- [Architecture](docs/architecture.md) — components, the write path, why Kafka is in the middle, what is
  and is not guaranteed
- [Capacity planning](docs/capacity-planning.md) — every number derived from stated assumptions
- [Failure modes](docs/failure-modes.md) — what breaks, who notices, what is lost, including the Kafka
  rewind used to repair a failover gap
- [Data model](docs/data-model.md) — documents, indexing policy, Redis keys, topic keys
- [Running locally](docs/local-development.md) — Docker, bare metal, Cosmos, tests, load, the API

## Running the tests

```bash
mvn test
```

No containers needed. They cover the places where a subtle bug would be expensive and invisible: token
tampering, multi-device delivery and the last-socket-closed signal, rate limiting, store ordering and
pagination, forward compatibility of the wire format, and the push preview and badge rules.

## What this does not do

Worth naming rather than leaving to be discovered:

- Message edits, deletions, reactions and threads. All straightforward additions to the write path.
- Read receipts are accepted on the wire and logged, not persisted.
- Attachments. The real design uploads to blob storage and sends a reference, so media never travels
  through Kafka.
- End-to-end encryption, which would change the fan-out model substantially: the server could no longer
  build push previews, and key distribution becomes its own system.
- Very large broadcast channels. Tens of thousands of members in one conversation need fan-out on read
  rather than on write.
- The local profile stores messages in memory so the system runs without an Azure account. Set
  `CHAT_STORE=cosmos` for the real thing.
