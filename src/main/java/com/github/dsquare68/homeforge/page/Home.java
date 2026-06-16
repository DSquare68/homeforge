package com.github.dsquare68.homeforge.page;

import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Layout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

import jakarta.annotation.security.RolesAllowed;

@AnonymousAllowed
@Route("home")
@Layout
public class Home extends AppLayout {

	public Home() {

	}
	
}
