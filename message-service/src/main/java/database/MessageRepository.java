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
import com.datastax.oss.driver.api.core.uuid.Uuids;
import java.time.Instant;
import java.util.concurrent.CompletionStage;

public class MessageRepository {

    private final CqlSession session;
    private final PreparedStatement insertStatement;

    public MessageRepository() {
        this(CassandraManager.getSession());
    }

    public MessageRepository(CqlSession session) {
        this.session = session;
        this.insertStatement =
                session.prepare(
                        """
                    INSERT INTO messages (conversation_id, message_id, sender_id, recipient_id, payload, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                """);
    }

    public CompletionStage<AsyncResultSet> saveMessageAsync(
            String senderId, String recipientId, String payload) {
        String conversationId = getConversationId(senderId, recipientId);
        Instant now = Instant.now();

        return session.executeAsync(
                insertStatement.bind(
                        conversationId, Uuids.timeBased(), senderId, recipientId, payload, now));
    }

    public static String getConversationId(String user1, String user2) {
        return user1.compareTo(user2) < 0 ? user1 + ":" + user2 : user2 + ":" + user1;
    }
}
