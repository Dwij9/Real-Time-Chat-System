# Capacity planning

Every number below is derived rather than asserted, so the assumptions can be argued with. Change an
input and the rest moves with it.

## Inputs

| Assumption | Value | Where it comes from |
| --- | --- | --- |
| Daily active users | 50,000,000 | Target |
| Messages sent per active user per day | 40 | Workplace chat sits well above social messaging |
| Average stored message size | 200 bytes | ~120 bytes of text plus ids, sequence, timestamps |
| Average conversation size | 3.5 recipients | Mostly 1:1, a long tail of group chats |
| Peak-to-average ratio | 3x | Working hours concentrate traffic; mornings are the worst |
| Share of users connected at peak | 20% | 10M concurrent sockets |

## Message throughput

```
50,000,000 users x 40 messages = 2,000,000,000 messages/day
2,000,000,000 / 86,400 seconds = ~23,000 messages/second average
23,000 x 3 peak factor         = ~70,000 messages/second at peak
```

Fan-out multiplies that for everything past the durable write:

```
70,000 x 3.5 recipients = ~245,000 delivery events/second at peak
```

The asymmetry is the whole reason fan-out happens after storage rather than before. One durable write
feeds 3.5 cheap events; doing it the other way round would mean 245,000 database writes a second for the
same 70,000 messages.

## Storage

```
2,000,000,000 messages/day x 200 bytes = 400 GB/day of new chat data
400 GB x 365                           = ~146 TB/year
```

With Cosmos DB's default dual replication for durability, provision roughly 3x that in raw terms. Growth
is linear and relentless, which is why the message container supports a per-document TTL: tiering
anything older than 90 days to cold blob storage cuts the hot footprint to around 36 TB while keeping
recent history — the part anyone actually reads — on the fast path.

### Partition sizing

A Cosmos logical partition is capped at 20 GB and 10,000 RU/s. At 200 bytes a message that is about 100
million messages in one conversation, which no real conversation reaches. The ceiling that bites first is
RU/s on an extremely busy group chat, not bytes.

For the pathological cases, the partition key becomes `conversationId:yyyyMM`, bucketing a long-running
conversation by month. Recent history stays in one partition — which is what reads ask for — while the
total is bounded. The bucket is resolved above the store layer so the storage code stays simple.

## Connections

```
10,000,000 concurrent sockets at peak
50,000 sockets per gateway node     = 200 nodes
```

50k per node is conservative for an event-driven server; the limit in practice is memory per connection
(socket buffers, the session object, a token bucket) rather than CPU. At roughly 20 KB a connection that
is about 1 GB of connection state per node, which comfortably fits a 4 GB heap with room for traffic.

Run at 1.5x that headroom — 300 nodes — so that losing an availability zone does not push the survivors
past their limit. Reconnect storms are the real risk: 200 nodes' worth of clients reconnecting at once is
a 10M-socket handshake burst, which is why clients must back off with jitter rather than retrying in
lockstep.

## Kafka

`chat.messages` carries 70k records/second at peak. A partition handles a few thousand records a second
comfortably once batching and compression are on.

```
1,024 partitions, replication factor 3, min.insync.replicas 2
```

1,024 is more than throughput strictly requires. The extra headroom buys consumer parallelism — the
partition count caps how many consumers can work the topic — and it is painful to increase later, because
raising the partition count changes the hash mapping and therefore breaks per-conversation ordering for
in-flight messages. Over-provisioning partitions is cheap; re-partitioning a live chat system is not.

`chat.fanout` carries 245k records/second and gets 2,048 partitions on the same reasoning.

Retention of 7 days on both. That is the real recovery budget: a consumer can be rebuilt from scratch and
replay a week, which covers a bad deploy discovered on Monday after shipping on Friday.

```
Peak ingest: 70,000 x 200 bytes x 3 replicas = ~42 MB/s
7 days of retention                          = ~25 TB across the cluster (lz4 brings this down by ~60%)
```

## Redis

Three separate clusters, because their access patterns and failure tolerances have nothing in common.

**Presence.** 10M keys of about 100 bytes is 1 GB, which is nothing. The load is write rate, not size:

```
10,000,000 users / 10 second heartbeat = 1,000,000 key writes/second
```

Spread over a cluster of 20 shards at 50k ops/s each, pipelined so a node refreshing 50,000 users does it
in one round trip rather than 50,000. Lengthening the heartbeat to 15s would cut this by a third at the
cost of a slower offline signal; 10s with a 30s TTL is the chosen point.

**Message cache.** 50 messages at ~250 bytes serialized is 12.5 KB per conversation.

```
10,000,000 conversations active in a 24h window x 12.5 KB = ~125 GB
```

With `allkeys-lru` and a 24 hour TTL, so a conversation nobody opens falls out on its own. Sized at 200
GB across the cluster to keep the hit rate high through the daily peak.

**Membership.** Small, nearly static, read on every single message. A 6 hour TTL and explicit
invalidation on membership change.

## Cosmos DB throughput

```
Writes: 70,000/second x ~5 RU per 200-byte insert       = ~350,000 RU/s
Reads:  cache absorbs ~95%, leaving ~15,000 queries/s
        x ~10 RU per 50-item single-partition query     = ~150,000 RU/s
Total at peak                                           ≈ 500,000 RU/s
```

Autoscale, not fixed. Traffic has a pronounced daily shape and a 3x peak, so provisioning for peak around
the clock would roughly triple the bill for capacity that sits idle overnight.

The 95% cache hit rate is the single most load-bearing assumption in this table. If it were 80%, database
reads would quadruple to 600,000 RU/s and the read path would cost more than the write path. It is worth
alerting on directly rather than inferring from latency.

## What the bottleneck actually is

Not throughput — every tier above scales horizontally. The constraints that bite first:

1. **Reconnect storms.** Losing a region moves millions of sockets at once. The handshake burst, not the
   steady state, sizes the gateway fleet. Mitigated with exponential backoff and jitter in clients, and
   with surviving-region headroom.
2. **Hot partitions.** A 10,000-member announcement channel concentrates on one logical partition for
   writes and one Kafka partition for ordering. Large broadcast channels need a different write path
   (fan-out on read instead of on write), which this design does not attempt.
3. **Cache hit rate.** As argued above.
