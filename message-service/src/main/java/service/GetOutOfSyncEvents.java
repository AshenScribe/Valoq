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
package service;

import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import database.BucketUtils;
import database.EventRepository;
import database.UserEventRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import server.model.Event;

public class GetOutOfSyncEvents implements Service<CompletionStage<List<Event>>> {

    private final UUID userId;
    private final String clientEventId;
    private final UserEventRepository userEventRepository;
    private final EventRepository eventRepository;

    public GetOutOfSyncEvents(
            UUID userId,
            String clientEventId,
            UserEventRepository userEventRepository,
            EventRepository eventRepository) {
        this.userId = userId;
        this.clientEventId = clientEventId;
        this.userEventRepository = userEventRepository;
        this.eventRepository = eventRepository;
    }

    public GetOutOfSyncEvents(UUID userId, String clientEventId) {
        this(userId, clientEventId, new UserEventRepository(), new EventRepository());
    }

    public CompletionStage<List<Event>> serve() {
        UUID cursor = UUID.fromString(clientEventId);

        Instant cursorTime = Instant.ofEpochMilli(Uuids.unixTimestamp(cursor));
        String timeBucket = BucketUtils.toTimeBucket(cursorTime);

        return userEventRepository
                .getUserEvents(userId, cursor, timeBucket)
                .thenCompose(
                        userEvents -> {
                            List<CompletionStage<Optional<Event>>> futures = new ArrayList<>();
                            for (Row row : userEvents.currentPage()) {
                                UUID eventId = row.getUuid("event_id");
                                UUID conversationId = row.getUuid("conversation_id");
                                Instant eventTime =
                                        Instant.ofEpochMilli(Uuids.unixTimestamp(eventId));
                                String eventBucket = BucketUtils.toTimeBucket(eventTime);

                                futures.add(
                                        eventRepository.getEvent(
                                                conversationId, eventBucket, 0, eventId));
                            }

                            CompletableFuture<?>[] all =
                                    futures.stream()
                                            .map(CompletionStage::toCompletableFuture)
                                            .toArray(CompletableFuture[]::new);

                            return CompletableFuture.allOf(all)
                                    .thenApply(
                                            v ->
                                                    futures.stream()
                                                            .map(
                                                                    CompletionStage
                                                                            ::toCompletableFuture)
                                                            .map(CompletableFuture::join)
                                                            .flatMap(Optional::stream)
                                                            .toList());
                        });
    }
}
