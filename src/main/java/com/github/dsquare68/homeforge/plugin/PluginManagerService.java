package com.github.dsquare68.homeforge.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import com.github.dsquare68.homeforge.component.StartupCheck;
import com.github.dsquare68.homeforge.db.HubDBProvider;
import com.github.dsquare68.homeforge.db.PluginDbCredentials;
import com.github.dsquare68.homeforgeapi.spi.HubPlugin;
import com.github.dsquare68.homeforgeapi.spi.PluginMetadata;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Service
public class PluginManagerService {

    private static final Logger log = LoggerFactory.getLogger(PluginManagerService.class);

    @Value("${hub.plugins.dir}")
    private String pluginsDir;

    /**
     * Runs on {@link ApplicationReadyEvent} after {@code StartupCheck}, which
     * uses {@link Ordered#HIGHEST_PRECEDENCE}.
     */
    public static final int STARTUP_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    private final HubDBProvider hubDb;

    /**
     * Resolved lazily to break the cycle with {@code StartupCheck}, which needs
     * this service to know which plugins to check.
     */
    private final ObjectProvider<StartupCheck> startupCheck;

    /** Every installed plugin's database credentials, keyed by plugin id, in install order. */
    private final Map<String, PluginDbCredentials> installedPlugins = new LinkedHashMap<>();

    private PluginManager pluginManager;

    public PluginManagerService(HubDBProvider hubDb, ObjectProvider<StartupCheck> startupCheck) {
        this.hubDb = hubDb;
        this.startupCheck = startupCheck;
    }

    /**
     * Reads what is already installed, without touching PostgreSQL or loading
     * anything: the startup check needs this list, and it runs first.
     */
    @PostConstruct
    public void init() {
        Path dir = Path.of(pluginsDir);
        ensureDirectoryExists(dir);
        scanInstalledPlugins(dir);
    }

    /**
     * The rest of plugin startup, once {@code StartupCheck} has reported on the
     * database:
     *
     * <ol>
     *   <li>provision every plugin that is not added yet — no credentials file,
     *       or no role/schema in PostgreSQL</li>
     *   <li>load and start the plugins</li>
     *   <li>re-run the check so its status covers the plugins just added</li>
     * </ol>
     *
     * <p>Provisioning has to finish before PF4J loads anything: the credentials
     * file is written <em>into the plugin jar</em>, which cannot be modified
     * once the plugin classloader holds it open.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(STARTUP_ORDER)
    public void provisionAndStartPlugins() {
        Path dir = Path.of(pluginsDir);

        provisionMissingPlugins(dir);

        pluginManager = new DefaultPluginManager(dir);
        pluginManager.loadPlugins();
        pluginManager.startPlugins();

        log.info("Plugin system started. Loaded {} plugin(s) from {}", pluginManager.getPlugins().size(), dir);

        startupCheck.ifAvailable(StartupCheck::recheck);
    }

    /**
     * Provisions the plugins that are not added yet, leaving the rest alone.
     *
     * <p>"Not added" means HUB cannot see a working setup for the jar: no
     * {@code <plugin_id>.properties} inside it, or no matching PostgreSQL role
     * and schema. Each one gets its credentials file written first, then its
     * role, schema and grants created — so a jar whose database work failed is
     * picked up and retried on the next start rather than silently skipped.
     */
    private void provisionMissingPlugins(Path dir) {
        if (!hubDb.isDatabaseConfigured()) {
            log.info("Skipping plugin database provisioning — PostgreSQL is not configured yet.");
            return;
        }

        List<String> added = new ArrayList<>();
        for (Path pluginPath : pluginPaths(dir)) {
            try {
                hubDb.provisionIfNeeded(pluginPath).ifPresent(credentials -> {
                    installedPlugins.put(credentials.pluginId(), credentials);
                    added.add(credentials.pluginId());
                });
            } catch (RuntimeException e) {
                // Spring wraps the driver's exception; the useful text (e.g.
                // "permission denied to create role") is on the root cause.
                log.error("Database provisioning failed for {}: {}", pluginPath.getFileName(),
                        NestedExceptionUtils.getMostSpecificCause(e).getMessage());
            }
        }

        if (added.isEmpty()) {
            log.info("No new plugins to provision; {} already installed: {}",
                    installedPlugins.size(), installedPlugins.keySet());
        } else {
            log.info("Added {} new plugin(s): {}", added.size(), added);
        }
    }

    /** Reads the credentials file of every plugin that already has one. */
    private void scanInstalledPlugins(Path dir) {
        installedPlugins.clear();
        for (Path pluginPath : pluginPaths(dir)) {
            hubDb.readCredentials(pluginPath)
                    .ifPresent(credentials -> installedPlugins.put(credentials.pluginId(), credentials));
        }
        log.info("Found {} plugin(s) in {}, {} already provisioned: {}",
                pluginPaths(dir).size(), dir, installedPlugins.size(), installedPlugins.keySet());
    }

    private List<Path> pluginPaths(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(this::looksLikePlugin).sorted().toList();
        } catch (IOException e) {
            log.warn("Could not scan {} for plugins: {}", dir, e.getMessage());
            return List.of();
        }
    }

    private boolean looksLikePlugin(Path path) {
        return Files.isDirectory(path) || path.getFileName().toString().endsWith(".jar");
    }

    /**
     * The database credentials of every installed plugin, as written into each
     * plugin jar at install time. Used by {@code StartupCheck} to verify every
     * plugin schema, and safe to call before the plugins themselves start.
     */
    public List<PluginDbCredentials> installedPlugins() {
        return List.copyOf(installedPlugins.values());
    }

    /**
     * Rescans the plugins directory and provisions anything not added yet.
     * Call this after dropping a new plugin jar in — before it is loaded.
     */
    public List<PluginDbCredentials> refreshInstalledPlugins() {
        Path dir = Path.of(pluginsDir);
        scanInstalledPlugins(dir);
        provisionMissingPlugins(dir);
        return installedPlugins();
    }

    /**
     * Returns metadata for every currently active plugin, sorted by display name.
     * Called by the UI on each page load — always reflects live plugin state.
     */
    public List<PluginMetadata> getActivePlugins() {
        if (pluginManager == null) {
            return List.of();
        }
        return pluginManager.getExtensions(HubPlugin.class)
                .stream()
                .map(plugin -> plugin.getMetadata())
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
    }

    /**
     * Returns active plugin instances sorted by display name.
     * Use when both metadata and icon bytes are needed (e.g. sidebar, plugin forge).
     */
    public List<HubPlugin> getActivePluginInstances() {
        if (pluginManager == null) {
            return List.of();
        }
        return pluginManager.getExtensions(HubPlugin.class)
                .stream()
                .sorted((a, b) -> a.getMetadata().name().compareToIgnoreCase(b.getMetadata().name()))
                .toList();
    }

    /**
     * Returns the underlying PF4J manager for advanced operations
     * (install, uninstall, enable/disable) used by the plugin management page.
     */
    public PluginManager raw() {
        return pluginManager;
    }

    @PreDestroy
    public void shutdown() {
        if (pluginManager != null) {
            pluginManager.stopPlugins();
            log.info("Plugin system stopped.");
        }
    }

    private void ensureDirectoryExists(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create plugin directory: " + dir, e);
        }
    }
}
