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
import com.datastax.oss.driver.api.core.cql.Row;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import server.model.Event;
import server.model.EventType;

public class EventRepository {

    private final CqlSession session;
    private final PreparedStatement insertStatement;
    private final PreparedStatement selectStatement;

    public EventRepository() {
        this(CassandraManager.getSession());
    }

    public EventRepository(CqlSession session) {
        this.session = session;
        this.insertStatement =
                session.prepare(
                        "INSERT INTO events (conversation_id, time_bucket, hash_bucket, event_id, event_type, actor_id, entity_id, payload) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
        this.selectStatement =
                session.prepare(
                        "SELECT * FROM events WHERE conversation_id = ? AND time_bucket = ? AND hash_bucket = ? AND event_id > ?");
    }

    public CompletionStage<AsyncResultSet> saveEvent(
            UUID conversationId,
            String timeBucket,
            int hashBucket,
            UUID eventId,
            String eventType,
            UUID actorId,
            UUID entityId,
            String payload) {

        return session.executeAsync(
                insertStatement.bind(
                        conversationId,
                        timeBucket,
                        hashBucket,
                        eventId,
                        eventType,
                        actorId,
                        entityId,
                        payload));
    }

    public CompletionStage<Optional<Event>> getEvents(
            UUID conversationId, String timeBucket, int hashBucket, UUID eventId) {

        return session.executeAsync(
                        selectStatement.bind(conversationId, timeBucket, hashBucket, eventId))
                .thenApply(
                        result -> {
                            Row row = result.one();

                            if (row == null) {
                                return Optional.empty();
                            }

                            return Optional.of(
                                    new Event(
                                            row.getUuid("actor_id"),
                                            row.getUuid("conversation_id"),
                                            row.getUuid("entity_id"),
                                            EventType.valueOf(row.getString("event_type")),
                                            row.getString("payload"),
                                            row.getInstant("created_at")));
                        });
    }
}
