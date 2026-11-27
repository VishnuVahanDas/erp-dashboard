package org.ajeet.web;

import org.ajeet.auth.AuthStore;

/** Role checks for Spring controllers, with the messages the old {@code Http} guards gave (403). */
public final class Guard {

    private Guard() {}

    public static AuthStore.Account requireRole(AuthStore.Account acct, String role) {
        return requireAnyRole(acct, role);
    }

    public static AuthStore.Account requireAnyRole(AuthStore.Account acct, String... roles) {
        for (String r : roles) if (acct.has(r)) return acct;
        throw new Forbidden(String.join(" or ", roles) + " role required");
    }
}
