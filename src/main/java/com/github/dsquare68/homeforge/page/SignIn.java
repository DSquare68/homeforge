package com.github.dsquare68.homeforge.page;

import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Properties;

import org.springframework.beans.factory.annotation.Autowired;

import com.github.dsquare68.homeforge.db.HubDBProperties;
import com.github.dsquare68.homeforge.model.User;
import com.github.dsquare68.homeforge.security.Roles;
import com.github.dsquare68.homeforge.services.UserService;
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
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

/**
 * First-run setup: creates the initial (admin) account and, if the database
 * isn't already reachable (e.g. a bare-metal/VM deploy rather than
 * docker-compose, where DB_USER/DB_PASSWORD are already resolved before this
 * page ever loads), the PostgreSQL connection details HUB will use. Only
 * reachable while no user exists yet — once the first account is created,
 * {@link #beforeEnter} routes here away to /login and normal sign-ups go
 * through {@link Registrasion} instead.
 */
@AnonymousAllowed
@Route("/sign-in")
public class SignIn extends VerticalLayout implements BeforeEnterObserver {

    private final TextField fullName = new TextField("Full Name");
    private final TextField username = new TextField("Username");
    private final EmailField email = new EmailField("Email");
    private final PasswordField password = new PasswordField("Password");
    private final PasswordField confirmPassword = new PasswordField("Confirm Password");

    private final TextField dbUser = new TextField("PostgreSQL User");
    private final PasswordField dbPassword = new PasswordField("PostgreSQL Password");
    private final TextField dbSchema = new TextField("PostgreSQL Schema");

    @Autowired
    UserService userService;
    @Autowired
    HubDBProperties hubDBProperties;

    private final boolean needsDatabaseSetup;

    public SignIn(UserService userService, HubDBProperties hubDBProperties) {
        this.needsDatabaseSetup = !hubDBProperties.isConfigured();

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

        Paragraph dbSubtitle = new Paragraph("PostgreSQL connection");
        dbSubtitle.getStyle()
                .set("color", "var(--lumo-secondary-text-color)")
                .set("margin", "0 0 0.5rem 0");

        dbUser.setWidthFull();
        dbUser.setRequired(true);
        dbUser.getStyle().set("margin-bottom", "0.5rem");

        dbPassword.setWidthFull();
        dbPassword.setRequired(true);
        dbPassword.getStyle().set("margin-bottom", "0.5rem");

        dbSchema.setWidthFull();
        dbSchema.setRequired(true);
        dbSchema.setValue("hub_schema");
        dbSchema.getStyle().set("margin-bottom", "1rem");
        
        Button registerButton = new Button("Create Account", e -> handleRegister());
        registerButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        registerButton.setWidthFull();

        Paragraph loginLink = new Paragraph("Already have an account? Sign in");
        loginLink.getStyle()
                .set("color", "var(--lumo-primary-color)")
                .set("cursor", "pointer")
                .set("text-align", "center")
                .set("margin-top", "1rem");
        loginLink.addClickListener(e -> getUI().ifPresent(ui -> ui.navigate("login")));

        card.add(title, subtitle, fullName, username, email, password, confirmPassword,
                dbSubtitle, dbUser, dbPassword, dbSchema, registerButton, loginLink);
        add(card);
    }

    private void handleRegister() {
        if (fullName.isEmpty() || username.isEmpty() || email.isEmpty()
                || password.isEmpty() || confirmPassword.isEmpty()
                || (needsDatabaseSetup && (dbUser.isEmpty() || dbPassword.isEmpty() || dbSchema.isEmpty()))) {
            showNotification("Please fill in all fields.", NotificationVariant.LUMO_ERROR);
            return;
        }

        if (!password.getValue().equals(confirmPassword.getValue())) {
            showNotification("Passwords do not match.", NotificationVariant.LUMO_ERROR);
            confirmPassword.setInvalid(true);
            return;
        }

        confirmPassword.setInvalid(false);

        if (needsDatabaseSetup) {
            if (!saveDatabaseConfig()) {
                showNotification("Failed to save database configuration.", NotificationVariant.LUMO_ERROR);
                return;
            }
            // Spring's Environment is fixed for the life of the process, so the
            // connection details just written only take effect after a restart —
            // the DB isn't usable in this running instance yet, so the account
            // can't be created until then.
            showNotification("Database configuration saved. Restart HomeForge, then sign in again to finish creating your account.",
                    NotificationVariant.LUMO_SUCCESS);
            return;
        }

        userService.addUser(new User(fullName.getValue(), username.getValue(), email.getValue(),
                password.getValue(), LocalDateTime.now(), LocalDateTime.now(), Roles.ADMIN.name(),null));
        showNotification("Account created! You can now sign in.", NotificationVariant.LUMO_SUCCESS);
        getUI().ifPresent(ui -> ui.navigate("login"));
    }

    private boolean saveDatabaseConfig() {
        Properties properties = new Properties();
        properties.setProperty("DB_USER", dbUser.getValue());
        properties.setProperty("DB_PASSWORD", dbPassword.getValue());
        properties.setProperty("DB_SCHEMA", dbSchema.getValue());
        properties.setProperty("JPA_DDL_AUTO", "update");

        try (FileOutputStream out = new FileOutputStream("src/main/resources/user.properties")) {
            properties.store(out, "PostgreSQL connection entered during first-run setup");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void showNotification(String message, NotificationVariant variant) {
        Notification notification = Notification.show(message, 3000, Notification.Position.TOP_CENTER);
        notification.addThemeVariants(variant);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        // Without a configured DB there is no way any user could already
        // exist, so skip the query entirely and let first-run setup proceed.
        if (!hubDBProperties.isConfigured()) {
            event.forwardTo("login");
        }
    }
}
