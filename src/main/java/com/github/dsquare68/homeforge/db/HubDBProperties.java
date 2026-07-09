package com.github.dsquare68.homeforge.db;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
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

    private static final String USER_PROPERTIES_FILE = "user.properties";

    private String url;
    private String username;
    private String password;
    private String driverClassName;
    private String defaultSchema = "hub_schema";
    private int maxPoolSize = 5;

    /**
     * Overlays the connection details entered on the first-run setup page
     * (written to {@value #USER_PROPERTIES_FILE} on the classpath by
     * {@code SignIn}) on top of whatever was bound from application.properties.
     */
    @PostConstruct
    void loadUserProperties() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(USER_PROPERTIES_FILE)) {
            if (in == null) {
                return;
            }
            Properties userProperties = new Properties();
            userProperties.load(in);

            String dbUser = userProperties.getProperty("DB_USER");
            String dbPassword = userProperties.getProperty("DB_PASSWORD");
            String dbSchema = userProperties.getProperty("DB_SCHEMA");

            if (dbUser != null && !dbUser.isBlank()) {
                username = dbUser;
            }
            if (dbPassword != null && !dbPassword.isBlank()) {
                password = dbPassword;
            }
            if (dbSchema != null && !dbSchema.isBlank()) {
                defaultSchema = dbSchema;
            }
        } catch (IOException e) {
            // No user-provided connection details yet; keep the bound defaults.
        }
    }

    /**
     * True once a real PostgreSQL user/password are available — from
     * secrets.properties, user.properties, or the environment — as opposed to
     * an unresolved {@code ${DB_USER}} placeholder. Used to keep SQL-backed
     * pages (login, registration) from touching the database before then.
     */
    public boolean isConfigured() {
        return isResolved(username) && isResolved(password);
    }

    private static boolean isResolved(String value) {
        return value != null && !value.isBlank() && !value.contains("${");
    }
}
