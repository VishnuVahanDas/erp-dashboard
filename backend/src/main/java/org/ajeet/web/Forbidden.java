package org.ajeet.web;

/** Not allowed for this account: answers 403 with the message. Used by ported areas and the guards. */
public class Forbidden extends RuntimeException {
    public Forbidden(String message) {
        super(message);
    }
}
