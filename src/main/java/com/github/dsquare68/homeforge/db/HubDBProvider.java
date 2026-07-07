package com.github.dsquare68.homeforge.db;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.github.dsquare68.homeforgeapi.spi.PluginMetadata;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import jakarta.annotation.PreDestroy;

/**
 * Vends a pooled PostgreSQL {@link DataSource} per plugin schema, backing
 * {@code StorageApi#dataSource()}.
 *
 * <p>Every DataSource returned here is pinned to a single plugin's schema
 * (via Hikari's {@code currentSchema}), so a plugin can only ever read or
 * write its own tables — never another plugin's schema or hub_schema.
 */
@Component
public class HubDBProvider {

    private static final Pattern VALID_SCHEMA_NAME = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private final HubDBProperties properties;
    private final Map<String, DataSource> pluginDataSources = new ConcurrentHashMap<>();
    private volatile DataSource bootstrapDataSource;

    public HubDBProvider(HubDBProperties properties) {
        this.properties = properties;
    }

    /**
     * Returns the pooled DataSource scoped to this plugin's schema, creating
     * both the schema and the pool on first access.
     */
    public DataSource dataSourceFor(PluginMetadata metadata) {
        return dataSourceFor(metadata.schema());
    }

    /**
     * Returns the pooled DataSource scoped to the given schema, creating both
     * the schema and the pool on first access.
     */
    public DataSource dataSourceFor(String schema) {
        return pluginDataSources.computeIfAbsent(schema, this::createSchemaDataSource);
    }

    @PreDestroy
    public void shutdown() {
        pluginDataSources.values().forEach(this::closeQuietly);
        pluginDataSources.clear();
        if (bootstrapDataSource != null) {
            closeQuietly(bootstrapDataSource);
        }
    }

    private DataSource createSchemaDataSource(String schema) {
        if (!VALID_SCHEMA_NAME.matcher(schema).matches()) {
            throw new IllegalArgumentException("Invalid plugin schema name: " + schema);
        }

        ensureSchemaExists(schema);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.getUrl());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setDriverClassName(properties.getDriverClassName());
        config.setSchema(schema);
        config.setPoolName("hub-plugin-" + schema);
        config.setMaximumPoolSize(properties.getMaxPoolSize());

        return new HikariDataSource(config);
    }

    private void ensureSchemaExists(String schema) {
        new JdbcTemplate(bootstrapDataSource())
                .execute("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
    }

    private DataSource bootstrapDataSource() {
        DataSource existing = bootstrapDataSource;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (bootstrapDataSource == null) {
                HikariConfig config = new HikariConfig();
                config.setJdbcUrl(properties.getUrl());
                config.setUsername(properties.getUsername());
                config.setPassword(properties.getPassword());
                config.setDriverClassName(properties.getDriverClassName());
                config.setSchema(properties.getDefaultSchema());
                config.setPoolName("hub-plugin-bootstrap");
                config.setMaximumPoolSize(2);
                bootstrapDataSource = new HikariDataSource(config);
            }
            return bootstrapDataSource;
        }
    }

    private void closeQuietly(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikariDataSource) {
            hikariDataSource.close();
        }
    }
}
