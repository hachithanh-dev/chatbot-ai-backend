package dev.thanh.spring_ai.config;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.ConnectionPoolConfig;

/**
 * Bean definitions cho Semantic Cache.
 *
 * <p>
 * Tạo {@link JedisPooled} riêng biệt cho FT commands (FT.CREATE, FT.SEARCH) —
 * KHÔNG ảnh hưởng Lettuce auto-config của Spring Data Redis.
 *
 * <p>
 * Local embedding model (multilingual-e5-small) được cấu hình tại
 * {@link LocalEmbeddingConfig} — tạo wrapper bean {@code cacheEmbeddingModel}
 * với prefix "query: " tự động.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "semantic-cache.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class SemanticCacheConfig {

    /**
     * JedisPooled dành riêng cho Semantic Cache — FT.CREATE / FT.SEARCH.
     *
     * <p>
     * Dùng {@link JedisPooled} (extends UnifiedJedis) thay vì JedisPool
     * vì Jedis 6.x chỉ expose ftCreate/ftSearch trên UnifiedJedis.
     *
     * <p>
     * Pool config tuned cho chatbot workload:
     * <ul>
     * <li>maxTotal=16 — FT.SEARCH rất nhẹ, 16 connections đủ cho concurrent
     * requests</li>
     * <li>borrowTimeout=50ms — skip cache ngay nếu pool busy (fail-open)</li>
     * </ul>
     */
    @Bean(destroyMethod = "close")
    public JedisPooled semanticCacheJedis(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            SemanticCacheProperties props) {

        ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();
        poolConfig.setMaxTotal(props.getPoolMaxTotal());
        poolConfig.setMaxIdle(props.getPoolMaxIdle());
        poolConfig.setMinIdle(props.getPoolMinIdle());
        poolConfig.setMaxWait(Duration.ofMillis(props.getPoolBorrowTimeoutMs()));
        poolConfig.setBlockWhenExhausted(true);

        DefaultJedisClientConfig.Builder clientConfigBuilder = DefaultJedisClientConfig.builder()
                .timeoutMillis(3000)
                .connectionTimeoutMillis(3000);

        if (password != null && !password.isEmpty()) {
            clientConfigBuilder.password(password);
        }

        JedisPooled jedis = new JedisPooled(
                poolConfig,
                new HostAndPort(host, port),
                clientConfigBuilder.build());

        log.info("✅ Semantic Cache JedisPooled created: host={}:{}, maxTotal={}, borrowTimeout={}ms",
                host, port, props.getPoolMaxTotal(), props.getPoolBorrowTimeoutMs());

        return jedis;
    }
}

