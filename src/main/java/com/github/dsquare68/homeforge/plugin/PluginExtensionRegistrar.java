package com.github.dsquare68.homeforge.plugin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.pf4j.PluginManager;
import org.pf4j.spring.ExtensionsInjector;
import org.pf4j.spring.SpringPluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory;
import org.springframework.stereotype.Component;

/**
 * Registers each plugin's {@code @Extension}-annotated classes — chiefly its
 * {@link com.github.dsquare68.homeforgeapi.spi.HubPlugin} implementation —
 * as singleton beans in HUB's own {@code ApplicationContext}, via
 * pf4j-spring's {@link ExtensionsInjector}.
 *
 * <p>The plugin manager is a {@link SpringPluginManager}, but the plugin
 * itself is a plain PF4J {@code Plugin}, not a {@code SpringPlugin} — it has
 * no application context of its own (see the SPI Javadoc for why). That
 * makes {@link org.pf4j.spring.SpringExtensionFactory} fall back to
 * autowiring every extension instance from <em>HUB's</em> application
 * context — which is what lets a plugin's {@code HubPlugin} implementation
 * declare {@code @Autowired} constructor parameters or fields (of types HUB
 * itself exposes as beans, e.g. {@code HubApi}) instead of only receiving
 * them through {@code onActivate(HubApi)}.
 *
 * <p>Objects returned from {@code HubPlugin#restControllers()} are untouched
 * by this — they aren't extension points, so pf4j-spring never sees them;
 * plugins still build and wire those by hand.
 */
@Component
public class PluginExtensionRegistrar {

    private static final Logger log = LoggerFactory.getLogger(PluginExtensionRegistrar.class);

    /** Bean names (== extension class names) this registrar added, keyed by plugin id. */
    private final Map<String, List<String>> registeredByPlugin = new ConcurrentHashMap<>();

    /**
     * Registers every {@code @Extension} class of this plugin as a Spring
     * singleton bean. A no-op if {@code manager} isn't a
     * {@link SpringPluginManager}.
     *
     * <p>{@link ExtensionsInjector#injectExtensions()} scans every started
     * plugin, not just this one, but it skips any extension class that
     * already has a bean — so calling it again here for a plugin that is
     * already registered costs a scan, not a duplicate.
     */
    public void register(String pluginId, PluginManager manager) {
        if (!(manager instanceof SpringPluginManager springManager)) {
            return;
        }

        AbstractAutowireCapableBeanFactory beanFactory = (AbstractAutowireCapableBeanFactory)
                springManager.getApplicationContext().getAutowireCapableBeanFactory();
        new ExtensionsInjector(springManager, beanFactory).injectExtensions();

        List<String> extensionClassNames = List.copyOf(springManager.getExtensionClassNames(pluginId));
        registeredByPlugin.put(pluginId, extensionClassNames);
        if (!extensionClassNames.isEmpty()) {
            log.info("Registered plugin '{}' extension(s) {} as Spring bean(s)", pluginId, extensionClassNames);
        }
    }

    /** Removes every bean {@link #register} added for this plugin. */
    public void unregister(String pluginId, PluginManager manager) {
        List<String> registered = registeredByPlugin.remove(pluginId);
        if (registered == null || registered.isEmpty() || !(manager instanceof SpringPluginManager springManager)) {
            return;
        }

        AbstractAutowireCapableBeanFactory beanFactory = (AbstractAutowireCapableBeanFactory)
                springManager.getApplicationContext().getAutowireCapableBeanFactory();
        for (String beanName : registered) {
            beanFactory.destroySingleton(beanName);
        }
        log.info("Unregistered {} extension bean(s) for plugin '{}'", registered.size(), pluginId);
    }
}
