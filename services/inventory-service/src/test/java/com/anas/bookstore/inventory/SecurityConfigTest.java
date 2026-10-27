package com.anas.bookstore.inventory;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Authorization rules for stock reads and the reserve/release operations. */
@SpringBootTest(properties = {
    "security.jwt.enabled=true",
    "security.jwt.jwk-set-uri=http://127.0.0.1:9/never-called/.well-known/jwks.json",
    "spring.datasource.url=jdbc:h2:mem:inventory-security;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "management.endpoints.web.exposure.include=health,prometheus"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigTest {
    @Autowired MockMvc mvc;

    private static SimpleGrantedAuthority scope(String value) {
        return new SimpleGrantedAuthority("SCOPE_" + value);
    }

    @Test
    void anonymousRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/inventory")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/inventory/1/reserve").param("quantity", "1"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void readsNeedTheReadScope() throws Exception {
        mvc.perform(get("/api/inventory").with(jwt().authorities(scope("inventory.write"))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/inventory").with(jwt().authorities(scope("inventory.read"))))
            .andExpect(status().isOk());
    }

    @Test
    void mutationsNeedTheWriteScope() throws Exception {
        mvc.perform(post("/api/inventory/1/reserve").param("quantity", "1")
                .with(jwt().authorities(scope("inventory.read"))))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/inventory/1/reserve").param("quantity", "1")
                .header("Idempotency-Key", "secured-reserve-0001")
                .with(jwt().authorities(scope("inventory.write"))))
            .andExpect(status().isOk());
    }

    @Test
    void probesAndMetricsStayPublic() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }
}
