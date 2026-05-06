package dev.thanh.spring_ai.service;

import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;

import dev.thanh.spring_ai.config.RedisStreamProperties;
import dev.thanh.spring_ai.dto.request.MessageDTO;
import dev.thanh.spring_ai.dto.request.StreamMessageMetadata;
import dev.thanh.spring_ai.entity.ChatMessage;
import dev.thanh.spring_ai.repository.BatchMessageRepository;
import dev.thanh.spring_ai.utils.SafeRedisExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisStreamService {

    private static final int LIMIT_SIZE = 10;
    private static final String HISTORY_PREFIX = "chat:history:";

    private final RedisTemplate<String, Object> redisTemplate;
    /**
     * Typed template cho history cache — Jackson2JsonRedisSerializer<MessageDTO>.
     * Không dùng DefaultTyping.NON_FINAL → không reflection, không @class metadata.
     * Tên field khớp bean name → Spring auto-resolve khi inject.
     */
    private final RedisTemplate<String, MessageDTO> historyRedisTemplate;
    private final RedisStreamProperties streamProperties;
    private final MessageProcessorService messageProcessor;
    private final DeadLetterQueueService dlqService;
    private final BatchMessageRepository batchRepository;
    private final SafeRedisExecutor safeRedis;
    private final ChatMetricsService chatMetrics;

    @PostConstruct
    public void initConsumerGroup() {
        String stream = streamProperties.getName();
        String group = streamProperties.getConsumerGroup();
        try {
            redisTemplate.opsForStream().createGroup(stream, ReadOffset.from("0-0"), group);
            log.info("✅ Consumer group '{}' created for stream '{}'", group, stream);
        } catch (Exception e) {
            handleGroupCreationError(stream, group, e);
        }
    }

    private void handleGroupCreationError(String stream, String group, Exception e) {
        if (rootCauseContains(e, "BUSYGROUP")) {
            log.info("✅ Consumer group '{}' already exists — reusing", group);
            return;
        }
        if (rootCauseContains(e, "ERR The XGROUP")) {
            bootstrapStream(stream, group);
            return;
        }
        log.warn("Could not init consumer group '{}': {}", group, e.getMessage());
    }

    private void bootstrapStream(String stream, String group) {
        redisTemplate.opsForStream().add(stream, Map.of("init", "true"));
        redisTemplate.opsForStream().createGroup(stream, ReadOffset.from("0-0"), group);
        log.info("✅ Stream '{}' bootstrapped with consumer group '{}'", stream, group);
    }

    private boolean rootCauseContains(Exception e, String keyword) {
        String msg = e.getMessage();
        if (msg != null && msg.contains(keyword))
            return true;
        Throwable cause = e.getCause();
        return cause != null && cause.getMessage() != null && cause.getMessage().contains(keyword);
    }

    /**
     * Push message to Redis Stream — protected by CircuitBreaker.
     * Nếu CB OPEN hoặc Redis fail → fallback direct insert vào PostgreSQL.
     */
    public void pushToStream(MessageDTO messageInfo) {
        safeRedis.tryCriticalExecuteOrElse(
                () -> {
                    // Map.of() — immutable, pre-sized, zero resize overhead
                    Map<String, String> messageData = Map.of(
                            "type", messageInfo.getRole().name(),
                            "id", messageInfo.getId(),
                            "sessionId", messageInfo.getSessionId(),
                            "content", messageInfo.getContent(),
                            "createdAt", messageInfo.getCreatedAt().toString());

                    StringRecord record = StreamRecords.string(messageData)
                            .withStreamKey(streamProperties.getName());
                    RecordId recordId = redisTemplate.opsForStream().add(record);

                    log.info("Pushed to stream: type={}, sessionId={}, recordId={}",
                            messageInfo.getRole().name(), messageInfo.getSessionId(), recordId);
                },
                () -> directDbFallback(messageInfo),
                "pushToStream");
    }

    /**
     * Get chat history from Redis cache — protected by CircuitBreaker.
     * Nếu CB OPEN → return empty list → ChatSessionService sẽ fallback sang DB.
     */
    public List<MessageDTO> getHistory(String sessionId) {
        String key = HISTORY_PREFIX + sessionId;
        return safeRedis.executeWithFallback(
                () -> {
                    // historyRedisTemplate trả về List<MessageDTO> trực tiếp — không cần cast
                    List<MessageDTO> history = historyRedisTemplate.opsForList().range(key, 0, -1);

                    if (history == null || history.isEmpty()) {
                        log.info("No history found for session {}", sessionId);
                        return List.<MessageDTO>of();
                    }

                    log.info("Retrieved history for session {}: {} messages", sessionId, history.size());
                    return history;
                },
                List::of,
                "getHistory");
    }

    public void trimStream(long maxLength) {
        safeRedis.tryExecute(
                () -> {
                    redisTemplate.opsForStream().trim(streamProperties.getName(), maxLength, true);
                    log.info("Trimmed stream to max {} messages", maxLength);
                },
                "trimStream");
    }

    public boolean hasHistory(String sessionId) {
        String key = HISTORY_PREFIX + sessionId;
        return safeRedis.executeWithFallback(
                () -> {
                    Long size = historyRedisTemplate.opsForList().size(key);
                    return size != null && size > 0;
                },
                () -> false,
                "hasHistory");
    }

    /**
     * Pipeline update for history cache — protected by CircuitBreaker.
     * Nếu CB OPEN → skip cache update, messages đã an toàn trong Redis Stream hoặc
     * DB.
     */
    public void updateHistoryCachePipeline(MessageDTO userMessage, MessageDTO assistantMessage) {
        safeRedis.tryExecute(
                () -> historyRedisTemplate.executePipelined(new SessionCallback<Object>() {
                    @Override
                    public <K, V> Object execute(@NonNull RedisOperations<K, V> operations)
                            throws DataAccessException {
                        @SuppressWarnings("unchecked")
                        RedisOperations<String, MessageDTO> ops = (RedisOperations<String, MessageDTO>) operations;
                        String sessionKey = HISTORY_PREFIX + userMessage.getSessionId();

                        ops.opsForList().rightPush(sessionKey, userMessage);
                        ops.opsForList().rightPush(sessionKey, assistantMessage);
                        ops.opsForList().trim(sessionKey, -LIMIT_SIZE, -1);
                        ops.expire(sessionKey, Duration.ofHours(24));

                        return null;
                    }
                }),
                "updateHistoryCache");
    }

    public void cacheHistory(String sessionId, List<MessageDTO> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        String key = HISTORY_PREFIX + sessionId;
        safeRedis.tryExecute(
                () -> {
                    historyRedisTemplate.opsForList().rightPushAll(key, messages);
                    historyRedisTemplate.expire(key, Duration.ofHours(24));
                    log.info("Cached {} messages for session {}", messages.size(), sessionId);
                },
                "cacheHistory");
    }

    /**
     * Clear the history cache for a specific session.
     * Called when a session is deleted.
     */
    public void clearHistory(String sessionId) {
        String key = HISTORY_PREFIX + sessionId;
        safeRedis.tryExecute(
                () -> {
                    Boolean deleted = historyRedisTemplate.delete(key);
                    if (Boolean.TRUE.equals(deleted)) {
                        log.info("Cleared history cache for session {}", sessionId);
                    }
                },
                "clearHistory");
    }

    /**
     * Consume new messages from Redis Stream — NOT protected by CircuitBreaker.
     *
     * Tại sao? Stream consumer chạy trong scheduler loop riêng biệt.
     * Nếu Redis die, read sẽ throw → scheduler catch → retry next cycle.
     * CB cho consumer sẽ gây vấn đề: CB OPEN → không đọc → messages tích tụ.
     */
    @SuppressWarnings("unchecked")
    public int consumeNewMessages(int consumerIndex) {
        String consumerName = streamProperties.getConsumerName() + "-" + consumerIndex;
        try {
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                    Consumer.from(streamProperties.getConsumerGroup(), consumerName),
                    StreamReadOptions.empty()
                            .count(streamProperties.getBatchSize())
                            .block(Duration.ofMillis(streamProperties.getBlockDurationMs())),
                    StreamOffset.create(streamProperties.getName(), ReadOffset.lastConsumed()));

            if (records == null || records.isEmpty()) {
                return 0;
            }

            log.info("Consumer-{} read {} messages", consumerIndex, records.size());
            return processMessageBatch(records);

        } catch (Exception e) {
            log.error("Consumer-{} error reading from stream", consumerIndex, e);
            return 0;
        }
    }

    public int processMessageBatch(List<MapRecord<String, Object, Object>> records) {
        try {
            // Phase 1: Transform stream entries to domain entities
            List<ChatMessage> messages = records.stream()
                    .map(messageProcessor::transformToChatMessage)
                    .filter(Objects::nonNull)
                    .toList();

            if (messages.isEmpty()) {
                log.info("No valid messages after transformation");
                return 0;
            }

            // Phase 2: Batch insert to PostgreSQL — đo thời gian insert
            Timer.Sample batchTimer = chatMetrics.startBatchInsertTimer();
            int insertedCount = batchRepository.batchInsert(messages);
            chatMetrics.stopBatchInsertTimer(batchTimer);

            List<String> messageIds = records.stream()
                    .map(entry -> entry.getId().getValue())
                    .toList();

            acknowledgeMessages(messageIds);

            // Cập nhật pending messages gauge (giảm sau khi xử lý xong)
            updatePendingMessagesGauge();

            log.info("Successfully processed batch: {} messages", insertedCount);
            return insertedCount;

        } catch (Exception e) {
            log.error("Batch insert failed, falling back to single insert for {} messages", records.size(), e);
            return handleFailedBatch(records);
        }
    }

    private void acknowledgeMessages(List<String> messageIds) {
        if (messageIds.isEmpty())
            return;
        try {
            Long ackedCount = redisTemplate.opsForStream().acknowledge(
                    streamProperties.getName(),
                    streamProperties.getConsumerGroup(),
                    messageIds.toArray(new String[0]));
            log.debug("Acknowledged {} messages", ackedCount);
        } catch (Exception e) {
            log.error("Failed to acknowledge messages (non-critical, will retry)", e);
        }
    }

    private int handleFailedBatch(List<MapRecord<String, Object, Object>> records) {
        List<String> succeededIds = new ArrayList<>();
        List<String> dlqIds = new ArrayList<>();
        int insertedCount = 0;

        for (MapRecord<String, Object, Object> entry : records) {
            String messageId = entry.getId().getValue();
            try {
                ChatMessage message = messageProcessor.transformToChatMessage(entry);
                if (message != null && batchRepository.singleInsert(message)) {
                    insertedCount++;
                }
                succeededIds.add(messageId);
            } catch (Exception singleError) {
                log.error("Single insert failed for message {}", messageId, singleError);
                StreamMessageMetadata metadata = getSpecificMessageMetadata(messageId);
                if (metadata != null && metadata.getDeliveryCount() >= streamProperties.getMaxRetryAttempts()) {
                    log.warn("Message {} exceeded max retries, moving to DLQ", messageId);
                    dlqService.sendToDeadLetterQueue(entry, singleError);
                    dlqIds.add(messageId);
                }
                // else: không acknowledge → Redis Stream sẽ tự redeliver
            }
        }

        // Acknowledge tất cả 1 lần duy nhất
        List<String> allAckIds = new ArrayList<>(succeededIds);
        allAckIds.addAll(dlqIds);
        acknowledgeMessages(allAckIds);

        log.info("Fallback result: inserted={}, dlq={}, pendingRetry={}",
                insertedCount, dlqIds.size(), records.size() - succeededIds.size() - dlqIds.size());
        return insertedCount;
    }

    private StreamMessageMetadata getSpecificMessageMetadata(String messageId) {
        try {
            PendingMessages pending = redisTemplate.opsForStream().pending(
                    streamProperties.getName(),
                    Consumer.from(streamProperties.getConsumerGroup(), streamProperties.getConsumerName()),
                    Range.closed(messageId, messageId),
                    1L);

            if (pending != null && !pending.isEmpty()) {
                PendingMessage msg = pending.get(0);
                return StreamMessageMetadata.builder()
                        .messageId(msg.getIdAsString())
                        .deliveryCount((int) msg.getTotalDeliveryCount())
                        .build();
            }
        } catch (Exception e) {
            log.error("Error fetching metadata for {}", messageId, e);
        }
        return null;
    }

    /**
     * Direct DB insert fallback khi Redis Stream không khả dụng.
     * Convert MessageDTO → ChatMessage entity và insert trực tiếp vào PostgreSQL.
     * <p>
     * ON CONFLICT (message_id) DO NOTHING đảm bảo idempotent nếu message
     * trùng với data đã persist qua path khác.
     */
    private void directDbFallback(MessageDTO messageInfo) {
        try {
            ChatMessage chatMessage = ChatMessage.builder()
                    .id(UUID.fromString(messageInfo.getId()))
                    .messageId(messageInfo.getId())
                    .sessionId(UUID.fromString(messageInfo.getSessionId()))
                    .role(messageInfo.getRole())
                    .content(messageInfo.getContent())
                    .createdAt(messageInfo.getCreatedAt())
                    .build();

            batchRepository.singleInsert(chatMessage);
            log.info("Direct DB fallback: persisted message for session {} (Redis unavailable)",
                    messageInfo.getSessionId());
        } catch (Exception dbError) {
            log.error("CRITICAL: Both Redis AND DB insert failed for message {}. DATA LOSS!",
                    messageInfo.getId(), dbError);
        }
    }

    /**
     * Get CircuitBreaker state — exposed for health monitoring via Actuator.
     */
    public SafeRedisExecutor getSafeRedis() {
        return safeRedis;
    }

    /**
     * Cập nhật gauge pending messages từ Redis Stream.
     * Gọi sau mỗi lần consume batch để Prometheus có giá trị realtime.
     */
    private void updatePendingMessagesGauge() {
        try {
            PendingMessagesSummary summary = redisTemplate.opsForStream().pending(
                    streamProperties.getName(),
                    streamProperties.getConsumerGroup());
            if (summary != null) {
                chatMetrics.getPendingMessages().set((int) summary.getTotalPendingMessages());
            }
        } catch (Exception e) {
            log.debug("Failed to update pending messages gauge: {}", e.getMessage());
        }
    }
}