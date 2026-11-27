package org.ajeet.web;

import org.ajeet.auth.AuthStore;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** CORS as the old server answered it, and the {@link CurrentAccount} parameter for controllers. */
@Configuration
class WebConfig implements WebMvcConfigurer {

    private final AuthStore auth;

    WebConfig(AuthStore auth) {
        this.auth = auth;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Authorization");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentAccountResolver(auth));
    }
}
