package dev.thanh.spring_ai.tools;

import dev.thanh.spring_ai.service.RagServicePort;
import dev.thanh.spring_ai.service.SemanticCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Agentic RAG Tool — LLM tự quyết định khi nào cần tra cứu knowledge base.
 * <p>
 * Thay vì mọi câu hỏi đều đi qua Qdrant + Reranking (naive RAG),
 * tool này được đăng ký vào ChatClient để Gemini chỉ gọi khi câu hỏi
 * liên quan đến Java / Spring Boot.
 * <p>
 * Delegate 100% logic search cho {@link RagServicePort} (Qdrant + Cohere
 * Rerank).
 * <p>
 * <b>Semantic Cache:</b> Trước khi gọi RAG pipeline, kiểm tra cache ngữ nghĩa
 * trên Redis HNSW. Nếu cache hit → trả kết quả ngay (~10ms thay vì ~2-5s).
 * Cache sử dụng {@link SemanticCacheService} (Optional — disabled khi config off).
 * <p>
 * <b>Resilience:</b> Timeout 10s + graceful fallback nếu Qdrant down/chậm
 * → LLM vẫn trả lời bằng general knowledge, stream không bị treo.
 * Cache failure cũng fail-open — không ảnh hưởng RAG pipeline.
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "RAG-TOOL")
public class JavaKnowledgeTools {

    private static final int TOOL_TIMEOUT_SECONDS = 10;
    private static final String FALLBACK_MSG = "No internal knowledge retrieved. Answer using general knowledge.";

    private final RagServicePort ragService;
    private final Executor virtualThreadExecutor;

    /**
     * Optional — absent khi {@code semantic-cache.enabled=false}.
     * Spring inject {@code Optional.empty()} nếu bean không tồn tại
     * → logic cache bị skip hoàn toàn → zero impact khi disabled.
     */
    private final Optional<SemanticCacheService> semanticCache;

    @Tool(name = "search_java_spring_boot_docs", description = """
            Search internal knowledge base for Java and Spring-related technical information.

            Use this tool when the question is about Java, Spring Boot, or backend systems built with them.

            Do NOT use for general knowledge, non-Java languages, or casual conversation.
            """)
    public String searchJavaSpringBootDocs(String query) {
        log.info("🤖 [TOOL CALLED] AI Agent nhận diện chủ đề Java. Đang quét với từ khóa: [{}]", query);

        // ── 1. Semantic Cache Lookup ──────────────────────────────────────
        if (semanticCache.isPresent()) {
            try {
                Optional<String> cached = semanticCache.get().lookup(query);
                if (cached.isPresent()) {
                    log.info("⚡ [CACHE HIT] Semantic cache trả kết quả ngay. Skip Qdrant + Cohere.");
                    return cached.get();
                }
                log.debug("[CACHE MISS] Chuyển sang full RAG pipeline.");
            } catch (Exception e) {
                // Fail-open: Jedis pool exhausted, connection error, etc.
                // Không ném lỗi — rớt xuống chạy RAG bình thường
                log.warn("⚠️ [CACHE ERROR] Semantic Cache lỗi, BỎ QUA cache → RAG: {}", e.getMessage());
            }
        }

        // ── 2. Full RAG Pipeline (Qdrant + Rerank) ───────────────────────
        try {
            // Timeout 10s — tránh Qdrant chậm/treo làm block toàn bộ stream
            String result = CompletableFuture
                    .supplyAsync(() -> ragService.searchSimilarity(query), virtualThreadExecutor)
                    .get(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // ── 3. Store vào cache (fire-and-forget) ─────────────────────
            if (!FALLBACK_MSG.equals(result)) {
                semanticCache.ifPresent(cache ->
                        CompletableFuture.runAsync(
                                () -> cache.store(query, result), virtualThreadExecutor));
            }

            return result;

        } catch (TimeoutException e) {
            log.warn("⏰ [TOOL TIMEOUT] Qdrant không phản hồi trong {}s. Fallback sang general knowledge.",
                    TOOL_TIMEOUT_SECONDS);
            return FALLBACK_MSG;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("🔴 [TOOL INTERRUPTED] RAG search bị interrupt");
            return FALLBACK_MSG;

        } catch (Exception e) {
            log.warn("🔴 [TOOL ERROR] RAG search thất bại: {}", e.getMessage());
            return FALLBACK_MSG;
        }
    }
}
