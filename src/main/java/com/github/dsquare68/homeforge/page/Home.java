package com.github.dsquare68.homeforge.page;

import java.util.List;

import com.github.dsquare68.homeforge.plugin.PluginManagerService;
import com.github.dsquare68.homeforge.ui.Icons;
import com.github.dsquare68.homeforgeapi.spi.PluginMetadata;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;

import jakarta.annotation.security.PermitAll;

@Route("home")
@PageTitle("Dashboard | HomeForge")
@PermitAll
public class Home extends AppLayout {

    public Home(PluginManagerService pluginService) {
        createNavbar();
        createSidebar(pluginService.getActivePlugins());
        createContent();
    }

    // -------------------------------------------------------------------------
    // Navbar — core app navigation
    // -------------------------------------------------------------------------

    private void createNavbar() {
        DrawerToggle toggle = new DrawerToggle();

        H1 title = new H1("HomeForge");
        title.addClassName("navbar-title");

        HorizontalLayout navLinks = buildNavLinks();
        navLinks.addClassName("hub-nav-links");

        HorizontalLayout navbar = new HorizontalLayout(toggle, title, navLinks);
        navbar.setAlignItems(FlexComponent.Alignment.CENTER);
        navbar.setFlexGrow(1, navLinks);
        navbar.addClassName("hub-navbar");

        addToNavbar(navbar);
    }

    private HorizontalLayout buildNavLinks() {
        HorizontalLayout links = new HorizontalLayout();
        links.setSpacing(false);
        links.setAlignItems(FlexComponent.Alignment.CENTER);

        links.add(navLink("Dashboard", Home.class,        VaadinIcon.DASHBOARD));
        links.add(navLink("Plugins",   PluginForge.class, VaadinIcon.PUZZLE_PIECE));

        return links;
    }

    private RouterLink navLink(String label, Class<? extends Component> target, VaadinIcon icon) {
        RouterLink link = new RouterLink();
        link.setRoute(target);
        link.addClassName("hub-nav-link");

        HorizontalLayout content = new HorizontalLayout(icon.create(), new Span(label));
        content.setAlignItems(FlexComponent.Alignment.CENTER);
        content.setSpacing(true);
        link.add(content);

        return link;
    }

    // -------------------------------------------------------------------------
    // Sidebar — installed plugin navigation
    // -------------------------------------------------------------------------

    private void createSidebar(List<PluginMetadata> plugins) {
        Span header = new Span("Installed Plugins");
        header.addClassName("hub-sidebar-header");

        SideNav pluginNav = buildPluginNav(plugins);

        VerticalLayout drawerContent = new VerticalLayout(header, pluginNav);
        drawerContent.addClassName("hub-drawer");
        drawerContent.setPadding(false);
        drawerContent.setSpacing(false);

        addToDrawer(drawerContent);
    }

    private SideNav buildPluginNav(List<PluginMetadata> plugins) {
        SideNav nav = new SideNav();
        nav.addClassName("hub-sidenav");

        if (plugins.isEmpty()) {
            SideNavItem empty = new SideNavItem("No plugins installed");
            empty.addClassName("hub-sidebar-empty");
            nav.addItem(empty);
            return nav;
        }

        for (PluginMetadata plugin : plugins) {
            nav.addItem(pluginItem(plugin));
        }

        return nav;
    }

    private SideNavItem pluginItem(PluginMetadata plugin) {
        String path = stripLeadingSlash(plugin.path());
        SideNavItem item = new SideNavItem(plugin.name(), path);

        Component icon = plugin.icon() != null
                ? Icons.from(plugin.icon(), plugin.name())
                : Icons.plugins();
        item.setPrefixComponent(icon);
        item.addClassName("hub-sidenav-item");

        return item;
    }

    // -------------------------------------------------------------------------
    // Content area
    // -------------------------------------------------------------------------

    private void createContent() {
        VerticalLayout content = new VerticalLayout();
        content.addClassName("hub-content");
        content.add(new H2("Dashboard"));
        content.add(new Paragraph("You are logged in."));
        setContent(content);
    }

    // -------------------------------------------------------------------------

    private static String stripLeadingSlash(String path) {
        return path != null && path.startsWith("/") ? path.substring(1) : path;
    }
}
