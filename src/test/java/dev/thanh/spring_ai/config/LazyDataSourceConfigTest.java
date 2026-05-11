package dev.thanh.spring_ai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link LazyDataSourceConfig}.
 * Verifies the BeanPostProcessor correctly wraps HikariDataSource
 * with LazyConnectionDataSourceProxy.
 */
@DisplayName("LazyDataSourceConfig — Unit Tests")
class LazyDataSourceConfigTest {

    private final BeanPostProcessor postProcessor = LazyDataSourceConfig.lazyConnectionDataSourceProxyPostProcessor();

    @Test
    @DisplayName("should wrap 'dataSource' bean with LazyConnectionDataSourceProxy")
    void shouldWrapDataSourceBean() {
        // Given
        DataSource mockDs = mock(DataSource.class);

        // When
        Object result = postProcessor.postProcessAfterInitialization(mockDs, "dataSource");

        // Then
        assertThat(result).isInstanceOf(LazyConnectionDataSourceProxy.class);
        LazyConnectionDataSourceProxy proxy = (LazyConnectionDataSourceProxy) result;
        assertThat(proxy.getTargetDataSource()).isSameAs(mockDs);
    }

    @Test
    @DisplayName("should NOT wrap beans with different names")
    void shouldNotWrapOtherBeans() {
        // Given
        DataSource mockDs = mock(DataSource.class);

        // When
        Object result = postProcessor.postProcessAfterInitialization(mockDs, "someOtherDataSource");

        // Then — should return the original bean, not wrapped
        assertThat(result).isSameAs(mockDs);
        assertThat(result).isNotInstanceOf(LazyConnectionDataSourceProxy.class);
    }

    @Test
    @DisplayName("should NOT double-wrap if already LazyConnectionDataSourceProxy")
    void shouldNotDoubleWrap() {
        // Given — already wrapped
        DataSource innerDs = mock(DataSource.class);
        LazyConnectionDataSourceProxy alreadyWrapped = new LazyConnectionDataSourceProxy(innerDs);

        // When
        Object result = postProcessor.postProcessAfterInitialization(alreadyWrapped, "dataSource");

        // Then — should return as-is, not wrap again
        assertThat(result).isSameAs(alreadyWrapped);
    }

    @Test
    @DisplayName("should pass through non-DataSource beans unchanged")
    void shouldPassThroughNonDataSourceBeans() {
        // Given
        String notADataSource = "I'm a String bean";

        // When
        Object result = postProcessor.postProcessAfterInitialization(notADataSource, "dataSource");

        // Then — String is not a DataSource, should pass through
        assertThat(result).isSameAs(notADataSource);
    }
}
