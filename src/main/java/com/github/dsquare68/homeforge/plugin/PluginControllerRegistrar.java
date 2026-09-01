package com.github.dsquare68.homeforge.plugin;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodIntrospector;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Registers plugin-contributed REST controllers (see
 * {@link com.github.dsquare68.homeforgeapi.spi.HubPlugin#restControllers()})
 * onto HUB's own {@link RequestMappingHandlerMapping}, with every method's
 * path forcibly rewritten under {@code /api/plugins/{pluginId}} regardless
 * of what the plugin's own {@code @RequestMapping} annotations declare.
 *
 * <p>Controller instances are plain objects, not Spring beans — plugins have
 * no per-plugin Spring context (see the SPI Javadoc for why) — so mappings
 * are built by asking {@link RequestMappingHandlerMapping} itself what it
 * would have produced for each method, the same way Spring's own component
 * scan does internally, then combining that with the plugin's namespace
 * prefix and registering it directly.
 */
@Component
public class PluginControllerRegistrar {

    private static final Logger log = LoggerFactory.getLogger(PluginControllerRegistrar.class);

    private final RequestMappingHandlerMapping handlerMapping;
    private final Method getMappingForMethod;

    /** Every mapping this registrar added, keyed by plugin id, for symmetric teardown. */
    private final Map<String, List<RequestMappingInfo>> registeredByPlugin = new ConcurrentHashMap<>();

    public PluginControllerRegistrar(RequestMappingHandlerMapping handlerMapping) {
        this.handlerMapping = handlerMapping;
        // getMappingForMethod is protected - there is no public API for building
        // the RequestMappingInfo Spring would have produced for an annotated
        // method, which we need before we can prefix it with the plugin's namespace.
        this.getMappingForMethod = ReflectionUtils.findMethod(
                RequestMappingHandlerMapping.class, "getMappingForMethod", Method.class, Class.class);
        ReflectionUtils.makeAccessible(getMappingForMethod);
    }

    /**
     * Registers every handler method on every given controller under
     * {@code /api/plugins/{pluginId}/...}. Safe to call with an empty list.
     */
    public void register(String pluginId, List<Object> controllers) {
        if (controllers.isEmpty()) {
            return;
        }
        String prefix = "/api/plugins/" + pluginId;
        List<RequestMappingInfo> registered = registeredByPlugin.computeIfAbsent(
                pluginId, id -> new CopyOnWriteArrayList<>());

        for (Object controller : controllers) {
            Class<?> userType = ClassUtils.getUserClass(controller);
            Map<Method, RequestMappingInfo> methods = MethodIntrospector.selectMethods(userType,
                    (MethodIntrospector.MetadataLookup<RequestMappingInfo>) method -> mappingFor(method, userType));

            methods.forEach((method, info) -> {
                RequestMappingInfo prefixed = RequestMappingInfo.paths(prefix).build().combine(info);
                handlerMapping.registerMapping(prefixed, controller, method);
                registered.add(prefixed);
                log.info("Registered plugin '{}' controller method {}#{} at {}",
                        pluginId, userType.getSimpleName(), method.getName(), prefixed);
            });
        }
    }

    /** Removes every mapping {@link #register} added for this plugin. */
    public void unregister(String pluginId) {
        List<RequestMappingInfo> registered = registeredByPlugin.remove(pluginId);
        if (registered == null) {
            return;
        }
        for (RequestMappingInfo info : registered) {
            handlerMapping.unregisterMapping(info);
        }
        log.info("Unregistered {} controller mapping(s) for plugin '{}'", registered.size(), pluginId);
    }

    private RequestMappingInfo mappingFor(Method method, Class<?> userType) {
        return (RequestMappingInfo) ReflectionUtils.invokeMethod(getMappingForMethod, handlerMapping, method, userType);
    }
}
