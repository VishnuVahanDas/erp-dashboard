package org.ajeet.web;

import org.ajeet.attendance.PunchFixStore;
import org.ajeet.auth.AuthStore;
import org.ajeet.leave.LeaveStore;
import org.ajeet.mbo.MboStore;
import org.ajeet.overtime.OvertimeStore;
import org.ajeet.payroll.PayrollStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Errors from Spring controllers -> the status codes and {@code {"error": "..."}} body that
 * ApiServer always gave: bad login 401, not allowed 403, invalid input 400, anything else 500.
 * One deliberate change: a body that isn't valid JSON is 400 (it was a 500).
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** The error body every endpoint answers with. */
    record ApiError(String error) {}

    static ResponseEntity<ApiError> error(int status, String message) {
        return ResponseEntity.status(status).body(new ApiError(message));
    }

    @ExceptionHandler(AuthStore.AuthException.class)
    ResponseEntity<ApiError> unauthorized(AuthStore.AuthException e) {
        return error(401, e.getMessage());
    }

    @ExceptionHandler({Http.ForbiddenException.class, LeaveStore.ForbiddenException.class,
            MboStore.ForbiddenException.class, PayrollStore.ForbiddenException.class,
            PunchFixStore.ForbiddenException.class, OvertimeStore.ForbiddenException.class})
    ResponseEntity<ApiError> forbidden(Exception e) {
        return error(403, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> badRequest(IllegalArgumentException e) {
        return error(400, e.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, tools.jackson.core.JacksonException.class})
    ResponseEntity<ApiError> unreadable(Exception e) {
        return error(400, "request body is not valid JSON");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> failed(Exception e) {
        log.error("request failed", e);
        return error(500, e.getMessage());
    }
}
