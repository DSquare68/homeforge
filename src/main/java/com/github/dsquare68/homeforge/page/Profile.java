package com.github.dsquare68.homeforge.page;

import java.time.format.DateTimeFormatter;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;

import com.github.dsquare68.homeforge.component.HomeButton;
import com.github.dsquare68.homeforge.model.User;
import com.github.dsquare68.homeforge.services.UserService;
import com.vaadin.flow.component.avatar.Avatar;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import jakarta.annotation.security.PermitAll;

/**
 * Details page for the currently signed-in user. Read-only account fields
 * (username, role, timestamps) are shown alongside editable contact/profile
 * fields that plugins and future notification features can build on.
 */
@Route("profile")
@PageTitle("Profile | HomeForge")
@PermitAll
public class Profile extends VerticalLayout implements BeforeEnterObserver {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final TextField fullName = new TextField("Full Name");
    private final EmailField email = new EmailField("Email");

    @Autowired
    UserService userService;

    private final VerticalLayout mainCard;
    private User user;

    public Profile(UserService userService) {
        this.userService = userService;

        setSizeFull();
        setAlignItems(Alignment.CENTER);
        addClassName("hub-content");
        getStyle().set("background", "var(--lumo-contrast-5pct)");

        VerticalLayout card = new VerticalLayout();
        card.setWidth("500px");
        card.setPadding(true);
        card.setSpacing(false);
        card.getStyle()
                .set("background", "var(--lumo-base-color)")
                .set("border-radius", "var(--lumo-border-radius-l)")
                .set("box-shadow", "var(--lumo-box-shadow-m)")
                .set("padding", "2rem")
                .set("margin-top", "2rem");

        H1 title = new H1("Profile");
        title.getStyle().set("margin", "0 0 1.5rem 0");
        card.add(title);

        add(card);
        
        HorizontalLayout bottomBar = new HorizontalLayout(new HomeButton());
        bottomBar.setWidthFull();
        bottomBar.setJustifyContentMode(JustifyContentMode.START);
        bottomBar.getStyle().set("padding", "1rem 1rem 0 1rem")
        					.set("display","flex")
        					.set("justify-content", "center")
        					.set("align-items", "center");
        add(bottomBar);

        this.mainCard = card;
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        Optional<User> found = userService.getUser(username);
        if (found.isEmpty()) {
            event.forwardTo("home");
            return;
        }
        this.user = found.get();
        populate();
    }

    private void populate() {
        mainCard.add(identityHeader());

        fullName.setValue(safe(user.getFullName()));
        fullName.setWidthFull();
        fullName.getStyle().set("margin-bottom", "0.5rem");

        email.setValue(safe(user.getEmail()));
        email.setWidthFull();
        email.getStyle().set("margin-bottom", "0.5rem");

        Button save = new Button("Save Changes", e -> handleSave());
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        save.setWidthFull();

        mainCard.add(fullName, email, save, readOnlyDetails());
    }

    private HorizontalLayout identityHeader() {
        Avatar avatar = new Avatar(user.getFullName());
        if (user.getAvatarUrl() != null && !user.getAvatarUrl().isBlank()) {
            avatar.setImage(user.getAvatarUrl());
        }
        avatar.setHeight("64px");
        avatar.setWidth("64px");

        VerticalLayout identity = new VerticalLayout();
        identity.setPadding(false);
        identity.setSpacing(false);
        H2 name = new H2(user.getFullName());
        name.getStyle().set("margin", "0");
        Span usernameLine = new Span(user.getRole());
        usernameLine.getStyle().set("color", "var(--lumo-secondary-text-color)");
        identity.add(name, usernameLine);

        HorizontalLayout header = new HorizontalLayout(avatar, identity);
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.setSpacing(true);
        header.getStyle().set("margin-bottom", "1.5rem");
        return header;
    }

    private VerticalLayout readOnlyDetails() {
        VerticalLayout details = new VerticalLayout();
        details.setPadding(false);
        details.setSpacing(false);
        details.getStyle()
                .set("margin-top", "1rem")
                .set("padding-top", "1rem")
                .set("border-top", "1px solid var(--lumo-contrast-10pct)")
                .set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)");

        details.add(new Span("Account created: " + user.getCreatedAt().format(DATE_FORMAT)));
        details.add(new Span("Last login: " + user.getLastLoginAt().format(DATE_FORMAT)));
        return details;
    }

    private void handleSave() {
        if (fullName.isEmpty() || email.isEmpty()) {
            showNotification("Full name and email are required.", NotificationVariant.LUMO_ERROR);
            return;
        }

        user.setFullName(fullName.getValue());
        user.setEmail(email.getValue());

        userService.updateUser(user);
        showNotification("Profile updated.", NotificationVariant.LUMO_SUCCESS);
    }

    private void showNotification(String message, NotificationVariant variant) {
        Notification notification = Notification.show(message, 3000, Notification.Position.TOP_CENTER);
        notification.addThemeVariants(variant);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
