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
		Assertions.assertEquals("FROM alice SGVsbG8=\n", recipientChannel.readOutbound());
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
		Assertions.assertEquals("FROM alice U2VsZi1tZXNzYWdl\n", aliceChannel.readOutbound());
	}
}
