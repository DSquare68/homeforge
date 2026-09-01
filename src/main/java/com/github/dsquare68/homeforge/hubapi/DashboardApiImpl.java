package com.github.dsquare68.homeforge.hubapi;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.github.dsquare68.homeforgeapi.dashboard.DashboardApi;
import com.github.dsquare68.homeforgeapi.dashboard.WidgetDescriptor;

/**
 * In-memory registry backing {@link DashboardApi}. Widgets live only for the
 * current process — a plugin re-registers its widgets every
 * {@code onActivate}, so nothing needs to survive a HUB restart here.
 */
@Component
public class DashboardApiImpl implements DashboardApi {

    private final Map<String, WidgetDescriptor> widgets = new ConcurrentHashMap<>();

    @Override
    public void registerWidget(WidgetDescriptor descriptor) {
        widgets.put(descriptor.id(), descriptor);
    }

    @Override
    public void unregisterWidget(String widgetId) {
        widgets.remove(widgetId);
    }

    /** Registered widgets, sorted for display order (lower order first). */
    public List<WidgetDescriptor> widgets() {
        return widgets.values().stream()
                .sorted(Comparator.comparingInt(WidgetDescriptor::order))
                .toList();
    }
}
