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

import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import database.BucketUtils;
import database.ConversationMemberRepository;
import database.EventRepository;
import io.netty.channel.Channel;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import server.model.Event;

public class MessageRouter {

    private final ConnectionTracker connectionTracker;
    private final EventRepository eventRepository;
    private final ConversationMemberRepository memberRepository;

    public MessageRouter(
            ConnectionTracker connectionTracker,
            EventRepository eventRepository,
            ConversationMemberRepository memberRepository) {

        this.connectionTracker = connectionTracker;
        this.eventRepository = eventRepository;
        this.memberRepository = memberRepository;
    }

    public CompletionStage<AsyncResultSet> route(Event event) {
        UUID eventId = Uuids.timeBased();
        String timeBucket = BucketUtils.toTimeBucket(event.createdAt());
        List<String> members = memberRepository.findMemberIds(event.conversationId());

        return eventRepository
                .saveEvent(
                        event.conversationId(),
                        timeBucket,
                        0,
                        eventId,
                        event.eventType().name(),
                        event.senderId(),
                        event.eventId(),
                        event.payload())
                .thenApply(
                        result -> {
                            for (String memberId : members) {
                                Channel channel = connectionTracker.get(memberId);
                                if (channel == null) {
                                    continue;
                                }
                                channel.writeAndFlush(
                                        String.format(
                                                "EVENT %s %s %s %s %s",
                                                event.eventType().name(),
                                                eventId,
                                                event.conversationId(),
                                                event.senderId(),
                                                event.payload()));
                            }

                            return result;
                        });
    }
}
