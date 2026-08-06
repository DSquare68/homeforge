package com.github.dsquare68.homeforge.db;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.github.dsquare68.homeforgeapi.spi.PluginMetadata;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import jakarta.annotation.PreDestroy;

/**
 * Owns everything HUB does to PostgreSQL on a plugin's behalf:
 *
 * <ul>
 *   <li>provisioning a plugin's dedicated login role, schema and grants
 *       ({@link #provisionIfNeeded(Path)})</li>
 *   <li>writing the generated credentials into {@code <plugin_id>.properties}
 *       inside the plugin jar, so the plugin can read them as a classpath
 *       resource and open its own connection pool</li>
 *   <li>revoking all of it again on uninstall ({@link #deprovision(String, String)})</li>
 *   <li>vending a pooled {@link DataSource} per plugin schema, backing
 *       {@code StorageApi#dataSource()}</li>
 * </ul>
 *
 * <p>Provisioning runs with HUB's own (privileged) credentials from
 * {@link HubDBProperties}; those never leave this class. Each plugin only ever
 * sees the scoped role generated for it, which owns its schema and has no
 * grants on {@code hub_schema} or any other plugin's schema.
 *
 * <p>Tables are <em>not</em> created here: HUB cannot know a plugin's data
 * model. The provisioned role owns its schema, so the plugin creates its own
 * tables from its own migrations (see the plugin template's Flyway setup) using
 * the credentials written into its jar.
 */
@Component
public class HubDBProvider {

    private static final Logger log = LoggerFactory.getLogger(HubDBProvider.class);

    private static final Pattern VALID_IDENTIFIER = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    /** Manifest attributes written by the plugin build — see the plugin template pom. */
    private static final String MANIFEST_PLUGIN_ID = "Plugin-Id";
    private static final String MANIFEST_HUB_SCHEMA = "Hub-Schema";

    private static final String ROLE_PREFIX = "plugin_";
    private static final int PASSWORD_BYTES = 24;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final HubDBProperties properties;
    private final Map<String, DataSource> pluginDataSources = new ConcurrentHashMap<>();
    private volatile DataSource bootstrapDataSource;

    public HubDBProvider(HubDBProperties properties) {
        this.properties = properties;
    }

    /** True once real PostgreSQL credentials are available for HUB itself. */
    public boolean isDatabaseConfigured() {
        return properties.isConfigured();
    }

    // ------------------------------------------------------------------
    // Provisioning
    // ------------------------------------------------------------------

    /**
     * Provisions the plugin at {@code pluginPath} unless it already has both a
     * live role and a credentials file, so restarts are a no-op and passwords
     * are not rotated behind a running plugin's back.
     *
     * <p>Must be called <em>before</em> PF4J loads the plugin: the credentials
     * file is written into the jar, which cannot be modified while the plugin
     * classloader holds it open.
     *
     * @return the credentials, or empty when the plugin was already provisioned
     */
    public Optional<PluginDbCredentials> provisionIfNeeded(Path pluginPath) {
        PluginIdentity identity = readIdentity(pluginPath);

        if (isProvisioned(identity, pluginPath)) {
            log.debug("Plugin '{}' already provisioned (role {}, schema {})",
                    identity.id(), roleNameFor(identity.id()), identity.schema());
            return Optional.empty();
        }
        return Optional.of(provision(pluginPath));
    }

    /**
     * Creates (or rotates) the plugin's PostgreSQL role, creates and hands it
     * ownership of its schema, revokes everything else, and writes the
     * resulting connection details into {@code <plugin_id>.properties} inside
     * the plugin jar.
     *
     * <p>The database work runs first: if the file cannot be written the caller
     * gets an exception and the plugin is left unloaded rather than started
     * with credentials it cannot read.
     */
    public PluginDbCredentials provision(Path pluginPath) {
        PluginIdentity identity = readIdentity(pluginPath);
        return provision(identity.id(), identity.schema(), pluginPath);
    }

    /** Provisioning for a plugin whose metadata is already known. */
    public PluginDbCredentials provision(PluginMetadata metadata, Path pluginPath) {
        return provision(metadata.id(), metadata.schema(), pluginPath);
    }

