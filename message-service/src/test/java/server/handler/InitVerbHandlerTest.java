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

import static org.mockito.Mockito.mock;

import database.ConversationMemberRepository;
import database.EventRepository;
import database.UserEventRepository;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.UUID;
import jwt.JwtUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import server.ConnectionTracker;
import server.MessageRouter;
import server.MessageServer;
import server.Session;
import server.TestKeyManager;

class InitVerbHandlerTest {

    private static final String NODE_ID = "node_id_random";
    private static KeyPair keyPair;

    private EmbeddedChannel channel;
    private Session session;
    private ConnectionTracker connectionTracker;

    private EventRepository eventRepository;
    private ConversationMemberRepository memberRepository;
    private UserEventRepository userEventRepository;

    @BeforeAll
    static void initJwtKey() throws Exception {
        keyPair = TestKeyManager.getKeyPair();

        String publicKeyBase64 =
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        JwtUtil.getInstance().init(publicKeyBase64);
    }

    @BeforeEach
    void setUp() {
        channel = new EmbeddedChannel(DefaultChannelId.newInstance());

        session = new Session();

        connectionTracker = new ConnectionTracker(NODE_ID);

        eventRepository = mock(EventRepository.class);
        memberRepository = mock(ConversationMemberRepository.class);
        userEventRepository = mock(database.UserEventRepository.class);

        channel.attr(MessageServer.MessageServerInitializer.SESSION_KEY).set(session);

        MessageRouter messageRouter =
                new MessageRouter(
                        connectionTracker, eventRepository, memberRepository, userEventRepository);

        channel.pipeline().addLast(new InitVerbHandler(connectionTracker, messageRouter));
    }

    @Test
    public void successOnValidJwtToken() throws Exception {
        UUID expectedUserId = UUID.randomUUID();

        String validJwt =
                createJwtToken("{\"sub\":\"" + expectedUserId + "\"}", keyPair.getPrivate());

        channel.writeInbound("INIT " + validJwt);

        Assertions.assertEquals("SUCCESS", channel.readOutbound());

        Assertions.assertEquals(expectedUserId, session.getUserId());

        Assertions.assertTrue(channel.isOpen());

        Assertions.assertEquals(channel, connectionTracker.get(expectedUserId));
    }

    @Test
    public void failOnInvalidJwtSignature() throws Exception {
        KeyPair wrongKeyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();

        String invalidJwt = createJwtToken("{\"sub\":\"user123\"}", wrongKeyPair.getPrivate());

        channel.writeInbound("INIT " + invalidJwt);

        Assertions.assertEquals("INVALID", channel.readOutbound());

        Assertions.assertNull(session.getUserId());

        Assertions.assertFalse(channel.isOpen());

        Assertions.assertEquals(0, connectionTracker.getSize());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "INIT",
                "INIT ",
                "START abc",
                "init abc",
                "FOO",
                "",
                "   ",
                "INIT malformed.jwt.token"
            })
    public void failOnIncorrectFormatOrBadToken(String input) {

        channel.writeInbound(input);

        Assertions.assertEquals("INVALID", channel.readOutbound());

        Assertions.assertNull(session.getUserId());

        Assertions.assertFalse(channel.isOpen());

        Assertions.assertEquals(0, connectionTracker.getSize());
    }

    private static String createJwtToken(String jsonPayload, PrivateKey privateKey)
            throws Exception {

        String header =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                "{\"alg\":\"RS256\",\"typ\":\"JWT\"}"
                                        .getBytes(StandardCharsets.UTF_8));

        String payload =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(jsonPayload.getBytes(StandardCharsets.UTF_8));

        String contentToSign = header + "." + payload;

        Signature signature = Signature.getInstance("SHA256withRSA");

        signature.initSign(privateKey);

        signature.update(contentToSign.getBytes(StandardCharsets.UTF_8));

        String sigBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());

        return contentToSign + "." + sigBase64;
    }
}
