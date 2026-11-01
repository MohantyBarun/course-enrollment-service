package com.seatsure.util;

import com.seatsure.service.Roles;

import java.security.Principal;

/**
 * The authenticated caller, taken from the access token. Controllers get it with
 * {@code @AuthenticationPrincipal CurrentUser user}; {@link #getName()} is the email, which is what
 * {@code Authentication.getName()} returns (used e.g. to scope idempotency keys).
 */
public record CurrentUser(Long id, String email, Roles role) implements Principal {

    @Override
    public String getName() {
        return email;
    }

    public boolean isAdmin() {
        return role == Roles.ROLE_ADMIN;
    }
}
