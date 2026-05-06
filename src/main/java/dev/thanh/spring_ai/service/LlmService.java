package dev.thanh.spring_ai.service;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
@ConditionalOnProperty(name = "llm.mock.enabled", havingValue = "false", matchIfMissing = true)
public class LlmService implements LlmServicePort {

    private static final String RESILIENCE_NAME = "llm-gemini";

    // ─────────────────────────────────────────────────────────────────────────
    // Metric name constants — tránh typo, dễ refactor
    // ─────────────────────────────────────────────────────────────────────────
    private static final class Metrics {
        static final String STREAM_STATUS = "llm.stream.status";
        static final String STREAM_TTFB = "llm.stream.ttfb";
        static final String TOKEN_USAGE = "llm.token.usage";
    }

    private final ChatClient chatClient;
    private final ChatClient cheapChatClient;
    private final CircuitBreaker circuitBreaker;
    private final RateLimiter rateLimiter;
    private final Bulkhead bulkhead;
    private final MeterRegistry meterRegistry;
    private final RateLimitService rateLimitService;
    private final Executor virtualThreadExecutor;

    @Value("classpath:prompts/agentic-system-prompt.st")
    private Resource agenticSystemPrompt;

    public LlmService(ChatClient chatClient,
            @Qualifier("cheapChatClient") ChatClient cheapChatClient,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RateLimiterRegistry rateLimiterRegistry,
            BulkheadRegistry bulkheadRegistry,
            MeterRegistry meterRegistry,
            RateLimitService rateLimitService,
            Executor virtualThreadExecutor) {
        this.chatClient = chatClient;
        this.cheapChatClient = cheapChatClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(RESILIENCE_NAME);
        this.rateLimiter = rateLimiterRegistry.rateLimiter(RESILIENCE_NAME);
        this.bulkhead = bulkheadRegistry.bulkhead(RESILIENCE_NAME);
        this.meterRegistry = meterRegistry;
        this.rateLimitService = rateLimitService;
        this.virtualThreadExecutor = virtualThreadExecutor;
        log.info(
                "LlmService initialized — Agentic RAG + Memory mode | CB: {}, RL: limitForPeriod={}, BH: maxConcurrent={}",
                RESILIENCE_NAME,
                rateLimiter.getRateLimiterConfig().getLimitForPeriod(),
                bulkhead.getBulkheadConfig().getMaxConcurrentCalls());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // streamResponse — Main chat stream với full Resilience4j stack
    // Nội bộ dùng .chatResponse() để capture usage metadata từ Gemini,
    // map về Flux<String> giữ nguyên contract cũ.
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public Flux<String> streamResponse(String userMsg, List<Message> history, String userId) {
        log.info("Requesting Gemini stream (Agentic RAG). UserMsg length: {}", userMsg.length());

        AtomicBoolean hasEmittedData = new AtomicBoolean(false);
        AtomicBoolean ttfbStopped = new AtomicBoolean(false);

        // Capture actual token usage từ Gemini metadata (chunk cuối)
        AtomicInteger actualTotalTokens = new AtomicInteger(0);

        // Đo TTFB (Time To First Byte) — từ lúc gọi method → token đầu tiên
        // Bao gồm thời gian xếp hàng RL/BH + tool execution → phản ánh TTFB thực từ góc
        // user
        Timer.Sample ttfbTimer = Timer.start(meterRegistry);

        return chatClient.prompt()
                .system(agenticSystemPrompt)
                .messages(history)
                .user(userMsg)
                .toolContext(Map.of("userId", userId)) // Truyền userId cho MemoryTools (ToolContext)
                .advisors(spec -> spec.param("userId", userId)) // Truyền userId cho Advisors
                                                                // (ChatClientRequest.context)
                .stream()
                .chatResponse() // Dùng chatResponse() thay vì content() để capture usage metadata
                .doOnSubscribe(s -> log.info("Gemini stream subscribed (tools: augmented via AiConfig)"))

                // ── Capture usage metadata + đánh dấu TTFB ──
                .doOnNext(response -> processStreamResponseMetadata(response, actualTotalTokens, hasEmittedData,
                        ttfbStopped, ttfbTimer))

                // ── Map ChatResponse → String (giữ nguyên contract Flux<String>) ──
                .map(this::extractContent)
                .filter(s -> !s.isEmpty())

                // Buffer 256 tokens để chịu được downstream SSE client chậm (backpressure)
                .onBackpressureBuffer(256)

                // ── TIMEOUT 2 PHA ──────────────────────────────────────
                // Phase 1: Đợi tối đa 60s cho token đầu tiên (Gemini cold start / thinking)
                // Phase 2: Từ token thứ 2 trở đi, chỉ cho phép idle tối đa 5s giữa các token
                .timeout(Mono.delay(Duration.ofSeconds(60)), v -> Mono.delay(Duration.ofSeconds(5)))

                // ── Resilience4j Stack: bọc từ trong ra ngoài ──────────
                // Thứ tự subscribe (ngoài → trong): RL → BH → CB → stream
                // CB trong cùng → chỉ đếm failure từ Gemini, KHÔNG bị RL/BH rejection nhiễu
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker)) // Trong: failure detection
                .transformDeferred(BulkheadOperator.of(bulkhead)) // Giữa: concurrent limit
                .transformDeferred(RateLimiterOperator.of(rateLimiter)) // Ngoài: RPM throttle

