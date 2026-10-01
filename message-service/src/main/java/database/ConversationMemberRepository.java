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
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ConversationMemberRepository {

    private final CqlSession session;

    private final PreparedStatement insertMember;
    private final PreparedStatement deleteMember;
    private final PreparedStatement findMembers;

    public ConversationMemberRepository() {
        this(CassandraManager.getSession());
    }

    public ConversationMemberRepository(CqlSession session) {
        this.session = session;

        this.insertMember =
                session.prepare(
                        """
                        INSERT INTO valoq_messages.conversation_members
                            (conversation_id, user_id, role, joined_at)
                        VALUES (?, ?, ?, ?)
                        """);

        this.deleteMember =
                session.prepare(
                        """
                        DELETE FROM valoq_messages.conversation_members
                        WHERE conversation_id = ?
                          AND user_id = ?
                        """);

        this.findMembers =
                session.prepare(
                        """
                        SELECT user_id
                        FROM valoq_messages.conversation_members
                        WHERE conversation_id = ?
                        """);
    }

    public CompletableFuture<Void> addMember(UUID conversationId, UUID userId, String role) {

        return session.executeAsync(insertMember.bind(conversationId, userId, role, Instant.now()))
                .toCompletableFuture()
                .thenApply(ignored -> null);
    }

    public CompletableFuture<Void> removeMember(UUID conversationId, UUID userId) {

        return session.executeAsync(deleteMember.bind(conversationId, userId))
                .toCompletableFuture()
                .thenApply(ignored -> null);
    }

    public List<String> findMemberIds(UUID conversationId) {
        List<String> members = new ArrayList<>();
        for (Row row : session.execute(findMembers.bind(conversationId))) {
            members.add(row.getUuid("user_id").toString());
        }
        return members;
    }
}
