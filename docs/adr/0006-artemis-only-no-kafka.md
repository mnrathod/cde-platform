# 6. Run ActiveMQ Artemis alone; adopt Kafka only on a documented trigger

- **Status:** Accepted
- **Date:** 2026-08-27 (recording a decision taken earlier)
- **Related:** ADR 5

## Context

The asynchronous work this product needs is document and IFC processing,
virus scanning, thumbnail generation, report and export generation, bulk
import, notification dispatch, webhook delivery with retry, and scheduled
jobs.

Every one of those is the same shape: *do this unit of work reliably, once,
with retries and a dead-letter queue.* That is a transactional work queue.

Kafka is a distributed log, optimised for a different problem: high-throughput
ordered streams consumed independently and replayed.

## Options

**Artemis alone.** Handles work queues well — scheduled and delayed delivery,
message groups, priorities, last-value queues, JMS/AMQP/MQTT/STOMP. Roughly
one component to operate.

**Kafka alone.** Would work, but every work-queue pattern becomes something
you build on top of the log rather than something the broker gives you, and
it brings partition planning, consumer group rebalancing, retention tuning
and schema registry operations.

**Both.** Doubles the operational surface, the failure modes, the on-call
knowledge and the cost. Until a trigger fires, it buys nothing.

## Decision

Artemis only, for everything.

Kafka is adopted only when one of these is demonstrably true, recorded in its
own ADR:

1. Multiple independent consumers need the same event stream, and
   reprocessing one must not affect the others.
2. Replay is a *functional* requirement — rebuilding a projection, search
   index or analytics store from history — rather than an operational
   convenience.
3. Sustained throughput exceeds what Artemis comfortably handles at our
   message sizes, **proven by load testing** rather than estimated.
4. Event sourcing is chosen as the persistence model for a bounded context,
   which needs its own ADR first.
5. Ordered, partitioned processing at scale, with per-key ordering guaranteed
   across many consumers.

Given ADR 5, trigger 1 or 2 is the most likely to fire: CDE state transitions
and audit events are a natural stream with several downstream consumers —
SIEM, analytics, search indexing.

**Design for that now without deploying it.** Publishing goes through a
transactional outbox behind a `DomainEventPublisher` interface, so moving a
topic to Kafka is an adapter change rather than a rewrite of every producer
and consumer.

## Consequences

- One broker to run, monitor, secure, upgrade and be paged about.
- The migration path is cheap because the abstraction exists from the start.
  This is the rare case where building the seam early is right — not because
  the second implementation is likely, but because retrofitting a publisher
  interface across every call site later is expensive and error-prone.
- Rules that hold whichever broker is running: transactional outbox for
  publishing, idempotent consumers, bounded retries with exponential backoff
  and jitter, a dead-letter queue with alerting, `tenantId`/`correlationId`/
  `traceId`/schema version on every message, never file contents or PII in a
  message, per-tenant partitioning and concurrency caps.
- **Not yet built.** Neither the broker nor the outbox is deployed. Until
  they are, work that should be asynchronous runs inline, and the "bulk
  operations return a job ID in under a second" rule cannot be satisfied.
  This is the largest gap this ADR describes, and it is recorded rather than
  glossed.
