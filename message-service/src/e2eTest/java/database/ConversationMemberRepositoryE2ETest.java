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

import base.BaseIntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ConversationMemberRepository E2E Integration Tests")
class ConversationMemberRepositoryE2ETest extends BaseIntegrationTest {

    private static final long TIMEOUT_SECONDS = 5L;
    private ConversationMemberRepository memberRepository;

    @BeforeEach
    void setUp() {
        memberRepository = new ConversationMemberRepository(getSession());
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Nested
    @DisplayName("Member Add & Find Lifecycle")
    class AddAndFindTests {

        @Test
        @DisplayName("successfully adds a member and retrieves them in findMemberIds")
        void addAndFindSingleMember() throws Exception {
            UUID conversationId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();

            await(memberRepository.addMember(conversationId, userId, "ADMIN"));

            List<UUID> memberIds =
                    await(memberRepository.findMemberIds(conversationId).toCompletableFuture());

            Assertions.assertEquals(1, memberIds.size());
            Assertions.assertEquals(userId, memberIds.get(0));
        }

        @Test
        @DisplayName("retrieves multiple members belonging to the same conversation partition")
        void addAndFindMultipleMembers() throws Exception {
            UUID conversationId = UUID.randomUUID();
            UUID userA = UUID.randomUUID();
            UUID userB = UUID.randomUUID();
            UUID userC = UUID.randomUUID();

            memberRepository.addMember(conversationId, userA, "OWNER").join();
            memberRepository.addMember(conversationId, userB, "MEMBER").join();
            memberRepository.addMember(conversationId, userC, "MEMBER").join();

            List<UUID> memberIds =
                    await(memberRepository.findMemberIds(conversationId).toCompletableFuture());

            Assertions.assertEquals(3, memberIds.size());
            Assertions.assertTrue(memberIds.containsAll(List.of(userA, userB, userC)));
        }

        @Test
        @DisplayName("returns empty list when conversation has no members")
        void emptyWhenNoMembers() throws Exception {
            UUID conversationId = UUID.randomUUID();

            List<UUID> memberIds =
                    await(memberRepository.findMemberIds(conversationId).toCompletableFuture());

            Assertions.assertTrue(memberIds.isEmpty());
        }
    }

    @Nested
    @DisplayName("Member Removal Lifecycle")
    class RemovalTests {

        @Test
        @DisplayName("successfully removes an existing member from a conversation")
        void removeMemberSuccessfully() throws Exception {
            UUID conversationId = UUID.randomUUID();
            UUID userA = UUID.randomUUID();
            UUID userB = UUID.randomUUID();

            memberRepository.addMember(conversationId, userA, "MEMBER").join();
            memberRepository.addMember(conversationId, userB, "MEMBER").join();

            await(memberRepository.removeMember(conversationId, userA));

            List<UUID> memberIds =
                    await(memberRepository.findMemberIds(conversationId).toCompletableFuture());

            Assertions.assertEquals(1, memberIds.size());
            Assertions.assertEquals(userB, memberIds.get(0));
        }

        @Test
        @DisplayName("removing a non-existent member completes gracefully as a no-op")
        void removeNonExistentMemberNoOp() {
            UUID conversationId = UUID.randomUUID();
            UUID missingUser = UUID.randomUUID();

            Assertions.assertDoesNotThrow(
                    () -> memberRepository.removeMember(conversationId, missingUser).join());
        }
    }

    @Nested
    @DisplayName("Conversation Partition Isolation")
    class PartitionIsolationTests {

        @Test
        @DisplayName("members of distinct conversations are kept completely isolated")
        void conversationsAreIsolated() throws Exception {
            UUID convA = UUID.randomUUID();
            UUID convB = UUID.randomUUID();
            UUID userA = UUID.randomUUID();
            UUID userB = UUID.randomUUID();

            memberRepository.addMember(convA, userA, "MEMBER").join();
            memberRepository.addMember(convB, userB, "MEMBER").join();

            List<UUID> membersA =
                    await(memberRepository.findMemberIds(convA).toCompletableFuture());
            List<UUID> membersB =
                    await(memberRepository.findMemberIds(convB).toCompletableFuture());

            Assertions.assertEquals(List.of(userA), membersA);
            Assertions.assertEquals(List.of(userB), membersB);
        }
    }
}
