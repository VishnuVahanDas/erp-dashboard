package org.ajeet.web;

import org.ajeet.auth.AuthStore;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Fills {@link CurrentAccount} parameters: Bearer token -> active account, else 403. */
final class CurrentAccountResolver implements HandlerMethodArgumentResolver {

    private final AuthStore auth;

    CurrentAccountResolver(AuthStore auth) {
        this.auth = auth;
    }

    @Override
    public boolean supportsParameter(MethodParameter p) {
        return p.hasParameterAnnotation(CurrentAccount.class) && p.getParameterType() == AuthStore.Account.class;
    }

    @Override
    public Object resolveArgument(MethodParameter p, ModelAndViewContainer mav, NativeWebRequest req,
                                  WebDataBinderFactory binders) throws Exception {
        String h = req.getHeader("Authorization");
        String token = (h != null && h.startsWith("Bearer ")) ? h.substring(7).trim() : null;
        AuthStore.Account acct = auth.resolve(token);
        if (acct == null) throw new Http.ForbiddenException("invalid or expired session");
        return acct;
    }
}
