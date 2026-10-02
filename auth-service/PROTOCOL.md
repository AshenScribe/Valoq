# Valoq Authentication Protocol (VAP) Specification

**Version:** 1.0  
**Status:** Production / Stable  
**Transport:** TCP / optional TLS  
**Framing:** Line-Based (`\n` or `\r\n` Delimited via `LineBasedFrameDecoder`)  
**Encoding:** US-ASCII / UTF-8  
**Max Frame Length:** 8,192 bytes (Configurable via `server.maxFrameLength`)

---

## 1. Protocol Architecture & Connection Lifecycle

The Valoq Authentication Protocol operates over a persistent TCP connection established between an authenticating client and the `AuthServer`.

```
[ Client ] ──────── (1. TCP Connect) ───────► [ AuthServer ]
           ◄─────── (2. Connection Active) ───── (ConnectionLimitHandler checks global/IP quotas)
           ──────── (3. AUTH / PUBLIC_KEY) ───► [ CommandDecoder ]
           ◄─────── (4. Response / ERROR) ────── [ AuthenticationHandler / InboundExceptionHandler ]
```

### 1.1 Connection Limits & DoS Protection
* **Global Maximum Connections:** Capped at 5,000 active sockets by default. Connections exceeding the threshold are rejected immediately upon `channelActive`.
* **Per-IP Connection Quota:** Capped at 50 concurrent sockets per remote IP address to prevent single-source connection flooding.
* **Idle Connection Reaper:** Connections that remain completely idle (no incoming bytes) for longer than 60 seconds (`idleTimeoutSeconds`) are automatically reaped and closed to prevent file descriptor leakage.

### 1.2 Backpressure & Rate Limiting
* **Single-In-Flight Invariant:** Authentication commands are processed sequentially per socket. Upon reading an `AUTH` command, the server executes `setAutoRead(false)` at the transport layer, forcing the client's TCP window to lock until the response is fully written and flushed back.
* **Database Pool Bulkheading:** Inbound authentication requests are gated by a fixed concurrency semaphore (matching the HikariCP pool size of 10). If the database pool is saturated, requests wait up to 2 seconds before shedding load with an explicit `ERROR Server overloaded, please retry later` response.

---

## 2. Command Set & Wire Formats

All requests must be sent as a single line of text terminated by a newline (`\n`).

### 2.1 Public Key Discovery (`PUBLIC_KEY`)
Used by internal gateway nodes or test clients to fetch the RSA public verification key for external JWT validation.

* **Request Format:**
  ```text
  PUBLIC_KEY\n
  ```
* **Success Response:**
  ```text
  <Base64-Encoded-X.509-RSA-Public-Key>\n
  ```

---

### 2.2 Basic Credentials Authentication (`AUTH BASIC`)
Authenticates an existing user account using a pre-hashed password and salt.

* **Request Format:**
  ```text
  AUTH BASIC <username>:<password_hash>:<salt>\n
  ```
* **Parameters:**
    * `username`: Alphanumeric string.
    * `password_hash`: Secure string representing the client-side hashed password (may contain colons; parsed via first/last colon boundaries).
    * `salt`: User-specific cryptographic salt.
* **Success Response:**
  ```text
  <RS256-Signed-JWT>\n
  ```
* **Failure Response:**
  ```text
  ERROR User with username <username> not found or password does not match\n
  ```

---

### 2.3 Token Renewal / Verification (`AUTH TOKEN`)
Verifies an active or expired JWT bearer token and issues a renewed token if valid.

* **Request Format:**
  ```text
  AUTH TOKEN <jwt_string>\n
  ```
* **Success Response:**
  ```text
  <Valid-Or-Renewed-RS256-JWT>\n
  ```
* **Failure Response:**
  ```text
  ERROR <error_description>\n
  ```

---

### 2.4 Account Registration (`AUTH REGISTER`)
Registers a new user record in the relational store and immediately issues an authenticated session token.

* **Request Format:**
  ```text
  AUTH REGISTER <base64_json_payload>\n
  ```
* **Payload Format (Raw JSON before Base64 encoding):**
  ```json
  {
    "username": "alice",
    "password": "SecurePassword123!",
    "salt": "rand_salt_998",
    "email": "alice@valoq.app"
  }
  ```
* **Validation Rules:**
    * `username`: Length 3–32 characters; matches `^[a-zA-Z0-9_]+$`.
    * `password`: Length 8–64 characters; must contain at least one uppercase letter, one lowercase letter, one number, and one special character (`[!@#$%^&*()-_+=<>?]`).
* **Success Response:**
  ```text
  <RS256-Signed-JWT>\n
  ```
* **Failure Response:**
  ```text
  ERROR <validation_error_message>\n
  ```

---

## 3. Error Handling & Exception Routing

Uncaught exceptions and validation failures are handled uniformly by `InboundExceptionHandler`, following the exception-routing pattern of high-throughput distributed systems:

* **Response Format:**
  ```text
  ERROR <client_safe_message>\n
  ```

### 3.1 Exception Mapping Table

| Root Exception Class                                | Wire Error Output                                    | Channel Action       | Description                                                                                                                                          |
|:----------------------------------------------------|:-----------------------------------------------------|:---------------------|:-----------------------------------------------------------------------------------------------------------------------------------------------------|
| `ValidationException`                               | `ERROR <message>`                                    | **Kept Open**        | Client sent input failing business validation rules.                                                                                                 |
| `UserNotFoundException`                             | `ERROR <message>`                                    | **Kept Open**        | Authentication failed (bad credentials or missing user).                                                                                             |
| `DecoderException`                                  | `ERROR <message>`                                    | **Kept Open**        | Malformed command syntax or invalid Base64 JSON.                                                                                                     |
| `IllegalStateException` (Overload)                  | `ERROR Server overloaded, please retry later`        | **Kept Open**        | Database concurrency pool saturated (load shedding).                                                                                                 |
| `TooLongFrameException`                             | `ERROR Command exceeds maximum allowed frame length` | **Closed (`CLOSE`)** | Fatal framing violation; input exceeded `maxFrameLength`.                                                                                            |
| `CorruptedFrameException`                           | `ERROR Corrupted frame received: <msg>`              | **Closed (`CLOSE`)** | Fatal stream disruption.                                                                                                                             |
| `NullPointerException`, `SQLException` (Unexpected) | `ERROR Internal server error`                        | **Kept Open**        | Unexpected server-side fault; full stack traces are logged locally via SLF4J with suppressed wire output to protect internal implementation details. |
| `IOException`                                       | *None*                                               | **Closed (`CLOSE`)** | Client abruptly reset or closed the connection; handled at DEBUG level without writing to wire.                                                      |

---

## 4. Cryptographic & Security Constraints

* **Algorithm:** RSA Signature with SHA-256 (`RS256`).
* **Timing Attack Defense:** Basic authentication credentials are validated using constant-time byte comparisons (`MessageDigest.isEqual(...)`) to prevent side-channel timing analysis.
* **Stateless Token Verification:** Issued tokens contain standard JWT claims (`sub` as the generated `usr_<hex>` identifier, `iat`, `exp`) and are verified statelessly against the immutable public key loaded via `KeyProvider`.