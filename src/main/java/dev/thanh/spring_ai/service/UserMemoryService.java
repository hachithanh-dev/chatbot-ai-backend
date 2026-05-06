package dev.thanh.spring_ai.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.thanh.spring_ai.config.MemoryProperties;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Points;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

/**
 * Service quản lý long-term memory cho user trong Qdrant.
 * <p>
 * Sử dụng collection riêng biệt {@code user-memories} (384-dim, ONNX multilingual-e5-small)
 * để lưu trữ và truy xuất user memories. Hoàn toàn tách biệt khỏi
 * RAG collection {@code spring} (768-dim, Gemini embedding).
 * <p>
 * <b>Asymmetric Retrieval:</b> Dùng 2 embedding model riêng:
 * <ul>
 *   <li>{@code memoryQueryEmbeddingModel} — prefix "query: " cho search</li>
 *   <li>{@code memoryPassageEmbeddingModel} — prefix "passage: " cho store facts</li>
 * </ul>
 * <p>
 * <b>Resilience — Fail-Open:</b> Mọi Qdrant exception đều được catch.
 * Memory failure KHÔNG BAO GIỜ ảnh hưởng đến chat pipeline chính.
 */
@Service
@Slf4j(topic = "USER-MEMORY")
@ConditionalOnProperty(name = "memory.enabled", havingValue = "true", matchIfMissing = true)
public class UserMemoryService {

    private final QdrantClient qdrantClient;
    private final EmbeddingModel queryEmbeddingModel;
    private final EmbeddingModel passageEmbeddingModel;
    private final MemoryProperties props;

