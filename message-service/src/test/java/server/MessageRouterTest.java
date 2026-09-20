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
package server;

import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MessageRouterTest {

    private ConnectionTracker connectionTracker;
    private MessageRouter messageRouter;

    @BeforeEach
    void setUp() {
        connectionTracker = new ConnectionTracker();
        messageRouter = new MessageRouter(connectionTracker);
    }

    @Test
    void testRouteSuccess() {
        EmbeddedChannel recipientChannel = new EmbeddedChannel(DefaultChannelId.newInstance());
        connectionTracker.register("bob", recipientChannel);
        boolean result = messageRouter.route("alice", "bob", "SGVsbG8=");
        Assertions.assertTrue(result);
        Assertions.assertEquals("FROM alice SGVsbG8=", recipientChannel.readOutbound());
    }

    @Test
    void testRouteFailureRecipientNotConnected() {
        boolean result = messageRouter.route("alice", "bob", "SGVsbG8=");
        Assertions.assertFalse(result);
        Assertions.assertNull(connectionTracker.get("bob"));
    }

    @Test
    void testRouteSelfMessage() {
        EmbeddedChannel aliceChannel = new EmbeddedChannel(DefaultChannelId.newInstance());
        connectionTracker.register("alice", aliceChannel);
        boolean result = messageRouter.route("alice", "alice", "U2VsZi1tZXNzYWdl");
        Assertions.assertTrue(result);
        Assertions.assertEquals("FROM alice U2VsZi1tZXNzYWdl", aliceChannel.readOutbound());
    }
}
