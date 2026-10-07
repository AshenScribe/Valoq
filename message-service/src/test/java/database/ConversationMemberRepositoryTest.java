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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ConversationMemberRepository Unit Tests")
class ConversationMemberRepositoryTest {

    private CqlSession session;
    private PreparedStatement insertStatement;
    private PreparedStatement deleteStatement;
    private PreparedStatement findStatement;

    private BoundStatement insertBound;
    private BoundStatement deleteBound;
    private BoundStatement findBound;

    private ConversationMemberRepository repository;

    @BeforeEach
    void setUp() {
        session = mock(CqlSession.class);
        insertStatement = mock(PreparedStatement.class);
        deleteStatement = mock(PreparedStatement.class);
        findStatement = mock(PreparedStatement.class);

        insertBound = mock(BoundStatement.class);
        deleteBound = mock(BoundStatement.class);
        findBound = mock(BoundStatement.class);

        gearMocks();
    }

    private void gearMocks() {
        when(session.prepare(any(SimpleStatement.class)))
                .thenReturn(insertStatement)
                .thenReturn(deleteStatement)
                .thenReturn(findStatement);

        when(insertStatement.bind(any(Object[].class))).thenReturn(insertBound);
        when(deleteStatement.bind(any(Object[].class))).thenReturn(deleteBound);
        when(findStatement.bind(any(Object[].class))).thenReturn(findBound);

        repository = new ConversationMemberRepository(session);
    }

    @Nested
    @DisplayName("Statement Binding and Execution")
    class ExecutionTests {

        @Test
        @DisplayName("addMember executes statement asynchronously and returns null on completion")
        void addMemberExecutesSuccessfully() throws Exception {
            AsyncResultSet rs = mock(AsyncResultSet.class);
            when(session.executeAsync(insertBound))
                    .thenReturn(CompletableFuture.completedFuture(rs));

            CompletableFuture<Void> future =
                    repository.addMember(UUID.randomUUID(), UUID.randomUUID(), "ADMIN");

            future.get(5, TimeUnit.SECONDS);
            verify(session).executeAsync(insertBound);
        }

        @Test
        @DisplayName(
                "removeMember executes statement asynchronously and returns null on completion")
        void removeMemberExecutesSuccessfully() throws Exception {
            AsyncResultSet rs = mock(AsyncResultSet.class);
            when(session.executeAsync(deleteBound))
                    .thenReturn(CompletableFuture.completedFuture(rs));

            CompletableFuture<Void> future =
                    repository.removeMember(UUID.randomUUID(), UUID.randomUUID());

            future.get(5, TimeUnit.SECONDS);
            verify(session).executeAsync(deleteBound);
        }

        @Test
        @DisplayName("findMemberIds extracts member UUIDs correctly from the result set")
        void findMemberIdsExtractsUuids() throws Exception {
            UUID u1 = UUID.randomUUID();
            UUID u2 = UUID.randomUUID();

            Row row1 = mock(Row.class);
            Row row2 = mock(Row.class);
            when(row1.getUuid("user_id")).thenReturn(u1);
            when(row2.getUuid("user_id")).thenReturn(u2);

            AsyncResultSet rs = mock(AsyncResultSet.class);
            when(rs.currentPage()).thenReturn(List.of(row1, row2));

            when(session.executeAsync(findBound)).thenReturn(CompletableFuture.completedFuture(rs));

            CompletionStage<List<UUID>> stage = repository.findMemberIds(UUID.randomUUID());

            List<UUID> memberIds = stage.toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(2, memberIds.size());
            assertEquals(u1, memberIds.get(0));
            assertEquals(u2, memberIds.get(1));
        }
    }

    @Nested
    @DisplayName("Failure Propagation")
    class FailureTests {

        @Test
        @DisplayName("addMember propagates database errors exceptionally")
        void addMemberFails() {
            CompletableFuture<AsyncResultSet> failedFuture = new CompletableFuture<>();
            RuntimeException boom = new RuntimeException("Cassandra connection error");
            failedFuture.completeExceptionally(boom);

            when(session.executeAsync(insertBound)).thenReturn(failedFuture);

            CompletableFuture<Void> future =
                    repository.addMember(UUID.randomUUID(), UUID.randomUUID(), "MEMBER");

            ExecutionException ex =
                    assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));

            assertSame(boom, ex.getCause());
        }

        @Test
        @DisplayName("findMemberIds propagates database errors exceptionally")
        void findMemberIdsFails() {
            CompletableFuture<AsyncResultSet> failedFuture = new CompletableFuture<>();
            RuntimeException boom = new RuntimeException("Read timeout");
            failedFuture.completeExceptionally(boom);

            when(session.executeAsync(findBound)).thenReturn(failedFuture);

            CompletionStage<List<UUID>> stage = repository.findMemberIds(UUID.randomUUID());

            ExecutionException ex =
                    assertThrows(
                            ExecutionException.class,
                            () -> stage.toCompletableFuture().get(5, TimeUnit.SECONDS));

            assertSame(boom, ex.getCause());
        }
    }
}
