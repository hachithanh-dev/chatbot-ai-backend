package dev.thanh.spring_ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties cho Semantic Cache — Redis HNSW + Local Embedding.
 *
 * <p>
 * Semantic cache so sánh query mới với các query đã cache bằng cosine similarity
 * trên embedding vector 384-dim (multilingual-e5-small, chạy local ONNX).
 *
 * <p>
 * Khi cache hit (similarity ≥ threshold), kết quả RAG được trả ngay từ Redis
 * mà KHÔNG cần gọi Qdrant + Cohere Rerank → ~10ms thay vì ~2-5s.
 *
 * <p>
 * <b>HNSW tuning guide:</b>
 * <ul>
 *   <li>M cao hơn → recall tốt hơn + RAM nhiều hơn</li>
 *   <li>EF_CONSTRUCTION cao → index quality tốt, build chậm hơn</li>
 *   <li>EF_RUNTIME cao → search chính xác hơn, chậm hơn</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "semantic-cache")
@Getter
@Setter
public class SemanticCacheProperties {

    /** Bật/tắt semantic cache. false = mọi query đi thẳng Qdrant. */
    private boolean enabled = true;

    /** Redis index name cho FT.CREATE. */
    private String indexName = "idx:semantic_cache";

    /** Redis key prefix cho cache entries. */
    private String keyPrefix = "scache:";

    /** Embedding dimension (multilingual-e5-small = 384). */
    private int embeddingDimension = 384;

    // ─── HNSW Tuning ────────────────────────────────────────────────────

    /** HNSW M parameter — max connections per node. */
    private int hnswM = 16;

    /** HNSW EF_CONSTRUCTION — candidate set size during index build. */
    private int hnswEfConstruction = 200;

    /** HNSW EF_RUNTIME — candidate set size during search. */
    private int hnswEfRuntime = 10;

    // ─── Cache Behavior ─────────────────────────────────────────────────

    /**
     * Cosine similarity threshold cho cache hit (0.0 - 1.0).
     * Càng cao → càng khắt khe, ít hit hơn nhưng chính xác hơn.
     */
    private double similarityThreshold = 0.88;

    /** TTL cho mỗi cache entry (seconds). Default: 24h. */
    private long ttlSeconds = 86400;

    // ─── Jedis Pool ─────────────────────────────────────────────────────

    /** Max connections trong JedisPool. FT.SEARCH nhẹ nên 16 là đủ. */
    private int poolMaxTotal = 16;

    /** Max idle connections. */
    private int poolMaxIdle = 8;

    /** Min idle connections — giữ warm khi traffic thấp. */
    private int poolMinIdle = 2;

    /** Timeout (ms) khi getResource từ pool. Quá → skip cache. */
    private long poolBorrowTimeoutMs = 50;
}
