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
package server.codec;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.command.BasicCommand;
import server.command.RegisterCommand;
import server.command.TokenCommand;

class CommandDecoderTest {

    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        channel = new EmbeddedChannel(new CommandDecoder());
    }

    @Test
    @DisplayName("Should successfully parse BASIC command and preserve colons in password")
    void testBasicCommandWithColonInPassword() {
        channel.writeInbound("AUTH BASIC alice:p@ss:w:ord:salt123");

        BasicCommand cmd = channel.readInbound();
        Assertions.assertNotNull(cmd);
        Assertions.assertEquals("alice", cmd.username());
        Assertions.assertEquals("p@ss:w:ord", cmd.password());
        Assertions.assertEquals("salt123", cmd.salt());
    }

    @Test
    @DisplayName("Should throw DecoderException when BASIC command has missing parts")
    void testBasicCommandWithMissingPartsThrowsDecoderException() {
        Assertions.assertThrows(
                DecoderException.class, () -> channel.writeInbound("AUTH BASIC only_one_part"));
    }

    @Test
    @DisplayName("Should successfully parse valid TOKEN command")
    void testTokenCommandValid() {
        channel.writeInbound("AUTH TOKEN eyJhbGciOi...");

        TokenCommand cmd = channel.readInbound();
        Assertions.assertNotNull(cmd);
        Assertions.assertEquals("eyJhbGciOi...", cmd.token());
    }

    @Test
    @DisplayName("Should throw DecoderException when TOKEN payload is blank")
    void testTokenCommandBlankThrowsDecoderException() {
        Assertions.assertThrows(DecoderException.class, () -> channel.writeInbound("AUTH TOKEN "));
    }

    @Test
    @DisplayName("Should successfully parse valid Base64 REGISTER JSON payload")
    void testRegisterCommandValid() {
        String json =
                "{\"username\":\"bob\",\"password\":\"Secret123!\",\"salt\":\"s1\",\"email\":\"b@b.com\"}";
        String b64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));

        channel.writeInbound("AUTH REGISTER " + b64);
        RegisterCommand cmd = channel.readInbound();
        Assertions.assertNotNull(cmd);
        Assertions.assertEquals("bob", cmd.username());
        Assertions.assertEquals("Secret123!", cmd.password());
        Assertions.assertEquals("s1", cmd.salt());
        Assertions.assertEquals("b@b.com", cmd.email());
    }

    @Test
    @DisplayName("Should throw DecoderException when REGISTER payload contains invalid Base64")
    void testRegisterCommandInvalidBase64ThrowsDecoderException() {
        Assertions.assertThrows(
                DecoderException.class,
                () -> channel.writeInbound("AUTH REGISTER not_valid_base64!!!"));
    }

    @Test
    @DisplayName("Should throw DecoderException when command does not start with AUTH")
    void testMalformedCommandPrefixThrowsDecoderException() {
        Assertions.assertThrows(
                DecoderException.class, () -> channel.writeInbound("LOGIN BASIC a:b:c"));
    }

    @Test
    @DisplayName("Should ignore empty lines and keep-alive pings without error")
    void testEmptyLineIgnored() {
        channel.writeInbound("   ");
        Object read = channel.readInbound();
        Assertions.assertNull(read);
    }
}
