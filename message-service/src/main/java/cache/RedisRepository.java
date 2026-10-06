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

import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands;
import java.util.concurrent.CompletionStage;

public final class RedisRepository {

    private final RedisAsyncCommands<String, String> redisAsyncCommands;
    private final RedisPubSubAsyncCommands<String, String> redisPubSubAsyncCommands;

    public RedisRepository() {
        RedisAsyncCommands<String, String> asyncCmds = null;
        RedisPubSubAsyncCommands<String, String> pubSubCmds = null;
        try {
            if (RedisManager.isInitialized()) {
                RedisManager redisManager = RedisManager.getInstance();
                asyncCmds = redisManager.async();
                pubSubCmds = redisManager.pubSubAsync();
            }
        } catch (IllegalStateException ignored) {
            // Redis not initialized (e.g. in isolated unit tests)
        }
        this.redisAsyncCommands = asyncCmds;
        this.redisPubSubAsyncCommands = pubSubCmds;
    }

    public RedisRepository(
            RedisAsyncCommands<String, String> redisAsyncCommands,
            RedisPubSubAsyncCommands<String, String> redisPubSubAsyncCommands) {
        this.redisAsyncCommands = redisAsyncCommands;
        this.redisPubSubAsyncCommands = redisPubSubAsyncCommands;
    }

    public CompletionStage<String> write(String key, String value) {
        if (redisAsyncCommands == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        return redisAsyncCommands.set(key, value);
    }

    public CompletionStage<String> read(String key) {
        if (redisAsyncCommands == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        return redisAsyncCommands.get(key);
    }

    public CompletionStage<Long> publish(String channel, String message) {
        if (redisPubSubAsyncCommands == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(0L);
        }
        return redisPubSubAsyncCommands.publish(channel, message);
    }

    public CompletionStage<Long> delete(String key) {
        if (redisAsyncCommands == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(0L);
        }
        return redisAsyncCommands.del(key);
    }
}
