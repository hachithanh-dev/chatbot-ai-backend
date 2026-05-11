package dev.thanh.spring_ai.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link JacksonConfig}.
 * Verifies ObjectMapper is configured correctly for the application:
 * - JavaTimeModule registered (LocalDateTime/ZonedDateTime serialization)
 * - WRITE_DATES_AS_TIMESTAMPS disabled (ISO-8601 format)
 * - FAIL_ON_UNKNOWN_PROPERTIES disabled (forward compatibility)
 * - NON_NULL inclusion (smaller JSON payloads)
 */
@DisplayName("JacksonConfig — Unit Tests")
class JacksonConfigTest {

    private final ObjectMapper objectMapper = new JacksonConfig().objectMapper();

    @Test
    @DisplayName("should serialize LocalDateTime as ISO-8601 string, not timestamp array")
    void shouldSerializeLocalDateTimeAsIso8601() throws JsonProcessingException {
        // Given
        LocalDateTime dateTime = LocalDateTime.of(2026, 5, 10, 14, 30, 0);

        // When
        String json = objectMapper.writeValueAsString(dateTime);

        // Then — ISO-8601 format, not [2026,5,10,14,30,0]
        assertThat(json).contains("2026-05-10");
        assertThat(json).doesNotContain("[");
    }

    @Test
    @DisplayName("should serialize ZonedDateTime as ISO-8601 string")
    void shouldSerializeZonedDateTimeAsIso8601() throws JsonProcessingException {
        // Given
        ZonedDateTime now = ZonedDateTime.now();

        // When
        String json = objectMapper.writeValueAsString(now);

        // Then
        assertThat(json).isNotEmpty();
        assertThat(json).doesNotContain("[");
    }

    @Test
    @DisplayName("should not fail on unknown properties during deserialization")
    void shouldNotFailOnUnknownProperties() {
        // Given — JSON with extra fields not in target class
        String json = """
                {"name":"test","unknownField":"value","anotherUnknown":123}
                """;

        // When / Then — should NOT throw
        assertThatCode(() -> objectMapper.readValue(json, TestDto.class))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should exclude null fields from serialization")
    void shouldExcludeNullFields() throws JsonProcessingException {
        // Given
        TestDto dto = new TestDto();
        dto.name = "test";
        dto.description = null; // should be excluded

        // When
        String json = objectMapper.writeValueAsString(dto);

        // Then
        assertThat(json).contains("name");
        assertThat(json).doesNotContain("description");
    }

    @Test
    @DisplayName("should have WRITE_DATES_AS_TIMESTAMPS disabled")
    void shouldHaveTimestampsDisabled() {
        assertThat(objectMapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isFalse();
    }

    // Helper DTO for testing
    static class TestDto {
        public String name;
        public String description;
    }
}
