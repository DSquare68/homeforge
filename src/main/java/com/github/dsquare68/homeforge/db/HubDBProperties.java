package com.github.dsquare68.homeforge.db;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Connection settings used by {@link HubDBProvider} to open one pooled
 * DataSource per plugin schema, on the same PostgreSQL instance that hosts
 * hub_schema.
 */
@Component
@ConfigurationProperties(prefix = "hub.db")
@Data
public class HubDBProperties {

    private String defaultSchema = "hub_schema";
    private int maxPoolSize = 5;
}
