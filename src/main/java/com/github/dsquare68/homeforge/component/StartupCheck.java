package com.github.dsquare68.homeforge.component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.github.dsquare68.homeforge.db.DatabaseStatus;
import com.github.dsquare68.homeforge.db.HubDBProperties;
import com.github.dsquare68.homeforge.db.HubDBProvider;

/**
 * Reports, once the application is up, whether HUB's database prerequisites are
 * actually in place: PostgreSQL configured, reachable, the hub schema visible,
 * and a {@code users} table inside it.
 *
 * <p>The check is deliberately read-only and non-fatal — a fresh install has no
 * database until first-run setup completes, so a failed check is logged as a
 * warning rather than aborting startup. It runs on {@link ApplicationReadyEvent}
 * so that any JPA schema generation ({@code spring.jpa.hibernate.ddl-auto}) has
 * already happened and the result reflects the real state.
 *
 * <p>Connects through {@link HubDBProperties} rather than the Spring
 * {@code DataSource} on purpose: those properties include the overlay written by
 * first-run setup, so the check sees the same credentials and schema
 * {@link HubDBProvider} will use.
 */
@Component
public class StartupCheck {

    private static final Logger log = LoggerFactory.getLogger(StartupCheck.class);

    private static final String USERS_TABLE = "users";
    private static final String CONNECT_TIMEOUT_SECONDS = "5";
    private static final String SOCKET_TIMEOUT_SECONDS = "10";

    private static final String SCHEMA_EXISTS_SQL =
            "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname = ?)";
    private static final String USERS_TABLE_SCHEMAS_SQL =
            "SELECT table_schema FROM information_schema.tables WHERE table_name = ?";

    private final HubDBProperties properties;
    private volatile DatabaseStatus status;

    public StartupCheck(HubDBProperties properties) {
        this.properties = properties;
        this.status = DatabaseStatus.notConfigured(properties.getDefaultSchema());
    }

    /** The result of the most recent check. */
    public DatabaseStatus status() {
        return status;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkOnStartup() {
        status = check();
        report(status);
    }

    /**
     * Runs the check now and returns the result without touching the cached
     * {@link #status()} — useful for re-checking after first-run setup.
     */
    public DatabaseStatus check() {
        String schema = properties.getDefaultSchema();

        if (!properties.isConfigured()) {
            return DatabaseStatus.notConfigured(schema);
        }

        try (Connection connection = openConnection()) {
            boolean schemaPresent = schemaExists(connection, schema);

            List<String> schemasWithUsers = schemasContainingUsersTable(connection);
            boolean usersTablePresent = schemasWithUsers.contains(schema);
            List<String> elsewhere = schemasWithUsers.stream().filter(s -> !s.equals(schema)).toList();

            return new DatabaseStatus(true, true, schemaPresent, usersTablePresent, schema, elsewhere,
                    describe(schema, schemaPresent, usersTablePresent, elsewhere));
        } catch (SQLException e) {
            return DatabaseStatus.unreachable(schema, e.getMessage());
        }
    }

    private Connection openConnection() throws SQLException {
        loadDriver();

        Properties connectionProperties = new Properties();
        connectionProperties.setProperty("user", properties.getUsername());
        connectionProperties.setProperty("password", properties.getPassword());
        connectionProperties.setProperty("connectTimeout", CONNECT_TIMEOUT_SECONDS);
        connectionProperties.setProperty("socketTimeout", SOCKET_TIMEOUT_SECONDS);
        connectionProperties.setProperty("ApplicationName", "homeforge-startup-check");

        return DriverManager.getConnection(properties.getUrl(), connectionProperties);
    }

    private void loadDriver() {
        String driverClassName = properties.getDriverClassName();
        if (driverClassName == null || driverClassName.isBlank()) {
            return;
        }
        try {
            Class.forName(driverClassName);
        } catch (ClassNotFoundException e) {
            // The PostgreSQL driver registers itself via ServiceLoader; if this
            // explicit load fails, let DriverManager report the real problem.
        }
    }

    private boolean schemaExists(Connection connection, String schema) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SCHEMA_EXISTS_SQL)) {
            statement.setString(1, schema);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private List<String> schemasContainingUsersTable(Connection connection) throws SQLException {
        List<String> schemas = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(USERS_TABLE_SCHEMAS_SQL)) {
            statement.setString(1, USERS_TABLE);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    schemas.add(rs.getString(1));
                }
            }
        }
        return schemas;
    }

    private String describe(String schema, boolean schemaPresent, boolean usersTablePresent,
            List<String> elsewhere) {
        if (!schemaPresent) {
            return "Connected to PostgreSQL, but schema '" + schema + "' does not exist.";
        }
        if (!usersTablePresent) {
            String message = "Schema '" + schema + "' exists but has no '" + USERS_TABLE + "' table.";
            if (!elsewhere.isEmpty()) {
                message += " A '" + USERS_TABLE + "' table was found in " + elsewhere
                        + " instead — check that the JDBC URL pins currentSchema=" + schema + ".";
            }
            return message;
        }
        return "PostgreSQL reachable, schema '" + schema + "' and table '" + USERS_TABLE + "' present.";
    }

    private void report(DatabaseStatus status) {
        if (status.ready()) {
            log.info("Database check: {}", status.detail());
        } else if (!status.configured()) {
            log.info("Database check: {}", status.detail());
        } else {
            log.warn("Database check: {}", status.detail());
        }
    }
}
