package com.anas.bookstore.orders;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Authorization rules with JWT enforcement on. The token itself is supplied by
 * the Spring Security test support, so no identity provider is needed: what is
 * under test is which scope may call which endpoint.
 */
@SpringBootTest(properties = {
    "security.jwt.enabled=true",
    "security.jwt.jwk-set-uri=http://127.0.0.1:9/never-called/.well-known/jwks.json",
    "spring.datasource.url=jdbc:h2:mem:orders-security;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "checkout.worker.enabled=false",
    "checkout.reconciler.enabled=false",
    "management.endpoints.web.exposure.include=health,prometheus"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigTest {
    private static final String CHECKOUT =
        "{\"customerName\":\"Secured Customer\",\"bookId\":1,\"quantity\":1}";

    @Autowired MockMvc mvc;

    private static SimpleGrantedAuthority scope(String value) {
        return new SimpleGrantedAuthority("SCOPE_" + value);
    }

    @Test
    void anonymousRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/checkout").contentType(MediaType.APPLICATION_JSON).content(CHECKOUT))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void missingScopeIsForbidden() throws Exception {
        mvc.perform(get("/api/orders").with(jwt().authorities(scope("inventory.read"))))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/checkout").contentType(MediaType.APPLICATION_JSON).content(CHECKOUT)
                .with(jwt().authorities(scope("orders.read"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void documentedScopesAreAccepted() throws Exception {
        mvc.perform(get("/api/orders").with(jwt().authorities(scope("orders.read"))))
            .andExpect(status().isOk());
        mvc.perform(post("/api/checkout").contentType(MediaType.APPLICATION_JSON).content(CHECKOUT)
                .header("Idempotency-Key", "secured-checkout-0001")
                .with(jwt().authorities(scope("orders.write"))))
            .andExpect(status().isAccepted());
    }

    @Test
    void probesAndMetricsStayPublic() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }

    @Test
    void unknownPathsAreDeniedRatherThanLeftOpen() throws Exception {
        mvc.perform(get("/api/not-a-route").with(jwt().authorities(scope("orders.write"))))
            .andExpect(status().isForbidden());
    }
}
