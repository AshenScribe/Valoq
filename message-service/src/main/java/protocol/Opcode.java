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
import com.fasterxml.jackson.annotation.JsonValue;

public enum Opcode {
    ERROR((byte) 0x00),
    INIT((byte) 0x01),
    READY((byte) 0x02),
    SEND((byte) 0x03),
    EVENT((byte) 0x04),
    SYNC((byte) 0x05),
    ACK((byte) 0x06),
    ACK_DELIVERED((byte) 0x07),
    ACK_READ((byte) 0x08);

    private static final Opcode[] LOOKUP;

    static {
        int maxOpcode = -1;
        for (Opcode op : values()) {
            if (op.code > maxOpcode) {
                maxOpcode = op.code;
            }
        }
        LOOKUP = new Opcode[maxOpcode + 1];
        for (Opcode op : values()) {
            LOOKUP[op.code] = op;
        }
    }

    private final byte code;

    Opcode(byte code) {
        this.code = code;
    }

    @JsonCreator
    public static Opcode fromByte(byte b) {
        if (b < 0 || b >= LOOKUP.length || LOOKUP[b] == null) {
            throw new IllegalArgumentException("Unknown opcode: " + b);
        }
        return LOOKUP[b];
    }

    @JsonValue
    public byte getCode() {
        return code;
    }
}
