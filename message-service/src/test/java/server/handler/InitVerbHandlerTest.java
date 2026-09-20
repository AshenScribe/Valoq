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
package server.handler;

import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import server.ConnectionTracker;
import server.MessageServer;
import server.Session;

class InitVerbHandlerTest {

    private EmbeddedChannel channel;
    private Session session;
    private final ConnectionTracker connectionTracker = new ConnectionTracker();

    @BeforeEach
    void setUp() {
        channel = new EmbeddedChannel(DefaultChannelId.newInstance());
        session = new Session();
        channel.attr(MessageServer.MessageServerInitializer.SESSION_KEY).set(session);
        channel.pipeline().addLast(new InitVerbHandler(connectionTracker));
    }

    @ParameterizedTest
    @CsvSource({
        "INIT abc, abc",
        "INIT user123, user123",
        "INIT 12345, 12345",
        "INIT user_1, user_1",
        "'INIT user-with-dashes', user-with-dashes",
        "'INIT abc\r\n', abc"
    })
    public void successOnCorrect(String input, String expectedUserId) {
        channel.writeInbound(input);
        Assertions.assertEquals("SUCCESS", channel.readOutbound()); // <-- No \n
        Assertions.assertEquals(expectedUserId, session.getUserId());
        Assertions.assertTrue(channel.isOpen());
        Assertions.assertEquals(channel, connectionTracker.get(expectedUserId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INIT", "INIT ", "START abc", "init abc", "INIT a b c", "FOO", "", "   ", "INIT \t abc"})
    public void failOnIncorrect(String input) {
        channel.writeInbound(input);
        Assertions.assertEquals("INVALID", channel.readOutbound()); // <-- No \n
        Assertions.assertNull(session.getUserId());
        Assertions.assertFalse(channel.isOpen());
        Assertions.assertEquals(0, connectionTracker.getSize());
    }
}
