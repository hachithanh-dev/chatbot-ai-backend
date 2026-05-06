package dev.thanh.spring_ai.service;

import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;
import dev.thanh.spring_ai.components.UuidV7Generator;
import dev.thanh.spring_ai.config.HybridRagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;

import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Service
@Slf4j(topic = "RAG-SERVICE")
@ConditionalOnProperty(name = "rag.mock.enabled", havingValue = "false", matchIfMissing = true)
public class RagService implements RagServicePort {

    private static final String NO_CONTEXT_MSG = "No specific context available from the internal knowledge base.";

    // ─── Retry ─────────────────────────────────────────────────────────────
    private static final int MAX_RETRIES = 2;
    private static final long INITIAL_BACKOFF_MS = 1_000;

    // ─── Indexing Beans ────────────────────────────────────────────────────
    private final VectorStore documentVectorStore; // @Primary — RETRIEVAL_DOCUMENT
    private final DocumentSplitter documentSplitter;
    private final HybridRagProperties ragProperties;
    private final UuidV7Generator uuidGenerator;
    private final DocumentParserService documentParserService;

    // ─── Semantic Cache (Event-Driven Invalidation) ──────────────────────
    private final Optional<SemanticCacheService> semanticCacheService;
    private final Executor virtualThreadExecutor;

    // ─── Retrieval Beans ──────────────────────────────────────────────────
    private final VectorStore queryVectorStore; // RETRIEVAL_QUERY
    private final RerankService rerankService;

    public RagService(
            VectorStore documentVectorStore,
            DocumentSplitter documentSplitter,
            HybridRagProperties ragProperties,
            UuidV7Generator uuidGenerator,
            DocumentParserService documentParserService,
            Optional<SemanticCacheService> semanticCacheService,
            Executor virtualThreadExecutor,
            @Qualifier("queryVectorStore") VectorStore queryVectorStore,
            RerankService rerankService) {
        this.documentVectorStore = documentVectorStore;
        this.documentSplitter = documentSplitter;
        this.ragProperties = ragProperties;
        this.uuidGenerator = uuidGenerator;
        this.documentParserService = documentParserService;
        this.semanticCacheService = semanticCacheService;
        this.virtualThreadExecutor = virtualThreadExecutor;
        this.queryVectorStore = queryVectorStore;
        this.rerankService = rerankService;
    }

    public void storeDataFile(MultipartFile file) {

        String filename = Optional.ofNullable(file.getOriginalFilename())
                .orElse("unknown_" + Instant.now().toEpochMilli());

        // Generate unique file_id for this upload session
        String fileId = uuidGenerator.generate().toString();

        log.info("[I-1] Parsing file: '{}' (file_id={})", filename, fileId);
        List<Document> pages = documentParserService.parse(file, filename);
        log.info("[I-1] Parsed {} document segment(s) from '{}'", pages.size(), filename);

        // I-2 + I-3 + I-4: Xử lý theo page window để tránh OOM
        String indexedAt = Instant.now().toString();
        int globalChunkIndex = 0;
        int totalChunksIndexed = 0;
        int pageWindow = ragProperties.getPageWindow();
        int batchSize = ragProperties.getUpsertBatchSize();

        for (int windowStart = 0; windowStart < pages.size(); windowStart += pageWindow) {
            int windowEnd = Math.min(windowStart + pageWindow, pages.size());
            List<Document> window = pages.subList(windowStart, windowEnd);

            // I-2: Merge window pages → split 1 lần trên window text
            String windowText = window.stream()
                    .map(Document::getText)
                    .filter(t -> t != null && !t.isBlank())
                    .collect(Collectors.joining("\n\n"));

            if (windowText.isBlank()) {
                log.warn("[I-2] Pages {}-{} of '{}' have no text — skipping", windowStart + 1, windowEnd, filename);
                continue;
            }

            var lc4jDoc = dev.langchain4j.data.document.Document.from(windowText);
            List<TextSegment> segments = documentSplitter.split(lc4jDoc);

            // I-3: Metadata Enrichment
            List<Document> windowChunks = new ArrayList<>(segments.size());
            for (TextSegment segment : segments) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("source", filename);
                metadata.put("file_id", fileId);
                metadata.put("chunk_length", segment.text().length());
                metadata.put("chunk_index", globalChunkIndex++);
                metadata.put("indexed_at", indexedAt);
                metadata.put("page_range", (windowStart + 1) + "-" + windowEnd);
                windowChunks.add(new Document(segment.text(), metadata));
            }

            log.info("[I-2/I-3] Window pages {}-{}: {} chunks from '{}'",
                    windowStart + 1, windowEnd, windowChunks.size(), filename);

            // I-4: Batch embed + upsert vào documentVectorStore (RETRIEVAL_DOCUMENT)
            totalChunksIndexed += batchUpsert(windowChunks, batchSize, filename);
        }

