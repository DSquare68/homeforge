package com.github.dsquare68.homeforge.hubapi;

import org.springframework.stereotype.Component;

import com.github.dsquare68.homeforgeapi.dashboard.DashboardApi;
import com.github.dsquare68.homeforgeapi.notification.NotificationApi;
import com.github.dsquare68.homeforgeapi.spi.HubApi;
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

    public HubApiImpl(UserApi userApi, NotificationApi notificationApi,
            DashboardApi dashboardApi) {
        this.userApi = userApi;
        this.notificationApi = notificationApi;
        this.dashboardApi = dashboardApi;
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
}
