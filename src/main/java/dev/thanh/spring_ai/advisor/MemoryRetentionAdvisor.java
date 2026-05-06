package dev.thanh.spring_ai.advisor;

import dev.thanh.spring_ai.service.MemoryExtractionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

/**
 * StreamAdvisor trigger memory extraction khi stream kết thúc.
 * <p>
 * Dùng {@code doFinally} thay {@code doOnComplete} vì trong SSE streaming,
 * downstream subscriber có thể gửi CANCEL thay vì COMPLETE khi đọc xong
 * toàn bộ data. {@code doFinally} bắt được CẢ HAI signal.
 * <p>
 * <b>Order:</b> {@code LOWEST_PRECEDENCE - 1} — chạy ngay trước
 * {@code ChatModelStreamAdvisor} (terminal advisor ở {@code LOWEST_PRECEDENCE}).
 * KHÔNG được dùng {@code LOWEST_PRECEDENCE} vì sẽ conflict với terminal advisor.
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "MEMORY-ADVISOR")
@ConditionalOnProperty(name = "memory.enabled", havingValue = "true", matchIfMissing = true)
public class MemoryRetentionAdvisor implements StreamAdvisor {

    private final MemoryExtractionService extractionService;

    @Override
    public String getName() {
        return "MemoryRetentionAdvisor";
    }

    /**
     * Order = LOWEST_PRECEDENCE - 1 — chạy ngay TRƯỚC ChatModelStreamAdvisor
     * (terminal advisor ở LOWEST_PRECEDENCE).
     * <p>
     * QUAN TRỌNG: ChatModelStreamAdvisor (Spring AI internal) có order = LOWEST_PRECEDENCE
     * và KHÔNG gọi chain.nextStream() (nó là terminal advisor gọi ChatModel trực tiếp).
     * Nếu MemoryRetentionAdvisor cũng dùng LOWEST_PRECEDENCE, OrderComparator.sort() sẽ
     * để thứ tự KHÔNG XÁC ĐỊNH giữa hai advisor → ChatModelStreamAdvisor có thể bị pop
     * trước → MemoryRetentionAdvisor KHÔNG BAO GIỜ được gọi.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        String userId = Optional.ofNullable(request.context())
                .map(ctx -> ctx.get("userId"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .orElse(null);

        String userMsg = request.prompt().getInstructions().stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .reduce((first, second) -> second)   // last UserMessage
                .map(UserMessage::getText)
                .orElse(null);

        log.info("adviseStream called. userId={}, msgLen={}", userId, userMsg != null ? userMsg.length() : 0);

        return chain.nextStream(request)
                .doFinally(signal -> {
                    if (signal == SignalType.ON_ERROR) {
                        log.warn("Skipped — stream error");
                        return;
                    }
                    if (userId != null && userMsg != null && userMsg.length() > 5) {
                        log.info("Triggering extraction for user={}", userId);
                        extractionService.processAndSaveMemoryAsync(userId, userMsg);
                    } else {
                        log.debug("Skipped — userId={}, msgLen={}", userId,
                                userMsg != null ? userMsg.length() : 0);
                    }
                });
    }
}
