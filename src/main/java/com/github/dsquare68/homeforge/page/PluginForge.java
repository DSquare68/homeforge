package com.github.dsquare68.homeforge.page;

import com.github.dsquare68.homeforge.plugin.PluginManagerService;
import com.github.dsquare68.homeforgeapi.spi.PluginMetadata;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;

import jakarta.annotation.security.RolesAllowed;

import java.util.List;

@RolesAllowed("USER")
@Route("plugins")
public class PluginForge extends VerticalLayout {

    public PluginForge(PluginManagerService pluginManagerService) {
        add(new H2("Installed Plugins"));

        List<PluginMetadata> plugins = pluginManagerService.getActivePlugins();

        if (plugins.isEmpty()) {
            add(new Paragraph("No plugins installed. Upload a plugin jar to get started."));
            return;
        }

        for (PluginMetadata plugin : plugins) {
            add(buildPluginCard(plugin));
        }
    }

    private VerticalLayout buildPluginCard(PluginMetadata plugin) {
        VerticalLayout card = new VerticalLayout();
        card.getStyle()
                .set("border", "1px solid var(--lumo-contrast-20pct)")
                .set("border-radius", "var(--lumo-border-radius-m)")
                .set("padding", "var(--lumo-space-m)");

        // Header row: icon + name + version badge
        HorizontalLayout header = new HorizontalLayout();
        header.setDefaultVerticalComponentAlignment(Alignment.CENTER);

        if (plugin.icon() != null) {
            try {
                header.add(VaadinIcon.valueOf(
                        plugin.icon().replace("vaadin:", "").replace("-", "_").toUpperCase()
                ).create());
            } catch (IllegalArgumentException ignored) {
                // Unknown icon name — skip silently
            }
        }

        Span name = new Span(plugin.name());
        name.getStyle().set("font-weight", "bold").set("font-size", "var(--lumo-font-size-l)");

        Span version = new Span("v" + plugin.version());
        version.getStyle()
                .set("font-size", "var(--lumo-font-size-s)")
                .set("color", "var(--lumo-secondary-text-color)")
                .set("margin-left", "var(--lumo-space-s)");

        header.add(name, version);

        // Description
        Paragraph description = new Paragraph(plugin.description());
        description.getStyle().set("margin", "0");

        // Path and schema badges
        HorizontalLayout meta = new HorizontalLayout();
        meta.add(badge("Path: " + plugin.path()));
        meta.add(badge("Schema: " + plugin.schema()));

        card.add(header, description, meta);
        return card;
    }

    private Span badge(String text) {
        Span badge = new Span(text);
        badge.getStyle()
                .set("background", "var(--lumo-contrast-10pct)")
                .set("border-radius", "var(--lumo-border-radius-s)")
                .set("font-size", "var(--lumo-font-size-xs)")
                .set("padding", "2px 6px");
        return badge;
    }
}
