package dev.thanh.spring_ai.service;


import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for LlmService — covers both normal Gemini failure scenarios
 * and Circuit Breaker state transitions.
 *
 * Strategy for CB tests: create a test-only CircuitBreakerRegistry with low thresholds
 * (minimum-number-of-calls=1, failure-rate=100%) so we can open the CB with a single failure.
 * This avoids complex timing or real Resilience4j internals while still testing
 * the integration between LlmService and the CB operator.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LlmService — Unit Tests (Gemini Failure Scenarios + Circuit Breaker)")
class LlmServiceTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient cheapChatClient;

    @Mock
    private RateLimitService rateLimitService;

    // Default registry (used by most tests — CB always CLOSED)
    private CircuitBreakerRegistry defaultRegistry;

    // Test-only registry with aggressive thresholds to trigger OPEN easily
    private CircuitBreakerRegistry sensitiveRegistry;

    // Registries needed by LlmService constructor
    private RateLimiterRegistry rateLimiterRegistry;
    private BulkheadRegistry bulkheadRegistry;
    private MeterRegistry meterRegistry;
    private Executor virtualThreadExecutor;

    private LlmService llmService;

    private static final String USER_ID = "test-user-123";

    @BeforeEach
    void setUp() {
        defaultRegistry = CircuitBreakerRegistry.ofDefaults();
        rateLimiterRegistry = RateLimiterRegistry.ofDefaults();
        bulkheadRegistry = BulkheadRegistry.ofDefaults();
        meterRegistry = new SimpleMeterRegistry();
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

        sensitiveRegistry = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(1)
                        .minimumNumberOfCalls(1)
                        .failureRateThreshold(100) // 1 failure = OPEN
                        .waitDurationInOpenState(Duration.ofSeconds(60)) // Stay OPEN during test
                        .recordExceptions(RuntimeException.class)
                        .build()
        );
        // Use default registry by default — tests needing CB OPEN will override
        llmService = new LlmService(chatClient, cheapChatClient, defaultRegistry, rateLimiterRegistry,
                bulkheadRegistry, meterRegistry, rateLimitService, virtualThreadExecutor);
    }

    // ─────────────────────────────────────────────────────────
    // Helper: mock ChatClient fluent chain for streamResponse
    // Now uses .chatResponse() instead of .content()
    // ─────────────────────────────────────────────────────────

    /**
     * Build a ChatResponse chunk from text content (intermediate chunk — no usage).
     */
    private ChatResponse buildTextChunk(String text) {
        AssistantMessage message = new AssistantMessage(text);
        return new ChatResponse(List.of(new Generation(message)));
    }

    /**
     * Build a ChatResponse chunk with usage metadata (final chunk).
     */
    private ChatResponse buildUsageChunk(String text, int totalTokens) {
        AssistantMessage message = new AssistantMessage(text);
        Usage usage = mock(Usage.class);
        lenient().when(usage.getTotalTokens()).thenReturn(totalTokens);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(usage)
                .build();
        return new ChatResponse(List.of(new Generation(message)), metadata);
    }

    private void mockChatResponseStream(Flux<ChatResponse> flux) {
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.ChatClientRequestSpec systemSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.ChatClientRequestSpec messagesSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.ChatClientRequestSpec userSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.ChatClientRequestSpec toolContextSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.ChatClientRequestSpec advisorsSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system((org.springframework.core.io.Resource) any())).thenReturn(systemSpec);
        when(systemSpec.messages(any(List.class))).thenReturn(messagesSpec);
        when(messagesSpec.user(anyString())).thenReturn(userSpec);
        when(userSpec.toolContext(any(Map.class))).thenReturn(toolContextSpec);
        when(toolContextSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(advisorsSpec);
        when(advisorsSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(flux);
    }

    // ─────────────────────────────────────────────────────────
    // Happy Path
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("streamResponse — happy path — should emit all tokens as strings")
    void streamResponse_WhenSuccess_ShouldEmitTokens() {
        // Given: 2 text chunks + 1 final chunk with usage metadata
        mockChatResponseStream(Flux.just(
                buildTextChunk("Hello"),
                buildTextChunk(" World"),
                buildUsageChunk("!", 120)
        ));

        // When & Then
        StepVerifier.create(llmService.streamResponse("Hi", Collections.emptyList(), USER_ID))
                .expectNext("Hello")
                .expectNext(" World")
                .expectNext("!")
                .verifyComplete();
    }

    @Test
    @DisplayName("streamResponse — happy path — should consume tokens via rateLimitService after completion")
    void streamResponse_WhenSuccess_ShouldConsumeTokensPostFlight() throws InterruptedException {
        // Given: final chunk has 3,070 total tokens (simulating RAG scenario)
        mockChatResponseStream(Flux.just(
                buildTextChunk("Cần báo trước"),
                buildUsageChunk(" 3 ngày.", 3070) // 3050 prompt (incl RAG) + 20 completion
        ));

        // When
        StepVerifier.create(llmService.streamResponse("Tóm tắt quy định xin nghỉ phép", Collections.emptyList(), USER_ID))
                .expectNext("Cần báo trước")
                .expectNext(" 3 ngày.")
                .verifyComplete();

        // Then: verify consumeTokens called asynchronously
        verify(rateLimitService, timeout(2000)).consumeTokens(USER_ID, 3070);
    }

    // ─────────────────────────────────────────────────────────
    // Timeout — must trigger fallback
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("streamResponse — when timeout — should return fallback error message (not throw)")
    void streamResponse_WhenTimeout_ShouldReturnFallbackMessage() {
        // Given: simulate timeout by emitting a TimeoutException directly.
        // Note: Resilience4j operators use real Schedulers, incompatible with StepVerifier.withVirtualTime().
        // Instead, we test the error handling path that timeout would trigger.
        mockChatResponseStream(Flux.error(new java.util.concurrent.TimeoutException("Gemini idle > 60s")));

        // When & Then: onErrorResume should catch timeout and emit a single error message
        StepVerifier.create(llmService.streamResponse("query", Collections.emptyList(), USER_ID))
                .assertNext(msg -> assertThat(msg).containsAnyOf("lỗi", "Xin lỗi"))
                .verifyComplete();
    }

    // ─────────────────────────────────────────────────────────
    // Generic Error — 500 / connection failure
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("streamResponse — when Gemini returns error — should emit fallback message (not propagate)")
    void streamResponse_WhenGeminiError_ShouldReturnFallbackMessage() {
        // Given: simulate Gemini API failure
        mockChatResponseStream(Flux.error(new RuntimeException("500 Internal Server Error from Gemini")));

        // When & Then: onErrorResume returns a single fallback string, no exception propagated
        StepVerifier.create(llmService.streamResponse("question", Collections.emptyList(), USER_ID))
                .assertNext(msg -> {
                    assertThat(msg).containsAnyOf("lỗi", "Xin lỗi");
                })
                .verifyComplete();
    }

    // ─────────────────────────────────────────────────────────
    // Circuit Breaker Tests
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("streamResponse — when CB is OPEN — should fail-fast with unavailable message (no 60s wait)")
    void streamResponse_WhenCircuitBreakerOpen_ShouldFailFast() {
        // Given: use sensitive registry, force CB into OPEN state
        LlmService serviceWithSensitiveCB = new LlmService(chatClient, cheapChatClient, sensitiveRegistry, rateLimiterRegistry,
                bulkheadRegistry, meterRegistry, rateLimitService, virtualThreadExecutor);
        CircuitBreaker cb = sensitiveRegistry.circuitBreaker("llm-gemini");

        // Manually transition to OPEN
        cb.transitionToOpenState();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        mockChatResponseStream(Flux.never());

        // When & Then: CallNotPermittedException → fail-fast message (no timeout wait)
        StepVerifier.create(serviceWithSensitiveCB.streamResponse("question", Collections.emptyList(), USER_ID))
                .assertNext(msg -> assertThat(msg).contains("tạm thời không khả dụng"))
                .verifyComplete();
    }

    @Test
    @DisplayName("streamResponse — after enough failures — CB should OPEN and subsequent calls fail-fast")
    void streamResponse_AfterFailures_CircuitShouldOpen_AndSubsequentCallsFailFast() {
        // Given: sensitive registry (1 failure = OPEN)
        LlmService serviceWithSensitiveCB = new LlmService(chatClient, cheapChatClient, sensitiveRegistry, rateLimiterRegistry,
                bulkheadRegistry, meterRegistry, rateLimitService, virtualThreadExecutor);
        CircuitBreaker cb = sensitiveRegistry.circuitBreaker("llm-gemini");

        // First call: mock Gemini error — this triggers CB to OPEN (1 failure = 100% rate)
        mockChatResponseStream(Flux.error(new RuntimeException("Gemini 503 Service Unavailable")));
        StepVerifier.create(serviceWithSensitiveCB.streamResponse("first", Collections.emptyList(), USER_ID))
                .assertNext(msg -> assertThat(msg).containsAnyOf("lỗi", "Xin lỗi"))
                .verifyComplete();

        // CB should now be OPEN after 1 failure
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Second call: CB OPEN → fail-fast without calling Gemini at all
        StepVerifier.create(serviceWithSensitiveCB.streamResponse("second", Collections.emptyList(), USER_ID))
                .assertNext(msg -> assertThat(msg).contains("tạm thời không khả dụng"))
                .verifyComplete();
    }

    @Test
    @DisplayName("generateTitle — when CB is OPEN — should return Mono.empty (silent fallback)")
    void generateTitle_WhenCircuitBreakerOpen_ShouldReturnEmpty() {
        // Given: force CB into OPEN state
        LlmService serviceWithSensitiveCB = new LlmService(chatClient, cheapChatClient, sensitiveRegistry, rateLimiterRegistry,
                bulkheadRegistry, meterRegistry, rateLimitService, virtualThreadExecutor);
        CircuitBreaker cb = sensitiveRegistry.circuitBreaker("llm-gemini");
        cb.transitionToOpenState();

        // When & Then: no exception propagated, silent empty (title generation is best-effort)
        StepVerifier.create(serviceWithSensitiveCB.generateTitle("What is Spring AI?"))
                .verifyComplete(); // empty Mono — title simply not generated
    }

    // ─────────────────────────────────────────────────────────
    // generateTitle — existing tests
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("generateTitle — happy path — should return trimmed title")
    void generateTitle_WhenSuccess_ShouldReturnTrimmedTitle() {
        // Given: mock the fluent chain: prompt() → user() → call() → content()
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.ChatClientRequestSpec userSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(cheapChatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(userSpec);
        when(userSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("  My First Chat  ");

        // When & Then
        StepVerifier.create(llmService.generateTitle("What is Spring?"))
                .assertNext(title -> assertThat(title).isEqualTo("My First Chat"))
                .verifyComplete();
    }

    @Test
    @DisplayName("generateTitle — when Gemini fails — should return Mono.empty (silent fallback)")
    void generateTitle_WhenFails_ShouldReturnEmpty() {
        // Given: mock the fluent chain: prompt() → user() → call() → content()
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.ChatClientRequestSpec userSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(cheapChatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(userSpec);
        when(userSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenThrow(new RuntimeException("Gemini 429"));

        // When & Then: onErrorResume → Mono.empty()
        StepVerifier.create(llmService.generateTitle("question"))
                .verifyComplete(); // empty, no error, no items
    }

    // ═══════════════════════════════════════════════════════════════════════
    // isSafeToRetry — Exhaustive Whitelist/Blacklist Tests
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("isSafeToRetry — Exception Whitelist/Blacklist")
    class IsSafeToRetryTests {

        /**
         * Uses reflection to test private isSafeToRetry method directly.
         * This avoids complex Flux setup for each exception type.
         */
        private boolean invokeIsSafeToRetry(Throwable error, boolean hasEmitted) {
            try {
                var method = LlmService.class.getDeclaredMethod("isSafeToRetry", Throwable.class, boolean.class);
                method.setAccessible(true);
                return (boolean) method.invoke(llmService, error, hasEmitted);
            } catch (Exception e) {
                throw new RuntimeException("Failed to invoke isSafeToRetry", e);
            }
        }

        // ─── BLACKLIST: Should NOT retry ─────────────────────────
        @Test
        @DisplayName("when hasEmitted=true — should always return false (no retry after data sent)")
        void hasEmitted_ShouldNeverRetry() {
            // Even retryable exceptions should be blocked after data emission
            assertThat(invokeIsSafeToRetry(new TimeoutException("idle"), true)).isFalse();
            assertThat(invokeIsSafeToRetry(new IOException("network"), true)).isFalse();
            assertThat(invokeIsSafeToRetry(new ConnectException("refused"), true)).isFalse();
        }

        @Test
        @DisplayName("BulkheadFullException — should NOT retry (internal protection)")
        void bulkheadFull_ShouldNotRetry() {
            BulkheadFullException ex = BulkheadFullException.createBulkheadFullException(
                    io.github.resilience4j.bulkhead.Bulkhead.ofDefaults("test"));
            assertThat(invokeIsSafeToRetry(ex, false)).isFalse();
        }

        @Test
        @DisplayName("RequestNotPermitted — should NOT retry (RateLimiter rejection)")
        void requestNotPermitted_ShouldNotRetry() {
            RequestNotPermitted ex = RequestNotPermitted.createRequestNotPermitted(
                    io.github.resilience4j.ratelimiter.RateLimiter.ofDefaults("test"));
            assertThat(invokeIsSafeToRetry(ex, false)).isFalse();
        }

        @Test
        @DisplayName("CallNotPermittedException — should NOT retry (CB OPEN rejection)")
        void callNotPermitted_ShouldNotRetry() {
            CircuitBreaker cb = CircuitBreaker.ofDefaults("test");
            cb.transitionToOpenState();
            CallNotPermittedException ex = CallNotPermittedException.createCallNotPermittedException(cb);
            assertThat(invokeIsSafeToRetry(ex, false)).isFalse();
        }

        @Test
        @DisplayName("RuntimeException — should NOT retry (not in whitelist)")
        void runtimeException_ShouldNotRetry() {
            assertThat(invokeIsSafeToRetry(new RuntimeException("random error"), false)).isFalse();
        }

        @Test
        @DisplayName("NullPointerException — should NOT retry (application bug)")
        void nullPointerException_ShouldNotRetry() {
            assertThat(invokeIsSafeToRetry(new NullPointerException(), false)).isFalse();
        }

        @Test
        @DisplayName("IllegalArgumentException — should NOT retry")
        void illegalArgument_ShouldNotRetry() {
            assertThat(invokeIsSafeToRetry(new IllegalArgumentException("bad input"), false)).isFalse();
        }

        // ─── WHITELIST: Should retry ─────────────────────────────
        @Test
        @DisplayName("TimeoutException — should retry (Gemini idle timeout)")
        void timeoutException_ShouldRetry() {
            assertThat(invokeIsSafeToRetry(new TimeoutException("Gemini idle > 30s"), false)).isTrue();
        }

        @Test
        @DisplayName("IOException — should retry (network failure)")
        void ioException_ShouldRetry() {
            assertThat(invokeIsSafeToRetry(new IOException("Connection reset"), false)).isTrue();
        }

        @Test
        @DisplayName("ConnectException — should retry (extends IOException)")
        void connectException_ShouldRetry() {
            // ConnectException extends IOException → covered by IOException check
            assertThat(invokeIsSafeToRetry(new ConnectException("Connection refused"), false)).isTrue();
        }

        // ─── NESTED EXCEPTION: Root cause extraction ─────────────
        @Test
        @DisplayName("wrapped TimeoutException — should unwrap and retry")
        void wrappedTimeout_ShouldUnwrapAndRetry() {
            Exception wrapped = new RuntimeException("Spring wrapper",
                    new TimeoutException("Gemini timeout"));
            assertThat(invokeIsSafeToRetry(wrapped, false)).isTrue();
        }

        @Test
        @DisplayName("wrapped IOException — should unwrap and retry")
        void wrappedIOException_ShouldUnwrapAndRetry() {
            Exception wrapped = new RuntimeException("Spring wrapper",
                    new IOException("Stream closed"));
            assertThat(invokeIsSafeToRetry(wrapped, false)).isTrue();
        }

        @Test
        @DisplayName("deeply nested ConnectException — should find root cause and retry")
        void deeplyNested_ShouldFindRootCause() {
            Exception deep = new RuntimeException("level1",
                    new RuntimeException("level2",
                            new ConnectException("Connection refused")));
            assertThat(invokeIsSafeToRetry(deep, false)).isTrue();
        }

        @Test
        @DisplayName("wrapped non-retryable RuntimeException — should NOT retry")
        void wrappedNonRetryable_ShouldNotRetry() {
            Exception wrapped = new RuntimeException("Spring wrapper",
                    new IllegalStateException("some state error"));
            assertThat(invokeIsSafeToRetry(wrapped, false)).isFalse();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Mid-stream interruption — "đã emit data lên client"
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Mid-stream interruption")
    class MidStreamInterruptionTests {

        @Test
        @DisplayName("when stream emits tokens then errors — should emit error suffix, no retry text repeat")
        void midStreamError_ShouldEmitErrorSuffix() {
            // Given: emit 2 text chunks, then error — simulates Gemini stream cutting mid-response
            mockChatResponseStream(Flux.concat(
                    Flux.just(buildTextChunk("Hello"), buildTextChunk(" World")),
                    Flux.error(new IOException("Connection reset mid-stream"))
            ));

            // When & Then
            StepVerifier.create(llmService.streamResponse("Hi", Collections.emptyList(), USER_ID))
                    .expectNext("Hello")
                    .expectNext(" World")
                    // After emitting data, the error is caught and a suffix is appended
                    .assertNext(msg -> assertThat(msg).contains("gián đoạn"))
                    .verifyComplete();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RateLimiter rejection — different from CB
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("RateLimiter rejection")
    class RateLimiterRejectionTests {

        @Test
        @DisplayName("when RateLimiter rejects — should emit quota exhausted message (not throw)")
        void whenRateLimiterRejects_ShouldEmitFriendlyMessage() {
            // Create RL with 1 permit and 60s refresh: after 1 call the next will be rejected
            RateLimiterRegistry strictRL = RateLimiterRegistry.of(
                    RateLimiterConfig.custom()
                            .limitForPeriod(1)
                            .limitRefreshPeriod(Duration.ofSeconds(60))
                            .timeoutDuration(Duration.ZERO) // fail immediately
                            .build()
            );
            LlmService strictService = new LlmService(chatClient, cheapChatClient, defaultRegistry, strictRL,
                    bulkheadRegistry, meterRegistry, rateLimitService, virtualThreadExecutor);

            // Exhaust the single permit
            io.github.resilience4j.ratelimiter.RateLimiter rl = strictRL.rateLimiter("llm-gemini");
            rl.acquirePermission(); // consume the only permit

            mockChatResponseStream(Flux.just(buildTextChunk("should-not-reach")));

            StepVerifier.create(strictService.streamResponse("Hi", Collections.emptyList(), USER_ID))
                    .assertNext(msg -> assertThat(msg).contains("quá tải"))
                    .verifyComplete();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // handleFinalTerminalError — error classification
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("handleFinalTerminalError — error classification")
    class ErrorClassificationTests {

        @Test
        @DisplayName("when CB OPEN — should return 'tạm thời không khả dụng'")
        void cbOpen_ShouldReturnUnavailableMessage() {
            LlmService service = new LlmService(chatClient, cheapChatClient, sensitiveRegistry, rateLimiterRegistry,
                    bulkheadRegistry, meterRegistry, rateLimitService, virtualThreadExecutor);
            sensitiveRegistry.circuitBreaker("llm-gemini").transitionToOpenState();
            mockChatResponseStream(Flux.never());

            StepVerifier.create(service.streamResponse("q", Collections.emptyList(), USER_ID))
                    .assertNext(msg -> assertThat(msg).contains("tạm thời không khả dụng"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("when generic error — should return safe message (no internal details leaked)")
        void genericError_ShouldReturnSafeMessage() {
            mockChatResponseStream(Flux.error(new RuntimeException("Unexpected API error")));

            StepVerifier.create(llmService.streamResponse("q", Collections.emptyList(), USER_ID))
                    .assertNext(msg -> {
                        assertThat(msg).contains("sự cố");
                        // SECURITY: internal error message must NOT be exposed to client
                        assertThat(msg).doesNotContain("Unexpected API error");
                    })
                    .verifyComplete();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Metrics verification
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Prometheus metrics")
    class MetricsTests {

        @Test
        @DisplayName("successful stream — should increment success counter")
        void successfulStream_ShouldIncrementSuccessCounter() {
            mockChatResponseStream(Flux.just(buildTextChunk("token1"), buildTextChunk("token2")));

            StepVerifier.create(llmService.streamResponse("Hi", Collections.emptyList(), USER_ID))
                    .expectNextCount(2)
                    .verifyComplete();

            var counter = meterRegistry.find("llm.stream.status").tag("result", "success").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("TTFB timer — should record on first token")
        void ttfbTimer_ShouldRecordOnFirstToken() {
            mockChatResponseStream(Flux.just(buildTextChunk("first-token")));

            StepVerifier.create(llmService.streamResponse("Hi", Collections.emptyList(), USER_ID))
                    .expectNext("first-token")
                    .verifyComplete();

            var timer = meterRegistry.find("llm.stream.ttfb").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("token usage metric — should record totalTokens from Gemini metadata")
        void tokenUsageMetric_ShouldRecordFromMetadata() throws InterruptedException {
            mockChatResponseStream(Flux.just(
                    buildTextChunk("Hello"),
                    buildUsageChunk("!", 550)
            ));

            StepVerifier.create(llmService.streamResponse("Hi", Collections.emptyList(), USER_ID))
                    .expectNextCount(2)
                    .verifyComplete();

            // Wait for async virtual thread to complete (increase timeout to prevent flakiness)
            Thread.sleep(2000);

            var summary = meterRegistry.find("llm.token.usage").summary();
            assertThat(summary).isNotNull();
            assertThat(summary.count()).isEqualTo(1);
            assertThat(summary.totalAmount()).isEqualTo(550.0);
        }
    }
}
