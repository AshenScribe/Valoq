# Valoq Authentication Service

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg)]()
[![Java Version](https://img.shields.io/badge/Java-21%2B-blue.svg)]()
[![Transport](https://img.shields.io/badge/Transport-Netty%20(Epoll%20%2F%20NIO)-orange.svg)]()
[![License](https://img.shields.io/badge/License-MIT-green.svg)]()

Valoq Authentication Service is an asynchronous, high-throughput identity and token authority engineered on top of Netty. Designed for mission-critical authentication pipelines, it incorporates defensive per-IP connection throttling, fail-fast database admission gating, non-blocking virtual thread workers, constant-time cryptographic verification, and automated idle connection reaping.

---

## 🚀 Key Architectural Highlights

### 1. Defensive Admission Control & Connection Bulkheading
* **Dual-Tier Socket Rate Limiting:** Positioned at the head of the pipeline, a `@Sharable` `ConnectionLimitHandler` enforces concurrent socket quotas both globally (`maxConnections = 5000`) and on a per-IP basis (`maxConnectionsPerIp = 50`). Excess sockets are terminated before buffer allocation or command parsing occur, neutralizing connection-flooding and socket-exhaustion DoS attacks.
* **Database Pool Sizing & Bulkheading:** Prevents virtual-thread connection stampedes against the relational connection pool (HikariCP). Inbound authentication tasks must acquire a bounded `Semaphore` permit matching the pool's capacity before hitting storage.
* **Fast-Fail Load Shedding:** Under database saturation, authentication requests wait at most 2 seconds for a concurrency permit. If the pool remains contested, the request sheds immediately with an explicit overload error (`ERROR Server overloaded, please retry later`), preventing cascading worker timeouts.
* **Dead-Client Task Cancellation:** Inspects `channel.isActive()` immediately upon acquiring worker resources. If a remote client disconnects or times out while queued, the server drops the task immediately, bypassing the SQL query and the compute-expensive RSA token-signing routine.

### 2. Transport Flow Control & Socket-Level Backpressure
* **Single-In-Flight Invariant:** Authentication is inherently sequential per TCP connection. Upon reading an `AUTH` command, the pipeline dynamically sets `channel.config().setAutoRead(false)` to halt kernel-level socket reads.
* **Pipeline Spam Defense:** Halting auto-read prevents adversarial clients from pipelining thousands of brute-force login attempts down a single socket. The socket resumes reading (`setAutoRead(true)`) strictly after the current cryptographic response has been written.
* **Zero-Allocation Command Scanner:** Replaces heap-allocating regex engines (`split("\\s+")` and `Pattern`) with a vectorized single-pass index scanner (`indexOf`), parsing verbs, subcommands, and Base64 JSON boundaries with zero garbage-collection overhead.

### 3. Cryptographic Security & Hardening
* **Timing-Attack Immune Hash Verification:** Replaces standard `String.equals()` (which leaks timing side-channels via early-exit byte comparison) with `MessageDigest.isEqual(...)`. Password hash comparisons run in constant time regardless of prefix match depth.
* **Asymmetric Token Authority (RS256):** Issues cryptographically signed RS256 JWT tokens using 2048-bit RSA keys loaded dynamically on boot. Public verification keys are served directly over a zero-overhead `PUBLIC_KEY` wire verb.
* **Strict Parameter Bounds:** Strict validation rules defend against buffer overflows, invalid Unicode, and SQL injection probes before any query reaches database execution layers.

### 4. Automated Socket Lifecycle & Teardown
* **Idle Connection Reaper:** Incorporates a non-polling hashed-wheel timer (`IdleStateHandler`) that monitors read-idleness. Half-open sockets, orphaned connections, and slow-loris attacks are reaped after 60 seconds of inactivity, instantly returning permits to the per-IP connection quota.
* **Two-Phase Graceful Shutdown:** On termination:
  1. Shuts down server listener sockets to stop accepting handshakes.
  2. Closes all active client channels concurrently via `ChannelGroup.close()`.
  3. Drains active virtual thread workers up to a 5-second deadline.
  4. Tears down HikariCP database connection pools last, preventing `ConnectionClosedException` in active transactions.

---

## 🛠️ Technology Stack

* **Runtime:** Java 21+
* **Networking Engine:** Netty 4.2 (Native Epoll on Linux / NIO fallback)
* **Concurrency:** Project Loom Virtual Threads + Bounded Concurrency Semaphores
* **Database Pool:** HikariCP + PostgreSQL Driver
* **Cryptography:** Java Security (RSA/RS256), JJWT (Java JWT)
* **Testing:** JUnit 5, Testcontainers (PostgreSQL), Embedded Netty Pipelines

---

## ⚡ Socket & Transport Tuning

The server bootstrap is optimized for low-latency identity transactions:

| Socket Option             | Setting                          | Purpose                                                                                 |
|:--------------------------|:---------------------------------|:----------------------------------------------------------------------------------------|
| `TCP_NODELAY`             | `true`                           | Disables Nagle's algorithm; forces immediate socket dispatch for token responses.       |
| `SO_LINGER`               | `0`                              | Bypasses kernel `TIME_WAIT` stalls on disconnect, preventing ephemeral port exhaustion. |
| `SO_KEEPALIVE`            | `true`                           | Periodically probes TCP keep-alive packets to detect dropped connections.               |
| `ALLOCATOR`               | `PooledByteBufAllocator.DEFAULT` | Reuses off-heap native memory arenas to eliminate JVM garbage collection pauses.        |
| `SO_BACKLOG`              | `1024`                           | Enlarges the kernel accept queue to absorb bursty connection spikes cleanly.            |
| `WRITE_BUFFER_WATER_MARK` | `8KB / 32KB`                     | Enforces backpressure if the remote client read buffer stalls.                          |

---

## 📦 Building and Running

### Prerequisites
* JDK 21 or later
* Docker (for automated integration testcontainers)

### Build & Run Tests
```bash
# Clean, format, and execute unit tests
./gradlew test

# Run full integration and security verification suites
./gradlew e2eTest

# Build executable distribution
./gradlew assemble
```

### Environment Configuration
The service is configured via environment variables or `auth-config.properties`:

| Variable | Default | Description |
| :--- | :--- | :--- |
| `PORT` | `8001` | TCP port for authentication traffic. |
| `DB_HOST` | `localhost` | PostgreSQL cluster host. |
| `DB_PORT` | `5432` | PostgreSQL port. |
| `DB_NAME` | `valoq` | Database name. |
| `DB_USER` | `valoq` | Database username. |
| `DB_PASSWORD` | `valoq` | Database password. |
| `JWT_EXPIRATION` | `300` | Issued token lifespan in seconds (default: 5 minutes). |
```