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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.util.Base64;
import java.util.Objects;

public class Envelope {

    public static final byte MAGIC = 0x56; // 'V'
    public static final byte VERSION = 0x01;
    public static final int HEADER_LENGTH = 12;
    public static final int EVENT_STREAM_ID = -1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Header header;
    private final ByteBuf body;

    static {
        SimpleModule module = new SimpleModule();
        module.addSerializer(ByteBuf.class, new ByteBufSerializer());
        module.addDeserializer(ByteBuf.class, new ByteBufDeserializer());
        MAPPER.registerModule(module);
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @JsonCreator
    public Envelope(@JsonProperty("header") Header header, @JsonProperty("body") ByteBuf body) {
        this.header = Objects.requireNonNull(header);
        this.body = Objects.requireNonNull(body);
    }

    public Header getHeader() {
        return header;
    }

    public ByteBuf getBody() {
        return body;
    }

    public static Envelope create(Opcode opcode, int streamId, ByteBuf body) {
        Header header =
                new Header(MAGIC, VERSION, opcode, (byte) 0, streamId, body.readableBytes());
        return new Envelope(header, body);
    }

    public static Envelope createEmpty(Opcode opcode, int streamId) {
        return create(opcode, streamId, Unpooled.EMPTY_BUFFER);
    }

    /**
     * Retains the body buffer so the envelope can be safely broadcast to multiple Netty channels.
     */
    public Envelope retain() {
        body.retain();
        return this;
    }

    /**
     * Creates a duplicate of this envelope sharing the body slice with an incremented ref count.
     */
    public Envelope duplicate() {
        return new Envelope(header, body.retainedSlice());
    }

    public static Envelope fromMessage(String jsonMessage) {
        try {
            return MAPPER.readValue(jsonMessage, Envelope.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize Envelope", e);
        }
    }

    @Override
    public String toString() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize Envelope", e);
        }
    }

    /**
     * Decrements the reference count of the underlying Netty {@link io.netty.buffer.ByteBuf} body,
     * deallocating its memory pool allocations if the reference count reaches zero.
     *
     * @see io.netty.buffer.ByteBuf#refCnt()
     * @see io.netty.buffer.ByteBuf#release()
     */
    public void release() {
        if (body.refCnt() > 0) {
            body.release();
        }
    }

    public record Header(
            byte magic, byte version, Opcode opcode, byte flags, int streamId, int bodyLength) {}

    private static final class ByteBufSerializer extends JsonSerializer<ByteBuf> {
        @Override
        public void serialize(ByteBuf value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            if (value == null || !value.isReadable()) {
                gen.writeString("");
                return;
            }
            byte[] bytes = new byte[value.readableBytes()];
            value.getBytes(value.readerIndex(), bytes);
            gen.writeString(Base64.getEncoder().encodeToString(bytes));
        }
    }

    private static final class ByteBufDeserializer extends JsonDeserializer<ByteBuf> {

        @Override
        public ByteBuf deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String base64 = p.getValueAsString();
            if (base64 == null || base64.isEmpty()) {
                return Unpooled.EMPTY_BUFFER;
            }
            byte[] bytes = Base64.getDecoder().decode(base64);
            return Unpooled.wrappedBuffer(bytes);
        }

        @Override
        public ByteBuf getNullValue(DeserializationContext ctxt) {
            return Unpooled.EMPTY_BUFFER;
        }
    }

    public static ObjectMapper getMapper() {
        return MAPPER;
    }
}
