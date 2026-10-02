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
package server;

import com.datastax.oss.driver.api.core.uuid.Uuids;
import database.BucketUtils;
import database.ConversationMemberRepository;
import database.EventRepository;
import database.UserEventRepository;
import io.netty.buffer.PooledByteBufAllocator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import protocol.BinaryMessages;
import protocol.Envelope;
import server.model.Event;
import server.model.EventType;

public class MessageRouter {

    private final ConnectionTracker connectionTracker;
    private final EventRepository eventRepository;
    private final ConversationMemberRepository memberRepository;
    private final UserEventRepository userEventRepository;

    public MessageRouter(ConnectionTracker connectionTracker) {
        this(
                connectionTracker,
                new EventRepository(),
                new ConversationMemberRepository(),
                new UserEventRepository());
    }

    public MessageRouter(
            ConnectionTracker connectionTracker,
            EventRepository eventRepository,
            ConversationMemberRepository memberRepository,
            UserEventRepository userEventRepository) {
        this.connectionTracker = connectionTracker;
        this.eventRepository = eventRepository;
        this.memberRepository = memberRepository;
        this.userEventRepository = userEventRepository;
    }

    public CompletionStage<UUID> route(Event event) {
        UUID eventId = Uuids.timeBased();
        String timeBucket = BucketUtils.toTimeBucket(event.createdAt());

        return memberRepository
                .findMemberIds(event.conversationId())
                .thenCompose(
                        members ->
                                eventRepository
                                        .saveEvent(
                                                event.conversationId(),
                                                timeBucket,
                                                0,
                                                eventId,
                                                event.eventType().name(),
                                                event.senderId(),
                                                event.eventId(),
                                                event.payload())
                                        .thenCompose(
                                                result -> {
                                                    Event persistedEvent =
                                                            new Event(
                                                                    event.senderId(),
                                                                    event.conversationId(),
                                                                    eventId,
                                                                    event.eventType(),
                                                                    event.payload(),
                                                                    event.createdAt());

                                                    Envelope eventEnvelope =
                                                            BinaryMessages.createEventEnvelope(
                                                                    persistedEvent,
                                                                    Envelope.EVENT_STREAM_ID,
                                                                    PooledByteBufAllocator.DEFAULT);

                                                    connectionTracker.broadcastToUsers(
                                                            members, eventEnvelope);

                                                    List<CompletableFuture<?>> indexFutures =
                                                            new ArrayList<>(members.size());
                                                    for (UUID memberId : members) {
                                                        indexFutures.add(
                                                                userEventRepository
                                                                        .saveUserEvent(
                                                                                memberId,
                                                                                timeBucket,
                                                                                eventId,
                                                                                event
                                                                                        .conversationId())
                                                                        .toCompletableFuture());
                                                    }

                                                    return CompletableFuture.allOf(
                                                                    indexFutures.toArray(
                                                                            new CompletableFuture
                                                                                    [0]))
                                                            .thenApply(v -> eventId);
                                                }));
    }

    public CompletionStage<Void> routeReceipt(
            UUID actorId, BinaryMessages.ReceiptRequest request, EventType type) {
        Instant now = Instant.now();
        String timeBucket = BucketUtils.toTimeBucket(now);
        UUID receiptEventId = Uuids.timeBased();

        UUID targetEntityId =
                request.mode() == BinaryMessages.ReceiptMode.WATERMARK
                        ? request.watermarkEventId()
                        : (request.explicitEventIds().isEmpty()
                                ? null
                                : request.explicitEventIds().get(0));

        String payload =
                request.mode() == BinaryMessages.ReceiptMode.WATERMARK
                        ? "WATERMARK"
                        : String.join(
                                ",",
                                request.explicitEventIds().stream().map(UUID::toString).toList());

        Event receiptEvent =
                new Event(actorId, request.conversationId(), receiptEventId, type, payload, now);

        return memberRepository
                .findMemberIds(request.conversationId())
                .thenCompose(
                        members ->
                                eventRepository
                                        .saveEvent(
                                                request.conversationId(),
                                                timeBucket,
                                                0,
                                                receiptEventId,
                                                type.name(),
                                                actorId,
                                                targetEntityId,
                                                payload)
                                        .thenAccept(
                                                res -> {
                                                    Envelope receiptEnvelope =
                                                            BinaryMessages.createEventEnvelope(
                                                                    receiptEvent,
                                                                    Envelope.EVENT_STREAM_ID,
                                                                    PooledByteBufAllocator.DEFAULT);

                                                    connectionTracker.broadcastToUsers(
                                                            members, receiptEnvelope);
                                                }));
    }
}
