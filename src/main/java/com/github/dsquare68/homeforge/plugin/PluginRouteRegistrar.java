package com.github.dsquare68.homeforge.plugin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.github.dsquare68.homeforgeapi.spi.PluginRoute;
import com.github.dsquare68.homeforgeapi.ui.BaseLayout;
import com.vaadin.flow.router.RouteConfiguration;
import com.vaadin.flow.server.VaadinServletContext;
import com.vaadin.flow.server.startup.ApplicationRouteRegistry;

import jakarta.servlet.ServletContext;

/**
 * Registers plugin-contributed Vaadin routes (see
 * {@link com.github.dsquare68.homeforgeapi.spi.HubPlugin#routes()}) onto the
 * application-scoped Vaadin route registry, nested under HUB's shared
 * {@link BaseLayout} chrome, with every path forcibly rewritten under this
 * plugin's own {@code PluginMetadata#path()} regardless of what
 * {@link PluginRoute#path()} says relative to it.
 *
 * <p>Plugins start during {@code ApplicationReadyEvent}, before any HTTP
 * request has bound a {@code VaadinService} to the current thread, so
 * {@code RouteConfiguration.forApplicationScope()} (which needs
 * {@code VaadinService.getCurrent()}) is not usable here — it works during a
 * request, but throws a NullPointerException at boot. Reaching the same
 * application-scoped registry through the servlet context directly works at
 * any time, boot included.
 */
@Component
public class PluginRouteRegistrar {

    private static final Logger log = LoggerFactory.getLogger(PluginRouteRegistrar.class);

    private final ServletContext servletContext;

    /** Every full path this registrar added, keyed by plugin id, for symmetric teardown. */
    private final Map<String, List<String>> registeredByPlugin = new ConcurrentHashMap<>();

    public PluginRouteRegistrar(ServletContext servletContext) {
        this.servletContext = servletContext;
    }

    /**
     * Registers every route under {@code <pluginBasePath>[/<route.path()>]}.
     * Safe to call with an empty list.
     */
    public void register(String pluginId, String pluginBasePath, List<PluginRoute> routes) {
        if (routes.isEmpty()) {
            return;
        }
        String base = stripLeadingSlash(pluginBasePath);
        List<String> registered = registeredByPlugin.computeIfAbsent(pluginId, id -> new CopyOnWriteArrayList<>());
        RouteConfiguration configuration = RouteConfiguration.forRegistry(applicationRegistry());

        for (PluginRoute route : routes) {
            String fullPath = fullPath(base, route.path());
            configuration.setRoute(fullPath, route.view(), BaseLayout.class);
            registered.add(fullPath);
            log.info("Registered plugin '{}' route {} -> {}", pluginId, fullPath, route.view().getSimpleName());
        }
    }

    /** Removes every route {@link #register} added for this plugin. */
    public void unregister(String pluginId) {
        List<String> registered = registeredByPlugin.remove(pluginId);
        if (registered == null) {
            return;
        }
        RouteConfiguration configuration = RouteConfiguration.forRegistry(applicationRegistry());
        for (String path : registered) {
            configuration.removeRoute(path);
        }
        log.info("Unregistered {} route(s) for plugin '{}'", registered.size(), pluginId);
    }

    private ApplicationRouteRegistry applicationRegistry() {
        return ApplicationRouteRegistry.getInstance(new VaadinServletContext(servletContext));
    }

    /** Package-private (rather than private) so it can be unit-tested with no live Vaadin service. */
    static String fullPath(String base, String relative) {
        if (relative == null || relative.isBlank()) {
            return base;
        }
        return base + "/" + stripLeadingSlash(relative);
    }

    static String stripLeadingSlash(String path) {
        return path != null && path.startsWith("/") ? path.substring(1) : path;
    }
}
