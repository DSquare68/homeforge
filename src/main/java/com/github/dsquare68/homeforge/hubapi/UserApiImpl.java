package com.github.dsquare68.homeforge.hubapi;

import java.util.Set;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.github.dsquare68.homeforge.model.User;
import com.github.dsquare68.homeforge.repository.UserRepository;
import com.github.dsquare68.homeforgeapi.user.HubUser;
import com.github.dsquare68.homeforgeapi.user.UserApi;

/**
 * Reads the authenticated user for the calling request off
 * {@link SecurityContextHolder}, the same source {@code Home} already uses
 * for its navbar, then loads the matching {@link User} row.
 *
 * <p>{@code users.id} is a JPA-generated {@code Long}, but {@link HubUser#id()}
 * is a {@code UUID} — this bridges the two with a deterministic
 * {@code new UUID(0L, id)} rather than a real random UUID. It is stable and
 * collision-free for this table, but it is a bridge, not a real identifier
 * column; changing {@code users.id} to a native UUID is a separate change.
 */
@Component
public class UserApiImpl implements UserApi {

    private final UserRepository userRepository;

    public UserApiImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public HubUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("No authenticated user in the current request context");
        }

        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated principal '" + authentication.getName() + "' has no matching user row"));

        return toHubUser(user);
    }

    @Override
    public boolean hasRole(String role) {
        if (role == null) {
            return false;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        String wanted = role.startsWith("ROLE_") ? role.substring("ROLE_".length()) : role;
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String granted = authority.getAuthority();
            if (granted.equals(wanted) || granted.equals("ROLE_" + wanted)) {
                return true;
            }
        }
        return false;
    }

    private HubUser toHubUser(User user) {
        return new HubUser(
                bridgeId(user.getId()),
                user.getUsername(),
                user.getEmail(),
                user.getFullName(),
                user.getCreatedAt(),
                user.getLastLoginAt(),
                Set.of(user.getRole()));
    }

    /** See the class Javadoc — a deterministic bridge, not a real UUID column. */
    private UUID bridgeId(Long id) {
        return new UUID(0L, id);
    }
}
