# Data model

## Cosmos DB: `messages`

Partition key `/conversationId`. Document id is the client-generated `messageId`.

```json
{
  "id": "6f1c0a3e-9a2b-4f1d-8c77-1b2e4d5a6f70",
  "conversationId": "dm:alice:bob",
  "senderId": "alice",
  "type": "TEXT",
  "body": "Standup moved to 10",
  "sentAt": 1757000000000,
  "serverSeq": 1482,
  "storedAt": 1757000000037,
  "ttl": null
}
```

`id` doubling as the message id is what makes writes idempotent: a Kafka replay upserts the identical
document rather than appending a second copy.

`serverSeq` is the ordering authority. Client clocks drift and tie; a server-assigned counter per
conversation does neither. It is monotonic but not gapless, and nothing in the system assumes otherwise.

### The index policy matters more than it looks

Cosmos indexes every property by default, and every indexed property costs RU on write. At 70,000 writes
a second, indexing `body` means paying to build a full-text index nobody queries.

```json
{
  "indexingMode": "consistent",
  "includedPaths": [{ "path": "/serverSeq/?" }],
  "excludedPaths": [{ "path": "/*" }]
}
```

Only `serverSeq` is indexed, because the only query is "the newest N of this conversation, ordered by
sequence" and the partition key is already pinned. This cuts the write RU charge by roughly half.

## Cosmos DB: `conversations`

Partition key `/conversationId`, so membership is a point read with the id the caller already has.

```json
{
  "id": "dm:alice:bob",
  "conversationId": "dm:alice:bob",
  "type": "DIRECT",
  "members": ["alice", "bob"],
  "title": null,
  "createdAt": 1756900000000
}
```

Direct conversation ids are derived from the sorted member pair, so both sides compute the same id
without a lookup, and opening a chat twice cannot create two of them. Group ids are random.

## Redis

| Key | Type | TTL | Purpose |
| --- | --- | --- | --- |
| `presence:{userId}` | string → nodeId | 30s | Online status and socket routing |
| `conv:{id}:recent` | sorted set, score = serverSeq | 24h | Last 50 messages |
| `conv:{id}:seq` | counter | none | Sequence allocation |
| `conv:{id}:members` | set | 6h | Membership, in front of Cosmos |
| `seq-of:{messageId}` | string → serverSeq | 24h | Replay keeps its original position |
| `devices:{userId}` | set of `platform\|token` | none | Push targets |
| `unread:{userId}:{conversationId}` | counter | none | Push badge |

The `{}` around conversation ids are Redis Cluster hash tags. Everything about one conversation hashes to
the same slot, so the cache write, the sequence increment and the membership read happen on one shard and
can be pipelined into a single round trip instead of three.

## Kafka

| Topic | Key | Partitions (prod) | Retention |
| --- | --- | --- | --- |
| `chat.messages` | conversationId | 1,024 | 7 days |
| `chat.fanout` | recipientId | 2,048 | 7 days |
| `chat.messages.dlq` | conversationId | 12 | 30 days |

The keys are the design. Keying `chat.messages` by conversation means one consumer owns a chat and
sequence allocation needs no lock. Keying `chat.fanout` by recipient means one person's incoming stream
is processed in order, so message 12 cannot overtake message 11 on the way to their screen.

Raising a partition count later changes the hash mapping and breaks ordering for in-flight messages,
which is why both topics are over-provisioned from the start.
