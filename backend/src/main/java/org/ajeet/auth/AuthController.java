package org.ajeet.auth;

import org.ajeet.web.CurrentAccount;
import org.ajeet.web.JsonBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** /api/auth: login, logout, the signed-in account and changing your own password. */
@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final AuthService auth;
    private final JsonBody json;

    AuthController(AuthService auth, JsonBody json) {
        this.auth = auth;
        this.json = json;
    }

    /** {email, password} -> the account + a bearer token. Wrong or disabled: 401. */
    @PostMapping("/login")
    Map<String, Object> login(@RequestBody(required = false) byte[] raw) {
        JsonNode b = json.read(raw);
        AuthService.Account acct = auth.verify(b.path("email").asString(""), b.path("password").asString(""));
        Map<String, Object> out = accountJson(acct);
        out.put("token", auth.newSession(acct.id()));
        return out;
    }

    /** Ends this token's session (if any); always ok. */
    @PostMapping("/logout")
    Map<String, Boolean> logout(@RequestHeader(value = "Authorization", required = false) String header) {
        auth.logout(header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null);
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    Map<String, Object> me(@CurrentAccount AuthService.Account me) {
        return accountJson(me);
    }

    /** {current, next}. */
    @PostMapping("/password")
    Map<String, Boolean> password(@CurrentAccount AuthService.Account me, @RequestBody(required = false) byte[] raw) {
        JsonNode b = json.read(raw);
        auth.changePassword(me.id(), b.path("current").asString(""), b.path("next").asString(""));
        return Map.of("ok", true);
    }

    /** The account as the UI sees it; never the password hash. enrollNumber only when linked. */
    private static Map<String, Object> accountJson(AuthService.Account a) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", a.id());
        out.put("email", a.email());
        out.put("roles", a.roles());
        out.put("status", a.status());
        out.put("name", a.name());
        if (a.enrollNumber() != null) out.put("enrollNumber", a.enrollNumber());
        return out;
    }
}
