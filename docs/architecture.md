# Architecture

## The shape of the problem

Chat looks simple and isn't, because two requirements pull against each other. A message must feel
instant, and a message must never be lost. Instant argues for pushing straight from sender to recipient.
Never lost argues for writing it down first. The whole design is an attempt to get both: accept fast,
persist before anyone is told, and treat delivery as something that can be retried rather than something
that must succeed on the first try.

## Components

```
                        ┌──────────────────────────────────────────┐
   phone / web /        │              gateway nodes               │
   desktop clients ────▶│  WebSocket termination, auth, rate limit │
      (WebSocket)       │  presence heartbeats, no business logic  │
                        └───────┬──────────────────────────▲───────┘
                                │ produce                  │ Redis pub/sub
                                │ key=conversationId       │ channel gw:{nodeId}
                                ▼                          │
                        ┌───────────────┐                  │
                        │ chat.messages │  Kafka           │
                        └───────┬───────┘                  │
                                │ consume                  │
                                ▼                          │
                        ┌──────────────────────┐           │
                        │   message-service    │           │
                        │  sequence → store →  │           │
                        │  cache → fan out     │           │
                        └───┬──────────────┬───┘           │
                 upsert     │              │ produce       │
            PK=conversation │              │ key=recipient │
                            ▼              ▼               │
                   ┌─────────────┐  ┌──────────────┐       │
                   │  Cosmos DB  │  │ chat.fanout  │       │
                   │  messages   │  └──────┬───────┘       │
                   └─────────────┘         │               │
                            ▲              ▼               │
                            │      ┌──────────────────────┐│
                   ┌────────┴────┐ │ notification-service ├┘
                   │    Redis    │◀┤ online? socket : push │
                   │ cache +     │ └──────────┬───────────┘
                   │ presence    │            │ offline
                   └─────────────┘            ▼
                                        APNs / FCM
```

Four moving parts, each with one job:

**gateway-service** holds the sockets and nothing else. It authenticates at handshake, enforces a
per-connection rate limit, writes accepted messages to Kafka, and keeps a presence key alive for every
user it holds. It never touches the database. That constraint is what makes the fleet disposable: a
gateway node can be killed at any moment and the only thing lost is a set of connections that clients
re-establish in a second.

**message-service** owns the write path. It is the only component that assigns sequence numbers and the
only one that writes to the message store, so ordering has exactly one author. It also serves history
reads, which is where the cache earns its place.

**notification-service** makes one decision per recipient: live socket or push notification. It is split
out from message-service because its failure modes are completely different — it talks to two external
providers that can be slow or down, and none of that should be able to back up the write path.

**common** holds the message contract, the Kafka topic names and the Redis key layout. Four services
agreeing on a wire format by convention is how a system develops a slow, silent data bug.

## The write path, step by step

1. A client sends a frame with a UUID it generated. That id is the idempotency key for everything
   downstream, which is what makes a resend after a dropped connection safe.
2. The gateway authenticates the frame against the handshake identity — a sender id in the payload is
   ignored — and produces to `chat.messages` keyed by conversation id.
3. The client is told `queued`, not `stored`. It keeps the message in a local outbox, greyed out, until it
   comes back through the delivery path with a sequence number on it. This is why the sender's own devices
   are included in fan-out.
4. message-service consumes the partition that owns the conversation, allocates a sequence number, upserts
   to Cosmos DB, adds to the recent cache, and only then produces one `chat.fanout` event per member.
5. notification-service looks up each recipient's presence. A hit means publishing on that gateway's Redis
   channel; a miss means an APNs or FCM push.
6. The gateway that owns the socket writes the frame out.

## Why Kafka is in the middle

A gateway could write to the database directly. It would be fewer moving parts and lower latency. Three
things make the queue worth its cost:

**It decouples accept rate from write rate.** Traffic arrives in spikes — a product launch, a sports
final, a region failing over and reconnecting all at once. The gateway can accept at whatever rate clients
offer while the consumers work through the backlog at the pace the database can sustain. Without the
buffer, a spike becomes database throttling, which becomes failed sends.

**It makes recovery a replay.** Consumer offsets mean the system's recovery procedure is "start reading
from where you stopped." A crashed consumer, a bad deploy rolled back, a Cosmos region that went
read-only for ten minutes — all the same procedure, and all without losing messages that were accepted.
Durability lives in the log, where it is cheap, instead of in bespoke retry logic in four places.

**It gives ordering for free.** Partitioning by conversation id means one consumer owns a conversation at
any moment, so sequence allocation needs no distributed lock. Ordering falls out of the partitioning.

## Why presence is a TTL and not a status table

The obvious implementation is a row that says online and a disconnect handler that sets it to offline.
That design works until a gateway process is killed, loses its network, or is OOM-killed mid-deploy —
because then the disconnect handler never runs and the row lies. Every system that does it this way ends
up with a reaper job to clean up stale rows, and the reaper is one more thing that can be down.

Here the record is `presence:{userId} = nodeId` with a 30 second TTL, refreshed while the socket is
healthy. Liveness is proven continuously rather than asserted once, so a crashed node's users mark
themselves offline within 30 seconds with nothing running. The failure mode is a bounded window of
staleness instead of an unbounded one, and there is no cleanup path to get wrong.

## Why the last fifty messages

Open a chat and you see the bottom of the thread. Almost nobody scrolls. The newest page is therefore both
the smallest slice of the data and nearly all of the read traffic, which is as good as a cache gets.

It is a Redis sorted set scored by sequence number rather than a list. A list would need LPUSH, which is
not idempotent: a Kafka replay would insert the same message twice. ZADD of a message already present just
rescores it, and the set stays ordered by sequence regardless of write arrival order.

## Consistency

The system is eventually consistent across regions and makes no apology for it. Strong consistency on the
write path would mean a cross-region quorum before a message is acknowledged, at a cost of tens of
milliseconds per message and a hard dependency on every region being reachable. For chat, that trade is
backwards. What users actually notice is a message that vanishes, and that is a durability problem, not a
consistency one.

What is guaranteed:

- A message acknowledged as stored is durable and will be delivered, eventually, to every member.
- Messages within a conversation are totally ordered by `serverSeq`, and every member sees that same order.
- A client reads its own writes (Cosmos session consistency).

What is not:

- Two clients may briefly see different *latest* messages. They converge within replication lag.
- Delivery is at-least-once end to end. Clients deduplicate on `messageId`, which they already have to do
  to support offline resend.
- Presence may be up to 30 seconds stale after an abrupt node loss.
