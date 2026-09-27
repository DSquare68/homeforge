package com.github.dsquare68.homeforge.security;

import com.github.dsquare68.homeforge.page.Login;
import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;

import java.security.PublicKey;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@EnableWebSecurity
@Configuration
public class SecurityConfiguration {

    // Stable across restarts so cookies issued before a restart are still
    // honored after one - a random per-instance key would log everyone out
    // on every deploy.
    @Value("${hub.security.remember-me-key}")
    private String rememberMeKey;

    @Value("${hub.security.remember-me-validity-seconds:2592000}")
    private int rememberMeValiditySeconds;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, UserDetailsService userDetailsService) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/public/**").permitAll()
                .requestMatchers("/welcome").permitAll()
                // "/" is the context root that Vaadin routes ALL its UIDL/heartbeat
                // requests through, and is now also the dashboard route itself.
                // Restricting it to anonymous() makes every Vaadin request 403 once
                // the user logs in ("Connection lost"). Per-view access (an
                // anonymous visitor actually reaching the dashboard) is enforced by
                // Vaadin annotations (@AnonymousAllowed / @PermitAll), not here.
                .requestMatchers("/").permitAll()
        		.requestMatchers("/register").permitAll()
        		.requestMatchers("/sign-in").permitAll()
        		// Every plugin REST route lands here (see PluginControllerRegistrar).
        		// One rule for all of them, with no per-plugin override mechanism -
        		// plugins never configure their own security.
        		.requestMatchers("/api/plugins/**").authenticated());

        // Issues a persistent login cookie on every successful login (no
        // checkbox: Vaadin's LoginOverlay custom-form-area fields aren't
        // submitted with the action-based POST this form uses, see Login.java)
        // so the session survives a closed browser / server restart.
        http.rememberMe(rememberMe -> rememberMe
                .key(rememberMeKey)
                .userDetailsService(userDetailsService)
                .tokenValiditySeconds(rememberMeValiditySeconds)
                .alwaysRemember(true));

        http.with(VaadinSecurityConfigurer.vaadin(), configurer -> {
            configurer.loginView(Login.class);
            // Dashboard now lives at "/" itself.
            configurer.defaultSuccessUrl("/", true);
        });
        return http.build();
    }
}
