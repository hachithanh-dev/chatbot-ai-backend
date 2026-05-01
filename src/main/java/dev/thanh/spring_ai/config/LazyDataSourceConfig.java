package dev.thanh.spring_ai.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;

/**
 * Wraps the auto-configured HikariCP DataSource with
 * {@link LazyConnectionDataSourceProxy}.
 *
 * <h3>Why?</h3>
 * <p>
 * By default, when a {@code @Transactional} method starts, Spring immediately
 * acquires a physical JDBC connection from HikariCP — even if the method's
 * first
 * action is a Redis call, external API call, or other non-DB work.
 * {@code LazyConnectionDataSourceProxy} defers the physical connection checkout
 * until the first SQL statement ({@code prepareStatement()},
 * {@code createStatement()}) is actually executed.
 * </p>
 *
 * <h3>How it works</h3>
 * <ul>
 * <li>This {@link BeanPostProcessor} intercepts the HikariDataSource bean
 * <b>after</b> creation by {@code DataSourceAutoConfiguration}</li>
 * <li>It wraps it with {@code LazyConnectionDataSourceProxy} before any
 * other bean (Flyway, JPA EntityManagerFactory, etc.) consumes it</li>
 * <li>Flyway still works correctly — it executes SQL immediately during
 * migration, which triggers real connection acquisition</li>
 * <li>{@code setDefaultAutoCommit(false)} matches HikariCP's
 * {@code auto-commit: false} config, preventing the proxy from issuing
 * unnecessary {@code setAutoCommit()} calls on the logical connection</li>
 * </ul>
 */
@Configuration
public class LazyDataSourceConfig {

    /**
     * Name of the auto-configured {@code DataSource} bean created by
     * {@code DataSourceAutoConfiguration}. This is a fixed Spring Boot
     * convention — only changes if manually overridden.
     */
    private static final String PRIMARY_DATASOURCE_BEAN_NAME = "dataSource";

    @Bean
    public static BeanPostProcessor lazyConnectionDataSourceProxyPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName)
                    throws BeansException {

                // Only wrap the primary auto-configured HikariDataSource.
                // Prevents accidentally wrapping:
                // - Internal Spring DataSource beans (H2 console, test, etc.)
                // - Future read-replica DataSources
                // - The LazyConnectionDataSourceProxy itself (multi-pass safety)
                if (PRIMARY_DATASOURCE_BEAN_NAME.equals(beanName)
                        && bean instanceof DataSource ds
                        && !(bean instanceof LazyConnectionDataSourceProxy)) {

                    LazyConnectionDataSourceProxy proxy = new LazyConnectionDataSourceProxy(ds);

                    // Match hikari.auto-commit=false in application.yml.
                    // Without this, the proxy would assume autoCommit=true (JDBC default)
                    // and call setAutoCommit(false) on every logical connection — defeating
                    // the purpose of our Hikari optimization.
                    proxy.setDefaultAutoCommit(false);

                    return proxy;
                }
                return bean;
            }
        };
    }
}
