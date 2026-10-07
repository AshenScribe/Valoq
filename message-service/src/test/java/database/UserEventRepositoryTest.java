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
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("UserEventRepository Unit Tests")
class UserEventRepositoryTest {

    private CqlSession session;
    private PreparedStatement selectPreparedStatement;
    private PreparedStatement insertPreparedStatement;
    private BoundStatement selectBoundStatement;
    private BoundStatement insertBoundStatement;
    private UserEventRepository repository;

    @BeforeEach
    void setUp() {
        session = mock(CqlSession.class);
        selectPreparedStatement = mock(PreparedStatement.class);
        insertPreparedStatement = mock(PreparedStatement.class);
        selectBoundStatement = mock(BoundStatement.class);
        gearMocks();
    }

    private void gearMocks() {
        when(session.prepare(any(SimpleStatement.class)))
                .thenReturn(selectPreparedStatement)
                .thenReturn(insertPreparedStatement);

        when(selectPreparedStatement.bind(any(Object[].class))).thenReturn(selectBoundStatement);
        when(insertPreparedStatement.bind(any(Object[].class))).thenReturn(insertBoundStatement);

        repository = new UserEventRepository(session);
    }

    @Nested
    @DisplayName("Statement Preparation and Execution")
    class ExecutionTests {

        @Test
        @DisplayName("getUserEvents binds parameters correctly and invokes executeAsync")
        void getUserEventsBindsAndExecutes() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID cursor = Uuids.timeBased();
            String timeBucket = "2026-09";

            AsyncResultSet expectedRs = mock(AsyncResultSet.class);
            when(session.executeAsync(selectBoundStatement))
                    .thenReturn(CompletableFuture.completedFuture(expectedRs));

            CompletionStage<AsyncResultSet> stage =
                    repository.getUserEvents(userId, cursor, timeBucket);

            AsyncResultSet result = stage.toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertSame(expectedRs, result);
            verify(session).executeAsync(selectBoundStatement);
        }

        @Test
        @DisplayName("saveUserEvent binds parameters correctly and invokes executeAsync")
        void saveUserEventBindsAndExecutes() throws Exception {
            UUID userId = UUID.randomUUID();
            String timeBucket = "2026-09";
            UUID eventId = Uuids.timeBased();
            UUID conversationId = UUID.randomUUID();

            AsyncResultSet expectedRs = mock(AsyncResultSet.class);
            when(session.executeAsync(insertBoundStatement))
                    .thenReturn(CompletableFuture.completedFuture(expectedRs));

            CompletionStage<AsyncResultSet> stage =
                    repository.saveUserEvent(userId, timeBucket, eventId, conversationId);

            AsyncResultSet result = stage.toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertSame(expectedRs, result);
            verify(session).executeAsync(insertBoundStatement);
        }
    }

    @Nested
    @DisplayName("Failure Handling")
    class FailureTests {

        @Test
        @DisplayName("getUserEvents propagates asynchronous execution exceptions")
        void getUserEventsPropagatesError() {
            RuntimeException boom = new RuntimeException("Cassandra node down");
            CompletableFuture<AsyncResultSet> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(boom);

            when(session.executeAsync(selectBoundStatement)).thenReturn(failedFuture);

            CompletionStage<AsyncResultSet> stage =
                    repository.getUserEvents(UUID.randomUUID(), Uuids.timeBased(), "2026-09");

            ExecutionException ex =
                    assertThrows(
                            ExecutionException.class,
                            () -> stage.toCompletableFuture().get(5, TimeUnit.SECONDS));

            assertSame(boom, ex.getCause());
        }

        @Test
        @DisplayName("saveUserEvent propagates asynchronous execution exceptions")
        void saveUserEventPropagatesError() {
            RuntimeException boom = new RuntimeException("Write timeout");
            CompletableFuture<AsyncResultSet> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(boom);

            when(session.executeAsync(insertBoundStatement)).thenReturn(failedFuture);

            CompletionStage<AsyncResultSet> stage =
                    repository.saveUserEvent(
                            UUID.randomUUID(), "2026-09", Uuids.timeBased(), UUID.randomUUID());

            ExecutionException ex =
                    assertThrows(
                            ExecutionException.class,
                            () -> stage.toCompletableFuture().get(5, TimeUnit.SECONDS));

            assertSame(boom, ex.getCause());
        }
    }
}
