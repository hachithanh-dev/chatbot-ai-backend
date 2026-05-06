package dev.thanh.spring_ai.config;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.OnnxEmbeddingModel;
import dev.langchain4j.model.embedding.onnx.PoolingMode;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Cấu hình tập trung cho local embedding model — multilingual-e5-small (ONNX).
 *
 * <p>
 * <b>Model:</b> {@code intfloat/multilingual-e5-small} — 384-dim, 100+ languages,
 * asymmetric retrieval (query:/passage:), contrastive + supervised training.
 *
 * <p>
 * <b>Hybrid Path Strategy:</b>
 * <ul>
 *   <li>IDE/Dev: file nằm trong classpath → extract sang temp dir cố định</li>
 *   <li>Docker/Prod: mount volume → đường dẫn tuyệt đối qua env var</li>
 * </ul>
 *
 * <p>
 * <b>Prefix Wrappers:</b> E5 yêu cầu prefix "query: " hoặc "passage: " cho input.
 * Thay vì bắt caller phải nhớ chèn prefix, config này tạo 3 wrapper beans:
 * <ul>
 *   <li>{@code cacheEmbeddingModel} — "query: " cho cả store + lookup (symmetric cache)</li>
 *   <li>{@code memoryQueryEmbeddingModel} — "query: " cho search memories</li>
 *   <li>{@code memoryPassageEmbeddingModel} — "passage: " cho store facts (asymmetric retrieval)</li>
 * </ul>
 *
 * @see <a href="https://huggingface.co/intfloat/multilingual-e5-small#faq">E5 Prefix FAQ</a>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "embedding.local.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class LocalEmbeddingConfig {

    @Value("${embedding.local.model-path:classpath:models/multilingual-e5-small/model_quantized.onnx}")
    private String modelPath;

    @Value("${embedding.local.tokenizer-path:classpath:models/multilingual-e5-small/tokenizer.json}")
    private String tokenizerPath;

    // ═══════════════════════════════════════════════════════════════════════
    // Raw Model — shared singleton, KHÔNG được inject trực tiếp
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Raw E5 model instance — không có prefix.
     * <p>
     * Dùng làm delegate cho các wrapper beans bên dưới.
     * <b>KHÔNG inject trực tiếp</b> — luôn dùng qualified beans.
     */
    @Bean
    public EmbeddingModel rawE5Model() throws Exception {
        String resolvedModel = resolvePath(modelPath, "model", ".onnx");
        String resolvedTokenizer = resolvePath(tokenizerPath, "tokenizer", ".json");

        EmbeddingModel model = new OnnxEmbeddingModel(resolvedModel, resolvedTokenizer, PoolingMode.MEAN);
        log.info("✅ Local embedding model loaded: multilingual-e5-small (384-dim, ONNX, PoolingMode.MEAN)");
        return model;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Prefix Wrapper Beans
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Semantic Cache — symmetric: cùng prefix "query: " cho cả store + lookup.
     * <p>
     * Theo E5 FAQ: dùng "query: " cho cả 2 bên khi so sánh similarity (symmetric).
     */
    @Bean("cacheEmbeddingModel")
    public EmbeddingModel cacheEmbeddingModel(@Qualifier("rawE5Model") EmbeddingModel delegate) {
        return wrapWithPrefix(delegate, "query: ");
    }

    /**
     * Memory search — query prefix cho retrieval query.
     */
    @Bean("memoryQueryEmbeddingModel")
    public EmbeddingModel memoryQueryEmbeddingModel(@Qualifier("rawE5Model") EmbeddingModel delegate) {
        return wrapWithPrefix(delegate, "query: ");
    }

    /**
     * Memory store — passage prefix cho document/fact storage (asymmetric retrieval).
     */
    @Bean("memoryPassageEmbeddingModel")
    public EmbeddingModel memoryPassageEmbeddingModel(@Qualifier("rawE5Model") EmbeddingModel delegate) {
        return wrapWithPrefix(delegate, "passage: ");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Internal Helpers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Resolve path: classpath → extract to fixed temp path, absolute → pass-through.
     * <p>
     * Dùng tên file cố định thay vì {@code Files.createTempFile()} để tránh
     * rò rỉ file temp khi container bị kill ngang (SIGKILL). Lần chạy sau
     * sẽ tự động overwrite file cũ.
     */
    private String resolvePath(String path, String prefix, String suffix) throws Exception {
        if (path.startsWith("classpath:")) {
            ClassPathResource resource = new ClassPathResource(path.replace("classpath:", ""));
            // Fixed path → tự overwrite mỗi lần restart, không rò rỉ /tmp
            Path tempFile = Path.of(System.getProperty("java.io.tmpdir"), "e5_model_" + prefix + suffix);
            try (InputStream is = resource.getInputStream()) {
                Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            log.debug("Extracted classpath resource [{}] → [{}]", path, tempFile);
            return tempFile.toAbsolutePath().toString();
        }
        return path; // Absolute path cho Docker volume mount
    }

    /**
     * Wrap embedding model với prefix tự động.
     * <p>
     * Override cả {@code embed(String)} và {@code embedAll(List<TextSegment>)}
     * để đảm bảo prefix được chèn bất kể caller gọi method nào.
     */
    private EmbeddingModel wrapWithPrefix(EmbeddingModel delegate, String prefix) {
        return new EmbeddingModel() {
            @Override
            public Response<Embedding> embed(String text) {
                return delegate.embed(prefix + text);
            }

            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                List<TextSegment> prefixed = segments.stream()
                        .map(seg -> TextSegment.from(prefix + seg.text(), seg.metadata()))
                        .toList();
                return delegate.embedAll(prefixed);
            }
        };
    }
}
