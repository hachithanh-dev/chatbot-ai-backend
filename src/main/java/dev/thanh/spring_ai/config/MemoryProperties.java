package dev.thanh.spring_ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Cấu hình cho Long-Term Memory module.
 * <p>
 * Memory được lưu trong Qdrant collection riêng biệt ({@code user-memories}),
 * sử dụng local ONNX embedding multilingual-e5-small (384-dim) — tách hoàn toàn khỏi
 * RAG collection {@code spring} (768-dim, Gemini embedding).
 */
@Configuration(proxyBeanMethods = false)
@ConfigurationProperties(prefix = "memory")
@Getter
@Setter
public class MemoryProperties {

    /** Bật/tắt toàn bộ memory module. */
    private boolean enabled = true;

    /** Tên Qdrant collection lưu user memories. */
    private String collectionName = "user-memories";

    /** Dimension của embedding vector (ONNX multilingual-e5-small = 384). */
    private int embeddingDimension = 384;

    /** Số lượng memories liên quan nhất trả về khi recall. */
    private int topK = 3;

    /** Bỏ qua messages ngắn hơn giá trị này (filter noise). */
    private int minContentLength = 5;

    /**
     * Ngưỡng cosine similarity tối thiểu cho recall.
     * E5 asymmetric model + XLM-RoBERTa anisotropy cho score cao hơn MiniLM.
     * Giữ threshold 0.80 để lọc bớt noise, topK + LLM lọc relevance cuối cùng.
     */
    private double similarityThreshold = 0.80;

    /**
     * Ngưỡng cosine similarity để coi là duplicate hoàn toàn → chỉ touch timestamp.
     */
    private double duplicateThreshold = 0.98;

    /** Ngưỡng cosine similarity để coi là tương tự → update content cùng ID. */
    private double updateThreshold = 0.92;
}
