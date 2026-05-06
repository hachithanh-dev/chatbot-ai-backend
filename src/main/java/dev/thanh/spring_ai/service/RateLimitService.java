package dev.thanh.spring_ai.service;

import dev.thanh.spring_ai.config.RateLimitProperties;
import dev.thanh.spring_ai.enums.RateLimitErrorCode;
import dev.thanh.spring_ai.exception.RateLimitException;
import dev.thanh.spring_ai.utils.SafeRedisExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.scripting.support.ResourceScriptSource;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@Slf4j(topic = "RATE-LIMIT")
@RequiredArgsConstructor
@SuppressWarnings("rawtypes")
public class RateLimitService {

    private static final String BUCKET_KEY_PREFIX = "rate_limit:bucket:";
    private static final String DAILY_TOKENS_KEY_PREFIX = "rate_limit:tokens:daily:";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties props;
    private final SafeRedisExecutor safeRedis;

    // ─────────────────────────────────────────────────────────────────────────
    // Lua Script: Token Bucket (atomic refill + consume) — Read-Compute-Write
    // bắt buộc atomic, không thể thay bằng Redis commands đơn lẻ.
    // Returns: [allowed(1/0), tokensLeft, retryAfterSeconds]
    // ─────────────────────────────────────────────────────────────────────────
    private static final DefaultRedisScript<List> TOKEN_BUCKET_SCRIPT;

    static {
        TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>();
        TOKEN_BUCKET_SCRIPT.setResultType(List.class);
        TOKEN_BUCKET_SCRIPT.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/token_bucket.lua")));
    }


    // ─────────────────────────────────────────────────────────────────────────
    // Layer 1: Token Bucket
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Check token bucket rate limit — protected by CircuitBreaker.
     * <p>
     * Fail-open strategy: khi Redis down → cho phép request đi tiếp.
     * Lý do: rate limit là bảo vệ phụ, không nên block user khi infra lỗi.
     * Khi Redis recovery → CB CLOSED → rate limit tự động hoạt động lại.
     */
    public void checkTokenBucket(String userId) {
        String bucketKey = BUCKET_KEY_PREFIX + userId;
        long nowMs = System.currentTimeMillis();

        List result = safeRedis.executeWithFallback(
                () -> redisTemplate.execute(
                        TOKEN_BUCKET_SCRIPT,
                        List.of(bucketKey),
                        String.valueOf(props.getBucketCapacity()),
                        String.valueOf(props.getRefillRatePerSecond()),
                        String.valueOf(nowMs)),
                () -> null,   // fail-open: trả null → cho qua
                "checkTokenBucket"
        );

        if (result == null) {
            log.warn("Token bucket check skipped for user={} (Redis unavailable), fail-open", userId);
            return;
        }

        long allowed = toLong(result.get(0));
        long tokensLeft = toLong(result.get(1));
        long retryAfterSec = toLong(result.get(2));

        if (allowed == 0) {
            log.warn("Layer 1 BLOCKED user={} retryAfter={}s", userId, retryAfterSec);
            throw new RateLimitException(RateLimitErrorCode.TOO_MANY_REQUESTS, retryAfterSec);
        }
        log.debug("Layer 1 OK user={} tokensLeft={}", userId, tokensLeft);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Layer 2: Daily Token Quota — PRE-FLIGHT (check only, no increment)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Pre-flight: chỉ CHECK xem user đã vượt quota chưa — KHÔNG increment.
     * <p>
     * Actual quota consumption (totalTokens từ Gemini metadata) được cộng
     * post-flight bởi {@link #consumeTokens(String, int)}.
     * <p>
     * Fail-open strategy: khi Redis down → cho phép request đi tiếp.
     */
    public void checkDailyTokenQuota(String userId) {
        String today = LocalDate.now(ZoneOffset.UTC).format(DATE_FMT);
        String dailyKey = DAILY_TOKENS_KEY_PREFIX + userId + ":" + today;

        String rawValue = safeRedis.executeWithFallback(
                () -> redisTemplate.opsForValue().get(dailyKey),
                () -> null,   // fail-open: trả null → cho qua
                "checkDailyQuota"
        );

        long dailyUsed = (rawValue != null) ? Long.parseLong(rawValue) : 0L;
        long dailyLimit = props.getDailyTokenLimit();

        if (dailyUsed >= dailyLimit) {
            log.warn("Layer 2 BLOCKED user={} dailyUsed={} dailyLimit={}", userId, dailyUsed, dailyLimit);
            throw new RateLimitException(RateLimitErrorCode.DAILY_TOKEN_LIMIT_EXCEEDED,
                    dailyUsed, dailyLimit);
        }
        log.debug("Layer 2 OK user={} dailyUsed={}/{}", userId, dailyUsed, dailyLimit);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Layer 2: Daily Token Quota — POST-FLIGHT (increment only, fire-and-forget)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Post-flight: cộng totalTokens (từ Gemini metadata) vào daily quota.
     * <p>
     * Con số này bao gồm: prompt tokens + RAG context tokens + output tokens.
     * Mapping 1:1 với billing thực tế từ Gemini API.
     * <p>
     * Fire-and-forget — không block, không reject.
     * Fail-open nếu Redis down.
     *
     * @param userId      user identifier
     * @param totalTokens total tokens from Gemini response metadata
     */
    public void consumeTokens(String userId, int totalTokens) {
        if (totalTokens <= 0) return;

        String today = LocalDate.now(ZoneOffset.UTC).format(DATE_FMT);
        String dailyKey = DAILY_TOKENS_KEY_PREFIX + userId + ":" + today;

        Long newUsed = safeRedis.executeWithFallback(
                () -> redisTemplate.opsForValue().increment(dailyKey, totalTokens),
                () -> null,
                "consumeTokens"
        );

        // Set TTL lần đầu khi key vừa được tạo (newUsed == totalTokens → key bắt đầu từ 0)
        if (newUsed != null && newUsed == totalTokens) {
            safeRedis.executeWithFallback(
                    () -> {
                        redisTemplate.expire(dailyKey, Duration.ofHours(25));
                        return null;
                    },
                    () -> null,
                    "consumeTokens:setTTL"
            );
        }

        log.debug("Post-flight quota: added {} total tokens (Gemini metadata) for user={}, newUsed={}",
                totalTokens, userId, newUsed);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private long toLong(Object val) {
        if (val instanceof Number number)
            return number.longValue();
        if (val instanceof String string)
            return Long.parseLong(string);
        return 0L;
    }
}
