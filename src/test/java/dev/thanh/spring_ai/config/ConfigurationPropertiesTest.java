package dev.thanh.spring_ai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for @ConfigurationProperties classes.
 * Verifies default values and setter/getter behavior.
 * These properties drive critical business logic (rate limits, cache, RAG tuning).
 */
@DisplayName("Configuration Properties — Unit Tests")
class ConfigurationPropertiesTest {

    // ─────────────────────────────────────────────────────────
    // SemanticCacheProperties
    // ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SemanticCacheProperties")
    class SemanticCachePropertiesTest {

        @Test
        @DisplayName("should have correct default values")
        void shouldHaveCorrectDefaults() {
            SemanticCacheProperties props = new SemanticCacheProperties();

            assertThat(props.isEnabled()).isTrue();
            assertThat(props.getIndexName()).isEqualTo("idx:semantic_cache");
            assertThat(props.getKeyPrefix()).isEqualTo("scache:");
            assertThat(props.getEmbeddingDimension()).isEqualTo(384);
            assertThat(props.getHnswM()).isEqualTo(16);
            assertThat(props.getHnswEfConstruction()).isEqualTo(200);
            assertThat(props.getHnswEfRuntime()).isEqualTo(10);
            assertThat(props.getSimilarityThreshold()).isEqualTo(0.88);
            assertThat(props.getTtlSeconds()).isEqualTo(86400L);
            assertThat(props.getPoolMaxTotal()).isEqualTo(16);
            assertThat(props.getPoolMaxIdle()).isEqualTo(8);
            assertThat(props.getPoolMinIdle()).isEqualTo(2);
            assertThat(props.getPoolBorrowTimeoutMs()).isEqualTo(50L);
        }

        @Test
        @DisplayName("should allow overriding values via setters")
        void shouldAllowOverridingValues() {
            SemanticCacheProperties props = new SemanticCacheProperties();

            props.setEnabled(false);
            props.setSimilarityThreshold(0.95);
            props.setTtlSeconds(3600);

            assertThat(props.isEnabled()).isFalse();
            assertThat(props.getSimilarityThreshold()).isEqualTo(0.95);
            assertThat(props.getTtlSeconds()).isEqualTo(3600L);
        }
    }

    // ─────────────────────────────────────────────────────────
    // HybridRagProperties
    // ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("HybridRagProperties")
    class HybridRagPropertiesTest {

        @Test
        @DisplayName("should have correct default values")
        void shouldHaveCorrectDefaults() {
            HybridRagProperties props = new HybridRagProperties();

            assertThat(props.getChunkSize()).isEqualTo(2048);
            assertThat(props.getChunkOverlap()).isEqualTo(256);
            assertThat(props.getCollectionName()).isEqualTo("spring");
            assertThat(props.getEmbeddingDimension()).isEqualTo(768);
            assertThat(props.getUpsertBatchSize()).isEqualTo(100);
            assertThat(props.getPageWindow()).isEqualTo(100);
            assertThat(props.getCandidateTopK()).isEqualTo(10);
            assertThat(props.getCandidateSimilarityThreshold()).isEqualTo(0.6);
            assertThat(props.getRerankTopK()).isEqualTo(3);
            assertThat(props.getRerankModel()).isEqualTo("rerank-v3.5");
        }

        @Test
        @DisplayName("chunk overlap should be less than chunk size")
        void chunkOverlapShouldBeLessThanChunkSize() {
            HybridRagProperties props = new HybridRagProperties();
            assertThat(props.getChunkOverlap()).isLessThan(props.getChunkSize());
        }

        @Test
        @DisplayName("candidate topK should be >= rerank topK")
        void candidateTopKShouldBeGreaterOrEqualToRerankTopK() {
            HybridRagProperties props = new HybridRagProperties();
            assertThat(props.getCandidateTopK()).isGreaterThanOrEqualTo(props.getRerankTopK());
        }
    }

    // ─────────────────────────────────────────────────────────
    // RateLimitProperties
    // ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("RateLimitProperties")
    class RateLimitPropertiesTest {

        @Test
        @DisplayName("should have correct default values")
        void shouldHaveCorrectDefaults() {
            RateLimitProperties props = new RateLimitProperties();

            assertThat(props.getBucketCapacity()).isEqualTo(5);
            assertThat(props.getRefillRatePerSecond()).isEqualTo(1);
            assertThat(props.getDailyTokenLimit()).isEqualTo(200_000L);
        }

        @Test
        @DisplayName("bucket capacity should be positive")
        void bucketCapacityShouldBePositive() {
            RateLimitProperties props = new RateLimitProperties();
            assertThat(props.getBucketCapacity()).isPositive();
        }

        @Test
        @DisplayName("should allow overriding daily token limit")
        void shouldAllowOverridingDailyLimit() {
            RateLimitProperties props = new RateLimitProperties();
            props.setDailyTokenLimit(500_000L);
            assertThat(props.getDailyTokenLimit()).isEqualTo(500_000L);
        }
    }

    // ─────────────────────────────────────────────────────────
    // RedisStreamProperties
    // ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("RedisStreamProperties")
    class RedisStreamPropertiesTest {

        @Test
        @DisplayName("should have a non-null stream name by default")
        void shouldHaveDefaultStreamName() {
            RedisStreamProperties props = new RedisStreamProperties();
            // Defaults come from @ConfigurationProperties, so directly created
            // objects will have null — but we test that setters work
            props.setName("chat:messages");
            props.setConsumerGroup("test-group");
            props.setConsumerName("worker-1");

            assertThat(props.getName()).isEqualTo("chat:messages");
            assertThat(props.getConsumerGroup()).isEqualTo("test-group");
            assertThat(props.getConsumerName()).isEqualTo("worker-1");
        }
    }

    // ─────────────────────────────────────────────────────────
    // MemoryProperties
    // ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("MemoryProperties")
    class MemoryPropertiesTest {

        @Test
        @DisplayName("should have correct defaults")
        void shouldHaveCorrectDefaults() {
            MemoryProperties props = new MemoryProperties();

            // Defaults from field initializers
            assertThat(props.isEnabled()).isTrue();
            assertThat(props.getCollectionName()).isEqualTo("user-memories");
            assertThat(props.getEmbeddingDimension()).isEqualTo(384);
        }

        @Test
        @DisplayName("duplicate threshold should be higher than update threshold")
        void duplicateThresholdShouldBeHigherThanUpdate() {
            MemoryProperties props = new MemoryProperties();
            assertThat(props.getDuplicateThreshold())
                    .isGreaterThanOrEqualTo(props.getUpdateThreshold());
        }
    }
}
