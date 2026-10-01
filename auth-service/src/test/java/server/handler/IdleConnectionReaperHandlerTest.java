package server.handler;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class IdleConnectionReaperHandlerTest {

    @Test
    void testIdleStateEventClosesChannel() {
        EmbeddedChannel channel = new EmbeddedChannel(IdleConnectionReaperHandler.INSTANCE);
        Assertions.assertTrue(channel.isOpen());

        channel.pipeline().fireUserEventTriggered(IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT);

        Assertions.assertFalse(channel.isOpen(), "Channel must be closed after idle state event");
    }

    @Test
    void testNonIdleUserEventDoesNotCloseChannel() {
        EmbeddedChannel channel = new EmbeddedChannel(IdleConnectionReaperHandler.INSTANCE);
        Assertions.assertTrue(channel.isOpen());

        channel.pipeline().fireUserEventTriggered("CUSTOM_USER_EVENT");

        Assertions.assertTrue(channel.isOpen(), "Channel should stay open on unrelated events");
    }
}