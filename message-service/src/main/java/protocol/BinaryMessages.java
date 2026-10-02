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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import server.model.Event;
import server.model.EventType;

public final class BinaryMessages {

    private BinaryMessages() {}

    public enum ReceiptMode {
        WATERMARK((byte) 0x00),
        EXPLICIT_LIST((byte) 0x01);

        private final byte code;

        ReceiptMode(byte code) {
            this.code = code;
        }

        public static ReceiptMode fromByte(byte b) {
            return b == 0x00 ? WATERMARK : EXPLICIT_LIST;
        }

        public byte getCode() {
            return code;
        }
    }

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

    public record ReceiptRequest(
            UUID conversationId,
            ReceiptMode mode,
            UUID watermarkEventId,
            List<UUID> explicitEventIds) {

        public static ReceiptRequest watermark(UUID conversationId, UUID watermarkEventId) {
            return new ReceiptRequest(
                    conversationId,
                    ReceiptMode.WATERMARK,
                    watermarkEventId,
                    Collections.emptyList());
        }

        public static ReceiptRequest explicit(UUID conversationId, List<UUID> eventIds) {
            return new ReceiptRequest(conversationId, ReceiptMode.EXPLICIT_LIST, null, eventIds);
        }

        public static ReceiptRequest decode(ByteBuf body) {
            UUID convId = BufferUtil.readUUID(body);
            ReceiptMode mode = ReceiptMode.fromByte(body.readByte());

            if (mode == ReceiptMode.WATERMARK) {
                UUID watermark = BufferUtil.readUUID(body);
                return new ReceiptRequest(convId, mode, watermark, Collections.emptyList());
            } else {
                int count = body.readUnsignedShort();
                List<UUID> ids = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    ids.add(BufferUtil.readUUID(body));
                }
                return new ReceiptRequest(convId, mode, null, ids);
            }
        }

        public static void encode(ReceiptRequest req, ByteBuf dest) {
            BufferUtil.writeUUID(req.conversationId(), dest);
            dest.writeByte(req.mode().code);

            if (req.mode() == ReceiptMode.WATERMARK) {
                BufferUtil.writeUUID(req.watermarkEventId(), dest);
            } else {
                dest.writeShort(req.explicitEventIds().size());
                for (UUID id : req.explicitEventIds()) {
                    BufferUtil.writeUUID(id, dest);
                }
            }
        }
    }

    public static Envelope createReadyResponse(int streamId) {
        return Envelope.createEmpty(Opcode.READY, streamId);
    }

    /** Server ACK: Sent back to sender when message is saved to Cassandra (1st check / SENT). */
    public static Envelope createAckResponse(int streamId, UUID eventId, ByteBufAllocator alloc) {
        ByteBuf body = alloc.buffer(16);
        BufferUtil.writeUUID(eventId, body);
        return Envelope.create(Opcode.ACK, streamId, body);
    }

    public static UUID decodeAckResponse(ByteBuf body) {
        return BufferUtil.readUUID(body);
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
