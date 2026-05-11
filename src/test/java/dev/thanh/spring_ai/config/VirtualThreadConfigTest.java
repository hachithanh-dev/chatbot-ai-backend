package dev.thanh.spring_ai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link VirtualThreadConfig}.
 * Verifies the executor bean creates virtual threads.
 */
@DisplayName("VirtualThreadConfig — Unit Tests")
class VirtualThreadConfigTest {

    private final VirtualThreadConfig config = new VirtualThreadConfig();

    @Test
    @DisplayName("should create a non-null executor")
    void shouldCreateNonNullExecutor() {
        Executor executor = config.virtualThreadExecutor();
        assertThat(executor).isNotNull();
    }

    @Test
    @DisplayName("should execute tasks on virtual threads")
    void shouldExecuteOnVirtualThreads() throws Exception {
        // Given
        Executor executor = config.virtualThreadExecutor();
        AtomicReference<Boolean> isVirtual = new AtomicReference<>();

        // When
        Thread thread = Thread.ofVirtual().unstarted(() ->
                isVirtual.set(Thread.currentThread().isVirtual()));
        executor.execute(() -> isVirtual.set(Thread.currentThread().isVirtual()));

        // Wait for task to complete
        Thread.sleep(200);

        // Then — executed on a virtual thread
        assertThat(isVirtual.get()).isTrue();
    }

    @Test
    @DisplayName("should execute multiple tasks concurrently")
    void shouldExecuteMultipleTasksConcurrently() throws Exception {
        // Given
        Executor executor = config.virtualThreadExecutor();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(3);

        // When
        for (int i = 0; i < 3; i++) {
            executor.execute(latch::countDown);
        }

        // Then — all 3 tasks complete
        boolean completed = latch.await(2, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(completed).isTrue();
    }
}
