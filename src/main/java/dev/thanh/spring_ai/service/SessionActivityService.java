package dev.thanh.spring_ai.service;

import dev.thanh.spring_ai.utils.SafeRedisExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Quản lý 2 Redis ZSETs cho session activity tracking:
 * <ul>
 * <li><b>ZSET 1 (User Sessions)</b>: {@code chat:user:{userId}:sessions} — phục
 * vụ API đọc danh sách session nhanh</li>
 * <li><b>ZSET 2 (Global Dirty)</b>: {@code system:dirty_sessions} — phục vụ
 * background scheduler sync xuống DB</li>
 * </ul>
 *
 * Score = epoch millis (timestamp activity mới nhất).
 *
 * Tất cả Redis operations đều được bảo vệ bởi {@link SafeRedisExecutor}.
 * Khi CB OPEN → fail-fast, return safe defaults (empty list, 0, etc.)
 * → ChatSessionService fallback sang DB queries.
 */
@Slf4j(topic = "SESSION-ACTIVITY")
@Service
@RequiredArgsConstructor
public class SessionActivityService {

    private static final String USER_SESSIONS_PREFIX = "chat:user:";
    private static final String USER_SESSIONS_SUFFIX = ":sessions";
    private static final String DIRTY_SESSIONS_KEY = "system:dirty_sessions";
    /**
     * Giới hạn tối đa số session được cache trong ZSET 1 (~5 trang × pageSize 10).
     */
    private static final int MAX_ZSET_SIZE = 50;

    private final RedisTemplate<String, Object> redisTemplate;
    private final SafeRedisExecutor safeRedis;

    @Value("${session.sync.user-sessions-ttl-hours:24}")
    private int userSessionsTtlHours;

    /**
     * Gọi khi user chat trên session (mới hoặc cũ).
     * Pipeline atomic: cập nhật cả ZSET 1 + ZSET 2 + refresh TTL trong 1
     * round-trip.
     */
    public void touchSession(String userId, String sessionId) {
        double score = System.currentTimeMillis();
        String userKey = buildUserKey(userId);

        safeRedis.tryExecute(
                () -> {
                    redisTemplate.executePipelined(new SessionCallback<Object>() {
                        @Override
                        @SuppressWarnings("unchecked")
                        public <K, V> Object execute(@NonNull RedisOperations<K, V> operations)
                                throws DataAccessException {
                            RedisOperations<String, Object> ops = (RedisOperations<String, Object>) operations;

                            // ZSET 1: User Sessions — score = timestamp hiện tại
                            ops.opsForZSet().add(userKey, sessionId, score);
                            ops.expire(userKey, Duration.ofHours(userSessionsTtlHours));

                            // ZSET 2: Global Dirty — scheduler sẽ pop và sync xuống DB
                            ops.opsForZSet().add(DIRTY_SESSIONS_KEY, sessionId, score);

                            return null;
                        }
                    });

                    log.debug("Touched session activity: userId={}, sessionId={}", userId, sessionId);
                },
                "touchSession");
    }

    /**
     * Lấy updatedAt từ ZSET 1 cho danh sách sessionIds.
     *
     * @return Map&lt;sessionId, LocalDateTime&gt; — chỉ chứa entries tồn tại trong
     *         ZSET.
     */
    public Map<String, LocalDateTime> getSessionTimestamps(String userId, List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return Collections.emptyMap();
        }

        String userKey = buildUserKey(userId);

