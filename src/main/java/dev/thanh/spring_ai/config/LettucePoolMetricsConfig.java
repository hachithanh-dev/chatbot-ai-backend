package dev.thanh.spring_ai.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Expose Lettuce connection pool metrics to Prometheus via Micrometer.
 * <p>
 * <b>Tại sao cần class này?</b>
 * <ul>
 * <li>Spring Boot auto-instrument HikariCP pool metrics, nhưng KHÔNG
 * auto-instrument
 * Lettuce pool (commons-pool2 GenericObjectPool).</li>
 * <li>Class này dùng reflection để truy cập {@link GenericObjectPool} bên trong
 * {@link LettuceConnectionFactory} và register các Gauge metrics.</li>
 * </ul>
 * <p>
 * <b>Tại sao dùng ApplicationReadyEvent thay vì MeterBinder?</b>
 * <ul>
 * <li>GenericObjectPool được tạo <b>lazily</b> — chỉ khi connection đầu tiên
 * được request.</li>
 * <li>MeterBinder chạy quá sớm (trước khi pool tồn tại) → reflection trả về
 * null.</li>
 * <li>ApplicationReadyEvent chạy sau khi tất cả beans đã sẵn sàng, cho phép ta
 * force
 * tạo connection trước, rồi mới extract pool.</li>
 * </ul>
 * <p>
 * <b>Metrics exposed:</b>
 * <ul>
 * <li>{@code lettuce_pool_active} — Số connections đang được sử dụng (borrowed
 * from pool)</li>
 * <li>{@code lettuce_pool_idle} — Số connections đang rảnh trong pool</li>
 * <li>{@code lettuce_pool_pending} — Số threads đang chờ connection (⚠️ nếu > 0
 * liên tục → cần tăng max-active)</li>
 * <li>{@code lettuce_pool_max} — Max pool size (max-active config)</li>
 * <li>{@code lettuce_pool_created_total} — Tổng connections đã tạo từ khi start
 * (dùng rate() để detect churn)</li>
 * <li>{@code lettuce_pool_destroyed_total} — Tổng connections đã bị
 * destroy</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "spring.data.redis.lettuce.pool.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class LettucePoolMetricsConfig {

    private final LettuceConnectionFactory connectionFactory;
    private final MeterRegistry meterRegistry;

    /**
     * Register Lettuce pool metrics after application is fully started.
     * <p>
     * Phải dùng ApplicationReadyEvent vì:
     * <ol>
     * <li>LettuceConnectionFactory tạo pool lazily (khi connection đầu tiên được
     * request)</li>
     * <li>ConnectionProvider bị wrap: ExceptionTranslatingConnectionProvider →
     * LettucePoolingConnectionProvider</li>
     * <li>Nên phải force tạo connection trước, rồi mới traverse chain để tìm
     * GenericObjectPool</li>
     * </ol>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registerMetrics() {
        // Step 1: Force pool initialization bằng cách tạo + trả connection
        // Pool chỉ tồn tại SAU KHI connection đầu tiên được borrow
        forcePoolInitialization();

        // Step 2: Extract GenericObjectPool qua reflection
        GenericObjectPool<?> pool = extractPool();

        if (pool == null) {
            log.warn(
                    "⚠️ Cannot extract GenericObjectPool from LettuceConnectionFactory — Lettuce pool metrics unavailable");
            return;
        }

        // Step 3: Register tất cả Gauge metrics
        registerPoolMetrics(pool);
        log.info("✅ Lettuce pool metrics registered: lettuce_pool_active={}, lettuce_pool_idle={}, max={}",
                pool.getNumActive(), pool.getNumIdle(), pool.getMaxTotal());
    }

    /**
     * Force pool initialization bằng cách borrow và trả connection.
     * <p>
     * GenericObjectPool chỉ được tạo khi {@code getConnection()} được gọi lần đầu.
     * Nếu không force, pool = null → reflection thất bại.
     */
    private void forcePoolInitialization() {
        try {
            RedisConnection conn = connectionFactory.getConnection();
            conn.close(); // Trả connection lại pool ngay lập tức
            log.debug("Lettuce pool initialized via warm-up connection");
        } catch (Exception e) {
            log.warn("Failed to warm up Lettuce pool: {}", e.getMessage());
        }
    }

    /**
     * Register all pool metrics as Micrometer Gauges.
     * <p>
     * Sử dụng Gauge (không phải Counter) vì commons-pool2 chỉ expose snapshot
     * values.
     * Để detect Connection Churn, dùng {@code rate(lettuce_pool_created_total[1m])}
     * trong PromQL.
     */
    private void registerPoolMetrics(GenericObjectPool<?> pool) {
        // Active connections — đang được sử dụng bởi application threads
        Gauge.builder("lettuce_pool_active", pool, GenericObjectPool::getNumActive)
                .description("Number of Lettuce connections currently borrowed from the pool")
                .register(meterRegistry);

        // Idle connections — rảnh trong pool, sẵn sàng cho request tiếp theo
        Gauge.builder("lettuce_pool_idle", pool, GenericObjectPool::getNumIdle)
                .description("Number of idle Lettuce connections in the pool")
                .register(meterRegistry);

        // Pending threads — threads đang block chờ connection
        // ⚠️ Nếu > 0 liên tục → CẦN tăng max-active
        Gauge.builder("lettuce_pool_pending", pool, GenericObjectPool::getNumWaiters)
                .description("Number of threads waiting for a Lettuce connection from the pool")
                .register(meterRegistry);

        // Max pool size — ceiling (từ config max-active)
        Gauge.builder("lettuce_pool_max", pool, p -> p.getMaxTotal())
                .description("Maximum number of connections in the Lettuce pool (max-active)")
                .register(meterRegistry);

        // Total created — dùng rate() trong Prometheus để detect Connection Churn
        Gauge.builder("lettuce_pool_created_total", pool, GenericObjectPool::getCreatedCount)
                .description("Total Lettuce connections created since application start")
                .register(meterRegistry);

        // Total destroyed — nếu rate(destroyed) cao → evictor hoặc churn đang diễn ra
        Gauge.builder("lettuce_pool_destroyed_total", pool, GenericObjectPool::getDestroyedCount)
                .description("Total Lettuce connections destroyed since application start")
                .register(meterRegistry);
    }

    /**
     * Extract the underlying {@link GenericObjectPool} from
     * LettuceConnectionFactory.
     * <p>
     * Cấu trúc internal (Spring Data Redis 3.x):
     * 
     * <pre>
     * LettuceConnectionFactory
     *   └─ connectionProvider (ExceptionTranslatingConnectionProvider)
     *        └─ delegate (LettucePoolingConnectionProvider)
     *             └─ pools (Map&lt;Class, GenericObjectPool&gt;)
     *                  └─ values() → GenericObjectPool instances
     * </pre>
     * 
     * Mỗi lớp wrap thêm 1 level, nên phải traverse chain.
     */
    private GenericObjectPool<?> extractPool() {
        try {
            // Step 1: Lấy connectionProvider từ LettuceConnectionFactory
            Object provider = getFieldValue(connectionFactory, "connectionProvider");
            if (provider == null) {
                log.debug("connectionProvider field not found");
                return null;
            }

            // Step 2: Traverse provider chain (unwrap delegates)
            // ExceptionTranslatingConnectionProvider → delegate →
            // LettucePoolingConnectionProvider
            Object poolingProvider = unwrapProvider(provider, 5);

            if (poolingProvider == null) {
                log.debug("Could not unwrap to LettucePoolingConnectionProvider");
                return null;
            }

            // Step 3: Lấy GenericObjectPool từ pools Map
            return extractPoolFromProvider(poolingProvider);

        } catch (Exception e) {
            log.debug("Reflection extraction failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Unwrap provider chain để tìm LettucePoolingConnectionProvider.
     * <p>
     * Chain có thể là:
     * ExceptionTranslatingConnectionProvider → delegate →
     * LettucePoolingConnectionProvider
     * Hoặc trực tiếp LettucePoolingConnectionProvider (tùy config)
     */
    private Object unwrapProvider(Object provider, int maxDepth) {
        if (maxDepth <= 0 || provider == null)
            return null;

        String className = provider.getClass().getSimpleName();

        // Đã tìm thấy LettucePoolingConnectionProvider
        if (className.contains("PoolingConnectionProvider")) {
            return provider;
        }

        // Thử unwrap qua "delegate" field (ExceptionTranslatingConnectionProvider
        // pattern)
        Object delegate = getFieldValue(provider, "delegate");
        if (delegate != null) {
            Object result = unwrapProvider(delegate, maxDepth - 1);
            if (result != null)
                return result;
        }

        // Thử unwrap qua "connectionProvider" field
        Object inner = getFieldValue(provider, "connectionProvider");
        if (inner != null) {
            Object result = unwrapProvider(inner, maxDepth - 1);
            if (result != null)
                return result;
        }

        // Scan tất cả fields cho LettuceConnectionProvider subtypes
        for (Field field : provider.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                Object value = field.get(provider);
                if (value != null && value.getClass().getSimpleName().contains("PoolingConnectionProvider")) {
                    return value;
                }
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    /**
     * Extract GenericObjectPool từ LettucePoolingConnectionProvider.
     * <p>
     * Provider chứa field "pools" (Map&lt;Class, GenericObjectPool&gt;).
     * Lấy giá trị đầu tiên trong Map.
     */
    @SuppressWarnings("unchecked")
    private GenericObjectPool<?> extractPoolFromProvider(Object poolingProvider) {
        // Scan tất cả fields để tìm Map chứa GenericObjectPool
        for (Field field : poolingProvider.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                Object value = field.get(poolingProvider);

                // Trường hợp 1: Field trực tiếp là GenericObjectPool
                if (value instanceof GenericObjectPool<?> pool) {
                    return pool;
                }

                // Trường hợp 2: Field là Map chứa GenericObjectPool (pools field)
                if (value instanceof Map<?, ?> map) {
                    for (Object mapValue : map.values()) {
                        if (mapValue instanceof GenericObjectPool<?> pool) {
                            return pool;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        log.debug("No GenericObjectPool found in {}", poolingProvider.getClass().getSimpleName());
        return null;
    }

    /**
     * Get field value via reflection, traversing class hierarchy.
     */
    private Object getFieldValue(Object target, String fieldName) {
        Class<?> clazz = target.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass(); // Walk up hierarchy
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }
}
