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

import config.CacheConfig;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.pubsub.RedisPubSubListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands;

public final class RedisManager implements AutoCloseable {

    private static volatile RedisManager INSTANCE = null;

    private final RedisClient client;

    private final StatefulRedisConnection<String, String> dataConnection;
    private final StatefulRedisPubSubConnection<String, String> pubSubConnection;

    private final RedisAsyncCommands<String, String> asyncCommands;
    private final RedisPubSubAsyncCommands<String, String> pubSubAsyncCommands;

    private RedisManager(CacheConfig cacheConfig) {
        RedisURI redisUri =
                RedisURI.Builder.redis(cacheConfig.getHost(), cacheConfig.getPort())
                        .withAuthentication(cacheConfig.getUsername(), cacheConfig.getPassword())
                        .build();

        this.client = RedisClient.create(redisUri);

        this.dataConnection = client.connect();
        this.asyncCommands = dataConnection.async();

        this.pubSubConnection = client.connectPubSub();
        this.pubSubAsyncCommands = pubSubConnection.async();
    }

    public static RedisManager getInstance(CacheConfig cacheConfig) {
        if (INSTANCE == null) {
            synchronized (RedisManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new RedisManager(cacheConfig);
                }
            }
        }
        return INSTANCE;
    }

    public static RedisManager getInstance() {
        RedisManager instance = INSTANCE;
        if (instance == null) {
            throw new IllegalStateException(
                    "Redis has not been initialized. Call getInstance(CacheConfig) first.");
        }
        return instance;
    }

    public RedisAsyncCommands<String, String> async() {
        return this.asyncCommands;
    }

    public void addPubSubListener(RedisPubSubListener<String, String> listener) {
        this.pubSubConnection.addListener(listener);
    }

    public RedisPubSubAsyncCommands<String, String> pubSubAsync() {
        return this.pubSubAsyncCommands;
    }

    @Override
    public void close() {
        if (dataConnection != null) {
            dataConnection.close();
        }
        if (pubSubConnection != null) {
            pubSubConnection.close();
        }
        if (client != null) {
            client.shutdown();
        }
        INSTANCE = null;
    }

    public static boolean isInitialized() {
        return INSTANCE != null;
    }
}
