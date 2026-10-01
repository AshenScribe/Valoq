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

import database.EventRepository;
import database.UserEventRepository;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public class SendMessage implements Service<CompletionStage<Void>> {

    private final UUID recipientId;
    private final String payload;
    private final String clientCreatedAt;
    private final EventRepository eventRepository;
    private final UserEventRepository userEventRepository;

    public SendMessage(UUID recipientId, String payload, String clientCreatedAt) {
        this(
                recipientId,
                payload,
                clientCreatedAt,
                new EventRepository(),
                new UserEventRepository());
    }

    public SendMessage(
            UUID recipientId,
            String payload,
            String clientCreatedAt,
            EventRepository eventRepository,
            UserEventRepository userEventRepository) {
        this.recipientId = recipientId;
        this.payload = payload;
        this.clientCreatedAt = clientCreatedAt;
        this.eventRepository = eventRepository;
        this.userEventRepository = userEventRepository;
    }

    @Override
    public CompletionStage<Void> serve() {
        // todo: implement the logic to send a message to the recipient
        return null;
    }
}
