package com.github.dsquare68.homeforge.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.github.dsquare68.homeforge.db.HubDBProvider;
import com.github.dsquare68.homeforgeapi.spi.HubPlugin;
import com.github.dsquare68.homeforgeapi.spi.PluginMetadata;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Service
public class PluginManagerService {

    private static final Logger log = LoggerFactory.getLogger(PluginManagerService.class);

    @Value("${hub.plugins.dir}")
    private String pluginsDir;

    private final HubDBProvider hubDb;

    private PluginManager pluginManager;

    public PluginManagerService(HubDBProvider hubDb) {
        this.hubDb = hubDb;
    }

    @PostConstruct
    public void init() {
        Path dir = Path.of(pluginsDir);
        ensureDirectoryExists(dir);

        provisionDatabases(dir);

        pluginManager = new DefaultPluginManager(dir);
        pluginManager.loadPlugins();
        pluginManager.startPlugins();

        log.info("Plugin system started. Loaded {} plugin(s) from {}", pluginManager.getPlugins().size(), dir);
    }

    /**
     * Gives every plugin in {@code dir} its PostgreSQL role, schema and
     * {@code <plugin_id>.properties} file before PF4J loads it — the file is
     * written into the plugin jar, which cannot be modified once the plugin
     * classloader holds it open.
     *
     * <p>Failures are per-plugin and non-fatal: a plugin that cannot be
     * provisioned is still loaded and will fail on its own terms, rather than
     * taking the whole plugin system down with it.
     */
    private void provisionDatabases(Path dir) {
        if (!hubDb.isDatabaseConfigured()) {
            log.info("Skipping plugin database provisioning — PostgreSQL is not configured yet.");
            return;
        }

        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(this::looksLikePlugin).forEach(this::provisionQuietly);
        } catch (IOException e) {
            log.warn("Could not scan {} for plugins to provision: {}", dir, e.getMessage());
        }
    }

    private boolean looksLikePlugin(Path path) {
        return Files.isDirectory(path) || path.getFileName().toString().endsWith(".jar");
    }

    private void provisionQuietly(Path pluginPath) {
        try {
            hubDb.provisionIfNeeded(pluginPath)
                    .ifPresent(credentials -> log.info("Wrote {}.properties into {}",
                            credentials.pluginId(), pluginPath.getFileName()));
        } catch (RuntimeException e) {
            log.error("Database provisioning failed for {}: {}", pluginPath.getFileName(), e.getMessage());
        }
    }

    /**
     * Returns metadata for every currently active plugin, sorted by display name.
     * Called by the UI on each page load — always reflects live plugin state.
     */
    public List<PluginMetadata> getActivePlugins() {
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
