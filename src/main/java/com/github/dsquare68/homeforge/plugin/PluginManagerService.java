package com.github.dsquare68.homeforge.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.github.dsquare68.homeforgeapi.spi.HubPlugin;
import com.github.dsquare68.homeforgeapi.spi.PluginMetadata;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Service
public class PluginManagerService {

    private static final Logger log = LoggerFactory.getLogger(PluginManagerService.class);

    @Value("${hub.plugins.dir}")
    private String pluginsDir;

    private PluginManager pluginManager;

    @PostConstruct
    public void init() {
        Path dir = Path.of(pluginsDir);
        ensureDirectoryExists(dir);

        pluginManager = new DefaultPluginManager(dir);
        pluginManager.loadPlugins();
        pluginManager.startPlugins();

        log.info("Plugin system started. Loaded {} plugin(s) from {}", pluginManager.getPlugins().size(), dir);
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
