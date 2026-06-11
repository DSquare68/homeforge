package com.github.dsquare68.homeforge.page;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

@AnonymousAllowed
@Route("register")
public class Registrasion extends VerticalLayout {

    private final TextField fullName = new TextField("Full Name");
    private final TextField username = new TextField("Username");
    private final EmailField email = new EmailField("Email");
    private final PasswordField password = new PasswordField("Password");
    private final PasswordField confirmPassword = new PasswordField("Confirm Password");

    public Registrasion() {
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

        Paragraph subtitle = new Paragraph("Create your account");
        subtitle.getStyle()
                .set("color", "var(--lumo-secondary-text-color)")
                .set("margin", "0 0 1.5rem 0");

        fullName.setWidthFull();
        fullName.setRequired(true);
        fullName.getStyle().set("margin-bottom", "0.5rem");

        username.setWidthFull();
        username.setRequired(true);
        username.getStyle().set("margin-bottom", "0.5rem");

        email.setWidthFull();
        email.setRequired(true);
        email.getStyle().set("margin-bottom", "0.5rem");

        password.setWidthFull();
        password.setRequired(true);
        password.getStyle().set("margin-bottom", "0.5rem");

        confirmPassword.setWidthFull();
        confirmPassword.setRequired(true);
        confirmPassword.getStyle().set("margin-bottom", "1rem");

        Button registerButton = new Button("Create Account", e -> handleRegister());
        registerButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        registerButton.setWidthFull();

        Paragraph loginLink = new Paragraph("Already have an account? Sign in");
        loginLink.getStyle()
                .set("color", "var(--lumo-primary-color)")
                .set("cursor", "pointer")
                .set("text-align", "center")
                .set("margin-top", "1rem");
        loginLink.addClickListener(e -> getUI().ifPresent(ui -> ui.navigate("")));

        card.add(title, subtitle, fullName, username, email, password, confirmPassword, registerButton, loginLink);
        add(card);
    }

    private void handleRegister() {
        if (fullName.isEmpty() || username.isEmpty() || email.isEmpty()
                || password.isEmpty() || confirmPassword.isEmpty()) {
            showNotification("Please fill in all fields.", NotificationVariant.LUMO_ERROR);
            return;
        }

        if (!password.getValue().equals(confirmPassword.getValue())) {
            showNotification("Passwords do not match.", NotificationVariant.LUMO_ERROR);
            confirmPassword.setInvalid(true);
            return;
        }

        confirmPassword.setInvalid(false);
        // TODO: persist user via a UserService
        showNotification("Account created! You can now sign in.", NotificationVariant.LUMO_SUCCESS);
        getUI().ifPresent(ui -> ui.navigate("login"));
    }

    private void showNotification(String message, NotificationVariant variant) {
        Notification notification = Notification.show(message, 3000, Notification.Position.TOP_CENTER);
        notification.addThemeVariants(variant);
    }
}
