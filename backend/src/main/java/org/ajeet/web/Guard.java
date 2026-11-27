package org.ajeet.web;

import org.ajeet.auth.AuthService;

/** Role checks for Spring controllers, with the messages the old {@code Http} guards gave (403). */
public final class Guard {

    private Guard() {}

    public static AuthService.Account requireRole(AuthService.Account acct, String role) {
        return requireAnyRole(acct, role);
    }

    public static AuthService.Account requireAnyRole(AuthService.Account acct, String... roles) {
        for (String r : roles) if (acct.has(r)) return acct;
        throw new Forbidden(String.join(" or ", roles) + " role required");
    }
}
