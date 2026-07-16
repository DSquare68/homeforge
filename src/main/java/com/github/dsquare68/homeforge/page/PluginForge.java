package com.github.dsquare68.homeforge.page;

import com.github.dsquare68.homeforge.component.HomeButton;
import com.github.dsquare68.homeforge.plugin.PluginManagerService;
import com.github.dsquare68.homeforgeapi.spi.HubPlugin;
import com.github.dsquare68.homeforgeapi.spi.PluginMetadata;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.StreamResource;

import jakarta.annotation.security.RolesAllowed;

import java.io.ByteArrayInputStream;
import java.util.List;

@RolesAllowed("USER")
@Route("plugins")
public class PluginForge extends VerticalLayout {

    public PluginForge(PluginManagerService pluginManagerService) {
        add(new HomeButton());
        add(new H2("Installed Plugins"));

        List<HubPlugin> plugins = pluginManagerService.getActivePluginInstances();

        if (plugins.isEmpty()) {
            add(new Paragraph("No plugins installed. Upload a plugin jar to get started."));
            return;
        }

        for (HubPlugin plugin : plugins) {
            add(buildPluginCard(plugin));
        }
    }

    private VerticalLayout buildPluginCard(HubPlugin plugin) {
        PluginMetadata meta = plugin.getMetadata();

        VerticalLayout card = new VerticalLayout();
        card.getStyle()
                .set("border", "1px solid var(--lumo-contrast-20pct)")
                .set("border-radius", "var(--lumo-border-radius-m)")
                .set("padding", "var(--lumo-space-m)");

        // Header row: icon + name + version badge
        HorizontalLayout header = new HorizontalLayout();
        header.setDefaultVerticalComponentAlignment(Alignment.CENTER);

        byte[] iconBytes = plugin.getIconBytes();
        if (iconBytes != null) {
            StreamResource res = new StreamResource("icon", () -> new ByteArrayInputStream(iconBytes));
            Image icon = new Image(res, meta.name());
            icon.setWidth("32px");
            icon.setHeight("32px");
            header.add(icon);
        }

        Span name = new Span(meta.name());
        name.getStyle().set("font-weight", "bold").set("font-size", "var(--lumo-font-size-l)");

        Span version = new Span("v" + meta.version());
        version.getStyle()
                .set("font-size", "var(--lumo-font-size-s)")
                .set("color", "var(--lumo-secondary-text-color)")
                .set("margin-left", "var(--lumo-space-s)");

        header.add(name, version);

        // Description
        Paragraph description = new Paragraph(meta.description());
        description.getStyle().set("margin", "0");

        // Path and schema badges
        HorizontalLayout metaRow = new HorizontalLayout();
        metaRow.add(badge("Path: " + meta.path()));
        metaRow.add(badge("Schema: " + meta.schema()));

        card.add(header, description, metaRow);
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