    /**
     * Provisions {@code schema} for {@code pluginId} and writes the credentials
     * file into {@code pluginPath} (a plugin jar, or an exploded plugin
     * directory).
     */
    public PluginDbCredentials provision(String pluginId, String schema, Path pluginPath) {
        requireConfigured();

        String role = roleNameFor(pluginId);
        requireValidIdentifier(schema, "schema");
        requireValidIdentifier(role, "role");

        String password = generatePassword();
        PluginDbCredentials credentials = new PluginDbCredentials(
                pluginId, schema, role, password,
                pluginJdbcUrl(schema), properties.getDriverClassName());

        createRole(role, password);
        createSchema(schema, role);
        applyGrants(schema, role);
        writeCredentialsFile(pluginPath, credentials);

        log.info("Provisioned plugin '{}': role {} owns schema {}", pluginId, role, schema);
        return credentials;
    }

    /**
     * Drops everything {@link #provision(String, String, Path)} created: the
     * plugin's objects, its schema and its login role. The credentials file
     * disappears with the jar, but is removed explicitly when the jar is kept.
     */
    public void deprovision(String pluginId, String schema) {
        requireConfigured();

        String role = roleNameFor(pluginId);
        requireValidIdentifier(schema, "schema");
        requireValidIdentifier(role, "role");

        JdbcTemplate jdbc = new JdbcTemplate(bootstrapDataSource());
        closePool(schema);

        if (roleExists(jdbc, role)) {
            jdbc.execute("DROP OWNED BY " + quoteIdentifier(role) + " CASCADE");
        }
        jdbc.execute("DROP SCHEMA IF EXISTS " + quoteIdentifier(schema) + " CASCADE");
        jdbc.execute("DROP ROLE IF EXISTS " + quoteIdentifier(role));

        log.info("Deprovisioned plugin '{}': dropped role {} and schema {}", pluginId, role, schema);
    }

    /** Removes a stale {@code <plugin_id>.properties} from a plugin jar or directory. */
    public void removeCredentialsFile(String pluginId, Path pluginPath) {
        String entry = credentialsFileName(pluginId);
        try {
            if (Files.isDirectory(pluginPath)) {
                Files.deleteIfExists(credentialsPathInDirectory(pluginPath, entry));
                return;
            }
            try (FileSystem jar = FileSystems.newFileSystem(pluginPath, Map.of())) {
                Files.deleteIfExists(jar.getPath("/" + entry));
            }
        } catch (IOException e) {
            log.warn("Could not remove {} from {}: {}", entry, pluginPath, e.getMessage());
        }
    }

    /** {@code gym} → {@code plugin_gym}; non-identifier characters become underscores. */
    public String roleNameFor(String pluginId) {
        return ROLE_PREFIX + pluginId.toLowerCase().replaceAll("[^a-z0-9_]", "_");
    }

    // ------------------------------------------------------------------
    // Per-plugin DataSources (StorageApi)
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // SQL
    // ------------------------------------------------------------------

    private void createRole(String role, String password) {
        JdbcTemplate jdbc = new JdbcTemplate(bootstrapDataSource());
        String quotedRole = quoteIdentifier(role);
        String literalPassword = quoteLiteral(password);

        if (roleExists(jdbc, role)) {
            jdbc.execute("ALTER ROLE " + quotedRole + " WITH LOGIN PASSWORD " + literalPassword);
        } else {
            jdbc.execute("CREATE ROLE " + quotedRole + " LOGIN PASSWORD " + literalPassword);
            grantRoleToHub(jdbc, quotedRole);
        }

        String database = databaseName();
        if (database != null) {
            jdbc.execute("GRANT CONNECT ON DATABASE " + quoteIdentifier(database) + " TO " + quotedRole);
        }
    }

    /**
     * A non-superuser HUB role needs membership in the new role before it can
     * hand it schema ownership. Superusers do not, and PostgreSQL 16+ grants it
     * implicitly — so a failure here is not fatal, the ownership statement will
     * report the real problem if there is one.
     */
    private void grantRoleToHub(JdbcTemplate jdbc, String quotedRole) {
        try {
            jdbc.execute("GRANT " + quotedRole + " TO CURRENT_USER");
        } catch (RuntimeException e) {
            log.debug("Could not grant {} to the HUB role: {}", quotedRole, e.getMessage());
        }
    }

