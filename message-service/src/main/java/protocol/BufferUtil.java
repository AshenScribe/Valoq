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
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class BufferUtil {

    private BufferUtil() {}

    public static void writeUUID(UUID uuid, ByteBuf dest) {
        dest.writeLong(uuid.getMostSignificantBits());
        dest.writeLong(uuid.getLeastSignificantBits());
    }

    public static UUID readUUID(ByteBuf src) {
        long msb = src.readLong();
        long lsb = src.readLong();
        return new UUID(msb, lsb);
    }

    public static void writeString(String str, ByteBuf dest) {
        if (str == null) {
            dest.writeShort(0);
            return;
        }
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        dest.writeShort(bytes.length);
        dest.writeBytes(bytes);
    }

    public static String readString(ByteBuf src) {
        int length = src.readUnsignedShort();
        if (length == 0) {
            return "";
        }
        byte[] bytes = new byte[length];
        src.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void writeLongString(String str, ByteBuf dest) {
        if (str == null) {
            dest.writeInt(0);
            return;
        }
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        dest.writeInt(bytes.length);
        dest.writeBytes(bytes);
    }

    public static String readLongString(ByteBuf src) {
        int length = src.readInt();
        if (length == 0) {
            return "";
        }
        byte[] bytes = new byte[length];
        src.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
