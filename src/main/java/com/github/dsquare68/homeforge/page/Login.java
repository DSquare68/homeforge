package com.github.dsquare68.homeforge.page;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import com.github.dsquare68.homeforge.model.User;
import com.github.dsquare68.homeforge.repository.UserRepository;
import com.github.dsquare68.homeforge.services.UserService;
import com.vaadin.flow.component.Unit;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.login.AbstractLogin.LoginEvent;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.login.LoginI18n;
import com.vaadin.flow.component.login.LoginOverlay;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.VaadinServletResponse;
import com.vaadin.flow.server.auth.AnonymousAllowed;

@AnonymousAllowed
@Route("login")
public class Login extends VerticalLayout implements BeforeEnterObserver {

    private final LoginOverlay login = new LoginOverlay();
    
    @Autowired
    UserService userService;

    public Login(UserService userService) {
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

        login.setI18n(i18n);
        login.setOpened(true);
        // No setAction(...) here on purpose: with an action set the form does a
        // browser POST handled by Spring Security's form-login filter and the
        // LoginListener below never fires. We authenticate programmatically instead.
        login.setAction("login");
        //login.addLoginListener(e->doLogin(e));
        
        Button signUpButton = new Button("Sign Up", event -> getUI().ifPresent(ui -> ui.navigate("register")));
        signUpButton.getStyle().set("background", "1D1DD1");
        signUpButton.getStyle().set("width", "150px");
        signUpButton.getStyle().set("height", "50px");
        signUpButton.getStyle().set("margin-right", "auto");
        signUpButton.getStyle().set("margin-left", "auto");
        card.add(title, subtitle, login,signUpButton);
        add(card);
    }

    private Object doLogin(LoginEvent e) {
		User  u = userService.isPasswordValid(e.getUsername(), e.getPassword());
		if(u==null) {
			login.setError(true);
			return null;
		}else {
			// u is non-null, so the user exists and the password is valid.
			// Build an authentication token for this user and hand it to Spring Security.
			List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole()));
			Authentication authentication = new UsernamePasswordAuthenticationToken(
					u.getUsername(), null, authorities);

			SecurityContext context = SecurityContextHolder.createEmptyContext();
			context.setAuthentication(authentication);
			SecurityContextHolder.setContext(context);

			// Persist the security context to the HTTP session so the authenticated
			// user survives subsequent requests.
			SecurityContextRepository repository = new HttpSessionSecurityContextRepository();
			repository.saveContext(context,
					VaadinServletRequest.getCurrent().getHttpServletRequest(),
					VaadinServletResponse.getCurrent().getHttpServletResponse());

			// Do a full page reload (not ui.navigate) so a fresh Vaadin UI is
			// bootstrapped under the now-authenticated session. Re-using the old
			// UI created under the anonymous session causes "Connection lost".
			login.close();
			getUI().ifPresent(ui -> ui.getPage().setLocation("home"));
			return null;
		}
	}

	@Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (event.getLocation().getQueryParameters().getParameters().containsKey("error")) {
            login.setError(true);
        }
    }
}
