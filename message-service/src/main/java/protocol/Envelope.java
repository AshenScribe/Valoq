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
import io.netty.buffer.Unpooled;
import java.util.Objects;

public class Envelope {

    public static final byte MAGIC = 0x56; // 'V'
    public static final byte VERSION = 0x01;
    public static final int HEADER_LENGTH = 12;
    public static final int EVENT_STREAM_ID = -1;

    private final Header header;
    private final ByteBuf body;

    public Envelope(Header header, ByteBuf body) {
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
}
