package server;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MessageServerInitializerTest {

    private EmbeddedChannel channel = new EmbeddedChannel();

    @BeforeEach
    public void setUp() {
        channel = new EmbeddedChannel(new MessageServer.MessageServerInitializer(new ConnectionTracker()));
    }

    @Test
    public void testChannelInitialPipeline() {
        Assertions.assertNotNull(channel.pipeline().get("stringDecoder"));
        Assertions.assertNotNull(channel.pipeline().get("initVerbHandler"));
        Assertions.assertNull(channel.pipeline().get("chatMessageHandler"));
    }

    @Test
    public void testChannelPipelineAfterInit() {
        channel.writeInbound("INIT user123");
        Assertions.assertNotNull(channel.pipeline().get("stringDecoder"));
        Assertions.assertNotNull(channel.pipeline().get("chatMessageHandler"));
        Assertions.assertNull(channel.pipeline().get("initVerbHandler"));
    }
}
