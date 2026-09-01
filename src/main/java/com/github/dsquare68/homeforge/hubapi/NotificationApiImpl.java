package com.github.dsquare68.homeforge.hubapi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.github.dsquare68.homeforgeapi.notification.NotificationApi;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.notification.Notification;

import java.util.UUID;

/**
 * Deliberately minimal MVP implementation: logs every notification, and if
 * called from a live Vaadin request (the common case — a plugin reacting to
 * something the current user just did) also surfaces it as a toast via
 * {@link UI#getCurrent()}.
 *
 * <p>There is no persisted, cross-session notification center yet — a user
 * who is not looking at a page when {@link #notifyUser} runs simply does not
 * see it. Building that is a separate feature; this satisfies the
 * {@link NotificationApi} contract well enough for plugins to depend on
 * without inventing their own notification mechanism.
 */
@Component
public class NotificationApiImpl implements NotificationApi {

    private static final Logger log = LoggerFactory.getLogger(NotificationApiImpl.class);

    @Override
    public void notifyUser(UUID userId, String message, Severity severity) {
        log.info("[notify:{}] {} -> {}", severity, userId, message);
        showToast(message, severity);
    }

    @Override
    public void broadcast(String message, Severity severity) {
        log.info("[broadcast:{}] {}", severity, message);
        showToast(message, severity);
    }

    private void showToast(String message, Severity severity) {
        UI ui = UI.getCurrent();
        if (ui == null) {
            return;
        }
        ui.access(() -> {
            Notification notification = Notification.show(message, 4000, Notification.Position.TOP_END);
            notification.addThemeVariants(themeFor(severity));
        });
    }

    private com.vaadin.flow.component.notification.NotificationVariant themeFor(Severity severity) {
        return switch (severity) {
            case ERROR -> com.vaadin.flow.component.notification.NotificationVariant.LUMO_ERROR;
            case WARNING -> com.vaadin.flow.component.notification.NotificationVariant.LUMO_WARNING;
            case INFO -> com.vaadin.flow.component.notification.NotificationVariant.LUMO_SUCCESS;
        };
    }
}
