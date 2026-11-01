# Valoq Binary Wire Protocol (VBWP) Specification

**Version:** 1.0  
**Status:** Stable  
**Transport:** Raw TCP (TLS may be terminated by the deployment)
**Byte Order:** Big-Endian (Network Byte Order)

---

## 1. Frame Architecture

Every packet transmitted between client and server consists of a fixed **12-byte
header** followed by exactly `body_length` payload bytes. The header's length
field is the frame body's byte count; it is not an additional length prefix.

```
0                   1                   2                   3
0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  Magic (0x56) |  Version (1)  |  Opcode (1B)  |   Flags (1B)  |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          Stream ID (4B)                       |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         Body Length (4B)                      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                        Payload Data...                        |
|                          (Body Length)                        |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### 1.1 Header Fields

| Field           | Type     | Offset | Description                                                                                                                 |
|:----------------|:---------|:-------|:----------------------------------------------------------------------------------------------------------------------------|
| **Magic**       | `uint8`  | 0      | Protocol identifier constant: `0x56` (ASCII `'V'`). Packets failing this check are rejected and the channel is terminated.  |
| **Version**     | `uint8`  | 1      | Protocol version. Current version: `0x01`.                                                                                  |
| **Opcode**      | `uint8`  | 2      | Identifies the operation type (see Section 2).                                                                              |
| **Flags**       | `uint8`  | 3      | Reserved bitmask for future extensions (e.g. payload encryption, tracing). Default: `0x00`.                                 |
| **Stream ID**   | `int32`  | 4      | Signed integer correlating request and response frames. For unprompted server pushes (events), this field is `-1`.          |
| **Body Length** | `uint32` | 8      | Size of the following payload data in bytes (`0` indicates an empty body). The decoder rejects frames larger than 10,485,760 bytes (10 MiB). |

---

## 2. Opcodes

| Hex    | Name            | Direction                   | Description                                                    |
|:-------|:----------------|:----------------------------|:---------------------------------------------------------------|
| `0x00` | `ERROR`         | Server $\rightarrow$ Client | Protocol or business validation error response.                |
| `0x01` | `INIT`          | Client $\rightarrow$ Server | Authenticates connection with JWT bearer token.                |
| `0x02` | `READY`         | Server $\rightarrow$ Client | Acknowledges successful initialization.                        |
| `0x03` | `SEND`          | Client $\rightarrow$ Server | Submits a new chat message for routing and persistence.        |
| `0x04` | `EVENT`         | Server $\rightarrow$ Client | Real-time push notification (message, delivery, read receipt). |
| `0x05` | `SYNC`          | Client $\rightarrow$ Server | Requests missed events occurring after a given cursor.         |
| `0x06` | `ACK`           | Server $\rightarrow$ Client | Confirms message persistence to sender (SENT status).          |
| `0x07` | `ACK_DELIVERED` | Client $\rightarrow$ Server | Acknowledges receipt of message(s) by recipient device.        |
| `0x08` | `ACK_READ`      | Client $\rightarrow$ Server | Acknowledges viewing of message(s) by recipient user.          |

---

## 3. Data Types & Primitive Encoding

* **UUID (`16 bytes`):** Encoded as two consecutive 64-bit big-endian integers (Most Significant Bits followed by Least Significant Bits).
* **Timestamp (`8 bytes`):** 64-bit big-endian integer representing milliseconds since Unix epoch (`Instant.toEpochMilli()`).
* **Short String (`2 + N bytes`):** 16-bit big-endian unsigned length prefix ($N \le 65,535$) followed by $N$ UTF-8 encoded bytes.
* **Long String (`4 + N bytes`):** 32-bit big-endian signed length prefix followed by $N$ UTF-8 encoded bytes.

---

## 4. Message Payloads

### 4.1 Connection Setup (`INIT` & `READY`)

#### `INIT` (`0x01`) — Client $\rightarrow$ Server
Sent immediately upon socket connection.
* **Stream ID:** Positive client sequence integer.
* **Payload:**
  * `token`: `Long String` (RS256 JWT string).

#### `READY` (`0x02`) — Server $\rightarrow$ Client
Returned when authentication succeeds.
* **Stream ID:** Echoes client `INIT` Stream ID.
* **Payload:** *Empty (Body Length = 0).*

---

### 4.2 Messaging Lifecycle (`SEND`, `ACK`, `EVENT`)

#### `SEND` (`0x03`) — Client $\rightarrow$ Server
Submits a message to a conversation.
* **Stream ID:** Positive client sequence integer.
* **Payload:**
  * `conversation_id`: `UUID (16 bytes)`
  * `client_timestamp`: `Timestamp (8 bytes)`
  * `payload`: `Long String (4 + N bytes)` (Application payload / JSON metadata).

#### `ACK` (`0x06`) — Server $\rightarrow$ Client (Sent Receipt)
Issued back to the sending socket once the message is durably persisted.
* **Stream ID:** Echoes client `SEND` Stream ID.
* **Payload:**
  * `persisted_event_id`: `UUID (16 bytes)` (Monotonic TimeUUID generated by the cluster).

#### `EVENT` (`0x04`) — Server $\rightarrow$ Client (Real-Time Push)
Pushed to conversation participants when new messages or receipts occur.
* **Stream ID:** `-1` (`0xFFFFFFFF`) for unsolicited fanout events.
* **Payload:**
  * `event_type`: `Short String` (e.g. `MESSAGE_CREATED`, `MESSAGE_DELIVERED`, `MESSAGE_READ`).
  * `event_id`: `UUID (16 bytes)` (Unique TimeUUID).
  * `conversation_id`: `UUID (16 bytes)`.
  * `sender_id`: `UUID (16 bytes)`.
  * `payload`: `Long String`.

---

### 4.3 Delivery & Read Receipts (`ACK_DELIVERED`, `ACK_READ`)

Receipts utilize a unified format supporting both **Cumulative High-Watermarks** and **Explicit Array Batches**.

#### `ACK_DELIVERED` (`0x07`) & `ACK_READ` (`0x08`) — Client $\rightarrow$ Server
* **Stream ID:** Positive client sequence integer.
* **Payload Format:**
  * `conversation_id`: `UUID (16 bytes)`
  * `receipt_mode`: `uint8`
    * `0x00` = `WATERMARK` (Acknowledges all messages up to and including target ID)
    * `0x01` = `EXPLICIT_LIST` (Acknowledges specific discrete message IDs)

**If `receipt_mode == 0x00` (Watermark):**
* `watermark_event_id`: `UUID (16 bytes)`

**If `receipt_mode == 0x01` (Explicit List):**
* `count`: `uint16 (2 bytes)`
* `event_ids`: `count * UUID (count * 16 bytes)`

---

### 4.4 Offline Catch-up (`SYNC`)

#### `SYNC` (`0x05`) — Client $\rightarrow$ Server
Requests all timeline events occurring strictly after a given cursor.
* **Stream ID:** Positive client sequence integer.
* **Payload:**
  * `cursor_event_id`: `UUID (16 bytes)` (TimeUUID of the last known client-side event).

#### Server Response:
The server streams a succession of `EVENT (0x04)` frames stamped with the
request's `streamId`. No separate end-of-stream frame is currently emitted;
the client uses the response stream ID and the application event set to
associate the returned events with the request.

---

### 4.5 Error Handling (`ERROR`)

#### `ERROR` (`0x00`) — Server $\rightarrow$ Client
Indicates protocol violation, authorization failure, or overload.
* **Stream ID:** Echoes client stream ID if known; `-1` if framing is corrupt.
* **Payload:**
  * `error_message`: `Short String` (UTF-8, safe client-facing text).

Errors generated after a known frame preserve that frame's stream ID. Errors
detected before a complete header is available use `-1` when a response can be
written; fatal decoder failures may close the channel without a response.

## 5. Connection, Ordering, and Resource Semantics

1. The server creates one root `SERVER` span named `message.tcp.connection` per
   accepted TCP channel and ends it exactly once when the channel closes.
2. `INIT` must complete successfully before `SEND`, `SYNC`, `ACK_DELIVERED`, or
   `ACK_READ` requests are accepted for that channel.
3. A client may reuse stream IDs only after the corresponding operation has
   completed. The server preserves per-connection route ordering for message
   mutations and receipts.
4. `SEND` and receipt operations may complete asynchronously. Responses are
   associated with the request stream ID; unsolicited fanout uses `-1`.
5. Payload buffers are binary and length-delimited. Clients must not send
   trailing bytes beyond the declared logical payload for a command.
6. The server applies backpressure by disabling Netty `autoRead` when the
   connection's in-flight operation count reaches its high watermark and
   re-enabling it after the low watermark is reached.
7. Connections idle for 300 seconds are closed by the idle reaper. Graceful
   shutdown closes the listener, notifies active clients, closes channels, and
   releases database/cache resources.

## 6. Observability and Privacy

The service exports OTLP gRPC traces to the configured Collector and exposes
Prometheus metrics from the embedded endpoint. The principal business metrics
are:

* `message_received_total{type}`
* `message_processing_errors_total{type,error_code}`
* `message_active_connections`
* `message_cassandra_operation_duration_seconds{operation}`
* `message_redis_operation_duration_seconds{operation}`

Label values are fixed protocol or error categories. Persistence spans use only
fixed database system, operation, and repository attributes. JSON logs include
the service, level, logger, thread, and optional trace/span IDs. Trace IDs are
stored as Loki structured metadata rather than labels.

JWTs, credentials, usernames, email addresses, message bodies, conversation IDs,
message IDs, IP addresses, Redis keys/values, raw CQL values, and arbitrary
exception messages must not appear in logs, metric labels, or span attributes.