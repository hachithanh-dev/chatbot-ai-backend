package dev.thanh.spring_ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thanh.spring_ai.dto.request.MessageDTO;
import dev.thanh.spring_ai.enums.MessageRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link RedisConfig}.
 * Verifies RedisTemplate serializer configurations without needing a real Redis.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisConfig — Unit Tests")
class RedisConfigTest {

    private RedisConfig redisConfig;

    @Mock
    private RedisConnectionFactory connectionFactory;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new JacksonConfig().objectMapper();
        RedisStreamProperties streamProps = new RedisStreamProperties();
        redisConfig = new RedisConfig(streamProps, objectMapper);
    }

    @Nested
    @DisplayName("redisTemplate — generic Object template")
    class RedisTemplateTests {

        @Test
        @DisplayName("should use StringRedisSerializer for keys")
        void shouldUseStringSerializerForKeys() {
            // When
            RedisTemplate<String, Object> template = redisConfig.redisTemplate(connectionFactory);

            // Then
            assertThat(template.getKeySerializer()).isInstanceOf(StringRedisSerializer.class);
            assertThat(template.getHashKeySerializer()).isInstanceOf(StringRedisSerializer.class);
        }

        @Test
        @DisplayName("should use GenericJackson2JsonRedisSerializer for values")
        void shouldUseJsonSerializerForValues() {
            // When
            RedisTemplate<String, Object> template = redisConfig.redisTemplate(connectionFactory);

            // Then
            assertThat(template.getValueSerializer()).isInstanceOf(GenericJackson2JsonRedisSerializer.class);
            assertThat(template.getHashValueSerializer()).isInstanceOf(GenericJackson2JsonRedisSerializer.class);
        }

        @Test
        @DisplayName("should use the provided connection factory")
        void shouldUseProvidedConnectionFactory() {
            // When
            RedisTemplate<String, Object> template = redisConfig.redisTemplate(connectionFactory);

            // Then
            assertThat(template.getConnectionFactory()).isSameAs(connectionFactory);
        }
    }

    @Nested
    @DisplayName("historyRedisTemplate — typed MessageDTO template")
    class HistoryRedisTemplateTests {

        @Test
        @DisplayName("should use StringRedisSerializer for keys")
        void shouldUseStringSerializerForKeys() {
            // When
            RedisTemplate<String, MessageDTO> template = redisConfig.historyRedisTemplate(connectionFactory);

            // Then
            assertThat(template.getKeySerializer()).isInstanceOf(StringRedisSerializer.class);
            assertThat(template.getHashKeySerializer()).isInstanceOf(StringRedisSerializer.class);
        }

        @Test
        @DisplayName("should use Jackson2JsonRedisSerializer (typed) for values — not GenericJackson2Json")
        void shouldUseTypedSerializerForValues() {
            // When
            RedisTemplate<String, MessageDTO> template = redisConfig.historyRedisTemplate(connectionFactory);

            // Then — typed serializer, NOT generic (smaller JSON, faster)
            assertThat(template.getValueSerializer()).isInstanceOf(Jackson2JsonRedisSerializer.class);
            assertThat(template.getValueSerializer()).isNotInstanceOf(GenericJackson2JsonRedisSerializer.class);
        }
    }

    @Nested
    @DisplayName("cacheManager — RedisCacheManager")
    class CacheManagerTests {

        @Test
        @DisplayName("should create CacheManager without error")
        void shouldCreateCacheManager() {
            // When / Then — no exception
            var cacheManager = redisConfig.cacheManager(connectionFactory);
            assertThat(cacheManager).isNotNull();
        }
    }
}
