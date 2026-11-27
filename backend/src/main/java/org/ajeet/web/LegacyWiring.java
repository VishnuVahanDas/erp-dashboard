package org.ajeet.web;

import org.ajeet.auth.AuthService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Every area is a Spring controller now; the old dispatcher is left with no route modules, so it
 * only answers unknown /api paths (404 "not found"). Removed in the next step with the bridge.
 */
@Configuration
class LegacyWiring {

    @Bean
    ApiServer legacyApi(AuthService auth) {
        return new ApiServer(new Http(auth), List.of());
    }
}
