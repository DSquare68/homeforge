package com.github.dsquare68.homeforge.security;

import com.github.dsquare68.homeforge.page.Login;
import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;

import java.security.PublicKey;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@EnableWebSecurity
@Configuration
public class SecurityConfiguration {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/public/**").permitAll()
                .requestMatchers("/home").permitAll()//.hasAllRoles(Roles.USER.name())
                // "/" is the context root that Vaadin routes ALL its UIDL/heartbeat
                // requests through. Restricting it to anonymous() makes every Vaadin
                // request 403 once the user logs in ("Connection lost"). Per-view access
                // is enforced by Vaadin annotations (@AnonymousAllowed / @PermitAll).
                .requestMatchers("/").permitAll()
        		.requestMatchers("/register").permitAll()
        		.requestMatchers("/sign-in").permitAll());

        http.with(VaadinSecurityConfigurer.vaadin(), configurer -> {
            configurer.loginView(Login.class);
            // Always land on /home after login. "/" is anonymous-only, so an
            // authenticated user redirected there would get 403 Forbidden.
            configurer.defaultSuccessUrl("/home", true);
        });
        return http.build();
    }
}
