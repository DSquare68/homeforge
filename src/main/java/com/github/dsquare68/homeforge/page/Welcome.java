package com.github.dsquare68.homeforge.page;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

@AnonymousAllowed
@Route("")
public class Welcome extends VerticalLayout {

	public Welcome() {
		setSizeFull();
		setAlignItems(Alignment.CENTER);
		setJustifyContentMode(JustifyContentMode.CENTER);
		getStyle().set("background", "var(--lumo-contrast-5pct)");

		H1 title = new H1("Welcome to HomeForge");
		title.getStyle()
				.set("margin", "0 0 0.25rem 0")
				.set("font-size", "6rem");

		Paragraph subtitle = new Paragraph("Please sign in to your account");
		Button signInButton = new Button("Sign In", event -> getUI().ifPresent(ui -> ui.navigate("login")));
		Button signUpButton = new Button("Sign Up", event -> getUI().ifPresent(ui -> ui.navigate("register"))); //getUI().ifPresent(ui -> ui.navigate(new HubDBProperties().isConfigured() ? "register" : "sign-in")));
		subtitle.getStyle()
				.set("color", "var(--lumo-secondary-text-color)")
				.set("margin", "0 0 1.5rem 0")
				.set("font-size", "4rem");
		signInButton.getStyle().set("background", "1D1DD1")
								.set("font-size", "4rem")
								.setMargin("0 0 1.5rem 0");
		signUpButton.getStyle().set("background", "1D1DD1")
								.set("font-size", "3rem");
		add(title, subtitle,signInButton, signUpButton);
	}

}
