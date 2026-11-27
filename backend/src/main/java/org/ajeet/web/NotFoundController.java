package org.ajeet.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Any /api path (or method) no controller has: 404 {"error": "not found"}, the body the API always
 * gave. Spring prefers every more specific mapping over this catch-all.
 */
@RestController
class NotFoundController {

    @RequestMapping("/api/**")
    ResponseEntity<ApiExceptionHandler.ApiError> notFound() {
        return ApiExceptionHandler.error(404, "not found");
    }
}
