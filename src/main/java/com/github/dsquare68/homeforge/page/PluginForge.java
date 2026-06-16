package com.github.dsquare68.homeforge.page;

import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;

import jakarta.annotation.security.RolesAllowed;

@RolesAllowed("USER")
@Route("plugins")
public class PluginForge extends VerticalLayout {

}
