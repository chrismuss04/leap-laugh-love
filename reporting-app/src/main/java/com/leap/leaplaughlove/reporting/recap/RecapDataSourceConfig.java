package com.leap.leaplaughlove.reporting.recap;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * The reporting service's two connection pools. Every report reads through the main pool, which
 * stays read-only so an accidental write fails loudly; recaps are the one thing this service
 * writes, through a small pool of its own that only {@link RecapSnapshotRepository} uses.
 *
 * <p>Declaring a second DataSource turns off Spring Boot's own, so the main pool is declared here
 * too, bound to the same spring.datasource properties as before: reports, JdbcTemplate and the
 * session check see no difference.
 */
@Configuration
public class RecapDataSourceConfig {

    /**
     * The connection settings both pools share.
     * @return spring.datasource's url, username and password
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * The main, read-only pool, with spring.datasource.hikari's settings.
     * @param properties the shared connection settings
     * @return the pool every report reads through
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    /**
     * The pool recaps are written through: the same database, writable, and small, since only the
     * monthly job writes.
     * @param properties the shared connection settings
     * @param maximumPoolSize the most connections it opens
     * @return the recap write pool
     */
    @Bean
    public HikariDataSource recapWriteDataSource(DataSourceProperties properties,
                                                 @Value("${reporting.recap.write-pool-size:2}") int maximumPoolSize) {
        HikariDataSource dataSource = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        dataSource.setPoolName("recap-write");
        dataSource.setReadOnly(false);
        dataSource.setMaximumPoolSize(maximumPoolSize);
        dataSource.setMinimumIdle(0);
        return dataSource;
    }
}
