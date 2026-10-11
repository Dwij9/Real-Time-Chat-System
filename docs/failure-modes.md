# Failure modes

The question for each component is the same: what breaks, who notices, and does anything get lost.

## A gateway node dies

**Happens:** OOM kill, node drain, zone loss, bad deploy.

**Effect:** Every socket it held drops. Clients reconnect to another node through the load balancer,
typically within a second or two.

**Messages in flight:** A message accepted and written to Kafka survives — it was acknowledged by the
broker before the client was told `queued`. A message in the socket buffer when the process died is lost
from the gateway's point of view, but the client never saw an acknowledgement for it and resends it with
the same `messageId`. The resend is deduplicated downstream, so there is no double post.

**Presence:** Nothing cleans up. The dead node's presence keys stop being refreshed and expire within 30
seconds. During that window, messages for those users are published to a Redis channel nobody is
listening on and are dropped — they are already durable, and the clients, which have reconnected by then,
see them in history. Nothing is lost; some notifications are late.

**Recovery:** Automatic. Replacement nodes register no state because gateways hold none.

## message-service dies or falls behind

**Happens:** Deploy, crash, database throttling, a slow dependency.

**Effect:** The `chat.messages` consumer lag grows. Messages are still accepted by gateways and still
land in Kafka — the queue is doing exactly what it is there for. What users see is delivery latency
rising, not failures.

**On restart:** Consumption resumes from the last committed offset. Because offsets are committed after
the durable write, anything in progress at the moment of death is redelivered and redone. The redo is
safe by construction: the store upserts on `messageId`, the cache rescores rather than reinserting, and
the sequence number is read back from its marker so the message keeps its original position.

**If it falls behind for hours:** Kafka retains 7 days. Consumer concurrency can be raised up to the
partition count without reprocessing anything, because partition assignment rebalances cleanly.

## notification-service dies

**Effect:** No live delivery and no pushes. Messages continue to be accepted, sequenced and stored
normally — the write path does not depend on this service at all.

**On restart:** `chat.fanout` has `auto-offset-reset: latest`, so a brand new consumer group starts at
the head rather than replaying history as a flood of push notifications. An existing group resumes from
its committed offset and works through the backlog.

**What users see:** Messages appear as soon as they refresh or reconnect, because history reads go
through message-service. Pushes for the outage window arrive late or not at all. This is the one
component whose failure is genuinely benign, which is exactly why it was separated from the write path.

## Redis is lost

Three clusters, three different stories.

**Presence cluster gone:** Every user looks offline. Delivery falls back entirely to push, which is
degraded but functional. Lookups fail closed, not open — a failed lookup is treated as offline, which
means an unnecessary push rather than a silently dropped message.

**Cache cluster gone:** Every history read goes to Cosmos DB. Reads get slower and considerably more
expensive; nothing is incorrect, because the cache is never a source of truth. This is the scenario to
watch the RU budget during, since read load jumps roughly twentyfold.

**Sequence counters gone:** The one case that needs care. Counters restarting at 1 would number new
messages below the existing history and corrupt ordering. `SequenceAllocator` detects a counter coming
back fresh and rebuilds the high-water mark from the durable store before using it. If that rebuild
fails, the message is failed deliberately so Kafka redelivers it — far better than writing a
wrongly-ordered message that nothing will ever correct.

## A Cosmos DB region fails

**Read failure:** The client's preferred-region list is ordered. Reads shift to the next region
automatically, without a restart or a config push.

**Write failure:** With multi-region writes enabled, the standby region takes writes. Cross-region
replication is asynchronous, so a region lost abruptly can leave a small window of writes unreplicated at
the moment of failover.

This is where replay matters. Those writes are still in Kafka, inside the retention window, and inside
the consumer's committed-offset history. Recovery is to rewind the `chat.messages` consumer group to a
timestamp before the failover and let it run forward. Every message is re-upserted with its original id
and original sequence number, so the replay is idempotent — it repairs the gap and leaves everything else
untouched. No reconciliation script, no diffing of two regions, no manual repair.

```bash
# Rewind the write-path consumer group to just before the failover and replay.
kafka-consumer-groups.sh --bootstrap-server $BROKERS \
  --group message-service \
  --topic chat.messages \
  --reset-offsets --to-datetime 2026-03-14T08:55:00.000 \
  --execute
```

**The trade:** Accepting asynchronous replication is what buys single-digit millisecond writes. Strong
consistency would make this failure mode disappear and make every message slower by a cross-region round
trip, permanently, to avoid a problem that replay already solves.

## Kafka is unavailable

The one genuine outage. Gateways cannot write, so messages cannot be accepted.

The producer is configured with `acks=all`, idempotence on, and a 30 second delivery timeout, so it rides
out a leader election or a broker restart without the client noticing. A total cluster loss is different:
sends fail, and the client keeps the message in its outbox and retries. Users see "sending" rather than a
message that was accepted and quietly dropped — which is the correct behaviour, if not a pleasant one.

`min.insync.replicas=2` with replication factor 3 means the cluster tolerates one broker down per
partition with no loss of availability and no loss of acknowledged writes.

## Poison messages

A message that fails processing forever would block its partition, and because the partition is keyed by
conversation, it would stall that entire chat behind it.

`DefaultErrorHandler` retries with exponential backoff for up to 10 seconds — enough to ride out a
transient database error — and then publishes to `chat.messages.dlq` and moves on. The partition keeps
flowing. The parked records keep their headers, including the original exception, and can be replayed
once the cause is fixed.

## Duplicate delivery

At-least-once means duplicates are normal, not exceptional. They are handled at three layers:

1. `messageId` is generated by the client, so a resend after a dropped connection carries the same id.
2. The store upserts on that id, so a second write overwrites rather than appends.
3. The sequence marker means a replayed message keeps its original position instead of being appended at
   the end of the thread.

Clients deduplicate on `messageId` on receipt. They already need that logic for their own offline
outbox, so it costs nothing extra.

## What this design does not survive

Worth being explicit about:

- **Total loss of the Kafka cluster.** Sends stop. Nothing accepted is lost, but the system is down.
- **A conversation hot enough to exceed one partition's throughput.** Very large broadcast channels need
  fan-out on read, which this design does not attempt.
- **Correlated client behaviour at global scale.** A worldwide reconnect storm is a capacity problem no
  amount of correct code solves; it needs client-side jitter and reserved headroom.
