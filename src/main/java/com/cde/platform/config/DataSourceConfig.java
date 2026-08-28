package com.cde.platform.config;

import com.cde.platform.tenancy.TenancyProperties;
import com.cde.platform.tenancy.TenantAwareDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Two DataSources over the same database, because migrations and application
 * queries need different privileges.
 *
 * <p>Everything the application does goes through {@link TenantAwareDataSource},
 * which drops each connection into the restricted role and pins it to the
 * caller's tenant. Flyway cannot: the restricted role has no DDL rights, and
 * more importantly a migration legitimately operates across all tenants, so
 * running it under a tenant filter would apply changes to one tenant's rows and
 * silently skip the rest.
 *
 * <p>Flyway therefore gets the raw DataSource, marked {@code @FlywayDataSource}
 * so Spring Boot's auto-configuration picks it up instead of the primary one.
 */
@Configuration
public class DataSourceConfig {

    /**
     * The connection pool itself. Not exposed as the primary DataSource —
     * injecting this directly would bypass tenant isolation, so the only bean
     * that references it is the wrapper below and Flyway.
     */
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource unrestrictedDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @org.springframework.boot.flyway.autoconfigure.FlywayDataSource
    public DataSource flywayDataSource(@Qualifier("unrestrictedDataSource") HikariDataSource pool) {
        return pool;
    }

    /**
     * What every repository, every service and every query in the application
     * actually uses.
     */
    @Bean
    @Primary
    public DataSource dataSource(@Qualifier("unrestrictedDataSource") HikariDataSource pool,
                                 TenancyProperties tenancyProperties) {
        return new TenantAwareDataSource(pool, tenancyProperties.getApplicationRole());
    }
}
