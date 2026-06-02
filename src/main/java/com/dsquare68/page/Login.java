package com.dsquare68.page;

import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.login.LoginI18n;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;

@Route("login")
public class Login extends VerticalLayout implements BeforeEnterObserver {

    private final LoginForm loginForm = new LoginForm();

    public Login() {
        setSizeFull();
        setAlignItems(Alignment.CENTER);
        setJustifyContentMode(JustifyContentMode.CENTER);
        getStyle().set("background", "var(--lumo-contrast-5pct)");

        VerticalLayout card = new VerticalLayout();
        card.setWidth("400px");
        card.setPadding(true);
        card.setSpacing(false);
        card.getStyle()
                .set("background", "var(--lumo-base-color)")
                .set("border-radius", "var(--lumo-border-radius-l)")
                .set("box-shadow", "var(--lumo-box-shadow-m)")
                .set("padding", "2rem");

        H1 title = new H1("HomeForge");
        title.getStyle()
                .set("margin", "0 0 0.25rem 0")
                .set("font-size", "1.75rem");

        Paragraph subtitle = new Paragraph("Sign in to your account");
        subtitle.getStyle()
                .set("color", "var(--lumo-secondary-text-color)")
                .set("margin", "0 0 1.5rem 0");

        LoginI18n i18n = LoginI18n.createDefault();
        i18n.setHeader(new LoginI18n.Header());
        i18n.getHeader().setTitle("");
        i18n.getHeader().setDescription("");

        loginForm.setI18n(i18n);
        loginForm.getStyle().set("width", "100%");
        loginForm.addLoginListener(e -> {
            boolean authenticated = authenticate(e.getUsername(), e.getPassword());
            if (authenticated) {
                getUI().ifPresent(ui -> ui.navigate(""));
            } else {
                loginForm.setError(true);
            }
        });

        card.add(title, subtitle, loginForm);
        add(card);
    }

    private boolean authenticate(String username, String password) {
        // TODO: replace with real authentication via Spring Security
        return "admin".equals(username) && "admin".equals(password);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (event.getLocation().getQueryParameters().getParameters().containsKey("error")) {
            loginForm.setError(true);
        }
    }
}
