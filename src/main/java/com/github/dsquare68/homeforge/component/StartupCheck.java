package com.github.dsquare68.homeforge.component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.github.dsquare68.homeforge.db.DBConnection;
import com.github.dsquare68.homeforge.db.DatabaseStatus;
import com.github.dsquare68.homeforge.db.PluginDbCredentials;
import com.github.dsquare68.homeforge.db.PluginSchemaStatus;
import com.github.dsquare68.homeforge.plugin.PluginManagerService;

/**
 * Reports, once the application is up, whether HUB's database prerequisites are
 * actually in place: PostgreSQL configured, reachable, the hub schema visible,
 * a {@code users} table inside it — and then the same for every installed
 * plugin's own schema.
 *
 * <p>The plugin half pairs with
 * {@link PluginManagerService#provisionAndStartPlugins()}, which runs straight
 * after this check and adds whatever this check found missing: installing a
 * plugin (writing its {@code <plugin_id>.properties} file, then creating its
 * role, schema and grants) happens <em>once per jar</em>, while this check runs
 * over <em>all</em> plugins on every start. The schemas are read back from the credentials files
 * inside the plugin jars, so the check sees exactly what each plugin connects
 * with, without loading a single plugin class.
 *
 * <p>The check is deliberately read-only and non-fatal — a fresh install has no
 * database until first-run setup completes, so a failed check is logged as a
 * warning rather than aborting startup. It runs on {@link ApplicationReadyEvent}
 * so that JPA schema generation ({@code spring.jpa.hibernate.ddl-auto}) and
 * plugin installation have both already happened, and the result reflects the
 * real state.
 *
 * <p>Connects through {@link DBConnection} rather than the Spring
 * {@code DataSource} on purpose: it carries the overlay written by first-run
 * setup, so the check sees exactly the credentials and schema the rest of HUB
 * uses.
 */
@Component
public class StartupCheck {

    private static final Logger log = LoggerFactory.getLogger(StartupCheck.class);

    private static final String USERS_TABLE = "users";

    private static final String SCHEMA_EXISTS_SQL =
            "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname = ?)";
    private static final String USERS_TABLE_SCHEMAS_SQL =
            "SELECT table_schema FROM information_schema.tables WHERE table_name = ?";

    private final DBConnection connections;
    private final PluginManagerService plugins;
    private volatile DatabaseStatus status;

    public StartupCheck(DBConnection connections, PluginManagerService plugins) {
        this.connections = connections;
        this.plugins = plugins;
        this.status = DatabaseStatus.notConfigured(connections.getSchema());
    }

    /** The result of the most recent check. */
    public DatabaseStatus status() {
        return status;
    }

    /**
     * First thing to run once the application is up — ahead of
     * {@code PluginManagerService#provisionAndStartPlugins()}, which uses this
     * report to decide what still has to be added.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void checkOnStartup() {
        status = check();
        report(status);
    }

    /**
     * Runs the check again and republishes {@link #status()} — called after new
     * plugins have been provisioned, so the cached status covers them too.
     */
    public DatabaseStatus recheck() {
        status = check();
        report(status);
        return status;
    }

    /**
     * Runs the check now and returns the result without touching the cached
     * {@link #status()} — useful for re-checking after first-run setup or after
     * a plugin has been installed.
     */
    public DatabaseStatus check() {
        String schema = connections.getSchema();

        if (!connections.isConfigured()) {
            return DatabaseStatus.notConfigured(schema);
        }

        try (Connection connection = connections.open()) {
            boolean schemaPresent = schemaExists(connection, schema);

            List<String> schemasWithUsers = schemasContainingUsersTable(connection);
            boolean usersTablePresent = schemasWithUsers.contains(schema);
            List<String> elsewhere = schemasWithUsers.stream().filter(s -> !s.equals(schema)).toList();

            List<PluginSchemaStatus> pluginStatuses = checkPlugins(connection);

            return new DatabaseStatus(true, true, schemaPresent, usersTablePresent, schema, elsewhere,
                    pluginStatuses,
                    describe(schema, schemaPresent, usersTablePresent, elsewhere, pluginStatuses));
        } catch (SQLException e) {
            return DatabaseStatus.unreachable(schema, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Plugin schemas
    // ------------------------------------------------------------------

    /**
     * Checks every installed plugin using the schema and role recorded in the
     * {@code <plugin_id>.properties} file HUB wrote into its jar at install
     * time — all of them, on every start, however long ago each was installed.
     */
    private List<PluginSchemaStatus> checkPlugins(Connection connection) {
        List<PluginSchemaStatus> statuses = new ArrayList<>();
        for (PluginDbCredentials credentials : plugins.installedPlugins()) {
            statuses.add(checkPlugin(connection, credentials));
        }
        return statuses;
    }

    private PluginSchemaStatus checkPlugin(Connection connection, PluginDbCredentials credentials) {
        String schema = credentials.schema();
        String role = credentials.role();

        boolean schemaPresent;
        try {
            schemaPresent = schemaExists(connection, schema);
        } catch (SQLException e) {
            return new PluginSchemaStatus(credentials.pluginId(), schema, role, false, false,
                    "Could not check schema '" + schema + "': " + e.getMessage());
        }

        if (!schemaPresent) {
            return new PluginSchemaStatus(credentials.pluginId(), schema, role, false, false,
                    "Schema '" + schema + "' is missing — the plugin has credentials but no schema to use them on.");
        }

        // The schema existing is not enough: the credentials sitting in the jar
        // must still be the ones PostgreSQL knows about.
        try (Connection pluginConnection = connections.open(role, credentials.password(), schema)) {
            boolean valid = !pluginConnection.isClosed();
            return new PluginSchemaStatus(credentials.pluginId(), schema, role, true, valid,
                    valid
                            ? "Schema '" + schema + "' present, role " + role + " connects."
                            : "Role " + role + " did not open a usable connection.");
        } catch (SQLException e) {
            return new PluginSchemaStatus(credentials.pluginId(), schema, role, true, false,
                    "Schema '" + schema + "' present, but role " + role + " cannot connect: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Reporting
    // ------------------------------------------------------------------

    private String describe(String schema, boolean schemaPresent, boolean usersTablePresent,
            List<String> elsewhere, List<PluginSchemaStatus> plugins) {
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
        return "PostgreSQL reachable, schema '" + schema + "' and table '" + USERS_TABLE + "' present. "
                + describePlugins(plugins);
    }

    private String describePlugins(List<PluginSchemaStatus> plugins) {
        if (plugins.isEmpty()) {
            return "No plugins installed.";
        }
        long ready = plugins.stream().filter(PluginSchemaStatus::ready).count();
        return ready + "/" + plugins.size() + " plugin schema(s) ready.";
    }

    private void report(DatabaseStatus status) {
        if (status.ready()) {
            log.info("Database check: {}", status.detail());
        } else if (!status.configured()) {
            log.info("Database check: {}", status.detail());
        } else {
            log.warn("Database check: {}", status.detail());
        }

        for (PluginSchemaStatus plugin : status.plugins()) {
            if (plugin.ready()) {
                log.info("Plugin database check [{}]: {}", plugin.pluginId(), plugin.detail());
            } else {
                log.warn("Plugin database check [{}]: {}", plugin.pluginId(), plugin.detail());
            }
        }
    }
}
