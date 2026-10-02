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
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

@ChannelHandler.Sharable
public final class EnvelopeEncoder extends MessageToByteEncoder<Envelope> {

    public static final EnvelopeEncoder INSTANCE = new EnvelopeEncoder();

    private EnvelopeEncoder() {}

    @Override
    protected void encode(ChannelHandlerContext ctx, Envelope msg, ByteBuf out) {
        out.writeByte(msg.getHeader().magic());
        out.writeByte(msg.getHeader().version());
        out.writeByte(msg.getHeader().opcode().getCode());
        out.writeByte(msg.getHeader().flags());
        out.writeInt(msg.getHeader().streamId());
        out.writeInt(msg.getHeader().bodyLength());
        if (msg.getBody().isReadable()) {
            out.writeBytes(msg.getBody());
        }
        msg.release();
    }
}
