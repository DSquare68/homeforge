package com.dsquare68.page;

import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;

import jakarta.annotation.security.RolesAllowed;

@RolesAllowed("USER")
@Route("home")
public class Home extends VerticalLayout {

	public Home() {
		add(new H1("Welcome to HomeForge!"));
		add(new Paragraph("Your one-stop solution for home management."));
	}
	
}
