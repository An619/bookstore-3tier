package com.anas.bookstore.orders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The direct orders API. It is the un-reserved path used for administrative
 * writes; checkout goes through /api/checkout and the outbox instead.
 * Test order is not guaranteed, so each test starts from a known seed row.
 */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class OrdersControllerTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        // Each test class owns a database: a second Spring context must not drop
        // the schema another context is still using.
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:orders-orders;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    }

    @Autowired MockMvc mvc;
    @Autowired OrdersRepository repository;

    private OrderRecord seed;

    @BeforeEach
    void ensureSeedOrder() {
        // Another test may have deleted the seed; recreate it so list/delete have a target.
        seed = repository.findAllByOrderByIdAsc().stream().findFirst()
            .orElseGet(() -> repository.save(
                new OrderRecord("Sample Customer", 1L, 1, OrderRecord.CREATED)));
    }

    @Test
    void listsOrders() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
        // The seeded order came through the direct API: CREATED, no reservation.
        mvc.perform(get("/api/orders/" + seed.getId())).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(OrderRecord.CREATED));
    }

    @Test
    void createsValidOrder() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerName\":\"Test Customer\",\"bookId\":1,\"quantity\":2}"))
            .andExpect(status().isCreated())
            // The direct API never reserves stock, so it stays CREATED.
            .andExpect(jsonPath("$.status").value(OrderRecord.CREATED));
    }

    @Test
    void rejectsInvalidOrder() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerName\":\"\",\"bookId\":1,\"quantity\":0}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void deletesExistingOrder() throws Exception {
        long id = seed.getId();
        mvc.perform(delete("/api/orders/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/orders/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void deletingUnknownOrderIsNotFound() throws Exception {
        mvc.perform(delete("/api/orders/999999")).andExpect(status().isNotFound());
    }
}
