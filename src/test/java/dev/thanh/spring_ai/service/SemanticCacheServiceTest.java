package dev.thanh.spring_ai.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.thanh.spring_ai.config.SemanticCacheProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.SearchResult;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SemanticCacheService unit tests — focuses on:
 * 1. Fail-open resilience (Redis down → graceful bypass, never crash RAG pipeline)
 * 2. Similarity threshold logic (hit vs miss)
 * 3. Eviction with index recreation
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SemanticCacheService — Unit Tests")
class SemanticCacheServiceTest {

    @Mock private JedisPooled jedis;
    @Mock private EmbeddingModel cacheEmbeddingModel;
    @Mock private SemanticCacheProperties props;

    private MeterRegistry meterRegistry;
    private SemanticCacheService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new SemanticCacheService(jedis, cacheEmbeddingModel, props, meterRegistry);
    }

    private void stubEmbedding() {
        float[] fakeVector = new float[384];
        fakeVector[0] = 0.5f;
        Embedding embedding = Embedding.from(fakeVector);
        lenient().when(cacheEmbeddingModel.embed(anyString()))
                .thenReturn(new Response<>(embedding));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // lookup — fail-open resilience
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("lookup — fail-open resilience")
    class LookupResilienceTests {

        @Test
        @DisplayName("🔴 Redis connection down — should return empty, NOT crash pipeline")
        void redisDown_ShouldReturnEmpty() {
            stubEmbedding();
            when(props.getIndexName()).thenReturn("idx:semantic_cache");
            when(jedis.ftSearch(anyString(), any(redis.clients.jedis.search.Query.class)))
                    .thenThrow(new JedisConnectionException("Connection refused"));

            Optional<String> result = service.lookup("test query");

            assertThat(result).isEmpty();
            // Verify error metric was recorded
            double errorCount = meterRegistry.counter("semantic_cache.lookup", "result", "error").count();
            assertThat(errorCount).isEqualTo(1.0);
        }

        @Test
        @DisplayName("FT.SEARCH returns 0 results — should return empty (cache miss)")
        void noResults_ShouldReturnEmpty() {
            stubEmbedding();
            when(props.getIndexName()).thenReturn("idx:semantic_cache");
            SearchResult emptyResult = mock(SearchResult.class);
            when(emptyResult.getTotalResults()).thenReturn(0L);
            when(jedis.ftSearch(anyString(), any(redis.clients.jedis.search.Query.class)))
                    .thenReturn(emptyResult);

            Optional<String> result = service.lookup("test query");

            assertThat(result).isEmpty();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // lookup — similarity threshold
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("lookup — similarity threshold")
    class LookupThresholdTests {

        @Test
        @DisplayName("similarity ≥ threshold — should return cached context (HIT)")
        void aboveThreshold_ShouldReturnContext() {
            stubEmbedding();
            when(props.getIndexName()).thenReturn("idx:semantic_cache");
            when(props.getSimilarityThreshold()).thenReturn(0.88);

            // distance=0.05 → similarity=0.95 ≥ 0.88
            Document doc = mock(Document.class);
            when(doc.getString("score")).thenReturn("0.05");
            when(doc.getString("context")).thenReturn("cached RAG context");

            SearchResult searchResult = mock(SearchResult.class);
            when(searchResult.getTotalResults()).thenReturn(1L);
            when(searchResult.getDocuments()).thenReturn(List.of(doc));
            when(jedis.ftSearch(anyString(), any(redis.clients.jedis.search.Query.class)))
                    .thenReturn(searchResult);

            Optional<String> result = service.lookup("similar query");

            assertThat(result).isPresent().contains("cached RAG context");
            double hitCount = meterRegistry.counter("semantic_cache.lookup", "result", "hit").count();
            assertThat(hitCount).isEqualTo(1.0);
        }

        @Test
        @DisplayName("similarity < threshold — should return empty (MISS)")
        void belowThreshold_ShouldReturnEmpty() {
            stubEmbedding();
            when(props.getIndexName()).thenReturn("idx:semantic_cache");
            when(props.getSimilarityThreshold()).thenReturn(0.88);

            // distance=0.30 → similarity=0.70 < 0.88
            Document doc = mock(Document.class);
            when(doc.getString("score")).thenReturn("0.30");

            SearchResult searchResult = mock(SearchResult.class);
            when(searchResult.getTotalResults()).thenReturn(1L);
            when(searchResult.getDocuments()).thenReturn(List.of(doc));
            when(jedis.ftSearch(anyString(), any(redis.clients.jedis.search.Query.class)))
                    .thenReturn(searchResult);

            Optional<String> result = service.lookup("different query");

            assertThat(result).isEmpty();
            double missCount = meterRegistry.counter("semantic_cache.lookup", "result", "miss").count();
            assertThat(missCount).isEqualTo(1.0);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // store — fail-open
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("store — fail-open")
    class StoreTests {

        @Test
        @DisplayName("Redis error during store — should NOT crash (fire-and-forget)")
        void redisError_ShouldNotCrash() {
            stubEmbedding();
            when(props.getKeyPrefix()).thenReturn("scache:");
            when(jedis.hset(any(byte[].class), anyMap()))
                    .thenThrow(new JedisConnectionException("Connection lost"));

            // Should NOT throw
            service.store("query", "context");

            double errorCount = meterRegistry.counter("semantic_cache.store", "result", "error").count();
            assertThat(errorCount).isEqualTo(1.0);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // evictAllCache — index lifecycle
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("evictAllCache — index lifecycle")
    class EvictTests {

        @Test
        @DisplayName("index not found — should skip silently, no crash")
        void indexNotFound_ShouldSkip() {
            when(props.getIndexName()).thenReturn("idx:semantic_cache");
            doThrow(new JedisDataException("Unknown index name"))
                    .when(jedis).ftDropIndex(anyString());

            // Should NOT throw
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.evictAllCache());
        }

        @Test
        @DisplayName("happy path — should drop index then recreate it")
        void happyPath_ShouldDropAndRecreate() {
            when(props.getIndexName()).thenReturn("idx:semantic_cache");
            when(jedis.ftDropIndex(anyString())).thenReturn("OK");
            // ensureIndex() → ftInfo throws (index doesn't exist after drop) → then ftCreate
            when(jedis.ftInfo(anyString()))
                    .thenThrow(new JedisDataException("Unknown index name"));
            when(props.getEmbeddingDimension()).thenReturn(384);
            when(props.getHnswM()).thenReturn(16);
            when(props.getHnswEfConstruction()).thenReturn(200);
            when(props.getKeyPrefix()).thenReturn("scache:");
            when(props.getSimilarityThreshold()).thenReturn(0.88);

            service.evictAllCache();

            verify(jedis).ftDropIndex("idx:semantic_cache");
            // ftCreate should be called during ensureIndex()
            verify(jedis).ftCreate(eq("idx:semantic_cache"),
                    any(redis.clients.jedis.search.IndexOptions.class),
                    any(redis.clients.jedis.search.Schema.class));
        }
    }
}
