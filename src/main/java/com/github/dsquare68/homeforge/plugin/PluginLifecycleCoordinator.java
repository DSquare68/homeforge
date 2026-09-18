package com.github.dsquare68.homeforge.plugin;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginStateEvent;
import org.pf4j.PluginStateListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.github.dsquare68.homeforgeapi.spi.HubApi;
import com.github.dsquare68.homeforgeapi.spi.HubPlugin;
import com.github.dsquare68.homeforgeapi.spi.PluginMetadata;

/**
 * Drives the HUB-specific parts of a plugin's lifecycle off PF4J's own state
 * transitions, so the same code path handles a plugin starting at HUB boot
 * and one started/stopped later at runtime (e.g. via
 * {@link PluginManagerService#activatePlugin(String)}):
 *
 * <ul>
 *   <li>{@code STARTED} — call {@code onInstall}/{@code onActivate}, then
 *       register the plugin's REST controllers, Vaadin routes, and
 *       {@code @Extension} classes (as Spring beans)</li>
 *   <li>{@code STOPPED}/{@code DISABLED} (from {@code STARTED}) — unregister
 *       all three, then call {@code onDeactivate}</li>
 * </ul>
 *
 * <p>Permanent removal ({@code onUninstall} plus dropping the plugin's
 * database role/schema) is a distinct, explicit action — see
 * {@link PluginManagerService#uninstallPlugin(String)} — not something this
 * listener infers from a generic state transition.
 */
@Component
public class PluginLifecycleCoordinator implements PluginStateListener {

    private static final Logger log = LoggerFactory.getLogger(PluginLifecycleCoordinator.class);

    private final HubApi hubApi;
    private final PluginControllerRegistrar controllerRegistrar;
    private final PluginRouteRegistrar routeRegistrar;
    private final PluginExtensionRegistrar extensionRegistrar;

    /** Plugin ids provisioned for the first time this run - see {@link #markFreshlyInstalled}. */
    private final Set<String> freshlyInstalledPluginIds = ConcurrentHashMap.newKeySet();

    public PluginLifecycleCoordinator(HubApi hubApi, PluginControllerRegistrar controllerRegistrar,
            PluginRouteRegistrar routeRegistrar, PluginExtensionRegistrar extensionRegistrar) {
        this.hubApi = hubApi;
        this.controllerRegistrar = controllerRegistrar;
        this.routeRegistrar = routeRegistrar;
        this.extensionRegistrar = extensionRegistrar;
    }

    /**
     * Marks the given plugin ids as needing {@code onInstall} the next time
     * each one reaches {@code STARTED}. Called by {@link PluginManagerService}
     * right after it provisions a plugin for the first time, before PF4J
     * loads/starts anything.
     */
    public void markFreshlyInstalled(Collection<String> pluginIds) {
        freshlyInstalledPluginIds.addAll(pluginIds);
    }

    @Override
    public void pluginStateChanged(PluginStateEvent event) {
        String pluginId = event.getPlugin().getPluginId();
        PluginState newState = event.getPluginState();
        PluginState oldState = event.getOldState();

        if (newState == PluginState.STARTED) {
            handleStarted(event.getSource(), pluginId);
        } else if ((newState == PluginState.STOPPED || newState == PluginState.DISABLED)
                && oldState == PluginState.STARTED) {
            handleStopped(event.getSource(), pluginId);
        }
    }

    private void handleStarted(PluginManager manager, String pluginId) {
        List<HubPlugin> plugins = manager.getExtensions(HubPlugin.class, pluginId);
        boolean freshInstall = freshlyInstalledPluginIds.remove(pluginId);

        for (HubPlugin plugin : plugins) {
            try {
                if (freshInstall) {
                    plugin.onInstall(hubApi);
                }
                plugin.onActivate(hubApi);

                PluginMetadata meta = plugin.getMetadata();
                controllerRegistrar.register(pluginId, plugin.restControllers());
                routeRegistrar.register(pluginId, meta.path(), plugin.routes());
                extensionRegistrar.register(pluginId, manager);
            } catch (RuntimeException e) {
                log.error("Failed to activate plugin '{}': {}", pluginId, e.getMessage(), e);
            }
        }
    }

    private void handleStopped(PluginManager manager, String pluginId) {
        controllerRegistrar.unregister(pluginId);
        routeRegistrar.unregister(pluginId);
        extensionRegistrar.unregister(pluginId, manager);

        for (HubPlugin plugin : manager.getExtensions(HubPlugin.class, pluginId)) {
            try {
                plugin.onDeactivate();
            } catch (RuntimeException e) {
                log.error("Plugin '{}' failed during onDeactivate: {}", pluginId, e.getMessage(), e);
            }
        }
    }
}
