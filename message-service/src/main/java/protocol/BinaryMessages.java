/*
 * MIT License
 *
 * Copyright (c) 2026 Valoq
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import java.time.Instant;
import java.util.UUID;
import server.model.Event;
import server.model.EventType;

public final class BinaryMessages {

    private BinaryMessages() {}

    public record InitRequest(String token) {
        public static InitRequest decode(ByteBuf body) {
            return new InitRequest(BufferUtil.readLongString(body));
        }
    }

    public record SendRequest(UUID conversationId, Instant timestamp, String payload) {
        public static SendRequest decode(ByteBuf body) {
            UUID convId = BufferUtil.readUUID(body);
            long epochMillis = body.readLong();
            String payload = BufferUtil.readLongString(body);
            return new SendRequest(convId, Instant.ofEpochMilli(epochMillis), payload);
        }

        public static void encode(SendRequest req, ByteBuf dest) {
            BufferUtil.writeUUID(req.conversationId(), dest);
            dest.writeLong(req.timestamp().toEpochMilli());
            BufferUtil.writeLongString(req.payload(), dest);
        }
    }

    public record SyncRequest(UUID cursorEventId) {
        public static SyncRequest decode(ByteBuf body) {
            return new SyncRequest(BufferUtil.readUUID(body));
        }
    }

    public static Envelope createReadyResponse(int streamId) {
        return Envelope.createEmpty(Opcode.READY, streamId);
    }

    public static Envelope createErrorResponse(
            int streamId, String errorMessage, ByteBufAllocator alloc) {
        ByteBuf body = alloc.buffer();
        BufferUtil.writeString(errorMessage, body);
        return Envelope.create(Opcode.ERROR, streamId, body);
    }

    public static Envelope createEventEnvelope(Event event, int streamId, ByteBufAllocator alloc) {
        ByteBuf body = alloc.buffer();
        BufferUtil.writeString(event.eventType().name(), body);
        BufferUtil.writeUUID(event.eventId(), body);
        BufferUtil.writeUUID(event.conversationId(), body);
        BufferUtil.writeUUID(event.senderId(), body);
        BufferUtil.writeLongString(event.payload(), body);

        return Envelope.create(Opcode.EVENT, streamId, body);
    }

    public static Event decodeEvent(ByteBuf body) {
        String eventTypeName = BufferUtil.readString(body);
        UUID eventId = BufferUtil.readUUID(body);
        UUID convId = BufferUtil.readUUID(body);
        UUID senderId = BufferUtil.readUUID(body);
        String payload = BufferUtil.readLongString(body);
        long epochMilli = com.datastax.oss.driver.api.core.uuid.Uuids.unixTimestamp(eventId);

        return new Event(
                senderId,
                convId,
                eventId,
                EventType.valueOf(eventTypeName),
                payload,
                Instant.ofEpochMilli(epochMilli));
    }
}