        log.info("[DONE] Successfully indexed {} total chunks from '{}' ({} pages)",
                totalChunksIndexed, filename, pages.size());

        // ── Event-Driven Cache Invalidation ──
        // Xóa toàn bộ semantic cache sau khi upload thành công
        // để đảm bảo cache không trả về context cũ (stale data).
        // Fire-and-forget async — không block response upload.
        semanticCacheService.ifPresent(cache ->
                CompletableFuture.runAsync(cache::evictAllCache, virtualThreadExecutor));
    }

    public String searchSimilarity(String query) {
        // ── Stage 1: Vector search với queryVectorStore (RETRIEVAL_QUERY) ───────────
        List<Document> candidates;
        try {
            candidates = queryVectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(ragProperties.getCandidateTopK())
                            .similarityThreshold(ragProperties.getCandidateSimilarityThreshold())
                            .build());
        } catch (Exception e) {
            log.warn("[R-1] Vector store search failed (Qdrant may be offline): {}", e.getMessage());
            return NO_CONTEXT_MSG;
        }

        if (candidates == null || candidates.isEmpty()) {
            log.debug("[R-1] No candidates found for query");
            return NO_CONTEXT_MSG;
        }

        log.info("[R-1] Found {} candidates from Qdrant", candidates.size());

        // ── Stage 2: Cross-encoder rerank → top-{rerankTopK} ───────────────────
        List<Document> reranked = rerankService.rerank(query, candidates);

        // ── Stage 3: Join context − trả về cho LLM ───────────────────────
        String context = reranked.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));

        log.info("[R-3] Context built from {} documents ({} chars)",
                reranked.size(), context.length());

        return context;
    }

    /**
     * Chia chunks thành batch, mỗi batch upsert riêng.
     * Retry tối đa {@link #MAX_RETRIES} lần với exponential backoff (nhân đôi thời
     * gian).
     *
     * @return số chunks đã upsert thành công
     */
    private int batchUpsert(List<Document> chunks, int batchSize, String filename) {
        int totalBatches = (chunks.size() + batchSize - 1) / batchSize;
        int indexed = 0;

        for (int i = 0; i < chunks.size(); i += batchSize) {
            List<Document> batch = chunks.subList(i, Math.min(i + batchSize, chunks.size()));
            int batchNum = i / batchSize + 1;

            boolean success = false;
            long backoffMs = INITIAL_BACKOFF_MS;

            for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
                try {
                    if (attempt > 0) {
                        log.info("[I-4] Retry {}/{} for batch {}/{} of '{}' after {}ms",
                                attempt, MAX_RETRIES, batchNum, totalBatches, filename, backoffMs);
                        Thread.sleep(backoffMs);
                        backoffMs *= 2;
                    }
                    documentVectorStore.add(batch);
                    success = true;
                    break;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Indexing interrupted for: " + filename, ie);
                } catch (Exception e) {
                    log.warn("[I-4] Batch {}/{} failed (attempt {}): {}",
                            batchNum, totalBatches, attempt + 1, e.getMessage());
                }
            }

            if (!success) {
                log.error("[I-4] Batch {}/{} of '{}' failed after {} retries — aborting",
                        batchNum, totalBatches, filename, MAX_RETRIES);
                throw new RuntimeException(
                        String.format("Embedding failed for '%s' at batch %d/%d after %d retries",
                                filename, batchNum, totalBatches, MAX_RETRIES));
            }

            indexed += batch.size();
            log.info("[I-4] Indexed batch {}/{} ({} chunks) from '{}'",
                    batchNum, totalBatches, batch.size(), filename);
        }
        return indexed;
    }
}
