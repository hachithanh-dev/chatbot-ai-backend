package dev.thanh.spring_ai.repository;

import com.github.f4b6a3.uuid.UuidCreator;
import dev.thanh.spring_ai.config.AbstractIntegrationTest;
import dev.thanh.spring_ai.entity.ChatMessage;
import dev.thanh.spring_ai.entity.Session;
import dev.thanh.spring_ai.enums.MessageRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;


import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BLOCKER 2 Fix: @ActiveProfiles("unittest") loads application-unittest.yml
 * which disables Flyway and uses Hibernate DDL create-drop for this test slice.
 * @AutoConfigureTestDatabase(replace = Replace.NONE) ensures we use the real PostgreSQL
 * container instead of H2.
 */
@Transactional // GIÁP BẢO VỆ 1: Khai báo tường minh để chống State Leakage cho Postgres
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("MessageRepository — Integration Tests (Testcontainers PostgreSQL)")
class MessageRepositoryIntegrationTest extends AbstractIntegrationTest {


    @Autowired private MessageRepository messageRepository;
    @Autowired private SessionRepository sessionRepository;

    private UUID sessionId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        sessionId = UuidCreator.getTimeOrderedEpoch();
        userId = UUID.randomUUID();

        // Create a session for FK constraint (if applicable)
        Session session = Session.builder()
                .id(sessionId)
                .userId(userId)
                .title("Test Session")
                .active(true)
                .build();
        sessionRepository.save(session);

        // Create 5 messages with distinct timestamps via UUIDv7 ordering
        for (int i = 1; i <= 5; i++) {
            ChatMessage msg = ChatMessage.builder()
                    .id(UuidCreator.getTimeOrderedEpoch())
                    .messageId("msg-" + i)
                    .sessionId(sessionId)
                    .role(i % 2 == 0 ? MessageRole.ASSISTANT : MessageRole.USER)
                    .content("Message content " + i)
                    .model("gemini-2.5-flash")
                    .build();
            messageRepository.saveAndFlush(msg);
        }
    }

    // ─────────────────────────────────────────────────────────
    // findRecentMessagesBySessionId
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("findRecentMessagesBySessionId — should return messages ordered by createdAt DESC")
    void findRecentMessagesBySessionId_ShouldReturnLatestMessages() {
        // When
        List<ChatMessage> results = messageRepository.findRecentMessagesBySessionId(
                sessionId, PageRequest.of(0, 3));

        // Then
        assertThat(results).hasSize(3);
        // DESC order: most recent first
        assertThat(results.get(0).getContent()).isEqualTo("Message content 5");
    }

    @Test
    @DisplayName("findRecentMessagesBySessionId — limit respected")
    void findRecentMessagesBySessionId_ShouldRespectLimit() {
        // When
        List<ChatMessage> results = messageRepository.findRecentMessagesBySessionId(
                sessionId, PageRequest.of(0, 2));

        // Then
        assertThat(results).hasSize(2);
    }

    // ─────────────────────────────────────────────────────────
    // findMessagesCursorBased (native query)
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("findMessagesCursorBased — no cursor — should return first page")
    void findMessagesCursorBased_WhenNoCursor_ShouldReturnFirstPage() {
        // When: no cursor (lastCreatedAt=null, lastId=null)
        List<ChatMessage> results = messageRepository.findMessagesCursorBased(
                sessionId, null, null, PageRequest.of(0, 3));

        // Then
        assertThat(results).hasSize(3);
    }

    @Test
    @DisplayName("findMessagesCursorBased — with cursor — should return next page")
    void findMessagesCursorBased_WhenCursorProvided_ShouldReturnNextPage() {
        // Given: get first page to obtain a cursor
        List<ChatMessage> firstPage = messageRepository.findMessagesCursorBased(
                sessionId, null, null, PageRequest.of(0, 3));
        assertThat(firstPage).hasSize(3);

        // Use last item of first page as cursor
        ChatMessage lastInFirstPage = firstPage.get(firstPage.size() - 1);

        // When: get next page using cursor
        List<ChatMessage> nextPage = messageRepository.findMessagesCursorBased(
                sessionId, lastInFirstPage.getCreatedAt(), lastInFirstPage.getId(),
                PageRequest.of(0, 3));

        // Then: should return remaining messages
        assertThat(nextPage).hasSizeLessThanOrEqualTo(2);
        // Items should not contain items from first page
        nextPage.forEach(msg ->
                assertThat(firstPage).doesNotContain(msg));
    }

    // ─────────────────────────────────────────────────────────
    // deleteBySessionId
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("deleteBySessionId — should hard-delete all messages in session")
    void deleteBySessionId_ShouldDeleteAllMessages() {
        // Given: 5 messages exist
        assertThat(messageRepository.countBySessionId(sessionId)).isEqualTo(5);

        // When
        messageRepository.deleteBySessionId(sessionId);

        // Then
        assertThat(messageRepository.countBySessionId(sessionId)).isZero();
    }
}