        return safeRedis.executeOrReject(
                () -> {
                    Map<String, LocalDateTime> result = new HashMap<>();

                    // Pipeline: ZSCORE cho mỗi sessionId trong 1 round-trip
                    List<Object> scores = redisTemplate.executePipelined(new SessionCallback<Object>() {
                        @Override
                        @SuppressWarnings("unchecked")
                        public <K, V> Object execute(@NonNull RedisOperations<K, V> operations)
                                throws DataAccessException {
                            RedisOperations<String, Object> ops = (RedisOperations<String, Object>) operations;
                            for (String sessionId : sessionIds) {
                                ops.opsForZSet().score(userKey, sessionId);
                            }
                            return null;
                        }
                    });

                    for (int i = 0; i < sessionIds.size(); i++) {
                        Object score = scores.get(i);
                        if (score instanceof Double doubleScore) {
                            result.put(sessionIds.get(i), millisToLocalDateTime(doubleScore.longValue()));
                        }
                    }

                    return result;
                },
                Collections::emptyMap,
                "getSessionTimestamps");
    }

    /**
     * Lấy kích thước ZSET 1 (dùng để check cold start).
     *
     * @return số phần tử trong ZSET, hoặc 0 nếu Redis lỗi.
     */
    public Long getZSetSize(String userId) {
        String userKey = buildUserKey(userId);
        return safeRedis.executeOrReject(
                () -> redisTemplate.opsForZSet().zCard(userKey),
                () -> 0L,
                "getZSetSize");
    }

    /**
     * Lấy sessions từ ZSET 1 theo score range (cursor-based pagination).
     * Trả về ordered set mới nhất trước.
     *
     * @param userId user ID
     * @param min    min score (inclusive)
     * @param max    max score (inclusive)
     * @param offset offset trong range
     * @param count  số phần tử cần lấy
     * @return Ordered set of TypedTuple(sessionId, score), hoặc empty set nếu lỗi.
     */
    public Set<ZSetOperations.TypedTuple<Object>> reverseRangeByScoreWithScores(
            String userId, double min, double max, long offset, long count) {
        String userKey = buildUserKey(userId);
        return safeRedis.executeOrReject(
                () -> redisTemplate.opsForZSet()
                        .reverseRangeByScoreWithScores(userKey, min, max, offset, count),
                Collections::emptySet,
                "reverseRangeByScore");
    }

    /**
     * Lấy top N session IDs mới nhất từ ZSET 1 (cho trang đầu tiên khi không có
     * cursor).
     *
     * @return Ordered list, mới nhất trước.
     */
    public List<String> getRecentSessionIds(String userId, int limit) {
        String userKey = buildUserKey(userId);

        return safeRedis.executeOrReject(
                () -> {
                    Set<ZSetOperations.TypedTuple<Object>> tuples = redisTemplate.opsForZSet()
                            .reverseRangeWithScores(userKey, 0, (long) limit - 1);

                    if (tuples == null || tuples.isEmpty()) {
                        return Collections.<String>emptyList();
                    }

                    return tuples.stream()
                            .map(tuple -> (String) tuple.getValue())
                            .toList();
                },
                Collections::emptyList,
                "getRecentSessionIds");
    }

    /**
     * ZPOPMIN batch từ ZSET 2 (Global Dirty) — scheduler gọi mỗi 10s.
     *
     * NOT protected by CB — scheduler must always attempt to drain dirty sessions.
     * Nếu Redis die, scheduler catch exception → retry next cycle.
     *
     * @return Map&lt;sessionId, LocalDateTime&gt; — các sessions cần sync xuống DB.
     */
    public Map<String, LocalDateTime> popDirtySessions(int batchSize) {
        try {
            Set<ZSetOperations.TypedTuple<Object>> popped = redisTemplate.opsForZSet()
                    .popMin(DIRTY_SESSIONS_KEY, batchSize);

            if (popped == null || popped.isEmpty()) {
                return Collections.emptyMap();
            }

            Map<String, LocalDateTime> result = new LinkedHashMap<>();
            for (ZSetOperations.TypedTuple<Object> tuple : popped) {
                String sessionId = (String) tuple.getValue();
                Double score = tuple.getScore();
                if (sessionId != null && score != null) {
                    result.put(sessionId, millisToLocalDateTime(score.longValue()));
                }
            }

            log.info("Popped {} dirty sessions for DB sync", result.size());
            return result;
        } catch (Exception e) {
            log.error("Failed to pop dirty sessions from Redis", e);
            return Collections.emptyMap();
        }
    }

    /**
     * Xóa session khỏi cả 2 ZSET (khi soft-delete).
     */
    public void removeSession(String userId, String sessionId) {
        String userKey = buildUserKey(userId);

        safeRedis.tryExecute(
                () -> {
                    redisTemplate.executePipelined(new SessionCallback<Object>() {
                        @Override
                        @SuppressWarnings("unchecked")
                        public <K, V> Object execute(@NonNull RedisOperations<K, V> operations)
                                throws DataAccessException {
                            RedisOperations<String, Object> ops = (RedisOperations<String, Object>) operations;

                            ops.opsForZSet().remove(userKey, sessionId);
                            ops.opsForZSet().remove(DIRTY_SESSIONS_KEY, sessionId);

                            return null;
                        }
                    });

                    log.info("Removed session {} from activity ZSETs for user {}", sessionId, userId);
                },
                "removeSession");
    }

    /**
     * Warm up ZSET 1 từ DB data khi cold start (ZSET trống).
     * Pipeline atomic: ZADD all + TTL trong 1 round-trip.
     *
     * @param userId        user ID
     * @param sessionScores map sessionId → epoch millis score
     */
    public void warmUpFromDb(String userId, Map<String, Double> sessionScores) {
        if (sessionScores == null || sessionScores.isEmpty()) {
            return;
        }

        String userKey = buildUserKey(userId);

        safeRedis.tryExecute(
                () -> {
                    redisTemplate.executePipelined(new SessionCallback<Object>() {
                        @Override
                        @SuppressWarnings("unchecked")
                        public <K, V> Object execute(@NonNull RedisOperations<K, V> operations)
                                throws DataAccessException {
                            RedisOperations<String, Object> ops = (RedisOperations<String, Object>) operations;

                            for (Map.Entry<String, Double> entry : sessionScores.entrySet()) {
                                ops.opsForZSet().add(userKey, entry.getKey(), entry.getValue());
                            }
                            // Trim về tối đa MAX_ZSET_SIZE: giữ top [newest], xóa oldest
                            // ZREMRANGEBYRANK 0 -(N+1) → xóa từ rank thấp nhất đến thứ N+1 từ cuối
                            ops.opsForZSet().removeRange(userKey, 0, -(MAX_ZSET_SIZE + 1));
                            ops.expire(userKey, Duration.ofHours(userSessionsTtlHours));

                            return null;
                        }
                    });

                    log.info("Warmed up Redis ZSET for user {} with {} sessions", userId, sessionScores.size());
                },
                "warmUpFromDb");
    }

    private String buildUserKey(String userId) {
        return USER_SESSIONS_PREFIX + userId + USER_SESSIONS_SUFFIX;
    }

    private LocalDateTime millisToLocalDateTime(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }
}
