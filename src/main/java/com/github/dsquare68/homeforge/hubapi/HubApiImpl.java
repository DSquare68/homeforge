package com.github.dsquare68.homeforge.hubapi;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.github.dsquare68.homeforge.plugin.PluginManagerService;
import com.github.dsquare68.homeforgeapi.dashboard.DashboardApi;
import com.github.dsquare68.homeforgeapi.notification.NotificationApi;
import com.github.dsquare68.homeforgeapi.spi.HubApi;
import com.github.dsquare68.homeforgeapi.spi.HubPlugin;
import com.github.dsquare68.homeforgeapi.user.UserApi;

/**
 * One shared instance handed to every plugin. {@link HubApi} carries no
 * plugin-specific state of its own — {@link NotificationApi#notifyUser} etc.
 * already take an explicit target id — so a single singleton bean is
 * correct rather than one instance per plugin.
 */
@Component
public class HubApiImpl implements HubApi {

    private final UserApi userApi;
    private final NotificationApi notificationApi;
    private final DashboardApi dashboardApi;

    /**
     * Resolved lazily: {@code PluginManagerService} depends (via
     * {@code PluginLifecycleCoordinator}) on this very bean, so a direct
     * constructor dependency would be circular.
     */
    private final ObjectProvider<PluginManagerService> pluginManagerService;

    public HubApiImpl(UserApi userApi, NotificationApi notificationApi,
            DashboardApi dashboardApi, ObjectProvider<PluginManagerService> pluginManagerService) {
        this.userApi = userApi;
        this.notificationApi = notificationApi;
        this.dashboardApi = dashboardApi;
        this.pluginManagerService = pluginManagerService;
    }

    @Override
    public UserApi user() {
        return userApi;
    }

    @Override
    public NotificationApi notifications() {
        return notificationApi;
    }

    @Override
    public DashboardApi dashboard() {
        return dashboardApi;
    }

    @Override
    public List<HubPlugin> activePlugins() {
        PluginManagerService service = pluginManagerService.getIfAvailable();
        return service != null ? service.getActivePluginInstances() : List.of();
    }
}
