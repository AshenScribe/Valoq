package server.handler;

import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
        Assertions.assertEquals("SUCCESS\n", channel.readOutbound());
        Assertions.assertEquals(expectedUserId, session.getUserId());
        Assertions.assertTrue(channel.isOpen());
        Assertions.assertEquals(channel, connectionTracker.get(expectedUserId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INIT", "INIT ", "START abc", "init abc", "INIT a b c", "FOO", "", "   ", "INIT \t abc"})
    public void failOnIncorrect(String input) {
        channel.writeInbound(input);
        Assertions.assertEquals("INVALID\n", channel.readOutbound());
        Assertions.assertNull(session.getUserId());
        Assertions.assertFalse(channel.isOpen());
        Assertions.assertEquals(0, connectionTracker.getSize());
    }

    @Test
    public void throwsExceptionWhenSessionAttributeIsMissing() {
        EmbeddedChannel uninitializedChannel = new EmbeddedChannel(new InitVerbHandler(connectionTracker));
        Assertions.assertThrows(IllegalStateException.class, () -> uninitializedChannel.writeInbound("INIT user123"));
    }
}
