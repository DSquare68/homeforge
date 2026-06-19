package com.github.dsquare68.homeforge.page;

import com.github.dsquare68.homeforge.security.Roles;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;

@Route("home")
@PermitAll
public class Home extends AppLayout {

	public Home() {
		H1 title = new H1("HomeForge");
		addToNavbar(title);

		VerticalLayout content = new VerticalLayout();
		content.add(new H1("Dashboard"));
		content.add(new Paragraph("You are logged in."));
		setContent(content);
	}

}
