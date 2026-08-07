package com.github.dsquare68.homeforge.db;

/**
 * Result of checking one installed plugin's database, from the
 * {@code <plugin_id>.properties} file HUB wrote into its jar.
 *
 * @param pluginId         plugin id, e.g. {@code gym}
 * @param schema           schema the plugin owns, e.g. {@code gym_schema}
 * @param role             login role from the credentials file, e.g. {@code plugin_gym}
 * @param schemaPresent    the schema exists in PostgreSQL
 * @param credentialsValid the plugin's own credentials still open a connection —
 *                         catches a role that was dropped or a password rotated
 *                         out from under a stale properties file
 * @param detail           human-readable summary, including the failure reason
 */
public record PluginSchemaStatus(
        String pluginId,
        String schema,
        String role,
        boolean schemaPresent,
        boolean credentialsValid,
        String detail) {

    /** True when this plugin can reach its own data. */
    public boolean ready() {
        return schemaPresent && credentialsValid;
    }
}
