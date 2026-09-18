package com.github.dsquare68.homeforge.plugin;

import java.nio.file.Path;

import org.pf4j.ExtensionFactory;
import org.pf4j.spring.SingletonSpringExtensionFactory;
import org.pf4j.spring.SpringPluginManager;

/**
 * HUB's own {@link SpringPluginManager}. Identical wiring, except extension
 * instances (chiefly each plugin's
 * {@link com.github.dsquare68.homeforgeapi.spi.HubPlugin} implementation) are
 * cached per extension class instead of being rebuilt on every
 * {@link #getExtensions} call, which is pf4j's default.
 *
 * <p>Without this, {@link PluginLifecycleCoordinator} — which independently
 * calls {@code getExtensions(HubPlugin.class, pluginId)} on start, on stop
 * and on uninstall — and {@link PluginExtensionRegistrar}'s use of
 * {@link org.pf4j.spring.ExtensionsInjector} would each end up holding a
 * different object for the same plugin: {@code onActivate} firing on one
 * instance while the Spring bean (and later {@code onDeactivate}) is a
 * completely different one.
 *
 * <p>The cache lives inside the {@link SingletonSpringExtensionFactory} for
 * as long as this manager does, keyed by extension class <em>name</em>, not
 * by {@code Class} object. Uninstalling and reinstalling a plugin under the
 * same id without restarting HUB would therefore still hand back the old,
 * pre-uninstall instance (from the discarded classloader) instead of a fresh
 * one — an accepted Phase 1 edge case, not something this class tries to
 * solve; restarting HUB after such a reinstall avoids it.
 */
class HubPluginManager extends SpringPluginManager {

    HubPluginManager(Path pluginsRoot) {
        super(pluginsRoot);
    }

    @Override
    protected ExtensionFactory createExtensionFactory() {
        return new SingletonSpringExtensionFactory(this);
    }
}
