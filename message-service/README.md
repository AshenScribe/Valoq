### V1 Message Protocol Specification

This protocol facilitates simple authenticated messaging between clients connected to a central server. All communications are line-based (terminated by `\n`).

#### 1. Connection Lifecycle
*   **Initialization:** Clients must issue the `INIT` command immediately upon connecting.
*   **Authentication:** The server treats the connection as unauthenticated until a valid `INIT` is processed.
*   **Termination:** If a client sends an invalid command or fails authentication, the server sends `INVALID\n` and terminates the connection.

#### 2. Commands

**`INIT <userId>`**
*   Registers the connection with the specified `userId`.
*   **Success:** Returns `SUCCESS\n`.
*   **Failure:** Returns `INVALID\n` and closes the connection.

**`SEND <recipientId> <base64Payload>`**
*   Routes a message to the specified recipient.
*   **Success:** The recipient receives `FROM <senderId> <base64Payload>\n`.
*   **Failure:** Returns `ERROR <reason>\n`.

---

### Answers to Your Protocol Questions

*   **Can `INIT` happen twice?**
    *   **No.** Once `INIT` is successfully processed, the `InitVerbHandler` replaces itself with the `ChatMessageHandler` in the pipeline. Subsequent `INIT` commands will not be processed by the same handler; they will be handled by the chat logic, which will reject them as "Invalid SEND format" (or similar), eventually causing the connection to close.

*   **Can `SEND` happen before `INIT`?**
    *   **No.** The pipeline is designed such that `ChatMessageHandler` is only added after a successful `INIT`. If a client attempts to `SEND` before `INIT`, the server is still running `InitVerbHandler`, which will reject the `SEND` command as `INVALID` and close the connection.

*   **What happens with duplicate user IDs?**
    *   **Last-Writer-Wins.** When a new connection performs an `INIT` with an existing `userId`, the `ConnectionTracker` updates the mapping to point to the new `Channel`. The old connection remains open (but orphaned), and the new connection becomes the active recipient for that `userId`.

*   **Maximum user ID length?**
    *   **64 characters.** As enforced by the `SEND` pattern regex `[^\\s]{1,64}`.

*   **Is payload maximum length?**
    *   **The protocol does not explicitly define a payload length limit.** However, the pipeline uses a `LineBasedFrameDecoder` with a max frame length of **1024 bytes**. Any payload (including the `SEND` command prefix) exceeding 1024 bytes will result in a `TooLongFrameException` (default Netty behavior), causing the connection to close.

*   **What characters are legal in IDs?**
    *   **Non-whitespace characters.** The regex `\S` (or `[^\\s]`) means any character that is not a space, tab, carriage return, or newline.

*   **Is Base64 standard or URL-safe?**
    *   **Standard Base64.** The current implementation does not perform custom decoding, so standard Base64 (including `=` padding characters) is expected.

*   **What does oversized input do?**
    *   **Connection Closure.** Input exceeding the `LineBasedFrameDecoder` limit (1024 bytes) will cause the decoder to throw an exception, which typically results in the connection being closed by the server.
