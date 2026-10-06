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
package cache;

import base.BaseIntegrationTest;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RedisRepository E2E Tests")
class RedisRepositoryTest extends BaseIntegrationTest {

    private static final String REDIS_PASSWORD = "testpassword123";
    private static final long TIMEOUT_SECONDS = 5L;

    private RedisRepository redisRepository;
    private final List<String> keysToDelete = new ArrayList<>();

    @BeforeEach
    void setUp() {
        redisRepository = new RedisRepository();
    }

    @AfterEach
    void cleanRedis() throws Exception {
        for (String key : keysToDelete) {
            redisRepository
                    .delete(key)
                    .toCompletableFuture()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        keysToDelete.clear();
    }

    private String uniqueKey(String prefix) {
        String key = "redis-repository-test:" + prefix + ":" + UUID.randomUUID();
        keysToDelete.add(key);
        return key;
    }

    private static String uniqueChannel(String prefix) {
        return "redis-repository-test:" + prefix + ":" + UUID.randomUUID();
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static RedisClient createRedisClient() {
        RedisURI redisUri =
                RedisURI.Builder.redis(
                                REDIS_CONTAINER.getHost(), REDIS_CONTAINER.getFirstMappedPort())
                        .withAuthentication("default", REDIS_PASSWORD)
                        .build();

        return RedisClient.create(redisUri);
    }

    @Nested
    @DisplayName("Write")
    class WriteTests {

        @Test
        @DisplayName("writes a value successfully")
        void writesValue() throws Exception {
            String key = uniqueKey("write");
            String value = "hello";

            String result = await(redisRepository.write(key, value).toCompletableFuture());

            Assertions.assertEquals("OK", result);
        }

        @Test
        @DisplayName("written value can be read back")
        void writeThenRead() throws Exception {
            String key = uniqueKey("write-read");
            String value = "hello redis";

            await(redisRepository.write(key, value).toCompletableFuture());

            String actual = await(redisRepository.read(key).toCompletableFuture());

            Assertions.assertEquals(value, actual);
        }

        @Test
        @DisplayName("writing an existing key overwrites the previous value")
        void overwritesExistingValue() throws Exception {
            String key = uniqueKey("overwrite");

            await(redisRepository.write(key, "first").toCompletableFuture());
            await(redisRepository.write(key, "second").toCompletableFuture());

            String actual = await(redisRepository.read(key).toCompletableFuture());

            Assertions.assertEquals("second", actual);
        }

        @Test
        @DisplayName("writes an empty string value")
        void writesEmptyValue() throws Exception {
            String key = uniqueKey("empty-value");

            String result = await(redisRepository.write(key, "").toCompletableFuture());

            Assertions.assertEquals("OK", result);
            Assertions.assertEquals("", await(redisRepository.read(key).toCompletableFuture()));
        }

        @Test
        @DisplayName("writes Unicode and UTF-8 content correctly")
        void writesUnicodeValue() throws Exception {
            String key = uniqueKey("unicode");
            String value = "Hello 世界 👋 नमस्ते مرحبا";

            await(redisRepository.write(key, value).toCompletableFuture());

            Assertions.assertEquals(value, await(redisRepository.read(key).toCompletableFuture()));
        }

        @Test
        @DisplayName("writes a large value correctly")
        void writesLargeValue() throws Exception {
            String key = uniqueKey("large-value");
            String value = "x".repeat(1024 * 1024);

            await(redisRepository.write(key, value).toCompletableFuture());

            String actual = await(redisRepository.read(key).toCompletableFuture());

            Assertions.assertEquals(value.length(), actual.length());
            Assertions.assertEquals(value, actual);
        }
    }

    @Nested
    @DisplayName("Read")
    class ReadTests {

        @Test
        @DisplayName("returns null for a missing key")
        void missingKeyReturnsNull() throws Exception {
            String key = uniqueKey("missing");

            String result = await(redisRepository.read(key).toCompletableFuture());

            Assertions.assertNull(result);
        }

        @Test
        @DisplayName("returns the exact stored value")
        void returnsExactValue() throws Exception {
            String key = uniqueKey("exact-value");
            String value = "value-with-special-chars: !@#$%^&*()";

            await(redisRepository.write(key, value).toCompletableFuture());

            Assertions.assertEquals(value, await(redisRepository.read(key).toCompletableFuture()));
        }

        @Test
        @DisplayName("returns the latest value after multiple overwrites")
        void returnsLatestValueAfterMultipleWrites() throws Exception {
            String key = uniqueKey("multiple-overwrites");

            await(redisRepository.write(key, "one").toCompletableFuture());
            await(redisRepository.write(key, "two").toCompletableFuture());
            await(redisRepository.write(key, "three").toCompletableFuture());

            Assertions.assertEquals(
                    "three", await(redisRepository.read(key).toCompletableFuture()));
        }
    }

    @Nested
    @DisplayName("Delete")
    class DeleteTests {

        @Test
        @DisplayName("deletes an existing key")
        void deletesExistingKey() throws Exception {
            String key = uniqueKey("delete-existing");

            await(redisRepository.write(key, "value").toCompletableFuture());

            Long deleted = await(redisRepository.delete(key).toCompletableFuture());

            Assertions.assertEquals(1L, deleted);
            Assertions.assertNull(await(redisRepository.read(key).toCompletableFuture()));

            keysToDelete.remove(key);
        }

        @Test
        @DisplayName("deleting a missing key returns zero")
        void deletingMissingKeyReturnsZero() throws Exception {
            String key = uniqueKey("delete-missing");

            Long deleted = await(redisRepository.delete(key).toCompletableFuture());

            Assertions.assertEquals(0L, deleted);

            keysToDelete.remove(key);
        }

        @Test
        @DisplayName("deleting the same key twice returns one then zero")
        void deletingSameKeyTwice() throws Exception {
            String key = uniqueKey("delete-twice");

            await(redisRepository.write(key, "value").toCompletableFuture());

            Long firstDelete = await(redisRepository.delete(key).toCompletableFuture());

            Long secondDelete = await(redisRepository.delete(key).toCompletableFuture());

            Assertions.assertEquals(1L, firstDelete);
            Assertions.assertEquals(0L, secondDelete);

            keysToDelete.remove(key);
        }

        @Test
        @DisplayName("delete removes only the requested key")
        void deleteDoesNotAffectOtherKeys() throws Exception {
            String firstKey = uniqueKey("delete-isolated-first");
            String secondKey = uniqueKey("delete-isolated-second");

            await(redisRepository.write(firstKey, "first").toCompletableFuture());
            await(redisRepository.write(secondKey, "second").toCompletableFuture());

            await(redisRepository.delete(firstKey).toCompletableFuture());

            Assertions.assertNull(await(redisRepository.read(firstKey).toCompletableFuture()));
            Assertions.assertEquals(
                    "second", await(redisRepository.read(secondKey).toCompletableFuture()));
        }
    }

    @Nested
    @DisplayName("Publish")
    class PublishTests {

        @Test
        @DisplayName("publishing to a channel with no subscribers returns zero")
        void publishWithoutSubscribers() throws Exception {
            String channel = uniqueChannel("no-subscriber");

            Long subscribers =
                    await(redisRepository.publish(channel, "hello").toCompletableFuture());

            Assertions.assertEquals(0L, subscribers);
        }

        @Test
        @DisplayName("publishes message to one subscriber")
        void publishesToOneSubscriber() throws Exception {
            String channel = uniqueChannel("one-subscriber");
            String message = "hello subscriber";

            RedisClient client = createRedisClient();
            StatefulRedisPubSubConnection<String, String> subscriber = client.connectPubSub();

            try {
                CompletableFuture<String> received = new CompletableFuture<>();

                subscriber.addListener(
                        new RedisPubSubAdapter<String, String>() {
                            @Override
                            public void message(String receivedChannel, String receivedMessage) {
                                if (channel.equals(receivedChannel)) {
                                    received.complete(receivedMessage);
                                }
                            }
                        });

                await(subscriber.async().subscribe(channel).toCompletableFuture());

                Long subscribers =
                        await(redisRepository.publish(channel, message).toCompletableFuture());

                Assertions.assertEquals(1L, subscribers);
                Assertions.assertEquals(message, await(received));
            } finally {
                subscriber.close();
                client.shutdown();
            }
        }

        @Test
        @DisplayName("publishes the exact Unicode message")
        void publishesUnicodeMessage() throws Exception {
            String channel = uniqueChannel("unicode-message");
            String message = "Hello 世界 👋 नमस्ते";

            RedisClient client = createRedisClient();
            StatefulRedisPubSubConnection<String, String> subscriber = client.connectPubSub();

            try {
                CompletableFuture<String> received = new CompletableFuture<>();

                subscriber.addListener(
                        new RedisPubSubAdapter<String, String>() {
                            @Override
                            public void message(String receivedChannel, String receivedMessage) {
                                if (channel.equals(receivedChannel)) {
                                    received.complete(receivedMessage);
                                }
                            }
                        });

                await(subscriber.async().subscribe(channel).toCompletableFuture());

                Long subscribers =
                        await(redisRepository.publish(channel, message).toCompletableFuture());

                Assertions.assertEquals(1L, subscribers);
                Assertions.assertEquals(message, await(received));
            } finally {
                subscriber.close();
                client.shutdown();
            }
        }

        @Test
        @DisplayName("publishing an empty message succeeds")
        void publishesEmptyMessage() throws Exception {
            String channel = uniqueChannel("empty-message");

            RedisClient client = createRedisClient();
            StatefulRedisPubSubConnection<String, String> subscriber = client.connectPubSub();

            try {
                CompletableFuture<String> received = new CompletableFuture<>();

                subscriber.addListener(
                        new RedisPubSubAdapter<String, String>() {
                            @Override
                            public void message(String receivedChannel, String receivedMessage) {
                                if (channel.equals(receivedChannel)) {
                                    received.complete(receivedMessage);
                                }
                            }
                        });

                await(subscriber.async().subscribe(channel).toCompletableFuture());

                Long subscribers =
                        await(redisRepository.publish(channel, "").toCompletableFuture());

                Assertions.assertEquals(1L, subscribers);
                Assertions.assertEquals("", await(received));
            } finally {
                subscriber.close();
                client.shutdown();
            }
        }

        @Test
        @DisplayName("publishes a large message correctly")
        void publishesLargeMessage() throws Exception {
            String channel = uniqueChannel("large-message");
            String message = "m".repeat(1024 * 1024);

            RedisClient client = createRedisClient();
            StatefulRedisPubSubConnection<String, String> subscriber = client.connectPubSub();

            try {
                CompletableFuture<String> received = new CompletableFuture<>();

                subscriber.addListener(
                        new RedisPubSubAdapter<String, String>() {
                            @Override
                            public void message(String receivedChannel, String receivedMessage) {
                                if (channel.equals(receivedChannel)) {
                                    received.complete(receivedMessage);
                                }
                            }
                        });

                await(subscriber.async().subscribe(channel).toCompletableFuture());

                Long subscribers =
                        await(redisRepository.publish(channel, message).toCompletableFuture());

                String receivedMessage = await(received);

                Assertions.assertEquals(1L, subscribers);
                Assertions.assertEquals(message.length(), receivedMessage.length());
                Assertions.assertEquals(message, receivedMessage);
            } finally {
                subscriber.close();
                client.shutdown();
            }
        }

        @Test
        @DisplayName("publishing to one channel does not deliver to another channel")
        void channelIsolation() throws Exception {
            String channel = uniqueChannel("channel-a");
            String otherChannel = uniqueChannel("channel-b");

            RedisClient client = createRedisClient();
            StatefulRedisPubSubConnection<String, String> subscriber = client.connectPubSub();

            try {
                CompletableFuture<String> received = new CompletableFuture<>();

                subscriber.addListener(
                        new RedisPubSubAdapter<String, String>() {
                            @Override
                            public void message(String receivedChannel, String receivedMessage) {
                                if (channel.equals(receivedChannel)) {
                                    received.complete(receivedMessage);
                                }
                            }
                        });

                await(subscriber.async().subscribe(otherChannel).toCompletableFuture());

                Long subscribers =
                        await(
                                redisRepository
                                        .publish(channel, "should-not-arrive")
                                        .toCompletableFuture());

                Assertions.assertEquals(0L, subscribers);
                Assertions.assertFalse(received.isDone());
            } finally {
                subscriber.close();
                client.shutdown();
            }
        }
    }

    @Nested
    @DisplayName("Concurrent Operations")
    class ConcurrentTests {

        @Test
        @DisplayName("handles many concurrent writes and reads")
        void concurrentWritesAndReads() throws Exception {
            int count = 50;
            List<String> keys = new ArrayList<>();
            List<CompletableFuture<String>> writes = new ArrayList<>();

            for (int i = 0; i < count; i++) {
                String key = uniqueKey("concurrent-" + i);
                keys.add(key);

                writes.add(redisRepository.write(key, "value-" + i).toCompletableFuture());
            }

            CompletableFuture.allOf(writes.toArray(new CompletableFuture[0]))
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            List<CompletableFuture<String>> reads = new ArrayList<>();

            for (int i = 0; i < count; i++) {
                reads.add(redisRepository.read(keys.get(i)).toCompletableFuture());
            }

            CompletableFuture.allOf(reads.toArray(new CompletableFuture[0]))
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            for (int i = 0; i < count; i++) {
                Assertions.assertEquals(
                        "value-" + i, reads.get(i).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
        }

        @Test
        @DisplayName("concurrent writes to the same key leave one complete final value")
        void concurrentWritesToSameKey() throws Exception {
            String key = uniqueKey("same-key");
            int count = 30;

            List<CompletableFuture<String>> writes = new ArrayList<>();

            for (int i = 0; i < count; i++) {
                writes.add(redisRepository.write(key, "value-" + i).toCompletableFuture());
            }

            CompletableFuture.allOf(writes.toArray(new CompletableFuture[0]))
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            String finalValue = await(redisRepository.read(key).toCompletableFuture());

            Assertions.assertNotNull(finalValue);
            Assertions.assertTrue(
                    finalValue.matches("value-\\d+"),
                    "Final Redis value must be one complete write");
        }
    }

    @Nested
    @DisplayName("Operation Lifecycle")
    class LifecycleTests {

        @Test
        @DisplayName("write-read-delete-read lifecycle works end-to-end")
        void completeLifecycle() throws Exception {
            String key = uniqueKey("lifecycle");
            String value = "lifecycle-value";

            String writeResult = await(redisRepository.write(key, value).toCompletableFuture());

            Assertions.assertEquals("OK", writeResult);

            String readResult = await(redisRepository.read(key).toCompletableFuture());

            Assertions.assertEquals(value, readResult);

            Long deleteResult = await(redisRepository.delete(key).toCompletableFuture());

            Assertions.assertEquals(1L, deleteResult);

            String afterDelete = await(redisRepository.read(key).toCompletableFuture());

            Assertions.assertNull(afterDelete);

            keysToDelete.remove(key);
        }

        @Test
        @DisplayName("repository uses the live Redis instance created by BaseIntegrationTest")
        void defaultConstructorUsesLiveRedis() throws Exception {
            String key = uniqueKey("default-constructor");

            RedisRepository repository = new RedisRepository();

            Assertions.assertEquals(
                    "OK", await(repository.write(key, "live-redis").toCompletableFuture()));

            Assertions.assertEquals(
                    "live-redis", await(repository.read(key).toCompletableFuture()));
        }
    }

    @Nested
    @DisplayName("Special Values")
    class SpecialValueTests {

        @Test
        @DisplayName("supports whitespace-only values")
        void whitespaceValue() throws Exception {
            String key = uniqueKey("whitespace");
            String value = "   \t\n   ";

            await(redisRepository.write(key, value).toCompletableFuture());

            Assertions.assertEquals(value, await(redisRepository.read(key).toCompletableFuture()));
        }

        @Test
        @DisplayName("supports strings containing Redis-looking characters")
        void redisSpecialCharacters() throws Exception {
            String key = uniqueKey("special-characters");
            String value = "GET key\r\nSET key value * $ | ; : {} [] ()";

            await(redisRepository.write(key, value).toCompletableFuture());

            Assertions.assertEquals(value, await(redisRepository.read(key).toCompletableFuture()));
        }

        @Test
        @DisplayName("supports binary-looking content represented as a String")
        void binaryLookingString() throws Exception {
            String key = uniqueKey("binary-looking");
            String value =
                    new String(new byte[] {0, 1, 2, 3, 10, 13, 127}, StandardCharsets.ISO_8859_1);

            await(redisRepository.write(key, value).toCompletableFuture());

            Assertions.assertEquals(value, await(redisRepository.read(key).toCompletableFuture()));
        }
    }
}
