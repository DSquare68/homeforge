package com.github.dsquare68.homeforge.component;

import com.github.dsquare68.homeforge.page.Home;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;

/**
 * Reusable "back to Home" chip for pages that live outside the main
 * dashboard {@link com.vaadin.flow.component.applayout.AppLayout} navbar
 * (e.g. {@link com.github.dsquare68.homeforge.page.Profile}). Drop it on
 * any standalone page so users always have a way back to the dashboard.
 */
public class HomeButton extends Composite<Button> {

    // Hand-drawn to match the stroke style of the bundled icon set
    // (src/main/resources/META-INF/resources/icons) rather than pulling an
    // icon font/CDN asset into a self-hosted app.
    private static final String ICON_SVG = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                 stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M4 11 12 4l8 7"/>
              <path d="M6 10v9a1 1 0 0 0 1 1h10a1 1 0 0 0 1-1v-9"/>
              <rect x="9" y="13" width="6" height="2.4" rx="0.4"/>
              <rect x="9" y="16.6" width="6" height="2.4" rx="0.4"/>
              <circle cx="10.2" cy="14.2" r="0.25" fill="currentColor" stroke="none"/>
              <circle cx="10.2" cy="17.8" r="0.25" fill="currentColor" stroke="none"/>
            </svg>
            """;

    public HomeButton() {
        Html icon = new Html(ICON_SVG);
        icon.addClassName("hub-icon");

        Button button = getContent();
        button.setText("Home");
        button.setIcon(icon);
        button.addClassName("hub-home-button");
        button.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        button.addClickListener(e -> UI.getCurrent().navigate(Home.class));
    }
}
