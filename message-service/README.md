# Valoq Message Service

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg)]()
[![Java Version](https://img.shields.io/badge/Java-21%2B-blue.svg)]()
[![Transport](https://img.shields.io/badge/Transport-Netty%20(Epoll%20%2F%20NIO)-orange.svg)]()
[![License](https://img.shields.io/badge/License-MIT-green.svg)]()

Valoq Message Service is a high-throughput, low-latency distributed messaging core engineered on top of Netty. Designed with a custom multiplexed binary wire protocol, it provides strict causal ordering, asynchronous event-sourced dual-index persistence, OS-level TCP backpressure, and high-watermark acknowledgment deduplication.

---

## 🚀 Key Architectural Highlights

### 1. Custom 12-Byte Binary Multiplexed Wire Protocol
* **Fixed-Header Framing:** Replaces brittle line-delimited ASCII and JSON/Regex string parsing with a fixed 12-byte binary envelope (`[Magic][Version][Opcode][Flags][StreamID][BodyLength]`), decoded via zero-copy linear index slicing.
* **Full Stream Multiplexing:** Client requests and server responses carry matching 32-bit `streamId` tokens, enabling concurrent, out-of-order request pipelining over a single physical TCP connection.
* **Out-of-Band Server Push:** Real-time fanout events use sentinel broadcast stream identifiers (`streamId = -1`), cleanly isolating autonomous server pushes from synchronous request-response transactions.

### 2. Kernel-Level TCP Flow Control & Dynamic Backpressure
* **High/Low Watermark AutoRead Throttling:** Protects against client message flooding and downstream storage latency spikes by monitoring in-flight asynchronous operations per connection.
* **OS TCP Window Zeroing:** When pending operations exceed `HIGH_WATERMARK (64)`, Netty's `autoRead` is dynamically toggled to `false`. This deliberately fills the OS kernel socket buffer, signaling a TCP Zero Window back to the sender and physically halting remote transmission at the transport layer without dropping packets or exhausting JVM heap.
* **Hysteresis Recovery:** Socket reads resume automatically once pending tasks drain below `LOW_WATERMARK (32)`, preventing pipeline thrashing.

### 3. Asynchronous Non-Blocking Event-Sourcing
* **Thread-Starvation Free:** Netty I/O worker loops execute zero blocking operations. Database reads and writes are fully decoupled into asynchronous `CompletionStage` pipelines, allowing worker threads to saturate hardware network bandwidth.
* **Deterministic Dual-Write Indexing:** Incoming chat events are simultaneously committed to the primary immutable event log and fanned out to per-user synchronization inboxes via composite futures (`CompletableFuture.allOf`), ensuring atomic synchronization visibility for offline recipients upon reconnect.
* **Idempotent Retry Safety:** All mutations execute with strict statement idempotency. If a transient network blip or coordinator failover occurs, client drivers safely re-execute writes without producing duplicate timeline entries.

### 4. Cumulative Watermark & Bulk Delivery / Read Receipts
* **Write-Amplification Elimination:** Avoids $O(N)$ row-level state updates across conversation groups. Instead of updating $N$ individual message records when a user catches up on chat history, the service accepts **Cumulative Watermark Receipts**.
* **Order-Preserving Verification:** A single acknowledgment packet carrying `(conversationId, upToEventId)` implicitly marks all historical messages up to that timestamp as delivered or read, reducing database mutation volume and UI socket chatter by over 90%.
* **Dual-Mode Receipt Protocol:** Supports both single/cumulative watermarks (`0x00`) and explicit array-based batch acknowledgments (`0x01`) within a single unified opcode.

### 5. Multi-Device Fanout & Connection Topology
* **One-to-Many Session Tracking:** Tracks client sessions through a concurrent, multi-device topology (`Map<UUID, Set<Channel>>`). A single user can remain connected simultaneously across desktop, mobile, and web clients.
* **Batched Outbound Flush Engine:** Replaces naive `writeAndFlush` loops with grouped buffer writes (`channel.write()`) followed by a consolidated single `channel.flush()` per target connection, minimizing native kernel system call overhead (`writev`/`epoll_wait`).
* **Safe Buffer Multiplexing:** Enforces Netty `ByteBuf.retainedSlice()` semantics across multi-channel broadcasts, preventing race conditions where early channel flushes prematurely deallocate off-heap memory from slower sibling connections.

### 6. Fault-Tolerant Connection Lifecycle
* **Idle Connection Reaper:** Incorporates an automated, non-polling hashed-wheel timer reaper (`IdleStateHandler`). Half-open sockets, dead mobile connections, and unauthenticated zombie connections are pruned after 300 seconds of read idleness, freeing file descriptors.
* **Phased Graceful Draining:** On server termination, the service:
    1. Closes the listener socket to reject new handshakes.
    2. Broadcasts an out-of-band `DISCONNECT` notice to active sockets so clients failover cleanly.
    3. Drains in-flight database mutations up to a grace period.
    4. Gracefully terminates event loop thread pools.

---

## 🛠️ Technology Stack

* **Runtime:** Java 21+
* **Networking Engine:** Netty 4.2 (Native Epoll on Linux / NIO fallback)
* **Buffer Management:** Direct Pooled Off-Heap Memory (`PooledByteBufAllocator`)
* **Security & Auth:** RS256 JWT Token Verification with Public Key Caching
* **Testing:** JUnit 5, Testcontainers, Concurrent Stress Pipelines

---

## ⚡ Socket & Transport Tuning

The server bootstrap is tuned specifically for low-latency, real-time message distribution:

| Socket Option             | Setting                          | Purpose                                                                                          |
|:--------------------------|:---------------------------------|:-------------------------------------------------------------------------------------------------|
| `TCP_NODELAY`             | `true`                           | Bypasses Nagle's algorithm; forces immediate packet dispatch for low latency.                    |
| `SO_LINGER`               | `0`                              | Eliminates kernel socket `TIME_WAIT` stalls on disconnect, preventing ephemeral port exhaustion. |
| `SO_KEEPALIVE`            | `true`                           | Periodically probes TCP keep-alive packets to detect dropped connections.                        |
| `ALLOCATOR`               | `PooledByteBufAllocator.DEFAULT` | Recycles native off-heap memory arenas to eliminate GC pauses.                                   |
| `WRITE_BUFFER_WATER_MARK` | `8KB (Low) / 32KB (High)`        | Guards against memory leaks from slow or unresponsive remote consumers.                          |

---

## 📦 Building and Running

### Prerequisites
* JDK 21 or later
* Docker (for automated integration testcontainers)

### Build & Run Tests
```bash
# Clean, format, and execute unit tests
./gradlew test

# Run end-to-end integration and concurrency suites
./gradlew e2eTest

# Build executable distribution
./gradlew assemble
```

### Local observability deployment

The repository Compose stack publishes the message TCP protocol on `8082` and its
container-side Prometheus endpoint on `9464`, mapped to host port `9465` so it
does not collide with auth-service. Message traces are sent to the in-network
OTel Collector and logs are collected from the `valoq-message-service` container
by Alloy.

The Compose credentials are development-only values. Production deployments must
override database, Redis, JWT, Grafana, and any other service credentials through
environment-specific secrets or a Compose override file; do not reuse the values
in `docker-compose.yaml`.

### Production observability

Message-service initializes an OpenTelemetry SDK at startup. It exports OTLP
gRPC traces to `OTEL_EXPORTER_OTLP_ENDPOINT` (default
`http://otel-collector:4317`) and serves Prometheus metrics from the embedded
HTTP endpoint configured by `PROMETHEUS_PORT` (default `9464`). In the root
Compose deployment the container endpoint is published on host port `9465` to
avoid the auth-service's host-port collision.

The service creates a root `SERVER` span named `message.tcp.connection` for each
TCP channel and propagates its context through Netty handlers, asynchronous
Cassandra/Redis callbacks, and virtual-thread tasks. Logback emits newline-
delimited JSON to stdout. `trace_id` and `span_id` are included only when an
active span exists, allowing Alloy and Grafana to correlate logs with Tempo
traces without putting trace IDs into Loki index labels.

The business metrics are intentionally low cardinality:

* `message_received_total{type}`
* `message_processing_errors_total{type,error_code}`
* `message_active_connections`
* `message_cassandra_operation_duration_seconds{operation}`
* `message_redis_operation_duration_seconds{operation}`

JVM runtime metrics cover memory pools, garbage collection, threads, peak
threads, and CPU. Persistence spans and errors use fixed operation/repository
attributes only. No observability signal contains JWTs, credentials, message
bodies, usernames, email addresses, conversation/message IDs, IP addresses,
Redis keys/values, or raw CQL values.

For local deployment, see the root [`README.md`](../README.md). Production
deployments should override `LOG_LEVEL`, telemetry endpoints, credentials, and
dashboard access through secrets or an environment-specific deployment overlay.