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
package database;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public class UserEventRepository {

    private final CqlSession session;
    private final PreparedStatement selectStatement;
    private final PreparedStatement insertStatement;

    public UserEventRepository() {
        this(CassandraManager.getSession());
    }

    public UserEventRepository(CqlSession session) {
        this.session = session;
        SimpleStatement selectSimple =
                SimpleStatement.newInstance(
                                "SELECT event_id, conversation_id FROM user_events WHERE user_id = ? AND time_bucket = ? AND event_id > ?")
                        .setIdempotent(true);

        SimpleStatement insertSimple =
                SimpleStatement.newInstance(
                                "INSERT INTO user_events (user_id, time_bucket, event_id, conversation_id) VALUES (?, ?, ?, ?)")
                        .setIdempotent(true);

        this.selectStatement = session.prepare(selectSimple);
        this.insertStatement = session.prepare(insertSimple);
    }

    public CompletionStage<AsyncResultSet> getUserEvents(
            UUID userId, UUID cursor, String timeBucket) {
        return session.executeAsync(selectStatement.bind(userId, timeBucket, cursor));
    }

    public CompletionStage<AsyncResultSet> saveUserEvent(
            UUID userId, String timeBucket, UUID eventId, UUID conversationId) {
        return session.executeAsync(
                insertStatement.bind(userId, timeBucket, eventId, conversationId));
    }
}
