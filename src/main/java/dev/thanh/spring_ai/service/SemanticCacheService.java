package dev.thanh.spring_ai.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.thanh.spring_ai.config.SemanticCacheProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.search.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * Semantic Cache Service — Redis HNSW + Local Embedding.
 *
 * <p>
 * Cung cấp lớp cache ngữ nghĩa trước pipeline RAG (Qdrant + Cohere Rerank).
 * Khi query mới đủ tương tự (cosine similarity ≥ threshold) với query đã cache,
 * trả kết quả từ Redis ngay — skip hoàn toàn Qdrant + Cohere.
 *
 * <p>
 * <b>Resilience — Fail-Open:</b> Mọi Redis/Jedis exception đều được catch.
 * Cache failure KHÔNG BAO GIỜ ảnh hưởng đến pipeline RAG chính.
 * <ul>
 *   <li>lookup() → return Optional.empty() khi lỗi</li>
 *   <li>store() → skip silently khi lỗi</li>
 *   <li>evictAllCache() → log warn khi lỗi</li>
 * </ul>
 *
 * <p>
 * <b>Architecture:</b>
 * <ul>
 *   <li>Dùng {@link JedisPooled} riêng (KHÔNG ảnh hưởng Lettuce/Spring Data Redis)</li>
 *   <li>Embedding bằng multilingual-e5-small (384-dim, ~2-3ms, local ONNX)</li>
 *   <li>Redis 8.x có RediSearch built-in — FT.CREATE / FT.SEARCH</li>
 * </ul>
 */
@Service
@Slf4j(topic = "SEMANTIC-CACHE")
@ConditionalOnProperty(name = "semantic-cache.enabled", havingValue = "true", matchIfMissing = true)
public class SemanticCacheService {

    // ─── Metric Name Constants ──────────────────────────────────────────
    private static final String METRIC_LOOKUP = "semantic_cache.lookup";
    private static final String METRIC_STORE = "semantic_cache.store";
    private static final String METRIC_EMBED_LATENCY = "semantic_cache.embed_latency";
    private static final String METRIC_LOOKUP_LATENCY = "semantic_cache.lookup_latency";

    private static final String FIELD_CONTEXT = "context";
    private static final String TAG_RESULT = "result";

    private final JedisPooled jedis;
    private final EmbeddingModel cacheEmbeddingModel;
    private final SemanticCacheProperties props;
    private final MeterRegistry meterRegistry;

