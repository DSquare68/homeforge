package com.github.dsquare68.homeforge.db;

import java.util.List;

import com.github.dsquare68.homeforge.component.StartupCheck;

/**
 * Outcome of the startup database check performed by
 * {@link StartupCheck}: HUB's own schema, plus one entry per installed plugin.
 *
 * @param configured        real PostgreSQL credentials are available (no
 *                          unresolved {@code ${DB_USER}} placeholders)
 * @param reachable         a connection to the PostgreSQL instance succeeded
 * @param schemaPresent     the expected hub schema exists and is visible to
 *                          the configured user
 * @param usersTablePresent a {@code users} table exists inside that schema
 * @param schema            the schema that was checked, e.g. {@code hub_schema}
 * @param otherUserTables   schemas other than {@code schema} that also contain
 *                          a {@code users} table — usually a sign that JPA
 *                          wrote to the connection's default schema instead
 * @param plugins           one result per installed plugin, read from the
 *                          {@code <plugin_id>.properties} in each plugin jar
 * @param detail            human-readable summary, including the failure
 *                          reason when the database could not be reached
 */
public record DatabaseStatus(
        boolean configured,
        boolean reachable,
        boolean schemaPresent,
        boolean usersTablePresent,
        String schema,
        List<String> otherUserTables,
        List<PluginSchemaStatus> plugins,
        String detail) {

    public DatabaseStatus {
        otherUserTables = otherUserTables == null ? List.of() : List.copyOf(otherUserTables);
        plugins = plugins == null ? List.of() : List.copyOf(plugins);
    }

    /** True when HUB has everything it needs to serve SQL-backed pages. */
    public boolean ready() {
        return configured && reachable && schemaPresent && usersTablePresent;
    }

    /** True when every installed plugin can reach its own schema. */
    public boolean pluginsReady() {
        return plugins.stream().allMatch(PluginSchemaStatus::ready);
    }

    /** Installed plugins whose schema or credentials did not check out. */
    public List<PluginSchemaStatus> brokenPlugins() {
        return plugins.stream().filter(plugin -> !plugin.ready()).toList();
    }

    public static DatabaseStatus notConfigured(String schema) {
        return new DatabaseStatus(false, false, false, false, schema, List.of(), List.of(),
                "PostgreSQL credentials are not configured yet — first-run setup has not completed.");
    }

    public static DatabaseStatus unreachable(String schema, String reason) {
        return new DatabaseStatus(true, false, false, false, schema, List.of(), List.of(),
                "PostgreSQL is configured but unreachable: " + reason);
    }
}