                // ── RETRY: ngoài RL — mỗi attempt đi lại qua RL→BH→CB ──
                .retryWhen(Retry.backoff(2, Duration.ofMillis(500))
                        .jitter(0.5)
                        .filter(error -> isSafeToRetry(error, hasEmittedData.get()))
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))

                // ── Metrics: phân loại kết quả stream ──
                .doOnComplete(() -> meterRegistry.counter(Metrics.STREAM_STATUS, "result", "success",
                        "type", "none").increment())
                .doOnError(e -> meterRegistry.counter(Metrics.STREAM_STATUS, "result", "error",
                        "type", e.getClass().getSimpleName()).increment())

                // ── Post-flight: consume quota + stop TTFB fallback ──
                .doFinally(signal -> handleStreamFinally(actualTotalTokens, userId, ttfbStopped, ttfbTimer))

                // ── Terminal error handler: normalize lỗi cuối cùng ──
                .onErrorResume(e -> handleFinalTerminalError(e, hasEmittedData.get()));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // generateTitle — Chỉ CircuitBreaker, KHÔNG cần RateLimiter/Bulkhead
    // Dùng chung default model từ config (application.yaml).
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public Mono<String> generateTitle(String userMsg) {
        String titlePrompt = """
                Summarize the following user message into a short title (5-10 words).
                Do not use quotes.
                Language must match the user message.
                Message: %s
                """.formatted(userMsg);
        return Mono.fromCallable(() -> cheapChatClient.prompt()
                .user(titlePrompt)
                .call()
                .content())
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(10))
                .map(String::trim)
                // ── Circuit Breaker: detect slow blocking call timeout ──
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorResume(CallNotPermittedException.class, e -> {
                    log.warn("Circuit breaker '{}' is OPEN — generateTitle rejected. Returning empty.",
                            RESILIENCE_NAME);
                    return Mono.empty();
                })
                .onErrorResume(e -> {
                    log.warn("Failed to generate title", e);
                    return Mono.empty();
                });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // extractText / extractContent — DRY: logic trích xuất text duy nhất
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Trích xuất text content từ ChatResponse.
     * Dùng chung cho cả doOnNext (TTFB detection) lẫn map (emit content).
     */
    private String extractText(ChatResponse response) {
        if (response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    /**
     * Map ChatResponse → String content (giữ nguyên contract Flux<String>).
     * Trả "" cho null/empty để filter bên ngoài loại bỏ.
     */
    private String extractContent(ChatResponse response) {
        String text = extractText(response);
        return text != null ? text : "";
    }

    // ═══════════════════════════════════════════════════════════════════════
    // isSafeToRetry — Whitelist nghiêm ngặt
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Kiểm soát retry cực kỳ khắt khe.
     * <p>
     * KHÔNG BAO GIỜ retry nếu:
     * <ul>
     * <li>Đã emit data lên client → retry sẽ tạo text bị lặp trên UI</li>
     * <li>Lỗi từ hàng rào bảo vệ nội bộ (BH/RL/CB) → retry vô nghĩa</li>
     * </ul>
     * CHỈ retry nếu:
     * <ul>
     * <li>{@link TimeoutException} — Gemini idle quá lâu</li>
     * <li>{@link IOException} — network failure (bao gồm ConnectException)</li>
     * </ul>
     */
    private boolean isSafeToRetry(Throwable e, boolean hasEmitted) {
        if (hasEmitted) {
            log.warn("Stream đứt giữa chừng sau khi đã emit data. Hủy retry để không lặp text trên UI.");
            return false;
        }

        if (e instanceof BulkheadFullException
                || e instanceof RequestNotPermitted
                || e instanceof CallNotPermittedException) {
            return false;
        }

        // Bóc lớp wrapper để lấy root cause thực sự từ Spring
        Throwable rootCause = NestedExceptionUtils.getRootCause(e);
        Throwable actualError = (rootCause != null) ? rootCause : e;

        // ConnectException extends IOException → chỉ cần check IOException
        return actualError instanceof TimeoutException
                || actualError instanceof IOException;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // handleFinalTerminalError — Normalize lỗi cuối cùng cho client
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Xử lý lỗi cuối cùng sau khi retry exhausted.
     * Phân loại exception để trả message phù hợp cho client.
     * <p>
     * KHÔNG lộ e.getMessage() ra client — chỉ log server-side.
     *
     * @param e          exception cuối cùng
     * @param hasEmitted true nếu đã gửi ít nhất 1 token lên client
     */
    private Flux<String> handleFinalTerminalError(Throwable e, boolean hasEmitted) {
        // Đã emit data → stream bị cắt giữa chừng
        if (hasEmitted) {
            log.error("Stream đứt giữa chừng: {}", e.getMessage());
            return Flux.just("\n\n[Lỗi: Kết nối bị gián đoạn. Vui lòng thử lại.]");
        }

        if (e instanceof CallNotPermittedException) {
            log.warn("CB '{}' is OPEN — rejected. State: {}",
                    RESILIENCE_NAME, circuitBreaker.getState());
            return Flux.just("Hệ thống AI tạm thời không khả dụng, vui lòng thử lại sau ít phút.");
        }

        if (e instanceof RequestNotPermitted) {
            log.warn("RateLimiter '{}' rejected — API quota exhausted", RESILIENCE_NAME);
            return Flux.just("Hệ thống đang quá tải, vui lòng thử lại sau vài giây.");
        }

        if (e instanceof BulkheadFullException) {
            log.warn("Bulkhead '{}' full — max concurrent calls reached", RESILIENCE_NAME);
            return Flux.just("Hệ thống đang bận xử lý nhiều yêu cầu, vui lòng thử lại sau.");
        }

        // Log chi tiết server-side nhưng KHÔNG lộ e.getMessage() ra client
        log.error("LlmService terminal error: ", e);
        return Flux.just("Xin lỗi, hệ thống AI gặp sự cố. Vui lòng thử lại sau.");
    }

    private void processStreamResponseMetadata(ChatResponse response, AtomicInteger actualTotalTokens,
            AtomicBoolean hasEmittedData, AtomicBoolean ttfbStopped,
            Timer.Sample ttfbTimer) {
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            Integer total = response.getMetadata().getUsage().getTotalTokens();
            if (total != null && total > 0) {
                actualTotalTokens.set(total);
            }
        }

        String content = extractText(response);
        if (content != null && !content.isEmpty()) {
            if (hasEmittedData.compareAndSet(false, true) && ttfbStopped.compareAndSet(false, true)) {
                ttfbTimer.stop(meterRegistry.timer(Metrics.STREAM_TTFB));
                log.debug("First token received. Retry disabled from this point.");
            }
        }
    }

    private void handleStreamFinally(AtomicInteger actualTotalTokens, String userId,
            AtomicBoolean ttfbStopped, Timer.Sample ttfbTimer) {
        int totalTokens = actualTotalTokens.get();
        if (totalTokens > 0) {
            CompletableFuture.runAsync(() -> {
                rateLimitService.consumeTokens(userId, totalTokens);
                meterRegistry.summary(Metrics.TOKEN_USAGE).record(totalTokens);
                log.info("Post-flight quota: {} total tokens consumed for user={}", totalTokens, userId);
            }, virtualThreadExecutor);
        } else {
            log.warn("No token usage metadata received from Gemini — quota not updated");
        }

        if (ttfbStopped.compareAndSet(false, true)) {
            ttfbTimer.stop(meterRegistry.timer(Metrics.STREAM_TTFB, "status", "no_token"));
        }
    }
}
