package dev.thanh.spring_ai.service;

import dev.thanh.spring_ai.config.RateLimitProperties;
import dev.thanh.spring_ai.enums.RateLimitErrorCode;
import dev.thanh.spring_ai.exception.RateLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for RateLimitService that run Lua scripts on a REAL Redis instance.
 * Uses Testcontainers with standard GenericContainer for maximum compatibility.
 *
 * BLOCKER 2 Fix: @ActiveProfiles("integration") loads application-integration.yml.
 */
@DisplayName("RateLimitService — Integration Tests (Redis Testcontainers)")
class RateLimitServiceIntegrationTest extends dev.thanh.spring_ai.config.AbstractIntegrationTest {

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RateLimitProperties rateLimitProperties;

    private String userId;

    @BeforeEach
    void setUp() {
        // GIÁP BẢO VỆ 2: Quét sạch rác Redis từ các test trước đó (Chống State Leakage tuyệt đối)
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();

        // Use unique userId per test to avoid cross-test contamination
        userId = "test-user-" + System.nanoTime();
    }

    // ─────────────────────────────────────────────────────────
    // Layer 1: Token Bucket — Lua Script on Real Redis
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("checkTokenBucket — first request — should allow (bucket has capacity)")
    void checkTokenBucket_WhenFirstRequest_ShouldAllow() {
        // When / Then: no exception on first call
        assertThatCode(() -> rateLimitService.checkTokenBucket(userId))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("checkTokenBucket — when bucket exhausted — should throw RateLimitException")
    void checkTokenBucket_WhenBucketExhausted_ShouldThrow() {
        // Given: exhaust the bucket (bucketCapacity + 1 calls)
        int capacity = (int) rateLimitProperties.getBucketCapacity();

        // Drain all tokens
        for (int i = 0; i < capacity; i++) {
            assertThatCode(() -> rateLimitService.checkTokenBucket(userId))
                    .doesNotThrowAnyException();
        }

        // When: one more request exceeds capacity
        assertThatThrownBy(() -> rateLimitService.checkTokenBucket(userId))
                .isInstanceOf(RateLimitException.class)
                .satisfies(ex -> {
                    RateLimitException rle = (RateLimitException) ex;
                    assertThat(rle.getErrorCode()).isEqualTo(RateLimitErrorCode.TOO_MANY_REQUESTS);
                    assertThat(rle.getRetryAfterSeconds()).isPositive();
                });
    }

    // ─────────────────────────────────────────────────────────
    // Layer 2: Daily Token Quota — Plain Redis commands
    // checkDailyTokenQuota(userId) = pre-flight CHECK only
    // consumeTokens(userId, tokens) = post-flight INCREMENT
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("checkDailyTokenQuota — no usage yet — should allow")
    void checkDailyTokenQuota_WhenNoUsage_ShouldAllow() {
        // When / Then: no tokens consumed yet → should pass
        assertThatCode(() -> rateLimitService.checkDailyTokenQuota(userId))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("checkDailyTokenQuota — within limit after consumeTokens — should allow")
    void checkDailyTokenQuota_WhenWithinLimit_ShouldAllow() {
        // Given: consume a small amount, well under daily limit
        rateLimitService.consumeTokens(userId, 100);

        // When / Then: check should still pass
        assertThatCode(() -> rateLimitService.checkDailyTokenQuota(userId))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("checkDailyTokenQuota — when accumulated >= daily limit — should throw")
    void checkDailyTokenQuota_WhenExceedLimit_ShouldThrow() {
        long dailyLimit = rateLimitProperties.getDailyTokenLimit();

        // Given: consume all quota via post-flight
        rateLimitService.consumeTokens(userId, (int) dailyLimit);

        // When: pre-flight check should now reject
        assertThatThrownBy(() -> rateLimitService.checkDailyTokenQuota(userId))
                .isInstanceOf(RateLimitException.class)
                .satisfies(ex -> {
                    RateLimitException rle = (RateLimitException) ex;
                    assertThat(rle.getErrorCode()).isEqualTo(RateLimitErrorCode.DAILY_TOKEN_LIMIT_EXCEEDED);
                    assertThat(rle.getTokenUsed()).isGreaterThanOrEqualTo(dailyLimit);
                });
    }

    @Test
    @DisplayName("consumeTokens — first call — should set TTL ~25h on daily key")
    void consumeTokens_WhenFirstCall_ShouldSetTtlTo25Hours() {
        // Given: first consume creates the daily key
        rateLimitService.consumeTokens(userId, 10);

        // Then: verify the key exists in Redis with a TTL
        String keyPattern = "rate_limit:tokens:daily:" + userId + ":*";
        var keys = redisTemplate.keys(keyPattern);
        assertThat(keys).isNotEmpty();

        // Verify TTL is set and is approximately 25 hours (25 * 3600 = 90000 seconds)
        Long ttl = redisTemplate.getExpire(keys.iterator().next());
        assertThat(ttl).isNotNull()
                .isPositive()
                .isLessThanOrEqualTo(25L * 3600);
    }
}
