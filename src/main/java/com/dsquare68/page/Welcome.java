package com.dsquare68.page;

import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;

@Route("")
public class Welcome extends VerticalLayout {

	public Welcome() {
		add(new H1("Welcome to HomeForge!"));
		add(new Paragraph("Your one-stop solution for home management."));
	}
	
}