    public UserMemoryService(
            QdrantClient qdrantClient,
            @Qualifier("memoryQueryEmbeddingModel") EmbeddingModel queryEmbeddingModel,
            @Qualifier("memoryPassageEmbeddingModel") EmbeddingModel passageEmbeddingModel,
            MemoryProperties props) {
        this.qdrantClient = qdrantClient;
        this.queryEmbeddingModel = queryEmbeddingModel;
        this.passageEmbeddingModel = passageEmbeddingModel;
        this.props = props;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Collection Initialization
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Tạo Qdrant collection {@code user-memories} khi application ready.
     * <p>
     * Config: 384-dim, Cosine distance — matching ONNX multilingual-e5-small output.
     * Fail-open: nếu tạo collection thất bại, memory module bị disabled
     * nhưng chat pipeline vẫn chạy bình thường.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureCollection() {
        try {
            boolean exists = qdrantClient.collectionExistsAsync(props.getCollectionName()).get();
            if (exists) {
                log.info("✅ Memory collection '{}' already exists", props.getCollectionName());
                return;
            }

            qdrantClient.createCollectionAsync(
                    props.getCollectionName(),
                    Collections.VectorParams.newBuilder()
                            .setSize(props.getEmbeddingDimension())
                            .setDistance(Collections.Distance.Cosine)
                            .build()
            ).get();

            log.info("✅ Memory collection '{}' created. DIM={}, Distance=Cosine",
                    props.getCollectionName(), props.getEmbeddingDimension());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("⚠️ Memory collection check interrupted for '{}'", props.getCollectionName());
        } catch (Exception e) {
            log.warn("⚠️ Failed to create memory collection '{}': {}. Memory module will be degraded.",
                    props.getCollectionName(), e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Search — Recall memories cho user cụ thể
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Semantic search memories cho userId cụ thể.
     * <p>
     * Flow: embed query (ONNX, ~1ms) → Qdrant search với filter userId → top-K.
     *
     * @param userId ID của user (từ JWT token)
     * @param query  câu hỏi hoặc context cần tìm memory liên quan
     * @param topK   số lượng memories trả về
     * @return formatted string chứa relevant memories, hoặc "No memories found"
     */
    public String searchMemories(String userId, String query, int topK) {
        try {
            float[] vector = embedQuery(query);

            // E5 asymmetric model: query → passage matching cho score cao hơn MiniLM.
            // userId filter + topK đã đủ scope. LLM tự đánh giá relevance cuối cùng.
            List<Points.ScoredPoint> results = qdrantClient.searchAsync(
                    Points.SearchPoints.newBuilder()
                            .setCollectionName(props.getCollectionName())
                            .addAllVector(toFloatList(vector))
                            .setLimit(topK)
                            .setFilter(Points.Filter.newBuilder()
                                    .addMust(matchKeyword("userId", userId))
                                    .build())
                            .setWithPayload(Points.WithPayloadSelector.newBuilder()
                                    .setEnable(true)
                                    .build())
                            .build()
            ).get();

            if (results.isEmpty()) {
                log.info("[RECALL] No memories found for user={}, query=[{}]", userId, truncate(query, 60));
                return "No memories found about this user.";
            }

            String memoriesText = results.stream()
                    .map(point -> {
                        String content = point.getPayloadMap().get("content").getStringValue();
                        log.debug("[RECALL] score={}, content=[{}]", point.getScore(), truncate(content, 80));
                        return "- " + content;
                    })
                    .collect(Collectors.joining("\n"));

            log.info("🧠 [RECALL] Found {} memories for user={}, query=[{}]",
                    results.size(), userId, truncate(query, 60));

            return "Known facts about this user from previous conversations:\n" + memoriesText;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("⚠️ [RECALL ERROR] Memory search interrupted for user={}", userId);
            return "No memories found about this user.";
        } catch (Exception e) {
            log.warn("⚠️ [RECALL ERROR] Memory search failed for user={}: {}", userId, e.getMessage());
            return "No memories found about this user.";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Store — Lưu fact vào Qdrant
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Embed fact và lưu vào Qdrant với chiến lược <b>Semantic Deduplication</b>.
     * <p>
     * Flow: embed fact → search existing memories (cùng userId, limit=1)
     * → 3 nhánh xử lý dựa trên cosine similarity score:
     * <ul>
     *   <li>{@code score >= duplicateThreshold (0.98)} → Skip, chỉ touch timestamp</li>
     *   <li>{@code score >= updateThreshold (0.92)} → Update content cùng point ID</li>
     *   <li>{@code score < updateThreshold} → Insert point mới</li>
     * </ul>
     * <p>
     * Fire-and-forget — caller ({@link MemoryExtractionService}) không cần chờ kết quả.
     * Fail-open: store failure chỉ log warn, không throw exception.
     *
     * @param userId user identifier
     * @param fact   fact đã được trích xuất bởi cheap LLM
     */
    public void storeMemory(String userId, String fact) {
        if (fact == null || fact.length() < props.getMinContentLength()) {
            log.debug("[STORE] Skipped — content too short: [{}]", fact);
            return;
        }

        try {
            float[] vector = embedPassage(fact);

            // ── Search-Before-Write: tìm memory tương tự nhất của cùng userId ──
            List<Points.ScoredPoint> similar = qdrantClient.searchAsync(
                    Points.SearchPoints.newBuilder()
                            .setCollectionName(props.getCollectionName())
                            .addAllVector(toFloatList(vector))
                            .setLimit(1)
                            .setScoreThreshold((float) props.getUpdateThreshold())
                            .setFilter(Points.Filter.newBuilder()
                                    .addMust(matchKeyword("userId", userId))
                                    .build())
                            .setWithPayload(Points.WithPayloadSelector.newBuilder()
                                    .setEnable(true)     // Cần payload để so sánh content length
                                    .build())
                            .build()
            ).get();

            if (!similar.isEmpty()) {
                float score = similar.getFirst().getScore();
                String existingId = similar.getFirst().getId().getUuid();
                String existingContent = similar.getFirst().getPayloadMap()
                        .get("content").getStringValue();

                if (score >= props.getDuplicateThreshold()) {
                    // Gần giống hệt → chỉ touch timestamp, không lưu thêm
                    log.info("🔁 [STORE] Skip duplicate | userId={}, score={}, fact=[{}]",
                            userId, score, truncate(fact, 60));
                    touchTimestamp(existingId);
                    return;
                }

                // Tương tự — chỉ update nếu fact mới DÀI HƠN (chứa nhiều info hơn).
                // Nếu fact mới ngắn hơn hoặc bằng → coi như duplicate, tránh mất info.
                if (fact.length() > existingContent.length()) {
                    log.info("♻️ [STORE] Update (richer) | userId={}, score={}, old=[{}], new=[{}]",
                            userId, score, truncate(existingContent, 40), truncate(fact, 40));
                    upsertPoint(existingId, userId, fact, vector);
                } else {
                    log.info("🔁 [STORE] Skip (existing is richer) | userId={}, score={}, fact=[{}]",
                            userId, score, truncate(fact, 60));
                    touchTimestamp(existingId);
                }
                return;
            }

            // ── Không có gì tương tự → insert point mới ──
            String newId = UUID.randomUUID().toString();
            upsertPoint(newId, userId, fact, vector);
            log.info("📦 [STORE] New memory | userId={}, fact=[{}]", userId, truncate(fact, 60));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("⚠️ [STORE ERROR] Memory store interrupted for user={}", userId);
        } catch (Exception e) {
            log.warn("⚠️ [STORE ERROR] Failed to store memory for user={}: {}", userId, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Internal Helpers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Cập nhật chỉ field {@code timestamp} của point hiện có.
     * <p>
     * Dùng {@code setPayloadAsync} — chỉ gửi 1 field payload qua gRPC,
     * không gửi lại vector 384-dim → tiết kiệm network.
     */
    private void touchTimestamp(String pointId) {
        try {
            qdrantClient.setPayloadAsync(
                    props.getCollectionName(),
                    Map.of("timestamp", value(Instant.now().toString())),
                    List.of(id(UUID.fromString(pointId))),
                    null,  // wait
                    null,  // ordering
                    null   // timeout
            ).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("⚠️ [TOUCH ERROR] Touch interrupted for point={}", pointId);
        } catch (Exception e) {
            log.warn("⚠️ [TOUCH ERROR] Failed to touch timestamp for point={}: {}", pointId, e.getMessage());
        }
    }

    /**
     * Upsert một point vào Qdrant với pointId cho trước.
     * <p>
     * Nếu pointId đã tồn tại → ghi đè (update content + vector + timestamp).
     * Nếu pointId chưa tồn tại → insert mới.
     */
    private void upsertPoint(String pointId, String userId, String fact, float[] vector) throws Exception {
        Points.PointStruct point = Points.PointStruct.newBuilder()
                .setId(id(UUID.fromString(pointId)))
                .setVectors(vectors(toFloatList(vector)))
                .putAllPayload(Map.of(
                        "userId", value(userId),
                        "content", value(fact),
                        "timestamp", value(Instant.now().toString())
                ))
                .build();

        qdrantClient.upsertAsync(props.getCollectionName(), List.of(point)).get();
    }

    /**
     * Embed query text thành float[] vector 384-dim.
     * Prefix "query: " được tự động chèn bởi wrapper bean.
     */
    private float[] embedQuery(String text) {
        Embedding embedding = queryEmbeddingModel.embed(text).content();
        if (embedding == null) {
            throw new IllegalStateException("Query embedding model returned null for text: " + truncate(text, 40));
        }
        return embedding.vector();
    }

    /**
     * Embed passage/fact text thành float[] vector 384-dim.
     * Prefix "passage: " được tự động chèn bởi wrapper bean.
     */
    private float[] embedPassage(String text) {
        Embedding embedding = passageEmbeddingModel.embed(text).content();
        if (embedding == null) {
            throw new IllegalStateException("Passage embedding model returned null for text: " + truncate(text, 40));
        }
        return embedding.vector();
    }

    /** Convert float[] → List<Float> cho Qdrant gRPC API. */
    private List<Float> toFloatList(float[] vector) {
        List<Float> list = new java.util.ArrayList<>(vector.length);
        for (float f : vector) {
            list.add(f);
        }
        return list;
    }

    /** Truncate text cho logging. */
    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