    public SemanticCacheService(
            JedisPooled jedis,
            @Qualifier("cacheEmbeddingModel") EmbeddingModel cacheEmbeddingModel,
            SemanticCacheProperties props,
            MeterRegistry meterRegistry) {
        this.jedis = jedis;
        this.cacheEmbeddingModel = cacheEmbeddingModel;
        this.props = props;
        this.meterRegistry = meterRegistry;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Index Initialization — ApplicationReadyEvent
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Tạo RediSearch index khi application đã ready.
     *
     * <p>
     * Dùng ApplicationReadyEvent thay vì @PostConstruct để đảm bảo:
     * <ul>
     *   <li>Redis connection đã sẵn sàng</li>
     *   <li>Không block application startup nếu Redis chưa ready</li>
     * </ul>
     *
     * <p>
     * Index schema:
     * <pre>
     * FT.CREATE idx:semantic_cache ON HASH PREFIX 1 scache: SCHEMA
     *   query_text TEXT NOINDEX
     *   context TEXT NOINDEX
     *   embedding VECTOR HNSW 6 TYPE FLOAT32 DIM 384 DISTANCE_METRIC COSINE
     * </pre>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndex() {
        try {
            // Check xem index đã tồn tại chưa
            try {
                jedis.ftInfo(props.getIndexName());
                log.info("✅ Semantic cache index '{}' already exists", props.getIndexName());
                return;
            } catch (JedisDataException e) {
                // Index chưa tồn tại — tạo mới
                log.info("Creating semantic cache index '{}'...", props.getIndexName());
            }

            // ── Build schema ──
            Schema schema = new Schema()
                    .addTextField("query_text", 1.0)
                    .addTextField(FIELD_CONTEXT, 1.0)
                    .addVectorField("embedding",
                            Schema.VectorField.VectorAlgo.HNSW,
                            Map.of(
                                    "TYPE", "FLOAT32",
                                    "DIM", String.valueOf(props.getEmbeddingDimension()),
                                    "DISTANCE_METRIC", "COSINE",
                                    "M", String.valueOf(props.getHnswM()),
                                    "EF_CONSTRUCTION", String.valueOf(props.getHnswEfConstruction())
                            ));

            // ── Create index ──
            IndexDefinition indexDef = new IndexDefinition(IndexDefinition.Type.HASH)
                    .setPrefixes(props.getKeyPrefix());

            jedis.ftCreate(props.getIndexName(),
                    IndexOptions.defaultOptions().setDefinition(indexDef), schema);

            log.info("✅ Semantic cache index '{}' created. DIM={}, M={}, EF_CONSTRUCTION={}, threshold={}",
                    props.getIndexName(), props.getEmbeddingDimension(),
                    props.getHnswM(), props.getHnswEfConstruction(),
                    props.getSimilarityThreshold());

        } catch (Exception e) {
            // Fail-open: index creation failure → cache sẽ không hoạt động
            // nhưng RAG pipeline vẫn chạy bình thường
            log.warn("⚠️ Failed to create semantic cache index: {}. Cache will be disabled until next restart.",
                    e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Lookup — Tìm cache entry gần nhất
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Tìm cache entry có embedding gần nhất với query.
     *
     * <p>
     * Flow: embed query (local, ~1ms) → FT.SEARCH KNN 1 → check cosine score.
     * <br>
     * Redis HNSW trả về cosine distance (0 = giống nhau, 2 = ngược nhau).
     * <br>
     * Similarity = 1 - distance. Cache hit khi similarity ≥ threshold.
     *
     * @param query câu hỏi của user
     * @return cached RAG context, hoặc empty nếu cache miss / error
     */
    public Optional<String> lookup(String query) {
        Timer.Sample lookupTimer = Timer.start(meterRegistry);

        try {
            // ── 1. Embed query ──
            float[] queryVector = embed(query);
            byte[] vectorBytes = toBytes(queryVector);

            // ── 2. FT.SEARCH KNN 1 ──
            Query ftQuery = new Query("*=>[KNN 1 @embedding $vec AS score]")
                    .addParam("vec", vectorBytes)
                    .returnFields(FIELD_CONTEXT, "score")
                    .dialect(2);

            SearchResult result = jedis.ftSearch(props.getIndexName(), ftQuery);

            // ── 3. Check threshold ──
            if (result.getTotalResults() == 0) {
                meterRegistry.counter(METRIC_LOOKUP, TAG_RESULT, "miss").increment();
                log.debug("[LOOKUP] No results from FT.SEARCH");
                return Optional.empty();
            }

            Document doc = result.getDocuments().get(0);
            double distance = Double.parseDouble(doc.getString("score"));
            double similarity = 1.0 - distance;

            if (similarity >= props.getSimilarityThreshold()) {
                String context = doc.getString(FIELD_CONTEXT);
                meterRegistry.counter(METRIC_LOOKUP, TAG_RESULT, "hit").increment();
                log.info("⚡ [CACHE HIT] similarity={} (threshold={}), query=[{}]",
                        String.format("%.4f", similarity), props.getSimilarityThreshold(),
                        truncate(query, 80));
                return Optional.ofNullable(context);
            }

            meterRegistry.counter(METRIC_LOOKUP, TAG_RESULT, "miss").increment();
            log.debug("[CACHE MISS] similarity={} < threshold={}, query=[{}]",
                    String.format("%.4f", similarity), props.getSimilarityThreshold(),
                    truncate(query, 80));
            return Optional.empty();

        } catch (Exception e) {
            meterRegistry.counter(METRIC_LOOKUP, TAG_RESULT, "error").increment();
            log.warn("⚠️ [LOOKUP ERROR] Cache lookup failed, fallback to RAG: {}", e.getMessage());
            return Optional.empty();
        } finally {
            lookupTimer.stop(meterRegistry.timer(METRIC_LOOKUP_LATENCY));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Store — Lưu query + context vào cache
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Lưu query + RAG context vào semantic cache.
     *
     * <p>
     * Fire-and-forget — caller không cần chờ kết quả.
     * Mỗi entry được set TTL để tự động expire.
     *
     * @param query   câu hỏi gốc
     * @param context kết quả RAG từ Qdrant + Cohere Rerank
     */
    public void store(String query, String context) {
        try {
            float[] queryVector = embed(query);
            byte[] vectorBytes = toBytes(queryVector);

            // Unique key = prefix + UUID
            String key = props.getKeyPrefix() + UUID.randomUUID();

            // HSET: lưu hash fields
            Map<byte[], byte[]> hash = new HashMap<>();
            hash.put("query_text".getBytes(), query.getBytes());
            hash.put(FIELD_CONTEXT.getBytes(), context.getBytes());
            hash.put("embedding".getBytes(), vectorBytes);

            jedis.hset(key.getBytes(), hash);
            jedis.expire(key, props.getTtlSeconds());

            meterRegistry.counter(METRIC_STORE, TAG_RESULT, "success").increment();
            log.info("📦 [CACHE STORED] key={}, query=[{}], context_length={}, ttl={}s",
                    key, truncate(query, 60), context.length(), props.getTtlSeconds());

        } catch (Exception e) {
            meterRegistry.counter(METRIC_STORE, TAG_RESULT, "error").increment();
            log.warn("⚠️ [STORE ERROR] Cache store failed (non-blocking): {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Evict — Xóa toàn bộ cache (Event-Driven Invalidation)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Xóa toàn bộ semantic cache bằng FT.DROPINDEX DD.
     *
     * <p>
     * Nuclear flush — xóa index VÀ tất cả dữ liệu bên trong.
     * Sau đó tạo lại index trống ngay lập tức.
     *
     * <p>
     * Gọi hàm này sau khi upload tài liệu mới vào Qdrant để đảm bảo
     * cache không trả về context cũ (stale data).
     *
     * <p>
     * Fail-open: Nếu lỗi, chỉ log warn — không ảnh hưởng tiến trình upload.
     */
    public void evictAllCache() {
        try {
            // FT.DROPINDEX DD — xóa index + documents
            jedis.ftDropIndex(props.getIndexName());
            log.info("🗑️ [CACHE INVALIDATION] Đã xóa sạch Semantic Cache do có tài liệu mới.");

            // Tạo lại index trống ngay lập tức
            ensureIndex();

        } catch (JedisDataException e) {
            // Index không tồn tại — bỏ qua
            if (e.getMessage() != null && e.getMessage().contains("Unknown index")) {
                log.debug("[EVICT] Index not found, nothing to evict");
                return;
            }
            log.warn("⚠️ Không thể xóa Redis Index: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("⚠️ Không thể xóa Redis Index: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Internal Helpers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Embed text thành float[] vector 384-dim.
     * Chạy local trên ONNX Runtime — ~2-3ms, zero API cost.
     * Prefix "query: " được tự động chèn bởi cacheEmbeddingModel wrapper.
     */
    private float[] embed(String text) {
        Timer.Sample timer = Timer.start(meterRegistry);
        try {
            Embedding embedding = cacheEmbeddingModel.embed(text).content();
            return embedding.vector();
        } finally {
            timer.stop(meterRegistry.timer(METRIC_EMBED_LATENCY));
        }
    }

    /**
     * Chuyển float[] → byte[] theo chuẩn FLOAT32 Little-Endian cho Redis.
     *
     * <p>
     * <b>⚠️ CRITICAL:</b> Java mặc định dùng Big-Endian cho ByteBuffer.
     * Redis HNSW yêu cầu Little-Endian. Nếu thiếu {@code .order(ByteOrder.LITTLE_ENDIAN)},
     * vector sẽ bị sai lệch hoàn toàn → FT.SEARCH trả kết quả rác.
     */
    private byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float f : vector) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    /** Truncate text cho logging — tránh log quá dài. */
    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
