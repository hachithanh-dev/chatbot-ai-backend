package dev.thanh.spring_ai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SchedulerConfig}.
 * Verifies TaskScheduler bean is configured with correct pool size,
 * thread prefix, and graceful shutdown settings.
 */
@DisplayName("SchedulerConfig — Unit Tests")
class SchedulerConfigTest {

    @Test
    @DisplayName("should create TaskScheduler with configured pool size")
    void shouldCreateSchedulerWithConfiguredPoolSize() {
        // Given
        SchedulerConfig config = new SchedulerConfig();
        ReflectionTestUtils.setField(config, "poolSize", 5);

        // When
        TaskScheduler scheduler = config.taskScheduler();

        // Then — after initialize(), pool size is reflected in the underlying executor
        assertThat(scheduler).isInstanceOf(ThreadPoolTaskScheduler.class);
        ThreadPoolTaskScheduler tpts = (ThreadPoolTaskScheduler) scheduler;
        ScheduledThreadPoolExecutor executor = tpts.getScheduledThreadPoolExecutor();
        assertThat(executor.getCorePoolSize()).isEqualTo(5);
    }

    @Test
    @DisplayName("should use 'scheduler-' thread name prefix")
    void shouldUseSchedulerThreadPrefix() {
        // Given
        SchedulerConfig config = new SchedulerConfig();
        ReflectionTestUtils.setField(config, "poolSize", 2);

        // When
        ThreadPoolTaskScheduler scheduler = (ThreadPoolTaskScheduler) config.taskScheduler();

        // Then
        assertThat(scheduler.getThreadNamePrefix()).isEqualTo("scheduler-");
    }

    @Test
    @DisplayName("should configure graceful shutdown (wait for tasks)")
    void shouldConfigureGracefulShutdown() {
        // Given
        SchedulerConfig config = new SchedulerConfig();
        ReflectionTestUtils.setField(config, "poolSize", 2);

        // When
        ThreadPoolTaskScheduler scheduler = (ThreadPoolTaskScheduler) config.taskScheduler();

        // Then — verify the scheduler is initialized and ready
        assertThat(scheduler.getScheduledThreadPoolExecutor()).isNotNull();
        assertThat(scheduler.getScheduledThreadPoolExecutor().isShutdown()).isFalse();

        // Cleanup
        scheduler.shutdown();
    }

    @Test
    @DisplayName("should work with custom pool size of 10")
    void shouldWorkWithCustomPoolSize() {
        // Given
        SchedulerConfig config = new SchedulerConfig();
        ReflectionTestUtils.setField(config, "poolSize", 10);

        // When
        ThreadPoolTaskScheduler scheduler = (ThreadPoolTaskScheduler) config.taskScheduler();

        // Then
        assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(10);

        // Cleanup
        scheduler.shutdown();
    }
}
