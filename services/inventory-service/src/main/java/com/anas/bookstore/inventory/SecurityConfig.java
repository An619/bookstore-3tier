package com.anas.bookstore.inventory;

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
 * Bearer-token authorization for the inventory API.
 *
 * <p>Stock reads need {@code inventory.read}; reserve/release need
 * {@code inventory.write}, which is the scope the orders service presents with
 * its service credentials. Probes and metrics stay public.
 *
 * <p>Disabled by default for the local lab (see the orders service for the same
 * trade-off and the startup warning).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    @ConditionalOnProperty(name = "security.jwt.enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain openFilterChain(HttpSecurity http) throws Exception {
        log.warn("security.jwt.enabled=false: the inventory API accepts anonymous requests. "
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
                .requestMatchers(HttpMethod.GET, "/api/inventory/**").hasAuthority("SCOPE_inventory.read")
                .requestMatchers("/api/inventory/**").hasAuthority("SCOPE_inventory.write")
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
        return http.build();
    }

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
