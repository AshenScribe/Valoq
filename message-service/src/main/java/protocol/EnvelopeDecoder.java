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
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

public final class EnvelopeDecoder extends LengthFieldBasedFrameDecoder {

    public EnvelopeDecoder(int maxFrameLength) {
        // maxFrameLength, lengthFieldOffset=8, lengthFieldLength=4, lengthAdjustment=0,
        // initialBytesToStrip=0
        super(maxFrameLength, 8, 4, 0, 0);
    }

    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        ByteBuf frame = (ByteBuf) super.decode(ctx, in);
        if (frame == null) {
            return null;
        }

        try {
            byte magic = frame.readByte();
            if (magic != Envelope.MAGIC) {
                throw new IllegalArgumentException(
                        String.format(
                                "Invalid magic byte: 0x%02X, expected 0x%02X",
                                magic, Envelope.MAGIC));
            }

            byte version = frame.readByte();
            Opcode opcode = Opcode.fromByte(frame.readByte());
            byte flags = frame.readByte();
            int streamId = frame.readInt();
            int bodyLength = frame.readInt();

            ByteBuf body = frame.readRetainedSlice(bodyLength);
            Envelope.Header header =
                    new Envelope.Header(magic, version, opcode, flags, streamId, bodyLength);
            return new Envelope(header, body);
        } finally {
            frame.release();
        }
    }
}
