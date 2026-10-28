package com.anas.bookstore.orders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Bearer-token authorization for the orders API.
 *
 * <p>Two modes, chosen by {@code security.jwt.enabled}:
 *
 * <ul>
 *   <li><b>disabled</b> (default, local lab): every request is allowed. This keeps
 *       `make lab`, Compose and the smoke test usable without an identity
 *       provider, and it is logged as a warning so it is never silent.</li>
 *   <li><b>enabled</b>: JWTs are validated against the configured issuer or JWKS
 *       URI and the scope claim decides access. Without a scope the request is
 *       403; without a token it is 401.</li>
 * </ul>
 *
 * <p>Scopes: {@code orders.read} for queries, {@code orders.write} for checkout
 * and order mutations. Health, info and Prometheus endpoints stay public so
 * probes and scraping never need credentials.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    @ConditionalOnProperty(name = "security.jwt.enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain openFilterChain(HttpSecurity http) throws Exception {
        log.warn("security.jwt.enabled=false: the orders API accepts anonymous requests. "
            + "Set SECURITY_JWT_ENABLED=true and an issuer/JWKS URI outside the local lab.");
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(name = "security.jwt.enabled", havingValue = "true")
    SecurityFilterChain jwtFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/orders/**").hasAuthority("SCOPE_orders.read")
                .requestMatchers("/api/checkout", "/api/orders/**").hasAuthority("SCOPE_orders.write")
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
        return http.build();
    }

    /**
     * Built here rather than by auto-configuration so a half-configured
     * deployment fails at startup instead of accepting untrusted tokens.
     */
    @Bean
    @ConditionalOnProperty(name = "security.jwt.enabled", havingValue = "true")
    JwtDecoder jwtDecoder(
        @Value("${security.jwt.issuer-uri:}") String issuerUri,
        @Value("${security.jwt.jwk-set-uri:}") String jwkSetUri
    ) {
        if (!jwkSetUri.isBlank()) {
            return NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        }
        if (!issuerUri.isBlank()) {
            return JwtDecoders.fromIssuerLocation(issuerUri);
        }
        throw new IllegalStateException(
            "security.jwt.enabled=true requires security.jwt.issuer-uri or security.jwt.jwk-set-uri");
    }
}