    private void createSchema(String schema, String role) {
        JdbcTemplate jdbc = new JdbcTemplate(bootstrapDataSource());
        String quotedSchema = quoteIdentifier(schema);
        String quotedRole = quoteIdentifier(role);

        jdbc.execute("CREATE SCHEMA IF NOT EXISTS " + quotedSchema + " AUTHORIZATION " + quotedRole);
        // Covers a schema that already existed (e.g. created by an older HUB
        // version through ensureSchemaExists) and is still owned by HUB.
        jdbc.execute("ALTER SCHEMA " + quotedSchema + " OWNER TO " + quotedRole);
    }

    private void applyGrants(String schema, String role) {
        JdbcTemplate jdbc = new JdbcTemplate(bootstrapDataSource());
        String quotedSchema = quoteIdentifier(schema);
        String quotedRole = quoteIdentifier(role);

        jdbc.execute("REVOKE ALL ON SCHEMA " + quotedSchema + " FROM PUBLIC");
        jdbc.execute("GRANT ALL ON SCHEMA " + quotedSchema + " TO " + quotedRole);
        jdbc.execute("GRANT ALL ON ALL TABLES IN SCHEMA " + quotedSchema + " TO " + quotedRole);
        jdbc.execute("GRANT ALL ON ALL SEQUENCES IN SCHEMA " + quotedSchema + " TO " + quotedRole);
        jdbc.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA " + quotedSchema
                + " GRANT ALL ON TABLES TO " + quotedRole);
        jdbc.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA " + quotedSchema
                + " GRANT ALL ON SEQUENCES TO " + quotedRole);
        // So the plugin's own connections resolve unqualified table names to
        // its schema even when it forgets currentSchema.
        jdbc.execute("ALTER ROLE " + quotedRole + " SET search_path TO " + quotedSchema);
    }

    private boolean roleExists(JdbcTemplate jdbc, String role) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = ?)",
                Boolean.class, role);
        return Boolean.TRUE.equals(exists);
    }

    private boolean schemaExists(JdbcTemplate jdbc, String schema) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname = ?)",
                Boolean.class, schema);
        return Boolean.TRUE.equals(exists);
    }

    private boolean isProvisioned(PluginIdentity identity, Path pluginPath) {
        if (!credentialsFileExists(pluginPath, credentialsFileName(identity.id()))) {
            return false;
        }
        JdbcTemplate jdbc = new JdbcTemplate(bootstrapDataSource());
        return roleExists(jdbc, roleNameFor(identity.id())) && schemaExists(jdbc, identity.schema());
    }

    // ------------------------------------------------------------------
    // Credentials file
    // ------------------------------------------------------------------

    /**
     * Writes {@code <plugin_id>.properties} into the plugin jar's root, where
     * the plugin picks it up with {@code getResourceAsStream("/<id>.properties")}.
     * Exploded plugin directories get the file under {@code classes/} instead,
     * which is what PF4J puts on the plugin classpath.
     */
    private void writeCredentialsFile(Path pluginPath, PluginDbCredentials credentials) {
        String entry = credentialsFileName(credentials.pluginId());
        try {
            if (Files.isDirectory(pluginPath)) {
                Path target = credentialsPathInDirectory(pluginPath, entry);
                Files.createDirectories(target.getParent());
                Files.writeString(target, credentials.toFileContent(),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                restrictToOwner(target);
                return;
            }

            try (FileSystem jar = FileSystems.newFileSystem(pluginPath, Map.of())) {
                Files.writeString(jar.getPath("/" + entry), credentials.toFileContent(),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            }
            // The jar now carries a password, so it stops being world-readable.
            restrictToOwner(pluginPath);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to write " + entry + " into plugin " + pluginPath, e);
        }
    }

    private boolean credentialsFileExists(Path pluginPath, String entry) {
        try {
            if (Files.isDirectory(pluginPath)) {
                return Files.exists(credentialsPathInDirectory(pluginPath, entry));
            }
            try (FileSystem jar = FileSystems.newFileSystem(pluginPath, Map.of())) {
                return Files.exists(jar.getPath("/" + entry));
            }
        } catch (IOException e) {
            log.warn("Could not inspect {} for {}: {}", pluginPath, entry, e.getMessage());
            return false;
        }
    }

    private Path credentialsPathInDirectory(Path pluginDirectory, String entry) {
        Path classes = pluginDirectory.resolve("classes");
        return Files.isDirectory(classes) ? classes.resolve(entry) : pluginDirectory.resolve(entry);
    }

    private String credentialsFileName(String pluginId) {
        return pluginId + ".properties";
    }

    /**
     * Best-effort {@code chmod 600}. This is hygiene, not a security boundary —
     * PF4J plugins share HUB's process, so a hostile plugin has other ways in
     * (see the Security Model section of CLAUDE.md).
     */
    private void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (IOException | UnsupportedOperationException e) {
            log.debug("Could not restrict permissions on {}: {}", file, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Plugin identity
    // ------------------------------------------------------------------

    /**
     * Reads {@code Plugin-Id} and {@code Hub-Schema} straight from the jar
     * manifest, so provisioning can happen before the plugin class is loaded.
     */
    private PluginIdentity readIdentity(Path pluginPath) {
        if (Files.isDirectory(pluginPath)) {
            return readIdentity(pluginPath.resolve("classes/META-INF/MANIFEST.MF"), pluginPath);
        }
        try (JarFile jar = new JarFile(pluginPath.toFile())) {
            Manifest manifest = jar.getManifest();
            if (manifest == null) {
                throw new IllegalStateException("Plugin jar has no manifest: " + pluginPath);
            }
            return toIdentity(manifest.getMainAttributes(), pluginPath);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read plugin manifest from " + pluginPath, e);
        }
    }

    private PluginIdentity readIdentity(Path manifestFile, Path pluginPath) {
        try (var in = Files.newInputStream(manifestFile)) {
            return toIdentity(new Manifest(in).getMainAttributes(), pluginPath);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read plugin manifest from " + manifestFile, e);
        }
    }

    private PluginIdentity toIdentity(Attributes attributes, Path pluginPath) {
        String id = attributes.getValue(MANIFEST_PLUGIN_ID);
        String schema = attributes.getValue(MANIFEST_HUB_SCHEMA);

        if (id == null || id.isBlank()) {
            throw new IllegalStateException(
                    "Plugin " + pluginPath + " has no " + MANIFEST_PLUGIN_ID + " manifest entry");
        }
        if (schema == null || schema.isBlank()) {
            throw new IllegalStateException(
                    "Plugin " + pluginPath + " has no " + MANIFEST_HUB_SCHEMA + " manifest entry");
        }
        return new PluginIdentity(id.trim(), schema.trim());
    }

    private record PluginIdentity(String id, String schema) {
    }

    // ------------------------------------------------------------------
    // Connections
    // ------------------------------------------------------------------

    private DataSource createSchemaDataSource(String schema) {
        requireValidIdentifier(schema, "schema");
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
                .execute("CREATE SCHEMA IF NOT EXISTS " + quoteIdentifier(schema));
    }

    private void closePool(String schema) {
        DataSource pool = pluginDataSources.remove(schema);
        if (pool != null) {
            closeQuietly(pool);
        }
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

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The URL handed to the plugin, pinned to its own schema. */
    private String pluginJdbcUrl(String schema) {
        String url = properties.getUrl();
        int queryStart = url.indexOf('?');
        if (queryStart < 0) {
            return url + "?currentSchema=" + schema;
        }

        String base = url.substring(0, queryStart);
        String kept = java.util.Arrays.stream(url.substring(queryStart + 1).split("&"))
                .filter(param -> !param.isBlank() && !param.startsWith("currentSchema="))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");
        return kept.isEmpty()
                ? base + "?currentSchema=" + schema
                : base + "?" + kept + "&currentSchema=" + schema;
    }

    /** Database name out of {@code jdbc:postgresql://host:port/name?params}. */
    private String databaseName() {
        String url = properties.getUrl();
        int queryStart = url.indexOf('?');
        String withoutQuery = queryStart < 0 ? url : url.substring(0, queryStart);
        int lastSlash = withoutQuery.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == withoutQuery.length() - 1) {
            return null;
        }
        String name = withoutQuery.substring(lastSlash + 1);
        return VALID_IDENTIFIER.matcher(name).matches() ? name : null;
    }

    private String generatePassword() {
        byte[] bytes = new byte[PASSWORD_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void requireConfigured() {
        if (!properties.isConfigured()) {
            throw new IllegalStateException(
                    "PostgreSQL is not configured yet — cannot provision plugin databases.");
        }
    }

    private void requireValidIdentifier(String value, String what) {
        if (value == null || !VALID_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid plugin " + what + " name: " + value);
        }
    }

    private String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private String quoteLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
