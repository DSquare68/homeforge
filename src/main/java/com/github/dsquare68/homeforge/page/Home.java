package com.github.dsquare68.homeforge.page;

import java.util.List;

import com.github.dsquare68.homeforge.hubapi.DashboardApiImpl;
import com.github.dsquare68.homeforgeapi.dashboard.WidgetDescriptor;
import com.github.dsquare68.homeforgeapi.spi.HubApi;
import com.github.dsquare68.homeforgeapi.ui.BaseLayout;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

/**
 * HUB's own dashboard page. The navbar/drawer chrome comes from
 * {@link BaseLayout}'s constructor - shared with every plugin route - so
 * this class only has to add its own body content.
 */
@Route("home")
@PageTitle("Dashboard | HomeForge")
public class Home extends BaseLayout {

    private final DashboardApiImpl dashboardApi;

    public Home(HubApi hubApi, DashboardApiImpl dashboardApi) {
        super(hubApi);
        this.dashboardApi = dashboardApi;
        createContent();
    }

    // -------------------------------------------------------------------------
    // Content area
    // -------------------------------------------------------------------------

    private void createContent() {
        VerticalLayout content = new VerticalLayout();
        content.addClassName("hub-content");
        content.add(new H2("Dashboard"));
        content.add(new Paragraph("You are logged in."));
        content.add(buildWidgets());
        setContent(content);
    }

    private Component buildWidgets() {
        List<WidgetDescriptor> widgets = dashboardApi.widgets();
        if (widgets.isEmpty()) {
            return new Span();
        }

        HorizontalLayout row = new HorizontalLayout();
        row.addClassName("hub-dashboard-widgets");
        for (WidgetDescriptor widget : widgets) {
            row.add(widgetCard(widget));
        }
        return row;
    }

    private VerticalLayout widgetCard(WidgetDescriptor widget) {
        VerticalLayout card = new VerticalLayout();
        card.addClassName("hub-dashboard-widget");
        card.getStyle()
                .set("border", "1px solid var(--lumo-contrast-20pct)")
                .set("border-radius", "var(--lumo-border-radius-m)")
                .set("padding", "var(--lumo-space-m)");
        card.add(new Span(widget.title()));
        return card;
    }
}
