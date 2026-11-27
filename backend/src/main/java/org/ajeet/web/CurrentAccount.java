package org.ajeet.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A controller parameter {@code @CurrentAccount AuthService.Account me} is the signed-in account from
 * the Bearer token. No or an unknown/expired token answers 403 "invalid or expired session", as
 * {@code Http.requireAccount} always did. Put it first, so it's checked before the body is read.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentAccount {}
