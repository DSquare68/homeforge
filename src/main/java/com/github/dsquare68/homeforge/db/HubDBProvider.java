package com.github.dsquare68.homeforge.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.github.dsquare68.homeforgeapi.db.TableApi;
import com.github.dsquare68.homeforgeapi.spi.PluginMetadata;

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
 * <p>Table definitions are <em>not</em> written here: HUB cannot know a plugin's
 * data model. The provisioned role owns its schema, so the plugin creates its
 * own tables — through {@code db().tables()} or its own Flyway migrations —
 * using the credentials written into its jar. What this class adds is the two
 * correctly-scoped entry points to the same {@link TableApi}:
 * {@link #hubTables()} for HUB's own schema and
 * {@link #tablesFor(PluginDbCredentials)} for a plugin's, each running as its
 * own PostgreSQL role.
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
    private static final int BOOTSTRAP_POOL_SIZE = 2;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DBConnection connections;
    private final Map<String, DataSource> pluginDataSources = new ConcurrentHashMap<>();
    private volatile DataSource bootstrapDataSource;

    public HubDBProvider(DBConnection connections) {
        this.connections = connections;
    }

    /** True once real PostgreSQL credentials are available for HUB itself. */
    public boolean isDatabaseConfigured() {
        return connections.isConnected();
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
        String role = roleNameFor(pluginId);
        requireValidIdentifier(schema, "schema");
        requireValidIdentifier(role, "role");

        String password = generatePassword();
        PluginDbCredentials credentials = new PluginDbCredentials(
                pluginId, schema, role, password,
                connections.url(schema), connections.getDrivers());

        // File first, database second. If the file cannot be written we stop
        // before touching PostgreSQL, so no role is left behind that nothing
        // can use. The reverse failure — file written, database statements fail
        // — leaves credentials that do not work yet, which the next startup
        // check spots (role missing) and re-provisions.
        writeCredentialsFile(pluginPath, credentials);

        createRole(role, password);
        createSchema(schema, role);
        applyGrants(schema, role);

        log.info("Provisioned plugin '{}': wrote {}.properties, role {} owns schema {}",
                pluginId, pluginId, role, schema);
        return credentials;
    }

    /**
     * Drops everything {@link #provision(String, String, Path)} created: the
     * plugin's objects, its schema and its login role. The credentials file
     * disappears with the jar, but is removed explicitly when the jar is kept.
     */
    public void deprovision(String pluginId, String schema) {
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

    /**
     * Reads back the {@code <plugin_id>.properties} HUB wrote into this plugin
     * when it was installed — the schema, role and password it actually runs
     * with. This is how the startup check learns about a plugin's schema
     * without loading the plugin or re-provisioning it.
     *
     * <p>Reading is safe at any time; only <em>writing</em> the file has to
     * happen before PF4J opens the jar.
     *
     * @return empty when the plugin has never been provisioned, or its file is
     *         unreadable / incomplete
     */
    public Optional<PluginDbCredentials> readCredentials(Path pluginPath) {
        String pluginId;
        try {
            pluginId = readIdentity(pluginPath).id();
        } catch (RuntimeException e) {
            log.debug("Not a readable plugin, skipping {}: {}", pluginPath, e.getMessage());
            return Optional.empty();
        }

        String entry = credentialsFileName(pluginId);
        try {
            Properties properties = new Properties();
            if (Files.isDirectory(pluginPath)) {
                Path file = credentialsPathInDirectory(pluginPath, entry);
                if (!Files.exists(file)) {
                    return Optional.empty();
                }
                try (InputStream in = Files.newInputStream(file)) {
                    properties.load(in);
                }
            } else {
                try (FileSystem jar = FileSystems.newFileSystem(pluginPath, Map.of())) {
                    Path file = jar.getPath("/" + entry);
                    if (!Files.exists(file)) {
                        return Optional.empty();
                    }
                    try (InputStream in = Files.newInputStream(file)) {
                        properties.load(in);
                    }
                }
            }
            return PluginDbCredentials.parse(pluginId, properties);
        } catch (IOException e) {
            log.warn("Could not read {} from {}: {}", entry, pluginPath, e.getMessage());
            return Optional.empty();
        }
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
    // Tables
    // ------------------------------------------------------------------

    /**
     * Creating and inspecting HUB's own tables, as the HUB master role inside
     * {@code hub_schema}.
     *
     * <pre>{@code
     * hubDb.hubTables().create(TableSpec.table("plugins")
     *         .column(Column.id())
     *         .column(Column.text("plugin_id").notNull().unique())
     *         .column(Column.timestamp("installed_at").notNull().defaultNow()));
     * }</pre>
     *
     * <p>Runs on the same privileged pool provisioning uses, so it can reach
     * every schema in the database. Keep it on the host side: handing this to a
     * plugin would create that plugin's tables owned by the master role and
     * undo the per-plugin ownership set up at install time — plugins call
     * {@code db().tables()}, which is the same API on their own role.
     */
    public TableApi hubTables() {
        return TableApi.on(bootstrapDataSource(), connections.getSchema());
    }

    /**
     * The same API against a plugin's schema, running as <em>that plugin's</em>
     * role rather than HUB's.
     *
     * <p>For the window where HUB acts on a plugin's behalf but the plugin's own
     * classes are not loaded yet — install-time setup driven from the host. The
     * tables it creates end up owned by the plugin role, exactly as if the
     * plugin had created them itself, so nothing is left behind that the plugin
     * cannot later alter or that survives {@link #deprovision(String, String)}.
     *
     * <p>The pool is cached per schema and closed on uninstall and shutdown.
     */
    public TableApi tablesFor(PluginDbCredentials credentials) {
        requireValidIdentifier(credentials.schema(), "schema");
        DataSource pool = pluginDataSources.computeIfAbsent(credentials.schema(),
                schema -> connections.pool(credentials.pluginId() + "-hub-pool", schema,
                        credentials.role(), credentials.password()));
        return TableApi.on(pool, credentials.schema());
    }

    // ------------------------------------------------------------------
    // Per-plugin DataSources
    // ------------------------------------------------------------------


    @PreDestroy
    public void shutdown() {
        pluginDataSources.values().forEach(connections::close);
        pluginDataSources.clear();
        if (bootstrapDataSource != null) {
            connections.close(bootstrapDataSource);
        }
    }

    // ------------------------------------------------------------------
    // SQL
    // ------------------------------------------------------------------

    private void createRole(String role, String password) {
        JdbcTemplate jdbc = new JdbcTemplate(bootstrapDataSource());
        String quotedRole = quoteIdentifier(role);
        String literalPassword = quoteLiteral(password);

        try {
            if (roleExists(jdbc, role)) {
                jdbc.execute("ALTER ROLE " + quotedRole + " WITH LOGIN PASSWORD " + literalPassword);
            } else {
                jdbc.execute("CREATE ROLE " + quotedRole + " LOGIN PASSWORD " + literalPassword);
                grantRoleToHub(jdbc, quotedRole);
            }
        } catch (DataAccessException e) {
            throw insufficientPrivilege(e)
                    ? new IllegalStateException("HUB's PostgreSQL user is not allowed to create roles. Grant it once:"
                            + " ALTER ROLE " + connections.getUsername() + " CREATEROLE;"
                            + " GRANT CREATE ON DATABASE " + connections.getDatabaseName()
                            + " TO " + connections.getUsername() + ";", e)
                    : e;
        }

        String database = databaseName();
        if (database != null) {
            jdbc.execute("GRANT CONNECT ON DATABASE " + quoteIdentifier(database) + " TO " + quotedRole);
        }
    }
    private String databaseName() {
        String name = connections.getDatabaseName();
        return name != null && VALID_IDENTIFIER.matcher(name).matches() ? name : null;
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

    /**
     * One small privileged pool shared by every provisioning statement. Cached
     * on purpose — a fresh Hikari pool per statement would leak connections.
     */
    private DataSource bootstrapDataSource() {
        DataSource existing = bootstrapDataSource;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (bootstrapDataSource == null) {
                bootstrapDataSource = connections.pool();
            }
            return bootstrapDataSource;
        }
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

    private void createSchemaDataSource(String schema) {
        requireValidIdentifier(schema, "schema");
        ensureSchemaExists(schema);
    }

    private void ensureSchemaExists(String schema) {
        new JdbcTemplate(bootstrapDataSource())
                .execute("CREATE SCHEMA IF NOT EXISTS " + quoteIdentifier(schema));
    }

    private void closePool(String schema) {
        DataSource pool = pluginDataSources.remove(schema);
        if (pool != null) {
            connections.close(pool);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String generatePassword() {
        byte[] bytes = new byte[PASSWORD_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void requireValidIdentifier(String value, String what) {
        if (value == null || !VALID_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid plugin " + what + " name: " + value);
        }
    }

    /**
     * PostgreSQL reports "permission denied to create role" with SQLState
     * 42501, which Spring lumps in with syntax errors as
     * {@code BadSqlGrammarException} — so the statement looks malformed when it
     * is really a missing privilege.
     */
    private boolean insufficientPrivilege(DataAccessException e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof SQLException sqlException && "42501".equals(sqlException.getSQLState())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private String quoteLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
